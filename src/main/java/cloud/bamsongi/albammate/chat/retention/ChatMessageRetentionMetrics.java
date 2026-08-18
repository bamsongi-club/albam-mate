package cloud.bamsongi.albammate.chat.retention;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 메시지 내용이나 사용자·방 식별자 없이 만료 삭제 실행 상태만 계측한다. */
@Component
class ChatMessageRetentionMetrics {

	private final Counter lockSkipped;
	private final Counter roomsPurged;
	private final Counter messagesDeleted;
	private final Counter failures;
	private final Counter recoveries;
	private final Counter leaseGuardAborted;
	private final Counter backlogRemaining;
	private final Timer executionDuration;
	private final Timer deletionDelay;
	private final AtomicBoolean failureAwaitingRecovery = new AtomicBoolean();

	ChatMessageRetentionMetrics(MeterRegistry meterRegistry) {
		Objects.requireNonNull(meterRegistry, "meterRegistry");
		lockSkipped = Counter.builder("chat.message.retention.lock.skipped").register(meterRegistry);
		roomsPurged = Counter.builder("chat.message.retention.rooms.purged").register(meterRegistry);
		messagesDeleted = Counter.builder("chat.message.retention.messages.deleted").register(meterRegistry);
		failures = Counter.builder("chat.message.retention.failures").register(meterRegistry);
		recoveries = Counter.builder("chat.message.retention.recoveries").register(meterRegistry);
		leaseGuardAborted = Counter.builder("chat.message.retention.lease.guard.aborted").register(meterRegistry);
		backlogRemaining = Counter.builder("chat.message.retention.backlog.remaining").register(meterRegistry);
		executionDuration = Timer.builder("chat.message.retention.execution.duration").register(meterRegistry);
		deletionDelay = Timer.builder("chat.message.retention.delay").register(meterRegistry);
	}

	void recordBacklogRemaining() {
		backlogRemaining.increment();
	}

	void recordLockSkipped() {
		lockSkipped.increment();
	}

	void recordExecutionFailure() {
		failures.increment();
		failureAwaitingRecovery.set(true);
	}

	void recordCompleted(ChatMessageRetentionCoordinator.RetentionRunSummary summary) {
		roomsPurged.increment(summary.purgedRoomCount());
		messagesDeleted.increment(summary.deletedMessageCount());
		failures.increment(summary.failureCount());
		if (summary.failureCount() > 0) {
			failureAwaitingRecovery.set(true);
		} else if (failureAwaitingRecovery.compareAndSet(true, false)) {
			recoveries.increment();
		}
		if (summary.leaseGuardAborted()) {
			leaseGuardAborted.increment();
		}
		if (summary.purgedRoomCount() > 0) {
			deletionDelay.record(Duration.ofMillis(summary.maximumDelayMillis()));
		}
		executionDuration.record(Duration.ofMillis(summary.durationMillis()));
	}
}
