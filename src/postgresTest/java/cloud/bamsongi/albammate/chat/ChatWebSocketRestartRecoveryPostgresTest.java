package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
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

/**
 * T11: 서버 재시작 뒤에도 기존 메시지 이력과 순서가 유지되고, 재시작 뒤 새 인스턴스로 재연결한 클라이언트가
 * {@code afterMessageId}로 재시작 전 누락분을 복구하는지 검증한다.
 */
@Testcontainers
@ActiveProfiles("local-multi")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"app.notification.relay.enabled=false"
})
class ChatWebSocketRestartRecoveryPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final Instant FUTURE_STARTS_AT = Instant.parse("2099-01-01T10:00:00Z");
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";
	private static final String PASSWORD = "123456789012345";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_ws_restart_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@LocalServerPort
	private int beforeRestartPort;

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
		registry.add("ALBAM_MATE_LOCAL_MULTI_REDIS_HOST", REDIS::getHost);
		registry.add("ALBAM_MATE_LOCAL_MULTI_REDIS_PORT", () -> REDIS.getMappedPort(6379));
	}

	@Test
	void 재시작_전에_커밋된_메시지_이력과_순서는_재시작_뒤_새_인스턴스의_재연결_catchup으로_복구된다() throws Exception {
		String email = "chat-ws-restart-" + UUID.randomUUID() + "@example.com";
		long hostUserId = userAccountService.createAccount(command(email, "재시작 검증 주최자")).id();
		Room room = createChatRoom(hostUserId);

		URI beforeRestartUri = URI.create("http://localhost:" + beforeRestartPort);
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		String csrfToken = csrfToken(get(client, beforeRestartUri.resolve("/api/auth/csrf")).body());
		assertEquals(
			200,
			post(client, beforeRestartUri.resolve("/api/auth/login"), loginBody(email), csrfToken).statusCode());

		String sessionId = cookieNamed(cookieManager, "JSESSIONID").getValue();
		LinkedBlockingQueue<String> liveFrames = new LinkedBlockingQueue<>();
		WebSocket liveConnection = connectWithoutAfterMessageId(beforeRestartPort, room.getId(), sessionId, liveFrames);
		long firstMessageId;
		try {
			firstMessageId = sendMessage(client, beforeRestartUri, room.getId(), "재시작 전 메시지 1");
			String liveFrame = liveFrames.poll(15, TimeUnit.SECONDS);
			assertTrue(liveFrame != null, "재시작 전 실시간 프레임을 받지 못했습니다.");
			assertTrue(liveFrame.contains("\"eventId\":" + firstMessageId), liveFrame);
		} finally {
			// 클라이언트가 firstMessageId까지 받은 뒤 연결이 끊긴 상태(재시작으로 인한 단절)를 재현한다.
			liveConnection.abort();
		}

		// 연결이 끊긴 동안(재시작 도중) 커밋된 메시지는 아무 연결에도 전달되지 않는다.
		long secondMessageId = sendMessage(client, beforeRestartUri, room.getId(), "재시작 도중 커밋된 메시지");
		assertTrue(secondMessageId > firstMessageId);

		// 재시작을 시뮬레이션한다: 이전 애플리케이션 프로세스의 메모리 상태 없이 같은 PostgreSQL로 새 인스턴스를 띄운다.
		try (ConfigurableApplicationContext restartedInstance = restartedApplicationContext()) {
			int restartedPort = serverPort(restartedInstance);
			LinkedBlockingQueue<String> recoveredFrames = new LinkedBlockingQueue<>();
			WebSocket webSocket = connect(restartedPort, room.getId(), firstMessageId, sessionId, recoveredFrames);
			try {
				String recoveredFrame = recoveredFrames.poll(15, TimeUnit.SECONDS);
				assertTrue(recoveredFrame != null, "재시작 뒤 catch-up 프레임을 받지 못했습니다.");
				assertTrue(recoveredFrame.contains("\"eventId\":" + secondMessageId), recoveredFrame);
			} finally {
				webSocket.abort();
			}
		}
	}

	private long sendMessage(HttpClient client, URI baseUri, long roomId, String content) throws Exception {
		String csrfToken = csrfToken(get(client, baseUri.resolve("/api/auth/csrf")).body());
		HttpResponse<String> response = post(
			client,
			baseUri.resolve("/api/rooms/" + roomId + "/chat/messages"),
			"{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"content\":\"" + content + "\"}",
			csrfToken);
		assertEquals(201, response.statusCode(), response.body());
		return messageId(response.body());
	}

	private ConfigurableApplicationContext restartedApplicationContext() {
		return new SpringApplicationBuilder(AlbamMateApplication.class).run(
			"--spring.profiles.active=local-multi",
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

	private WebSocket connect(
		int port, long roomId, long afterMessageId, String sessionId, LinkedBlockingQueue<String> receivedFrames)
		throws Exception {
		return connectTo(
			port, "/api/rooms/" + roomId + "/chat/ws?afterMessageId=" + afterMessageId, sessionId, receivedFrames);
	}

	private WebSocket connectWithoutAfterMessageId(
		int port, long roomId, String sessionId, LinkedBlockingQueue<String> receivedFrames) throws Exception {
		return connectTo(port, "/api/rooms/" + roomId + "/chat/ws", sessionId, receivedFrames);
	}

	private WebSocket connectTo(
		int port, String path, String sessionId, LinkedBlockingQueue<String> receivedFrames) throws Exception {
		return HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.header("Cookie", "JSESSIONID=" + sessionId)
			.header("Origin", ALLOWED_ORIGIN)
			.buildAsync(
				URI.create("ws://localhost:" + port + path),
				new WebSocket.Listener() {

					@Override
					public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
						receivedFrames.add(data.toString());
						webSocket.request(1);
						return null;
					}
				})
			.get(10, TimeUnit.SECONDS);
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

	private String loginBody(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
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

	private long messageId(String body) {
		Matcher matcher = Pattern.compile("\\\"messageId\\\":(\\d+)").matcher(body);
		assertTrue(matcher.find(), body);
		return Long.parseLong(matcher.group(1));
	}

	private CreateUserAccountCommand command(String email, String nickname) {
		return new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(), RawPassword.from(PASSWORD).orElseThrow(),
			UserNickname.from(nickname).orElseThrow());
	}

	private Room createChatRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"재시작 복구 검증 방",
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
