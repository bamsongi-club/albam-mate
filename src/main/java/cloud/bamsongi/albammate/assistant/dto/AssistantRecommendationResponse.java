package cloud.bamsongi.albammate.assistant.dto;

import java.util.List;
import java.util.Objects;

import cloud.bamsongi.albammate.game.contract.AssistantRecommendationCandidate;

/** AI-02가 후보 조회 뒤 반환하는 부수효과 없는 추천 결과다. */
public record AssistantRecommendationResponse(
	AssistantRecommendationState state,
	AssistantConditionSummary conditions,
	List<AssistantMissingField> missingFields,
	List<AssistantRecommendationCandidate> candidates) {

	public AssistantRecommendationResponse {
		state = Objects.requireNonNull(state, "state");
		conditions = Objects.requireNonNull(conditions, "conditions");
		missingFields = List.copyOf(Objects.requireNonNull(missingFields, "missingFields"));
		candidates = List.copyOf(Objects.requireNonNull(candidates, "candidates"));
	}
}
