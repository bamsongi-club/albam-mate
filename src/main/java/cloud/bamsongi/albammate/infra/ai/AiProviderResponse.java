package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

record AiProviderResponse(
	String action,
	List<String> categories,
	List<String> mechanisms,
	List<String> themes,
	BigDecimal complexityMax,
	String playTimeMax,
	Integer playerCount,
	int inputTokens,
	int outputTokens,
	BigDecimal costUsd,
	AiProviderFailure failure) {

	static AiProviderResponse success(
		String action,
		List<String> categories,
		List<String> mechanisms,
		List<String> themes,
		BigDecimal complexityMax,
		String playTimeMax,
		Integer playerCount,
		int inputTokens,
		int outputTokens,
		BigDecimal costUsd) {
		return new AiProviderResponse(action, List.copyOf(categories), List.copyOf(mechanisms), List.copyOf(themes),
			complexityMax, playTimeMax, playerCount, inputTokens, outputTokens,
			Objects.requireNonNull(costUsd, "costUsd"), null);
	}

	static AiProviderResponse success(
		String action,
		List<String> categories,
		int inputTokens,
		int outputTokens,
		BigDecimal costUsd) {
		return success(action, categories, List.of(), List.of(), null, null, null, inputTokens, outputTokens,
			costUsd);
	}

	static AiProviderResponse failure(AiProviderFailure failure) {
		return failure(failure, 0, 0, BigDecimal.ZERO);
	}

	static AiProviderResponse failure(
		AiProviderFailure failure,
		int inputTokens,
		int outputTokens,
		BigDecimal costUsd) {
		return new AiProviderResponse(null, List.of(), List.of(), List.of(), null, null, null, inputTokens,
			outputTokens,
			Objects.requireNonNull(costUsd, "costUsd"), Objects.requireNonNull(failure, "failure"));
	}

	boolean succeeded() {
		return failure == null;
	}
}
