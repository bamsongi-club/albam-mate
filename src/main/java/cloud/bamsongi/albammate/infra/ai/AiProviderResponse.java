package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

record AiProviderResponse(
	String action,
	List<String> gameStyles,
	int inputTokens,
	int outputTokens,
	BigDecimal costUsd,
	AiProviderFailure failure) {

	static AiProviderResponse success(
		String action,
		List<String> gameStyles,
		int inputTokens,
		int outputTokens,
		BigDecimal costUsd) {
		return new AiProviderResponse(action, List.copyOf(gameStyles), inputTokens, outputTokens,
			Objects.requireNonNull(costUsd, "costUsd"), null);
	}

	static AiProviderResponse failure(AiProviderFailure failure) {
		return new AiProviderResponse(null, List.of(), 0, 0, BigDecimal.ZERO,
			Objects.requireNonNull(failure, "failure"));
	}

	boolean succeeded() {
		return failure == null;
	}
}
