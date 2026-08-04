package cloud.bamsongi.albammate.notification.recovery;

import java.util.List;

/** one-shot adapter가 검증한 운영 명령만 Service로 전달한다. */
public record NotificationOutboxRecoveryRequest(
	NotificationRecoveryAction action,
	List<Long> eventIds,
	boolean dryRun,
	String reasonReference,
	String reason,
	String requestedBy,
	String confirm) {

	public NotificationOutboxRecoveryRequest {
		reasonReference = trim(reasonReference);
		reason = trim(reason);
		requestedBy = trim(requestedBy);
		confirm = trim(confirm);
	}

	private static String trim(String value) {
		return value == null ? null : value.trim();
	}
}
