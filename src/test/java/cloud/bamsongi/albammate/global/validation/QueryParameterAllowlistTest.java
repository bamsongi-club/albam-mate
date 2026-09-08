package cloud.bamsongi.albammate.global.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;

class QueryParameterAllowlistTest {

	@Test
	void 허용된_parameter_이름은_통과하고_알수없는_이름은_VALIDATION_ERROR다() {
		MockHttpServletRequest allowedRequest = new MockHttpServletRequest();
		allowedRequest.addParameter("page", "0");
		allowedRequest.addParameter("size", "10");
		MockHttpServletRequest unknownRequest = new MockHttpServletRequest();
		unknownRequest.addParameter("sort", "createdAt");

		assertDoesNotThrow(() -> QueryParameterAllowlist.validate(allowedRequest, Set.of("page", "size")));

		BusinessException exception = assertThrows(
			BusinessException.class, () -> QueryParameterAllowlist.validate(unknownRequest, Set.of("page", "size")));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
	}
}
