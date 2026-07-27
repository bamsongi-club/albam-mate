package cloud.bamsongi.albammate.auth.controller;

import cloud.bamsongi.albammate.global.config.SecurityCookieProperties;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그아웃 HTTP 경계에서 세션·인증·CSRF 상태를 함께 무효화한다. */
@RestController
@RequestMapping("/api/auth")
public final class LogoutController {

    private final CsrfTokenRepository csrfTokenRepository;
    private final SecurityCookieProperties cookieProperties;

    public LogoutController(
            CsrfTokenRepository csrfTokenRepository, SecurityCookieProperties cookieProperties) {
        this.csrfTokenRepository =
                Objects.requireNonNull(csrfTokenRepository, "csrfTokenRepository");
        this.cookieProperties = Objects.requireNonNull(cookieProperties, "cookieProperties");
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<java.util.Map<String, Object>>> logout(
            HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
        csrfTokenRepository.saveToken(null, servletRequest, servletResponse);

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        new SecurityContextLogoutHandler().logout(servletRequest, servletResponse, authentication);
        new CookieClearingLogoutHandler(sessionCookieToClear())
                .logout(servletRequest, servletResponse, authentication);

        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK));
    }

    private Cookie sessionCookieToClear() {
        Cookie cookie = new Cookie("JSESSIONID", "");
        cookie.setMaxAge(0);
        cookie.setPath("/");
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieProperties.isSecure());
        cookie.setAttribute("SameSite", "Lax");
        return cookie;
    }
}
