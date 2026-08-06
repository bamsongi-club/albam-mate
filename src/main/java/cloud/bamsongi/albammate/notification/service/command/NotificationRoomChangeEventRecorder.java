package cloud.bamsongi.albammate.notification.service.command;

import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;

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

/** ROOM 공개 계약을 기존 Notification Outbox와 수신자 저장 모델로 기록한다. */
@Service
public class NotificationRoomChangeEventRecorder implements RoomChangeEventRecorder {

	private final NotificationOutboxEventRepository eventRepository;
	private final NotificationOutboxRecipientRepository recipientRepository;
	private final Clock clock;

	public NotificationRoomChangeEventRecorder(
		NotificationOutboxEventRepository eventRepository,
		NotificationOutboxRecipientRepository recipientRepository,
		Clock clock) {
		this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
		this.recipientRepository = Objects.requireNonNull(recipientRepository, "recipientRepository");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	/** 원인 이벤트의 occurredAt과 별개로 기록 시각을 Clock에서 얻어 같은 트랜잭션에 저장한다. */
	@Override
	public void record(RoomChangeEvent event, Collection<Long> recipientUserIds) {
		Objects.requireNonNull(event, "event");
		List<Long> recipients = List.copyOf(Objects.requireNonNull(recipientUserIds, "recipientUserIds"));
		if (recipients.isEmpty()) {
			throw new IllegalArgumentException("recipientUserIds must not be empty");
		}
		recipients.forEach(recipientUserId -> Objects.requireNonNull(recipientUserId, "recipientUserId"));
		Instant recordedAt = Instant.now(clock);
		NotificationOutboxEvent outboxEvent = eventRepository.saveAndFlush(
			NotificationOutboxEvent.createPending(eventTypeOf(event), event.roomId(), event.occurredAt(), recordedAt));
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
