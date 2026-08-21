package cloud.bamsongi.albammate.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import io.lettuce.core.ClientOptions;

@Testcontainers
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.security.cookie.secure=false",
	"app.notification.relay.enabled=false"
})
class ChatMessageRateLimitRedisRecoveryPostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String PASSWORD = "123456789012345";
	private static final int REDIS_STARTUP_MAX_ATTEMPTS = 50;
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_rate_limit_redis_recovery_test");

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
		.withExposedPorts(6379)
		.withCommand("sh", "-c", "redis-server --save '' --daemonize yes && tail -f /dev/null")
		.waitingFor(Wait.forListeningPort());

	@LocalServerPort
	private int port;
	@Autowired
	private ChatMessageCommandService chatMessageCommandService;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private LettuceConnectionFactory redisConnectionFactory;
	@Autowired
	private StringRedisTemplate redisTemplate;

	@DynamicPropertySource
	static void localProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("ALBAM_MATE_LOCAL_REDIS_HOST", REDIS::getHost);
		registry.add("ALBAM_MATE_LOCAL_REDIS_PORT", () -> REDIS.getMappedPort(6379));
	}

	@Test
	void Redis_단절_요청은_503으로_끝나고_복구_뒤_별도_HTTP_요청은_새_연결로_성공한다() throws Exception {
		String email = "chat-rate-limit-recovery-" + UUID.randomUUID() + "@example.com";
		long userId = userAccountService.createAccount(command(email, "Redis 복구 사용자")).id();
		Room room = createChatRoom(userId);
		assertEquals("PONG", redisTemplate.getConnectionFactory().getConnection().ping());
		CookieManager unavailableCookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient unavailableClient = HttpClient.newBuilder().cookieHandler(unavailableCookieManager).build();
		URI baseUri = URI.create("http://localhost:" + port);
		String unavailableCsrfToken = csrfToken(get(unavailableClient, baseUri.resolve("/api/auth/csrf")).body());

		stopRedisProcess();
		HttpResponse<String> unavailableLogin = post(
			unavailableClient,
			baseUri.resolve("/api/auth/login"),
			loginBody(email),
			unavailableCsrfToken);
		assertEquals(503, unavailableLogin.statusCode(), unavailableLogin.body());
		assertTrue(unavailableLogin.headers().firstValue("Retry-After").isEmpty());
		Instant startedAt = Instant.now();
		BusinessException exception = assertThrows(BusinessException.class,
			() -> chatMessageCommandService.send(
				userId,
				room.getId(),
				new ChatMessageSendRequest(UUID.randomUUID().toString(), "Redis 단절 중 메시지")));
		assertEquals(503, exception.getErrorCode().getStatus());
		assertTrue(Duration.between(startedAt, Instant.now()).compareTo(Duration.ofSeconds(5)) < 0,
			"Redis 재기동을 기다리는 서버 재시도를 해서는 안 됩니다.");
		assertEquals(0, chatMessageRepository.count());

		startRedisProcess();
		awaitPrimaryRedisReady();

		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		HttpResponse<String> csrf = get(client, baseUri.resolve("/api/auth/csrf"));
		assertEquals(200, csrf.statusCode(), csrf.body());
		HttpResponse<String> login = post(client, baseUri.resolve("/api/auth/login"), loginBody(email),
			csrfToken(csrf.body()));
		assertEquals(200, login.statusCode(), login.body());
		assertNotNull(cookieNamed(cookieManager, "JSESSIONID"));

		HttpResponse<String> recovered = post(
			client,
			baseUri.resolve("/api/rooms/" + room.getId() + "/chat/messages"),
			"{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"content\":\"Redis 복구 뒤 새 요청\"}",
			csrfToken(get(client, baseUri.resolve("/api/auth/csrf")).body()));
		assertEquals(201, recovered.statusCode(), recovered.body());
		assertEquals(1, chatMessageRepository.count());

		assertTrue(redisConnectionFactory.getShareNativeConnection());
		assertEquals(Duration.ofSeconds(2), redisConnectionFactory.getClientConfiguration().getCommandTimeout());
		ClientOptions clientOptions = redisConnectionFactory.getClientConfiguration().getClientOptions().orElseThrow();
		assertTrue(clientOptions.isAutoReconnect());
		assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS, clientOptions.getDisconnectedBehavior());
	}

	private void stopRedisProcess() throws Exception {
		assertEquals(0, REDIS.execInContainer("redis-cli", "shutdown", "nosave").getExitCode());
		assertEquals(0, REDIS.execInContainer(
			"sh", "-c", "until ! redis-cli ping >/dev/null 2>&1; do sleep 0.1; done").getExitCode());
	}

	private void startRedisProcess() throws Exception {
		assertEquals(0, REDIS.execInContainer("redis-server", "--save", "", "--daemonize", "yes").getExitCode());
		String waitForPongCommand = "attempt=0; while [ \"$attempt\" -lt " + REDIS_STARTUP_MAX_ATTEMPTS
			+ " ]; do if redis-cli ping | grep -qx PONG; then exit 0; fi; attempt=$((attempt + 1)); sleep 0.1; done; "
			+ "echo 'Redis did not return PONG after " + REDIS_STARTUP_MAX_ATTEMPTS + " attempts.' >&2; exit 1";
		org.testcontainers.containers.Container.ExecResult result = REDIS.execInContainer("sh", "-c",
			waitForPongCommand);
		assertEquals(0, result.getExitCode(), "Redis 시작 대기 실패: " + result.getStderr());
	}

	private void awaitPrimaryRedisReady() throws InterruptedException {
		Instant deadline = Instant.now().plusSeconds(5);
		while (Instant.now().isBefore(deadline)) {
			try {
				if ("PONG".equals(redisTemplate.getConnectionFactory().getConnection().ping())) {
					return;
				}
			} catch (RuntimeException ignored) {
				// 자동 재연결이 새 native connection을 준비하는 동안만 poll한다.
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Redis 복구 뒤 Primary Redis connection이 5초 안에 준비되지 않았습니다");
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
		return cookieManager.getCookieStore().getCookies().stream()
			.filter(cookie -> name.equals(cookie.getName()))
			.findFirst()
			.orElse(null);
	}

	private String csrfToken(String body) {
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(body);
		assertTrue(matcher.find(), body);
		return matcher.group(1);
	}

	private String loginBody(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
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
				"Redis 복구 검증 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				Instant.parse("2099-01-01T10:00:00Z"),
				"홍대",
				2));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}

}
