package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
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
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;
import cloud.bamsongi.albammate.infra.redis.ChatMessageRateLimitProperties;
import cloud.bamsongi.albammate.infra.redis.RedisChatMessageRateLimiter;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;

/** T1~T3: production profile이 local과 분리된 namespace로 사용자·방 전송 제한을 등록하고 인스턴스 간 상태를 공유하는지 검증한다. */
@Testcontainers
@ActiveProfiles("production")
@SpringBootTest(properties = "app.notification.relay.enabled=false")
@Import(ChatMessageRateLimitProductionPostgresTest.TestBeans.class)
class ChatMessageRateLimitProductionPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String PRODUCTION_RATE_LIMIT_PREFIX = "albam-mate:production:ratelimit";
	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_rate_limit_production_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@Autowired
	private ChatMessageCommandService chatMessageCommandService;
	@Autowired
	private ChatMessageRateLimiter chatMessageRateLimiter;
	@Autowired
	private ChatMessageRateLimitProperties rateLimitProperties;
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
	private Environment environment;
	@Autowired
	private Flyway flyway;
	@Autowired
	private RecordingChatRealtimePublisher realtimePublisher;

	@DynamicPropertySource
	static void productionProperties(DynamicPropertyRegistry registry) {
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
	void T4_사용자_bucket은_production_namespace로_모든_방의_신규_전송을_합산해_오십건만_허용한다() {
		long userId = insertUser("사용자");
		Room firstRoom = createChatRoom(userId, 2);
		Room secondRoom = createChatRoom(userId, 2);

		for (int index = 1; index <= 50; index++) {
			send(userId, index % 2 == 0 ? secondRoom.getId() : firstRoom.getId(), "message-" + index);
		}

		assertTrue(Boolean.TRUE.equals(redis().hasKey(userKey(PRODUCTION_RATE_LIMIT_PREFIX, userId))));
		assertRateLimited(() -> send(userId, secondRoom.getId(), "fifty-first"));
		assertEquals(50, chatMessageRepository.count());
	}

	@Test
	void T4_방_bucket은_production_namespace로_모든_참여자의_신규_전송을_합산해_백건만_허용한다() {
		long hostUserId = insertUser("방장");
		Room room = createChatRoom(hostUserId, 10);
		List<Long> senders = new ArrayList<>(List.of(hostUserId));
		for (int index = 1; index <= 6; index++) {
			long participantUserId = insertUser("참가자" + index);
			roomParticipationService.participate(participantUserId, room.getId());
			senders.add(participantUserId);
		}

		for (int messageNumber = 1; messageNumber <= 100; messageNumber++) {
			send(senders.get((messageNumber - 1) % senders.size()), room.getId(), "room-" + messageNumber);
		}

		assertTrue(Boolean.TRUE.equals(redis().hasKey(roomKey(PRODUCTION_RATE_LIMIT_PREFIX, room.getId()))));
		assertRateLimited(() -> send(senders.get(6), room.getId(), "room-101"));
		assertEquals(100, chatMessageRepository.count());
	}

	@Test
	void T6_production_profile은_local_seed_없이_schema_only로_Flyway를_실행한다() {
		assertTrue(Arrays.stream(flyway.getConfiguration().getLocations())
			.noneMatch(location -> location.getDescriptor().contains("db/local")));
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from users where email = 'local.seed.host@albammate.local'",
				Integer.class));
	}

	@Test
	void T4_같은_Redis를_공유하는_두_production_인스턴스가_사용자와_방_bucket_상태를_공유해_오십백_합산_한도를_넘기지_못한다() {
		assertInstanceOf(RedisChatMessageRateLimiter.class, chatMessageRateLimiter);

		ChatMessageRateLimitProperties rateLimitProperties = new ChatMessageRateLimitProperties(50, 100,
			Duration.ofSeconds(10));
		RedisChatMessageRateLimiter firstInstance = new RedisChatMessageRateLimiter(redisConnectionFactory,
			environment, rateLimitProperties);
		RedisChatMessageRateLimiter secondInstance = new RedisChatMessageRateLimiter(redisConnectionFactory,
			environment, rateLimitProperties);

		long sharedUserId = 9_100_001L;
		long sharedRoomId = 9_200_001L;
		for (int index = 1; index <= 50; index++) {
			RedisChatMessageRateLimiter instance = index % 2 == 0 ? secondInstance : firstInstance;
			instance.reserve(sharedUserId, sharedRoomId);
		}
		assertThrows(RateLimitExceededException.class, () -> firstInstance.reserve(sharedUserId, sharedRoomId));
		assertThrows(RateLimitExceededException.class, () -> secondInstance.reserve(sharedUserId, sharedRoomId));

		long sharedRoomForManyUsersId = 9_200_002L;
		for (int index = 1; index <= 100; index++) {
			RedisChatMessageRateLimiter instance = index % 2 == 0 ? secondInstance : firstInstance;
			instance.reserve(9_100_100L + index, sharedRoomForManyUsersId);
		}
		assertThrows(
			RateLimitExceededException.class,
			() -> firstInstance.reserve(9_100_200L, sharedRoomForManyUsersId));
		assertThrows(
			RateLimitExceededException.class,
			() -> secondInstance.reserve(9_100_201L, sharedRoomForManyUsersId));
	}

	@Test
	void T6_production_전송_제한_상태_확인_실패는_저장_전_Retry_After_없는_오백삼으로_fail_closed한다() {
		assertEquals(50, rateLimitProperties.userLimit());
		assertEquals(100, rateLimitProperties.roomLimit());
		long userId = insertUser("production 코럽트");
		Room room = createChatRoom(userId, 2);
		redis().opsForValue().set(roomKey(PRODUCTION_RATE_LIMIT_PREFIX, room.getId()), "not-an-integer");

		BusinessException exception = assertThrows(
			BusinessException.class, () -> send(userId, room.getId(), "corrupt-rate-limit-state"));

		assertEquals(ErrorCode.SERVICE_UNAVAILABLE, exception.getErrorCode());
		assertEquals(0, chatMessageRepository.count());
	}

	private void send(long userId, long roomId, String clientMessageId) {
		chatMessageCommandService.send(userId, roomId,
			new ChatMessageSendRequest(clientMessageId, "본문 " + clientMessageId));
	}

	private void assertRateLimited(org.junit.jupiter.api.function.Executable executable) {
		BusinessException exception = assertThrows(BusinessException.class, executable);
		assertEquals(ErrorCode.RATE_LIMIT_EXCEEDED, exception.getErrorCode());
	}

	private Room createChatRoom(long hostUserId, int capacity) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"운영 전송 제한 방",
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
		String email = "chat-rate-limit-production-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			nickname,
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private String userKey(String prefix, long userId) {
		return prefix + ":user:" + userId;
	}

	private String roomKey(String prefix, long roomId) {
		return prefix + ":room:" + roomId;
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

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}

	static class RecordingChatRealtimePublisher implements ChatRealtimePublisher {

		private final List<MessageCommitted> events = new ArrayList<>();

		@Override
		public void publish(MessageCommitted event) {
			events.add(event);
		}

		void clear() {
			events.clear();
		}
	}
}
