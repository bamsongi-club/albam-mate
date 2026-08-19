package cloud.bamsongi.albammate.room.service.command;

import static cloud.bamsongi.albammate.fixture.StructuredLogAssertions.fieldText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import jakarta.persistence.OptimisticLockException;

@ExtendWith(MockitoExtension.class)
class RoomParticipationCancelServiceTest {

	private static final long ROOM_ID = 7L;
	private static final long USER_ID = 42L;
	private static final Instant REQUEST_TIME = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomParticipationCancelExecutor executor;

	@Test
	void 낙관_락_충돌_뒤_다음_독립_시도가_성공하면_취소_응답을_반환한다() {
		RoomParticipationCancelService service = service();
		RoomParticipationResponse expected = response();
		when(executor.cancelParticipation(eq(USER_ID), eq(ROOM_ID), eq(REQUEST_TIME), any(Runnable.class)))
			.thenThrow(new OptimisticLockException())
			.thenReturn(expected);

		assertSame(expected, service.cancelParticipation(USER_ID, ROOM_ID));

		verify(executor, times(2)).cancelParticipation(eq(USER_ID), eq(ROOM_ID), eq(REQUEST_TIME), any(Runnable.class));
	}

	@Test
	void 세_번_모두_낙관_락_충돌이면_마지막_원인을_보존한_409를_반환한다() {
		RoomParticipationCancelService service = service();
		OptimisticLockException third = new OptimisticLockException("third");
		when(executor.cancelParticipation(eq(USER_ID), eq(ROOM_ID), eq(REQUEST_TIME), any(Runnable.class)))
			.thenThrow(new OptimisticLockException("first"))
			.thenThrow(new ObjectOptimisticLockingFailureException(Room.class, ROOM_ID))
			.thenThrow(third);

		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(
				BusinessException.class,
				() -> service.cancelParticipation(USER_ID, ROOM_ID));

			assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
			assertSame(third, exception.getCause());
			verify(executor, times(3)).cancelParticipation(
				eq(USER_ID), eq(ROOM_ID), eq(REQUEST_TIME), any(Runnable.class));
			assertRetryLogs(appender, "event=room_participation_cancel_retry roomId=7");
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 업무_오류는_재시도하지_않고_그대로_전달한다() {
		RoomParticipationCancelService service = service();
		BusinessException expected = new BusinessException(ErrorCode.PARTICIPATION_NOT_FOUND);
		when(executor.cancelParticipation(eq(USER_ID), eq(ROOM_ID), eq(REQUEST_TIME), any(Runnable.class)))
			.thenThrow(expected);

		assertSame(
			expected,
			assertThrows(
				BusinessException.class,
				() -> service.cancelParticipation(USER_ID, ROOM_ID)));

		verify(executor).cancelParticipation(eq(USER_ID), eq(ROOM_ID), eq(REQUEST_TIME), any(Runnable.class));
	}

	private RoomParticipationCancelService service() {
		return new RoomParticipationCancelService(
			executor,
			new RoomCommandExecutionCoordinator(
				Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), new RoomOptimisticLockRetrier()));
	}

	private RoomParticipationResponse response() {
		return new RoomParticipationResponse(
			ROOM_ID, ParticipationStatus.CANCELED, RoomStatus.RECRUITING, 1, 2);
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

	private void assertRetryLogs(ListAppender<ILoggingEvent> appender, String eventWithRoomId) {
		assertEquals(3, appender.list.size());
		assertEquals(Level.DEBUG, appender.list.get(0).getLevel());
		assertEquals(Level.DEBUG, appender.list.get(1).getLevel());
		assertEquals(Level.WARN, appender.list.get(2).getLevel());
		assertTrue(appender.list.stream().allMatch(
			event -> fieldText(event).contains(eventWithRoomId)));
		assertTrue(fieldText(appender.list.get(0)).contains("attempt=2"));
		assertTrue(fieldText(appender.list.get(1)).contains("attempt=3"));
		assertTrue(fieldText(appender.list.get(2)).contains("attempt=3"));
		assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
	}
}
