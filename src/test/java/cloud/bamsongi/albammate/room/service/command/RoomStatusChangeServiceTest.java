package cloud.bamsongi.albammate.room.service.command;

import static cloud.bamsongi.albammate.fixture.StructuredLogAssertions.fieldText;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
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
import cloud.bamsongi.albammate.room.dto.RoomStatusResponse;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import jakarta.persistence.OptimisticLockException;

@ExtendWith(MockitoExtension.class)
class RoomStatusChangeServiceTest {

	private static final long USER_ID = 42L;
	private static final long ROOM_ID = 7L;
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Mock
	private RoomStatusChangeExecutor executor;

	private RoomStatusChangeService service;

	@BeforeEach
	void setUp() {
		service = new RoomStatusChangeService(
			executor,
			new RoomCommandExecutionCoordinator(
				Clock.fixed(NOW, ZoneOffset.UTC), new RoomOptimisticLockRetrier()));
	}

	@Test
	void 취소는_첫_시도_성공_응답을_반환한다() {
		RoomStatusResponse expected = new RoomStatusResponse(ROOM_ID, RoomStatus.CANCELED);
		when(executor.cancelRoom(USER_ID, ROOM_ID, NOW)).thenReturn(expected);

		RoomStatusResponse actual = service.cancelRoom(USER_ID, ROOM_ID);

		assertSame(expected, actual);
		verify(executor).cancelRoom(USER_ID, ROOM_ID, NOW);
	}

	@Test
	void 취소는_낙관_락_충돌_뒤_같은_시각으로_재시도한다() {
		RoomStatusResponse expected = new RoomStatusResponse(ROOM_ID, RoomStatus.CANCELED);
		when(executor.cancelRoom(USER_ID, ROOM_ID, NOW))
			.thenThrow(new ObjectOptimisticLockingFailureException(getClass(), ROOM_ID))
			.thenReturn(expected);

		RoomStatusResponse actual = service.cancelRoom(USER_ID, ROOM_ID);

		assertSame(expected, actual);
		verify(executor, org.mockito.Mockito.times(2)).cancelRoom(USER_ID, ROOM_ID, NOW);
	}

	@Test
	void 취소가_세_번_낙관_락_충돌하면_동시_변경_오류를_반환한다() {
		doThrow(new OptimisticLockException())
			.when(executor)
			.cancelRoom(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());

		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(
				BusinessException.class, () -> service.cancelRoom(USER_ID, ROOM_ID));

			assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
			verify(executor, org.mockito.Mockito.times(3)).cancelRoom(USER_ID, ROOM_ID, NOW);
			assertRetryLogs(appender, "event=room_cancel_retry roomId=7");
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 종료는_낙관_락_충돌_뒤_같은_시각으로_재시도한다() {
		RoomStatusResponse expected = new RoomStatusResponse(ROOM_ID, RoomStatus.FINISHED);
		when(executor.finishRoom(USER_ID, ROOM_ID, NOW))
			.thenThrow(new ObjectOptimisticLockingFailureException(getClass(), ROOM_ID))
			.thenReturn(expected);

		RoomStatusResponse actual = service.finishRoom(USER_ID, ROOM_ID);

		assertSame(expected, actual);
		verify(executor, org.mockito.Mockito.times(2)).finishRoom(USER_ID, ROOM_ID, NOW);
	}

	@Test
	void 업무_오류는_재시도하지_않는다() {
		BusinessException expected = new BusinessException(ErrorCode.FORBIDDEN);
		doThrow(expected)
			.when(executor)
			.cancelRoom(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());

		BusinessException actual = assertThrows(BusinessException.class, () -> service.cancelRoom(USER_ID, ROOM_ID));

		assertSame(expected, actual);
		verify(executor).cancelRoom(USER_ID, ROOM_ID, NOW);
	}

	@Test
	void 세_번_낙관_락_충돌하면_동시_변경_오류를_반환한다() {
		doThrow(new OptimisticLockException())
			.when(executor)
			.finishRoom(anyLong(), anyLong(), org.mockito.ArgumentMatchers.any());

		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			BusinessException exception = assertThrows(
				BusinessException.class, () -> service.finishRoom(USER_ID, ROOM_ID));

			assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exception.getErrorCode());
			verify(executor, org.mockito.Mockito.times(3)).finishRoom(USER_ID, ROOM_ID, NOW);
			assertRetryLogs(appender, "event=room_finish_retry roomId=7");
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
		assertEquals(Level.INFO, appender.list.get(0).getLevel());
		assertEquals(Level.INFO, appender.list.get(1).getLevel());
		assertEquals(Level.WARN, appender.list.get(2).getLevel());
		assertTrue(appender.list.stream().allMatch(
			event -> fieldText(event).contains(eventWithRoomId)));
		assertTrue(fieldText(appender.list.get(0)).contains("attempt=2"));
		assertTrue(fieldText(appender.list.get(1)).contains("attempt=3"));
		assertTrue(fieldText(appender.list.get(2)).contains("attempt=3"));
		assertTrue(appender.list.stream().allMatch(event -> event.getThrowableProxy() == null));
	}
}
