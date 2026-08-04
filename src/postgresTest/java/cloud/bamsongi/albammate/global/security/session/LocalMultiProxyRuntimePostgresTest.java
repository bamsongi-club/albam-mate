package cloud.bamsongi.albammate.global.security.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "issue360.localMultiProxy", matches = "true")
class LocalMultiProxyRuntimePostgresTest {

	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
	private static final List<String> LOCAL_MULTI_SERVICES = List.of("postgres", "redis", "spring-1", "spring-2",
		"proxy");
	private static final Set<String> PUBLIC_SERVICES = Set.of("postgres", "redis", "proxy");

	@Test
	void local_multi_서비스가_healthy이고_공개_포트가_loopback에만_바인딩되며_프록시_세션이_공유된다() throws Exception {
		assertLocalMultiServicesHealthyAndLoopbackBound();

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
		Set<String> upstreams = new HashSet<>();
		for (int requestNumber = 0; requestNumber < 8; requestNumber++) {
			HttpResponse<String> profile = getWithSession(
				proxyUri.resolve("/api/users/me"), sessionCookie);
			assertEquals(200, profile.statusCode(), "proxy request " + requestNumber + " lost the shared session");
			upstreams.add(profile.headers().firstValue("X-Albam-Mate-Upstream").orElseThrow());
		}
		assertEquals(2, upstreams.size(), "proxy did not route requests to both Spring instances: " + upstreams);
	}

	private void assertLocalMultiServicesHealthyAndLoopbackBound() throws Exception {
		for (String service : LOCAL_MULTI_SERVICES) {
			String containerId = dockerCompose("ps", "-q", service).trim();
			assertFalse(containerId.isBlank(), service + " container is not running");
			assertEquals("running", dockerInspect(containerId, "{{.State.Status}}"), service + " state");
			assertEquals("healthy", dockerInspect(containerId, "{{.State.Health.Status}}"), service + " health");

			String publishedHostIps = dockerInspect(
				containerId,
				"{{range .NetworkSettings.Ports}}{{range .}}{{println .HostIp}}{{end}}{{end}}");
			if (PUBLIC_SERVICES.contains(service)) {
				assertFalse(publishedHostIps.isBlank(), service + " has no published host binding");
			}
			publishedHostIps.lines()
				.filter(hostIp -> !hostIp.isBlank())
				.forEach(hostIp -> assertEquals("127.0.0.1", hostIp, service + " published HostIp"));
		}
	}

	private String dockerCompose(String... arguments) throws Exception {
		String[] command = new String[arguments.length + 6];
		command[0] = "docker";
		command[1] = "compose";
		command[2] = "--env-file";
		command[3] = ".env.example";
		command[4] = "-f";
		command[5] = "compose.local-multi.yml";
		System.arraycopy(arguments, 0, command, 6, arguments.length);
		return runCommand(command);
	}

	private String dockerInspect(String containerId, String format) throws Exception {
		return runCommand("docker", "inspect", "--format", format, containerId);
	}

	private String runCommand(String... command) throws IOException, InterruptedException {
		Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertTrue(process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS),
			String.join(" ", command) + " timed out");
		assertEquals(0, process.exitValue(), String.join(" ", command) + " failed: " + output);
		return output.trim();
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
