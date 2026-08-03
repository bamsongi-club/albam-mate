package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.enums.NotificationType;

class NotificationEventTypeMapperTest {

	private final NotificationEventTypeMapper mapper = new NotificationEventTypeMapper();

	@Test
	void Outbox_이벤트_유형을_정본_알림_유형으로_명시적으로_변환한다() {
		assertEquals(NotificationType.PARTICIPANT_JOINED, mapper.map(NotificationOutboxEventType.PARTICIPATION_JOINED));
		assertEquals(NotificationType.PARTICIPANT_CANCELED,
			mapper.map(NotificationOutboxEventType.PARTICIPATION_CANCELED));
		assertEquals(NotificationType.ROOM_CANCELED, mapper.map(NotificationOutboxEventType.ROOM_CANCELED));
	}

	@Test
	void null_Outbox_이벤트_유형은_거절한다() {
		assertThrows(NullPointerException.class, () -> mapper.map(null));
	}
}
