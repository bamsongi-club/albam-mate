package cloud.bamsongi.albammate.assistant.dto;

import java.time.Instant;

import cloud.bamsongi.albammate.assistant.entity.AssistantConsent;

/** 외부 provider로 보내기 전 사용자에게 보여주는 현재 동의·정책 상태다. */
public record AssistantConsentResponse(
	AssistantConsentStatus status,
	String provider,
	String consentVersion,
	String policyVersion,
	String policyUrl,
	boolean store,
	Instant grantedAt,
	Instant revokedAt) {

	public static AssistantConsentResponse from(AssistantConsent consent) {
		return new AssistantConsentResponse(
			toResponseStatus(consent.getStatus()),
			consent.getProvider(),
			consent.getConsentVersion(),
			consent.getPolicyVersion(),
			consent.getPolicyUrl(),
			consent.isStore(),
			consent.getStatus() == cloud.bamsongi.albammate.assistant.entity.AssistantConsentStatus.GRANTED
				? consent.getGrantedAt()
				: null,
			consent.getRevokedAt());
	}

	public static AssistantConsentResponse fromCurrentPolicy(
		AssistantConsent consent,
		String consentVersion,
		String policyVersion,
		String policyUrl,
		boolean grantIsCurrent) {
		AssistantConsentStatus status = toResponseStatus(consent.getStatus());
		if (status == AssistantConsentStatus.GRANTED && !grantIsCurrent) {
			status = AssistantConsentStatus.NOT_GRANTED;
		}
		return new AssistantConsentResponse(
			status,
			consent.getProvider(),
			consentVersion,
			policyVersion,
			policyUrl,
			status == AssistantConsentStatus.GRANTED && consent.isStore(),
			status == AssistantConsentStatus.GRANTED ? consent.getGrantedAt() : null,
			status == AssistantConsentStatus.REVOKED ? consent.getRevokedAt() : null);
	}

	private static AssistantConsentStatus toResponseStatus(
		cloud.bamsongi.albammate.assistant.entity.AssistantConsentStatus status) {
		return AssistantConsentStatus.valueOf(status.name());
	}

	public static AssistantConsentResponse notGranted(
		String consentVersion,
		String policyVersion,
		String policyUrl) {
		return new AssistantConsentResponse(
			AssistantConsentStatus.NOT_GRANTED,
			AssistantConsent.PROVIDER,
			consentVersion,
			policyVersion,
			policyUrl,
			false,
			null,
			null);
	}
}
