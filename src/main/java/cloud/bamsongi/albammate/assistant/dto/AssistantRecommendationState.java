package cloud.bamsongi.albammate.assistant.dto;

/** 자연어 추천 요청의 성공 상태다. */
public enum AssistantRecommendationState {
	NEEDS_INPUT,
	RECOMMENDED,
	NO_CANDIDATES,
	UNSUPPORTED
}
