package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;

/** T4: 전송 제한 프로퍼티(사용자·방 허용량, 창 크기)를 기본값(5·30·10초)과 다르게 주입하면 그 값이 Lua 인자로 전달되고
 * TTL도 창 크기를 따라간다. */
@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(properties = {
	"app.notification.relay.enabled=false",
	"app.chat.rate-limit.user-limit=2",
	"app.chat.rate-limit.room-limit=3",
	"app.chat.rate-limit.window=3s"
})
@Import(ChatMessageRateLimitPropertiesPostgresTest.TestBeans.class)
class ChatMessageRateLimitPropertiesPostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final String RATE_LIMIT_PREFIX = "albam-mate:local:ratelimit";
	private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_rate_limit_properties_test");

	@Container
	static final GenericContainer REDIS = new GenericContainer(REDIS_IMAGE)
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	@Autowired
	private ChatMessageCommandService chatMessageCommandService;
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
		jdbcTemplate
			.execute("truncate table chat_messages, chat_rooms, participations, rooms, users restart identity cascade");
	}

	@Test
	void 주입된_사용자_허용량과_창_크기가_Lua_인자로_반영돼_경계와_TTL이_달라진다() {
		long userId = insertUser("사용자");
		Room firstRoom = createChatRoom(userId, 2);
		Room secondRoom = createChatRoom(userId, 2);

		send(userId, firstRoom.getId(), "first");
		Long initialTtl = redis().getExpire(userKey(userId), TimeUnit.MILLISECONDS);
		assertNotNull(initialTtl);
		assertTrue(initialTtl > 0 && initialTtl <= 3_000, "initial TTL=" + initialTtl);

		send(userId, secondRoom.getId(), "second");

		assertRateLimited(() -> send(userId, firstRoom.getId(), "third"));
		assertEquals(2, chatMessageRepository.count());
	}

	@Test
	void 주입된_방_허용량이_Lua_인자로_반영돼_경계가_달라진다() {
		long hostUserId = insertUser("방장");
		Room room = createChatRoom(hostUserId, 10);
		long participantUserId = insertUser("참가자");
		roomParticipationService.participate(participantUserId, room.getId());

		send(hostUserId, room.getId(), "room-1");
		send(participantUserId, room.getId(), "room-2");
		send(hostUserId, room.getId(), "room-3");

		assertRateLimited(() -> send(participantUserId, room.getId(), "room-4"));
		assertEquals(3, chatMessageRepository.count());
	}

	private ChatMessageSendResult send(long userId, long roomId, String clientMessageId) {
		return chatMessageCommandService
			.send(userId, roomId, new ChatMessageSendRequest(clientMessageId, "본문 " + clientMessageId));
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
				"전송 제한 프로퍼티 방",
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
		String email = "chat-rate-limit-properties-" + UUID.randomUUID() + "@example.com";
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

		private final List<MessageCommitted> events = new java.util.ArrayList<>();

		@Override
		public void publish(MessageCommitted event) {
			events.add(event);
		}
	}
}
