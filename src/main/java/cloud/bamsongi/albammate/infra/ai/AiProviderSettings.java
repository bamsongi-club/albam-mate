package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

record AiProviderSettings(
	String provider,
	boolean enabled,
	boolean providerConfigured,
	boolean noRetentionVerified,
	boolean noTrainingVerified,
	String policyVersion,
	String policyUrl,
	String model,
	Duration timeout,
	int retryCount,
	boolean storeResponses,
	String pricingSnapshot,
	BigDecimal inputTokenPriceUsdPerMillion,
	BigDecimal outputTokenPriceUsdPerMillion,
	int maxInputTokens,
	int maxOutputTokens,
	BigDecimal reservationCostUsd) {

	static AiProviderSettings fakeDefaults() {
		return new AiProviderSettings(
			"fake", true, true, true, true, "", "", "gpt-5.6-luna", Duration.ofSeconds(10), 0, false,
			"TEST-PRICING-V1", new BigDecimal("1.00"), new BigDecimal("1.00"), 4096, 256,
			new BigDecimal("0.10"));
	}

	AiProviderSettings withEnabled(boolean enabled) {
		return new AiProviderSettings(
			provider, enabled, providerConfigured, noRetentionVerified, noTrainingVerified, policyVersion, policyUrl,
			model,
			timeout, retryCount, storeResponses, pricingSnapshot, inputTokenPriceUsdPerMillion,
			outputTokenPriceUsdPerMillion, maxInputTokens, maxOutputTokens, reservationCostUsd);
	}

	boolean readyForCall() {
		boolean externalProviderPolicyReady = "fake".equals(provider)
			|| (noRetentionVerified && noTrainingVerified && hasText(policyVersion) && hasText(policyUrl));
		boolean externalProviderPricingReady = "fake".equals(provider)
			|| (hasText(pricingSnapshot) && isPositive(inputTokenPriceUsdPerMillion)
				&& isPositive(outputTokenPriceUsdPerMillion) && maxInputTokens > 0 && maxOutputTokens > 0
				&& isPositive(reservationCostUsd) && reservationCoversMaximumCost());
		return enabled && providerConfigured && externalProviderPolicyReady
			&& externalProviderPricingReady && !storeResponses
			&& Duration.ofSeconds(10).equals(timeout) && retryCount == 0;
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private boolean isPositive(BigDecimal value) {
		return value != null && value.signum() > 0;
	}

	private boolean reservationCoversMaximumCost() {
		BigDecimal maximumTokenCost = inputTokenPriceUsdPerMillion.multiply(BigDecimal.valueOf(maxInputTokens))
			.add(outputTokenPriceUsdPerMillion.multiply(BigDecimal.valueOf(maxOutputTokens)))
			.divide(new BigDecimal("1000000"), 8, RoundingMode.CEILING);
		return reservationCostUsd.compareTo(maximumTokenCost) >= 0;
	}
}
