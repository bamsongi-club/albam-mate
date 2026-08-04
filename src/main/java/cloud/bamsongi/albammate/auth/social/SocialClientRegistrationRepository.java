package cloud.bamsongi.albammate.auth.social;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.auth.social.SocialOAuthProperties.Credentials;
import cloud.bamsongi.albammate.user.contract.SocialProvider;

/**
 * 현재 실행 환경에 설정된 제공자만 담는 OAuth client 등록부다.
 *
 * <p>등록이 하나도 없어도 비어 있는 상태로 만들어지므로 자격증명 없이 애플리케이션이 기동한다. 노출 순서는
 * {@link SocialProvider} 선언 순서인 {@code GOOGLE}, {@code NAVER}, {@code KAKAO}다.
 */
@Component
public final class SocialClientRegistrationRepository
	implements ClientRegistrationRepository, Iterable<ClientRegistration> {

	public static final String AUTHORIZATION_BASE_URI = "/api/auth/social/authorization";
	public static final String CALLBACK_BASE_URI = "/api/auth/social/callback";

	/**
	 * callback URI는 사용자가 접속한 same-site 주소에서 계산한다.
	 *
	 * <p>{@code {baseUrl}}은 현재 요청의 scheme·host·port로 치환되므로 로컬 Vite와 운영 도메인이 각자의 주소를
	 * 그대로 사용한다.
	 */
	private static final String REDIRECT_URI = "{baseUrl}" + CALLBACK_BASE_URI + "/{registrationId}";

	private final Map<String, ClientRegistration> registrationsById;
	private final List<SocialProvider> configuredProviders;

	public SocialClientRegistrationRepository(SocialOAuthProperties properties) {
		Map<String, ClientRegistration> registrations = new LinkedHashMap<>();
		List<SocialProvider> configured = new ArrayList<>();
		for (SocialProvider provider : SocialProvider.values()) {
			Credentials credentials = properties.getProviders().get(provider);
			if (credentials == null || !credentials.isConfigured()) {
				continue;
			}
			configured.add(provider);
			registrations.put(registrationId(provider), registration(provider, credentials));
		}
		this.registrationsById = Map.copyOf(registrations);
		this.configuredProviders = List.copyOf(configured);
	}

	public static String registrationId(SocialProvider provider) {
		return provider.name().toLowerCase(Locale.ROOT);
	}

	@Override
	public ClientRegistration findByRegistrationId(String registrationId) {
		return registrationsById.get(registrationId);
	}

	@Override
	public Iterator<ClientRegistration> iterator() {
		return configuredProviders.stream()
			.map(provider -> registrationsById.get(registrationId(provider)))
			.iterator();
	}

	/** 설정된 제공자를 노출 순서로 반환한다. */
	public List<SocialProvider> configuredProviders() {
		return configuredProviders;
	}

	/** 경로값이 지원·설정된 제공자를 가리킬 때만 그 제공자를 반환한다. */
	public Optional<SocialProvider> configuredProvider(String registrationId) {
		return configuredProviders.stream()
			.filter(provider -> registrationId(provider).equals(registrationId))
			.findFirst();
	}

	private static ClientRegistration registration(SocialProvider provider, Credentials credentials) {
		return switch (provider) {
			case GOOGLE -> CommonOAuth2Provider.GOOGLE.getBuilder(registrationId(provider))
				.clientId(credentials.getClientId())
				.clientSecret(credentials.getClientSecret())
				.redirectUri(REDIRECT_URI)
				.build();
			case NAVER -> naver(credentials);
			case KAKAO -> kakao(credentials);
		};
	}

	/** Naver는 OpenID Connect를 쓰지 않으므로 회원 프로필 응답의 {@code response.id}로 식별한다. */
	private static ClientRegistration naver(Credentials credentials) {
		return ClientRegistration.withRegistrationId(registrationId(SocialProvider.NAVER))
			.clientId(credentials.getClientId())
			.clientSecret(credentials.getClientSecret())
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri(REDIRECT_URI)
			.authorizationUri("https://nid.naver.com/oauth2.0/authorize")
			.tokenUri("https://nid.naver.com/oauth2.0/token")
			.userInfoUri("https://openapi.naver.com/v1/nid/me")
			.userNameAttributeName("response")
			.clientName("Naver")
			.build();
	}

	/**
	 * Kakao는 OpenID Connect의 {@code sub}로 식별한다.
	 *
	 * <p>이메일 신뢰 상태는 OIDC userinfo에 없어 회원 정보 응답을 사용하며, 그 응답의 이름 속성은 {@code id}다. 두 값을 합치는
	 * 책임은 {@link SocialOidcUserService}가 진다.
	 */
	private static ClientRegistration kakao(Credentials credentials) {
		return ClientRegistration.withRegistrationId(registrationId(SocialProvider.KAKAO))
			.clientId(credentials.getClientId())
			.clientSecret(credentials.getClientSecret())
			.clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri(REDIRECT_URI)
			.scope("openid", "account_email", "profile_nickname")
			.authorizationUri("https://kauth.kakao.com/oauth/authorize")
			.tokenUri("https://kauth.kakao.com/oauth/token")
			.userInfoUri("https://kapi.kakao.com/v2/user/me")
			.userNameAttributeName("id")
			.jwkSetUri("https://kauth.kakao.com/.well-known/jwks.json")
			.issuerUri("https://kauth.kakao.com")
			.clientName("Kakao")
			.build();
	}
}
