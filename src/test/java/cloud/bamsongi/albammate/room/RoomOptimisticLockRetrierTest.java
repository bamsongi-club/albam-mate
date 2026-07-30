package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntConsumer;

import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import jakarta.persistence.OptimisticLockException;

class RoomOptimisticLockRetrierTest {

	private final RoomOptimisticLockRetrier retrier = new RoomOptimisticLockRetrier();

	@Test
	void 최초_성공은_한_번만_실행하고_응답을_반환한다() {
		AtomicInteger attempts = new AtomicInteger();

		String result = retrier.execute(() -> {
			attempts.incrementAndGet();
			return "success";
		}, "room_update_retry", 7L);

		assertEquals("success", result);
		assertEquals(1, attempts.get());
	}

	@Test
	void 두_낙관락_예외를_재시도하고_hook은_다음_시도_직전에_호출한다() {
		AtomicInteger attempts = new AtomicInteger();
		IntConsumer beforeRetry = mock(IntConsumer.class);

		String result = retrier.execute(() -> {
			switch (attempts.incrementAndGet()) {
				case 1:
					throw new OptimisticLockException();
				case 2:
					throw new ObjectOptimisticLockingFailureException(Room.class, 7L);
				default:
					return "success";
			}
		}, "room_participation_retry", 7L, beforeRetry);

		assertEquals("success", result);
		assertEquals(3, attempts.get());
		verify(beforeRetry).accept(2);
		verify(beforeRetry).accept(3);
	}

	@Test
	void 업무_예외는_재시도하지_않고_그대로_전달한다() {
		AtomicInteger attempts = new AtomicInteger();
		BusinessException expected = new BusinessException(ErrorCode.ROOM_NOT_FOUND);

		BusinessException actual = assertThrows(
			BusinessException.class,
			() -> retrier.execute(() -> {
				attempts.incrementAndGet();
				throw expected;
			}, "room_update_retry", 7L));

		assertSame(expected, actual);
		assertEquals(1, attempts.get());
	}

	@Test
	void 세_번_충돌하면_마지막_원인을_보존하고_단건_재시도_로그를_남긴다() {
		OptimisticLockException first = new OptimisticLockException("first");
		OptimisticLockException second = new OptimisticLockException("second");
		OptimisticLockException third = new OptimisticLockException("third");
		AtomicInteger attempts = new AtomicInteger();
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(
				BusinessException.class,
				() -> retrier.<Void>execute(() -> {
					switch (attempts.incrementAndGet()) {
						case 1:
							throw first;
						case 2:
							throw second;
						default:
							throw third;
					}
				}, "room_cancel_retry", 7L));

			assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
			assertSame(third, exception.getCause());
			assertEquals(3, attempts.get());
			assertRetryLogs(appender, "event=room_cancel_retry roomId=7");
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void due_보정은_roomId_없이_같은_로그_계약을_사용한다() {
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			assertThrows(
				BusinessException.class,
				() -> retrier.execute(
					() -> {
						throw new OptimisticLockException();
					}, "room_state_reconciliation_retry", null));

			assertEquals("event=room_state_reconciliation_retry attempt=2",
				appender.list.get(0).getFormattedMessage());
			assertEquals("event=room_state_reconciliation_retry attempt=3",
				appender.list.get(1).getFormattedMessage());
			assertEquals("event=room_state_reconciliation_retry attempt=3",
				appender.list.get(2).getFormattedMessage());
			assertTrue(appender.list.stream().noneMatch(event -> event.getFormattedMessage().contains("roomId=")));
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

	private void assertRetryLogs(ListAppender<ILoggingEvent> appender, String eventWithRoomId) {
		assertEquals(3, appender.list.size());
		assertEquals(Level.DEBUG, appender.list.get(0).getLevel());
		assertEquals(Level.DEBUG, appender.list.get(1).getLevel());
		assertEquals(Level.WARN, appender.list.get(2).getLevel());
		assertTrue(appender.list.stream().allMatch(event -> event.getFormattedMessage().contains(eventWithRoomId)));
		assertTrue(appender.list.get(0).getFormattedMessage().contains("attempt=2"));
		assertTrue(appender.list.get(1).getFormattedMessage().contains("attempt=3"));
		assertTrue(appender.list.get(2).getFormattedMessage().contains("attempt=3"));
		assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
	}
}
