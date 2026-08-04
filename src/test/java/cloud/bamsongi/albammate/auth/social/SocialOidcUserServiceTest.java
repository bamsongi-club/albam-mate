package cloud.bamsongi.albammate.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.sun.net.httpserver.HttpServer;

import cloud.bamsongi.albammate.user.contract.SocialIdentity;
import cloud.bamsongi.albammate.user.contract.SocialProvider;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;

/**
 * Kakao 회원 정보 응답과 ID token의 {@code sub}를 합치는 경로를 고정 응답으로 검증한다.
 *
 * <p>제공자 통신은 로컬 HTTP 대역으로 대신하므로 실제 Client Secret이 필요하지 않다.
 */
class SocialOidcUserServiceTest {

	private final SocialOidcUserService userService = new SocialOidcUserService();

	private HttpServer providerServer;
	private String userInfoBody;

	@BeforeEach
	void startProvider() throws IOException {
		providerServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		providerServer.createContext("/user-info", exchange -> {
			byte[] body = userInfoBody.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			try (OutputStream output = exchange.getResponseBody()) {
				output.write(body);
			}
		});
		providerServer.start();
	}

	@AfterEach
	void stopProvider() {
		providerServer.stop(0);
	}

	@Test
	void Kakao는_회원_정보_응답에_ID_token의_sub를_더해_하나의_사용자로_만든다() {
		userInfoBody = """
			{
			  "id": 1234567890,
			  "kakao_account": {
			    "email": "player@example.com",
			    "is_email_valid": true,
			    "is_email_verified": true,
			    "profile": {"nickname": "밤톨"}
			  }
			}
			""";

		OidcUser user = userService.loadUser(userRequest("kakao", "kakao-subject"));

		assertEquals("kakao-subject", user.getName());
		assertEquals("kakao-subject", user.getAttributes().get("sub"));

		SocialIdentity identity = new SocialIdentityMapper().map(SocialProvider.KAKAO, user.getAttributes());
		assertEquals("kakao-subject", identity.providerSubject());
		assertEquals(UserEmail.from("player@example.com"), identity.email());
		assertEquals(UserNickname.from("밤톨"), identity.nickname());
	}

	@Test
	void 다른_OpenID_Connect_제공자는_표준_사용자_조회를_사용한다() {
		userInfoBody = """
			{"sub": "google-subject", "email": "player@example.com", "email_verified": true, "name": "밤톨"}
			""";

		OidcUser user = userService.loadUser(userRequest("google", "google-subject"));

		assertEquals("google-subject", user.getName());
		assertEquals("player@example.com", user.getAttributes().get("email"));
	}

	private OidcUserRequest userRequest(String registrationId, String subject) {
		Instant issuedAt = Instant.now().truncatedTo(ChronoUnit.SECONDS);
		Instant expiresAt = issuedAt.plus(5, ChronoUnit.MINUTES);
		return new OidcUserRequest(
			clientRegistration(registrationId),
			new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER, "stub-access-token", issuedAt, expiresAt),
			new OidcIdToken("stub-id-token", issuedAt, expiresAt, Map.of("sub", subject)));
	}

	private ClientRegistration clientRegistration(String registrationId) {
		return ClientRegistration.withRegistrationId(registrationId)
			.clientId(registrationId + "-id")
			.clientSecret(registrationId + "-secret")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("{baseUrl}/api/auth/social/callback/{registrationId}")
			.scope("openid", "profile", "email")
			.authorizationUri("https://stub.example.com/authorize")
			.tokenUri("https://stub.example.com/token")
			.userInfoUri("http://127.0.0.1:" + providerServer.getAddress().getPort() + "/user-info")
			.userNameAttributeName("kakao".equals(registrationId) ? "id" : "sub")
			.build();
	}
}
