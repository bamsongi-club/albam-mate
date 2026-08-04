package cloud.bamsongi.albammate.notification.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationRepository;

class NotificationCleanupExecutorTest {

	@Test
	void Notification_batch는_Notification_Repository에만_위임한다() {
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		Instant measurementTime = Instant.parse("2026-08-04T00:00:00Z");
		when(eventRepository.findCleanupMeasurementTime()).thenReturn(measurementTime);
		when(notificationRepository.deleteExpiredNotifications(measurementTime, 10)).thenReturn(3L);
		NotificationCleanupExecutor executor = new NotificationCleanupExecutor(notificationRepository, eventRepository);

		NotificationCleanupExecutor.CleanupBatchResult result = executor.cleanupOneBatch(
			NotificationCleanupTarget.NOTIFICATION, 10);

		assertEquals(NotificationCleanupTarget.NOTIFICATION, result.targetType());
		assertEquals(3, result.deletedCount());
		verify(notificationRepository).deleteExpiredNotifications(measurementTime, 10);
	}

	@Test
	void Outbox_batch는_Outbox_Repository에만_위임한다() {
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		Instant measurementTime = Instant.parse("2026-08-04T00:00:00Z");
		when(eventRepository.findCleanupMeasurementTime()).thenReturn(measurementTime);
		when(eventRepository.deleteExpiredProcessedOrDiscardedEvents(measurementTime, 10)).thenReturn(4L);
		NotificationCleanupExecutor executor = new NotificationCleanupExecutor(notificationRepository, eventRepository);

		NotificationCleanupExecutor.CleanupBatchResult result = executor.cleanupOneBatch(
			NotificationCleanupTarget.OUTBOX, 10);

		assertEquals(NotificationCleanupTarget.OUTBOX, result.targetType());
		assertEquals(4, result.deletedCount());
		verify(eventRepository).deleteExpiredProcessedOrDiscardedEvents(measurementTime, 10);
	}
}
