package cloud.bamsongi.albammate.auth.social;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;

/**
 * T3을 실제 HTTP 서버와 쿠키 저장소로 검증한다.
 *
 * <p>authorization 시작부터 callback, 새 CSRF 조회, 보호 API, 로그아웃과 로그아웃 뒤 세션 거절까지 한 흐름으로 확인하고, 기존 이메일
 * 자격증명 계약이 회귀하지 않는지 함께 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"app.social.providers.google.client-id=google-test-id",
	"app.social.providers.google.client-secret=google-test-secret"
})
@Import(StubSocialProvider.Beans.class)
class SocialLoginRealHttpIntegrationTest {

	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@LocalServerPort
	private int port;

	@Autowired
	private StubSocialProvider stubSocialProvider;

	@Test
	void 실제_HTTP_쿠키만으로_소셜_로그인부터_로그아웃_뒤_기존_세션_거절까지_수행한다() throws Exception {
		String email = "social-real-http-" + UUID.randomUUID() + "@example.com";
		stubSocialProvider.respondWith(
			Map.of(
				"sub", UUID.randomUUID().toString(), "email", email, "email_verified", true, "name", "소셜 사용자"));
		URI baseUri = URI.create("http://localhost:" + port);
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();

		HttpResponse<String> anonymousCsrf = get(client, baseUri.resolve("/api/auth/csrf"));
		assertEquals(200, anonymousCsrf.statusCode());
		String anonymousToken = csrfToken(anonymousCsrf.body());

		HttpResponse<String> authorization = get(
			client, baseUri.resolve("/api/auth/social/authorization/google"));
		assertEquals(302, authorization.statusCode());
		String authorizationLocation = authorization.headers().firstValue("Location").orElseThrow();
		assertTrue(authorizationLocation.startsWith("https://accounts.google.com/o/oauth2/v2/auth"));
		String state = queryParameter(authorizationLocation, "state");
		HttpCookie anonymousSession = cookieNamed(cookieManager, "JSESSIONID").orElseThrow();

		HttpResponse<String> callback = get(
			client,
			baseUri.resolve("/api/auth/social/callback/google?code=stub-code&state=" + state));
		assertEquals(302, callback.statusCode());
		assertLoginSuccessRedirect(callback, baseUri);
		HttpCookie authenticatedSession = cookieNamed(cookieManager, "JSESSIONID").orElseThrow();
		assertNotEquals(anonymousSession.getValue(), authenticatedSession.getValue());

		HttpResponse<String> staleCsrfLogout = post(
			client, baseUri.resolve("/api/auth/logout"), "", anonymousToken);
		assertEquals(403, staleCsrfLogout.statusCode());
		assertTrue(staleCsrfLogout.body().contains("CSRF_TOKEN_INVALID"));

		HttpResponse<String> refreshedCsrf = get(client, baseUri.resolve("/api/auth/csrf"));
		assertEquals(200, refreshedCsrf.statusCode());
		String refreshedToken = csrfToken(refreshedCsrf.body());
		assertNotEquals(anonymousToken, refreshedToken);

		HttpResponse<String> protectedProfile = get(client, baseUri.resolve("/api/users/me"));
		assertEquals(200, protectedProfile.statusCode());
		assertTrue(protectedProfile.body().contains("소셜 사용자"));

		HttpResponse<String> logout = post(client, baseUri.resolve("/api/auth/logout"), "", refreshedToken);
		assertEquals(200, logout.statusCode());

		HttpCookie staleSession = new HttpCookie("JSESSIONID", authenticatedSession.getValue());
		staleSession.setPath("/");
		cookieManager.getCookieStore().add(baseUri, staleSession);
		assertEquals(401, get(client, baseUri.resolve("/api/users/me")).statusCode());
	}

	@Test
	void 소셜_전용_사용자의_이메일로는_비밀번호_로그인을_할_수_없다() throws Exception {
		String email = "social-only-" + UUID.randomUUID() + "@example.com";
		stubSocialProvider.respondWith(
			Map.of(
				"sub", UUID.randomUUID().toString(), "email", email, "email_verified", true, "name", "소셜 전용"));
		URI baseUri = URI.create("http://localhost:" + port);
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		signInWithGoogle(client, baseUri);

		CookieManager emailLoginCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient emailLoginClient = HttpClient.newBuilder().cookieHandler(emailLoginCookies).build();
		String token = csrfToken(get(emailLoginClient, baseUri.resolve("/api/auth/csrf")).body());

		HttpResponse<String> login = post(
			emailLoginClient,
			baseUri.resolve("/api/auth/login"),
			"{\"email\":\"" + email + "\",\"password\":\"123456789012345\"}",
			token);

		assertEquals(401, login.statusCode());
		assertTrue(login.body().contains("INVALID_CREDENTIALS"));
		assertFalse(cookieNamed(emailLoginCookies, "JSESSIONID").isPresent());
	}

	private void signInWithGoogle(HttpClient client, URI baseUri) throws Exception {
		get(client, baseUri.resolve("/api/auth/csrf"));
		HttpResponse<String> authorization = get(
			client, baseUri.resolve("/api/auth/social/authorization/google"));
		String state = queryParameter(authorization.headers().firstValue("Location").orElseThrow(), "state");
		HttpResponse<String> callback = get(
			client,
			baseUri.resolve("/api/auth/social/callback/google?code=stub-code&state=" + state));
		assertLoginSuccessRedirect(callback, baseUri);
	}

	/** 서블릿 컨테이너가 같은 host의 절대 URL로 바꿔 보낼 수 있어 두 표현을 모두 허용한다. */
	private void assertLoginSuccessRedirect(HttpResponse<String> response, URI baseUri) {
		String expected = "/?socialAuth=login-success#/home";
		String location = response.headers().firstValue("Location").orElseThrow();
		assertTrue(location.equals(expected) || location.equals(baseUri + expected), location);
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(HttpClient client, URI uri, String body, String csrfToken)
		throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/json")
				.header("X-XSRF-TOKEN", csrfToken)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find());
		return matcher.group(1);
	}

	private String queryParameter(String location, String name) {
		String query = URI.create(location).getRawQuery();
		for (String parameter : query.split("&")) {
			int separator = parameter.indexOf('=');
			if (separator > 0 && name.equals(parameter.substring(0, separator))) {
				return URLDecoder.decode(parameter.substring(separator + 1), StandardCharsets.UTF_8);
			}
		}
		throw new IllegalStateException("query parameter not found: " + name);
	}

	private Optional<HttpCookie> cookieNamed(CookieManager cookieManager, String name) {
		return cookieManager.getCookieStore()
			.getCookies()
			.stream()
			.filter(cookie -> name.equals(cookie.getName()))
			.findFirst();
	}
}
