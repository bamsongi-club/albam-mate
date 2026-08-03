package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.util.Optional;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;

class NotificationRelayFailureRecorderTest {

	@Test
	void 재시도_기록은_안전한_분류와_다음_처리_시각을_반환한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxEventRepository.RelayFailureRecord retryRecord = failureRecord(true, false);
		when(
			eventRepository.recordRelayFailure(anyLong(), anyString(), anyString(), anyString(), anyBoolean(), anyInt(),
				anyLong(), anyLong(), anyLong(), anyLong()))
			.thenReturn(Optional.of(retryRecord));
		NotificationRelayFailureRecorder recorder = new NotificationRelayFailureRecorder(
			eventRepository, new NotificationRelayFailureClassifier());

		NotificationRelayFailureRecorder.RecordedFailure recordedFailure = recorder.record(
			NotificationRelayProcessingException.failed(10L, new IllegalStateException("temporary failure")))
			.orElseThrow();

		assertTrue(recordedFailure.retryScheduled());
		assertEquals("RELAY_PROCESSING_FAILURE", recordedFailure.failureCode());
		assertEquals(1, recordedFailure.failureCount());
		verify(eventRepository).recordRelayFailure(
			eq(10L), eq("RELAY_PROCESSING_FAILURE"), eq("IllegalStateException"),
			eq("Notification relay processing failed"), eq(false), eq(5), eq(10L), eq(30L), eq(120L), eq(600L));
	}

	@Test
	void 최종_격리_기록은_재시도_시각_없이_실패_결과를_반환한다() {
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		NotificationOutboxEventRepository.RelayFailureRecord failedRecord = failureRecord(false, true);
		when(
			eventRepository.recordRelayFailure(anyLong(), anyString(), anyString(), anyString(), anyBoolean(), anyInt(),
				anyLong(), anyLong(), anyLong(), anyLong()))
			.thenReturn(Optional.of(failedRecord));
		NotificationRelayFailureRecorder recorder = new NotificationRelayFailureRecorder(
			eventRepository, new NotificationRelayFailureClassifier());

		NotificationRelayFailureRecorder.RecordedFailure recordedFailure = recorder.record(
			NotificationRelayProcessingException.failed(10L, new IllegalArgumentException("unsupported type")))
			.orElseThrow();

		assertTrue(recordedFailure.deterministicFailure());
		assertEquals("UNSUPPORTED_EVENT_TYPE", recordedFailure.failureCode());
		assertNull(recordedFailure.nextAvailableAt());
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
}
