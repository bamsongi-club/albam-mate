package cloud.bamsongi.albammate.notification.cleanup;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** target별 batch 상한을 지키고 실패한 target이 다른 target을 막지 않게 조정한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationCleanupCoordinator {

	private static final String CLEANUP_BATCH_FAILURE = "CLEANUP_BATCH_FAILURE";

	@NonNull private final NotificationCleanupExecutor executor;
	@NonNull private final NotificationCleanupProperties properties;

	/** Notification과 Outbox cleanup을 서로 독립적으로 실행한다. */
	public void cleanupExpiredData() {
		cleanupTarget(NotificationCleanupTarget.NOTIFICATION);
		cleanupTarget(NotificationCleanupTarget.OUTBOX);
	}

	private void cleanupTarget(NotificationCleanupTarget targetType) {
		for (int batchNumber = 1; batchNumber <= properties.getMaxBatchesPerTarget(); batchNumber++) {
			long startedAtNanos = System.nanoTime();
			try {
				NotificationCleanupExecutor.CleanupBatchResult batchResult = executor.cleanupOneBatch(
					targetType, properties.getBatchSize());
				logCompletedBatch(batchNumber, batchResult, elapsedMillis(startedAtNanos));
				if (batchResult.deletedCount() < properties.getBatchSize()) {
					return;
				}
			} catch (NotificationCleanupExecutor.CleanupBatchFailedException exception) {
				logFailedBatch(
					targetType,
					batchNumber,
					exception.getOriginalExceptionClass(),
					exception.getMeasurementTime(),
					elapsedMillis(startedAtNanos));
				return;
			} catch (RuntimeException exception) {
				logFailedBatch(
					targetType,
					batchNumber,
					exception.getClass().getSimpleName(),
					null,
					elapsedMillis(startedAtNanos));
				return;
			}
		}
	}

	private void logCompletedBatch(
		int batchNumber,
		NotificationCleanupExecutor.CleanupBatchResult batchResult,
		long durationMillis) {
		if (batchResult.deletedCount() == 0) {
			log.atDebug().addKeyValue("event", "notification_cleanup_completed")
				.addKeyValue("targetType", batchResult.targetType()).addKeyValue("batchNumber", batchNumber)
				.addKeyValue("deletedCount", batchResult.deletedCount()).addKeyValue("durationMs", durationMillis)
				.addKeyValue("measurementTime", batchResult.measurementTime()).log("notification cleanup completed");
			return;
		}
		log.atInfo().addKeyValue("event", "notification_cleanup_completed")
			.addKeyValue("targetType", batchResult.targetType()).addKeyValue("batchNumber", batchNumber)
			.addKeyValue("deletedCount", batchResult.deletedCount()).addKeyValue("durationMs", durationMillis)
			.addKeyValue("measurementTime", batchResult.measurementTime()).log("notification cleanup completed");
	}

	private void logFailedBatch(
		NotificationCleanupTarget targetType,
		int batchNumber,
		String exceptionClass,
		Instant measurementTime,
		long durationMillis) {
		if (measurementTime == null) {
			log.atWarn().addKeyValue("event", "notification_cleanup_failed").addKeyValue("targetType", targetType)
				.addKeyValue("batchNumber", batchNumber).addKeyValue("deletedCount", 0)
				.addKeyValue("failureCode", CLEANUP_BATCH_FAILURE).addKeyValue("exceptionClass", exceptionClass)
				.addKeyValue("durationMs", durationMillis).log("notification cleanup failed");
			return;
		}
		log.atWarn().addKeyValue("event", "notification_cleanup_failed").addKeyValue("targetType", targetType)
			.addKeyValue("batchNumber", batchNumber).addKeyValue("deletedCount", 0)
			.addKeyValue("failureCode", CLEANUP_BATCH_FAILURE).addKeyValue("exceptionClass", exceptionClass)
			.addKeyValue("durationMs", durationMillis).addKeyValue("measurementTime", measurementTime)
			.log("notification cleanup failed");
	}

	private long elapsedMillis(long startedAtNanos) {
		return Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis();
	}
}
