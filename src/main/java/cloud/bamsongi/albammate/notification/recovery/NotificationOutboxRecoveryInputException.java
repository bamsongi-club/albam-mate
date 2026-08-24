package cloud.bamsongi.albammate.notification.recovery;

/** 사용자 입력 또는 전체 대상 적격성 거절은 원문 입력을 노출하지 않는다. */
public class NotificationOutboxRecoveryInputException extends RuntimeException {

	public NotificationOutboxRecoveryInputException() {
		super("notification outbox operation rejected");
	}
}
