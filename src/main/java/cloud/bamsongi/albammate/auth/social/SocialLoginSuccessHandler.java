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
		boolean linkAttempt = linkIntentStore.isLinkCallback(request);
		SocialLinkIntent linkIntent = linkIntentStore.consumeCallbackIntent(request).orElse(null);
		SocialAuthResult result = handle(request, response, authentication, linkAttempt, linkIntent);
		redirectStrategy.sendRedirect(request, response, result.location(linkAttempt));
	}

	private SocialAuthResult handle(
		HttpServletRequest request,
		HttpServletResponse response,
		Authentication authentication,
		boolean linkAttempt,
		SocialLinkIntent linkIntent) {
		if (!(authentication instanceof OAuth2AuthenticationToken token)) {
			restoreSession(
				SocialLinkCurrentUserFilter.currentUserId(request).orElse(null), request, response);
			return SocialAuthResult.FAILED;
		}
		SocialProvider provider = clientRegistrationRepository
			.configuredProvider(token.getAuthorizedClientRegistrationId())
			.orElse(null);
		if (provider == null) {
			restoreSession(
				SocialLinkCurrentUserFilter.currentUserId(request).orElse(null), request, response);
			return SocialAuthResult.PROVIDER_UNAVAILABLE;
		}
		if (linkAttempt) {
			if (linkIntent == null) {
				restoreSession(
					SocialLinkCurrentUserFilter.currentUserId(request).orElse(null), request, response);
				return SocialAuthResult.INVALID_STATE;
			}
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
	 * <p>제공자 이메일은 연결 대상을 고르는 데 쓰지 않고, 의도의 제공자와 사용자가 callback 직전의 세션 사용자와 모두 맞을 때만 연결한다.
	 * 어느 쪽이든 결과가 정해지면 callback 직전에 로그인해 있던 사용자를 다시 세운다. 의도의 사용자로 되세우면 세션 사용자가 바뀐 경우에 다른
	 * 사용자로 로그인되기 때문이다.
	 */
	private SocialAuthResult link(
		SocialLinkIntent intent,
		SocialProvider provider,
		OAuth2AuthenticationToken token,
		HttpServletRequest request,
		HttpServletResponse response) {
		Long currentUserId = SocialLinkCurrentUserFilter.currentUserId(request).orElse(null);
		if (!intent.provider().equals(provider) || !intent.userId().equals(currentUserId)) {
			restoreSession(currentUserId, request, response);
			return SocialAuthResult.INVALID_STATE;
		}

		SocialLinkResult linkResult;
		try {
			SocialIdentity identity = identityMapper.map(provider, token.getPrincipal().getAttributes());
			linkResult = socialAccountService.link(intent.userId(), identity);
		} catch (RuntimeException exception) {
			restoreSession(currentUserId, request, response);
			return SocialAuthResult.FAILED;
		}

		restoreSession(currentUserId, request, response);
		if (linkResult == SocialLinkResult.LINKED) {
			csrfTokenRepository.saveToken(null, request, response);
			return SocialAuthResult.LINK_SUCCESS;
		}
		return SocialAuthResult.LINK_CONFLICT;
	}

	/**
	 * 연결 시도면 callback 직전의 로그인 사용자를 되돌리고, 로그인 시도면 남은 인증을 지운다.
	 *
	 * <p>OAuth filter는 성공 처리 직전에 세션 인증을 외부 principal로 덮으므로, 연결 결과가 성공이든 실패든 원래 로그인 사용자를 다시
	 * 세우지 않으면 연결 시도만으로 로그아웃된다.
	 */
	private void restoreSession(
		Long userId, HttpServletRequest request, HttpServletResponse response) {
		if (userId == null) {
			sessionEstablisher.discard(request, response);
			return;
		}
		sessionEstablisher.establish(userId, request, response);
	}
}
