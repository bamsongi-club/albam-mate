package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "issue360.localMultiProxy", matches = "true")
class LocalMultiProxyRuntimePostgresTest {

	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@Test
	void 프록시_경유_동일_JSESSIONID가_두_애플리케이션_인스턴스에서_유지된다() throws Exception {
		URI proxyUri = URI.create("http://127.0.0.1:5174");
		String password = "123456789012345";
		String email = "proxy-runtime-" + UUID.randomUUID() + "@example.com";
		HttpClient client = HttpClient.newBuilder()
			.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
			.build();

		HttpResponse<String> csrf = get(client, proxyUri.resolve("/api/auth/csrf"));
		assertEquals(200, csrf.statusCode());
		String csrfToken = csrfToken(csrf.body());

		HttpResponse<String> signup = post(
			client,
			proxyUri.resolve("/api/auth/signup"),
			"{\"email\":\"" + email + "\",\"password\":\"" + password + "\","
				+ "\"nickname\":\"프록시 세션 사용자\"}",
			csrfToken);
		assertEquals(201, signup.statusCode());

		csrf = get(client, proxyUri.resolve("/api/auth/csrf"));
		csrfToken = csrfToken(csrf.body());
		HttpResponse<String> login = post(
			client,
			proxyUri.resolve("/api/auth/login"),
			"{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}",
			csrfToken);
		assertEquals(200, login.statusCode());

		HttpCookie sessionCookie = cookieNamed(client, "JSESSIONID");
		for (int requestNumber = 0; requestNumber < 8; requestNumber++) {
			HttpResponse<String> profile = getWithSession(
				proxyUri.resolve("/api/users/me"), sessionCookie);
			assertEquals(200, profile.statusCode(), "proxy request " + requestNumber + " lost the shared session");
		}
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri).GET().build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> getWithSession(URI uri, HttpCookie sessionCookie) throws Exception {
		return HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(uri)
				.header("Cookie", "JSESSIONID=" + sessionCookie.getValue())
				.GET()
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(HttpClient client, URI uri, String body, String csrfToken) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri)
				.header("Content-Type", "application/json")
				.header("X-XSRF-TOKEN", csrfToken)
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build(),
			HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpCookie cookieNamed(HttpClient client, String name) {
		CookieManager cookieManager = (CookieManager)client.cookieHandler().orElseThrow();
		return cookieManager.getCookieStore().getCookies().stream()
			.filter(cookie -> cookie.getName().equals(name))
			.findFirst()
			.orElseThrow();
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return matcher.group(1);
	}
}
