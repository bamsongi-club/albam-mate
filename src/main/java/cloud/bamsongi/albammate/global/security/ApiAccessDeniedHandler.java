package cloud.bamsongi.albammate.global.security;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;

/** 권한·CSRF 실패를 API 명세의 공통 JSON 오류 봉투로 변환한다. */
@Component
public final class ApiAccessDeniedHandler implements AccessDeniedHandler {

    private final SecurityErrorResponseWriter responseWriter;
    private final RequestMatcher publicAuthenticationRequestMatcher;

    public ApiAccessDeniedHandler(
            SecurityErrorResponseWriter responseWriter,
            RequestMatcher publicAuthenticationRequestMatcher) {
        this.responseWriter = responseWriter;
        this.publicAuthenticationRequestMatcher = publicAuthenticationRequestMatcher;
    }

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
        if (!(accessDeniedException instanceof CsrfException)) {
            return ErrorCode.FORBIDDEN;
        }

        if (isAnonymous() && !isPublicAuthenticationRequest(request)) {
            return ErrorCode.UNAUTHENTICATED;
        }
        return ErrorCode.CSRF_TOKEN_INVALID;
    }

    private boolean isAnonymous() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken;
    }

    private boolean isPublicAuthenticationRequest(HttpServletRequest request) {
        return publicAuthenticationRequestMatcher.matches(request);
    }
}
