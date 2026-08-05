package cloud.bamsongi.albammate.notification.recovery;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/** 운영 복구 요청의 의미 검증과 정규화를 한 곳에서 수행한다. */
@Component
class NotificationOutboxRecoveryPolicy {

	private static final int MAX_EVENT_IDS = 50;
	private static final int MAX_REASON_LENGTH = 500;
	private static final int MAX_REQUESTED_BY_LENGTH = 100;
	private static final Pattern REASON_REFERENCE_PATTERN = Pattern.compile(
		"(?:INC-[0-9]{4}-[0-9]{1,10}|ISSUE-[1-9][0-9]{0,9})");

	List<Long> validateAndNormalize(NotificationOutboxRecoveryRequest request, ExecutionMode mode) {
		if (request == null || request.action() == null || mode == null) {
			throw new NotificationOutboxRecoveryInputException();
		}
		List<Long> eventIds = normalizeEventIds(request.eventIds());
		validateMetadata(request);
		validateExecutionMode(request, mode);
		return eventIds;
	}

	private static List<Long> normalizeEventIds(List<Long> eventIds) {
		if (eventIds == null || eventIds.isEmpty() || eventIds.size() > MAX_EVENT_IDS) {
			throw new NotificationOutboxRecoveryInputException();
		}
		Set<Long> distinctIds = new HashSet<>();
		for (Long eventId : eventIds) {
			if (eventId == null || eventId <= 0 || !distinctIds.add(eventId)) {
				throw new NotificationOutboxRecoveryInputException();
			}
		}
		List<Long> sortedEventIds = new ArrayList<>(distinctIds);
		sortedEventIds.sort(Comparator.naturalOrder());
		return List.copyOf(sortedEventIds);
	}

	private static void validateMetadata(NotificationOutboxRecoveryRequest request) {
		if (request.action() == NotificationRecoveryAction.INSPECT) {
			if (request.reasonReference() != null || request.reason() != null || request.requestedBy() != null
				|| request.confirm() != null) {
				throw new NotificationOutboxRecoveryInputException();
			}
			return;
		}
		if (isBlankOrTooLong(request.reason(), MAX_REASON_LENGTH)
			|| isBlankOrTooLong(request.requestedBy(), MAX_REQUESTED_BY_LENGTH)
			|| request.requestedBy().codePoints().anyMatch(Character::isISOControl)
			|| request.reasonReference() == null
			|| !REASON_REFERENCE_PATTERN.matcher(request.reasonReference()).matches()) {
			throw new NotificationOutboxRecoveryInputException();
		}
	}

	private static void validateExecutionMode(NotificationOutboxRecoveryRequest request, ExecutionMode mode) {
		if (mode != ExecutionMode.EXECUTE) {
			return;
		}
		if (request.action() == NotificationRecoveryAction.INSPECT || request.dryRun()) {
			throw new NotificationOutboxRecoveryInputException();
		}
		if (request.action() == NotificationRecoveryAction.DISCARD && !"DISCARD".equals(request.confirm())) {
			throw new NotificationOutboxRecoveryInputException();
		}
	}

	private static boolean isBlankOrTooLong(String value, int maxLength) {
		return value == null || value.isEmpty() || value.length() > maxLength;
	}

	enum ExecutionMode {
		PREVIEW,
		EXECUTE
	}
}
