package cloud.bamsongi.albammate.notification.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

class NotificationQueryParameterAllowlistValidatorTest {

	@Test
	void 목록과_미확인_개수의_허용하지_않은_parameter는_VALIDATION_ERROR다() {
		MockHttpServletRequest listRequest = new MockHttpServletRequest();
		listRequest.addParameter("sort", "id");
		MockHttpServletRequest unreadCountRequest = new MockHttpServletRequest();
		unreadCountRequest.setRequestURI("/api/users/me/notifications/unread-count");
		unreadCountRequest.addParameter("sort", "id");

		BusinessException listException = assertThrows(
			BusinessException.class, () -> NotificationQueryParameterAllowlistValidator.validateList(listRequest));
		BusinessException unreadCountException = assertThrows(
			BusinessException.class,
			() -> NotificationQueryParameterAllowlistValidator.validateUnreadCount(unreadCountRequest));

		assertEquals(ErrorCode.VALIDATION_ERROR, listException.getErrorCode());
		assertEquals(ErrorCode.VALIDATION_ERROR, unreadCountException.getErrorCode());
	}
}
