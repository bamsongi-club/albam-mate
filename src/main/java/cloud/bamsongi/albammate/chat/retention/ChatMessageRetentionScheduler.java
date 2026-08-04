package cloud.bamsongi.albammate.chat.retention;

import java.util.Objects;

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

	@Scheduled(cron = "${app.chat.retention.cron:0 0 3 * * *}", zone = "UTC")
	void purgeExpiredMessages() {
		if (!properties.isEnabled()) {
			return;
		}
		try {
			ScheduledTaskLock.LockExecution execution = scheduledTaskLock.tryExecute(
				LOCK_NAME, properties.getLockAtMostFor(), coordinator::purgeExpiredMessages);
			if (!execution.acquired()) {
				metrics.recordLockSkipped();
				log.info("event=chat_message_retention_lock_skipped lockName={}", LOCK_NAME);
			}
		} catch (RuntimeException exception) {
			metrics.recordExecutionFailure();
			log.error("event=chat_message_retention_failed exceptionClass={}",
				exception.getClass().getSimpleName());
		}
	}
}
