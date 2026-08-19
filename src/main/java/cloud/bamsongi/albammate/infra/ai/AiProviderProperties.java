package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.assistant")
class AiProviderProperties {

	private boolean enabled;
	private String provider = "fake";
	private boolean noRetentionVerified;
	private boolean noTrainingVerified;
	private boolean store;
	private String model = "gpt-5.6-luna";
	private Duration timeout = Duration.ofSeconds(10);
	private int retryCount;
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

	BigDecimal getReservationCostUsd() {
		return reservationCostUsd;
	}

	void setReservationCostUsd(BigDecimal reservationCostUsd) {
		this.reservationCostUsd = reservationCostUsd;
	}
}
