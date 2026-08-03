package cloud.bamsongi.albammate.notification.relay;

import java.util.Objects;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.enums.NotificationType;

/** 승인된 Outbox 원인 사실을 사용자 알림 유형으로 명시적으로 변환한다. */
@Component
public class NotificationEventTypeMapper {

	public NotificationType map(NotificationOutboxEventType eventType) {
		Objects.requireNonNull(eventType, "eventType");
		return switch (eventType) {
			case PARTICIPATION_JOINED -> NotificationType.PARTICIPANT_JOINED;
			case PARTICIPATION_CANCELED -> NotificationType.PARTICIPANT_CANCELED;
			case ROOM_CANCELED -> NotificationType.ROOM_CANCELED;
		};
	}
}
