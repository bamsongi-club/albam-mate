package cloud.bamsongi.albammate.notification.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;

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
		NotificationCleanupExecutor executor = executor(notificationRepository, eventRepository, transactionManager());

		NotificationCleanupExecutor.CleanupBatchResult result = executor.cleanupOneBatch(
			NotificationCleanupTarget.NOTIFICATION, 10);

		assertEquals(NotificationCleanupTarget.NOTIFICATION, result.targetType());
		assertEquals(measurementTime, result.measurementTime());
		assertEquals(3, result.deletedCount());
		verify(eventRepository, times(1)).findCleanupMeasurementTime();
		verify(notificationRepository).deleteExpiredNotifications(measurementTime, 10);
	}

	@Test
	void Outbox_batch는_Outbox_Repository에만_위임한다() {
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		Instant measurementTime = Instant.parse("2026-08-04T00:00:00Z");
		when(eventRepository.findCleanupMeasurementTime()).thenReturn(measurementTime);
		when(eventRepository.deleteExpiredProcessedOrDiscardedEvents(measurementTime, 10)).thenReturn(4L);
		NotificationCleanupExecutor executor = executor(notificationRepository, eventRepository, transactionManager());

		NotificationCleanupExecutor.CleanupBatchResult result = executor.cleanupOneBatch(
			NotificationCleanupTarget.OUTBOX, 10);

		assertEquals(NotificationCleanupTarget.OUTBOX, result.targetType());
		assertEquals(measurementTime, result.measurementTime());
		assertEquals(4, result.deletedCount());
		verify(eventRepository, times(1)).findCleanupMeasurementTime();
		verify(eventRepository).deleteExpiredProcessedOrDiscardedEvents(measurementTime, 10);
	}

	@Test
	void commit_실패도_고정된_measurementTime으로_전달한다() {
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		Instant measurementTime = Instant.parse("2026-08-04T00:00:00Z");
		when(eventRepository.findCleanupMeasurementTime()).thenReturn(measurementTime);
		when(notificationRepository.deleteExpiredNotifications(measurementTime, 10)).thenReturn(1L);
		PlatformTransactionManager transactionManager = transactionManager();
		doThrow(new TransactionSystemException("commit failure"))
			.when(transactionManager).commit(any());
		NotificationCleanupExecutor executor = executor(notificationRepository, eventRepository, transactionManager);

		NotificationCleanupExecutor.CleanupBatchFailedException exception = assertThrows(
			NotificationCleanupExecutor.CleanupBatchFailedException.class,
			() -> executor.cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 10));

		assertEquals(measurementTime, exception.getMeasurementTime());
		assertEquals(TransactionSystemException.class.getSimpleName(), exception.getOriginalExceptionClass());
		verify(eventRepository, times(1)).findCleanupMeasurementTime();
		verify(notificationRepository).deleteExpiredNotifications(measurementTime, 10);
	}

	@Test
	void 각_cleanup_batch는_REQUIRES_NEW_트랜잭션에서_실행한다() {
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		PlatformTransactionManager transactionManager = transactionManager();
		when(eventRepository.findCleanupMeasurementTime()).thenReturn(Instant.parse("2026-08-04T00:00:00Z"));
		NotificationCleanupExecutor executor = executor(notificationRepository, eventRepository, transactionManager);

		executor.cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 10);

		ArgumentCaptor<TransactionDefinition> transactionDefinition = ArgumentCaptor
			.forClass(TransactionDefinition.class);
		verify(transactionManager).getTransaction(transactionDefinition.capture());
		assertEquals(
			TransactionDefinition.PROPAGATION_REQUIRES_NEW,
			transactionDefinition.getValue().getPropagationBehavior());
	}

	@Test
	void DB_시각_조회_전_실패는_원본_예외를_그대로_전달하고_delete하지_않는다() {
		// Arrange
		NotificationRepository notificationRepository = mock(NotificationRepository.class);
		NotificationOutboxEventRepository eventRepository = mock(NotificationOutboxEventRepository.class);
		RuntimeException measurementTimeFailure = new IllegalStateException("measurement time query failure");
		when(eventRepository.findCleanupMeasurementTime()).thenThrow(measurementTimeFailure);
		NotificationCleanupExecutor executor = executor(notificationRepository, eventRepository, transactionManager());

		// Act
		RuntimeException thrownException = assertThrows(
			RuntimeException.class,
			() -> executor.cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 10));

		// Assert
		assertSame(measurementTimeFailure, thrownException);
		verify(eventRepository, times(1)).findCleanupMeasurementTime();
		verify(notificationRepository, never()).deleteExpiredNotifications(any(), anyInt());
		verify(eventRepository, never()).deleteExpiredProcessedOrDiscardedEvents(any(), anyInt());
	}

	private NotificationCleanupExecutor executor(
		NotificationRepository notificationRepository,
		NotificationOutboxEventRepository eventRepository,
		PlatformTransactionManager transactionManager) {
		return new NotificationCleanupExecutor(notificationRepository, eventRepository, transactionManager);
	}

	private PlatformTransactionManager transactionManager() {
		PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
		when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
		return transactionManager;
	}
}
