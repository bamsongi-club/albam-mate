package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;

import cloud.bamsongi.albammate.assistant.contract.AssistantCostWarningEvent;
import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
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
	private static final BigDecimal WARNING_THRESHOLD_USD = new BigDecimal("4.00");
	private static final Set<String> ALLOWED_STATUSES = Set.of(
		"SUCCESS",
		"NOT_ENABLED",
		"CONSENT_REQUIRED",
		"SENSITIVE_INPUT_REJECTED",
		"SERVICE_UNAVAILABLE",
		"QUOTA_EXCEEDED",
		"COST_CAP_REACHED",
		"PROVIDER_TIMEOUT",
		"PROVIDER_RATE_LIMITED",
		"INVALID_PROVIDER_SCHEMA");

	private final MeterRegistry meterRegistry;

	AssistantUsageEventMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
	}

	void recordUsage(AssistantUsageEvent event) {
		Objects.requireNonNull(event, "event");
		Tags tags = usageTags(event);
		Counter.builder(USAGE_EVENTS).tags(tags).register(meterRegistry).increment();
		recordTokens(event, tags);
		Timer.builder(USAGE_LATENCY).tags(tags).register(meterRegistry).record(event.latency());
	}

	void recordCostWarning(AssistantCostWarningEvent event) {
		Objects.requireNonNull(event, "event");
		Counter.builder(COST_WARNING_EVENTS)
			.tag("warning_threshold_usd", boundedWarningThreshold(event.warningThresholdUsd()))
			.register(meterRegistry)
			.increment();
	}

	private Tags usageTags(AssistantUsageEvent event) {
		return Tags.of("status", bounded(event.status(), ALLOWED_STATUSES));
	}

	private String bounded(String value, Set<String> allowedValues) {
		return allowedValues.contains(value) ? value : UNKNOWN_LABEL;
	}

	private String boundedWarningThreshold(BigDecimal value) {
		return value.compareTo(WARNING_THRESHOLD_USD) == 0
			? WARNING_THRESHOLD_USD.toPlainString()
			: UNKNOWN_LABEL;
	}

	private void recordTokens(AssistantUsageEvent event, Tags tags) {
		DistributionSummary.builder(USAGE_TOKENS).tags(tags.and("token_type", "input"))
			.register(meterRegistry).record(event.inputTokens());
		DistributionSummary.builder(USAGE_TOKENS).tags(tags.and("token_type", "output"))
			.register(meterRegistry).record(event.outputTokens());
		DistributionSummary.builder(USAGE_TOKENS).tags(tags.and("token_type", "total"))
			.register(meterRegistry).record(event.inputTokens() + event.outputTokens());
	}
}
