package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/** Applies the release's per-million-token price snapshot using conservative micro-USD rounding. */
final class AiTokenCostCalculator {

	static final int USD_SCALE = 6;
	private static final BigDecimal TOKENS_PER_MILLION = new BigDecimal("1000000");

	private AiTokenCostCalculator() {}

	static BigDecimal estimate(
		int inputTokens,
		int outputTokens,
		BigDecimal inputPriceUsdPerMillion,
		BigDecimal outputPriceUsdPerMillion) {
		return estimate(inputTokens, null, null, outputTokens, inputPriceUsdPerMillion, outputPriceUsdPerMillion,
			inputPriceUsdPerMillion, inputPriceUsdPerMillion);
	}

	static BigDecimal estimate(
		int inputTokens,
		Integer cachedInputTokens,
		Integer cacheWriteInputTokens,
		int outputTokens,
		BigDecimal inputPriceUsdPerMillion,
		BigDecimal outputPriceUsdPerMillion,
		BigDecimal cachedInputPriceUsdPerMillion,
		BigDecimal cacheWriteInputPriceUsdPerMillion) {
		if (inputTokens < 0 || outputTokens < 0) {
			throw new IllegalArgumentException("token counts must not be negative");
		}
		BigDecimal inputPrice = requireNonNegative(inputPriceUsdPerMillion, "inputPriceUsdPerMillion");
		BigDecimal outputPrice = requireNonNegative(outputPriceUsdPerMillion, "outputPriceUsdPerMillion");
		BigDecimal cachedInputPrice = requireNonNegative(cachedInputPriceUsdPerMillion,
			"cachedInputPriceUsdPerMillion");
		BigDecimal cacheWriteInputPrice = requireNonNegative(cacheWriteInputPriceUsdPerMillion,
			"cacheWriteInputPriceUsdPerMillion");
		if (cachedInputTokens == null && cacheWriteInputTokens == null) {
			return inputPrice.multiply(BigDecimal.valueOf(inputTokens))
				.add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
				.divide(TOKENS_PER_MILLION, USD_SCALE, RoundingMode.CEILING);
		}
		int cachedTokens = nonNegativeTokenCount(cachedInputTokens, "cachedInputTokens");
		int cacheWriteTokens = nonNegativeTokenCount(cacheWriteInputTokens, "cacheWriteInputTokens");
		long classifiedInputTokens = (long)cachedTokens + cacheWriteTokens;
		if (classifiedInputTokens > inputTokens) {
			throw new IllegalArgumentException("classified input tokens must not exceed inputTokens");
		}
		int regularInputTokens = inputTokens - (int)classifiedInputTokens;
		return inputPrice.multiply(BigDecimal.valueOf(regularInputTokens))
			.add(cachedInputPrice.multiply(BigDecimal.valueOf(cachedTokens)))
			.add(cacheWriteInputPrice.multiply(BigDecimal.valueOf(cacheWriteTokens)))
			.add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
			.divide(TOKENS_PER_MILLION, USD_SCALE, RoundingMode.CEILING);
	}

	static BigDecimal maximumCost(AiProviderSettings settings) {
		Objects.requireNonNull(settings, "settings");
		BigDecimal maximumInputPrice = requireNonNegative(settings.inputTokenPriceUsdPerMillion(),
			"inputTokenPriceUsdPerMillion")
			.max(requireNonNegative(settings.cachedInputTokenPriceUsdPerMillion(),
				"cachedInputTokenPriceUsdPerMillion"))
			.max(requireNonNegative(settings.cacheWriteInputTokenPriceUsdPerMillion(),
				"cacheWriteInputTokenPriceUsdPerMillion"));
		return estimate(settings.maxInputTokens(), settings.maxOutputTokens(),
			maximumInputPrice, settings.outputTokenPriceUsdPerMillion());
	}

	static BigDecimal normalize(BigDecimal costUsd) {
		return requireNonNegative(costUsd, "costUsd").setScale(USD_SCALE, RoundingMode.CEILING);
	}

	private static BigDecimal requireNonNegative(BigDecimal value, String name) {
		Objects.requireNonNull(value, name);
		if (value.signum() < 0) {
			throw new IllegalArgumentException(name + " must not be negative");
		}
		return value;
	}

	private static int nonNegativeTokenCount(Integer value, String name) {
		if (value == null) {
			return 0;
		}
		if (value < 0) {
			throw new IllegalArgumentException(name + " must not be negative");
		}
		return value;
	}
}
