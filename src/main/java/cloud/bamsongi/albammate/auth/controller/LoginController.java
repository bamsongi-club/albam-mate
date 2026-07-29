package cloud.bamsongi.albammate.auth.controller;

import cloud.bamsongi.albammate.auth.dto.LoginRequest;
import cloud.bamsongi.albammate.auth.dto.UserSummary;
import cloud.bamsongi.albammate.auth.service.LoginService;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.global.security.session.SessionCookieConfigurer;
import cloud.bamsongi.albammate.user.contract.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 로그인 HTTP 경계를 담당하고 성공한 인증을 서버 세션에 저장한다. */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public final class LoginController {

    @NonNull private final LoginService loginService;
    @NonNull private final CsrfTokenRepository csrfTokenRepository;
    @NonNull private final SessionCookieConfigurer sessionCookieConfigurer;
    private final SecurityContextRepository securityContextRepository =
            new HttpSessionSecurityContextRepository();

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<UserSummary>> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        UserAccount account =
                loginService.login(request.normalize(), servletRequest.getRemoteAddr());
        establishSession(account, servletRequest, servletResponse);
        csrfTokenRepository.saveToken(null, servletRequest, servletResponse);

        UserSummary userSummary = new UserSummary(account.id(), account.nickname());
        return ResponseEntity.ok(ApiResponse.success(HttpStatus.OK, userSummary));
    }

    private void establishSession(
            UserAccount account,
            HttpServletRequest servletRequest,
            HttpServletResponse servletResponse) {
        var session = servletRequest.getSession(true);
        servletRequest.changeSessionId();

        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(
                        new CurrentUserPrincipal(account.id()),
                        null,
                        AuthorityUtils.NO_AUTHORITIES));
        SecurityContextHolder.setContext(context);
        securityContextRepository.saveContext(context, servletRequest, servletResponse);
        if (!hasSessionCookie(servletResponse)) {
            servletResponse.addCookie(sessionCookieConfigurer.sessionCookie(session.getId()));
        }
    }

    private boolean hasSessionCookie(HttpServletResponse servletResponse) {
        return servletResponse.getHeaders("Set-Cookie").stream()
                .anyMatch(header -> header.startsWith("JSESSIONID="));
    }
}
