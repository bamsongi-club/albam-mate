package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.notification.entity.Notification;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;

class NotificationRelayFailureRecorderTest {

	@Test
	void 재시도_기록은_안전한_분류와_다음_처리_시각을_반환한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxEventRepository.RelayFailureRecord retryRecord = failureRecord(true, false);
		when(
			eventRepository.recordRelayFailure(anyLong(), anyString(), anyString(), anyString(), anyBoolean(), anyInt(),
				anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString()))
			.thenReturn(Optional.of(retryRecord));
		NotificationRelayFailureClassifier classifier = new NotificationRelayFailureClassifier();
		NotificationRelayFailureRecorder recorder = new NotificationRelayFailureRecorder(eventRepository, classifier);

		RecordedFailureResult result = recordWithActiveSynchronization(
			recorder, NotificationRelayProcessingException.failed(10L, new IllegalStateException("temporary failure")));
		try {
			NotificationRelayFailureRecorder.RecordedFailure recordedFailure = result.recordedFailure().orElseThrow();

			assertTrue(recordedFailure.retryScheduled());
			assertEquals("RELAY_PROCESSING_FAILURE", recordedFailure.failureCode());
			assertEquals(1, recordedFailure.failureCount());
			verify(eventRepository).recordRelayFailure(
				eq(10L), eq("RELAY_PROCESSING_FAILURE"), eq("IllegalStateException"),
				eq("Notification relay processing failed"), eq(false), eq(5), eq(10L), eq(30L), eq(120L), eq(600L),
				eq(Notification.retentionPeriod().toSeconds()),
				eq(classifier.expiredClassification().failureCode()),
				eq(classifier.expiredClassification().failureClass()),
				eq(classifier.expiredClassification().sanitizedMessage()));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void 최종_격리_기록은_재시도_시각_없이_실패_결과를_반환한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxEventRepository.RelayFailureRecord failedRecord = failureRecord(false, true);
		when(
			eventRepository.recordRelayFailure(anyLong(), anyString(), anyString(), anyString(), anyBoolean(), anyInt(),
				anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString()))
			.thenReturn(Optional.of(failedRecord));
		NotificationRelayFailureRecorder recorder = new NotificationRelayFailureRecorder(
			eventRepository, new NotificationRelayFailureClassifier());

		RecordedFailureResult result = recordWithActiveSynchronization(
			recorder,
			NotificationRelayProcessingException.failed(10L, new IllegalArgumentException("unsupported type")));
		try {
			NotificationRelayFailureRecorder.RecordedFailure recordedFailure = result.recordedFailure().orElseThrow();

			assertTrue(recordedFailure.deterministicFailure());
			assertEquals("UNSUPPORTED_EVENT_TYPE", recordedFailure.failureCode());
			assertNull(recordedFailure.nextAvailableAt());
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}
	}

	@Test
	void 재시도_상태_WARN은_커밋_전에는_남기지_않고_afterCommit_뒤에_남긴다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxEventRepository.RelayFailureRecord retryRecord = failureRecord(true, false);
		when(
			eventRepository.recordRelayFailure(anyLong(), anyString(), anyString(), anyString(), anyBoolean(), anyInt(),
				anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString()))
			.thenReturn(Optional.of(retryRecord));
		NotificationRelayFailureRecorder recorder = new NotificationRelayFailureRecorder(
			eventRepository, new NotificationRelayFailureClassifier());
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			RecordedFailureResult result = recordWithActiveSynchronization(
				recorder,
				NotificationRelayProcessingException.failed(10L, new IllegalStateException("temporary failure")));

			assertTrue(appender.list.isEmpty());
			invokeAfterCommit(result.synchronizations());

			assertEquals(1, appender.list.size());
			assertEquals(Level.WARN, appender.list.getFirst().getLevel());
			String message = appender.list.getFirst().getFormattedMessage();
			assertTrue(message.contains("event=notification_outbox_relay_retry_scheduled sourceEventId=10"));
			assertFalse(message.contains("temporary failure"));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
			detachLogAppender(appender);
		}
	}

	@Test
	void 최종_실패_WARN은_커밋_전에는_남기지_않고_afterCommit_뒤에_남긴다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxEventRepository.RelayFailureRecord failedRecord = failureRecord(false, true);
		when(
			eventRepository.recordRelayFailure(anyLong(), anyString(), anyString(), anyString(), anyBoolean(), anyInt(),
				anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyString()))
			.thenReturn(Optional.of(failedRecord));
		NotificationRelayFailureRecorder recorder = new NotificationRelayFailureRecorder(
			eventRepository, new NotificationRelayFailureClassifier());
		ListAppender<ILoggingEvent> appender = attachLogAppender();
		try {
			RecordedFailureResult result = recordWithActiveSynchronization(
				recorder,
				NotificationRelayProcessingException.failed(10L, new IllegalArgumentException("unsupported type")));

			assertTrue(appender.list.isEmpty());
			invokeAfterCommit(result.synchronizations());

			assertEquals(1, appender.list.size());
			assertEquals(Level.WARN, appender.list.getFirst().getLevel());
			String message = appender.list.getFirst().getFormattedMessage();
			assertTrue(message.contains("event=notification_outbox_relay_event_failed sourceEventId=10"));
			assertFalse(message.contains("unsupported type"));
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
			detachLogAppender(appender);
		}
	}

	private NotificationOutboxEventRepository.RelayFailureRecord failureRecord(
		boolean retryScheduled,
		boolean deterministicFailure) {
		NotificationOutboxEventRepository.RelayFailureRecord record = mock(
			NotificationOutboxEventRepository.RelayFailureRecord.class);
		when(record.getSourceEventId()).thenReturn(10L);
		when(record.getEventType()).thenReturn("PARTICIPATION_JOINED");
		when(record.getFailureCode()).thenReturn(
			deterministicFailure ? "UNSUPPORTED_EVENT_TYPE" : "RELAY_PROCESSING_FAILURE");
		when(record.getFailureClass()).thenReturn(
			deterministicFailure ? "IllegalArgumentException" : "IllegalStateException");
		when(record.getFailureCount()).thenReturn(1);
		when(record.getTotalFailureCount()).thenReturn(1);
		when(record.getNextAvailableAt()).thenReturn(retryScheduled ? Instant.parse("2026-08-03T00:00:10Z") : null);
		when(record.isDeterministicFailure()).thenReturn(deterministicFailure);
		when(record.isRetryScheduled()).thenReturn(retryScheduled);
		return record;
	}

	private RecordedFailureResult recordWithActiveSynchronization(
		NotificationRelayFailureRecorder recorder,
		NotificationRelayProcessingException processingException) {
		TransactionSynchronizationManager.initSynchronization();
		Optional<NotificationRelayFailureRecorder.RecordedFailure> recordedFailure = recorder
			.record(processingException);
		return new RecordedFailureResult(recordedFailure, TransactionSynchronizationManager.getSynchronizations());
	}

	private void invokeAfterCommit(List<TransactionSynchronization> synchronizations) {
		synchronizations.forEach(TransactionSynchronization::afterCommit);
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayFailureRecorder.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayFailureRecorder.class);
		logger.detachAppender(appender);
		appender.stop();
	}

	private record RecordedFailureResult(
		Optional<NotificationRelayFailureRecorder.RecordedFailure> recordedFailure,
		List<TransactionSynchronization> synchronizations) {
	}
}
