package cloud.bamsongi.albammate.infra.ai;

import java.util.Objects;

import cloud.bamsongi.albammate.assistant.contract.AssistantCostWarningEvent;
import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;

/** 원문이나 사용자 식별자 없이 허용된 AI 사용량과 비용 이벤트를 Micrometer에 기록한다. */
class AssistantUsageEventMetrics {

	private static final String USAGE_EVENTS = "assistant.usage.events";
	private static final String USAGE_TOKENS = "assistant.usage.tokens";
	private static final String USAGE_LATENCY = "assistant.usage.latency";
	private static final String COST_WARNING_EVENTS = "assistant.cost.warning.events";
	private static final String UNKNOWN_LABEL = "unknown";

	private final MeterRegistry meterRegistry;

	AssistantUsageEventMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
		costWarningCounter();
	}

	void recordUsage(AssistantUsageEvent event) {
		Objects.requireNonNull(event, "event");
		Tags tags = usageTags(event);
		Counter.builder(USAGE_EVENTS).tags(tags).register(meterRegistry).increment();
		recordTokens(event);
		Timer.builder(USAGE_LATENCY).register(meterRegistry).record(event.latency());
	}

	void recordCostWarning(AssistantCostWarningEvent event) {
		Objects.requireNonNull(event, "event");
		costWarningCounter().increment();
	}

	private Counter costWarningCounter() {
		return Counter.builder(COST_WARNING_EVENTS).register(meterRegistry);
	}

	private Tags usageTags(AssistantUsageEvent event) {
		return Tags.of("status", observedStatus(event.status()));
	}

	private String observedStatus(String status) {
		return switch (status) {
			case "SUCCESS" -> "SUCCESS";
			case "NOT_ENABLED" -> "NOT_CALLED";
			case "CONSENT_REQUIRED", "SENSITIVE_INPUT_REJECTED", "QUOTA_EXCEEDED", "COST_CAP_REACHED",
				"PROVIDER_INPUT_TOO_LARGE", "INVALID_PROVIDER_SCHEMA" -> "REJECTED";
			case "SERVICE_UNAVAILABLE", "PROVIDER_TIMEOUT", "PROVIDER_RATE_LIMITED" -> "FAILED";
			default -> UNKNOWN_LABEL;
		};
	}

	private void recordTokens(AssistantUsageEvent event) {
		Counter.builder(USAGE_TOKENS).tag("token_type", "input")
			.register(meterRegistry).increment(Math.max(0, event.inputTokens()));
		Counter.builder(USAGE_TOKENS).tag("token_type", "output")
			.register(meterRegistry).increment(Math.max(0, event.outputTokens()));
	}
}
