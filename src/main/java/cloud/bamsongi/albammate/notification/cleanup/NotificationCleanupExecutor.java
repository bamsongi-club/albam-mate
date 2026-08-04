package cloud.bamsongi.albammate.notification.cleanup;

import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import cloud.bamsongi.albammate.notification.repository.NotificationRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 각 target의 한 batch를 독립 트랜잭션에서 선점하고 삭제한다. */
@Service
@RequiredArgsConstructor
public class NotificationCleanupExecutor {

	@NonNull private final NotificationRepository notificationRepository;
	@NonNull private final NotificationOutboxEventRepository eventRepository;

	/** PostgreSQL 시각을 고정한 뒤 한 target의 제한된 batch만 삭제한다. */
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public CleanupBatchResult cleanupOneBatch(NotificationCleanupTarget targetType, int batchSize) {
		Instant measurementTime = eventRepository.findCleanupMeasurementTime();
		try {
			long deletedCount = switch (targetType) {
				case NOTIFICATION -> notificationRepository.deleteExpiredNotifications(measurementTime, batchSize);
				case OUTBOX -> eventRepository.deleteExpiredProcessedOrDiscardedEvents(measurementTime, batchSize);
			};
			return new CleanupBatchResult(targetType, measurementTime, deletedCount);
		} catch (RuntimeException exception) {
			throw CleanupBatchFailedException.afterMeasurement(measurementTime, exception);
		}
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
