package cloud.bamsongi.albammate.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.global.response.ApiResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** T4: production profile도 Redis 연결 확인 실패 시 저장 전에 Retry-After 없는 503으로 fail-closed하는지 검증한다. */
@ActiveProfiles("production")
@SpringBootTest(properties = {
	"spring.datasource.url=jdbc:h2:mem:chat-rate-limit-unavailable-production;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
	"spring.datasource.username=sa",
	"spring.datasource.password=",
	"spring.datasource.driver-class-name=org.h2.Driver",
	"spring.flyway.locations=classpath:db/migration",
	"app.redis.host=127.0.0.1",
	"app.redis.port=1",
	"app.notification.relay.enabled=false"
})
@Import(ChatMessageRateLimitUnavailableIntegrationTest.TestBeans.class)
class ChatMessageRateLimitProductionUnavailableIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

	@Autowired
	private ChatMessageCommandService chatMessageCommandService;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private ChatMessageRateLimitUnavailableIntegrationTest.RecordingChatRealtimePublisher realtimePublisher;
	@Autowired
	private GlobalExceptionHandler globalExceptionHandler;
	@MockitoBean(name = "chatRealtimeMessageListenerContainer")
	private RedisMessageListenerContainer chatRealtimeMessageListenerContainer;

	private Long userId;
	private Long roomId;
	private Long chatRoomId;

	@AfterEach
	void tearDown() {
		if (chatRoomId != null) {
			jdbcTemplate.update("delete from chat_messages where chat_room_id = ?", chatRoomId);
			jdbcTemplate.update("delete from chat_rooms where id = ?", chatRoomId);
		}
		if (roomId != null) {
			jdbcTemplate.update("delete from rooms where id = ?", roomId);
		}
		if (userId != null) {
			jdbcTemplate.update("delete from users where id = ?", userId);
		}
		realtimePublisher.clear();
	}

	@Test
	void production_profile에서_Redis_연결_확인에_실패하면_저장_전에_Retry_After_없는_503으로_fail_closed한다() {
		long currentUserId = insertUser();
		Room room = createChatRoom(currentUserId);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> chatMessageCommandService.send(
				currentUserId, room.getId(), new ChatMessageSendRequest("redis-down", "저장되면 안 되는 본문")));
		assertEquals(503, exception.getErrorCode().getStatus());
		ResponseEntity<ApiResponse<Void>> response = globalExceptionHandler.handleBusinessException(exception);
		assertEquals(503, response.getStatusCode().value());
		assertNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
		assertEquals(0, chatMessageRepository.count());
		assertTrue(realtimePublisher.events().isEmpty());
	}

	private long insertUser() {
		String email = "chat-rate-limit-unavailable-production-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', '운영장애검증', ?, ?)",
			email,
			Timestamp.from(NOW),
			Timestamp.from(NOW));
		userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		return userId;
	}

	private Room createChatRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"운영 장애 검증 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대",
				2));
		roomId = room.getId();
		ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		chatRoomId = chatRoom.getId();
		return room;
	}
}
