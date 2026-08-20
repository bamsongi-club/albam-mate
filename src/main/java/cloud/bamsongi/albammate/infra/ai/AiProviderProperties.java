package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.assistant")
class AiProviderProperties {

	private boolean enabled;
	private String provider = "fake";
	private boolean providerConfigured;
	private boolean noRetentionVerified;
	private boolean noTrainingVerified;
	private boolean store;
	private String policyVersion = "";
	private String policyUrl = "";
	private String model = "gpt-5.6-luna";
	private Duration timeout = Duration.ofSeconds(10);
	private int retryCount;
	private String pricingSnapshot = "";
	private BigDecimal inputTokenPriceUsdPerMillion = BigDecimal.ZERO;
	private BigDecimal outputTokenPriceUsdPerMillion = BigDecimal.ZERO;
	private int maxInputTokens = 4096;
	private int maxOutputTokens = 256;
	private BigDecimal reservationCostUsd = new BigDecimal("0.10");

	boolean isEnabled() {
		return enabled;
	}

	void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	String getProvider() {
		return provider;
	}

	void setProvider(String provider) {
		this.provider = provider;
	}

	boolean isProviderConfigured() {
		return providerConfigured;
	}

	void setProviderConfigured(boolean providerConfigured) {
		this.providerConfigured = providerConfigured;
	}

	boolean isNoRetentionVerified() {
		return noRetentionVerified;
	}

	void setNoRetentionVerified(boolean noRetentionVerified) {
		this.noRetentionVerified = noRetentionVerified;
	}

	boolean isNoTrainingVerified() {
		return noTrainingVerified;
	}

	void setNoTrainingVerified(boolean noTrainingVerified) {
		this.noTrainingVerified = noTrainingVerified;
	}

	boolean isStore() {
		return store;
	}

	void setStore(boolean store) {
		this.store = store;
	}

	String getPolicyVersion() {
		return policyVersion;
	}

	void setPolicyVersion(String policyVersion) {
		this.policyVersion = policyVersion;
	}

	String getPolicyUrl() {
		return policyUrl;
	}

	void setPolicyUrl(String policyUrl) {
		this.policyUrl = policyUrl;
	}

	String getModel() {
		return model;
	}

	void setModel(String model) {
		this.model = model;
	}

	Duration getTimeout() {
		return timeout;
	}

	void setTimeout(Duration timeout) {
		this.timeout = timeout;
	}

	int getRetryCount() {
		return retryCount;
	}

	void setRetryCount(int retryCount) {
		this.retryCount = retryCount;
	}

	String getPricingSnapshot() {
		return pricingSnapshot;
	}

	void setPricingSnapshot(String pricingSnapshot) {
		this.pricingSnapshot = pricingSnapshot;
	}

	BigDecimal getInputTokenPriceUsdPerMillion() {
		return inputTokenPriceUsdPerMillion;
	}

	void setInputTokenPriceUsdPerMillion(BigDecimal inputTokenPriceUsdPerMillion) {
		this.inputTokenPriceUsdPerMillion = inputTokenPriceUsdPerMillion;
	}

	BigDecimal getOutputTokenPriceUsdPerMillion() {
		return outputTokenPriceUsdPerMillion;
	}

	void setOutputTokenPriceUsdPerMillion(BigDecimal outputTokenPriceUsdPerMillion) {
		this.outputTokenPriceUsdPerMillion = outputTokenPriceUsdPerMillion;
	}

	int getMaxInputTokens() {
		return maxInputTokens;
	}

	void setMaxInputTokens(int maxInputTokens) {
		this.maxInputTokens = maxInputTokens;
	}

	int getMaxOutputTokens() {
		return maxOutputTokens;
	}

	void setMaxOutputTokens(int maxOutputTokens) {
		this.maxOutputTokens = maxOutputTokens;
	}

	BigDecimal getReservationCostUsd() {
		return reservationCostUsd;
	}

	void setReservationCostUsd(BigDecimal reservationCostUsd) {
		this.reservationCostUsd = reservationCostUsd;
	}
}
