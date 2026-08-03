package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;

class NotificationRelayCoordinatorTest {

	@Test
	void 한_실행은_설정한_상한까지만_독립_이벤트_처리를_호출한다() {
		NotificationRelayExecutor executor = mock(NotificationRelayExecutor.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		properties.setMaxEventsPerRun(2);
		when(executor.processOne()).thenReturn(Optional.of(processedEvent()), Optional.of(processedEvent()));
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(0L);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, eventRepository,
			properties);

		NotificationRelayCoordinator.RelayBatchSummary summary = coordinator.processBatch();

		verify(executor, times(2)).processOne();
		assertEquals(2, summary.claimedCount());
		assertEquals(2, summary.processedCount());
		assertEquals(0, summary.retryScheduledCount());
		assertEquals(0, summary.failedCount());
	}

	@Test
	void 처리_가능한_이벤트가_없으면_첫_시도_뒤_종료한다() {
		NotificationRelayExecutor executor = mock(NotificationRelayExecutor.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		when(executor.processOne()).thenReturn(Optional.empty());
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(0L);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, eventRepository,
			properties);

		NotificationRelayCoordinator.RelayBatchSummary summary = coordinator.processBatch();

		verify(executor).processOne();
		assertEquals(0, summary.claimedCount());
		assertEquals(0, summary.processedCount());
	}

	@Test
	void 처리하거나_적체가_있으면_INFO_batch_로그에_필수_필드만_남긴다() {
		NotificationRelayExecutor executor = mock(NotificationRelayExecutor.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		properties.setMaxEventsPerRun(1);
		when(executor.processOne()).thenReturn(Optional.of(processedEvent()));
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(321L);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, eventRepository,
			properties);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			coordinator.processBatch();

			assertEquals(1, appender.list.size());
			assertEquals(Level.INFO, appender.list.getFirst().getLevel());
			String message = appender.list.getFirst().getFormattedMessage();
			assertTrue(message.contains("event=notification_outbox_relay_batch_completed claimedCount=1"));
			assertTrue(message.contains("processedCount=1 retryScheduledCount=0 failedCount=0"));
			assertTrue(message.contains("durationMs="));
			assertTrue(message.contains("oldestProcessableAgeMs=321"));
			assertNoSensitiveValue(message);
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void 처리와_적체가_모두_없으면_DEBUG_batch_로그를_남긴다() {
		NotificationRelayExecutor executor = mock(NotificationRelayExecutor.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		when(executor.processOne()).thenReturn(Optional.empty());
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(0L);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, eventRepository,
			properties);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			coordinator.processBatch();

			assertEquals(1, appender.list.size());
			assertEquals(Level.DEBUG, appender.list.getFirst().getLevel());
			String message = appender.list.getFirst().getFormattedMessage();
			assertTrue(message.contains("claimedCount=0 processedCount=0 retryScheduledCount=0 failedCount=0"));
			assertTrue(message.contains("durationMs="));
			assertTrue(message.contains("oldestProcessableAgeMs=0"));
			assertNoSensitiveValue(message);
		} finally {
			detachLogAppender(appender);
		}
	}

	private static NotificationRelayExecutor.ProcessedEvent processedEvent() {
		Instant time = Instant.parse("2026-08-03T00:00:00Z");
		return new NotificationRelayExecutor.ProcessedEvent(1L, "PARTICIPATION_JOINED", 1, time, time, 0, 0, 0, 0, 0);
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayCoordinator.class);
		logger.setLevel(Level.DEBUG);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayCoordinator.class);
		logger.detachAppender(appender);
		logger.setLevel(null);
		appender.stop();
	}

	private void assertNoSensitiveValue(String message) {
		assertFalse(message.contains("987654321"));
		assertFalse(message.contains("relay-room-title-sensitive"));
		assertFalse(message.contains("relay-payload-sensitive"));
		assertFalse(message.contains("select * from notifications"));
		assertFalse(message.contains("relay-session-sensitive"));
	}
}
