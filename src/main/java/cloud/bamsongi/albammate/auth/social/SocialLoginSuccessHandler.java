package cloud.bamsongi.albammate.auth.social;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.auth.security.AppSessionEstablisher;
import cloud.bamsongi.albammate.user.contract.SocialAccountService;
import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialLinkResult;
import cloud.bamsongi.albammate.user.contract.SocialLoginResult;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * OAuth 인증에 성공한 외부 신원을 알밤메이트 세션으로 바꾼다.
 *
 * <p>외부 principal은 여기서 버리고 {@code CurrentUserPrincipal}만 세션에 남긴다. 성공은 세션 ID를 교체하고 기존 CSRF
 * 토큰을 무효화하므로 클라이언트는 다음 상태 변경 전에 CSRF 토큰을 다시 받아야 한다. 로그인을 만들지 않는 결과는 인증을 남기지 않고 고정 결과로만
 * 돌아간다.
 */
@Component
@RequiredArgsConstructor
public final class SocialLoginSuccessHandler implements AuthenticationSuccessHandler {

	@NonNull private final SocialClientRegistrationRepository clientRegistrationRepository;
	@NonNull private final SocialIdentityMapper identityMapper;
	@NonNull private final SocialAccountService socialAccountService;
	@NonNull private final AppSessionEstablisher sessionEstablisher;
	@NonNull private final CsrfTokenRepository csrfTokenRepository;
	@NonNull private final SocialLinkIntentStore linkIntentStore;

	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

	@Override
	public void onAuthenticationSuccess(
		HttpServletRequest request, HttpServletResponse response, Authentication authentication)
		throws IOException {
		SocialAuthResult result = handle(request, response, authentication);
		redirectStrategy.sendRedirect(request, response, result.location());
	}

	private SocialAuthResult handle(
		HttpServletRequest request, HttpServletResponse response, Authentication authentication) {
		if (!(authentication instanceof OAuth2AuthenticationToken token)) {
			sessionEstablisher.discard(request, response);
			return SocialAuthResult.FAILED;
		}
		SocialProvider provider = clientRegistrationRepository
			.configuredProvider(token.getAuthorizedClientRegistrationId())
			.orElse(null);
		if (provider == null) {
			sessionEstablisher.discard(request, response);
			return SocialAuthResult.PROVIDER_UNAVAILABLE;
		}

		SocialLinkIntent linkIntent = linkIntentStore.consume(request).orElse(null);
		if (linkIntent != null) {
			return link(linkIntent, provider, token, request, response);
		}

		SocialLoginResult loginResult;
		try {
			SocialIdentity identity = identityMapper.map(provider, token.getPrincipal().getAttributes());
			loginResult = socialAccountService.login(identity);
		} catch (RuntimeException exception) {
			// 필수 subject 누락과 처리 실패는 같은 고정 결과로 돌아간다. 제공자 응답과 예외 설명은 노출하지 않는다.
			sessionEstablisher.discard(request, response);
			return SocialAuthResult.FAILED;
		}

		if (loginResult instanceof SocialLoginResult.LoggedIn loggedIn) {
			sessionEstablisher.establish(loggedIn.account().id(), request, response);
			csrfTokenRepository.saveToken(null, request, response);
			return SocialAuthResult.LOGIN_SUCCESS;
		}
		sessionEstablisher.discard(request, response);
		return SocialAuthResult.LINK_REQUIRED;
	}

	/**
	 * 연결 의도가 있는 callback을 현재 사용자의 명시적 연결로 처리한다.
	 *
	 * <p>제공자 이메일은 연결 대상을 고르는 데 쓰지 않고 의도에 담긴 사용자를 그대로 연결한다. OAuth filter가 이미 세션 인증을 외부
	 * principal로 덮었으므로, 성공·실패 어느 쪽이든 의도에 담긴 사용자의 앱 세션을 다시 세운다.
	 */
	private SocialAuthResult link(
		SocialLinkIntent intent,
		SocialProvider provider,
		OAuth2AuthenticationToken token,
		HttpServletRequest request,
		HttpServletResponse response) {
		if (!intent.provider().equals(provider)) {
			sessionEstablisher.establish(intent.userId(), request, response);
			return SocialAuthResult.INVALID_STATE;
		}

		SocialLinkResult linkResult;
		try {
			SocialIdentity identity = identityMapper.map(provider, token.getPrincipal().getAttributes());
			linkResult = socialAccountService.link(intent.userId(), identity);
		} catch (RuntimeException exception) {
			sessionEstablisher.establish(intent.userId(), request, response);
			return SocialAuthResult.FAILED;
		}

		sessionEstablisher.establish(intent.userId(), request, response);
		if (linkResult == SocialLinkResult.LINKED) {
			csrfTokenRepository.saveToken(null, request, response);
			return SocialAuthResult.LINK_SUCCESS;
		}
		return SocialAuthResult.LINK_CONFLICT;
	}
}
