package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
		NotificationRelayFailureRecorder failureRecorder = mock(NotificationRelayFailureRecorder.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		properties.setMaxEventsPerRun(2);
		when(executor.processOne()).thenReturn(Optional.of(processedEvent()), Optional.of(processedEvent()));
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(null);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, failureRecorder,
			eventRepository,
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
		NotificationRelayFailureRecorder failureRecorder = mock(NotificationRelayFailureRecorder.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		when(executor.processOne()).thenReturn(Optional.empty());
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(null);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, failureRecorder,
			eventRepository,
			properties);

		NotificationRelayCoordinator.RelayBatchSummary summary = coordinator.processBatch();

		verify(executor).processOne();
		assertEquals(0, summary.claimedCount());
		assertEquals(0, summary.processedCount());
	}

	@Test
	void 처리하거나_적체가_있으면_INFO_batch_로그에_필수_필드만_남긴다() {
		NotificationRelayExecutor executor = mock(NotificationRelayExecutor.class);
		NotificationRelayFailureRecorder failureRecorder = mock(NotificationRelayFailureRecorder.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		properties.setMaxEventsPerRun(1);
		when(executor.processOne()).thenReturn(Optional.of(processedEvent()));
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(321L);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, failureRecorder,
			eventRepository,
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
		NotificationRelayFailureRecorder failureRecorder = mock(NotificationRelayFailureRecorder.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		when(executor.processOne()).thenReturn(Optional.empty());
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(null);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, failureRecorder,
			eventRepository,
			properties);
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			coordinator.processBatch();

			assertEquals(1, appender.list.size());
			assertEquals(Level.DEBUG, appender.list.getFirst().getLevel());
			String message = appender.list.getFirst().getFormattedMessage();
			assertTrue(message.contains("claimedCount=0 processedCount=0 retryScheduledCount=0 failedCount=0"));
			assertTrue(message.contains("durationMs="));
			assertTrue(message.contains("oldestProcessableAgeMs=null"));
			assertNoSensitiveValue(message);
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void poison_이벤트를_기록한_뒤_같은_batch의_다음_이벤트를_처리한다() {
		NotificationRelayExecutor executor = mock(NotificationRelayExecutor.class);
		NotificationRelayFailureRecorder failureRecorder = mock(NotificationRelayFailureRecorder.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		properties.setMaxEventsPerRun(2);
		NotificationRelayProcessingException processingException = NotificationRelayProcessingException.failed(
			10L, new IllegalArgumentException("unsupported event type"));
		NotificationRelayFailureRecorder.RecordedFailure recordedFailure = new NotificationRelayFailureRecorder.RecordedFailure(
			10L, "PARTICIPATION_JOINED", "UNSUPPORTED_EVENT_TYPE", "IllegalArgumentException", 1, 1, null, true,
			false);
		when(executor.processOne()).thenThrow(processingException).thenReturn(Optional.of(processedEvent()));
		when(failureRecorder.record(processingException)).thenReturn(Optional.of(recordedFailure));
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(null);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, failureRecorder,
			eventRepository,
			properties);

		NotificationRelayCoordinator.RelayBatchSummary summary = coordinator.processBatch();

		verify(executor, times(2)).processOne();
		verify(failureRecorder).record(processingException);
		assertEquals(2, summary.claimedCount());
		assertEquals(1, summary.processedCount());
		assertEquals(0, summary.retryScheduledCount());
		assertEquals(1, summary.failedCount());
	}

	@Test
	void 선점_전_인프라_실패는_실패_기록을_요청하지_않는다() {
		NotificationRelayExecutor executor = mock(NotificationRelayExecutor.class);
		NotificationRelayFailureRecorder failureRecorder = mock(NotificationRelayFailureRecorder.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		when(executor.processOne()).thenThrow(new IllegalStateException("database unavailable"));
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, failureRecorder,
			eventRepository,
			properties);

		IllegalStateException exception = org.junit.jupiter.api.Assertions.assertThrows(
			IllegalStateException.class, coordinator::processBatch);

		assertEquals("database unavailable", exception.getMessage());
		verifyNoInteractions(failureRecorder);
	}

	@Test
	void 일시_실패를_기록하면_같은_batch_요약에_재시도_건수를_반영한다() {
		NotificationRelayExecutor executor = mock(NotificationRelayExecutor.class);
		NotificationRelayFailureRecorder failureRecorder = mock(NotificationRelayFailureRecorder.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		NotificationRelayProcessingException processingException = NotificationRelayProcessingException.failed(
			10L, new IllegalStateException("temporary failure"));
		NotificationRelayFailureRecorder.RecordedFailure recordedFailure = new NotificationRelayFailureRecorder.RecordedFailure(
			10L, "PARTICIPATION_JOINED", "RELAY_PROCESSING_FAILURE", "IllegalStateException", 1, 1,
			java.time.Instant.parse("2026-08-03T00:00:10Z"), false, true);
		when(executor.processOne()).thenThrow(processingException).thenReturn(Optional.empty());
		when(failureRecorder.record(processingException)).thenReturn(Optional.of(recordedFailure));
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(null);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, failureRecorder,
			eventRepository,
			properties);

		NotificationRelayCoordinator.RelayBatchSummary summary = coordinator.processBatch();

		assertEquals(1, summary.claimedCount());
		assertEquals(1, summary.retryScheduledCount());
		assertEquals(0, summary.failedCount());
	}

	@Test
	void 조건부_실패_기록이_대상을_찾지_못하면_실패_건수를_증가시키지_않는다() {
		NotificationRelayExecutor executor = mock(NotificationRelayExecutor.class);
		NotificationRelayFailureRecorder failureRecorder = mock(NotificationRelayFailureRecorder.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		NotificationRelayProcessingException processingException = NotificationRelayProcessingException.failed(
			10L, new IllegalStateException("late failure"));
		when(executor.processOne()).thenThrow(processingException).thenReturn(Optional.empty());
		when(failureRecorder.record(processingException)).thenReturn(Optional.empty());
		when(eventRepository.findOldestProcessableAgeMillis()).thenReturn(null);
		NotificationRelayCoordinator coordinator = new NotificationRelayCoordinator(executor, failureRecorder,
			eventRepository,
			properties);

		NotificationRelayCoordinator.RelayBatchSummary summary = coordinator.processBatch();

		verify(executor, times(2)).processOne();
		assertEquals(1, summary.claimedCount());
		assertEquals(0, summary.processedCount());
		assertEquals(0, summary.retryScheduledCount());
		assertEquals(0, summary.failedCount());
	}

	private static NotificationRelayExecutor.ProcessedEvent processedEvent() {
		return mock(NotificationRelayExecutor.ProcessedEvent.class);
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
