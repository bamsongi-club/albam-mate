package cloud.bamsongi.albammate.assistant.contract;

/** AI 요청을 외부 처리 동의 상태로 차단하는 업무 경계다. */
public interface AssistantConsentGate {

	boolean isGranted(long userId);

	void requireGranted(long userId);
}
