package cloud.bamsongi.albammate.auth.social;

import org.springframework.security.oauth2.client.web.AuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 연결 전용 nonce와 Spring Security OAuth authorization request의 state를 같은 세션에서 결속한다. */
@Component
@RequiredArgsConstructor
public final class SocialLinkAuthorizationRequestRepository
	implements AuthorizationRequestRepository<OAuth2AuthorizationRequest> {

	public static final String LINK_NONCE_PARAMETER = "linkNonce";

	private final AuthorizationRequestRepository<OAuth2AuthorizationRequest> delegate = new HttpSessionOAuth2AuthorizationRequestRepository();

	@NonNull private final SocialLinkIntentStore linkIntentStore;

	@Override
	public OAuth2AuthorizationRequest loadAuthorizationRequest(HttpServletRequest request) {
		return delegate.loadAuthorizationRequest(request);
	}

	@Override
	public void saveAuthorizationRequest(
		OAuth2AuthorizationRequest authorizationRequest,
		HttpServletRequest request,
		HttpServletResponse response) {
		if (authorizationRequest != null) {
			String nonce = request.getParameter(LINK_NONCE_PARAMETER);
			if (nonce == null) {
				linkIntentStore.discardPendingIntent(request);
				delegate.saveAuthorizationRequest(null, request, response);
			} else {
				linkIntentStore.bindAuthorizationRequest(request, authorizationRequest.getState(), nonce);
			}
		}
		delegate.saveAuthorizationRequest(authorizationRequest, request, response);
	}

	@Override
	public OAuth2AuthorizationRequest removeAuthorizationRequest(
		HttpServletRequest request, HttpServletResponse response) {
		OAuth2AuthorizationRequest authorizationRequest = delegate.removeAuthorizationRequest(request, response);
		linkIntentStore.activateForCallback(request, request.getParameter("state"));
		if (linkIntentStore.isLinkCallback(request)
			&& (authorizationRequest == null || !request.getRequestURI()
				.substring(request.getRequestURI().lastIndexOf('/') + 1)
				.equals(authorizationRequest.getAttribute(OAuth2ParameterNames.REGISTRATION_ID)))) {
			return null;
		}
		if (!linkIntentStore.isLinkCallback(request) && linkIntentStore.discardPendingIntent(request)) {
			delegate.saveAuthorizationRequest(null, request, response);
			linkIntentStore.markLinkCallback(request);
		}
		return authorizationRequest;
	}
}
