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
	boolean usageAvailable,
	BigDecimal costUsd,
	AiProviderFailure failure,
	boolean confirmedUnprocessed) {

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
		return success(action, categories, mechanisms, themes, complexityMax, playTimeMax, playerCount, inputTokens,
			outputTokens, costUsd, true);
	}

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
		BigDecimal costUsd,
		boolean usageAvailable) {
		return new AiProviderResponse(action, List.copyOf(categories), List.copyOf(mechanisms), List.copyOf(themes),
			complexityMax, playTimeMax, playerCount, inputTokens, outputTokens, usageAvailable,
			Objects.requireNonNull(costUsd, "costUsd"), null, false);
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
		return failure(failure, 0, 0, BigDecimal.ZERO, false);
	}

	static AiProviderResponse failureBeforeProcessing(AiProviderFailure failure) {
		return failure(failure, 0, 0, BigDecimal.ZERO, false, true);
	}

	static AiProviderResponse failure(
		AiProviderFailure failure,
		int inputTokens,
		int outputTokens,
		BigDecimal costUsd) {
		return failure(failure, inputTokens, outputTokens, costUsd, true);
	}

	static AiProviderResponse failure(
		AiProviderFailure failure,
		int inputTokens,
		int outputTokens,
		BigDecimal costUsd,
		boolean usageAvailable) {
		return failure(failure, inputTokens, outputTokens, costUsd, usageAvailable, false);
	}

	static AiProviderResponse failure(
		AiProviderFailure failure,
		int inputTokens,
		int outputTokens,
		BigDecimal costUsd,
		boolean usageAvailable,
		boolean confirmedUnprocessed) {
		return new AiProviderResponse(null, List.of(), List.of(), List.of(), null, null, null, inputTokens,
			outputTokens, usageAvailable,
			Objects.requireNonNull(costUsd, "costUsd"), Objects.requireNonNull(failure, "failure"),
			confirmedUnprocessed);
	}

	boolean succeeded() {
		return failure == null;
	}
}
