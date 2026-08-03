package cloud.bamsongi.albammate.notification.relay;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import cloud.bamsongi.albammate.notification.entity.Notification;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.enums.NotificationType;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;

/** 한 Outbox 이벤트의 PostgreSQL 선점, 멱등 Notification 저장과 완료 전환을 함께 처리한다. */
@Service
@Slf4j
public class NotificationRelayExecutor {

	private final NotificationOutboxEventRepository eventRepository;
	private final NotificationOutboxRecipientRepository recipientRepository;
	private final NotificationRepository notificationRepository;
	private final NotificationEventTypeMapper eventTypeMapper;

	public NotificationRelayExecutor(
		NotificationOutboxEventRepository eventRepository,
		NotificationOutboxRecipientRepository recipientRepository,
		NotificationRepository notificationRepository,
		NotificationEventTypeMapper eventTypeMapper) {
		this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
		this.recipientRepository = Objects.requireNonNull(recipientRepository, "recipientRepository");
		this.notificationRepository = Objects.requireNonNull(notificationRepository, "notificationRepository");
		this.eventTypeMapper = Objects.requireNonNull(eventTypeMapper, "eventTypeMapper");
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
		NotificationOutboxEvent event = eventRepository.findById(relayClaim.getId())
			.orElseThrow(() -> new IllegalStateException("claimed notification outbox event is missing"));
		Instant operationTime = relayClaim.getOperationTime();
		NotificationType notificationType = eventTypeMapper.map(event.getEventType());
		List<Long> recipientUserIds = recipientRepository.findRecipientUserIdsByOutboxEventId(event.getId());
		if (recipientUserIds.isEmpty()) {
			throw new IllegalStateException("claimed notification outbox event has no recipients");
		}

		for (Long recipientUserId : recipientUserIds) {
			Notification notification = Notification.createUnread(
				event.getId(), recipientUserId, event.getRoomId(), notificationType, event.getOccurredAt(),
				operationTime);
			notificationRepository.insertIfAbsent(
				notification.getSourceEventId(),
				notification.getRecipientUserId(),
				notification.getRoomId(),
				notification.getType().name(),
				notification.getCreatedAt(),
				notification.getRecordedAt(),
				notification.getExpiresAt());
		}

		event.markProcessed(operationTime);
		ProcessedEvent processedEvent = new ProcessedEvent(
			event.getId(),
			event.getEventType().name(),
			recipientUserIds.size(),
			event.getRecordedAt(),
			operationTime,
			event.getFailureCount(),
			event.getTotalFailureCount(),
			event.getReprocessCount(),
			Duration.between(event.getRecordedAt(), operationTime).toMillis(),
			elapsedMillis(startedAtNanos));
		logAfterCommit(processedEvent);
		return Optional.of(processedEvent);
	}

	private void logAfterCommit(ProcessedEvent processedEvent) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			logProcessedEvent(processedEvent);
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				logProcessedEvent(processedEvent);
			}
		});
	}

	private static void logProcessedEvent(ProcessedEvent processedEvent) {
		log.info(
			"event=notification_outbox_relay_event_processed sourceEventId={} eventType={} recipientCount={} "
				+ "outboxRecordedAt={} notificationRecordedAt={} failureCount={} totalFailureCount={} "
				+ "reprocessCount={} deliveryDelayMs={} processingDurationMs={}",
			processedEvent.sourceEventId(),
			processedEvent.eventType(),
			processedEvent.recipientCount(),
			processedEvent.outboxRecordedAt(),
			processedEvent.notificationRecordedAt(),
			processedEvent.failureCount(),
			processedEvent.totalFailureCount(),
			processedEvent.reprocessCount(),
			processedEvent.deliveryDelayMs(),
			processedEvent.processingDurationMs());
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
	}
}
