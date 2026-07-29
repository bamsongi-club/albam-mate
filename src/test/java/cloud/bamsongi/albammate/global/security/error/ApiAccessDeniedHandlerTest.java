package cloud.bamsongi.albammate.global.security.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfException;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import tools.jackson.databind.ObjectMapper;

class ApiAccessDeniedHandlerTest {

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void 익명_보호_요청의_CSRF_실패는_UNAUTHENTICATED다() throws IOException {
		MockHttpServletResponse response = handleCsrfFailure(false);

		assertError(response, ErrorCode.UNAUTHENTICATED);
	}

	@Test
	void 공개_인증_요청의_CSRF_실패는_CSRF_TOKEN_INVALID다() throws IOException {
		MockHttpServletResponse response = handleCsrfFailure(true);

		assertError(response, ErrorCode.CSRF_TOKEN_INVALID);
	}

	@Test
	void 인증된_요청의_CSRF_실패는_CSRF_TOKEN_INVALID다() throws IOException {
		SecurityContextHolder.getContext().setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(
				"user", null, AuthorityUtils.NO_AUTHORITIES));

		MockHttpServletResponse response = handleCsrfFailure(false);

		assertError(response, ErrorCode.CSRF_TOKEN_INVALID);
	}

	@Test
	void CSRF가_아닌_접근_거부는_FORBIDDEN이다() throws IOException {
		ApiAccessDeniedHandler handler = handler(false);
		MockHttpServletResponse response = new MockHttpServletResponse();

		handler.handle(new MockHttpServletRequest(), response, new AccessDeniedException("forbidden"));

		assertError(response, ErrorCode.FORBIDDEN);
	}

	private MockHttpServletResponse handleCsrfFailure(boolean publicAuthenticationRequest) throws IOException {
		ApiAccessDeniedHandler handler = handler(publicAuthenticationRequest);
		MockHttpServletResponse response = new MockHttpServletResponse();
		handler.handle(new MockHttpServletRequest(), response, new TestCsrfException());
		return response;
	}

	private ApiAccessDeniedHandler handler(boolean publicAuthenticationRequest) {
		return new ApiAccessDeniedHandler(
			new SecurityErrorResponseWriter(new ObjectMapper()), request -> publicAuthenticationRequest);
	}

	private void assertError(MockHttpServletResponse response, ErrorCode errorCode) throws IOException {
		assertEquals(errorCode.getStatus(), response.getStatus());
		assertTrue(response.getContentAsString().contains("\"code\":\"" + errorCode.getCode() + "\""));
	}

	private static final class TestCsrfException extends CsrfException {

		private TestCsrfException() {
			super("csrf");
		}
	}
}
