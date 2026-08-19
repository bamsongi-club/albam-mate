package cloud.bamsongi.albammate.assistant.contract;

/** 공개 HTTP 오류 매핑 전 provider·quota 경계의 실패 상태다. */
public enum AssistantIntentStatus {
	SUCCESS,
	NOT_ENABLED,
	CONSENT_REQUIRED,
	SENSITIVE_INPUT_REJECTED,
	SERVICE_UNAVAILABLE,
	QUOTA_EXCEEDED,
	COST_CAP_REACHED,
	PROVIDER_TIMEOUT,
	PROVIDER_RATE_LIMITED,
	INVALID_PROVIDER_SCHEMA
}
