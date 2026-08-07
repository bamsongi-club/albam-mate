package cloud.bamsongi.albammate.notification.relay;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 원본 예외를 저장하거나 로그에 남기지 않고 relay 실패를 안정된 코드로 분류한다.
 *
 * <p>발생 지점에서 도메인 코드와 재시도 정책을 확정할 수 있는 예상 불변식·업무 실패는 {@link
 * NotificationRelayProcessingException.FailureReason}으로 전달한다. DB·프레임워크 등 하위 계층의 기술 예외는
 * {@code PROCESSING_FAILURE}의 cause 타입으로만 분류한다. 명시적 Reason을 우선하고 알려진 cause 매핑이 없으면 일시적 실패를
 * 기본값으로 사용한다.
 */
@Component
public class NotificationRelayFailureClassifier {

	public FailureClassification classify(NotificationRelayProcessingException processingException) {
		return switch (processingException.getFailureReason()) {
			case EXPIRED -> FailureClassification.deterministic(
				"NOTIFICATION_EXPIRED", "NotificationExpired", "Notification event expired before relay processing");
			case MISSING_RECIPIENT_SNAPSHOT -> FailureClassification.deterministic(
				"MISSING_RECIPIENT_SNAPSHOT", "IllegalStateException", "Notification recipient snapshot is missing");
			case PROCESSING_FAILURE -> classifyProcessingFailure(processingException.getCause());
		};
	}

	private FailureClassification classifyProcessingFailure(Throwable cause) {
		if (cause instanceof IllegalArgumentException) {
			return FailureClassification.deterministic("UNSUPPORTED_EVENT_TYPE", "IllegalArgumentException",
				"Notification event type is unsupported");
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
