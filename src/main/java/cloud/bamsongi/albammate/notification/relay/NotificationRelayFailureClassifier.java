package cloud.bamsongi.albammate.notification.relay;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** 원본 예외를 저장하거나 로그에 남기지 않고 relay 실패를 안정된 코드로 분류한다. */
@Component
public class NotificationRelayFailureClassifier {

	private static final String MISSING_RECIPIENT_MESSAGE = "claimed notification outbox event has no recipients";

	public FailureClassification classify(NotificationRelayProcessingException processingException) {
		if (processingException.isExpired()) {
			return FailureClassification.deterministic("NOTIFICATION_EXPIRED", "NotificationExpired",
				"Notification event expired before relay processing");
		}

		Throwable cause = processingException.getCause();
		if (cause instanceof IllegalArgumentException) {
			return FailureClassification.deterministic("UNSUPPORTED_EVENT_TYPE", "IllegalArgumentException",
				"Notification event type is unsupported");
		}
		if (cause instanceof IllegalStateException && MISSING_RECIPIENT_MESSAGE.equals(cause.getMessage())) {
			return FailureClassification.deterministic("MISSING_RECIPIENT_SNAPSHOT", "IllegalStateException",
				"Notification recipient snapshot is missing");
		}
		if (cause instanceof DataIntegrityViolationException) {
			return FailureClassification.deterministic("DATA_CONSTRAINT_VIOLATION", "DataIntegrityViolationException",
				"Notification event data violates a required constraint");
		}
		return FailureClassification.transientFailure("RELAY_PROCESSING_FAILURE", failureClass(cause),
			"Notification relay processing failed");
	}

	private String failureClass(Throwable cause) {
		if (cause == null) {
			return "UnknownFailure";
		}
		return cause.getClass().getSimpleName();
	}

	public record FailureClassification(
		String failureCode,
		String failureClass,
		String sanitizedMessage,
		boolean deterministic) {

		private static FailureClassification deterministic(String failureCode, String failureClass,
			String sanitizedMessage) {
			return new FailureClassification(failureCode, failureClass, sanitizedMessage, true);
		}

		private static FailureClassification transientFailure(
			String failureCode,
			String failureClass,
			String sanitizedMessage) {
			return new FailureClassification(failureCode, failureClass, sanitizedMessage, false);
		}
	}
}
