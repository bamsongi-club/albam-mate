package cloud.bamsongi.albammate.notification.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxStatus;
import cloud.bamsongi.albammate.notification.enums.NotificationType;

class NotificationPersistenceModelTest {

	private static final Instant OCCURRED_AT = Instant.parse("2026-08-01T12:00:00Z");
	private static final Instant RECORDED_AT = Instant.parse("2026-08-02T00:00:00Z");

	@Test
	void 최초_Outbox는_PENDING과_처리_가능_시각을_함께_만든다() {
		NotificationOutboxEvent event = NotificationOutboxEvent.createPending(
			NotificationOutboxEventType.PARTICIPATION_JOINED, 1L, OCCURRED_AT, RECORDED_AT);

		assertEquals(NotificationOutboxStatus.PENDING, event.getStatus());
		assertEquals(RECORDED_AT, event.getAvailableAt());
		assertEquals(0, event.getFailureCount());
		assertEquals(0, event.getTotalFailureCount());
		assertEquals(0, event.getReprocessCount());
		assertNull(event.getProcessedAt());
		assertNull(event.getCleanupAt());
	}

	@Test
	void 처리_가능한_Outbox만_30일_정리_시각과_함께_처리_완료로_전이한다() {
		NotificationOutboxEvent event = NotificationOutboxEvent.createPending(
			NotificationOutboxEventType.PARTICIPATION_JOINED, 1L, OCCURRED_AT, RECORDED_AT);
		Instant processedAt = Instant.parse("2026-08-03T00:00:00Z");

		event.markProcessed(processedAt);

		assertEquals(NotificationOutboxStatus.PROCESSED, event.getStatus());
		assertNull(event.getAvailableAt());
		assertEquals(processedAt, event.getProcessedAt());
		assertEquals(processedAt.plusSeconds(30L * 24 * 60 * 60), event.getCleanupAt());
		assertThrows(IllegalStateException.class, () -> event.markProcessed(processedAt));
	}

	@Test
	void Outbox_수신자와_복합_식별자는_null_식별자를_허용하지_않는다() {
		assertThrows(NullPointerException.class, () -> NotificationOutboxRecipient.create(null, 2L));
		assertThrows(NullPointerException.class, () -> NotificationOutboxRecipient.create(1L, null));
		assertThrows(NullPointerException.class, () -> NotificationOutboxRecipientId.of(null, 2L));
		assertThrows(NullPointerException.class, () -> NotificationOutboxRecipientId.of(1L, null));
	}

	@Test
	void Notification은_미확인_상태와_90일_만료_시각을_함께_만든다() {
		Notification notification = Notification.createUnread(
			1L, 2L, 3L, NotificationType.PARTICIPANT_JOINED, OCCURRED_AT, RECORDED_AT);

		assertNull(notification.getReadAt());
		assertEquals(OCCURRED_AT.plusSeconds(90L * 24 * 60 * 60), notification.getExpiresAt());
	}
}
