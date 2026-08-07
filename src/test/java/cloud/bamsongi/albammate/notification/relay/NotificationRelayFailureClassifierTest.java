package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class NotificationRelayFailureClassifierTest {

	private final NotificationRelayFailureClassifier classifier = new NotificationRelayFailureClassifier();

	@Test
	void 결정적_실패는_정제된_코드와_설명으로_분류한다() {
		NotificationRelayFailureClassifier.FailureClassification classification = classifier.classify(
			NotificationRelayProcessingException.failed(10L,
				new DataIntegrityViolationException("insert into notifications values (secret)")));

		assertEquals("DATA_CONSTRAINT_VIOLATION", classification.failureCode());
		assertEquals("DataIntegrityViolationException", classification.failureClass());
		assertTrue(classification.deterministic());
		assertFalse(classification.sanitizedMessage().contains("secret"));
	}

	@Test
	void 만료_이벤트는_NOTIFICATION_EXPIRED로_분류한다() {
		NotificationRelayFailureClassifier.FailureClassification classification = classifier.classify(
			NotificationRelayProcessingException.expired(10L));

		assertEquals("NOTIFICATION_EXPIRED", classification.failureCode());
		assertEquals("NotificationExpired", classification.failureClass());
		assertEquals("Notification event expired before relay processing", classification.sanitizedMessage());
		assertTrue(classification.deterministic());
	}

	@Test
	void 분류되지_않은_실패는_원본_메시지를_복사하지_않는다() {
		NotificationRelayFailureClassifier.FailureClassification classification = classifier.classify(
			NotificationRelayProcessingException.failed(10L,
				new IllegalStateException("select * from notification_outbox_events where recipient=987654321")));

		assertEquals("RELAY_PROCESSING_FAILURE", classification.failureCode());
		assertFalse(classification.deterministic());
		assertFalse(classification.sanitizedMessage().contains("987654321"));
	}

	@Test
	void 지원하지_않는_이벤트_유형과_누락된_수신자_스냅샷은_결정적으로_분류한다() {
		NotificationRelayFailureClassifier.FailureClassification unsupportedType = classifier.classify(
			NotificationRelayProcessingException.failed(10L, new IllegalArgumentException("unknown type")));
		NotificationRelayFailureClassifier.FailureClassification missingRecipients = classifier.classify(
			NotificationRelayProcessingException.missingRecipientSnapshot(11L));

		assertEquals("UNSUPPORTED_EVENT_TYPE", unsupportedType.failureCode());
		assertTrue(unsupportedType.deterministic());
		assertEquals("MISSING_RECIPIENT_SNAPSHOT", missingRecipients.failureCode());
		assertTrue(missingRecipients.deterministic());
	}

	@Test
	void 원인_예외가_없으면_안전한_기본_분류를_사용한다() {
		NotificationRelayFailureClassifier.FailureClassification classification = classifier.classify(
			NotificationRelayProcessingException.failed(10L, null));

		assertEquals("RELAY_PROCESSING_FAILURE", classification.failureCode());
		assertEquals("UnknownFailure", classification.failureClass());
		assertFalse(classification.deterministic());
	}

	@Test
	void 수신자_누락과_같은_메시지도_PROCESSING_FAILURE이면_일시_실패로_분류한다() {
		NotificationRelayFailureClassifier.FailureClassification classification = classifier.classify(
			NotificationRelayProcessingException.failed(
				10L, new IllegalStateException("claimed notification outbox event has no recipients")));

		assertEquals("RELAY_PROCESSING_FAILURE", classification.failureCode());
		assertFalse(classification.deterministic());
	}
}
