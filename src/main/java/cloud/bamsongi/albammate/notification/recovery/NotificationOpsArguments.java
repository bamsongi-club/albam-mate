package cloud.bamsongi.albammate.notification.recovery;

import java.util.Arrays;
import java.util.List;

import org.springframework.core.env.Environment;

/** 환경 바인딩 실패를 기동 실패로 바꾸지 않도록 one-shot 인자를 명시적으로 해석한다. */
final class NotificationOpsArguments {

	private NotificationOpsArguments() {}

	static NotificationOutboxRecoveryRequest from(Environment environment) {
		String actionValue = required(environment, "app.notification.ops.action");
		NotificationRecoveryAction action;
		try {
			action = NotificationRecoveryAction.valueOf(actionValue);
		} catch (IllegalArgumentException exception) {
			throw new NotificationOutboxRecoveryInputException();
		}
		List<Long> eventIds = Arrays.stream(required(environment, "app.notification.ops.event-ids").split(",", -1))
			.map(NotificationOpsArguments::parseEventId)
			.toList();
		String dryRunValue = environment.getProperty("app.notification.ops.dry-run", "true");
		if (!"true".equals(dryRunValue) && !"false".equals(dryRunValue)) {
			throw new NotificationOutboxRecoveryInputException();
		}
		boolean dryRun = Boolean.parseBoolean(dryRunValue);
		return new NotificationOutboxRecoveryRequest(
			action, eventIds, dryRun,
			environment.getProperty("app.notification.ops.reason-reference"),
			environment.getProperty("app.notification.ops.reason"),
			environment.getProperty("app.notification.ops.requested-by"),
			environment.getProperty("app.notification.ops.confirm"));
	}

	private static String required(Environment environment, String key) {
		String value = environment.getProperty(key);
		if (value == null || value.trim().isEmpty()) {
			throw new NotificationOutboxRecoveryInputException();
		}
		return value.trim();
	}

	private static Long parseEventId(String value) {
		try {
			return Long.parseLong(value.trim());
		} catch (NumberFormatException exception) {
			throw new NotificationOutboxRecoveryInputException();
		}
	}
}
