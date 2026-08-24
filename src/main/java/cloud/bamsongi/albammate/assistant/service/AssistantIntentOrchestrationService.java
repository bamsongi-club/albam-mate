package cloud.bamsongi.albammate.assistant.service;

import org.springframework.stereotype.Service;

import cloud.bamsongi.albammate.assistant.contract.AssistantConsentGate;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
import cloud.bamsongi.albammate.assistant.dto.AssistantConditionSummary;
import cloud.bamsongi.albammate.assistant.dto.AssistantMissingField;
import cloud.bamsongi.albammate.assistant.dto.AssistantRecommendationRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantRecommendationResponse;
import cloud.bamsongi.albammate.assistant.dto.AssistantRecommendationState;
import cloud.bamsongi.albammate.game.contract.AssistantExactGameNameQuery;
import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.AssistantRecommendationCandidate;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

/** 사용자 동의 확인을 provider port 진입보다 먼저 수행하는 AI-01 요청 경계다. */
@Service
@RequiredArgsConstructor
public class AssistantIntentOrchestrationService {

	private final AssistantConsentGate assistantConsentGate;
	private final AssistantConsentProperties assistantConsentProperties;
	private final AssistantExactGameNameQuery assistantExactGameNameQuery;
	private final AssistantIntentExtractor assistantIntentExtractor;
	private final AssistantGameCandidateQuery assistantGameCandidateQuery;

	public AssistantIntentExtraction extract(long userId, AssistantIntentRequest request) {
		requireAssistantAccess(userId);
		return assistantIntentExtractor.extract(request);
	}

	public AssistantRecommendationResponse recommend(long userId, AssistantRecommendationRequest request) {
		requireAssistantAccess(userId);
		var exactCandidate = assistantExactGameNameQuery.findUniqueByNormalizedName(request.message());
		if (exactCandidate.isPresent()) {
			AssistantConditionSummary conditions = conditionsFor(request.conditions(), exactCandidate.get());
			assistantGameCandidateQuery.validateCriteria(criteriaFor(conditions));
			return response(AssistantRecommendationState.RECOMMENDED,
				conditions, java.util.List.of(), java.util.List.of(exactCandidate.get()));
		}
		AssistantIntentExtraction extraction = assistantIntentExtractor.extract(
			AssistantIntentRequest.forUser(Long.toString(userId), request.message(), java.util.List.of()));
		if (extraction.status() != AssistantIntentStatus.SUCCESS) {
			throw new BusinessException(errorCodeFor(extraction.status()));
		}
		if (extraction.proposal() == null || "UNSUPPORTED".equals(extraction.proposal().action())) {
			return response(AssistantRecommendationState.UNSUPPORTED,
				AssistantConditionSummary.empty(), java.util.List.of());
		}
		if (!"RECOMMEND".equals(extraction.proposal().action())
			&& !"NEEDS_INPUT".equals(extraction.proposal().action())) {
			return response(AssistantRecommendationState.UNSUPPORTED,
				AssistantConditionSummary.empty(), java.util.List.of());
		}
		AssistantConditionSummary extractedConditions = new AssistantConditionSummary(
			extraction.proposal().categories(), extraction.proposal().mechanisms(), extraction.proposal().themes(),
			extraction.proposal().complexityMax(), extraction.proposal().playTimeMax(), null,
			extraction.proposal().playerCount(), null, null, null);
		AssistantConditionSummary conditions = request.conditions() == null
			? extractedConditions
			: request.conditions().merge(extractedConditions);
		AssistantGameCandidateQuery.Criteria criteria = new AssistantGameCandidateQuery.Criteria(
			conditions.categories(),
			conditions.mechanisms(),
			conditions.themes(),
			conditions.complexityMax(),
			conditions.playTimeMax(),
			conditions.gameId(),
			conditions.playerCount());
		if (hasCatalogCriteria(conditions)) {
			assistantGameCandidateQuery.validateCriteria(criteria);
		}
		if (!conditions.hasRecommendationSearchCondition()) {
			return response(AssistantRecommendationState.NEEDS_INPUT, conditions,
				java.util.List.of(AssistantMissingField.GAME_STYLE));
		}
		var candidates = assistantGameCandidateQuery.findCandidates(criteria);
		return response(candidates.isEmpty() ? AssistantRecommendationState.NO_CANDIDATES
			: AssistantRecommendationState.RECOMMENDED, conditions, java.util.List.of(), candidates);
	}

	private boolean hasCatalogCriteria(AssistantConditionSummary conditions) {
		return conditions.hasRecommendationSearchCondition() || conditions.gameId() != null;
	}

	private void requireAssistantAccess(long userId) {
		assistantConsentGate.requireGranted(userId);
		if (!assistantConsentProperties.isGrantable()) {
			throw new BusinessException(ErrorCode.ASSISTANT_NOT_ENABLED);
		}
	}

	private AssistantGameCandidateQuery.Criteria criteriaFor(AssistantConditionSummary conditions) {
		return new AssistantGameCandidateQuery.Criteria(
			conditions.categories(), conditions.mechanisms(), conditions.themes(), conditions.complexityMax(),
			conditions.playTimeMax(), conditions.gameId(), conditions.playerCount());
	}

	private AssistantConditionSummary conditionsFor(
		AssistantConditionSummary requested,
		AssistantRecommendationCandidate candidate) {
		AssistantConditionSummary source = requested == null ? AssistantConditionSummary.empty() : requested;
		return new AssistantConditionSummary(
			source.categories(), source.mechanisms(), source.themes(), source.complexityMax(), source.playTimeMax(),
			candidate.id(), source.playerCount(), source.startsAt(), source.region(), source.experienceLevel());
	}

	private AssistantRecommendationResponse response(
		AssistantRecommendationState state,
		AssistantConditionSummary conditions,
		java.util.List<AssistantMissingField> missingFields) {
		return response(state, conditions, missingFields, java.util.List.of());
	}

	private AssistantRecommendationResponse response(
		AssistantRecommendationState state,
		AssistantConditionSummary conditions,
		java.util.List<AssistantMissingField> missingFields,
		java.util.List<AssistantRecommendationCandidate> candidates) {
		return new AssistantRecommendationResponse(
			state, conditions, missingFields, candidates);
	}

	private ErrorCode errorCodeFor(AssistantIntentStatus status) {
		return switch (status) {
			case NOT_ENABLED -> ErrorCode.ASSISTANT_NOT_ENABLED;
			case CONSENT_REQUIRED -> ErrorCode.ASSISTANT_CONSENT_REQUIRED;
			case SENSITIVE_INPUT_REJECTED -> ErrorCode.ASSISTANT_INPUT_NOT_ALLOWED;
			case SERVICE_UNAVAILABLE -> ErrorCode.SERVICE_UNAVAILABLE;
			case QUOTA_EXCEEDED -> ErrorCode.RATE_LIMIT_EXCEEDED;
			case COST_CAP_REACHED -> ErrorCode.ASSISTANT_COST_LIMIT_EXCEEDED;
			case PROVIDER_TIMEOUT, PROVIDER_RATE_LIMITED, PROVIDER_INPUT_TOO_LARGE ->
				ErrorCode.ASSISTANT_PROVIDER_UNAVAILABLE;
			case INVALID_PROVIDER_SCHEMA -> ErrorCode.ASSISTANT_PROVIDER_RESPONSE_INVALID;
			case SUCCESS -> throw new IllegalArgumentException("successful extraction has no error code");
		};
	}
}
