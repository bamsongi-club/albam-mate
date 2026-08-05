package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxEventType;
import cloud.bamsongi.albammate.notification.enums.NotificationOutboxStatus;

class NotificationOutboxRecoveryPolicyTest {

	private static final Instant OPERATION_TIME = Instant.parse("2026-08-03T00:00:00Z");

	private final NotificationOutboxRecoveryPolicy policy = new NotificationOutboxRecoveryPolicy();

	@Test
	void 이벤트_ID는_양수_중복없음_최대_50개만_허용하고_오름차순으로_정규화한다() {
		assertRejected(request(NotificationRecoveryAction.REPROCESS, List.of(0L), true, validMetadata(null)),
			NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);
		assertRejected(request(NotificationRecoveryAction.REPROCESS, List.of(3L, 3L), true, validMetadata(null)),
			NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);
		assertRejected(request(NotificationRecoveryAction.REPROCESS,
			java.util.stream.LongStream.rangeClosed(1, 51).boxed().toList(), true, validMetadata(null)),
			NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);

		assertEquals(List.of(2L, 7L), policy.validateAndNormalize(
			request(NotificationRecoveryAction.REPROCESS, List.of(7L, 2L), true, validMetadata(null)),
			NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW));
	}

	@Test
	void 변경_action은_감사_메타데이터의_필수값_길이_형식과_ISO_제어문자를_검증한다() {
		assertRejected(changeRequest("", "ISSUE-267", "ops-user"));
		assertRejected(changeRequest("r".repeat(501), "ISSUE-267", "ops-user"));
		assertRejected(changeRequest("reason", "incident-267", "ops-user"));
		assertRejected(changeRequest("reason", "ISSUE-267", ""));
		assertRejected(changeRequest("reason", "ISSUE-267", "u".repeat(101)));
		assertRejected(changeRequest("reason", "ISSUE-267", "ops-user\r\nforged"));

		policy.validateAndNormalize(changeRequest("r".repeat(500), "INC-2026-267", "u".repeat(100)),
			NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);
	}

	@Test
	void INSPECT는_감사_메타데이터를_허용하지_않는다() {
		assertRejected(request(NotificationRecoveryAction.INSPECT, List.of(3L), true,
			new Metadata("ISSUE-267", null, null, null)), NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);
		assertRejected(request(NotificationRecoveryAction.INSPECT, List.of(3L), true,
			new Metadata(null, "reason", null, null)), NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);
		assertRejected(request(NotificationRecoveryAction.INSPECT, List.of(3L), true,
			new Metadata(null, null, "ops-user", null)), NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);
		assertRejected(request(NotificationRecoveryAction.INSPECT, List.of(3L), true,
			new Metadata(null, null, null, "DISCARD")), NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);
	}

	@Test
	void EXECUTE는_INSPECT와_dry_run을_거절하고_DISCARD_확인을_요구한다() {
		assertRejected(request(NotificationRecoveryAction.INSPECT, List.of(3L), false,
			new Metadata(null, null, null, null)), NotificationOutboxRecoveryPolicy.ExecutionMode.EXECUTE);
		assertRejected(request(NotificationRecoveryAction.REPROCESS, List.of(3L), true, validMetadata(null)),
			NotificationOutboxRecoveryPolicy.ExecutionMode.EXECUTE);
		assertRejected(request(NotificationRecoveryAction.DISCARD, List.of(3L), false, validMetadata("DELETE")),
			NotificationOutboxRecoveryPolicy.ExecutionMode.EXECUTE);
		policy.validateAndNormalize(
			request(NotificationRecoveryAction.DISCARD, List.of(3L), false, validMetadata("DISCARD")),
			NotificationOutboxRecoveryPolicy.ExecutionMode.EXECUTE);
	}

	@Test
	void FAILED가_아닌_이벤트는_모든_action에서_부적격이다() {
		NotificationOutboxEvent event = event(3L, NotificationOutboxStatus.RETRY_WAIT,
			OPERATION_TIME.minusSeconds(60), "RELAY_PROCESSING_FAILURE");

		for (NotificationRecoveryAction action : NotificationRecoveryAction.values()) {
			NotificationOutboxRecoveryPolicy.RecoveryEligibility result = policy.evaluateEligibility(
				event, action, OPERATION_TIME, true);
			assertFalse(result.reprocessable());
			assertFalse(result.eligible());
		}
	}

	@Test
	void REPROCESS는_수신자_스냅샷과_89일_경계와_만료코드를_함께_판정한다() {
		NotificationOutboxEvent justBefore = failed(3L,
			OPERATION_TIME.minus(Duration.ofDays(89)).plusSeconds(1), "RELAY_PROCESSING_FAILURE");
		NotificationOutboxEvent exactBoundary = failed(4L,
			OPERATION_TIME.minus(Duration.ofDays(89)), "RELAY_PROCESSING_FAILURE");
		NotificationOutboxEvent afterBoundary = failed(5L,
			OPERATION_TIME.minus(Duration.ofDays(89)).minusSeconds(1), "RELAY_PROCESSING_FAILURE");
		NotificationOutboxEvent expiredCode = failed(6L, OPERATION_TIME.minusSeconds(60), "NOTIFICATION_EXPIRED");

		assertEquals(new NotificationOutboxRecoveryPolicy.RecoveryEligibility(true, true),
			policy.evaluateEligibility(justBefore, NotificationRecoveryAction.REPROCESS, OPERATION_TIME, true));
		assertEquals(new NotificationOutboxRecoveryPolicy.RecoveryEligibility(false, false),
			policy.evaluateEligibility(exactBoundary, NotificationRecoveryAction.REPROCESS, OPERATION_TIME, true));
		assertEquals(new NotificationOutboxRecoveryPolicy.RecoveryEligibility(false, false),
			policy.evaluateEligibility(afterBoundary, NotificationRecoveryAction.REPROCESS, OPERATION_TIME, true));
		assertEquals(new NotificationOutboxRecoveryPolicy.RecoveryEligibility(false, false),
			policy.evaluateEligibility(expiredCode, NotificationRecoveryAction.REPROCESS, OPERATION_TIME, true));
		assertEquals(new NotificationOutboxRecoveryPolicy.RecoveryEligibility(false, false),
			policy.evaluateEligibility(justBefore, NotificationRecoveryAction.REPROCESS, OPERATION_TIME, false));
	}

	@Test
	void INSPECT는_FAILED를_적격으로_유지하면서_실제_수신자_여부로_reprocessable을_알린다() {
		NotificationOutboxEvent event = failed(3L, OPERATION_TIME.minusSeconds(60), "RELAY_PROCESSING_FAILURE");

		assertEquals(new NotificationOutboxRecoveryPolicy.RecoveryEligibility(true, true),
			policy.evaluateEligibility(event, NotificationRecoveryAction.INSPECT, OPERATION_TIME, true));
		assertEquals(new NotificationOutboxRecoveryPolicy.RecoveryEligibility(false, true),
			policy.evaluateEligibility(event, NotificationRecoveryAction.INSPECT, OPERATION_TIME, false));
	}

	@Test
	void DISCARD의_eligible은_수신자와_무관하고_reprocessable은_실제_재처리_가능성을_알린다() {
		NotificationOutboxEvent event = failed(3L, OPERATION_TIME.minusSeconds(60), "RELAY_PROCESSING_FAILURE");

		NotificationOutboxRecoveryPolicy.RecoveryEligibility withRecipients = policy.evaluateEligibility(
			event, NotificationRecoveryAction.DISCARD, OPERATION_TIME, true);
		NotificationOutboxRecoveryPolicy.RecoveryEligibility withoutRecipients = policy.evaluateEligibility(
			event, NotificationRecoveryAction.DISCARD, OPERATION_TIME, false);

		assertTrue(withRecipients.reprocessable());
		assertTrue(withRecipients.eligible());
		assertFalse(withoutRecipients.reprocessable());
		assertTrue(withoutRecipients.eligible());
	}

	private void assertRejected(NotificationOutboxRecoveryRequest request) {
		assertRejected(request, NotificationOutboxRecoveryPolicy.ExecutionMode.PREVIEW);
	}

	private void assertRejected(
		NotificationOutboxRecoveryRequest request,
		NotificationOutboxRecoveryPolicy.ExecutionMode mode) {
		assertThrows(NotificationOutboxRecoveryInputException.class,
			() -> policy.validateAndNormalize(request, mode));
	}

	private static NotificationOutboxRecoveryRequest changeRequest(
		String reason,
		String reasonReference,
		String requestedBy) {
		return request(NotificationRecoveryAction.REPROCESS, List.of(3L), true,
			new Metadata(reasonReference, reason, requestedBy, null));
	}

	private static NotificationOutboxRecoveryRequest request(
		NotificationRecoveryAction action,
		List<Long> eventIds,
		boolean dryRun,
		Metadata metadata) {
		return new NotificationOutboxRecoveryRequest(action, eventIds, dryRun, metadata.reasonReference(),
			metadata.reason(), metadata.requestedBy(), metadata.confirm());
	}

	private static Metadata validMetadata(String confirm) {
		return new Metadata("ISSUE-267", "reason", "ops-user", confirm);
	}

	private static NotificationOutboxEvent failed(long eventId, Instant occurredAt, String failureCode) {
		return event(eventId, NotificationOutboxStatus.FAILED, occurredAt, failureCode);
	}

	private static NotificationOutboxEvent event(
		long eventId,
		NotificationOutboxStatus status,
		Instant occurredAt,
		String failureCode) {
		NotificationOutboxEvent event = NotificationOutboxEvent.createPending(
			NotificationOutboxEventType.PARTICIPATION_JOINED, 1L, occurredAt, occurredAt);
		ReflectionTestUtils.setField(event, "id", eventId);
		ReflectionTestUtils.setField(event, "status", status);
		ReflectionTestUtils.setField(event, "lastFailureCode", failureCode);
		return event;
	}

	private record Metadata(String reasonReference, String reason, String requestedBy, String confirm) {
	}
}
