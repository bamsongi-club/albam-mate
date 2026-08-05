package cloud.bamsongi.albammate.notification.enums;

public enum NotificationOutboxEventType {

	PARTICIPATION_JOINED,
	PARTICIPATION_CANCELED,
	ROOM_CANCELED;

	/** Outbox 원인 사실을 사용자에게 노출할 알림 유형으로 변환한다. */
	public NotificationType toNotificationType() {
		return switch (this) {
			case PARTICIPATION_JOINED -> NotificationType.PARTICIPANT_JOINED;
			case PARTICIPATION_CANCELED -> NotificationType.PARTICIPANT_CANCELED;
			case ROOM_CANCELED -> NotificationType.ROOM_CANCELED;
		};
	}
}
