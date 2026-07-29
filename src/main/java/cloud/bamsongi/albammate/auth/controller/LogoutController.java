package cloud.bamsongi.albammate.auth.controller;

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

import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.session.SessionCookieConfigurer;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 로그아웃 HTTP 경계에서 세션·인증·CSRF 상태를 함께 무효화한다. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public final class LogoutController {

	@NonNull private final CsrfTokenRepository csrfTokenRepository;
	@NonNull private final SessionCookieConfigurer sessionCookieConfigurer;

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
		return sessionCookieConfigurer.expiredSessionCookie();
	}
}
