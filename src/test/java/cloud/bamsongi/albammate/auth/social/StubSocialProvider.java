package cloud.bamsongi.albammate.auth.social;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.oauth2.client.endpoint.OAuth2AccessTokenResponseClient;
import org.springframework.security.oauth2.client.endpoint.OAuth2AuthorizationCodeGrantRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserRequest;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.endpoint.OidcParameterNames;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoderFactory;

/**
 * 실제 Client Secret 없이 고정된 provider 응답으로 OAuth 흐름을 검증하는 대역이다.
 *
 * <p>token 교환과 사용자 조회만 대신하고 authorization 요청 생성, {@code state} 검증, 세션 저장과 리다이렉트는 실제 Spring
 * Security filter가 처리한다. OpenID Connect 제공자는 ID token의 {@code nonce} 해시까지 실제 검증 경로를 통과한다.
 */
class StubSocialProvider {

	static final String ACCESS_TOKEN = "stub-access-token";
	private static final String ID_TOKEN_PREFIX = "stub-id-token:";

	private Map<String, Object> attributes = Map.of();

	/** 다음 callback에서 제공자가 돌려줄 사용자 응답을 정한다. */
	void respondWith(Map<String, Object> attributes) {
		this.attributes = Map.copyOf(attributes);
	}

	private OAuth2AccessTokenResponse tokenResponse(OAuth2AuthorizationCodeGrantRequest request) {
		ClientRegistration registration = request.getClientRegistration();
		OAuth2AccessTokenResponse.Builder response = OAuth2AccessTokenResponse.withToken(ACCESS_TOKEN)
			.tokenType(OAuth2AccessToken.TokenType.BEARER)
			.expiresIn(3600)
			.scopes(registration.getScopes());
		if (registration.getScopes().contains(OidcScopes.OPENID)) {
			OAuth2AuthorizationRequest authorizationRequest = request.getAuthorizationExchange()
				.getAuthorizationRequest();
			String nonce = authorizationRequest.getAttribute(OidcParameterNames.NONCE);
			response.additionalParameters(
				Map.of(OidcParameterNames.ID_TOKEN, ID_TOKEN_PREFIX + nonceHash(nonce)));
		}
		return response.build();
	}

	private OAuth2User oauth2User(OAuth2UserRequest request) {
		String nameAttributeKey = request.getClientRegistration()
			.getProviderDetails()
			.getUserInfoEndpoint()
			.getUserNameAttributeName();
		return new DefaultOAuth2User(
			AuthorityUtils.createAuthorityList("OAUTH2_USER"), attributes, nameAttributeKey);
	}

	private OidcUser oidcUser(OidcUserRequest request) {
		return new DefaultOidcUser(
			AuthorityUtils.createAuthorityList("OIDC_USER"), request.getIdToken(), IdTokenClaimNames.SUB);
	}

	private Jwt idToken(ClientRegistration registration, String tokenValue) {
		Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Map<String, Object> claims = new LinkedHashMap<>(attributes);
		claims.put(IdTokenClaimNames.ISS, "https://stub.example.com");
		claims.put(IdTokenClaimNames.AUD, List.of(registration.getClientId()));
		claims.put(OidcParameterNames.NONCE, tokenValue.substring(ID_TOKEN_PREFIX.length()));
		return Jwt.withTokenValue(tokenValue)
			.header("alg", "none")
			.claims(target -> target.putAll(claims))
			.issuedAt(issuedAt)
			.expiresAt(issuedAt.plus(5, ChronoUnit.MINUTES))
			.build();
	}

	private static String nonceHash(String nonce) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(nonce.getBytes(StandardCharsets.US_ASCII));
			return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	/**
	 * 제공자 통신만 대역으로 바꾸고 나머지 OAuth 구성은 생산 코드를 그대로 사용한다.
	 *
	 * <p>Spring Security는 이 대역들을 제네릭 타입으로 찾으므로 람다가 아니라 익명 클래스로 만든다. 람다는 실제 타입에 제네릭 정보가
	 * 없어 {@code OAuth2UserService<?, ?>} 조회에서 서로 구분되지 않는다.
	 */
	@TestConfiguration(proxyBeanMethods = false)
	static class Beans {

		@Bean
		StubSocialProvider stubSocialProvider() {
			return new StubSocialProvider();
		}

		@Bean
		OAuth2AccessTokenResponseClient<OAuth2AuthorizationCodeGrantRequest> stubTokenResponseClient(
			StubSocialProvider provider) {
			return new OAuth2AccessTokenResponseClient<>() {

				@Override
				public OAuth2AccessTokenResponse getTokenResponse(OAuth2AuthorizationCodeGrantRequest request) {
					return provider.tokenResponse(request);
				}
			};
		}

		@Bean
		OAuth2UserService<OAuth2UserRequest, OAuth2User> stubUserService(StubSocialProvider provider) {
			return new OAuth2UserService<OAuth2UserRequest, OAuth2User>() {

				@Override
				public OAuth2User loadUser(OAuth2UserRequest request) {
					return provider.oauth2User(request);
				}
			};
		}

		/** 생산 코드의 OIDC 사용자 조회를 대신하므로 같은 타입에서 우선한다. */
		@Bean
		@Primary
		OAuth2UserService<OidcUserRequest, OidcUser> stubOidcUserService(StubSocialProvider provider) {
			return new OAuth2UserService<OidcUserRequest, OidcUser>() {

				@Override
				public OidcUser loadUser(OidcUserRequest request) {
					return provider.oidcUser(request);
				}
			};
		}

		@Bean
		JwtDecoderFactory<ClientRegistration> stubJwtDecoderFactory(StubSocialProvider provider) {
			return new JwtDecoderFactory<ClientRegistration>() {

				@Override
				public JwtDecoder createDecoder(ClientRegistration registration) {
					return tokenValue -> provider.idToken(registration, tokenValue);
				}
			};
		}
	}
}
