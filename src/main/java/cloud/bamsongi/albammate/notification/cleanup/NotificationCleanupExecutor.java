package cloud.bamsongi.albammate.notification.cleanup;

import java.time.Instant;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationRepository;

/** 각 target의 한 batch를 독립 트랜잭션에서 선점하고 삭제한다. */
@Service
public class NotificationCleanupExecutor {

	private final NotificationRepository notificationRepository;
	private final NotificationOutboxEventRepository eventRepository;
	private final TransactionTemplate cleanupTransaction;

	public NotificationCleanupExecutor(
		NotificationRepository notificationRepository,
		NotificationOutboxEventRepository eventRepository,
		PlatformTransactionManager transactionManager) {
		this.notificationRepository = Objects.requireNonNull(notificationRepository, "notificationRepository");
		this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
		this.cleanupTransaction = new TransactionTemplate(
			Objects.requireNonNull(transactionManager, "transactionManager"));
		this.cleanupTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	/** PostgreSQL 시각을 고정한 뒤 한 target의 제한된 batch만 삭제한다. */
	public CleanupBatchResult cleanupOneBatch(NotificationCleanupTarget targetType, int batchSize) {
		CleanupBatchExecution execution = new CleanupBatchExecution();
		try {
			return cleanupTransaction.execute(status -> cleanupInTransaction(targetType, batchSize, execution));
		} catch (RuntimeException exception) {
			if (execution.measurementTime == null) {
				throw exception;
			}
			throw CleanupBatchFailedException.afterMeasurement(execution.measurementTime, exception);
		}
	}

	private CleanupBatchResult cleanupInTransaction(
		NotificationCleanupTarget targetType,
		int batchSize,
		CleanupBatchExecution execution) {
		execution.measurementTime = eventRepository.findCleanupMeasurementTime();
		long deletedCount = switch (targetType) {
			case NOTIFICATION ->
				notificationRepository.deleteExpiredNotifications(execution.measurementTime, batchSize);
			case OUTBOX ->
				eventRepository.deleteExpiredProcessedOrDiscardedEvents(execution.measurementTime, batchSize);
		};
		return new CleanupBatchResult(targetType, execution.measurementTime, deletedCount);
	}

	private static final class CleanupBatchExecution {

		private Instant measurementTime;
	}

	/** 한 cleanup batch가 PostgreSQL에서 측정한 시각과 실제 삭제 건수다. */
	public record CleanupBatchResult(
		NotificationCleanupTarget targetType,
		Instant measurementTime,
		long deletedCount) {
	}

	/** DB 기준 시각을 얻은 뒤 실패한 batch의 안전한 로그용 정보를 보존한다. */
	public static final class CleanupBatchFailedException extends RuntimeException {

		private final Instant measurementTime;
		private final String originalExceptionClass;

		private CleanupBatchFailedException(
			Instant measurementTime,
			String originalExceptionClass,
			RuntimeException cause) {
			super(null, cause);
			this.measurementTime = measurementTime;
			this.originalExceptionClass = originalExceptionClass;
		}

		static CleanupBatchFailedException afterMeasurement(Instant measurementTime, RuntimeException exception) {
			return new CleanupBatchFailedException(measurementTime, exception.getClass().getSimpleName(), exception);
		}

		public Instant getMeasurementTime() {
			return measurementTime;
		}

		public String getOriginalExceptionClass() {
			return originalExceptionClass;
		}
	}
}
