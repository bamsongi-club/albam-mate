package cloud.bamsongi.albammate.room.statuscorrection;

import static cloud.bamsongi.albammate.fixture.StructuredLogAssertions.fieldText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import jakarta.persistence.OptimisticLockException;

class RoomStatusCorrectionBoundedCoordinatorTest {

	private static final Instant CUTOFF = Instant.parse("2026-08-06T00:00:00Z");

	@Test
	void 한_ROOM의_실패를_격리하고_남은_ROOM을_처리한_뒤_실패_ID까지_cursor를_전진한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector selector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), selector, progressStore);
		RoomStatusCorrectionProgressStore.ProgressSnapshot claimed = snapshot(1L, null, null);
		when(selector.select(any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), eq(10)))
			.thenReturn(List.of(candidate(10L), candidate(20L), candidate(30L)), List.of());
		doThrow(new IllegalStateException("fixture failure")).when(executor).correctRoom(20L, CUTOFF);
		when(progressStore.advanceCursor(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class), anyLong()))
			.thenAnswer(invocation -> Optional.of(snapshot(
				invocation.getArgument(0, RoomStatusCorrectionProgressStore.ProgressSnapshot.class).progressVersion()
					+ 1,
				invocation.getArgument(1, Instant.class), invocation.getArgument(2, Long.class))));
		when(progressStore.wrap(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class)))
			.thenReturn(Optional.of(snapshot(5L, null, null)));

		coordinator.correctBoundedDueRooms(CUTOFF, claimed, 10, 1001);

		verify(executor).correctRoom(10L, CUTOFF);
		verify(executor).correctRoom(20L, CUTOFF);
		verify(executor).correctRoom(30L, CUTOFF);
		verify(progressStore, times(3)).advanceCursor(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class), anyLong());
		verify(progressStore).wrap(any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class));
	}

	@Test
	void bounded_상태_보정은_실패_종류별_로그_계약을_지키고_다음_ROOM과_cursor를_계속_처리한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector selector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), selector, progressStore);
		RoomStatusCorrectionProgressStore.ProgressSnapshot claimed = snapshot(1L, null, null);
		when(selector.select(any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), eq(10)))
			.thenReturn(List.of(candidate(10L), candidate(20L), candidate(30L), candidate(40L)), List.of());
		doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.doThrow(new OptimisticLockException())
			.when(executor).correctRoom(10L, CUTOFF);
		doThrow(new BusinessException(ErrorCode.ROOM_NOT_FOUND)).when(executor).correctRoom(20L, CUTOFF);
		doThrow(new IllegalStateException("로그에 남기면 안 되는 예외 메시지")).when(executor).correctRoom(30L, CUTOFF);
		when(executor.correctRoom(40L, CUTOFF)).thenReturn(true);
		when(progressStore.advanceCursor(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class), anyLong()))
			.thenAnswer(invocation -> Optional.of(snapshot(
				invocation.getArgument(0, RoomStatusCorrectionProgressStore.ProgressSnapshot.class).progressVersion()
					+ 1,
				invocation.getArgument(1, Instant.class), invocation.getArgument(2, Long.class))));
		when(progressStore.wrap(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class)))
			.thenReturn(Optional.of(snapshot(6L, null, null)));
		ListAppender<ILoggingEvent> coordinatorAppender = attachLogAppender(RoomStatusCorrectionCoordinator.class);
		ListAppender<ILoggingEvent> retrierAppender = attachLogAppender(RoomOptimisticLockRetrier.class);
		try {
			RoomStatusCorrectionCoordinator.BoundedCorrectionResult result = coordinator.correctBoundedDueRooms(
				CUTOFF, claimed, 10, 1001);

			assertEquals(1, result.changedCount());
			assertFalse(result.hasRemainingCandidates());
			verify(executor, times(3)).correctRoom(10L, CUTOFF);
			verify(executor).correctRoom(20L, CUTOFF);
			verify(executor).correctRoom(30L, CUTOFF);
			verify(executor).correctRoom(40L, CUTOFF);
			verify(progressStore, times(4)).advanceCursor(
				any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class), anyLong());
			assertEquals(4, retrierAppender.list.size());
			assertEquals(
				"event=room_state_reconciliation_retry roomId=10 attempt=2 useCase=ROOM_STATUS_CORRECTION reasonCode=OPTIMISTIC_LOCK_CONFLICT",
				fieldText(retrierAppender.list.get(0)));
			assertEquals(
				"event=room_state_reconciliation_retry roomId=10 attempt=3 useCase=ROOM_STATUS_CORRECTION reasonCode=OPTIMISTIC_LOCK_CONFLICT",
				fieldText(retrierAppender.list.get(1)));
			assertEquals(
				"event=room_state_reconciliation_retry roomId=10 attempt=3 useCase=ROOM_STATUS_CORRECTION reasonCode=OPTIMISTIC_LOCK_EXHAUSTED",
				fieldText(retrierAppender.list.get(2)));
			assertEquals(
				"event=room_state_reconciliation_retry roomId=30 attempt=1 useCase=ROOM_STATUS_CORRECTION "
					+ "reasonCode=UNEXPECTED_TECHNICAL_FAILURE sqlState=",
				retrierAppender.list.get(3).getFormattedMessage());
			assertEquals(1, coordinatorAppender.list.size());
			assertEquals(
				"event=room_status_reconciliation_room_failed roomId=30 useCase=ROOM_STATUS_CORRECTION reasonCode=UNEXPECTED_FAILURE",
				fieldText(coordinatorAppender.list.get(0)));
			assertTrue(coordinatorAppender.list.stream().noneMatch(event -> fieldText(event)
				.contains("로그에 남기면 안 되는 예외 메시지")));
		} finally {
			detachLogAppender(RoomStatusCorrectionCoordinator.class, coordinatorAppender);
			detachLogAppender(RoomOptimisticLockRetrier.class, retrierAppender);
		}
	}

	@Test
	void stale_cursor_CAS가_거절되면_남은_후보를_처리하지_않고_현재_실행을_끝낸다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector selector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), selector, progressStore);
		RoomStatusCorrectionProgressStore.ProgressSnapshot claimed = snapshot(1L, null, null);
		when(selector.select(claimed, 10)).thenReturn(List.of(candidate(10L), candidate(20L)));
		when(progressStore.advanceCursor(claimed, CUTOFF.minusSeconds(1), 10L)).thenReturn(Optional.empty());

		coordinator.correctBoundedDueRooms(CUTOFF, claimed, 10, 1001);

		verify(executor).correctRoom(10L, CUTOFF);
		verify(executor, never()).correctRoom(20L, CUTOFF);
		verify(progressStore, never()).wrap(
			any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class));
	}

	@Test
	void 실행당_배치_상한에_도달하고_잔여_후보가_있으면_cursor를_보존해_다음_claim이_같은_순회를_재개한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector selector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), selector, progressStore);
		RoomStatusCorrectionProgressStore.ProgressSnapshot firstClaim = snapshot(1L, null, null);
		RoomStatusCorrectionProgressStore.ProgressSnapshot afterFirst = snapshot(2L, CUTOFF.minusSeconds(10), 10L);
		RoomStatusCorrectionProgressStore.ProgressSnapshot afterSecond = snapshot(3L, CUTOFF.minusSeconds(20), 20L);
		RoomStatusCorrectionProgressStore.ProgressSnapshot nextClaim = snapshot(4L, CUTOFF.minusSeconds(20), 20L);
		RoomStatusCorrectionProgressStore.ProgressSnapshot afterThird = snapshot(5L, CUTOFF.minusSeconds(30), 30L);
		when(executor.correctRoom(anyLong(), eq(CUTOFF))).thenReturn(true);
		when(selector.select(firstClaim, 1)).thenReturn(List.of(candidate(10L)));
		when(selector.select(afterFirst, 1)).thenReturn(List.of(candidate(20L)));
		when(selector.select(afterSecond, 1)).thenReturn(List.of(candidate(30L)));
		when(selector.select(nextClaim, 1)).thenReturn(List.of(candidate(30L)));
		when(selector.select(afterThird, 1)).thenReturn(List.of());
		when(progressStore.advanceCursor(firstClaim, CUTOFF.minusSeconds(10), 10L)).thenReturn(Optional.of(afterFirst));
		when(progressStore.advanceCursor(afterFirst, CUTOFF.minusSeconds(20), 20L))
			.thenReturn(Optional.of(afterSecond));
		when(progressStore.advanceCursor(nextClaim, CUTOFF.minusSeconds(30), 30L))
			.thenReturn(Optional.of(afterThird));
		when(progressStore.wrap(any(RoomStatusCorrectionProgressStore.ProgressSnapshot.class), any(Instant.class)))
			.thenReturn(Optional.of(snapshot(6L, null, null)));

		RoomStatusCorrectionCoordinator.BoundedCorrectionResult firstResult = coordinator.correctBoundedDueRooms(
			CUTOFF, firstClaim, 1, 2);

		assertEquals(2, firstResult.changedCount());
		assertTrue(firstResult.hasRemainingCandidates());
		verify(executor).correctRoom(10L, CUTOFF);
		verify(executor).correctRoom(20L, CUTOFF);
		verify(executor, never()).correctRoom(30L, CUTOFF);
		verify(progressStore, never()).wrap(eq(afterSecond), eq(CUTOFF.plusNanos(1_000)));

		RoomStatusCorrectionCoordinator.BoundedCorrectionResult secondResult = coordinator.correctBoundedDueRooms(
			CUTOFF, nextClaim, 1, 2);

		assertEquals(1, secondResult.changedCount());
		assertFalse(secondResult.hasRemainingCandidates());
		verify(executor).correctRoom(30L, CUTOFF);
		verify(selector).select(afterSecond, 1);
		verify(selector).select(afterThird, 1);
		verify(progressStore).wrap(afterThird, CUTOFF.plusNanos(1_000));
	}

	@Test
	void 실행당_배치_상한을_정확히_소진하고_잔여_후보가_없으면_cursor를_wrap한다() {
		RoomStatusCorrectionExecutor executor = mock(RoomStatusCorrectionExecutor.class);
		RoomStatusCorrectionCandidateSelector selector = mock(RoomStatusCorrectionCandidateSelector.class);
		RoomStatusCorrectionProgressStore progressStore = mock(RoomStatusCorrectionProgressStore.class);
		RoomStatusCorrectionCoordinator coordinator = new RoomStatusCorrectionCoordinator(
			executor, new RoomOptimisticLockRetrier(), selector, progressStore);
		RoomStatusCorrectionProgressStore.ProgressSnapshot claimed = snapshot(1L, null, null);
		RoomStatusCorrectionProgressStore.ProgressSnapshot afterFirst = snapshot(2L, CUTOFF.minusSeconds(10), 10L);
		RoomStatusCorrectionProgressStore.ProgressSnapshot afterSecond = snapshot(3L, CUTOFF.minusSeconds(20), 20L);
		when(executor.correctRoom(anyLong(), eq(CUTOFF))).thenReturn(true);
		when(selector.select(claimed, 1)).thenReturn(List.of(candidate(10L)));
		when(selector.select(afterFirst, 1)).thenReturn(List.of(candidate(20L)));
		when(selector.select(afterSecond, 1)).thenReturn(List.of());
		when(progressStore.advanceCursor(claimed, CUTOFF.minusSeconds(10), 10L)).thenReturn(Optional.of(afterFirst));
		when(progressStore.advanceCursor(afterFirst, CUTOFF.minusSeconds(20), 20L))
			.thenReturn(Optional.of(afterSecond));
		when(progressStore.wrap(afterSecond, CUTOFF.plusNanos(1_000)))
			.thenReturn(Optional.of(snapshot(4L, null, null)));

		RoomStatusCorrectionCoordinator.BoundedCorrectionResult result = coordinator.correctBoundedDueRooms(
			CUTOFF, claimed, 1, 2);

		assertEquals(2, result.changedCount());
		assertFalse(result.hasRemainingCandidates());
		verify(executor).correctRoom(10L, CUTOFF);
		verify(executor).correctRoom(20L, CUTOFF);
		verify(selector).select(afterSecond, 1);
		verify(progressStore).wrap(afterSecond, CUTOFF.plusNanos(1_000));
	}

	private RoomStatusCorrectionCandidateSelector.DueRoomCandidate candidate(long roomId) {
		return new RoomStatusCorrectionCandidateSelector.DueRoomCandidate(roomId, CUTOFF.minusSeconds(roomId));
	}

	private RoomStatusCorrectionProgressStore.ProgressSnapshot snapshot(
		long version, Instant cursorDueAt, Long cursorRoomId) {
		return new RoomStatusCorrectionProgressStore.ProgressSnapshot(
			CUTOFF, cursorDueAt, cursorRoomId, version, 1L);
	}

	private ListAppender<ILoggingEvent> attachLogAppender(Class<?> loggerType) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(loggerType);
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(Class<?> loggerType, ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(loggerType);
		logger.detachAppender(appender);
		logger.setLevel(null);
		appender.stop();
	}
}
