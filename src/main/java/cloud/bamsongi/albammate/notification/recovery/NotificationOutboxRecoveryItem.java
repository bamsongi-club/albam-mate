package cloud.bamsongi.albammate.notification.recovery;

import java.time.Instant;

/** 수신자·payload·자유 서술 사유 없이 운영자가 대상별 상태를 판정하는 출력 행이다. */
public record NotificationOutboxRecoveryItem(
	Long eventId,
	String status,
	String eventType,
	Instant occurredAt,
	Instant expiresAt,
	int failureCount,
	int totalFailureCount,
	String lastFailureCode,
	boolean reprocessable,
	boolean eligible) {

	public static NotificationOutboxRecoveryItem missing(Long eventId) {
		return new NotificationOutboxRecoveryItem(eventId, "MISSING", null, null, null, 0, 0, null, false, false);
	}
}
