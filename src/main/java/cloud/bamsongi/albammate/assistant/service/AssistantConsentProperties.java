package cloud.bamsongi.albammate.assistant.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 동의 승인 전에 확인할 provider 정책·기능 활성 상태다. */
@ConfigurationProperties("app.assistant")
public class AssistantConsentProperties {

	private static final String DEFAULT_CONSENT_VERSION = "AI-01-CONSENT-V1";

	private boolean enabled;
	private String provider = "fake";
	private boolean noRetentionVerified;
	private boolean noTrainingVerified;
	private boolean store;
	private String policyVersion = "";
	private String policyUrl = "";
	private String consentVersion = DEFAULT_CONSENT_VERSION;

	public boolean isGrantable() {
		return enabled
			&& isSupportedProvider()
			&& noRetentionVerified
			&& noTrainingVerified
			&& !store
			&& hasText(policyVersion)
			&& hasText(policyUrl)
			&& hasText(consentVersion);
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public void setEnabled(boolean enabled) {
		this.enabled = enabled;
	}

	public void setNoRetentionVerified(boolean noRetentionVerified) {
		this.noRetentionVerified = noRetentionVerified;
	}

	public void setNoTrainingVerified(boolean noTrainingVerified) {
		this.noTrainingVerified = noTrainingVerified;
	}

	public void setStore(boolean store) {
		this.store = store;
	}

	public String getPolicyVersion() {
		return policyVersion;
	}

	public void setPolicyVersion(String policyVersion) {
		this.policyVersion = policyVersion;
	}

	public String getPolicyUrl() {
		return policyUrl;
	}

	public void setPolicyUrl(String policyUrl) {
		this.policyUrl = policyUrl;
	}

	public String getConsentVersion() {
		return consentVersion;
	}

	public void setConsentVersion(String consentVersion) {
		this.consentVersion = consentVersion;
	}

	public String responsePolicyVersion() {
		return hasText(policyVersion) ? policyVersion : "UNVERIFIED";
	}

	public String responsePolicyUrl() {
		return hasText(policyUrl) ? policyUrl : "about:blank";
	}

	private boolean isSupportedProvider() {
		return "fake".equals(provider) || "local-openai".equals(provider);
	}

	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}
}
