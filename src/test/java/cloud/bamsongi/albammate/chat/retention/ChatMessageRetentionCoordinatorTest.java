package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ChatMessageRetentionCoordinatorTest {

	private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
	private static final Instant PURGE_AFTER = Instant.parse("2026-08-01T00:00:00Z");

	@Test
	void 한_방의_실패가_앞선_성공을_되돌리지_않고_민감한_예외_본문을_로그에_남기지_않는다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionRoomProcessor processor = mock(ChatMessageRetentionRoomProcessor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxRoomsPerRun(2);
		properties.setMaxMessagesPerRun(5);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
		ChatMessageRetentionStore.DueChatRoom successfulRoom = new ChatMessageRetentionStore.DueChatRoom(
			1L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoom failedRoom = new ChatMessageRetentionStore.DueChatRoom(2L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoomCursor failedRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(failedRoom);
		when(store.findDueChatRooms(eq(NOW), isNull(), eq(2))).thenReturn(List.of(successfulRoom, failedRoom));
		when(store.findDueChatRooms(eq(NOW), eq(failedRoomCursor), eq(2))).thenReturn(List.of());
		when(processor.process(eq(successfulRoom), eq(NOW), eq(5), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 3, 3, false, false));
		when(processor.process(eq(failedRoom), eq(NOW), eq(2), any()))
			.thenThrow(new IllegalStateException("message-content-secret user=99 session=token"));
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

			assertEquals(1, summary.purgedRoomCount());
			assertEquals(3, summary.deletedMessageCount());
			assertEquals(1, summary.failureCount());
			assertFalse(summary.leaseGuardAborted());
			assertEquals(1.0, meterRegistry.get("chat.message.retention.rooms.purged").counter().count());
			assertEquals(3.0, meterRegistry.get("chat.message.retention.messages.deleted").counter().count());
			assertEquals(1.0, meterRegistry.get("chat.message.retention.failures").counter().count());
			String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
			assertFalse(logs.contains("message-content-secret"));
			assertFalse(logs.contains("session=token"));
			verify(processor).process(eq(failedRoom), eq(NOW), eq(2), any());
		} finally {
			detachLogAppender(appender);
			meterRegistry.close();
		}
	}

	@Test
	void 한_batch의_메시지_후보_예산을_방들이_공유하고_다음_batch에서_초기화한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionRoomProcessor processor = mock(ChatMessageRetentionRoomProcessor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxRoomsPerRun(2);
		properties.setMaxMessagesPerRun(5);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
		ChatMessageRetentionStore.DueChatRoom firstRoom = new ChatMessageRetentionStore.DueChatRoom(1L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoom secondRoom = new ChatMessageRetentionStore.DueChatRoom(2L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoom nextPageRoom = new ChatMessageRetentionStore.DueChatRoom(3L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoomCursor secondRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(secondRoom);
		ChatMessageRetentionStore.DueChatRoomCursor nextPageRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(nextPageRoom);
		when(store.findDueChatRooms(eq(NOW), isNull(), eq(2))).thenReturn(List.of(firstRoom, secondRoom));
		when(store.findDueChatRooms(eq(NOW), eq(secondRoomCursor), eq(2))).thenReturn(List.of(nextPageRoom));
		when(store.findDueChatRooms(eq(NOW), eq(nextPageRoomCursor), eq(2))).thenReturn(List.of());
		when(processor.process(eq(firstRoom), eq(NOW), eq(5), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(false, 5, 5, false, false),
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 1, 1, false, false));
		when(processor.process(eq(secondRoom), eq(NOW), eq(4), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 2, 2, false, false));
		when(processor.process(eq(nextPageRoom), eq(NOW), eq(5), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 1, 1, false, false));

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertEquals(3, summary.purgedRoomCount());
		assertEquals(9, summary.deletedMessageCount());
		assertEquals(9.0, meterRegistry.get("chat.message.retention.messages.deleted").counter().count());
		verify(processor, times(2)).process(eq(firstRoom), eq(NOW), eq(5), any());
		verify(processor).process(eq(secondRoom), eq(NOW), eq(4), any());
		verify(processor).process(eq(nextPageRoom), eq(NOW), eq(5), any());
		meterRegistry.close();
	}

	@Test
	void 무진행_방은_실패로_격리하고_다음_keyset_page를_계속_처리한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionRoomProcessor processor = mock(ChatMessageRetentionRoomProcessor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxRoomsPerRun(1);
		properties.setMaxMessagesPerRun(5);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
		ChatMessageRetentionStore.DueChatRoom stalledRoom = new ChatMessageRetentionStore.DueChatRoom(1L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoom successfulRoom = new ChatMessageRetentionStore.DueChatRoom(
			2L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoomCursor stalledRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(stalledRoom);
		ChatMessageRetentionStore.DueChatRoomCursor successfulRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(successfulRoom);
		when(store.findDueChatRooms(eq(NOW), isNull(), eq(1))).thenReturn(List.of(stalledRoom));
		when(store.findDueChatRooms(eq(NOW), eq(stalledRoomCursor), eq(1))).thenReturn(List.of(successfulRoom));
		when(store.findDueChatRooms(eq(NOW), eq(successfulRoomCursor), eq(1))).thenReturn(List.of());
		when(processor.process(eq(stalledRoom), eq(NOW), eq(5), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(false, 0, 0, false, false));
		when(processor.process(eq(successfulRoom), eq(NOW), eq(5), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 1, 1, false, false));

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertEquals(1, summary.purgedRoomCount());
		assertEquals(1, summary.deletedMessageCount());
		assertEquals(1, summary.failureCount());
		verify(processor).process(eq(stalledRoom), eq(NOW), eq(5), any());
		verify(processor).process(eq(successfulRoom), eq(NOW), eq(5), any());
		meterRegistry.close();
	}

	@Test
	void 완료한_방의_최대_삭제_지연을_metric으로_기록한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionRoomProcessor processor = mock(ChatMessageRetentionRoomProcessor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxRoomsPerRun(1);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
		ChatMessageRetentionStore.DueChatRoom lateRoom = new ChatMessageRetentionStore.DueChatRoom(1L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoomCursor lateRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(lateRoom);
		when(store.findDueChatRooms(eq(NOW), isNull(), eq(1))).thenReturn(List.of(lateRoom));
		when(store.findDueChatRooms(eq(NOW), eq(lateRoomCursor), eq(1))).thenReturn(List.of());
		when(processor.process(eq(lateRoom), eq(NOW), anyInt(), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 1, 1, false, false));

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertEquals(Duration.between(PURGE_AFTER, NOW).toMillis(), summary.maximumDelayMillis());
		assertEquals(1, meterRegistry.get("chat.message.retention.delay").timer().count());
		assertEquals(Duration.between(PURGE_AFTER, NOW).toMillis(),
			meterRegistry.get("chat.message.retention.delay").timer().totalTime(TimeUnit.MILLISECONDS));
		meterRegistry.close();
	}

	@Test
	void 삭제한_방이_없으면_지연을_기록하지_않는다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionRoomProcessor processor = mock(ChatMessageRetentionRoomProcessor.class);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, new ChatMessageRetentionProperties(), metrics, Clock.fixed(NOW, ZoneOffset.UTC));
		when(store.findDueChatRooms(eq(NOW), isNull(), anyInt())).thenReturn(List.of());

		coordinator.purgeExpiredMessages();

		assertEquals(0, meterRegistry.get("chat.message.retention.delay").timer().count());
		meterRegistry.close();
	}

	@Test
	void 방_처리_중_상한_도달은_실패가_아니라_실행_중단으로_기록한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionRoomProcessor processor = mock(ChatMessageRetentionRoomProcessor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxRoomsPerRun(2);
		properties.setMaxMessagesPerRun(5);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, Clock.fixed(NOW, ZoneOffset.UTC));
		ChatMessageRetentionStore.DueChatRoom slowRoom = new ChatMessageRetentionStore.DueChatRoom(1L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoom nextRoom = new ChatMessageRetentionStore.DueChatRoom(2L, PURGE_AFTER);
		when(store.findDueChatRooms(eq(NOW), isNull(), eq(2))).thenReturn(List.of(slowRoom, nextRoom));
		when(processor.process(eq(slowRoom), eq(NOW), eq(5), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(false, 2, 2, false, true));

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertTrue(summary.leaseGuardAborted());
		assertEquals(0, summary.failureCount());
		assertEquals(0, summary.purgedRoomCount());
		assertEquals(2, summary.deletedMessageCount());
		assertEquals(1.0, meterRegistry.get("chat.message.retention.lease.guard.aborted").counter().count());
		assertEquals(0.0, meterRegistry.get("chat.message.retention.failures").counter().count());
		verify(processor, never()).process(eq(nextRoom), any(), anyInt(), any());
		meterRegistry.close();
	}

	@Test
	void 실행_상한에_도달하면_다음_batch를_조회하지_않고_중단한다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionRoomProcessor processor = mock(ChatMessageRetentionRoomProcessor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxRoomsPerRun(1);
		properties.setMaxMessagesPerRun(5);
		properties.setMaxRunDuration(Duration.ofSeconds(2));
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, steppingClock(Duration.ofMillis(500)));
		ChatMessageRetentionStore.DueChatRoom firstRoom = new ChatMessageRetentionStore.DueChatRoom(1L, PURGE_AFTER);
		when(store.findDueChatRooms(any(), any(), eq(1))).thenReturn(List.of(firstRoom), List.of());
		when(processor.process(eq(firstRoom), any(), eq(5), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 1, 1, false, false));

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertTrue(summary.leaseGuardAborted());
		assertEquals(1, summary.purgedRoomCount());
		assertEquals(1.0, meterRegistry.get("chat.message.retention.lease.guard.aborted").counter().count());
		verify(store, times(1)).findDueChatRooms(any(), any(), eq(1));
		meterRegistry.close();
	}

	@Test
	void 실행_상한에_도달하면_같은_batch의_남은_방도_처리하지_않는다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionRoomProcessor processor = mock(ChatMessageRetentionRoomProcessor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxRoomsPerRun(3);
		properties.setMaxMessagesPerRun(5);
		properties.setMaxRunDuration(Duration.ofSeconds(2));
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, steppingClock(Duration.ofMillis(500)));
		ChatMessageRetentionStore.DueChatRoom firstRoom = new ChatMessageRetentionStore.DueChatRoom(1L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoom secondRoom = new ChatMessageRetentionStore.DueChatRoom(2L, PURGE_AFTER);
		ChatMessageRetentionStore.DueChatRoom thirdRoom = new ChatMessageRetentionStore.DueChatRoom(3L, PURGE_AFTER);
		when(store.findDueChatRooms(any(), any(), eq(3)))
			.thenReturn(List.of(firstRoom, secondRoom, thirdRoom), List.of());
		when(processor.process(eq(firstRoom), any(), eq(5), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 1, 1, false, false));

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertTrue(summary.leaseGuardAborted());
		assertEquals(1, summary.purgedRoomCount());
		verify(processor, never()).process(eq(secondRoom), any(), anyInt(), any());
		verify(processor, never()).process(eq(thirdRoom), any(), anyInt(), any());
		meterRegistry.close();
	}

	/** 고정 시각에서 호출마다 같은 폭으로만 진행해 실행 상한 도달 시점을 결정적으로 만든다. */
	private Clock steppingClock(Duration step) {
		return new Clock() {

			private Instant current = NOW;

			@Override
			public ZoneOffset getZone() {
				return ZoneOffset.UTC;
			}

			@Override
			public Clock withZone(java.time.ZoneId zone) {
				return this;
			}

			@Override
			public Instant instant() {
				Instant reading = current;
				current = current.plus(step);
				return reading;
			}
		};
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(ChatMessageRetentionCoordinator.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(ChatMessageRetentionCoordinator.class);
		logger.detachAppender(appender);
		appender.stop();
	}
}
