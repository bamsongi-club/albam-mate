package cloud.bamsongi.albammate.notification.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import cloud.bamsongi.albammate.monitoring.NotificationRelayMetrics;
import cloud.bamsongi.albammate.notification.entity.Notification;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.enums.NotificationType;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationRepository;
import io.micrometer.core.instrument.Metrics;
import lombok.extern.slf4j.Slf4j;

/** 한 Outbox 이벤트의 PostgreSQL 선점, 멱등 Notification 저장과 완료 전환을 함께 처리한다. */
@Service
@Slf4j
public class NotificationRelayExecutor {

	private final NotificationOutboxEventRepository eventRepository;
	private final NotificationOutboxRecipientRepository recipientRepository;
	private final NotificationRepository notificationRepository;
	private final NotificationRelayMetrics metrics;

	@Autowired
	public NotificationRelayExecutor(
		NotificationOutboxEventRepository eventRepository,
		NotificationOutboxRecipientRepository recipientRepository,
		NotificationRepository notificationRepository,
		NotificationRelayMetrics metrics) {
		this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
		this.recipientRepository = Objects.requireNonNull(recipientRepository, "recipientRepository");
		this.notificationRepository = Objects.requireNonNull(notificationRepository, "notificationRepository");
		this.metrics = Objects.requireNonNull(metrics, "metrics");
	}

	public NotificationRelayExecutor(
		NotificationOutboxEventRepository eventRepository,
		NotificationOutboxRecipientRepository recipientRepository,
		NotificationRepository notificationRepository) {
		this(eventRepository, recipientRepository, notificationRepository,
			new NotificationRelayMetrics(Metrics.globalRegistry));
	}

	/** 처리 가능한 가장 이른 이벤트 하나만 독립 트랜잭션에서 처리한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public Optional<ProcessedEvent> processOne() {
		long startedAtNanos = System.nanoTime();
		Optional<NotificationOutboxEventRepository.RelayClaim> claim = eventRepository.claimEarliestProcessableEvent();
		if (claim.isEmpty()) {
			return Optional.empty();
		}

		NotificationOutboxEventRepository.RelayClaim relayClaim = claim.get();
		try {
			return Optional.of(processClaimedEvent(relayClaim, startedAtNanos));
		} catch (NotificationRelayProcessingException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw NotificationRelayProcessingException.failed(relayClaim.getId(), exception);
		}
	}

	private ProcessedEvent processClaimedEvent(
		NotificationOutboxEventRepository.RelayClaim relayClaim,
		long startedAtNanos) {
		NotificationOutboxEvent event = eventRepository.findById(relayClaim.getId())
			.orElseThrow(() -> new IllegalStateException("claimed notification outbox event is missing"));
		Instant operationTime = relayClaim.getOperationTime();
		Instant notificationCreatedAt = event.getOccurredAt();
		// Notification 쓰기 전 선제 차단이며, 롤백 뒤 별도 트랜잭션의 최종 실패 판정은 아니다.
		if (Notification.isExpiredAt(notificationCreatedAt, operationTime)) {
			throw NotificationRelayProcessingException.expired(event.getId());
		}
		NotificationType notificationType = event.getEventType().toNotificationType();
		List<Long> recipientUserIds = recipientRepository.findRecipientUserIdsByOutboxEventId(event.getId());
		if (recipientUserIds.isEmpty()) {
			throw NotificationRelayProcessingException.missingRecipientSnapshot(event.getId());
		}

		for (Long recipientUserId : recipientUserIds) {
			Notification notification = Notification.createUnread(
				event.getId(), recipientUserId, event.getRoomId(), notificationType, notificationCreatedAt,
				operationTime);
			notificationRepository.insertIfAbsent(notification);
		}

		event.markProcessed(operationTime);
		eventRepository.flush();
		ProcessedEvent processedEvent = ProcessedEvent.completed(
			event, recipientUserIds.size(), operationTime, elapsedMillis(startedAtNanos));
		registerAfterCommitLog(processedEvent);
		return processedEvent;
	}

	private void registerAfterCommitLog(ProcessedEvent processedEvent) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				metrics.recordProcessed(processedEvent.processingDurationMs());
				logProcessedEvent(processedEvent);
			}
		});
	}

	private static void logProcessedEvent(ProcessedEvent processedEvent) {
		log.atInfo().addKeyValue("event", "notification_outbox_relay_event_processed")
			.addKeyValue("sourceEventId", processedEvent.sourceEventId())
			.addKeyValue("eventType", processedEvent.eventType())
			.addKeyValue("recipientCount", processedEvent.recipientCount())
			.addKeyValue("outboxRecordedAt", processedEvent.outboxRecordedAt())
			.addKeyValue("notificationRecordedAt", processedEvent.notificationRecordedAt())
			.addKeyValue("failureCount", processedEvent.failureCount())
			.addKeyValue("totalFailureCount", processedEvent.totalFailureCount())
			.addKeyValue("reprocessCount", processedEvent.reprocessCount())
			.addKeyValue("deliveryDelayMs", processedEvent.deliveryDelayMs())
			.addKeyValue("processingDurationMs", processedEvent.processingDurationMs())
			.log("notification relay event processed");
	}

	private static long elapsedMillis(long startedAtNanos) {
		return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
	}

	public record ProcessedEvent(
		Long sourceEventId,
		String eventType,
		int recipientCount,
		Instant outboxRecordedAt,
		Instant notificationRecordedAt,
		int failureCount,
		int totalFailureCount,
		int reprocessCount,
		long deliveryDelayMs,
		long processingDurationMs) {

		public static ProcessedEvent completed(
			NotificationOutboxEvent event,
			int recipientCount,
			Instant operationTime,
			long processingDurationMillis) {
			return new ProcessedEvent(
				event.getId(),
				event.getEventType().name(),
				recipientCount,
				event.getRecordedAt(),
				operationTime,
				event.getFailureCount(),
				event.getTotalFailureCount(),
				event.getReprocessCount(),
				Duration.between(event.getRecordedAt(), operationTime).toMillis(),
				processingDurationMillis);
		}
	}
}
