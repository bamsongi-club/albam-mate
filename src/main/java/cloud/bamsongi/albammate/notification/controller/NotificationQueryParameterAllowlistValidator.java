package cloud.bamsongi.albammate.notification.controller;

import java.util.Set;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;

/** 알림 목록은 계약한 page·size 이외 query parameter를 허용하지 않는다. */
final class NotificationQueryParameterAllowlistValidator {

	private static final Set<String> LIST_PARAMETERS = Set.of("page", "size");

	private NotificationQueryParameterAllowlistValidator() {}

	static void validateList(HttpServletRequest request) {
		if (!LIST_PARAMETERS.containsAll(request.getParameterMap().keySet())) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}

	static void validateUnreadCount(HttpServletRequest request) {
		if (!request.getParameterMap().isEmpty()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
	}
}
