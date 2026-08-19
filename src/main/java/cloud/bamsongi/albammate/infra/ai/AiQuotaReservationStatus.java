package cloud.bamsongi.albammate.infra.ai;

enum AiQuotaReservationStatus {
	ACQUIRED,
	UNAVAILABLE,
	USER_LIMIT_REACHED,
	CONCURRENT_LIMIT_REACHED,
	COST_CAP_REACHED
}
