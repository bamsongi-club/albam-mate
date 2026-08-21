package cloud.bamsongi.albammate.chat.match;

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
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
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
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;

/**
 * T2(CHAT-T6): 메시지를 저장한 인스턴스와 다른 인스턴스의 WebSocket 연결이 실제 Redis pub/sub으로 커밋된 메시지를
 * 실시간으로 수신하는지 검증한다.
 */
@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"app.notification.relay.enabled=false"
})
class MatchChatWebSocketCrossInstanceDeliveryPostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String ALLOWED_ORIGIN = "http://localhost:5173";
	private static final String PASSWORD = "123456789012345";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_chat_ws_delivery_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@LocalServerPort
	private int httpInstancePort;

	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void localMultiProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("ALBAM_MATE_LOCAL_REDIS_HOST", REDIS::getHost);
		registry.add("ALBAM_MATE_LOCAL_REDIS_PORT", () -> REDIS.getMappedPort(6379));
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_chat_messages, match_chat_rooms, match_party_participants, match_parties, "
				+ "users restart identity cascade");
	}

	@Test
	void 메시지를_저장한_인스턴스와_다른_인스턴스의_WebSocket_연결이_커밋된_메시지를_실시간으로_수신한다() throws Exception {
		String email = "match-chat-ws-cross-delivery-" + UUID.randomUUID() + "@example.com";
		long hostUserId = userAccountService.createAccount(command(email, "교차 인스턴스 수신자")).id();
		long partyId = insertActivePartyWithParticipants(hostUserId);

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

			LinkedBlockingQueue<String> receivedFrames = new LinkedBlockingQueue<>();
			WebSocket webSocket = connect(webSocketInstancePort, partyId, sessionId, receivedFrames);
			try {
				String clientMessageId = UUID.randomUUID().toString();
				csrfToken = csrfToken(get(client, httpInstanceUri.resolve("/api/auth/csrf")).body());
				HttpResponse<String> sendResponse = post(
					client,
					httpInstanceUri.resolve("/api/matches/parties/" + partyId + "/chat/messages"),
					"{\"clientMessageId\":\"" + clientMessageId + "\",\"content\":\"교차 인스턴스 실시간 메시지\"}",
					csrfToken);
				assertEquals(201, sendResponse.statusCode(), sendResponse.body());
				long messageId = messageId(sendResponse.body());

				String frame = receivedFrames.poll(15, TimeUnit.SECONDS);
				assertTrue(frame != null, "WebSocket 인스턴스가 실시간 프레임을 받지 못했습니다.");
				assertTrue(frame.contains("\"eventId\":" + messageId), frame);
				assertTrue(frame.contains("\"type\":\"MESSAGE_CREATED\""), frame);
				assertTrue(frame.contains("교차 인스턴스 실시간 메시지"), frame);
			} finally {
				webSocket.abort();
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

	private WebSocket connect(int port, long partyId, String sessionId, LinkedBlockingQueue<String> receivedFrames)
		throws Exception {
		return HttpClient.newHttpClient()
			.newWebSocketBuilder()
			.header("Cookie", "JSESSIONID=" + sessionId)
			.header("Origin", ALLOWED_ORIGIN)
			.buildAsync(
				URI.create("ws://localhost:" + port + "/api/matches/parties/" + partyId + "/chat/ws"),
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

	private long insertActivePartyWithParticipants(long... userIds) {
		long partyId = jdbcTemplate.queryForObject(
			"insert into match_parties "
				+ "(status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) "
				+ "values ('ACTIVE', current_timestamp, current_timestamp, "
				+ "current_timestamp + interval '1 day', current_timestamp, current_timestamp) returning id",
			Long.class);
		jdbcTemplate.update(
			"insert into match_chat_rooms (party_id, created_at, updated_at) "
				+ "values (?, current_timestamp, current_timestamp)",
			partyId);
		for (long userId : userIds) {
			jdbcTemplate.update(
				"insert into match_party_participants (party_id, user_id, participant_ref, created_at) "
					+ "values (?, ?, ?, current_timestamp)",
				partyId, userId, UUID.randomUUID());
		}
		return partyId;
	}
}
