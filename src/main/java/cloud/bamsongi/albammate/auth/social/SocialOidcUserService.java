package cloud.bamsongi.albammate.auth.social;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.OAuth2User;

import cloud.bamsongi.albammate.user.contract.SocialProvider;

/**
 * OpenID Connect 제공자의 사용자 속성을 읽는다.
 *
 * <p>Kakao만 특별 취급한다. 이메일 신뢰 상태({@code is_email_valid}, {@code is_email_verified})는 OIDC
 * userinfo에 없고 회원 정보 응답에만 있는데, 그 응답의 사용자 식별자는 {@code sub}가 아니라 {@code id}다. 그래서 표준
 * {@link OidcUserService}의 {@code sub} 일치 검증을 통과하지 못한다. 회원 정보 응답 속성에 ID token의 {@code sub}를
 * 더해 하나의 사용자로 만들고, 식별에는 ID token이 준 {@code sub}만 사용한다.
 */
public final class SocialOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

	private static final String KAKAO_REGISTRATION_ID = SocialClientRegistrationRepository
		.registrationId(SocialProvider.KAKAO);

	private final OidcUserService oidcUserService = new OidcUserService();
	private final DefaultOAuth2UserService userInfoService = new DefaultOAuth2UserService();

	@Override
	public OidcUser loadUser(OidcUserRequest userRequest) {
		if (!KAKAO_REGISTRATION_ID.equals(userRequest.getClientRegistration().getRegistrationId())) {
			return oidcUserService.loadUser(userRequest);
		}

		OAuth2User userInfo = userInfoService.loadUser(
			new OAuth2UserRequest(userRequest.getClientRegistration(), userRequest.getAccessToken()));
		Map<String, Object> claims = new LinkedHashMap<>(userInfo.getAttributes());
		claims.put(IdTokenClaimNames.SUB, userRequest.getIdToken().getSubject());
		return new DefaultOidcUser(
			AuthorityUtils.createAuthorityList("OIDC_USER"),
			userRequest.getIdToken(),
			new OidcUserInfo(claims),
			IdTokenClaimNames.SUB);
	}
}
