package cloud.bamsongi.albammate.assistant.dto;

/** 외부 AI 처리 동의의 변경 요청이다. REVOKE에서는 consentVersion을 보내지 않는다. */
public record AssistantConsentRequest(
	AssistantConsentDecision decision,
	String consentVersion) {
}
