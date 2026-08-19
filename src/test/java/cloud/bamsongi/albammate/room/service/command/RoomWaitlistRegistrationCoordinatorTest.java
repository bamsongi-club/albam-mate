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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.CannotSerializeTransactionException;
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
	void T1_직렬화_실패는_재시도하지_않고_정제_오류를_한번만_남긴다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		String sensitiveMessage = "SQL=insert constraint=unexpected session-token actorUserId=7";
		CannotSerializeTransactionException databaseFailure = new CannotSerializeTransactionException(sensitiveMessage);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(databaseFailure);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

			assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
			assertEquals(databaseFailure, exception.getCause());
			verify(executor).register(7L, 1L, REQUEST_TIME);
			assertEquals(1, appender.list.size());
			assertEquals(Level.ERROR, appender.list.get(0).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=1 reasonCode=UNEXPECTED_TECHNICAL_FAILURE sqlState=",
				appender.list.get(0).getFormattedMessage());
			assertTrue(appender.list.stream()
				.noneMatch(event -> event.getFormattedMessage().contains(sensitiveMessage)));
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void T2_비대상_무결성_위반은_기존_정제_오류를_유지한다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		DataIntegrityViolationException integrityFailure = waitingQueueOrderConflict(
			"other_constraint", "constraint=other_constraint SQL=session-token actorUserId=7");
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(integrityFailure);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

			assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
			assertEquals(integrityFailure, exception.getCause());
			verify(executor).register(7L, 1L, REQUEST_TIME);
			assertEquals(1, appender.list.size());
			assertEquals(Level.ERROR, appender.list.get(0).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=1 reasonCode=UNEXPECTED_INTEGRITY_FAILURE", appender.list.get(0).getFormattedMessage());
			assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("SQL=")));
			assertTrue(
				appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("session-token")));
		} finally {
			detachLogAppender(appender);
		}

		RoomWaitlistRegistrationCoordinator identifierlessCoordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		DataIntegrityViolationException identifierlessFailure = new DataIntegrityViolationException(
			"SQL=session-token actorUserId=8");
		when(executor.register(eq(8L), eq(1L), any(Instant.class)))
			.thenThrow(identifierlessFailure);
		ListAppender<ILoggingEvent> identifierlessAppender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class,
				() -> identifierlessCoordinator.register(8L, 1L));

			assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
			assertEquals(identifierlessFailure, exception.getCause());
			verify(executor).register(8L, 1L, REQUEST_TIME);
			assertEquals(1, identifierlessAppender.list.size());
			assertEquals(Level.ERROR, identifierlessAppender.list.get(0).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=1 reasonCode=UNEXPECTED_INTEGRITY_FAILURE",
				identifierlessAppender.list.get(0).getFormattedMessage());
		} finally {
			detachLogAppender(identifierlessAppender);
		}
	}

	@Test
	void T3_대상_대기_순번_충돌은_ROOM_충돌과_세번_예산과_기존_로그_계약을_공유한다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new ObjectOptimisticLockingFailureException(Room.class, 1L))
			.thenThrow(waitingQueueOrderConflict("SQL=session-token actorUserId=7"));
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

			assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
			verify(executor, times(3)).register(7L, 1L, REQUEST_TIME);
			assertEquals(2, appender.list.size());
			assertEquals(Level.WARN, appender.list.get(0).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=3 reasonCode=WAITING_QUEUE_ORDER_CONFLICT", appender.list.get(0).getFormattedMessage());
			assertEquals(Level.ERROR, appender.list.get(1).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=3 reasonCode=WAITING_QUEUE_ORDER_CONFLICT_EXHAUSTED",
				appender.list.get(1).getFormattedMessage());
			assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("SQL=")));
			assertTrue(
				appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("session-token")));
			assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("actorUserId")));
		} finally {
			detachLogAppender(appender);
		}

		RoomWaitlistRegistrationCoordinator pureUniqueCoordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(8L), eq(1L), any(Instant.class)))
			.thenThrow(waitingQueueOrderConflict("SQL=session-token actorUserId=8"));
		ListAppender<ILoggingEvent> pureUniqueAppender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class,
				() -> pureUniqueCoordinator.register(8L, 1L));

			assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
			verify(executor, times(3)).register(8L, 1L, REQUEST_TIME);
			assertEquals(3, pureUniqueAppender.list.size());
			assertEquals(Level.WARN, pureUniqueAppender.list.get(0).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=2 reasonCode=WAITING_QUEUE_ORDER_CONFLICT",
				pureUniqueAppender.list.get(0).getFormattedMessage());
			assertEquals(Level.WARN, pureUniqueAppender.list.get(1).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=3 reasonCode=WAITING_QUEUE_ORDER_CONFLICT",
				pureUniqueAppender.list.get(1).getFormattedMessage());
			assertEquals(Level.ERROR, pureUniqueAppender.list.get(2).getLevel());
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=3 reasonCode=WAITING_QUEUE_ORDER_CONFLICT_EXHAUSTED",
				pureUniqueAppender.list.get(2).getFormattedMessage());
			assertTrue(
				pureUniqueAppender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("SQL=")));
			assertTrue(
				pureUniqueAppender.list.stream()
					.noneMatch(event -> event.getFormattedMessage().contains("session-token")));
			assertTrue(
				pureUniqueAppender.list.stream()
					.noneMatch(event -> event.getFormattedMessage().contains("actorUserId")));
		} finally {
			detachLogAppender(pureUniqueAppender);
		}
	}

	@Test
	void T4_ROOM_낙관_락은_세번_후_409로_끝나고_일반_DB_오류를_기록하지_않는다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new ObjectOptimisticLockingFailureException(Room.class, 1L));
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

			assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
			verify(executor, times(3)).register(7L, 1L, REQUEST_TIME);
			assertTrue(appender.list.isEmpty());
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void T1_낙관_충돌_뒤_최신_업무_오류는_세번째_시도와_소진_로그보다_우선한다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		BusinessException latestBusinessException = new BusinessException(ErrorCode.WAITLIST_NOT_AVAILABLE);
		when(executor.register(eq(7L), eq(1L), any(Instant.class)))
			.thenThrow(new ObjectOptimisticLockingFailureException(Room.class, 1L))
			.thenThrow(latestBusinessException);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

			assertEquals(ErrorCode.WAITLIST_NOT_AVAILABLE, exception.getErrorCode());
			verify(executor, times(2)).register(7L, 1L, REQUEST_TIME);
			assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("EXHAUSTED")));
			assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("attempt=3")));
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void T5_lock_timeout은_재시도하지_않고_500과_LOCK_TIMEOUT을_기록한다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		CannotAcquireLockException lockTimeout = new CannotAcquireLockException(
			"lock timeout", new SQLException("lock timeout", "55P03"));
		when(executor.register(eq(7L), eq(1L), any(Instant.class))).thenThrow(lockTimeout);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

			assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
			verify(executor).register(7L, 1L, REQUEST_TIME);
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=1 reasonCode=LOCK_TIMEOUT sqlState=55P03",
				appender.list.getFirst().getFormattedMessage());
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void T6_deadlock은_재시도하지_않고_500과_DEADLOCK을_기록한다() {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		CannotAcquireLockException deadlock = new CannotAcquireLockException(
			"deadlock", new SQLException("deadlock", "40P01"));
		when(executor.register(eq(7L), eq(1L), any(Instant.class))).thenThrow(deadlock);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));

			assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
			verify(executor).register(7L, 1L, REQUEST_TIME);
			assertEquals("roomId=1 useCase=ROOM_WAITLIST_REGISTRATION "
				+ "attempt=1 reasonCode=DEADLOCK sqlState=40P01",
				appender.list.getFirst().getFormattedMessage());
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 기술적_예외의_원인_유형을_구분해_기록한다() {
		assertTechnicalReason(new SQLException("unknown state", "99999"), "UNEXPECTED_TECHNICAL_FAILURE", "99999");
		assertTechnicalReason(new DeadlockFailure(), "DEADLOCK", "");
		assertTechnicalReason(new LockTimeoutFailure(), "LOCK_TIMEOUT", "");
		assertTechnicalReason(new PessimisticLockFailure(), "LOCK_TIMEOUT", "");
	}

	private void assertTechnicalReason(Throwable cause, String expectedReason, String expectedSqlState) {
		RoomWaitlistRegistrationCoordinator coordinator = new RoomWaitlistRegistrationCoordinator(
			Clock.fixed(REQUEST_TIME, ZoneOffset.UTC), executor);
		CannotAcquireLockException failure = new CannotAcquireLockException("technical failure", cause);
		when(executor.register(eq(7L), eq(1L), any(Instant.class))).thenThrow(failure);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			assertThrows(BusinessException.class, () -> coordinator.register(7L, 1L));
			assertTrue(appender.list.getFirst().getFormattedMessage().endsWith(
				"reasonCode=" + expectedReason + " sqlState=" + expectedSqlState));
		} finally {
			detachLogAppender(appender);
		}
	}

	private static final class DeadlockFailure extends RuntimeException {}

	private static final class LockTimeoutFailure extends RuntimeException {}

	private static final class PessimisticLockFailure extends RuntimeException {}

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
