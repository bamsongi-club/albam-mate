package cloud.bamsongi.albammate.monitoring;

import java.time.Duration;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** ROOM 상태 보정 실행을 유한한 결과와 실행 시간으로만 계측한다. */
@Component
public final class RoomStatusCorrectionMetrics {

	private final Counter completedRuns;
	private final Counter failedRuns;
	private final Counter batchLimitedRuns;
	private final Timer executionDuration;

	public RoomStatusCorrectionMetrics(MeterRegistry meterRegistry) {
		Objects.requireNonNull(meterRegistry, "meterRegistry");
		completedRuns = counter(meterRegistry, "completed");
		failedRuns = counter(meterRegistry, "failed");
		batchLimitedRuns = counter(meterRegistry, "batch_limit");
		executionDuration = Timer.builder("room.status.correction.duration").register(meterRegistry);
	}

	/** 한 scheduler 실행은 completed·failed·batch_limit 가운데 하나의 결과만 남긴다. */
	public void recordRun(RunOutcome outcome, Duration duration) {
		switch (outcome) {
			case COMPLETED -> completedRuns.increment();
			case FAILED -> failedRuns.increment();
			case BATCH_LIMIT -> batchLimitedRuns.increment();
		}
		executionDuration.record(duration);
	}

	private static Counter counter(MeterRegistry meterRegistry, String outcome) {
		return Counter.builder("room.status.correction.runs").tag("outcome", outcome).register(meterRegistry);
	}

	public enum RunOutcome {
		COMPLETED,
		FAILED,
		BATCH_LIMIT
	}
}
