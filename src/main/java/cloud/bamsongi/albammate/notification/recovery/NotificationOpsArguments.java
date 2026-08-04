package cloud.bamsongi.albammate.notification.recovery;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
		validateEventIds(eventIds);
		String dryRunValue = environment.getProperty("app.notification.ops.dry-run", "true");
		if (!"true".equals(dryRunValue) && !"false".equals(dryRunValue)) {
			throw new NotificationOutboxRecoveryInputException();
		}
		boolean dryRun = Boolean.parseBoolean(dryRunValue);
		NotificationOutboxRecoveryRequest request = new NotificationOutboxRecoveryRequest(
			action, eventIds, dryRun,
			environment.getProperty("app.notification.ops.reason-reference"),
			environment.getProperty("app.notification.ops.reason"),
			environment.getProperty("app.notification.ops.requested-by"),
			environment.getProperty("app.notification.ops.confirm"));
		validateChangeArguments(request);
		return request;
	}

	private static void validateEventIds(List<Long> eventIds) {
		if (eventIds.isEmpty() || eventIds.size() > 50) {
			throw new NotificationOutboxRecoveryInputException();
		}
		Set<Long> distinctIds = new HashSet<>();
		for (Long eventId : eventIds) {
			if (eventId <= 0 || !distinctIds.add(eventId)) {
				throw new NotificationOutboxRecoveryInputException();
			}
		}
	}

	private static void validateChangeArguments(NotificationOutboxRecoveryRequest request) {
		if (request.action() == NotificationRecoveryAction.INSPECT) {
			validateInspectMetadataIsAbsent(request);
			return;
		}
		if (isBlankOrTooLong(request.reason(), 500) || isBlankOrTooLong(request.requestedBy(), 100)
			|| request.reasonReference() == null
			|| !request.reasonReference().matches("(?:INC-[0-9]{4}-[0-9]{1,10}|ISSUE-[1-9][0-9]{0,9})")) {
			throw new NotificationOutboxRecoveryInputException();
		}
		if (!request.dryRun() && request.action() == NotificationRecoveryAction.DISCARD
			&& !"DISCARD".equals(request.confirm())) {
			throw new NotificationOutboxRecoveryInputException();
		}
	}

	private static void validateInspectMetadataIsAbsent(NotificationOutboxRecoveryRequest request) {
		if (request.reasonReference() != null || request.reason() != null || request.requestedBy() != null
			|| request.confirm() != null) {
			throw new NotificationOutboxRecoveryInputException();
		}
	}

	private static boolean isBlankOrTooLong(String value, int maxLength) {
		return value == null || value.isEmpty() || value.length() > maxLength;
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
