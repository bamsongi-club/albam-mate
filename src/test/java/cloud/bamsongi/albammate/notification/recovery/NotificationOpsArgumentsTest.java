package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class NotificationOpsArgumentsTest {

	@Test
	void 비양수_중복_상한초과와_잘못된_운영_인자를_입력오류로_거절한다() {
		assertRejected(environment("0", "REPROCESS", "reason", "INC-2026-267", "ops-user", "false", null));
		assertRejected(environment("3,3", "REPROCESS", "reason", "INC-2026-267", "ops-user", "false", null));
		assertRejected(
			environment(moreThanFiftyIds(), "REPROCESS", "reason", "INC-2026-267", "ops-user", "false", null));
		assertRejected(environment("3", "UNKNOWN", "reason", "INC-2026-267", "ops-user", "false", null));
		assertRejected(environment("3", "REPROCESS", " ", "INC-2026-267", "ops-user", "false", null));
		assertRejected(environment("3", "REPROCESS", "reason", "incident-267", "ops-user", "false", null));
		assertRejected(environment("3", "REPROCESS", "reason", "INC-2026-267", " ", "false", null));
		assertRejected(environment("3", "DISCARD", "reason", "INC-2026-267", "ops-user", "false", "DELETE"));
	}

	@Test
	void reason과_requestedBy는_공백을_제거한_값으로_전달한다() {
		NotificationOutboxRecoveryRequest request = NotificationOpsArguments.from(
			environment("3", "REPROCESS", "  reason  ", " ISSUE-267 ", "  ops-user  ", "false", null));
		assertEquals("reason", request.reason());
		assertEquals("ISSUE-267", request.reasonReference());
		assertEquals("ops-user", request.requestedBy());
	}

	@Test
	void trim_후_reason_501자와_requestedBy_101자는_거절하고_경계값은_허용한다() {
		assertRejected(
			environment("3", "REPROCESS", " " + "r".repeat(501) + " ", "INC-2026-267", "ops-user", "false", null));
		assertRejected(
			environment("3", "REPROCESS", "reason", "INC-2026-267", " " + "u".repeat(101) + " ", "false", null));

		NotificationOutboxRecoveryRequest request = NotificationOpsArguments.from(environment(
			"3", "REPROCESS", " " + "r".repeat(500) + " ", "INC-2026-267", " " + "u".repeat(100) + " ", "false", null));
		assertEquals(500, request.reason().length());
		assertEquals(100, request.requestedBy().length());
	}

	@Test
	void INSPECT는_각_감사_메타데이터를_입력오류로_거절한다() {
		assertRejected(inspectEnvironment("app.notification.ops.reason", "private reason"));
		assertRejected(inspectEnvironment("app.notification.ops.reason-reference", "ISSUE-267"));
		assertRejected(inspectEnvironment("app.notification.ops.requested-by", "ops-user"));
		assertRejected(inspectEnvironment("app.notification.ops.confirm", "DISCARD"));
	}

	private static void assertRejected(MockEnvironment environment) {
		assertThrows(NotificationOutboxRecoveryInputException.class, () -> NotificationOpsArguments.from(environment));
	}

	private static MockEnvironment environment(
		String eventIds, String action, String reason, String reasonReference, String requestedBy, String dryRun,
		String confirm) {
		MockEnvironment environment = new MockEnvironment()
			.withProperty("app.notification.ops.event-ids", eventIds)
			.withProperty("app.notification.ops.action", action)
			.withProperty("app.notification.ops.reason", reason)
			.withProperty("app.notification.ops.reason-reference", reasonReference)
			.withProperty("app.notification.ops.requested-by", requestedBy)
			.withProperty("app.notification.ops.dry-run", dryRun);
		if (confirm != null) {
			environment.withProperty("app.notification.ops.confirm", confirm);
		}
		return environment;
	}

	private static String moreThanFiftyIds() {
		StringBuilder ids = new StringBuilder();
		for (int id = 1; id <= 51; id++) {
			if (id > 1) {
				ids.append(',');
			}
			ids.append(id);
		}
		return ids.toString();
	}

	private static MockEnvironment inspectEnvironment(String metadataKey, String metadataValue) {
		return new MockEnvironment()
			.withProperty("app.notification.ops.action", "INSPECT")
			.withProperty("app.notification.ops.event-ids", "3")
			.withProperty(metadataKey, metadataValue);
	}
}
