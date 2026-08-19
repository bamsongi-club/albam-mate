package cloud.bamsongi.albammate.assistant.contract;

import java.util.List;
import java.util.Objects;

/** 호출 전 동의·인증 경계가 확인한 현재 문장과 quota 주체다. */
public record AssistantIntentRequest(
	String quotaSubject,
	String currentUserSentence,
	List<String> missingFields,
	boolean externalProcessingConsented) {

	public AssistantIntentRequest {
		quotaSubject = requireText(quotaSubject, "quotaSubject");
		currentUserSentence = requireText(currentUserSentence, "currentUserSentence");
		missingFields = List.copyOf(Objects.requireNonNull(missingFields, "missingFields"));
	}

	public static AssistantIntentRequest forUser(String quotaSubject, String currentUserSentence,
		List<String> missingFields) {
		return new AssistantIntentRequest(quotaSubject, currentUserSentence, missingFields, true);
	}

	public static AssistantIntentRequest withoutConsent(
		String quotaSubject,
		String currentUserSentence,
		List<String> missingFields) {
		return new AssistantIntentRequest(quotaSubject, currentUserSentence, missingFields, false);
	}

	private static String requireText(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}
}
