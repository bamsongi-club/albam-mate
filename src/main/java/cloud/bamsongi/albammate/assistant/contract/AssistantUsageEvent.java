package cloud.bamsongi.albammate.assistant.contract;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Objects;

/** 원문·식별자 없이 허용된 AI 호출량과 추정 비용만 전달하는 event다. */
public record AssistantUsageEvent(
	String provider,
	String model,
	String feature,
	String promptVersion,
	String schemaVersion,
	int inputTokens,
	int outputTokens,
	int totalTokens,
	Duration latency,
	String status,
	BigDecimal costUsd) {

	public AssistantUsageEvent {
		provider = Objects.requireNonNull(provider, "provider");
		model = Objects.requireNonNull(model, "model");
		feature = Objects.requireNonNull(feature, "feature");
		promptVersion = Objects.requireNonNull(promptVersion, "promptVersion");
		schemaVersion = Objects.requireNonNull(schemaVersion, "schemaVersion");
		latency = Objects.requireNonNull(latency, "latency");
		status = Objects.requireNonNull(status, "status");
		costUsd = Objects.requireNonNull(costUsd, "costUsd");
	}
}
