package cloud.bamsongi.albammate.assistant.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.assistant.contract.AssistantConsentGate;
import cloud.bamsongi.albammate.assistant.contract.AssistantConsentRevokedEvent;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentDecision;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentResponse;
import cloud.bamsongi.albammate.assistant.entity.AssistantConsent;
import cloud.bamsongi.albammate.assistant.entity.AssistantConsentStatus;
import cloud.bamsongi.albammate.assistant.repository.AssistantConsentRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

/** AI-01 동의 저장과 provider 호출 전 fail-closed 경계를 조정한다. */
@Service
@RequiredArgsConstructor
public class AssistantConsentService implements AssistantConsentGate {

	private final AssistantConsentRepository consentRepository;
	private final AssistantConsentProperties properties;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;

	@Transactional(readOnly = true)
	public AssistantConsentResponse getConsent(long userId) {
		return consentRepository.findById(userId)
			.map(AssistantConsentResponse::from)
			.orElseGet(() -> AssistantConsentResponse.notGranted(
				properties.getConsentVersion(),
				properties.responsePolicyVersion(),
				properties.responsePolicyUrl()));
	}

	@Transactional
	public AssistantConsentResponse changeConsent(long userId, AssistantConsentRequest request) {
		validateRequest(request);
		if (request.decision() == AssistantConsentDecision.GRANT) {
			return grant(userId, request.consentVersion());
		}
		return revoke(userId);
	}

	@Override
	@Transactional(readOnly = true)
	public boolean isGranted(long userId) {
		return consentRepository.findById(userId)
			.map(consent -> consent.getStatus() == AssistantConsentStatus.GRANTED)
			.orElse(false);
	}

	@Override
	@Transactional(readOnly = true)
	public void requireGranted(long userId) {
		if (!isGranted(userId)) {
			throw new BusinessException(ErrorCode.ASSISTANT_CONSENT_REQUIRED);
		}
	}

	private AssistantConsentResponse grant(long userId, String requestedConsentVersion) {
		if (!properties.isGrantable()) {
			throw new BusinessException(ErrorCode.ASSISTANT_NOT_ENABLED);
		}
		validateGrantVersion(requestedConsentVersion);
		Instant now = clock.instant();
		AssistantConsent consent = consentRepository.findByUserIdForUpdate(userId)
			.orElseGet(() -> AssistantConsent.createGranted(
				userId,
				properties.getConsentVersion(),
				properties.getPolicyVersion(),
				properties.getPolicyUrl(),
				now));
		consent.grant(
			properties.getConsentVersion(),
			properties.getPolicyVersion(),
			properties.getPolicyUrl(),
			now);
		return AssistantConsentResponse.from(consentRepository.saveAndFlush(consent));
	}

	private AssistantConsentResponse revoke(long userId) {
		Instant now = clock.instant();
		AssistantConsent consent = consentRepository.findByUserIdForUpdate(userId)
			.orElseGet(() -> AssistantConsent.createRevoked(
				userId,
				properties.getConsentVersion(),
				properties.responsePolicyVersion(),
				properties.responsePolicyUrl(),
				now));
		consent.revoke(now);
		AssistantConsentResponse response = AssistantConsentResponse.from(consentRepository.saveAndFlush(consent));
		eventPublisher.publishEvent(new AssistantConsentRevokedEvent(userId, now));
		return response;
	}

	private void validateRequest(AssistantConsentRequest request) {
		if (request == null || request.decision() == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (request.decision() == AssistantConsentDecision.REVOKE
			&& request.consentVersion() != null
			&& !request.consentVersion().isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private void validateGrantVersion(String requestedConsentVersion) {
		if (requestedConsentVersion == null || requestedConsentVersion.isBlank()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		if (!properties.getConsentVersion().equals(requestedConsentVersion)) {
			throw new BusinessException(ErrorCode.ASSISTANT_CONSENT_VERSION_MISMATCH);
		}
	}
}
