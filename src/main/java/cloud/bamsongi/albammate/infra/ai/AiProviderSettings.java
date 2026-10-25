package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
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
	BigDecimal cachedInputTokenPriceUsdPerMillion,
	BigDecimal cacheWriteInputTokenPriceUsdPerMillion,
	int maxInputTokens,
	int maxOutputTokens,
	String retentionMode) {

	AiProviderSettings(
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
		String retentionMode) {
		this(provider, enabled, providerConfigured, noRetentionVerified, noTrainingVerified, policyVersion, policyUrl,
			model, timeout, retryCount, storeResponses, pricingSnapshot, inputTokenPriceUsdPerMillion,
			outputTokenPriceUsdPerMillion, inputTokenPriceUsdPerMillion, inputTokenPriceUsdPerMillion,
			maxInputTokens, maxOutputTokens, retentionMode);
	}

	static AiProviderSettings fakeDefaults() {
		return new AiProviderSettings(
			"fake", true, true, true, true, "", "", "gpt-5.6-luna", Duration.ofSeconds(10), 0, false,
			"TEST-PRICING-V1", new BigDecimal("1.00"), new BigDecimal("1.00"), 4096, 256, "unverified");
	}

	AiProviderSettings withEnabled(boolean enabled) {
		return new AiProviderSettings(
			provider, enabled, providerConfigured, noRetentionVerified, noTrainingVerified, policyVersion, policyUrl,
			model,
			timeout, retryCount, storeResponses, pricingSnapshot, inputTokenPriceUsdPerMillion,
			outputTokenPriceUsdPerMillion, cachedInputTokenPriceUsdPerMillion,
			cacheWriteInputTokenPriceUsdPerMillion, maxInputTokens, maxOutputTokens, retentionMode);
	}

	boolean readyForCall() {
		boolean externalProviderPolicyReady = "fake".equals(provider)
			|| (retentionPolicyReady() && noTrainingVerified && hasText(policyVersion) && hasText(policyUrl));
		boolean externalProviderPricingReady = "fake".equals(provider)
			|| (hasText(pricingSnapshot) && isPositive(inputTokenPriceUsdPerMillion)
				&& isPositive(outputTokenPriceUsdPerMillion) && isPositive(cachedInputTokenPriceUsdPerMillion)
				&& isPositive(cacheWriteInputTokenPriceUsdPerMillion)
				&& maxInputTokens > 0 && maxOutputTokens > 0);
		return enabled && providerConfigured && externalProviderPolicyReady
			&& externalProviderPricingReady && !storeResponses
			&& Duration.ofSeconds(10).equals(timeout) && retryCount == 0;
	}

	private boolean retentionPolicyReady() {
		return switch (retentionMode) {
			case "default-30d" -> !noRetentionVerified;
			case "zero-data-retention" -> noRetentionVerified;
			default -> false;
		};
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	private boolean isPositive(BigDecimal value) {
		return value != null && value.signum() > 0;
	}

	BigDecimal maximumCostUsd() {
		return AiTokenCostCalculator.maximumCost(this);
	}
}
