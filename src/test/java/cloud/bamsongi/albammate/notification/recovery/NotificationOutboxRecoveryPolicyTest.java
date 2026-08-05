package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

class NotificationOutboxRecoveryPolicyTest {

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

	private record Metadata(String reasonReference, String reason, String requestedBy, String confirm) {
	}
}
