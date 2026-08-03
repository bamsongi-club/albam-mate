package cloud.bamsongi.albammate.notification.relay;

import java.time.Duration;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** batch 트랜잭션 없이 실행당 처리 상한과 건별 Executor 호출만 조정한다. */
@Service
@Slf4j
@RequiredArgsConstructor
public class NotificationRelayCoordinator {

	@NonNull private final NotificationRelayExecutor executor;
	@NonNull private final NotificationOutboxEventRepository eventRepository;
	@NonNull private final NotificationRelayProperties properties;

	/** 최대 설정 건수까지만 각 이벤트를 독립 트랜잭션으로 처리한다. */
	public RelayBatchSummary processBatch() {
		long startedAtNanos = System.nanoTime();
		int processedCount = 0;
		for (int index = 0; index < properties.getMaxEventsPerRun(); index++) {
			if (executor.processOne().isEmpty()) {
				break;
			}
			processedCount++;
		}

		long oldestProcessableAgeMillis = eventRepository.findOldestProcessableAgeMillis();
		RelayBatchSummary summary = RelayBatchSummary.completed(
			processedCount,
			Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis(),
			oldestProcessableAgeMillis);
		logBatch(summary);
		return summary;
	}

	private void logBatch(RelayBatchSummary summary) {
		if (summary.claimedCount() == 0 && summary.oldestProcessableAgeMillis() == 0) {
			log.debug(
				"event=notification_outbox_relay_batch_completed claimedCount={} processedCount={} retryScheduledCount={} "
					+ "failedCount={} durationMs={} oldestProcessableAgeMs={}",
				summary.claimedCount(), summary.processedCount(), summary.retryScheduledCount(), summary.failedCount(),
				summary.durationMillis(), summary.oldestProcessableAgeMillis());
			return;
		}
		log.info(
			"event=notification_outbox_relay_batch_completed claimedCount={} processedCount={} retryScheduledCount={} "
				+ "failedCount={} durationMs={} oldestProcessableAgeMs={}",
			summary.claimedCount(), summary.processedCount(), summary.retryScheduledCount(), summary.failedCount(),
			summary.durationMillis(), summary.oldestProcessableAgeMillis());
	}

	public record RelayBatchSummary(
		int claimedCount,
		int processedCount,
		int retryScheduledCount,
		int failedCount,
		long durationMillis,
		long oldestProcessableAgeMillis) {

		public static RelayBatchSummary completed(
			int processedCount,
			long durationMillis,
			long oldestProcessableAgeMillis) {
			return new RelayBatchSummary(processedCount, processedCount, 0, 0, durationMillis,
				oldestProcessableAgeMillis);
		}
	}
}
