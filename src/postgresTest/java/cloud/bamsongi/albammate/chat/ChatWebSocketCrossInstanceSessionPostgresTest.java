package cloud.bamsongi.albammate.chat;

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
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;

/** T4: HTTP 저장과 WebSocket 연결이 서로 다른 인스턴스에 도달해도 공용 세션이 같은 경계로 판정하는지 검증한다. */
@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"app.notification.relay.enabled=false"
})
class ChatWebSocketCrossInstanceSessionPostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final Instant FUTURE_STARTS_AT = Instant.parse("2099-01-01T10:00:00Z");
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";
	private static final String PASSWORD = "123456789012345";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_ws_session_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@LocalServerPort
	private int httpInstancePort;

	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;

	@DynamicPropertySource
	static void localMultiProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("ALBAM_MATE_LOCAL_REDIS_HOST", REDIS::getHost);
		registry.add("ALBAM_MATE_LOCAL_REDIS_PORT", () -> REDIS.getMappedPort(6379));
	}

	@Test
	void 메시지를_저장한_인스턴스와_다른_인스턴스의_WebSocket_handshake도_같은_공용_세션으로_판정된다() throws Exception {
		String email = "chat-ws-cross-instance-" + UUID.randomUUID() + "@example.com";
		long hostUserId = userAccountService.createAccount(command(email, "교차 인스턴스 주최자")).id();
		Room room = createChatRoom(hostUserId);

		try (ConfigurableApplicationContext webSocketInstance = secondApplicationContext()) {
			URI httpInstanceUri = URI.create("http://localhost:" + httpInstancePort);
			int webSocketInstancePort = serverPort(webSocketInstance);

			CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
			HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
			String csrfToken = csrfToken(get(client, httpInstanceUri.resolve("/api/auth/csrf")).body());
			assertEquals(
				200,
				post(client, httpInstanceUri.resolve("/api/auth/login"), loginBody(email), csrfToken).statusCode());
			String sessionId = cookieNamed(cookieManager, "JSESSIONID").getValue();

			csrfToken = csrfToken(get(client, httpInstanceUri.resolve("/api/auth/csrf")).body());
			HttpResponse<String> sendResponse = post(
				client,
				httpInstanceUri.resolve("/api/rooms/" + room.getId() + "/chat/messages"),
				"{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"content\":\"교차 인스턴스 메시지\"}",
				csrfToken);
			assertEquals(201, sendResponse.statusCode(), sendResponse.body());

			WebSocket webSocket = connect(webSocketInstancePort, room.getId(), sessionId);
			try {
				assertTrue(!webSocket.isOutputClosed());
			} finally {
				webSocket.abort();
			}

			csrfToken = csrfToken(get(client, httpInstanceUri.resolve("/api/auth/csrf")).body());
			assertEquals(
				200,
				post(client, httpInstanceUri.resolve("/api/auth/logout"), "", csrfToken).statusCode());

			ExecutionException rejected = assertThrows(
				ExecutionException.class, () -> connect(webSocketInstancePort, room.getId(), sessionId));
			assertEquals(
				401,
				assertInstanceOf(WebSocketHandshakeException.class, rejected.getCause()).getResponse().statusCode());
		}
	}

	private ConfigurableApplicationContext secondApplicationContext() {
		return new SpringApplicationBuilder(AlbamMateApplication.class).run(
			"--spring.profiles.active=local",
			"--server.port=0",
			"--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
			"--spring.datasource.username=" + POSTGRES.getUsername(),
			"--spring.datasource.password=" + POSTGRES.getPassword(),
			"--spring.flyway.enabled=false",
			"--app.redis.host=" + REDIS.getHost(),
			"--app.redis.port=" + REDIS.getMappedPort(6379),
			"--app.security.cookie.secure=false",
			"--app.notification.relay.enabled=false");
	}

	private int serverPort(ConfigurableApplicationContext context) {
		return ((ServletWebServerApplicationContext)context).getWebServer().getPort();
	}

	private WebSocket connect(int port, long roomId, String sessionId) throws Exception {
		return HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.header("Cookie", "JSESSIONID=" + sessionId)
			.header("Origin", ALLOWED_ORIGIN)
			.buildAsync(
				URI.create("ws://localhost:" + port + "/api/rooms/" + roomId + "/chat/ws"),
				new WebSocket.Listener() {})
			.get(10, TimeUnit.SECONDS);
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri).GET().build(),
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

	private String loginBody(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
	}

	private HttpCookie cookieNamed(CookieManager cookieManager, String name) {
		return cookieManager.getCookieStore().getCookies().stream()
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
			UserEmail.from(email).orElseThrow(),
			RawPassword.from(PASSWORD).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}

	private Room createChatRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"교차 인스턴스 handshake 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				FUTURE_STARTS_AT,
				"홍대",
				2));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}
}
