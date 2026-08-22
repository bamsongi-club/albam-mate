package cloud.bamsongi.albammate.assistant.entity;

import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 사용자별 최신 외부 AI 처리 동의와 확인된 provider 정책 메타데이터만 보관한다. */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "assistant_consents")
public class AssistantConsent {

	public static final String PROVIDER = "OPENAI";

	@Id
	@Column(name = "user_id")
	private Long userId;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 20)
	private AssistantConsentStatus status;

	@Column(name = "consent_version", nullable = false, length = 50)
	private String consentVersion;

	@Column(name = "provider", nullable = false, length = 20)
	private String provider;

	@Column(name = "policy_version", nullable = false, length = 100)
	private String policyVersion;

	@Column(name = "policy_url", nullable = false, length = 500)
	private String policyUrl;

	@Column(name = "retention_mode", nullable = false, length = 30)
	private String retentionMode;

	@Column(name = "store", nullable = false)
	private boolean store;

	@Column(name = "granted_at")
	private Instant grantedAt;

	@Column(name = "revoked_at")
	private Instant revokedAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	private AssistantConsent(
		long userId,
		String consentVersion,
		String policyVersion,
		String policyUrl,
		String retentionMode,
		Instant updatedAt) {
		this.userId = userId;
		this.provider = PROVIDER;
		this.store = false;
		this.consentVersion = requireText(consentVersion, "consentVersion");
		this.policyVersion = requireText(policyVersion, "policyVersion");
		this.policyUrl = requireText(policyUrl, "policyUrl");
		this.retentionMode = requireText(retentionMode, "retentionMode");
		this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
	}

	public static AssistantConsent createRevoked(
		long userId,
		String consentVersion,
		String policyVersion,
		String policyUrl,
		String retentionMode,
		Instant revokedAt) {
		AssistantConsent consent = new AssistantConsent(
			userId, consentVersion, policyVersion, policyUrl, retentionMode, revokedAt);
		consent.revoke(revokedAt);
		return consent;
	}

	public static AssistantConsent createGranted(
		long userId,
		String consentVersion,
		String policyVersion,
		String policyUrl,
		String retentionMode,
		Instant grantedAt) {
		AssistantConsent consent = new AssistantConsent(
			userId, consentVersion, policyVersion, policyUrl, retentionMode, grantedAt);
		consent.grant(consentVersion, policyVersion, policyUrl, retentionMode, grantedAt);
		return consent;
	}

	public void grant(
		String consentVersion,
		String policyVersion,
		String policyUrl,
		String retentionMode,
		Instant grantedAt) {
		this.status = AssistantConsentStatus.GRANTED;
		this.consentVersion = requireText(consentVersion, "consentVersion");
		this.policyVersion = requireText(policyVersion, "policyVersion");
		this.policyUrl = requireText(policyUrl, "policyUrl");
		this.retentionMode = requireText(retentionMode, "retentionMode");
		this.provider = PROVIDER;
		this.store = false;
		this.grantedAt = Objects.requireNonNull(grantedAt, "grantedAt");
		this.revokedAt = null;
		this.updatedAt = grantedAt;
	}

	public void revoke(Instant revokedAt) {
		this.status = AssistantConsentStatus.REVOKED;
		this.revokedAt = Objects.requireNonNull(revokedAt, "revokedAt");
		this.updatedAt = revokedAt;
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
