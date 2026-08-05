package cloud.bamsongi.albammate.notification.enums;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NotificationOutboxEventTypeTest {

	@Test
	void Outbox_이벤트_유형을_정본_알림_유형으로_명시적으로_변환한다() {
		assertEquals(NotificationType.PARTICIPANT_JOINED,
			NotificationOutboxEventType.PARTICIPATION_JOINED.toNotificationType());
		assertEquals(NotificationType.PARTICIPANT_CANCELED,
			NotificationOutboxEventType.PARTICIPATION_CANCELED.toNotificationType());
		assertEquals(NotificationType.ROOM_CANCELED,
			NotificationOutboxEventType.ROOM_CANCELED.toNotificationType());
	}
}
