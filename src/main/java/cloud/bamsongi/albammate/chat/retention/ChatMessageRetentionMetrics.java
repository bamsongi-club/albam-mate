package cloud.bamsongi.albammate.chat.retention;

import java.time.Duration;
import java.util.Objects;

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
	private final Timer executionDuration;

	ChatMessageRetentionMetrics(MeterRegistry meterRegistry) {
		Objects.requireNonNull(meterRegistry, "meterRegistry");
		lockSkipped = Counter.builder("chat.message.retention.lock.skipped").register(meterRegistry);
		roomsPurged = Counter.builder("chat.message.retention.rooms.purged").register(meterRegistry);
		messagesDeleted = Counter.builder("chat.message.retention.messages.deleted").register(meterRegistry);
		failures = Counter.builder("chat.message.retention.failures").register(meterRegistry);
		executionDuration = Timer.builder("chat.message.retention.execution.duration").register(meterRegistry);
	}

	void recordLockSkipped() {
		lockSkipped.increment();
	}

	void recordExecutionFailure() {
		failures.increment();
	}

	void recordCompleted(ChatMessageRetentionCoordinator.RetentionRunSummary summary) {
		roomsPurged.increment(summary.purgedRoomCount());
		messagesDeleted.increment(summary.deletedMessageCount());
		failures.increment(summary.failureCount());
		executionDuration.record(Duration.ofMillis(summary.durationMillis()));
	}
}
