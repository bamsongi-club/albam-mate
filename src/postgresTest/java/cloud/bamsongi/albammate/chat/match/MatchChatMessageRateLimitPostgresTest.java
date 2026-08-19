package cloud.bamsongi.albammate.chat.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
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
import cloud.bamsongi.albammate.infra.redis.RedisChatMessageRateLimiter;
import cloud.bamsongi.albammate.infra.redis.RedisMatchChatMessageRateLimiter;

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
@ActiveProfiles("local")
@SpringBootTest(properties = "app.notification.relay.enabled=false")
@Import(MatchChatMessageRateLimitPostgresTest.TestBeans.class)
class MatchChatMessageRateLimitPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String RATE_LIMIT_PREFIX = "albam-mate:local:ratelimit";
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

	@DynamicPropertySource
	static void localMultiProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("app.redis.host", REDIS::getHost);
		registry.add("app.redis.port", () -> REDIS.getMappedPort(6379));
	}

	@AfterEach
	void tearDown() {
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

	private MatchChatMessageSendResult send(long userId, long partyId, String clientMessageId) {
		return send(userId, partyId, clientMessageId, "본문 " + clientMessageId);
	}

	private MatchChatMessageSendResult send(long userId, long partyId, String clientMessageId, String content) {
		return matchChatMessageCommandService.send(
			userId, partyId, new MatchChatMessageSendRequest(clientMessageId, content));
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
