package cloud.bamsongi.albammate.assistant.contract;

/** AI provider가 제안한 구조화 의도를 받는 단일 업무 port다. */
public interface AssistantIntentExtractor {

	AssistantIntentExtraction extract(AssistantIntentRequest request);
}
