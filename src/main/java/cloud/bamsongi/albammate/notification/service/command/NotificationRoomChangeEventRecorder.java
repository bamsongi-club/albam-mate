package cloud.bamsongi.albammate.notification.service.command;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxRecipient;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxRecipientRepository;
import cloud.bamsongi.albammate.room.contract.ParticipationCanceledEvent;
import cloud.bamsongi.albammate.room.contract.ParticipationJoinedEvent;
import cloud.bamsongi.albammate.room.contract.RoomCanceledEvent;
import cloud.bamsongi.albammate.room.contract.RoomChangeEvent;
import cloud.bamsongi.albammate.room.contract.RoomChangeEventRecorder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** ROOM 공개 계약을 기존 Notification Outbox와 수신자 저장 모델로 기록한다. */
@Service
@RequiredArgsConstructor
public class NotificationRoomChangeEventRecorder implements RoomChangeEventRecorder {

	@NonNull private final NotificationOutboxEventRepository eventRepository;
	@NonNull private final NotificationOutboxRecipientRepository recipientRepository;

	/** 원인 업무 트랜잭션의 PostgreSQL operationTime으로 Outbox 기록 시각을 고정한다. */
	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void record(RoomChangeEvent event, Collection<Long> recipientUserIds) {
		Objects.requireNonNull(event, "event");
		List<Long> recipients = List.copyOf(Objects.requireNonNull(recipientUserIds, "recipientUserIds"));
		if (recipients.isEmpty()) {
			throw new IllegalArgumentException("recipientUserIds must not be empty");
		}
		recipients.forEach(recipientUserId -> Objects.requireNonNull(recipientUserId, "recipientUserId"));
		Instant operationTime = eventRepository.findPostgresOperationTime();
		NotificationOutboxEvent outboxEvent = eventRepository.saveAndFlush(
			NotificationOutboxEvent.createPending(eventTypeOf(event), event.roomId(), event.occurredAt(),
				operationTime));
		recipientRepository.saveAll(recipients.stream()
			.map(recipientUserId -> NotificationOutboxRecipient.create(outboxEvent.getId(), recipientUserId))
			.toList());
	}

	private NotificationOutboxEventType eventTypeOf(RoomChangeEvent event) {
		return switch (event) {
			case ParticipationJoinedEvent ignored -> NotificationOutboxEventType.PARTICIPATION_JOINED;
			case ParticipationCanceledEvent ignored -> NotificationOutboxEventType.PARTICIPATION_CANCELED;
			case RoomCanceledEvent ignored -> NotificationOutboxEventType.ROOM_CANCELED;
		};
	}

}
