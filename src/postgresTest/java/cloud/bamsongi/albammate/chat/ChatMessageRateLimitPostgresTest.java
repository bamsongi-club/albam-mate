package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
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

import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;
import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatMessageCommandService;
import cloud.bamsongi.albammate.chat.service.ChatMessageSendResult;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;

@Testcontainers
@ActiveProfiles("local-multi")
@SpringBootTest(properties = "app.notification.relay.enabled=false")
@Import(ChatMessageRateLimitPostgresTest.TestBeans.class)
class ChatMessageRateLimitPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String RATE_LIMIT_PREFIX = "albam-mate:local-multi:ratelimit";
	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_rate_limit_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@Autowired
	private ChatMessageCommandService chatMessageCommandService;
	@Autowired
	private ChatMessageRateLimiter chatMessageRateLimiter;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RedisConnectionFactory redisConnectionFactory;
	@Autowired
	private RecordingChatRealtimePublisher realtimePublisher;
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
		jdbcTemplate
			.execute("truncate table chat_messages, chat_rooms, participations, rooms, users restart identity cascade");
	}

	@Test
	void 사용자_bucket은_모든_방의_신규_전송을_합산해_다섯_건만_허용하고_TTL을_연장하지_않는다() {
		long userId = insertUser("사용자");
		Room firstRoom = createChatRoom(userId, 2);
		Room secondRoom = createChatRoom(userId, 2);

		send(userId, firstRoom.getId(), "first");
		Long initialTtl = redis().getExpire(userKey(userId), TimeUnit.MILLISECONDS);
		assertNotNull(initialTtl);
		assertTrue(initialTtl > 0 && initialTtl <= 10_000, "initial TTL=" + initialTtl);
		for (int index = 2; index <= 5; index++) {
			send(userId, index % 2 == 0 ? secondRoom.getId() : firstRoom.getId(), "message-" + index);
		}

		Long ttlAfterAllowedMessages = redis().getExpire(userKey(userId), TimeUnit.MILLISECONDS);
		assertNotNull(ttlAfterAllowedMessages);
		assertTrue(ttlAfterAllowedMessages > 0 && ttlAfterAllowedMessages <= initialTtl,
			"TTL이 연장됐습니다. initial=" + initialTtl + ", actual=" + ttlAfterAllowedMessages);
		assertRateLimited(() -> send(userId, secondRoom.getId(), "sixth"));
		assertEquals(5, chatMessageRepository.count());
	}

	@Test
	void 방_bucket은_모든_참여자의_신규_전송을_합산해_서른_건만_허용하고_TTL을_연장하지_않는다() {
		long hostUserId = insertUser("방장");
		Room room = createChatRoom(hostUserId, 10);
		List<Long> senders = new ArrayList<>(List.of(hostUserId));
		for (int index = 1; index <= 6; index++) {
			long participantUserId = insertUser("참가자" + index);
			roomParticipationService.participate(participantUserId, room.getId());
			senders.add(participantUserId);
		}

		send(senders.getFirst(), room.getId(), "room-1");
		Long initialTtl = redis().getExpire(roomKey(room.getId()), TimeUnit.MILLISECONDS);
		assertNotNull(initialTtl);
		assertTrue(initialTtl > 0 && initialTtl <= 10_000, "initial TTL=" + initialTtl);
		int messageNumber = 2;
		for (int senderIndex = 0; senderIndex < 6; senderIndex++) {
			int sends = senderIndex == 0 ? 4 : 5;
			for (int sendIndex = 0; sendIndex < sends; sendIndex++) {
				send(senders.get(senderIndex), room.getId(), "room-" + messageNumber++);
			}
		}

		Long ttlAfterAllowedMessages = redis().getExpire(roomKey(room.getId()), TimeUnit.MILLISECONDS);
		assertNotNull(ttlAfterAllowedMessages);
		assertTrue(ttlAfterAllowedMessages > 0 && ttlAfterAllowedMessages <= initialTtl,
			"TTL이 연장됐습니다. initial=" + initialTtl + ", actual=" + ttlAfterAllowedMessages);
		assertRateLimited(() -> send(senders.get(6), room.getId(), "room-31"));
		assertEquals(30, chatMessageRepository.count());
	}

	@Test
	void 하나라도_초과하면_두_bucket을_증가시키지_않고_두_bucket_초과시_더_긴_TTL을_Retry_After로_반환한다() {
		long userId = insertUser("원자성");
		Room room = createChatRoom(userId, 2);
		redis().opsForValue().set(userKey(userId), "5", 2_500, TimeUnit.MILLISECONDS);
		redis().opsForValue().set(roomKey(room.getId()), "7", 2_500, TimeUnit.MILLISECONDS);

		assertRateLimited(() -> send(userId, room.getId(), "user-over"));
		assertEquals("5", redis().opsForValue().get(userKey(userId)));
		assertEquals("7", redis().opsForValue().get(roomKey(room.getId())));

		redis().opsForValue().set(roomKey(room.getId()), "30", 5_000, TimeUnit.MILLISECONDS);
		Long userTtl = redis().getExpire(userKey(userId), TimeUnit.MILLISECONDS);
		Long roomTtl = redis().getExpire(roomKey(room.getId()), TimeUnit.MILLISECONDS);
		assertNotNull(userTtl);
		assertNotNull(roomTtl);
		RateLimitExceededException exception = assertThrows(
			RateLimitExceededException.class, () -> send(userId, room.getId(), "both-over"));

		int expectedRetryAfter = (int)Math.ceil(Math.max(userTtl, roomTtl) / 1_000.0);
		assertTrue(exception.getRetryAfterSeconds() <= expectedRetryAfter);
		assertTrue(exception.getRetryAfterSeconds() >= Math.max(1, expectedRetryAfter - 1));
		assertEquals("5", redis().opsForValue().get(userKey(userId)));
		assertEquals("30", redis().opsForValue().get(roomKey(room.getId())));
	}

	@Test
	void 검증과_권한_실패와_동일_멱등_재전송은_quota를_소비하지_않고_신규_메시지만_소비한다() {
		long hostUserId = insertUser("검증방장");
		long strangerUserId = insertUser("비참여자");
		Room room = createChatRoom(hostUserId, 2);

		assertBusinessError(ErrorCode.VALIDATION_ERROR,
			() -> send(hostUserId, room.getId(), "invalid", "   "));
		assertBusinessError(ErrorCode.FORBIDDEN,
			() -> send(strangerUserId, room.getId(), "forbidden", "본문"));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(userKey(hostUserId))));
		assertFalse(Boolean.TRUE.equals(redis().hasKey(roomKey(room.getId()))));

		send(hostUserId, room.getId(), "replay", "첫 본문");
		send(hostUserId, room.getId(), "replay", "첫 본문");
		assertEquals("1", redis().opsForValue().get(roomKey(room.getId())));
		for (int index = 2; index <= 5; index++) {
			send(hostUserId, room.getId(), "new-" + index);
		}

		assertEquals("5", redis().opsForValue().get(roomKey(room.getId())));
		assertRateLimited(() -> send(hostUserId, room.getId(), "new-6"));
		assertEquals(5, chatMessageRepository.count());
		assertEquals(5, realtimePublisher.events().size());
	}

	@Test
	void PostgreSQL에_저장된_신규_메시지만_커밋_후_전달되고_제한_초과_요청은_둘다_만들지_않는다() {
		long userId = insertUser("저장경계");
		Room room = createChatRoom(userId, 2);
		transactionTemplate.executeWithoutResult(status -> {
			send(userId, room.getId(), "rollback");
			status.setRollbackOnly();
		});
		assertEquals(0, chatMessageRepository.count());
		assertTrue(realtimePublisher.events().isEmpty());

		for (int index = 1; index <= 5; index++) {
			ChatMessageSendResult result = send(userId, room.getId(), "stored-" + index);
			assertTrue(result.created());
		}

		assertRateLimited(() -> send(userId, room.getId(), "not-stored"));
		assertEquals(5, chatMessageRepository.count());
		assertEquals(5, realtimePublisher.events().size());
	}

	@Test
	void 이전_window의_rollback이_새_window_quota를_차감하지_않고_한_bucket_만료도_다른_bucket을_보존한다() {
		long userId = insertUser("window 경계");
		Room room = createChatRoom(userId, 2);

		ChatMessageRateLimiter.RateLimitReservation oldReservation = chatMessageRateLimiter
			.reserve(userId, room.getId());
		redis().delete(List.of(
			userKey(userId), roomKey(room.getId()), userReservationsKey(userId), roomReservationsKey(room.getId())));
		ChatMessageRateLimiter.RateLimitReservation newReservation = chatMessageRateLimiter
			.reserve(userId, room.getId());
		oldReservation.release();
		assertEquals("1", redis().opsForValue().get(userKey(userId)));
		assertEquals("1", redis().opsForValue().get(roomKey(room.getId())));
		newReservation.release();

		ChatMessageRateLimiter.RateLimitReservation partialWindowReservation = chatMessageRateLimiter
			.reserve(userId, room.getId());
		redis().delete(List.of(userKey(userId), userReservationsKey(userId)));
		ChatMessageRateLimiter.RateLimitReservation nextReservation = chatMessageRateLimiter
			.reserve(userId, room.getId());
		partialWindowReservation.release();
		assertEquals("1", redis().opsForValue().get(roomKey(room.getId())));
		nextReservation.release();
	}

	private ChatMessageSendResult send(long userId, long roomId, String clientMessageId) {
		return send(userId, roomId, clientMessageId, "본문 " + clientMessageId);
	}

	private ChatMessageSendResult send(long userId, long roomId, String clientMessageId, String content) {
		return chatMessageCommandService.send(userId, roomId, new ChatMessageSendRequest(clientMessageId, content));
	}

	private void assertRateLimited(org.junit.jupiter.api.function.Executable executable) {
		assertBusinessError(ErrorCode.RATE_LIMIT_EXCEEDED, executable);
	}

	private void assertBusinessError(ErrorCode errorCode, org.junit.jupiter.api.function.Executable executable) {
		BusinessException exception = assertThrows(BusinessException.class, executable);
		assertEquals(errorCode, exception.getErrorCode());
	}

	private Room createChatRoom(long hostUserId, int capacity) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"전송 제한 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대",
				capacity));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}

	private long insertUser(String nickname) {
		String email = "chat-rate-limit-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			nickname,
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private String userKey(long userId) {
		return RATE_LIMIT_PREFIX + ":user:" + userId;
	}

	private String roomKey(long roomId) {
		return RATE_LIMIT_PREFIX + ":room:" + roomId;
	}

	private String userReservationsKey(long userId) {
		return userKey(userId) + ":reservations";
	}

	private String roomReservationsKey(long roomId) {
		return roomKey(roomId) + ":reservations";
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
		RecordingChatRealtimePublisher recordingChatRealtimePublisher() {
			return new RecordingChatRealtimePublisher();
		}
	}

	static class RecordingChatRealtimePublisher implements ChatRealtimePublisher {

		private final List<MessageCommitted> events = new ArrayList<>();

		@Override
		public void publish(MessageCommitted event) {
			events.add(event);
		}

		List<MessageCommitted> events() {
			return List.copyOf(events);
		}

		void clear() {
			events.clear();
		}
	}
}
