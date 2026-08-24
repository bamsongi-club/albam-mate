package cloud.bamsongi.albammate.notification.relay;

import java.util.Objects;

/** 선점 뒤 롤백된 relay 이벤트를 별도 실패 기록 트랜잭션에 안전하게 전달한다. */
public class NotificationRelayProcessingException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long sourceEventId;
	private final FailureReason failureReason;

	private NotificationRelayProcessingException(long sourceEventId, FailureReason failureReason, Throwable cause) {
		super(null, cause, true, false);
		this.sourceEventId = sourceEventId;
		this.failureReason = Objects.requireNonNull(failureReason, "failureReason");
	}

	public static NotificationRelayProcessingException failed(long sourceEventId, Throwable cause) {
		return new NotificationRelayProcessingException(sourceEventId, FailureReason.PROCESSING_FAILURE, cause);
	}

	public static NotificationRelayProcessingException expired(long sourceEventId) {
		return new NotificationRelayProcessingException(sourceEventId, FailureReason.EXPIRED, null);
	}

	public static NotificationRelayProcessingException missingRecipientSnapshot(long sourceEventId) {
		return new NotificationRelayProcessingException(sourceEventId, FailureReason.MISSING_RECIPIENT_SNAPSHOT, null);
	}

	public long getSourceEventId() {
		return sourceEventId;
	}

	public FailureReason getFailureReason() {
		return failureReason;
	}

	public enum FailureReason {
		EXPIRED,
		MISSING_RECIPIENT_SNAPSHOT,
		PROCESSING_FAILURE
	}
}
