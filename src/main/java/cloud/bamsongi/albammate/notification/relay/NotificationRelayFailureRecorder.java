package cloud.bamsongi.albammate.notification.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import cloud.bamsongi.albammate.notification.entity.Notification;
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

	/** 최초 처리 1회와 실패 1~4 뒤 재시도 4회로 최대 5회 자동 처리한 뒤 최종 실패로 전환한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<RecordedFailure> record(NotificationRelayProcessingException processingException) {
		FailureClassification classification = failureClassifier.classify(processingException);
		FailureClassification expiredClassification = failureClassifier.expiredClassification();
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
				FOURTH_RETRY_DELAY.toSeconds(),
				Notification.retentionPeriod().toSeconds(),
				expiredClassification.failureCode(),
				expiredClassification.failureClass(),
				expiredClassification.sanitizedMessage());
		return storedFailure.map(this::createAndRegisterAfterCommitLog);
	}

	private RecordedFailure createAndRegisterAfterCommitLog(
		NotificationOutboxEventRepository.RelayFailureRecord record) {
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
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				logRecordedFailure(recordedFailure);
			}
		});
		return recordedFailure;
	}

	private void logRecordedFailure(RecordedFailure recordedFailure) {
		if (recordedFailure.retryScheduled()) {
			log.atWarn().addKeyValue("event", "notification_outbox_relay_retry_scheduled")
				.addKeyValue("sourceEventId", recordedFailure.sourceEventId())
				.addKeyValue("eventType", recordedFailure.eventType())
				.addKeyValue("failureCode", recordedFailure.failureCode())
				.addKeyValue("failureCount", recordedFailure.failureCount())
				.addKeyValue("totalFailureCount", recordedFailure.totalFailureCount())
				.addKeyValue("nextAvailableAt", recordedFailure.nextAvailableAt())
				.log("notification relay retry scheduled");
		} else {
			log.atWarn().addKeyValue("event", "notification_outbox_relay_event_failed")
				.addKeyValue("sourceEventId", recordedFailure.sourceEventId())
				.addKeyValue("eventType", recordedFailure.eventType())
				.addKeyValue("failureCode", recordedFailure.failureCode())
				.addKeyValue("failureCount", recordedFailure.failureCount())
				.addKeyValue("totalFailureCount", recordedFailure.totalFailureCount())
				.log("notification relay event failed");
		}
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
