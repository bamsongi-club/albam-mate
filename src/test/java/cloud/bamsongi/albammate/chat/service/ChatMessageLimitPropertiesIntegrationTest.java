package cloud.bamsongi.albammate.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** T2: 메시지 길이 프로퍼티를 기본값(100·500)과 다르게 주입하면 그 주입값 경계가 실제 검증에 반영된다. */
@SpringBootTest(properties = {
	"app.chat.message.max-client-message-id-length=5",
	"app.chat.message.max-content-length=10"
})
class ChatMessageLimitPropertiesIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-08T00:00:00Z");

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

	private final List<Long> userIds = new ArrayList<>();
	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> chatRoomIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		chatRoomIds
			.forEach(chatRoomId -> jdbcTemplate.update("delete from chat_messages where chat_room_id = ?", chatRoomId));
		chatRoomIds.forEach(chatRoomId -> jdbcTemplate.update("delete from chat_rooms where id = ?", chatRoomId));
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from rooms where id = ?", roomId));
		userIds.forEach(userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void 주입된_길이_프로퍼티_경계에서_저장과_VALIDATION_ERROR가_갈린다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);

		ChatMessageSendResult atBoundary = chatMessageCommandService.send(
			hostUserId, room.getId(), new ChatMessageSendRequest("i".repeat(5), "c".repeat(10)));
		assertTrue(atBoundary.created());
		assertEquals(10, atBoundary.message().content().length());

		assertValidationError(hostUserId, room.getId(), new ChatMessageSendRequest("i".repeat(6), "짧은 본문"));
		assertValidationError(hostUserId, room.getId(), new ChatMessageSendRequest("abcde", "c".repeat(11)));

		assertEquals(1, chatMessageRepository.count());
	}

	@Test
	void T5_길이_제약이_0이면_기동이_바인딩_실패로_끝난다() {
		contextRunnerWith("app.chat.message.max-content-length=0")
			.run(context -> assertNotNull(context.getStartupFailure()));
		contextRunnerWith("app.chat.message.max-client-message-id-length=0")
			.run(context -> assertNotNull(context.getStartupFailure()));
		contextRunnerWith()
			.run(context -> assertNull(context.getStartupFailure()));
	}

	private ApplicationContextRunner contextRunnerWith(String... properties) {
		return new ApplicationContextRunner()
			.withUserConfiguration(ChatMessageLimitPropertiesConfiguration.class)
			.withPropertyValues(properties);
	}

	@TestConfiguration
	@EnableConfigurationProperties(ChatMessageLimitProperties.class)
	static class ChatMessageLimitPropertiesConfiguration {}

	private void assertValidationError(long userId, long roomId, ChatMessageSendRequest request) {
		BusinessException exception = assertThrows(
			BusinessException.class, () -> chatMessageCommandService.send(userId, roomId, request));
		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
	}

	private long insertUser(String nickname) {
		String email = "chat-limit-props-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			nickname,
			NOW,
			NOW);
		Long userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		userIds.add(userId);
		return userId;
	}

	private Room createChatRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"길이 프로퍼티 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대",
				2));
		roomIds.add(room.getId());
		ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		chatRoomIds.add(chatRoom.getId());
		return room;
	}
}
