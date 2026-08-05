package cloud.bamsongi.albammate.notification.recovery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxStatus;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** FAILED Outbox의 inspect, dry-run, 전체 원자적 재처리·폐기를 소유한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationOutboxRecoveryService {

	private static final Duration REPROCESS_WINDOW = Duration.ofDays(89);
	private static final Duration NOTIFICATION_RETENTION = Duration.ofDays(90);
	private static final Duration DISCARDED_RETENTION = Duration.ofDays(30);

	@NonNull private final NotificationOutboxEventRepository eventRepository;
	@NonNull private final NotificationOutboxRecipientRepository recipientRepository;
	@NonNull private final NotificationOutboxRecoveryPolicy recoveryPolicy;

	/** inspect와 dry-run은 현재 상태를 읽기만 하고 실제 변경 때는 다시 검증한다. */
	@Transactional(readOnly = true)
	public NotificationOutboxRecoveryResult preview(NotificationOutboxRecoveryRequest request) {
		List<Long> eventIds = recoveryPolicy.validateAndNormalize(request,
			NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);
		Instant operationTime = eventRepository.findRecoveryOperationTime();
		List<NotificationOutboxEvent> events = eventRepository.findAllByIdInOrderById(eventIds);
		List<NotificationOutboxRecoveryItem> items = createPreviewItems(eventIds, events, request.action(),
			operationTime);
		int eligibleCount = countEligible(items);
		logPreview(request, eventIds, eligibleCount);
		return new NotificationOutboxRecoveryResult(eventIds, eligibleCount, 0, items);
	}

	/** 한 PostgreSQL operationTime과 오름차순 행 잠금 안에서 전체 적격성을 먼저 확인한다. */
	@Transactional
	public NotificationOutboxRecoveryResult execute(NotificationOutboxRecoveryRequest request) {
		List<Long> eventIds = recoveryPolicy.validateAndNormalize(request,
			NotificationOutboxRecoveryPolicy.ExecutionMode.EXECUTE);
		List<NotificationOutboxEvent> events = eventRepository.findAllByIdInOrderByIdForUpdate(eventIds);
		Instant operationTime = eventRepository.findRecoveryOperationTime();
		ensureAllEligible(events, eventIds, request.action(), operationTime);

		int changedCount = switch (request.action()) {
			case REPROCESS -> eventRepository.reprocessAll(eventIds, operationTime, request.reason());
			case DISCARD -> discardAll(eventIds, operationTime, request.reason());
			case INSPECT -> throw new NotificationOutboxRecoveryInputException();
		};
		if (changedCount != eventIds.size()) {
			throw new IllegalStateException("notification outbox operation changed an unexpected number of events");
		}
		logCompleted(request, eventIds, changedCount);
		return new NotificationOutboxRecoveryResult(eventIds, changedCount, changedCount, List.of());
	}

	private int discardAll(List<Long> eventIds, Instant operationTime, String reason) {
		int changedCount = eventRepository.discardAll(eventIds, operationTime, operationTime.plus(DISCARDED_RETENTION),
			reason);
		recipientRepository.deleteByIdOutboxEventIdIn(eventIds);
		return changedCount;
	}

	private void ensureAllEligible(
		List<NotificationOutboxEvent> events,
		List<Long> eventIds,
		NotificationRecoveryAction action,
		Instant operationTime) {
		if (events.size() != eventIds.size() || countEligible(events, action, operationTime) != eventIds.size()) {
			throw new NotificationOutboxRecoveryInputException();
		}
		if (action == NotificationRecoveryAction.REPROCESS) {
			for (NotificationOutboxEvent event : events) {
				if (!recipientRepository.existsByIdOutboxEventId(event.getId())) {
					throw new NotificationOutboxRecoveryInputException();
				}
			}
		}
	}

	private static int countEligible(
		List<NotificationOutboxEvent> events,
		NotificationRecoveryAction action,
		Instant operationTime) {
		return (int)events.stream().filter(event -> isEligible(event, action, operationTime)).count();
	}

	private static int countEligible(List<NotificationOutboxRecoveryItem> items) {
		return (int)items.stream().filter(NotificationOutboxRecoveryItem::eligible).count();
	}

	private List<NotificationOutboxRecoveryItem> createPreviewItems(
		List<Long> eventIds,
		List<NotificationOutboxEvent> events,
		NotificationRecoveryAction action,
		Instant operationTime) {
		Map<Long, NotificationOutboxEvent> eventById = events.stream()
			.collect(Collectors.toMap(NotificationOutboxEvent::getId, Function.identity()));
		List<NotificationOutboxRecoveryItem> items = new ArrayList<>();
		for (Long eventId : eventIds) {
			NotificationOutboxEvent event = eventById.get(eventId);
			if (event == null) {
				items.add(NotificationOutboxRecoveryItem.missing(eventId));
				continue;
			}
			boolean recipientSnapshotExists = recipientRepository.existsByIdOutboxEventId(eventId);
			boolean reprocessable = isReprocessable(event, operationTime, recipientSnapshotExists);
			boolean eligible = isEligible(event, action, operationTime)
				&& (action != NotificationRecoveryAction.REPROCESS || recipientSnapshotExists);
			items.add(new NotificationOutboxRecoveryItem(
				eventId,
				event.getStatus().name(),
				event.getEventType().name(),
				event.getOccurredAt(),
				event.getOccurredAt().plus(NOTIFICATION_RETENTION),
				event.getFailureCount(),
				event.getTotalFailureCount(),
				event.getLastFailureCode(),
				reprocessable,
				eligible));
		}
		return List.copyOf(items);
	}

	private static boolean isEligible(
		NotificationOutboxEvent event,
		NotificationRecoveryAction action,
		Instant operationTime) {
		if (event.getStatus() != NotificationOutboxStatus.FAILED) {
			return false;
		}
		if (action == NotificationRecoveryAction.INSPECT) {
			return true;
		}
		if (action == NotificationRecoveryAction.DISCARD) {
			return true;
		}
		return action == NotificationRecoveryAction.REPROCESS
			&& isReprocessable(event, operationTime, true);
	}

	private static boolean isReprocessable(
		NotificationOutboxEvent event,
		Instant operationTime,
		boolean recipientSnapshotExists) {
		return event.getStatus() == NotificationOutboxStatus.FAILED
			&& recipientSnapshotExists
			&& !"NOTIFICATION_EXPIRED".equals(event.getLastFailureCode())
			&& operationTime.isBefore(event.getOccurredAt().plus(REPROCESS_WINDOW));
	}

	private static void logPreview(NotificationOutboxRecoveryRequest request, List<Long> eventIds, int eligibleCount) {
		if (request.action() == NotificationRecoveryAction.INSPECT) {
			log.info(
				"event=notification_outbox_operation_previewed sourceEventIds={} action={} requestedCount={} eligibleCount={} dryRun={}",
				eventIds, request.action(), eventIds.size(), eligibleCount, request.dryRun());
			return;
		}
		log.info(
			"event=notification_outbox_operation_previewed sourceEventIds={} action={} reasonReference={} requestedBy={} requestedCount={} eligibleCount={} dryRun={}",
			eventIds, request.action(), request.reasonReference(), request.requestedBy(), eventIds.size(),
			eligibleCount, request.dryRun());
	}

	private static void logCompleted(NotificationOutboxRecoveryRequest request, List<Long> eventIds, int changedCount) {
		log.warn(
			"event=notification_outbox_operation_completed sourceEventIds={} action={} reasonReference={} requestedBy={} requestedCount={} changedCount={} dryRun={}",
			eventIds, request.action(), request.reasonReference(), request.requestedBy(), eventIds.size(), changedCount,
			false);
	}
}
