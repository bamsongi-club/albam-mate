package cloud.bamsongi.albammate.auth.social;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.DefaultOAuth2UserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
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

	/** 표준 {@link OidcUserService}가 주체 불일치에 사용하는 오류 코드와 같은 값이다. */
	private static final String INVALID_USER_INFO_RESPONSE = "invalid_user_info_response";

	private final OidcUserService oidcUserService = new OidcUserService();
	private final DefaultOAuth2UserService userInfoService = new DefaultOAuth2UserService();

	@Override
	public OidcUser loadUser(OidcUserRequest userRequest) {
		if (!KAKAO_REGISTRATION_ID.equals(userRequest.getClientRegistration().getRegistrationId())) {
			return oidcUserService.loadUser(userRequest);
		}

		OAuth2User userInfo;
		try {
			userInfo = userInfoService.loadUser(
				new OAuth2UserRequest(userRequest.getClientRegistration(), userRequest.getAccessToken()));
		} catch (IllegalArgumentException exception) {
			// 회원 정보 응답에 이름 속성인 id가 없으면 표준 조회가 여기서 실패한다. 주체를 확인할 수 없는 것은 같다.
			throw subjectMismatch();
		}

		String subject = userRequest.getIdToken().getSubject();
		String userId = serviceUserId(userInfo.getAttributes().get("id"));
		if (userId == null || !userId.equals(subject)) {
			throw subjectMismatch();
		}

		Map<String, Object> claims = new LinkedHashMap<>(userInfo.getAttributes());
		claims.put(IdTokenClaimNames.SUB, subject);
		return new DefaultOidcUser(
			AuthorityUtils.createAuthorityList("OIDC_USER"),
			userRequest.getIdToken(),
			new OidcUserInfo(claims),
			IdTokenClaimNames.SUB);
	}

	/**
	 * 회원 정보 응답의 서비스 사용자 ID를 ID token {@code sub}와 비교할 문자열로 정규화한다.
	 *
	 * <p>제공자가 JSON 정수로 보내므로 크기에 따라 {@code Integer}나 {@code Long}으로 읽힌다. 다른 타입이면 주체를 확인할 수
	 * 없으므로 {@code null}이다.
	 */
	private static String serviceUserId(Object value) {
		return switch (value) {
			case Integer id -> id.toString();
			case Long id -> id.toString();
			case null, default -> null;
		};
	}

	private static OAuth2AuthenticationException subjectMismatch() {
		return new OAuth2AuthenticationException(
			new OAuth2Error(
				INVALID_USER_INFO_RESPONSE,
				"Kakao user info id does not match the ID token subject",
				null));
	}
}
