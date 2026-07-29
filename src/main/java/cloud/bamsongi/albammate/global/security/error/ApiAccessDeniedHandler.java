package cloud.bamsongi.albammate.global.security.error;

import java.io.IOException;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 권한·CSRF 실패를 API 명세의 공통 JSON 오류 봉투로 변환한다. */
@RequiredArgsConstructor
@Component
public final class ApiAccessDeniedHandler implements AccessDeniedHandler {

	@NonNull private final SecurityErrorResponseWriter responseWriter;
	@NonNull private final RequestMatcher publicAuthenticationRequestMatcher;

	@Override
	public void handle(
		HttpServletRequest request,
		HttpServletResponse response,
		AccessDeniedException accessDeniedException)
		throws IOException {
		ErrorCode errorCode = resolveErrorCode(request, accessDeniedException);
		responseWriter.write(response, errorCode);
	}

	private ErrorCode resolveErrorCode(
		HttpServletRequest request, AccessDeniedException accessDeniedException) {
		if (accessDeniedException instanceof CsrfException) {
			return csrfErrorCode(request);
		}
		return ErrorCode.FORBIDDEN;
	}

	private ErrorCode csrfErrorCode(HttpServletRequest request) {
		if (isAuthenticatedRequest() || isPublicAuthenticationRequest(request)) {
			return ErrorCode.CSRF_TOKEN_INVALID;
		}
		return ErrorCode.UNAUTHENTICATED;
	}

	private boolean isAuthenticatedRequest() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
			return false;
		}
		return authentication.isAuthenticated();
	}

	private boolean isPublicAuthenticationRequest(HttpServletRequest request) {
		return publicAuthenticationRequestMatcher.matches(request);
	}
}
