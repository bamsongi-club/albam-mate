package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.Duration;

record AiProviderSettings(
	String provider,
	boolean enabled,
	boolean providerConfigured,
	boolean noRetentionVerified,
	boolean noTrainingVerified,
	String model,
	Duration timeout,
	int retryCount,
	boolean storeResponses,
	BigDecimal reservationCostUsd) {

	static AiProviderSettings fakeDefaults() {
		return new AiProviderSettings(
			"fake", true, true, true, true, "gpt-5.6-luna", Duration.ofSeconds(10), 0, false,
			new BigDecimal("0.10"));
	}

	AiProviderSettings withEnabled(boolean enabled) {
		return new AiProviderSettings(
			provider, enabled, providerConfigured, noRetentionVerified, noTrainingVerified, model, timeout, retryCount,
			storeResponses, reservationCostUsd);
	}

	boolean readyForCall() {
		boolean externalProviderPolicyReady = "fake".equals(provider)
			|| (noRetentionVerified && noTrainingVerified);
		return enabled && providerConfigured && externalProviderPolicyReady
			&& !storeResponses && timeout.equals(Duration.ofSeconds(10)) && retryCount == 0;
	}
}
