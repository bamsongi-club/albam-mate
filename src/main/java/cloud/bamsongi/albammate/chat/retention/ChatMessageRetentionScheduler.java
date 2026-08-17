package cloud.bamsongi.albammate.chat.retention;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;
import lombok.extern.slf4j.Slf4j;

/** 모든 인스턴스가 UTC 일일 작업을 등록하되 ShedLock 획득 실패는 대기 없이 건너뛴다. */
@Component
@Slf4j
class ChatMessageRetentionScheduler {

	static final String LOCK_NAME = "chat-message-retention";

	private final ScheduledTaskLock scheduledTaskLock;
	private final ChatMessageRetentionCoordinator coordinator;
	private final ChatMessageRetentionProperties properties;
	private final ChatMessageRetentionMetrics metrics;

	ChatMessageRetentionScheduler(
		ScheduledTaskLock scheduledTaskLock,
		ChatMessageRetentionCoordinator coordinator,
		ChatMessageRetentionProperties properties,
		ChatMessageRetentionMetrics metrics) {
		this.scheduledTaskLock = Objects.requireNonNull(scheduledTaskLock, "scheduledTaskLock");
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		this.properties = Objects.requireNonNull(properties, "properties");
		this.metrics = Objects.requireNonNull(metrics, "metrics");
	}

	/**
	 * 실행 상한에 걸린 적체를 다음 일일 스케줄로 미루지 않는다. 한 잠금 구간이 상한에서 중단되면 같은
	 * cron 실행 안에서 잠금을 다시 얻어 이어 처리하고, 각 구간은 자신의 임대 안에서만 작업한다.
	 */
	@Scheduled(cron = "${app.chat.retention.cron:0 0 3 * * *}", zone = "UTC")
	void purgeExpiredMessages() {
		if (!properties.isEnabled()) {
			return;
		}
		try {
			for (int section = 1; section <= properties.getMaxLockSectionsPerRun(); section++) {
				AtomicReference<ChatMessageRetentionCoordinator.RetentionRunSummary> summary = new AtomicReference<>();
				ScheduledTaskLock.LockExecution execution = scheduledTaskLock.tryExecute(
					LOCK_NAME, properties.getLockAtMostFor(), properties.getLockAtLeastFor(),
					() -> summary.set(coordinator.purgeExpiredMessages()));
				if (!execution.acquired()) {
					metrics.recordLockSkipped();
					log.atInfo().addKeyValue("event", "chat_message_retention_lock_skipped")
						.addKeyValue("lockName", LOCK_NAME).addKeyValue("section", section)
						.log("chat message retention lock skipped");
					return;
				}
				ChatMessageRetentionCoordinator.RetentionRunSummary result = summary.get();
				if (result == null || !result.leaseGuardAborted()) {
					return;
				}
			}
			metrics.recordBacklogRemaining();
			log.atWarn().addKeyValue("event", "chat_message_retention_backlog_remaining")
				.addKeyValue("maxLockSectionsPerRun", properties.getMaxLockSectionsPerRun())
				.log("chat message retention backlog remaining");
		} catch (RuntimeException exception) {
			metrics.recordExecutionFailure();
			log.atError().addKeyValue("event", "chat_message_retention_failed")
				.addKeyValue("exceptionClass", exception.getClass().getSimpleName())
				.log("chat message retention failed");
		}
	}
}
