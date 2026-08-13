package cloud.bamsongi.albammate.measurement;

import java.time.Duration;
import java.util.Set;
import java.util.function.Supplier;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 인증·알림의 고정된 내부 단계만 기록해 요청 식별자를 metric tag로 남기지 않는다. */
public final class AuthNotificationMeasurementRecorder {

	private static final Set<String> AUTH_STAGES = Set.of(
		"request-limit", "verification-gate", "failure-limit", "user-lookup", "bcrypt-permit",
		"bcrypt-verify", "bcrypt-upgrade-check", "bcrypt-upgrade-encode", "password-hash-update",
		"failure-record", "failure-reset", "session-context-save", "session-repository-save");
	private static final Set<String> REJECTION_SOURCES = Set.of(
		"ip-limit", "verification-gate", "failure-limit", "bcrypt-slot", "redis-unavailable");
	private static final Set<String> QUERY_STAGES = Set.of("content", "total-count", "unread-count");
	private static final Set<String> RELAY_COLLECTION_STAGES = Set.of(
		"claim", "event-fetch", "recipient-lookup", "recipient-insert-loop", "event-flush", "tx-commit", "tx-total");
	private static final Set<String> TRANSACTION_RESULTS = Set.of("committed", "rolled-back");

	private final MeterRegistry meterRegistry;

	public AuthNotificationMeasurementRecorder(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
		preRegisterMeters();
	}

	public <T> T authStage(String stage, Supplier<T> work) {
		requireAllowed(AUTH_STAGES, stage, "auth stage");
		return authTimer(stage).record(work);
	}

	public void authStage(String stage, Runnable work) {
		requireAllowed(AUTH_STAGES, stage, "auth stage");
		authTimer(stage).record(work);
	}

	public void authRejection(String source) {
		requireAllowed(REJECTION_SOURCES, source, "rejection source");
		rejectionCounter(source).increment();
	}

	public <T> T queryStage(String stage, Supplier<T> work) {
		requireAllowed(QUERY_STAGES, stage, "query stage");
		return queryTimer(stage).record(work);
	}

	public <T> T relayStage(String stage, String result, Supplier<T> work) {
		return relayTimer(stage, result).record(work);
	}

	public void relayStage(String stage, String result, Runnable work) {
		relayTimer(stage, result).record(work);
	}

	/** 호출자가 이미 측정한 단조 시계 구간을 relay Timer에 직접 기록한다. */
	public void recordRelayDuration(String stage, String result, Duration duration) {
		relayTimer(stage, result).record(duration);
	}

	private void preRegisterMeters() {
		AUTH_STAGES.forEach(this::authTimer);
		REJECTION_SOURCES.forEach(this::rejectionCounter);
		QUERY_STAGES.forEach(this::queryTimer);
		RELAY_COLLECTION_STAGES.stream().filter(stage -> !stage.startsWith("tx-"))
			.forEach(stage -> relayTimer(stage, "success"));
		TRANSACTION_RESULTS.forEach(result -> {
			relayTimer("tx-commit", result);
			relayTimer("tx-total", result);
			relayTimer("afterCompletion", result);
		});
	}

	private Timer authTimer(String stage) {
		return Timer.builder("auth.login.stage.duration").tag("stage", stage).register(meterRegistry);
	}

	private Counter rejectionCounter(String source) {
		return Counter.builder("auth.login.rejections").tag("source", source).register(meterRegistry);
	}

	private Timer queryTimer(String stage) {
		return Timer.builder("notification.query.stage.duration").tag("stage", stage).register(meterRegistry);
	}

	private Timer relayTimer(String stage, String result) {
		requireRelayTag(stage, result);
		return Timer.builder("notification.relay.stage.duration")
			.tags("stage", stage, "result", result).register(meterRegistry);
	}

	private static void requireRelayTag(String stage, String result) {
		if ("afterCompletion".equals(stage)) {
			requireAllowed(TRANSACTION_RESULTS, result, "relay transaction result");
		} else {
			requireAllowed(RELAY_COLLECTION_STAGES, stage, "relay stage");
			if (stage.startsWith("tx-")) {
				requireAllowed(TRANSACTION_RESULTS, result, "relay transaction result");
			} else {
				requireAllowed(Set.of("success"), result, "relay result");
			}
		}
	}

	private static void requireAllowed(Set<String> allowed, String value, String label) {
		if (!allowed.contains(value)) {
			throw new IllegalArgumentException("unapproved " + label + ": " + value);
		}
	}
}
