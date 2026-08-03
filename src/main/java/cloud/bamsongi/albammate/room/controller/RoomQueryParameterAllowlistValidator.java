package cloud.bamsongi.albammate.room.controller;

import java.util.Set;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;

/** ROOM 목록 API가 허용하는 query parameter 이름만 검사한다. */
final class RoomQueryParameterAllowlistValidator {

	private static final Set<String> ROOM_LIST_PARAMETERS = Set.of(
		"type",
		"gameId",
		"keyword",
		"startsAtFrom",
		"startsAtTo",
		"minRemainingSeats",
		"experienceLevels",
		"rulemasterOnly",
		"page",
		"size");
	private static final Set<String> MY_ROOM_LIST_PARAMETERS = Set.of("role", "page", "size");
	private static final Set<String> RULEMASTER_ONLY_VALUES = Set.of("true", "false");

	private RoomQueryParameterAllowlistValidator() {}

	static void validateRoomList(HttpServletRequest request) {
		validate(request, ROOM_LIST_PARAMETERS);
		if (!hasSingleAllowedRulemasterOnlyValue(request.getParameterValues("rulemasterOnly"))) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	static void validateMyRoomList(HttpServletRequest request) {
		validate(request, MY_ROOM_LIST_PARAMETERS);
	}

	private static void validate(HttpServletRequest request, Set<String> allowedParameterNames) {
		if (!allowedParameterNames.containsAll(request.getParameterMap().keySet())) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	private static boolean hasSingleAllowedRulemasterOnlyValue(String[] values) {
		return values == null || (values.length == 1 && RULEMASTER_ONLY_VALUES.contains(values[0]));
	}
}
