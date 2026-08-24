package cloud.bamsongi.albammate.notification.relay;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.monitoring.NotificationRelayMetrics;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;
import io.micrometer.core.instrument.Metrics;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/** batch 트랜잭션 없이 실행당 처리 상한과 건별 Executor 호출만 조정한다. */
@Service
@Slf4j
public class NotificationRelayCoordinator {

	@NonNull private final NotificationRelayExecutor executor;
	@NonNull private final NotificationRelayFailureRecorder failureRecorder;
	@NonNull private final NotificationOutboxEventRepository eventRepository;
	@NonNull private final NotificationRelayProperties properties;
	@NonNull private final NotificationRelayMetrics metrics;

	public NotificationRelayCoordinator(
		NotificationRelayExecutor executor,
		NotificationRelayFailureRecorder failureRecorder,
		NotificationOutboxEventRepository eventRepository,
		NotificationRelayProperties properties,
		NotificationRelayMetrics... metrics) {
		this.executor = Objects.requireNonNull(executor, "executor");
		this.failureRecorder = Objects.requireNonNull(failureRecorder, "failureRecorder");
		this.eventRepository = Objects.requireNonNull(eventRepository, "eventRepository");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.metrics = metrics.length == 0
			? new NotificationRelayMetrics(Metrics.globalRegistry)
			: Objects.requireNonNull(metrics[0], "metrics");
	}

	/** 최대 설정 건수까지만 각 이벤트를 독립 트랜잭션으로 처리한다. */
	public RelayBatchSummary processBatch() {
		long startedAtNanos = System.nanoTime();
		int claimedCount = 0;
		int processedCount = 0;
		int retryScheduledCount = 0;
		int failedCount = 0;
		for (int index = 0; index < properties.getMaxEventsPerRun(); index++) {
			try {
				if (executor.processOne().isEmpty()) {
					break;
				}
				claimedCount++;
				processedCount++;
			} catch (NotificationRelayProcessingException exception) {
				claimedCount++;
				Optional<NotificationRelayFailureRecorder.RecordedFailure> recordedFailure = failureRecorder
					.record(exception);
				if (recordedFailure.isEmpty()) {
					continue;
				}
				if (recordedFailure.get().retryScheduled()) {
					retryScheduledCount++;
				} else {
					failedCount++;
				}
			}
		}

		Long oldestProcessableAgeMillis = eventRepository.findOldestProcessableAgeMillis();
		metrics.recordOldestProcessableAgeMillis(oldestProcessableAgeMillis);
		RelayBatchSummary summary = new RelayBatchSummary(
			claimedCount,
			processedCount,
			retryScheduledCount,
			failedCount,
			Duration.ofNanos(System.nanoTime() - startedAtNanos).toMillis(),
			oldestProcessableAgeMillis);
		logBatch(summary);
		return summary;
	}

	private void logBatch(RelayBatchSummary summary) {
		if (summary.claimedCount() == 0 && summary.oldestProcessableAgeMillis() == null) {
			log.atDebug().addKeyValue("event", "notification_outbox_relay_batch_completed")
				.addKeyValue("claimedCount", summary.claimedCount())
				.addKeyValue("processedCount", summary.processedCount())
				.addKeyValue("retryScheduledCount", summary.retryScheduledCount())
				.addKeyValue("failedCount", summary.failedCount())
				.addKeyValue("durationMs", summary.durationMillis())
				.addKeyValue("oldestProcessableAgeMs", summary.oldestProcessableAgeMillis())
				.log("notification relay batch completed");
			return;
		}
		log.atInfo().addKeyValue("event", "notification_outbox_relay_batch_completed")
			.addKeyValue("claimedCount", summary.claimedCount()).addKeyValue("processedCount", summary.processedCount())
			.addKeyValue("retryScheduledCount", summary.retryScheduledCount())
			.addKeyValue("failedCount", summary.failedCount())
			.addKeyValue("durationMs", summary.durationMillis())
			.addKeyValue("oldestProcessableAgeMs", summary.oldestProcessableAgeMillis())
			.log("notification relay batch completed");
	}

	public record RelayBatchSummary(
		int claimedCount,
		int processedCount,
		int retryScheduledCount,
		int failedCount,
		long durationMillis,
		Long oldestProcessableAgeMillis) {
	}
}
