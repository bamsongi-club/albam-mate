package cloud.bamsongi.albammate.chat.service;

import static cloud.bamsongi.albammate.fixture.StructuredLogAssertions.assertFields;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ChatMessageCommittedListenerTest {

	private static final long ROOM_ID = 5L;
	private static final long MESSAGE_ID = 77L;

	@Test
	void 실시간_전달_성공은_지연_시간을_식별_태그_없이_기록한다() {
		ChatRealtimePublisher chatRealtimePublisher = mock(ChatRealtimePublisher.class);
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageCommittedListener listener = new ChatMessageCommittedListener(
			chatRealtimePublisher, meterRegistry);

		try {
			listener.publishAfterCommit(MessageCommitted.messageCreated(ROOM_ID, MESSAGE_ID));

			assertEquals(1, meterRegistry.get("chat.message.delivery.duration").timer().count());
			assertTrue(meterRegistry.get("chat.message.delivery.duration").timer().getId().getTags().isEmpty());
			assertEquals(0.0, meterRegistry.get("chat.message.delivery.failures").counter().count());
		} finally {
			meterRegistry.close();
		}
	}

	@Test
	void 실시간_전달_실패는_실패_건수만_증가시키고_로그에_roomId와_messageId만_남긴다() {
		ChatRealtimePublisher chatRealtimePublisher = mock(ChatRealtimePublisher.class);
		doThrow(new IllegalStateException("boom")).when(chatRealtimePublisher).publish(
			MessageCommitted.messageCreated(ROOM_ID, MESSAGE_ID));
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		ChatMessageCommittedListener listener = new ChatMessageCommittedListener(
			chatRealtimePublisher, meterRegistry);
		ListAppender<ILoggingEvent> appender = attachLogAppender();

		try {
			listener.publishAfterCommit(MessageCommitted.messageCreated(ROOM_ID, MESSAGE_ID));

			assertEquals(1.0, meterRegistry.get("chat.message.delivery.failures").counter().count());
			assertTrue(meterRegistry.get("chat.message.delivery.failures").counter().getId().getTags().isEmpty());
			assertEquals(1, appender.list.size());
			ILoggingEvent event = appender.list.getFirst();
			assertEquals(Level.WARN, event.getLevel());
			assertFields(event, java.util.Map.of(
				"event", "chat_realtime_publish_failed", "eventType", "MESSAGE_CREATED", "roomId", ROOM_ID,
				"messageId", MESSAGE_ID, "exceptionType", IllegalStateException.class.getName()));
			assertFalse(event.getFormattedMessage().contains("boom"));
		} finally {
			detachLogAppender(appender);
			meterRegistry.close();
		}
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(ChatMessageCommittedListener.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(ChatMessageCommittedListener.class);
		logger.detachAppender(appender);
		appender.stop();
	}
}
