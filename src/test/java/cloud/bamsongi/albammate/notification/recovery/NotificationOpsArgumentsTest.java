package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class NotificationOpsArgumentsTest {

	@Test
	void 필수값과_알_수_없는_action과_숫자가_아닌_ID를_거절한다() {
		assertRejected(new MockEnvironment());
		assertRejected(environment("3", "UNKNOWN", "true"));
		assertRejected(environment("not-a-number", "REPROCESS", "true"));
	}

	@Test
	void dry_run은_소문자_true_false만_허용한다() {
		assertTrue(NotificationOpsArguments.from(environment("3", "REPROCESS", "true")).dryRun());
		assertEquals(false, NotificationOpsArguments.from(environment("3", "REPROCESS", "false")).dryRun());
		assertRejected(environment("3", "REPROCESS", "TRUE"));
		assertRejected(environment("3", "REPROCESS", "yes"));
	}

	@Test
	void dry_run_기본값과_문자열_trim을_적용해_요청으로_전달한다() {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("app.notification.ops.action", " REPROCESS ")
			.withProperty("app.notification.ops.event-ids", " 7, 3 ")
			.withProperty("app.notification.ops.reason-reference", " ISSUE-267 ")
			.withProperty("app.notification.ops.reason", " reason ")
			.withProperty("app.notification.ops.requested-by", " ops-user ");

		NotificationOutboxRecoveryRequest request = NotificationOpsArguments.from(environment);

		assertTrue(request.dryRun());
		assertEquals(NotificationRecoveryAction.REPROCESS, request.action());
		assertEquals(java.util.List.of(7L, 3L), request.eventIds());
		assertEquals("ISSUE-267", request.reasonReference());
		assertEquals("reason", request.reason());
		assertEquals("ops-user", request.requestedBy());
	}

	private static void assertRejected(MockEnvironment environment) {
		assertThrows(NotificationOutboxRecoveryInputException.class, () -> NotificationOpsArguments.from(environment));
	}

	private static MockEnvironment environment(String eventIds, String action, String dryRun) {
		return new MockEnvironment()
			.withProperty("app.notification.ops.event-ids", eventIds)
			.withProperty("app.notification.ops.action", action)
			.withProperty("app.notification.ops.dry-run", dryRun);
	}
}
