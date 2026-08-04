package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ChatMessageRetentionCoordinatorTest {

	@Test
	void 한_방의_실패가_앞선_성공을_되돌리지_않고_민감한_예외_본문을_로그에_남기지_않는다() {
		ChatMessageRetentionStore store = mock(ChatMessageRetentionStore.class);
		ChatMessageRetentionRoomProcessor processor = mock(ChatMessageRetentionRoomProcessor.class);
		ChatMessageRetentionProperties properties = new ChatMessageRetentionProperties();
		properties.setMaxRoomsPerRun(2);
		properties.setMaxMessagesPerRun(5);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		Clock clock = Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, clock);
		ChatMessageRetentionStore.DueChatRoom successfulRoom = new ChatMessageRetentionStore.DueChatRoom(
			1L, Instant.parse("2026-08-01T00:00:00Z"));
		ChatMessageRetentionStore.DueChatRoom failedRoom = new ChatMessageRetentionStore.DueChatRoom(
			2L, Instant.parse("2026-08-01T00:00:00Z"));
		ChatMessageRetentionStore.DueChatRoomCursor failedRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(failedRoom);
		when(store.findDueChatRooms(Instant.now(clock), null, 2)).thenReturn(List.of(successfulRoom, failedRoom));
		when(store.findDueChatRooms(Instant.now(clock), failedRoomCursor, 2)).thenReturn(List.of());
		when(processor.process(successfulRoom, Instant.now(clock), 5)).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 3, 3, false));
		when(processor.process(failedRoom, Instant.now(clock), 2))
			.thenThrow(new IllegalStateException("message-content-secret user=99 session=token"));
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

			assertEquals(1, summary.purgedRoomCount());
			assertEquals(3, summary.deletedMessageCount());
			assertEquals(1, summary.failureCount());
			assertEquals(1.0, meterRegistry.get("chat.message.retention.rooms.purged").counter().count());
			assertEquals(3.0, meterRegistry.get("chat.message.retention.messages.deleted").counter().count());
			assertEquals(1.0, meterRegistry.get("chat.message.retention.failures").counter().count());
			String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
			assertFalse(logs.contains("message-content-secret"));
			assertFalse(logs.contains("session=token"));
			verify(processor).process(failedRoom, Instant.now(clock), 2);
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
		Clock clock = Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, clock);
		ChatMessageRetentionStore.DueChatRoom firstRoom = new ChatMessageRetentionStore.DueChatRoom(
			1L, Instant.parse("2026-08-01T00:00:00Z"));
		ChatMessageRetentionStore.DueChatRoom secondRoom = new ChatMessageRetentionStore.DueChatRoom(
			2L, Instant.parse("2026-08-01T00:00:00Z"));
		ChatMessageRetentionStore.DueChatRoom nextPageRoom = new ChatMessageRetentionStore.DueChatRoom(
			3L, Instant.parse("2026-08-01T00:00:00Z"));
		ChatMessageRetentionStore.DueChatRoomCursor secondRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(secondRoom);
		ChatMessageRetentionStore.DueChatRoomCursor nextPageRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(nextPageRoom);
		when(store.findDueChatRooms(Instant.now(clock), null, 2)).thenReturn(List.of(firstRoom, secondRoom));
		when(store.findDueChatRooms(Instant.now(clock), secondRoomCursor, 2)).thenReturn(List.of(nextPageRoom));
		when(store.findDueChatRooms(Instant.now(clock), nextPageRoomCursor, 2)).thenReturn(List.of());
		when(processor.process(firstRoom, Instant.now(clock), 5)).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(false, 5, 5, false),
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 1, 1, false));
		when(processor.process(secondRoom, Instant.now(clock), 4)).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 2, 2, false));
		when(processor.process(nextPageRoom, Instant.now(clock), 5)).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 1, 1, false));

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertEquals(3, summary.purgedRoomCount());
		assertEquals(9, summary.deletedMessageCount());
		assertEquals(9.0, meterRegistry.get("chat.message.retention.messages.deleted").counter().count());
		verify(processor, times(2)).process(firstRoom, Instant.now(clock), 5);
		verify(processor).process(secondRoom, Instant.now(clock), 4);
		verify(processor).process(nextPageRoom, Instant.now(clock), 5);
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
		Clock clock = Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, clock);
		ChatMessageRetentionStore.DueChatRoom stalledRoom = new ChatMessageRetentionStore.DueChatRoom(
			1L, Instant.parse("2026-08-01T00:00:00Z"));
		ChatMessageRetentionStore.DueChatRoom successfulRoom = new ChatMessageRetentionStore.DueChatRoom(
			2L, Instant.parse("2026-08-01T00:00:00Z"));
		ChatMessageRetentionStore.DueChatRoomCursor stalledRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(stalledRoom);
		ChatMessageRetentionStore.DueChatRoomCursor successfulRoomCursor = ChatMessageRetentionStore.DueChatRoomCursor
			.after(successfulRoom);
		when(store.findDueChatRooms(Instant.now(clock), null, 1)).thenReturn(List.of(stalledRoom));
		when(store.findDueChatRooms(Instant.now(clock), stalledRoomCursor, 1)).thenReturn(List.of(successfulRoom));
		when(store.findDueChatRooms(Instant.now(clock), successfulRoomCursor, 1)).thenReturn(List.of());
		when(processor.process(stalledRoom, Instant.now(clock), 5)).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(false, 0, 0, false));
		when(processor.process(successfulRoom, Instant.now(clock), 5)).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 1, 1, false));

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertEquals(1, summary.purgedRoomCount());
		assertEquals(1, summary.deletedMessageCount());
		assertEquals(1, summary.failureCount());
		verify(processor).process(stalledRoom, Instant.now(clock), 5);
		verify(processor).process(successfulRoom, Instant.now(clock), 5);
		meterRegistry.close();
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
