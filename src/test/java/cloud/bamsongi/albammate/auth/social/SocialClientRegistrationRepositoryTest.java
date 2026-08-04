package cloud.bamsongi.albammate.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.oidc.IdTokenClaimNames;

import cloud.bamsongi.albammate.auth.social.SocialOAuthProperties.Credentials;
import cloud.bamsongi.albammate.user.contract.SocialProvider;

/** AUTH-05b-AC1의 활성화 조건과 노출 순서를 검증한다. */
class SocialClientRegistrationRepositoryTest {

	@Test
	void 자격증명이_없으면_등록_없이_만들어진다() {
		SocialClientRegistrationRepository repository = repository(new SocialOAuthProperties());

		assertEquals(List.of(), repository.configuredProviders());
		assertFalse(repository.iterator().hasNext());
		assertNull(repository.findByRegistrationId("google"));
	}

	@Test
	void Client_ID와_Secret이_모두_있는_제공자만_등록한다() {
		SocialOAuthProperties properties = new SocialOAuthProperties();
		configure(properties, SocialProvider.GOOGLE, "google-id", "google-secret");
		configure(properties, SocialProvider.NAVER, "naver-id", "");
		configure(properties, SocialProvider.KAKAO, "", "kakao-secret");

		SocialClientRegistrationRepository repository = repository(properties);

		assertEquals(List.of(SocialProvider.GOOGLE), repository.configuredProviders());
		assertNull(repository.findByRegistrationId("naver"));
		assertNull(repository.findByRegistrationId("kakao"));
		assertEquals(Optional.empty(), repository.configuredProvider("naver"));
		assertEquals(Optional.of(SocialProvider.GOOGLE), repository.configuredProvider("google"));
	}

	@Test
	void 설정된_제공자를_GOOGLE_NAVER_KAKAO_순서로_노출한다() {
		SocialClientRegistrationRepository repository = repository(allConfigured());

		assertEquals(
			List.of(SocialProvider.GOOGLE, SocialProvider.NAVER, SocialProvider.KAKAO),
			repository.configuredProviders());
		assertEquals(
			List.of("google", "naver", "kakao"),
			List.of(
				repository.findByRegistrationId("google").getRegistrationId(),
				repository.findByRegistrationId("naver").getRegistrationId(),
				repository.findByRegistrationId("kakao").getRegistrationId()));
	}

	@Test
	void 지원하지_않는_경로값은_설정된_제공자로_읽지_않는다() {
		SocialClientRegistrationRepository repository = repository(allConfigured());

		assertEquals(Optional.empty(), repository.configuredProvider("apple"));
		assertEquals(Optional.empty(), repository.configuredProvider("GOOGLE"));
	}

	@Test
	void 세_제공자의_callback_URI는_접속한_same_site_주소에서_계산한다() {
		SocialClientRegistrationRepository repository = repository(allConfigured());

		for (String registrationId : List.of("google", "naver", "kakao")) {
			assertEquals(
				"{baseUrl}/api/auth/social/callback/{registrationId}",
				repository.findByRegistrationId(registrationId).getRedirectUri());
		}
	}

	@Test
	void Google과_Kakao는_OpenID_Connect로_Naver는_회원_프로필로_식별한다() {
		SocialClientRegistrationRepository repository = repository(allConfigured());

		ClientRegistration google = repository.findByRegistrationId("google");
		assertTrue(google.getScopes().contains("openid"));
		assertEquals(IdTokenClaimNames.SUB,
			google.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());

		ClientRegistration kakao = repository.findByRegistrationId("kakao");
		assertTrue(kakao.getScopes().contains("openid"));
		assertEquals(
			"https://kapi.kakao.com/v2/user/me",
			kakao.getProviderDetails().getUserInfoEndpoint().getUri());

		ClientRegistration naver = repository.findByRegistrationId("naver");
		assertFalse(naver.getScopes().contains("openid"));
		assertEquals(
			"https://openapi.naver.com/v1/nid/me",
			naver.getProviderDetails().getUserInfoEndpoint().getUri());
		assertEquals("response", naver.getProviderDetails().getUserInfoEndpoint().getUserNameAttributeName());
	}

	private SocialClientRegistrationRepository repository(SocialOAuthProperties properties) {
		return new SocialClientRegistrationRepository(properties);
	}

	private SocialOAuthProperties allConfigured() {
		SocialOAuthProperties properties = new SocialOAuthProperties();
		for (SocialProvider provider : SocialProvider.values()) {
			configure(properties, provider, provider.name() + "-id", provider.name() + "-secret");
		}
		return properties;
	}

	private void configure(
		SocialOAuthProperties properties, SocialProvider provider, String clientId, String clientSecret) {
		Credentials credentials = new Credentials();
		credentials.setClientId(clientId);
		credentials.setClientSecret(clientSecret);
		properties.getProviders().put(provider, credentials);
	}
}
