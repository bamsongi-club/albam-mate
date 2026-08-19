package cloud.bamsongi.albammate.assistant.contract;

/** provider 결과를 서버가 후속 검증할 수 있는 구조화 상태로만 반환한다. */
public record AssistantIntentExtraction(
	AssistantIntentStatus status,
	AssistantIntentProposal proposal,
	AssistantUsageEvent usage,
	boolean fallbackUsed) {
}
