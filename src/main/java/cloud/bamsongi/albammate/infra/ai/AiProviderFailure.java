package cloud.bamsongi.albammate.infra.ai;

enum AiProviderFailure {
	TIMEOUT,
	RATE_LIMITED,
	INPUT_TOO_LARGE,
	INVALID_SCHEMA,
	SERVICE_UNAVAILABLE
}
