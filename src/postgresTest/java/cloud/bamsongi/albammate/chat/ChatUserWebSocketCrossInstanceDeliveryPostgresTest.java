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
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;

/**
 * CHAT-08 T4·T6: 저장 인스턴스와 다른 인스턴스에 연결된 사용자 단위 채널이 같은 커밋 신호로 최소 이벤트를 받고,
 * 그 환경에서 기존 방별 WebSocket(CHAT-03)도 회귀 없이 실제 메시지 내용을 전달하는지 검증한다.
 */
@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"app.notification.relay.enabled=false"
})
class ChatUserWebSocketCrossInstanceDeliveryPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final Instant FUTURE_STARTS_AT = Instant.parse("2099-01-01T10:00:00Z");
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";
	private static final String PASSWORD = "123456789012345";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_user_ws_delivery_test");

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
	@Autowired
	private RoomParticipationService roomParticipationService;

	@DynamicPropertySource
	static void localMultiProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("ALBAM_MATE_LOCAL_REDIS_HOST", REDIS::getHost);
		registry.add("ALBAM_MATE_LOCAL_REDIS_PORT", () -> REDIS.getMappedPort(6379));
	}

	@Test
	void T4_메시지를_저장한_인스턴스와_다른_인스턴스에_연결된_참가자도_사용자_단위_최소_신호를_수신한다() throws Exception {
		String hostEmail = "chat-user-ws-host-" + UUID.randomUUID() + "@example.com";
		String participantEmail = "chat-user-ws-participant-" + UUID.randomUUID() + "@example.com";
		long hostUserId = userAccountService.createAccount(command(hostEmail, "발신자")).id();
		long participantUserId = userAccountService.createAccount(command(participantEmail, "목록화면참가자")).id();
		Room room = createChatRoom(hostUserId);
		roomParticipationService.participate(participantUserId, room.getId());

		try (ConfigurableApplicationContext userWsInstance = secondApplicationContext()) {
			URI httpInstanceUri = URI.create("http://localhost:" + httpInstancePort);
			int userWsInstancePort = serverPort(userWsInstance);

			CookieManager hostCookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
			HttpClient hostClient = HttpClient.newBuilder().cookieHandler(hostCookieManager).build();
			login(hostClient, httpInstanceUri, hostEmail);

			CookieManager participantCookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
			HttpClient participantClient = HttpClient.newBuilder().cookieHandler(participantCookieManager).build();
			String participantSessionId = login(participantClient, httpInstanceUri, participantEmail).getValue();

			LinkedBlockingQueue<String> receivedFrames = new LinkedBlockingQueue<>();
			WebSocket userWebSocket = connectUserWs(userWsInstancePort, participantSessionId, receivedFrames);
			try {
				String clientMessageId = UUID.randomUUID().toString();
				String csrfToken = csrfToken(get(hostClient, httpInstanceUri.resolve("/api/auth/csrf")).body());
				HttpResponse<String> sendResponse = post(
					hostClient,
					httpInstanceUri.resolve("/api/rooms/" + room.getId() + "/chat/messages"),
					"{\"clientMessageId\":\"" + clientMessageId + "\",\"content\":\"목록 갱신 신호 메시지\"}",
					csrfToken);
				assertEquals(201, sendResponse.statusCode(), sendResponse.body());
				long messageId = messageId(sendResponse.body());

				String frame = receivedFrames.poll(15, TimeUnit.SECONDS);
				assertTrue(frame != null, "다른 인스턴스의 사용자 단위 WebSocket이 최소 신호를 받지 못했습니다.");
				assertEquals("{\"roomId\":" + room.getId() + ",\"messageId\":" + messageId + "}", frame);
			} finally {
				userWebSocket.abort();
			}
		}
	}

	@Test
	void T6_새_사용자_단위_채널_추가_후에도_기존_방별_WebSocket_실시간_전달은_회귀_없이_동작한다() throws Exception {
		String hostEmail = "chat-room-ws-host-" + UUID.randomUUID() + "@example.com";
		long hostUserId = userAccountService.createAccount(command(hostEmail, "회귀검증발신자")).id();
		Room room = createChatRoom(hostUserId);

		try (ConfigurableApplicationContext roomWsInstance = secondApplicationContext()) {
			URI httpInstanceUri = URI.create("http://localhost:" + httpInstancePort);
			int roomWsInstancePort = serverPort(roomWsInstance);

			CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
			HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
			String sessionId = login(client, httpInstanceUri, hostEmail).getValue();

			LinkedBlockingQueue<String> receivedFrames = new LinkedBlockingQueue<>();
			WebSocket roomWebSocket = connectRoomWs(roomWsInstancePort, room.getId(), sessionId, receivedFrames);
			try {
				String clientMessageId = UUID.randomUUID().toString();
				String csrfToken = csrfToken(get(client, httpInstanceUri.resolve("/api/auth/csrf")).body());
				HttpResponse<String> sendResponse = post(
					client,
					httpInstanceUri.resolve("/api/rooms/" + room.getId() + "/chat/messages"),
					"{\"clientMessageId\":\"" + clientMessageId + "\",\"content\":\"기존 방별 채널 회귀 검증 메시지\"}",
					csrfToken);
				assertEquals(201, sendResponse.statusCode(), sendResponse.body());
				long messageId = messageId(sendResponse.body());

				String frame = receivedFrames.poll(15, TimeUnit.SECONDS);
				assertTrue(frame != null, "기존 방별 WebSocket 인스턴스가 실시간 프레임을 받지 못했습니다.");
				assertTrue(frame.contains("\"eventId\":" + messageId), frame);
				assertTrue(frame.contains("\"type\":\"MESSAGE_CREATED\""), frame);
				assertTrue(frame.contains("기존 방별 채널 회귀 검증 메시지"), frame);
			} finally {
				roomWebSocket.abort();
			}
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

	private WebSocket connectUserWs(int port, String sessionId, LinkedBlockingQueue<String> receivedFrames)
		throws Exception {
		return HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.header("Cookie", "JSESSIONID=" + sessionId)
			.header("Origin", ALLOWED_ORIGIN)
			.buildAsync(URI.create("ws://localhost:" + port + "/api/users/me/chat/ws"), new WebSocket.Listener() {

				@Override
				public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
					receivedFrames.add(data.toString());
					webSocket.request(1);
					return null;
				}
			})
			.get(10, TimeUnit.SECONDS);
	}

	private WebSocket connectRoomWs(
		int port, long roomId, String sessionId, LinkedBlockingQueue<String> receivedFrames) throws Exception {
		return HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.header("Cookie", "JSESSIONID=" + sessionId)
			.header("Origin", ALLOWED_ORIGIN)
			.buildAsync(
				URI.create("ws://localhost:" + port + "/api/rooms/" + roomId + "/chat/ws"),
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

	private HttpCookie login(HttpClient client, URI httpInstanceUri, String email) throws Exception {
		String csrfToken = csrfToken(get(client, httpInstanceUri.resolve("/api/auth/csrf")).body());
		HttpResponse<String> loginResponse = post(
			client, httpInstanceUri.resolve("/api/auth/login"), loginBody(email), csrfToken);
		assertEquals(200, loginResponse.statusCode(), loginResponse.body());
		return cookieNamed(client, "JSESSIONID");
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

	private HttpCookie cookieNamed(HttpClient client, String name) {
		return ((CookieManager)client.cookieHandler().orElseThrow())
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
				"사용자 단위 채널 교차 인스턴스 방",
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
