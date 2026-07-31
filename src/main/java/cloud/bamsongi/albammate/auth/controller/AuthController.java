package cloud.bamsongi.albammate.auth.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.CookieClearingLogoutHandler;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cloud.bamsongi.albammate.auth.dto.CsrfTokenResponse;
import cloud.bamsongi.albammate.auth.dto.LoginRequest;
import cloud.bamsongi.albammate.auth.dto.SignupRequest;
import cloud.bamsongi.albammate.auth.dto.UserSummary;
import cloud.bamsongi.albammate.auth.service.LoginService;
import cloud.bamsongi.albammate.auth.service.SignupService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.session.SessionCookieConfigurer;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** CSRF 토큰 발급과 회원가입·로그인·로그아웃 HTTP 경계를 담당한다. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public final class AuthController {

	@NonNull private final SignupService signupService;
	@NonNull private final LoginService loginService;
	@NonNull private final CsrfTokenRepository csrfTokenRepository;
	@NonNull private final SecurityContextRepository securityContextRepository;
	@NonNull private final SessionCookieConfigurer sessionCookieConfigurer;

	@GetMapping("/csrf")
	public ApiResponse<CsrfTokenResponse> getCsrfToken(CsrfToken csrfToken) {
		CsrfTokenResponse response = new CsrfTokenResponse(csrfToken.getHeaderName(), csrfToken.getToken());
		return ApiResponse.success(HttpStatus.OK, response);
	}

	@PostMapping("/signup")
	public ResponseEntity<ApiResponse<UserSummary>> signup(
		@Valid @RequestBody
		SignupRequest request, HttpServletRequest servletRequest) {
		UserAccount account = signupService.signup(request.normalize(), servletRequest.getRemoteAddr());
		UserSummary userSummary = UserSummary.from(account);
		return ResponseEntity.status(HttpStatus.CREATED)
			.body(ApiResponse.success(HttpStatus.CREATED, userSummary));
	}

	@PostMapping("/login")
	public ResponseEntity<ApiResponse<UserSummary>> login(
		@Valid @RequestBody
		LoginRequest request,
		HttpServletRequest servletRequest,
		HttpServletResponse servletResponse) {
		UserAccount account = loginService.login(request.normalize(), servletRequest.getRemoteAddr());
		establishSession(account, servletRequest, servletResponse);
		csrfTokenRepository.saveToken(null, servletRequest, servletResponse);

		UserSummary userSummary = UserSummary.from(account);
		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, userSummary));
	}

	@PostMapping("/logout")
	public ResponseEntity<ApiResponse<Map<String, Object>>> logout(
		HttpServletRequest servletRequest, HttpServletResponse servletResponse) {
		csrfTokenRepository.saveToken(null, servletRequest, servletResponse);

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		new SecurityContextLogoutHandler().logout(servletRequest, servletResponse, authentication);
		new CookieClearingLogoutHandler(sessionCookieConfigurer.expiredSessionCookie())
			.logout(servletRequest, servletResponse, authentication);

		return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK));
	}

	/**
	 * 로그인 성공 사용자를 세션 인증으로 등록하고 세션 ID를 교체해 세션 고정 공격을 방어한다.
	 */
	private void establishSession(
		UserAccount account,
		HttpServletRequest servletRequest,
		HttpServletResponse servletResponse) {
		// changeSessionId()는 세션이 있어야 하므로 먼저 만든다. JSESSIONID 발급은 컨테이너가 담당한다.
		servletRequest.getSession(true);
		servletRequest.changeSessionId();

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(account.id()),
				null,
				AuthorityUtils.NO_AUTHORITIES));
		SecurityContextHolder.setContext(context);
		securityContextRepository.saveContext(context, servletRequest, servletResponse);
	}
}
