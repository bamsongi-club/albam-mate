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
import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;

/** 사용자 동의 확인을 provider port 진입보다 먼저 수행하는 AI-01 요청 경계다. */
@Service
@RequiredArgsConstructor
public class AssistantIntentOrchestrationService {

	private final AssistantConsentGate assistantConsentGate;
	private final AssistantIntentExtractor assistantIntentExtractor;
	private final AssistantGameCandidateQuery assistantGameCandidateQuery;

	public AssistantIntentExtraction extract(long userId, AssistantIntentRequest request) {
		assistantConsentGate.requireGranted(userId);
		return assistantIntentExtractor.extract(request);
	}

	public AssistantRecommendationResponse recommend(long userId, AssistantRecommendationRequest request) {
		AssistantIntentExtraction extraction = extract(userId,
			AssistantIntentRequest.forUser(Long.toString(userId), request.message(), java.util.List.of()));
		if (extraction.status() != AssistantIntentStatus.SUCCESS) {
			throw new BusinessException(errorCodeFor(extraction.status()));
		}
		if (extraction.proposal() == null || !"RECOMMEND".equals(extraction.proposal().action())) {
			return response(AssistantRecommendationState.UNSUPPORTED,
				AssistantConditionSummary.empty(), java.util.List.of());
		}
		AssistantConditionSummary extractedConditions = new AssistantConditionSummary(
			extraction.proposal().gameStyles());
		AssistantConditionSummary conditions = request.conditions() == null
			? extractedConditions
			: request.conditions().merge(extractedConditions);
		if (!conditions.hasRecommendationSearchCondition()) {
			return response(AssistantRecommendationState.NEEDS_INPUT, conditions,
				java.util.List.of(AssistantMissingField.GAME_STYLE));
		}
		var candidates = assistantGameCandidateQuery.findCandidates(new AssistantGameCandidateQuery.Criteria(
			conditions.categories(),
			conditions.mechanisms(),
			conditions.themes(),
			conditions.complexityMax(),
			conditions.playTimeMax(),
			conditions.gameId(),
			conditions.playerCount()));
		return response(candidates.isEmpty() ? AssistantRecommendationState.NO_CANDIDATES
			: AssistantRecommendationState.RECOMMENDED, conditions, java.util.List.of(), candidates);
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
		java.util.List<cloud.bamsongi.albammate.game.contract.GameSummary> candidates) {
		return new AssistantRecommendationResponse(
			state, conditions, missingFields, candidates);
	}

	private ErrorCode errorCodeFor(AssistantIntentStatus status) {
		return switch (status) {
			case NOT_ENABLED -> ErrorCode.ASSISTANT_NOT_ENABLED;
			case CONSENT_REQUIRED -> ErrorCode.ASSISTANT_CONSENT_REQUIRED;
			case QUOTA_EXCEEDED -> ErrorCode.RATE_LIMIT_EXCEEDED;
			default -> ErrorCode.SERVICE_UNAVAILABLE;
		};
	}
}
