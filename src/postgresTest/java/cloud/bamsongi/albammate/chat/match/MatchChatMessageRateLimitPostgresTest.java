package cloud.bamsongi.albammate.chat.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.chat.match.contract.MatchChatMessageCommitted;
import cloud.bamsongi.albammate.chat.match.contract.MatchChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.match.service.MatchChatMessageCommandService;
import cloud.bamsongi.albammate.chat.match.service.MatchChatMessageSendResult;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.infra.redis.ChatMessageRateLimitProperties;
import cloud.bamsongi.albammate.infra.redis.MatchChatMessageRateLimitProperties;
import cloud.bamsongi.albammate.infra.redis.RedisChatMessageRateLimiter;
import cloud.bamsongi.albammate.infra.redis.RedisMatchChatMessageRateLimiter;
import cloud.bamsongi.albammate.user.contract.CreateUserAccountCommand;
import cloud.bamsongi.albammate.user.contract.RawPassword;
import cloud.bamsongi.albammate.user.contract.UserAccountService;
import cloud.bamsongi.albammate.user.contract.UserEmail;
import cloud.bamsongi.albammate.user.contract.UserNickname;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * CHAT-T5 — MATCH 채팅 전송의 사용자 5건·Party 30건/10초 quota를 실제 PostgreSQL 트랜잭션과 Redis 원자 판정으로
 * 검증한다.
 *
 * <p>ADR-0080 축 1이 승인한 공유 엔진({@code RedisFixedWindowDualBucketRateLimiter})을 감싸는
 * {@link cloud.bamsongi.albammate.infra.redis.RedisMatchChatMessageRateLimiter}가 ROOM의 {@code room}
 * key와 겹치지 않는 {@code party} key로 예약·판정하는지, 저장이 성공했지만 호출자 트랜잭션이 rollback되면 예약이
 * release되는지를 함께 확인한다.
 */
@Testcontainers
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"app.notification.relay.enabled=false",
	"app.security.cookie.secure=false",
	"spring.flyway.locations=classpath:db/migration,classpath:db/vendor-migration/postgresql",
	"spring.jpa.hibernate.ddl-auto=validate"
})
@Import(MatchChatMessageRateLimitPostgresTest.TestBeans.class)
class MatchChatMessageRateLimitPostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String RATE_LIMIT_PREFIX = "albam-mate:local:ratelimit";
	private static final String PASSWORD = "123456789012345";
	private static final Pattern CSRF_TOKEN_PATTERN = Pattern.compile("\\\"token\\\":\\\"([^\\\"]+)\\\"");
	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_chat_rate_limit_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@Autowired
	private MatchChatMessageCommandService matchChatMessageCommandService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RedisConnectionFactory redisConnectionFactory;
	@Autowired
	private RedisChatMessageRateLimiter roomRateLimiter;
	@Autowired
	private RedisMatchChatMessageRateLimiter matchRateLimiter;
	@Autowired
	private RecordingMatchChatRealtimePublisher realtimePublisher;
	@Autowired
	private TransactionTemplate transactionTemplate;
	@Autowired
	private UserAccountService userAccountService;
	@Autowired
	private ObjectMapper objectMapper;
	@LocalServerPort
	private int serverPort;
	private boolean redisPaused;

	@DynamicPropertySource
	static void testContainerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@AfterEach
	void tearDown() {
		if (redisPaused) {
			startRedis();
		}
		redis().getConnectionFactory().getConnection().serverCommands().flushDb();
		realtimePublisher.clear();
		jdbcTemplate.execute(
			"truncate table match_chat_messages, match_chat_rooms, match_party_participants, match_parties, users "
				+ "restart identity cascade");
	}

	@Test
	void T5_같은_사용자의_ROOM과_MATCH_예약은_실제_Redis에서_count_TTL_reservation을_서로_변경하지_않는다() {
		long userId = insertUser();
		long partyId = insertActivePartyWithParticipants(userId);
		long roomId = 701L;

		var roomReservation = roomRateLimiter.reserve(userId, roomId);
		var matchReservation = matchRateLimiter.reserve(userId, partyId);

		assertEquals("1", redis().opsForValue().get(roomUserKey(userId)));
		assertEquals("1", redis().opsForValue().get(userKey(userId)));
		assertTrue(Boolean.TRUE.equals(redis().hasKey(roomUserReservationsKey(userId))));
		assertTrue(Boolean.TRUE.equals(redis().hasKey(userReservationsKey(userId))));
		assertNotNull(redis().getExpire(roomUserKey(userId), TimeUnit.MILLISECONDS));
		assertNotNull(redis().getExpire(userKey(userId), TimeUnit.MILLISECONDS));

		matchReservation.release();

		assertEquals("1", redis().opsForValue().get(roomUserKey(userId)));
		assertTrue(Boolean.TRUE.equals(redis().hasKey(roomUserReservationsKey(userId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userKey(userId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userReservationsKey(userId))));

		roomReservation.release();

		assertFalse(Boolean.TRUE.equals(redis().hasKey(roomUserKey(userId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(roomUserReservationsKey(userId))));
	}

	@Test
	void 사용자_bucket은_서로_다른_Party_전송을_합산해_다섯건만_허용하고_TTL을_연장하지_않는다() {
		long userId = insertUser();
		long firstPartyId = insertActivePartyWithParticipants(userId);
		long secondPartyId = insertActivePartyWithParticipants(userId);

		send(userId, firstPartyId, "first");
		Long initialTtl = redis().getExpire(userKey(userId), TimeUnit.MILLISECONDS);
		assertNotNull(initialTtl);
		assertTrue(initialTtl > 0 && initialTtl <= 10_000, "initial TTL=" + initialTtl);
		send(userId, secondPartyId, "second");
		send(userId, firstPartyId, "third");
		send(userId, secondPartyId, "fourth");
		send(userId, firstPartyId, "fifth");

		Long ttlAfterAllowedMessages = redis().getExpire(userKey(userId), TimeUnit.MILLISECONDS);
		assertNotNull(ttlAfterAllowedMessages);
		assertTrue(ttlAfterAllowedMessages > 0 && ttlAfterAllowedMessages <= initialTtl,
			"TTL이 연장됐습니다. initial=" + initialTtl + ", actual=" + ttlAfterAllowedMessages);
		assertRateLimited(() -> send(userId, secondPartyId, "sixth"));
	}

	@Test
	void Party_bucket은_여러_참가자의_전송을_합산해_서른건만_허용하고_TTL을_연장하지_않는다() {
		long partyId = insertActivePartyWithParticipants();
		List<Long> senders = new ArrayList<>();
		for (int index = 1; index <= 6; index++) {
			long userId = insertUser();
			addParticipant(partyId, userId);
			senders.add(userId);
		}

		send(senders.getFirst(), partyId, "party-1");
		Long initialTtl = redis().getExpire(partyKey(partyId), TimeUnit.MILLISECONDS);
		assertNotNull(initialTtl);
		assertTrue(initialTtl > 0 && initialTtl <= 10_000, "initial TTL=" + initialTtl);
		for (int messageNumber = 2; messageNumber <= 30; messageNumber++) {
			long sender = senders.get((messageNumber - 1) % senders.size());
			send(sender, partyId, "party-" + messageNumber);
		}

		Long ttlAfterAllowedMessages = redis().getExpire(partyKey(partyId), TimeUnit.MILLISECONDS);
		assertNotNull(ttlAfterAllowedMessages);
		assertTrue(ttlAfterAllowedMessages > 0 && ttlAfterAllowedMessages <= initialTtl,
			"TTL이 연장됐습니다. initial=" + initialTtl + ", actual=" + ttlAfterAllowedMessages);
		long freshSender = senders.get(5);
		assertRateLimited(() -> send(freshSender, partyId, "party-31"));
	}

	@Test
	void 하나라도_초과하면_두_bucket을_증가시키지_않고_두_bucket_초과시_더_긴_TTL을_Retry_After로_반환한다() {
		long userId = insertUser();
		long partyId = insertActivePartyWithParticipants(userId);
		redis().opsForValue().set(userKey(userId), "5", 2_500, TimeUnit.MILLISECONDS);
		redis().opsForValue().set(partyKey(partyId), "29", 2_500, TimeUnit.MILLISECONDS);
		assertRateLimited(() -> send(userId, partyId, "user-over"));
		assertEquals("5", redis().opsForValue().get(userKey(userId)));
		assertEquals("29", redis().opsForValue().get(partyKey(partyId)));

		redis().opsForValue().set(userKey(userId), "4", 2_500, TimeUnit.MILLISECONDS);
		redis().opsForValue().set(partyKey(partyId), "30", 2_500, TimeUnit.MILLISECONDS);
		assertRateLimited(() -> send(userId, partyId, "party-over"));
		assertEquals("4", redis().opsForValue().get(userKey(userId)));
		assertEquals("30", redis().opsForValue().get(partyKey(partyId)));

		redis().opsForValue().set(userKey(userId), "4", 2_500, TimeUnit.MILLISECONDS);
		redis().opsForValue().set(partyKey(partyId), "29", 2_500, TimeUnit.MILLISECONDS);
		send(userId, partyId, "last-allowed");
		assertEquals("5", redis().opsForValue().get(userKey(userId)));
		assertEquals("30", redis().opsForValue().get(partyKey(partyId)));

		redis().opsForValue().set(partyKey(partyId), "30", 5_000, TimeUnit.MILLISECONDS);
		Long userTtl = redis().getExpire(userKey(userId), TimeUnit.MILLISECONDS);
		Long partyTtl = redis().getExpire(partyKey(partyId), TimeUnit.MILLISECONDS);
		assertNotNull(userTtl);
		assertNotNull(partyTtl);
		RateLimitExceededException exception = assertThrows(
			RateLimitExceededException.class, () -> send(userId, partyId, "both-over-longer-ttl"));

		int expectedRetryAfter = (int)Math.ceil(Math.max(userTtl, partyTtl) / 1_000.0);
		assertTrue(exception.getRetryAfterSeconds() <= expectedRetryAfter);
		assertTrue(exception.getRetryAfterSeconds() >= Math.max(1, expectedRetryAfter - 1));
		assertEquals("5", redis().opsForValue().get(userKey(userId)));
		assertEquals("30", redis().opsForValue().get(partyKey(partyId)));
	}

	@Test
	void party_key가_비정수이면_user_INCR_전에_검증에서_막혀_두_bucket_모두_변하지_않고_503으로_끝난다() {
		long userId = insertUser();
		long partyId = insertActivePartyWithParticipants(userId);
		redis().opsForValue().set(userKey(userId), "2", 10_000, TimeUnit.MILLISECONDS);
		redis().opsForValue().set(partyKey(partyId), "1.5", 10_000, TimeUnit.MILLISECONDS);

		assertBusinessError(ErrorCode.SERVICE_UNAVAILABLE, () -> send(userId, partyId, "corrupt-party"));

		assertEquals("2", redis().opsForValue().get(userKey(userId)));
		assertEquals("1.5", redis().opsForValue().get(partyKey(partyId)));
	}

	@Test
	void user_key가_비정수이면_party_INCR_전에_검증에서_막혀_두_bucket_모두_변하지_않고_503으로_끝난다() {
		long userId = insertUser();
		long partyId = insertActivePartyWithParticipants(userId);
		redis().opsForValue().set(userKey(userId), "abc", 10_000, TimeUnit.MILLISECONDS);
		redis().opsForValue().set(partyKey(partyId), "3", 10_000, TimeUnit.MILLISECONDS);

		assertBusinessError(ErrorCode.SERVICE_UNAVAILABLE, () -> send(userId, partyId, "corrupt-user"));

		assertEquals("abc", redis().opsForValue().get(userKey(userId)));
		assertEquals("3", redis().opsForValue().get(partyKey(partyId)));
	}

	@Test
	void 검증과_권한_실패와_동일_멱등_재전송은_quota를_소비하지_않고_신규_메시지만_소비한다() {
		long hostUserId = insertUser();
		long strangerUserId = insertUser();
		long partyId = insertActivePartyWithParticipants(hostUserId);

		assertBusinessError(ErrorCode.VALIDATION_ERROR, () -> send(hostUserId, partyId, "invalid", "   "));
		assertBusinessError(ErrorCode.FORBIDDEN, () -> send(strangerUserId, partyId, "forbidden", "본문"));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userKey(hostUserId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userKey(strangerUserId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(partyKey(partyId))));

		send(hostUserId, partyId, "replay", "첫 본문");
		send(hostUserId, partyId, "replay", "첫 본문");
		assertEquals("1", redis().opsForValue().get(partyKey(partyId)));
		for (int index = 2; index <= 5; index++) {
			send(hostUserId, partyId, "new-" + index);
		}

		assertEquals("5", redis().opsForValue().get(partyKey(partyId)));
		assertRateLimited(() -> send(hostUserId, partyId, "new-6"));
	}

	@Test
	void PostgreSQL에_저장된_신규_메시지만_커밋_후_전달되고_저장_후_트랜잭션이_rollback되면_예약도_해제된다() {
		long userId = insertUser();
		long partyId = insertActivePartyWithParticipants(userId);
		transactionTemplate.executeWithoutResult(status -> {
			send(userId, partyId, "rollback");
			status.setRollbackOnly();
		});
		assertTrue(realtimePublisher.events().isEmpty());
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userKey(userId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(partyKey(partyId))));

		for (int index = 1; index <= 5; index++) {
			MatchChatMessageSendResult result = send(userId, partyId, "stored-" + index);
			assertTrue(result.created());
		}

		assertRateLimited(() -> send(userId, partyId, "not-stored"));
		assertEquals(5, realtimePublisher.events().size());
	}

	@Test
	void T6_A_Redis_rate_limit_저장소_장애면_실제_HTTP_전송은_저장과_signal과_quota_소비_전에_503으로_끝난다()
		throws Exception {
		String email = uniqueEmail("t6-a");
		long userId = createAccount(email, "T6 A 사용자");
		long partyId = insertActivePartyWithParticipants(userId);
		HttpClient client = authenticatedClient(email);
		String csrfToken = csrfToken(client);
		redis().opsForValue().set(userKey(userId), "2", 10, TimeUnit.SECONDS);
		redis().opsForValue().set(partyKey(partyId), "4", 10, TimeUnit.SECONDS);

		stopRedis();
		HttpResponse<String> response = postMessage(client, partyId, "t6-a-outage", csrfToken);

		assertEquals(503, response.statusCode(), response.body());
		startRedis();
		assertEquals(0, messageCount(partyId));
		assertTrue(realtimePublisher.events().isEmpty());
		assertEquals("2", redis().opsForValue().get(userKey(userId)));
		assertEquals("4", redis().opsForValue().get(partyKey(partyId)));
	}

	@Test
	void T6_B_Redis_장애_응답은_고정_503_오류_봉투이고_메시지_row와_signal을_만들지_않는다() throws Exception {
		String email = uniqueEmail("t6-b");
		long userId = createAccount(email, "T6 B 사용자");
		long partyId = insertActivePartyWithParticipants(userId);
		HttpClient client = authenticatedClient(email);
		String csrfToken = csrfToken(client);

		stopRedis();
		HttpResponse<String> response = postMessage(client, partyId, "t6-b-outage", csrfToken);

		assertEquals(503, response.statusCode(), response.body());
		JsonNode body = objectMapper.readTree(response.body());
		assertEquals(503, body.path("status").asInt(), response.body());
		assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getCode(), body.path("code").asString(), response.body());
		assertEquals(ErrorCode.SERVICE_UNAVAILABLE.getMessage(), body.path("message").asString(), response.body());
		assertTrue(body.path("data").isNull(), response.body());
		assertTrue(response.headers().firstValue("Retry-After").isEmpty());
		startRedis();
		assertEquals(0, messageCount(partyId));
		assertTrue(realtimePublisher.events().isEmpty());
	}

	@Test
	void T6_C_Redis_복구_뒤_ACTIVE_참가자의_실제_HTTP_전송은_저장_quota_응답을_정상_처리한다() throws Exception {
		String email = uniqueEmail("t6-c");
		long userId = createAccount(email, "T6 C 사용자");
		long partyId = insertActivePartyWithParticipants(userId);
		HttpClient client = authenticatedClient(email);
		String csrfToken = csrfToken(client);

		stopRedis();
		assertEquals(503, postMessage(client, partyId, "t6-c-outage", csrfToken).statusCode());
		startRedis();
		HttpResponse<String> response = postMessage(client, partyId, "t6-c-recovered", csrfToken);

		assertEquals(201, response.statusCode(), response.body());
		JsonNode body = objectMapper.readTree(response.body());
		assertEquals(201, body.path("status").asInt(), response.body());
		assertEquals("t6-c-recovered", body.path("data").path("clientMessageId").asString(), response.body());
		assertEquals(1, messageCount(partyId));
		assertEquals(1, realtimePublisher.events().size());
		assertEquals("1", redis().opsForValue().get(userKey(userId)));
		assertEquals("1", redis().opsForValue().get(partyKey(partyId)));
	}

	@Test
	void T6_D_인증_권한_상태_CSRF_거부는_Redis_장애와_무관하게_기존_HTTP_계약을_유지한다() throws Exception {
		String activeEmail = uniqueEmail("t6-d-active");
		long activeUserId = createAccount(activeEmail, "T6 D 활성 사용자");
		long activePartyId = insertActivePartyWithParticipants(activeUserId);
		String strangerEmail = uniqueEmail("t6-d-stranger");
		long strangerUserId = createAccount(strangerEmail, "T6 D 비참가 사용자");
		String preparingEmail = uniqueEmail("t6-d-preparing");
		long preparingUserId = createAccount(preparingEmail, "T6 D 준비 사용자");
		long preparingPartyId = insertPartyWithParticipant("PREPARING", preparingUserId);
		String closedEmail = uniqueEmail("t6-d-closed");
		long closedUserId = createAccount(closedEmail, "T6 D 종료 사용자");
		long closedPartyId = insertPartyWithParticipant("CLOSED", closedUserId);

		HttpClient activeClient = authenticatedClient(activeEmail);
		String activeCsrfToken = csrfToken(activeClient);
		HttpClient strangerClient = authenticatedClient(strangerEmail);
		String strangerCsrfToken = csrfToken(strangerClient);
		HttpClient preparingClient = authenticatedClient(preparingEmail);
		String preparingCsrfToken = csrfToken(preparingClient);
		HttpClient closedClient = authenticatedClient(closedEmail);
		String closedCsrfToken = csrfToken(closedClient);
		HttpClient anonymousClient = HttpClient.newHttpClient();

		stopRedis();
		assertError(anonymousClient, activePartyId, "t6-d-anonymous", null, ErrorCode.UNAUTHENTICATED);
		assertError(strangerClient, activePartyId, "t6-d-forbidden", strangerCsrfToken, ErrorCode.FORBIDDEN);
		assertError(preparingClient, preparingPartyId, "t6-d-preparing", preparingCsrfToken,
			ErrorCode.MATCH_CHAT_NOT_ACTIVE);
		assertError(closedClient, closedPartyId, "t6-d-closed", closedCsrfToken, ErrorCode.FORBIDDEN);
		assertError(activeClient, activePartyId, "t6-d-csrf", null, ErrorCode.CSRF_TOKEN_INVALID);

		startRedis();
		assertEquals(0, messageCount(activePartyId));
		assertEquals(0, messageCount(preparingPartyId));
		assertEquals(0, messageCount(closedPartyId));
		assertTrue(realtimePublisher.events().isEmpty());
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userKey(activeUserId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userKey(strangerUserId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userKey(preparingUserId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userKey(closedUserId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(partyKey(activePartyId))));
	}

	private MatchChatMessageSendResult send(long userId, long partyId, String clientMessageId) {
		return send(userId, partyId, clientMessageId, "본문 " + clientMessageId);
	}

	private MatchChatMessageSendResult send(long userId, long partyId, String clientMessageId, String content) {
		return matchChatMessageCommandService.send(
			userId, partyId, new MatchChatMessageSendRequest(clientMessageId, content));
	}

	private HttpClient authenticatedClient(String email) throws Exception {
		CookieManager cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		HttpClient client = HttpClient.newBuilder().cookieHandler(cookieManager).build();
		String csrfToken = csrfToken(client);
		HttpResponse<String> response = post(
			client, serverUri("/api/auth/login"), loginBody(email), csrfToken);
		assertEquals(200, response.statusCode(), response.body());
		return client;
	}

	private String csrfToken(HttpClient client) throws Exception {
		HttpResponse<String> response = get(client, serverUri("/api/auth/csrf"));
		assertEquals(200, response.statusCode(), response.body());
		Matcher matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
		assertTrue(matcher.find(), response.body());
		return matcher.group(1);
	}

	private HttpResponse<String> postMessage(
		HttpClient client, long partyId, String clientMessageId, String csrfToken) throws Exception {
		return post(
			client,
			serverUri("/api/matches/parties/" + partyId + "/chat/messages"),
			"{\"clientMessageId\":\"" + clientMessageId + "\",\"content\":\"본문\"}",
			csrfToken);
	}

	private void assertError(
		HttpClient client, long partyId, String clientMessageId, String csrfToken, ErrorCode errorCode) throws Exception {
		HttpResponse<String> response = postMessage(client, partyId, clientMessageId, csrfToken);
		assertEquals(errorCode.getStatus(), response.statusCode(), response.body());
		JsonNode body = objectMapper.readTree(response.body());
		assertEquals(errorCode.getStatus(), body.path("status").asInt(), response.body());
		assertEquals(errorCode.getCode(), body.path("code").asString(), response.body());
	}

	private HttpResponse<String> get(HttpClient client, URI uri) throws Exception {
		return client.send(
			HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private HttpResponse<String> post(HttpClient client, URI uri, String body, String csrfToken) throws Exception {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri)
			.header("Content-Type", "application/json")
			.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
		if (csrfToken != null) {
			request.header("X-XSRF-TOKEN", csrfToken);
		}
		return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private URI serverUri(String path) {
		return URI.create("http://localhost:" + serverPort + path);
	}

	private String loginBody(String email) {
		return "{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}";
	}

	private String uniqueEmail(String prefix) {
		return "match-chat-rate-limit-" + prefix + "-" + UUID.randomUUID() + "@example.com";
	}

	private long createAccount(String email, String nickname) {
		return userAccountService.createAccount(new CreateUserAccountCommand(
			UserEmail.from(email).orElseThrow(),
			RawPassword.from(PASSWORD).orElseThrow(),
			UserNickname.from(nickname).orElseThrow())).id();
	}

	private void stopRedis() {
		DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();
		redisPaused = true;
	}

	private void startRedis() {
		DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
		redisPaused = false;
		assertTrue(REDIS.isRunning());
		for (int attempt = 0; attempt < 10; attempt++) {
			try {
				redis().opsForValue().set("match-chat-rate-limit-recovery-probe", "ready");
				redis().delete("match-chat-rate-limit-recovery-probe");
				return;
			} catch (RuntimeException exception) {
				if (attempt == 9) {
					throw exception;
				}
				try {
					Thread.sleep(Duration.ofMillis(100));
				} catch (InterruptedException interruptedException) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException("Redis 복구 대기 중 인터럽트되었습니다.", interruptedException);
				}
			}
		}
	}

	private int messageCount(long partyId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from match_chat_messages message join match_chat_rooms room "
				+ "on room.id = message.match_chat_room_id where room.party_id = ?",
			Integer.class,
			partyId);
	}

	private void assertRateLimited(org.junit.jupiter.api.function.Executable executable) {
		assertBusinessError(ErrorCode.RATE_LIMIT_EXCEEDED, executable);
	}

	private void assertBusinessError(ErrorCode errorCode, org.junit.jupiter.api.function.Executable executable) {
		BusinessException exception = assertThrows(BusinessException.class, executable);
		assertEquals(errorCode, exception.getErrorCode());
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
			addParticipant(partyId, userId);
		}
		return partyId;
	}

	private long insertPartyWithParticipant(String status, long userId) {
		long partyId = insertActivePartyWithParticipants(userId);
		if ("PREPARING".equals(status)) {
			jdbcTemplate.update("update match_parties set status = 'PREPARING' where id = ?", partyId);
			return partyId;
		}
		if ("CLOSED".equals(status)) {
			jdbcTemplate.update(
				"update match_parties set status = 'CLOSED', closed_at = current_timestamp, "
					+ "purge_after = current_timestamp + interval '7 days' where id = ?",
				partyId);
			return partyId;
		}
		throw new IllegalArgumentException("지원하지 않는 MATCH party 상태입니다: " + status);
	}

	private void addParticipant(long partyId, long userId) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) "
				+ "values (?, ?, ?, current_timestamp)",
			partyId, userId, UUID.randomUUID());
	}

	private long insertUser() {
		String email = "match-chat-rate-limit-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			"매칭 사용자",
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private String userKey(long userId) {
		return RATE_LIMIT_PREFIX + ":match:user:" + userId;
	}

	private String partyKey(long partyId) {
		return RATE_LIMIT_PREFIX + ":match:party:" + partyId;
	}

	private String userReservationsKey(long userId) {
		return userKey(userId) + ":reservations";
	}

	private String roomUserKey(long userId) {
		return RATE_LIMIT_PREFIX + ":user:" + userId;
	}

	private String roomUserReservationsKey(long userId) {
		return roomUserKey(userId) + ":reservations";
	}

	private StringRedisTemplate redis() {
		StringRedisTemplate redis = new StringRedisTemplate(redisConnectionFactory);
		redis.afterPropertiesSet();
		return redis;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		@Primary
		LettuceConnectionFactory redisConnectionFactory() {
			ClientOptions clientOptions = ClientOptions.builder()
				.autoReconnect(true)
				.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
				.socketOptions(SocketOptions.builder().connectTimeout(Duration.ofSeconds(1)).build())
				.build();
			LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
				.clientOptions(clientOptions)
				.commandTimeout(Duration.ofSeconds(2))
				.build();
			LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
				new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
				clientConfiguration);
			connectionFactory.setShareNativeConnection(true);
			return connectionFactory;
		}

		@Bean
		@Primary
		RedisChatMessageRateLimiter roomRateLimiter(
			RedisConnectionFactory redisConnectionFactory, Environment environment) {
			return new RedisChatMessageRateLimiter(
				redisConnectionFactory,
				environment,
				new ChatMessageRateLimitProperties(50, 100, Duration.ofSeconds(10)));
		}

		@Bean
		@Primary
		RedisMatchChatMessageRateLimiter matchChatMessageRateLimiter(
			RedisConnectionFactory redisConnectionFactory, Environment environment) {
			return new RedisMatchChatMessageRateLimiter(
				redisConnectionFactory,
				environment,
				new MatchChatMessageRateLimitProperties(5, 30, Duration.ofSeconds(10)));
		}

		@Bean
		@Primary
		RecordingMatchChatRealtimePublisher recordingMatchChatRealtimePublisher() {
			return new RecordingMatchChatRealtimePublisher();
		}

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	static class RecordingMatchChatRealtimePublisher implements MatchChatRealtimePublisher {

		private final List<MatchChatMessageCommitted> events = new ArrayList<>();

		@Override
		public void publish(MatchChatMessageCommitted event) {
			events.add(event);
		}

		List<MatchChatMessageCommitted> events() {
			return List.copyOf(events);
		}

		void clear() {
			events.clear();
		}
	}
}
