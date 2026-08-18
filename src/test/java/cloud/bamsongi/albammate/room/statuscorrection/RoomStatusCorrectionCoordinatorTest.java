package cloud.bamsongi.albammate.room.statuscorrection;

import static cloud.bamsongi.albammate.fixture.StructuredLogAssertions.fieldText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.OptimisticLockException;

class RoomStatusCorrectionCoordinatorTest {

	private static final Long ROOM_ID = 10L;
	private static final Instant REQUEST_TIME = Instant.parse("2026-07-27T00:00:00Z");

	@Test
	void T2_보정_결과는_roomId없이_유한_outcome_metric으로_기록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector candidateSelector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionProgressStore.ProgressSnapshot progress = mock(
			RoomStatusCorrectionProgressStore.ProgressSnapshot.class);
		when(progress.turnCutoff()).thenReturn(REQUEST_TIME.minusSeconds(1));
		when(candidateSelector.select(progress, 10)).thenReturn(List.of());
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), candidateSelector, progressStore);
		try {
			RoomStatusCorrectionCoordinator.BoundedCorrectionResult result = coordinator.correctBoundedDueRooms(
				REQUEST_TIME, progress, 10, 1);

			assertEquals(0, result.changedCount());
			assertEquals(1.0, registry.find("room.status.correction.runs").tag("outcome", "completed").counter()
				.count());
			assertTrue(registry.find("room.status.correction.runs").meters().stream()
				.allMatch(meter -> meter.getId().getTags().stream().allMatch(tag -> "outcome".equals(tag.getKey()))));
		} finally {
			Metrics.removeRegistry(registry);
			registry.close();
		}
	}

	@Test
	void T2_상한도달_run은_completed와_중복집계하지_않는다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector candidateSelector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionProgressStore.ProgressSnapshot progress = mock(
			RoomStatusCorrectionProgressStore.ProgressSnapshot.class);
		RoomStatusCorrectionCandidateSelector.DueRoomCandidate candidate = new RoomStatusCorrectionCandidateSelector.DueRoomCandidate(
			10L, REQUEST_TIME.minusSeconds(2));
		when(progress.turnCutoff()).thenReturn(REQUEST_TIME.minusSeconds(1));
		when(candidateSelector.select(progress, 10)).thenReturn(List.of(candidate));
		when(candidateSelector.select(progress, 1)).thenReturn(List.of(candidate));
		when(progressStore.advanceCursor(progress, candidate.dueAt(), candidate.roomId()))
			.thenReturn(Optional.of(progress));
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), candidateSelector, progressStore);
		try {
			coordinator.correctBoundedDueRooms(REQUEST_TIME, progress, 10, 1);

			assertEquals(0.0, registry.find("room.status.correction.runs").tag("outcome", "completed").counter()
				.count());
			assertEquals(1.0, registry.find("room.status.correction.runs").tag("outcome", "batch_limit").counter()
				.count());
		} finally {
			Metrics.removeRegistry(registry);
			registry.close();
		}
	}

	@Test
	void T2_부분_ROOM_실패가_있으면_전체_run은_failed_하나로_기록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Metrics.addRegistry(registry);
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector candidateSelector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionProgressStore.ProgressSnapshot progress = mock(
			RoomStatusCorrectionProgressStore.ProgressSnapshot.class);
		RoomStatusCorrectionCandidateSelector.DueRoomCandidate candidate = new RoomStatusCorrectionCandidateSelector.DueRoomCandidate(
			10L, REQUEST_TIME.minusSeconds(2));
		when(progress.turnCutoff()).thenReturn(REQUEST_TIME.minusSeconds(1));
		when(candidateSelector.select(progress, 10)).thenReturn(List.of(candidate));
		when(candidateSelector.select(progress, 1)).thenReturn(List.of());
		when(progressStore.advanceCursor(progress, candidate.dueAt(), candidate.roomId()))
			.thenReturn(Optional.of(progress));
		doThrow(new IllegalStateException("room correction failure")).when(executor).correctRoom(10L, REQUEST_TIME);
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), candidateSelector, progressStore);
		try {
			coordinator.correctBoundedDueRooms(REQUEST_TIME, progress, 10, 1);

			assertEquals(1.0, registry.find("room.status.correction.runs").tag("outcome", "failed").counter()
				.count());
			assertEquals(0.0, registry.find("room.status.correction.runs").tag("outcome", "completed").counter()
				.count());
		} finally {
			Metrics.removeRegistry(registry);
			registry.close();
		}
	}

	@Test
	void 낙관락_충돌_후_성공하면_한_번만_재시도한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCoordinator coordinator = coordinator(executor);
		OptimisticLockException conflict = new OptimisticLockException();
		doThrow(conflict).doReturn(false).when(executor).correctRoom(ROOM_ID, REQUEST_TIME);

		coordinator.correctRoom(ROOM_ID, REQUEST_TIME);

		verify(executor, times(2)).correctRoom(ROOM_ID, REQUEST_TIME);
	}

	@Test
	void 세_번_충돌하면_오류코드와_마지막_cause를_보존한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCoordinator coordinator = coordinator(executor);
		OptimisticLockException first = new OptimisticLockException("first");
		OptimisticLockException second = new OptimisticLockException("second");
		OptimisticLockException third = new OptimisticLockException("third");
		doThrow(first)
			.doThrow(second)
			.doThrow(third)
			.when(executor)
			.correctRoom(ROOM_ID, REQUEST_TIME);

		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(
				BusinessException.class,
				() -> coordinator.correctRoom(ROOM_ID, REQUEST_TIME));

			assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
			assertSame(third, exception.getCause());
			verify(executor, times(3)).correctRoom(ROOM_ID, REQUEST_TIME);
			assertEquals(3, appender.list.size());
			assertEquals(Level.DEBUG, appender.list.get(0).getLevel());
			assertEquals(Level.DEBUG, appender.list.get(1).getLevel());
			assertEquals(Level.WARN, appender.list.get(2).getLevel());
			assertEquals(
				"event=room_state_reconciliation_retry roomId=10 attempt=2 useCase=ROOM_STATUS_CORRECTION reasonCode=OPTIMISTIC_LOCK_CONFLICT",
				fieldText(appender.list.get(0)));
			assertEquals(
				"event=room_state_reconciliation_retry roomId=10 attempt=3 useCase=ROOM_STATUS_CORRECTION reasonCode=OPTIMISTIC_LOCK_CONFLICT",
				fieldText(appender.list.get(1)));
			assertEquals(
				"event=room_state_reconciliation_retry roomId=10 attempt=3 useCase=ROOM_STATUS_CORRECTION reasonCode=OPTIMISTIC_LOCK_EXHAUSTED",
				fieldText(appender.list.get(2)));
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void Spring_낙관락_충돌_후_성공하면_동일한_요청시각으로_재시도한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCoordinator coordinator = coordinator(executor);
		ObjectOptimisticLockingFailureException conflict = new ObjectOptimisticLockingFailureException(Room.class,
			ROOM_ID);
		doThrow(conflict).doReturn(false).when(executor).correctRoom(ROOM_ID, REQUEST_TIME);

		coordinator.correctRoom(ROOM_ID, REQUEST_TIME);

		ArgumentCaptor<Instant> requestTimes = ArgumentCaptor.forClass(Instant.class);
		verify(executor, times(2)).correctRoom(eq(ROOM_ID), requestTimes.capture());
		requestTimes.getAllValues().forEach(requestTime -> assertSame(REQUEST_TIME, requestTime));
	}

	@Test
	void Spring_낙관락이_세_번_충돌하면_오류와_마지막_cause를_보존한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCoordinator coordinator = coordinator(executor);
		ObjectOptimisticLockingFailureException first = new ObjectOptimisticLockingFailureException(Room.class,
			ROOM_ID);
		ObjectOptimisticLockingFailureException second = new ObjectOptimisticLockingFailureException(Room.class,
			ROOM_ID);
		ObjectOptimisticLockingFailureException third = new ObjectOptimisticLockingFailureException(Room.class,
			ROOM_ID);
		doThrow(first)
			.doThrow(second)
			.doThrow(third)
			.when(executor)
			.correctRoom(ROOM_ID, REQUEST_TIME);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> coordinator.correctRoom(ROOM_ID, REQUEST_TIME));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		assertSame(third, exception.getCause());
		verify(executor, times(3)).correctRoom(ROOM_ID, REQUEST_TIME);
	}

	@Test
	void 낙관락이_아닌_업무_예외는_재시도하지_않고_그대로_전달한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCoordinator coordinator = coordinator(executor);
		BusinessException businessException = new BusinessException(ErrorCode.ROOM_NOT_FOUND);
		doThrow(businessException).when(executor).correctRoom(ROOM_ID, REQUEST_TIME);

		BusinessException thrown = assertThrows(
			BusinessException.class,
			() -> coordinator.correctRoom(ROOM_ID, REQUEST_TIME));

		assertSame(businessException, thrown);
		verify(executor).correctRoom(ROOM_ID, REQUEST_TIME);
	}

	@Test
	void 재시도마다_동일한_요청_시각을_전달한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCoordinator coordinator = coordinator(executor);
		when(executor.correctDueRooms(REQUEST_TIME))
			.thenThrow(new OptimisticLockException())
			.thenReturn(0);

		coordinator.correctDueRooms(REQUEST_TIME);

		ArgumentCaptor<Instant> requestTimes = ArgumentCaptor.forClass(Instant.class);
		verify(executor, times(2)).correctDueRooms(requestTimes.capture());
		requestTimes.getAllValues().forEach(requestTime -> assertSame(REQUEST_TIME, requestTime));
		verify(executor, times(2)).correctDueRooms(eq(REQUEST_TIME));
	}

	@Test
	void 스케줄러_재시도_hook은_충돌_뒤_두번째와_세번째_시도_전에만_호출된다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCoordinator coordinator = coordinator(executor);
		IntConsumer beforeRetry = mock(IntConsumer.class);
		doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.when(executor)
			.correctDueRooms(REQUEST_TIME);

		assertThrows(
			BusinessException.class,
			() -> coordinator.correctDueRooms(REQUEST_TIME, beforeRetry));

		verify(beforeRetry).accept(2);
		verify(beforeRetry).accept(3);
		verify(executor, times(3)).correctDueRooms(REQUEST_TIME);
	}

	@Test
	void 공개_due_보정은_지연_hook_없이_기존처럼_세_번_즉시_재시도한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCoordinator coordinator = coordinator(executor);
		doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.when(executor)
			.correctDueRooms(REQUEST_TIME);

		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			assertThrows(BusinessException.class, () -> coordinator.correctDueRooms(REQUEST_TIME));

			verify(executor, times(3)).correctDueRooms(REQUEST_TIME);
			assertEquals(3, appender.list.size());
			assertEquals(Level.DEBUG, appender.list.get(0).getLevel());
			assertEquals(Level.DEBUG, appender.list.get(1).getLevel());
			assertEquals(Level.WARN, appender.list.get(2).getLevel());
			assertEquals(
				"event=room_state_reconciliation_retry attempt=2 useCase=ROOM_STATUS_CORRECTION reasonCode=OPTIMISTIC_LOCK_CONFLICT",
				fieldText(appender.list.get(0)));
			assertEquals(
				"event=room_state_reconciliation_retry attempt=3 useCase=ROOM_STATUS_CORRECTION reasonCode=OPTIMISTIC_LOCK_CONFLICT",
				fieldText(appender.list.get(1)));
			assertEquals(
				"event=room_state_reconciliation_retry attempt=3 useCase=ROOM_STATUS_CORRECTION reasonCode=OPTIMISTIC_LOCK_EXHAUSTED",
				fieldText(appender.list.get(2)));
			assertTrue(appender.list.stream().noneMatch(event -> fieldText(event).contains("roomId=")));
		} finally {
			detachLogAppender(appender);
		}
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomOptimisticLockRetrier.class);
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomOptimisticLockRetrier.class);
		logger.detachAppender(appender);
		logger.setLevel(null);
		appender.stop();
	}

	private RoomStatusCorrectionCoordinator coordinator(RoomStatusCorrectionExecutor executor) {
		return new RoomStatusCorrectionCoordinator(
			executor,
			new RoomOptimisticLockRetrier(),
			mock(RoomStatusCorrectionCandidateSelector.class),
			mock(RoomStatusCorrectionProgressStore.class));
	}
}
