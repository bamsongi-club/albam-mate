package cloud.bamsongi.albammate.auth.social;

import java.io.IOException;

import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.DefaultRedirectStrategy;
import org.springframework.security.web.RedirectStrategy;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * OAuth 인증 실패와 사용자 취소를 고정 결과로 바꾼다.
 *
 * <p>제공자가 보낸 오류 설명, {@code code}와 token은 리다이렉트 URL에 넣지 않고 결과 값만 전달한다. 실패는 사용자·소셜 계정·로그인
 * 세션을 만들지 않는다.
 *
 * <p>이미 있는 앱 세션은 지우지 않는다. callback은 공개 경로이고 세션 쿠키가 {@code SameSite=Lax}라 top-level GET에 실려
 * 나가므로, 실패 처리가 저장된 인증을 비우면 공격자가 링크 하나로 로그인한 사용자를 로그아웃시킬 수 있다. 실패한 시도의 인증 컨텍스트는
 * {@code AbstractAuthenticationProcessingFilter}가 이미 비우며 저장소에는 쓰지 않는다. 연결 시도는 이 성질 덕분에 기존
 * 로그인이 그대로 남고, 남은 일회성 연결 의도만 폐기한다.
 */
@Component
@RequiredArgsConstructor
public final class SocialLoginFailureHandler implements AuthenticationFailureHandler {

	/** Spring Security가 저장된 authorization request를 찾지 못할 때 사용하는 오류 코드다. */
	private static final String AUTHORIZATION_REQUEST_NOT_FOUND = "authorization_request_not_found";

	@NonNull private final SocialLinkIntentStore linkIntentStore;

	private final RedirectStrategy redirectStrategy = new DefaultRedirectStrategy();

	@Override
	public void onAuthenticationFailure(
		HttpServletRequest request, HttpServletResponse response, AuthenticationException exception)
		throws IOException {
		boolean linkAttempt = linkIntentStore.isLinkCallback(request);
		if (!linkAttempt) {
			linkAttempt = linkIntentStore.discardPendingIntent(request);
		}
		linkIntentStore.consumeCallbackIntent(request);
		redirectStrategy.sendRedirect(request, response, result(exception).location(linkAttempt));
	}

	private SocialAuthResult result(AuthenticationException exception) {
		if (!(exception instanceof OAuth2AuthenticationException oauth2Exception)) {
			return SocialAuthResult.FAILED;
		}
		return switch (oauth2Exception.getError().getErrorCode()) {
			// state가 없거나 다르거나 이미 쓰였으면 저장된 authorization request를 찾지 못하고,
			// state 없는 callback은 authorization 응답으로 인정되지 않는다.
			case AUTHORIZATION_REQUEST_NOT_FOUND, OAuth2ErrorCodes.INVALID_REQUEST -> SocialAuthResult.INVALID_STATE;
			case OAuth2ErrorCodes.ACCESS_DENIED -> SocialAuthResult.CANCELED;
			default -> SocialAuthResult.FAILED;
		};
	}
}
