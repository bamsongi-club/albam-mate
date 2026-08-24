package cloud.bamsongi.albammate.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import cloud.bamsongi.albammate.user.repository.UserRepository;

/** CHAT-08 T1·T7: 사용자 단위 handshake가 방 권한 검사 없이 세션만으로 성공하고, 세션이 없으면 401로 거절되는지 검증한다. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "app.security.cookie.secure=false")
class ChatUserWebSocketHandshakeIntegrationTest {

	private static final String ALLOWED_ORIGIN = "http://localhost:5173";
	private static final String PASSWORD = "123456789012345";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@LocalServerPort
	private int port;

	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private UserRepository userRepository;

	private final List<Long> userIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		userIds.forEach(userRepository::deleteById);
	}

	@Test
	void T1_어떤_방에도_참가하지_않은_사용자도_세션만_있으면_handshake에_성공한다() throws Exception {
		TestAccount user = signup("목록화면사용자");
		String sessionId = login(user.email());

		WebSocket webSocket = connect(sessionId, ALLOWED_ORIGIN);
		try {
			assertTrue(!webSocket.isOutputClosed());
		} finally {
			webSocket.abort();
		}
	}

	@Test
	void T7_세션이_없는_handshake_요청은_401_UNAUTHENTICATED로_거절된다() {
		ExecutionException exception = assertThrows(ExecutionException.class, () -> connect(null, ALLOWED_ORIGIN));
		WebSocketHandshakeException handshakeException = assertInstanceOf(
			WebSocketHandshakeException.class, exception.getCause());
		assertEquals(401, handshakeException.getResponse().statusCode());
	}

	private WebSocket connect(String sessionId, String origin) throws Exception {
		HttpClient client = HttpClient.newHttpClient();
		URI wsUri = URI.create("ws://localhost:" + port + "/api/users/me/chat/ws");
		WebSocket.Builder builder = client.newWebSocketBuilder().header("Origin", origin);
		if (sessionId != null) {
			builder.header("Cookie", "JSESSIONID=" + sessionId);
		}
		return builder.buildAsync(wsUri, new WebSocket.Listener() {}).get(10, TimeUnit.SECONDS);
	}

	private TestAccount signup(String nickname) {
		String email = "chat-user-ws-" + UUID.randomUUID() + "@example.com";
		long userId = userAccountService.createAccount(command(email, nickname)).id();
		userIds.add(userId);
		return new TestAccount(userId, email);
	}

	private record TestAccount(long userId, String email) {
	}

	private String login(String email) throws Exception {
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		URI baseUri = URI.create("http://localhost:" + port);
		HttpResponse<String> csrf = get(client, baseUri.resolve("/api/auth/csrf"));
		HttpResponse<String> loginResponse = post(
			client, baseUri.resolve("/api/auth/login"), loginBody(email), csrfToken(csrf.body()));
		assertEquals(200, loginResponse.statusCode());
		return cookieNamed(cookieManager, "JSESSIONID").getValue();
	}

	private String loginBody(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
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

	private HttpCookie cookieNamed(CookieManager cookieManager, String name) {
		return cookieManager
			.getCookieStore()
			.getCookies()
			.stream()
			.filter(cookie -> name.equals(cookie.getName()))
			.findFirst()
			.orElseThrow();
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return matcher.group(1);
	}

	private CreateUserAccountCommand command(String email, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(), RawPassword.from(PASSWORD).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}
}
