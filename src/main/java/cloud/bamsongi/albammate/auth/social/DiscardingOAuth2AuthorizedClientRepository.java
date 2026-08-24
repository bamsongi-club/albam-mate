package cloud.bamsongi.albammate.auth.social;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * 외부 authorized client를 저장하지 않는 저장소다.
 *
 * <p>알밤메이트는 로그인 뒤 제공자 API를 호출하지 않으므로 access·refresh token을 보관할 이유가 없다. 기본 저장소는 이를 서버 세션에
 * 담기 때문에 대신 이 구현을 사용한다.
 */
final class DiscardingOAuth2AuthorizedClientRepository implements OAuth2AuthorizedClientRepository {

	@Override
	public <T extends OAuth2AuthorizedClient> T loadAuthorizedClient(
		String clientRegistrationId, Authentication principal, HttpServletRequest request) {
		return null;
	}

	@Override
	public void saveAuthorizedClient(
		OAuth2AuthorizedClient authorizedClient,
		Authentication principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		// 저장하지 않는다.
	}

	@Override
	public void removeAuthorizedClient(
		String clientRegistrationId,
		Authentication principal,
		HttpServletRequest request,
		HttpServletResponse response) {
		// 저장하지 않으므로 지울 것도 없다.
	}
}
