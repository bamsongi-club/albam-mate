package cloud.bamsongi.albammate.room.service.command;

import java.util.Objects;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/** 대기열의 외부 operation과 커밋된 최종 결과만 유한 tag로 기록한다. */
@Component
final class RoomWaitlistMetrics {

	private final MeterRegistry meterRegistry;

	RoomWaitlistMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
	}

	void recordJoinAccepted() {
		record("join", "accepted");
	}

	void recordJoinRejected() {
		record("join", "rejected");
	}

	void recordJoinFailed() {
		record("join", "failed");
	}

	void recordCancelAccepted() {
		record("cancel", "accepted");
	}

	void recordCancelRejected() {
		record("cancel", "rejected");
	}

	void recordCancelFailed() {
		record("cancel", "failed");
	}

	void recordPromoteAccepted() {
		record("promote", "accepted");
	}

	void recordPromoteFailed() {
		record("promote", "failed");
	}

	private void record(String operation, String outcome) {
		Counter.builder("room.waitlist.operations")
			.tag("operation", operation)
			.tag("outcome", outcome)
			.register(meterRegistry)
			.increment();
	}
}
