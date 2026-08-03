package cloud.bamsongi.albammate.notification.relay;

/** 선점 뒤 롤백된 relay 이벤트를 별도 실패 기록 트랜잭션에 안전하게 전달한다. */
public class NotificationRelayProcessingException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final long sourceEventId;
	private final boolean expired;

	private NotificationRelayProcessingException(long sourceEventId, boolean expired, Throwable cause) {
		super(null, cause, true, false);
		this.sourceEventId = sourceEventId;
		this.expired = expired;
	}

	public static NotificationRelayProcessingException failed(long sourceEventId, Throwable cause) {
		return new NotificationRelayProcessingException(sourceEventId, false, cause);
	}

	public static NotificationRelayProcessingException expired(long sourceEventId) {
		return new NotificationRelayProcessingException(sourceEventId, true, null);
	}

	public long getSourceEventId() {
		return sourceEventId;
	}

	public boolean isExpired() {
		return expired;
	}
}
