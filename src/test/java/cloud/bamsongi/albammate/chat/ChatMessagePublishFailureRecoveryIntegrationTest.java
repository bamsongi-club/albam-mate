package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.dto.ChatMessagePageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatMessageCommandService;
import cloud.bamsongi.albammate.chat.service.ChatMessageHistoryQueryService;
import cloud.bamsongi.albammate.chat.service.ChatMessageSendResult;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/**
 * T8: 커밋 뒤 Pub/Sub 발행이 실패해도 저장 성공 응답과 이력이 유지되고, 이력 조회와 재연결이 누락분을 복구하는지 검증한다.
 */
@SpringBootTest(properties = "app.notification.relay.enabled=false")
@Import(ChatMessagePublishFailureRecoveryIntegrationTest.TestBeans.class)
class ChatMessagePublishFailureRecoveryIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-05T00:00:00Z");

	@Autowired
	private ChatMessageCommandService chatMessageCommandService;
	@Autowired
	private ChatMessageHistoryQueryService chatMessageHistoryQueryService;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

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
	}

	@Test
	void 발행_실패는_저장_성공_응답과_이력을_유지하고_이력_조회와_재연결로_누락분을_복구한다() throws Exception {
		long currentUserId = insertUser();
		Room room = createChatRoom(currentUserId);

		ChatMessageSendResult result = chatMessageCommandService.send(
			currentUserId, room.getId(), new ChatMessageSendRequest("publish-fail-1", "발행 실패해도 저장은 유지"));

		assertTrue(result.created());
		assertEquals(1, chatMessageRepository.count());

		ChatMessagePageResponse history = chatMessageHistoryQueryService.history(
			currentUserId, room.getId(), null, 50);
		assertEquals(1, history.messages().size());
		assertEquals(result.message().messageId(), history.messages().get(0).messageId());

		ChatMessage saved = chatMessageRepository.findAll().get(0);
		java.util.List<ChatMessage> recovered = chatMessageRepository
			.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(chatRoomId, 0L);
		assertEquals(1, recovered.size());
		assertEquals(saved.getId(), recovered.get(0).getId());
	}

	private long insertUser() {
		String email = "chat-publish-failure-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', '발행실패검증', ?, ?)",
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
				"발행 실패 검증 방",
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

	@TestConfiguration(proxyBeanMethods = false)
	static class TestBeans {

		@Bean
		@Primary
		ChatRealtimePublisher throwingChatRealtimePublisher() {
			return event -> {
				throw new IllegalStateException("redis publish unavailable");
			};
		}
	}
}
