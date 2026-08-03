package cloud.bamsongi.albammate.notification.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.notification.relay.NotificationRelayFailureClassifier.FailureClassification;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 롤백된 이벤트만 PostgreSQL 시각 기준의 별도 트랜잭션에서 실패 상태로 기록한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationRelayFailureRecorder {

	private static final int MAX_AUTOMATIC_ATTEMPTS = 5;
	private static final Duration FIRST_RETRY_DELAY = Duration.ofSeconds(10);
	private static final Duration SECOND_RETRY_DELAY = Duration.ofSeconds(30);
	private static final Duration THIRD_RETRY_DELAY = Duration.ofMinutes(2);
	private static final Duration FOURTH_RETRY_DELAY = Duration.ofMinutes(10);

	@NonNull private final NotificationOutboxEventRepository eventRepository;
	@NonNull private final NotificationRelayFailureClassifier failureClassifier;

	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<RecordedFailure> record(NotificationRelayProcessingException processingException) {
		FailureClassification classification = failureClassifier.classify(processingException);
		Optional<NotificationOutboxEventRepository.RelayFailureRecord> storedFailure = eventRepository
			.recordRelayFailure(
				processingException.getSourceEventId(),
				classification.failureCode(),
				classification.failureClass(),
				classification.sanitizedMessage(),
				classification.deterministic(),
				MAX_AUTOMATIC_ATTEMPTS,
				FIRST_RETRY_DELAY.toSeconds(),
				SECOND_RETRY_DELAY.toSeconds(),
				THIRD_RETRY_DELAY.toSeconds(),
				FOURTH_RETRY_DELAY.toSeconds());
		return storedFailure.map(this::logAndCreate);
	}

	private RecordedFailure logAndCreate(NotificationOutboxEventRepository.RelayFailureRecord record) {
		RecordedFailure recordedFailure = new RecordedFailure(
			record.getSourceEventId(),
			record.getEventType(),
			record.getFailureCode(),
			record.getFailureClass(),
			record.getFailureCount(),
			record.getTotalFailureCount(),
			record.getNextAvailableAt(),
			record.isDeterministicFailure(),
			record.isRetryScheduled());
		if (recordedFailure.retryScheduled()) {
			log.warn(
				"event=notification_outbox_relay_retry_scheduled sourceEventId={} eventType={} failureCode={} "
					+ "failureClass={} failureCount={} totalFailureCount={} nextAvailableAt={}",
				recordedFailure.sourceEventId(), recordedFailure.eventType(), recordedFailure.failureCode(),
				recordedFailure.failureClass(), recordedFailure.failureCount(), recordedFailure.totalFailureCount(),
				recordedFailure.nextAvailableAt());
		} else {
			log.warn(
				"event=notification_outbox_relay_event_failed sourceEventId={} eventType={} failureCode={} "
					+ "failureClass={} failureCount={} totalFailureCount={} deterministicFailure={}",
				recordedFailure.sourceEventId(), recordedFailure.eventType(), recordedFailure.failureCode(),
				recordedFailure.failureClass(), recordedFailure.failureCount(), recordedFailure.totalFailureCount(),
				recordedFailure.deterministicFailure());
		}
		return recordedFailure;
	}

	public record RecordedFailure(
		long sourceEventId,
		String eventType,
		String failureCode,
		String failureClass,
		int failureCount,
		int totalFailureCount,
		Instant nextAvailableAt,
		boolean deterministicFailure,
		boolean retryScheduled) {
	}
}
