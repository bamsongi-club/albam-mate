package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
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
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(meterRegistry);
		Clock clock = Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC);
		ChatMessageRetentionCoordinator coordinator = new ChatMessageRetentionCoordinator(
			store, processor, properties, metrics, clock);
		ChatMessageRetentionStore.DueChatRoom successfulRoom = new ChatMessageRetentionStore.DueChatRoom(
			1L, Instant.parse("2026-08-01T00:00:00Z"));
		ChatMessageRetentionStore.DueChatRoom failedRoom = new ChatMessageRetentionStore.DueChatRoom(
			2L, Instant.parse("2026-08-01T00:00:00Z"));
		when(store.findDueChatRooms(Instant.now(clock), 2)).thenReturn(List.of(successfulRoom, failedRoom));
		when(processor.process(any(), any())).thenReturn(
			new ChatMessageRetentionRoomProcessor.RoomProcessResult(true, 3));
		when(processor.process(failedRoom, Instant.now(clock)))
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
		} finally {
			detachLogAppender(appender);
			meterRegistry.close();
		}
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
