package cloud.bamsongi.albammate.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.chat.dto.ChatMessagePageResponse;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@SpringBootTest
class ChatMessageHistoryQueryServiceIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

	@Autowired
	private ChatMessageHistoryQueryService chatMessageHistoryQueryService;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	private final List<Long> userIds = new ArrayList<>();
	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> chatRoomIds = new ArrayList<>();
	private final List<Long> participationIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		chatRoomIds
			.forEach(chatRoomId -> jdbcTemplate.update("delete from chat_messages where chat_room_id = ?", chatRoomId));
		participationIds.forEach(participationRepository::deleteById);
		chatRoomIds.forEach(chatRoomId -> jdbcTemplate.update("delete from chat_rooms where id = ?", chatRoomId));
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from rooms where id = ?", roomId));
		userIds.forEach(userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void 커서_없이_최신_메시지부터_size만큼_내림차순으로_반환하고_남으면_hasNext다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		List<Long> messageIds = insertMessages(room.getId(), hostUserId, 51);

		ChatMessagePageResponse page = chatMessageHistoryQueryService.history(hostUserId, room.getId(), null, 50);

		assertEquals(50, page.messages().size());
		assertTrue(page.hasNext());
		assertEquals(messageIds.get(messageIds.size() - 1), page.messages().get(0).messageId());
		assertEquals(messageIds.get(1), page.messages().get(49).messageId());
		assertEquals(messageIds.get(1), page.nextBeforeMessageId());
		for (int i = 0; i < page.messages().size() - 1; i++) {
			assertTrue(page.messages().get(i).messageId() > page.messages().get(i + 1).messageId());
		}
	}

	@Test
	void 존재하지_않는_양수_cursor는_오류_없이_빈_경계를_반환한다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		List<Long> messageIds = insertMessages(room.getId(), hostUserId, 1);
		long nonExistentCursor = messageIds.get(0) - 1;

		ChatMessagePageResponse page = chatMessageHistoryQueryService
			.history(hostUserId, room.getId(), nonExistentCursor, 50);

		assertTrue(page.messages().isEmpty());
		assertNull(page.nextBeforeMessageId());
		assertFalse(page.hasNext());
	}

	@Test
	void 참가_취소_사용자의_이력_조회는_FORBIDDEN이고_메시지를_노출하지_않는다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId);
		insertMessages(room.getId(), hostUserId, 1);
		Participation participation = participationRepository.saveAndFlush(
			Participation.createActive(room, participantUserId, NOW));
		participationIds.add(participation.getId());
		participation.cancel(NOW.plusSeconds(1));
		participationRepository.saveAndFlush(participation);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> chatMessageHistoryQueryService.history(participantUserId, room.getId(), null, 50));

		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	private List<Long> insertMessages(long roomId, long senderUserId, int count) {
		List<Long> messageIds = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			ChatMessage saved = chatMessageRepository.save(
				ChatMessage.create(
					chatRoomInternalId(roomId), senderUserId, "history-" + UUID.randomUUID(), "본문 " + i,
					NOW.plusSeconds(i)));
			messageIds.add(saved.getId());
		}
		return messageIds;
	}

	private long chatRoomInternalId(long roomId) {
		return chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
	}

	private long insertUser(String nickname) {
		String email = "chat-history-" + UUID.randomUUID() + "@example.com";
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
				"메시지 이력 방",
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
