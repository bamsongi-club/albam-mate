package cloud.bamsongi.albammate.room.service.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;

@ExtendWith(MockitoExtension.class)
class RoomWaitlistRegistrationCoordinatorTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-05T00:00:00Z");
	private static final String WAITING_QUEUE_ORDER_CONSTRAINT = "uq_room_waitlists_waiting_room_queue_order";

	@Mock
	private RoomWaitlistRegistrationExecutor executor;

	@Test
	void T5_ROOM_충돌은_같은_기준시각으로_세번까지만_재시도하고_409로_끝난다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new ObjectOptimisticLockingFailureException(Room.class, 1L));

		BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
		verify(executor, times(3)).register(7L, 1L, REQUEST_TIME);
	}

	@Test
	void T1_Hibernate가_제공한_constraint_identifier가_정확히_일치하면_세번_재시도하고_500으로_끝난다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(waitingQueueOrderConflict("database detail without constraint name"));

		BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

		assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
		verify(executor, times(3)).register(7L, 1L, REQUEST_TIME);
	}

	@Test
	void T2_다른_실제_constraint_identifier는_재시도하지_않는다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(waitingQueueOrderConflict("other_constraint", "uq_room_waitlists_waiting_room_queue_order"));

		BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

		assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
		verify(executor).register(7L, 1L, REQUEST_TIME);
	}

	@Test
	void T3_메시지에_대상_문자열이_있어도_실제_constraint_identifier가_없으면_재시도하지_않는다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new DataIntegrityViolationException("uq_room_waitlists_waiting_room_queue_order"));

		BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

		assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
		verify(executor).register(7L, 1L, REQUEST_TIME);
	}

	@Test
	void T4_ROOM_충돌과_대상_대기_순번_충돌은_하나의_세번_예산을_공유하고_마지막_대기_순번_충돌이면_500으로_끝난다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new ObjectOptimisticLockingFailureException(Room.class, 1L))
			.thenThrow(waitingQueueOrderConflict("unique conflict"));
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

			assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
			verify(executor, times(3)).register(7L, 1L, REQUEST_TIME);
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=3 reasonCode=WAITING_QUEUE_ORDER_CONFLICT", appender.list.get(0).getFormattedMessage());
		} finally {
			detachLogAppender(appender);
		}

		RoomWaitlistRegistrationCoordinator finalRoomConflictCoordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(8L), eq(1L), any(Instant.class)))
			.thenThrow(waitingQueueOrderConflict("unique conflict"))
			.thenThrow(new ObjectOptimisticLockingFailureException(Room.class, 1L));

		BusinessException finalRoomConflict = assertThrows(BusinessException.class,
			() -> finalRoomConflictCoordinator.register(8L, 1L));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, finalRoomConflict.getErrorCode());
		verify(executor, times(3)).register(8L, 1L, REQUEST_TIME);
	}

	@Test
	void T5_재시도와_소진_로그에는_허용된_식별자와_이유만_남긴다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		String sensitiveMessage = "uq_room_waitlists_waiting_room_queue_order SQL=session-token actorUserId=7";
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(waitingQueueOrderConflict(sensitiveMessage));
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

			assertEquals(3, appender.list.size());
			assertEquals(Level.WARN, appender.list.get(0).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=2 reasonCode=WAITING_QUEUE_ORDER_CONFLICT", appender.list.get(0).getFormattedMessage());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=3 reasonCode=WAITING_QUEUE_ORDER_CONFLICT", appender.list.get(1).getFormattedMessage());
			assertEquals(Level.ERROR, appender.list.get(2).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=3 reasonCode=WAITING_QUEUE_ORDER_CONFLICT_EXHAUSTED",
				appender.list.get(2).getFormattedMessage());
			assertTrue(appender.list.stream()
				.noneMatch(event -> event.getFormattedMessage().contains("actorUserId")));
			assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("SQL=")));
			assertTrue(
				appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("session-token")));
			assertTrue(
				appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains(sensitiveMessage)));
		} finally {
			detachLogAppender(appender);
		}
	}

	private DataIntegrityViolationException waitingQueueOrderConflict(String message) {
		return waitingQueueOrderConflict(WAITING_QUEUE_ORDER_CONSTRAINT, message);
	}

	private DataIntegrityViolationException waitingQueueOrderConflict(String constraintName, String message) {
		SQLException postgresException = new SQLException(message, "23505");
		ConstraintViolationException hibernateException = new ConstraintViolationException(
			"Hibernate wrapper", postgresException, "insert into room_waitlists", constraintName);
		return new DataIntegrityViolationException("Spring wrapper", hibernateException);
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomWaitlistRegistrationCoordinator.class);
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(RoomWaitlistRegistrationCoordinator.class);
		logger.detachAppender(appender);
		logger.setLevel(null);
		appender.stop();
	}
}
