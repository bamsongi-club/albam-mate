package cloud.bamsongi.albammate.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.chat.contract.ChatRoomPreviewQuery;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** CHAT-07 채팅 목록 배치 미리보기·미읽음 파생 계산을 검증한다. */
@SpringBootTest
class ChatRoomPreviewQueryServiceIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

	@Autowired
	private ChatRoomPreviewQueryService chatRoomPreviewQueryService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
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
		chatRoomIds.forEach(
			chatRoomId -> jdbcTemplate.update("delete from chat_room_read_states where chat_room_id = ?", chatRoomId));
		participationIds.forEach(participationRepository::deleteById);
		chatRoomIds.forEach(chatRoomId -> jdbcTemplate.update("delete from chat_rooms where id = ?", chatRoomId));
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from rooms where id = ?", roomId));
		userIds.forEach(userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void T1_메시지가_없는_채팅방은_미리보기와_미읽음이_null과_0이다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);

		Map<Long, ChatRoomPreviewQuery.ChatRoomPreview> previews = chatRoomPreviewQueryService
			.findPreviews(hostUserId, Set.of(room.getId()));

		ChatRoomPreviewQuery.ChatRoomPreview preview = previews
			.getOrDefault(room.getId(), ChatRoomPreviewQuery.ChatRoomPreview.EMPTY);
		assertNull(preview.lastMessagePreview());
		assertNull(preview.lastMessageAt());
		assertEquals(0, preview.unreadCount());
	}

	@Test
	void T2_다른_사용자의_메시지가_여러_건이어도_표시행은_하나이고_unreadCount는_정확한_건수다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId);
		joinActive(room, participantUserId);
		insertMessages(room.getId(), participantUserId, 4, "손님 메시지 ");

		Map<Long, ChatRoomPreviewQuery.ChatRoomPreview> previews = chatRoomPreviewQueryService
			.findPreviews(hostUserId, Set.of(room.getId()));

		ChatRoomPreviewQuery.ChatRoomPreview preview = previews.get(room.getId());
		assertEquals("손님 메시지 3", preview.lastMessagePreview());
		assertEquals(4, preview.unreadCount());
	}

	@Test
	void T3_본인이_보낸_메시지는_본인_unreadCount에서_제외된다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		insertMessages(room.getId(), hostUserId, 2, "내 메시지 ");

		Map<Long, ChatRoomPreviewQuery.ChatRoomPreview> previews = chatRoomPreviewQueryService
			.findPreviews(hostUserId, Set.of(room.getId()));

		ChatRoomPreviewQuery.ChatRoomPreview preview = previews
			.getOrDefault(room.getId(), ChatRoomPreviewQuery.ChatRoomPreview.EMPTY);
		assertEquals(0, preview.unreadCount());
		assertEquals("내 메시지 1", preview.lastMessagePreview());
	}

	@Test
	void T5_여러_방의_unreadCount와_마지막_메시지는_서로_섞이지_않는다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room roomA = createChatRoom(hostUserId);
		Room roomB = createChatRoom(hostUserId);
		joinActive(roomA, participantUserId);
		joinActive(roomB, participantUserId);
		insertMessages(roomA.getId(), participantUserId, 1, "방A ");
		insertMessages(roomB.getId(), participantUserId, 3, "방B ");

		Map<Long, ChatRoomPreviewQuery.ChatRoomPreview> previews = chatRoomPreviewQueryService
			.findPreviews(hostUserId, Set.of(roomA.getId(), roomB.getId()));

		assertEquals(1, previews.get(roomA.getId()).unreadCount());
		assertEquals("방A 0", previews.get(roomA.getId()).lastMessagePreview());
		assertEquals(3, previews.get(roomB.getId()).unreadCount());
		assertEquals("방B 2", previews.get(roomB.getId()).lastMessagePreview());
	}

	@Test
	void T6_같은_메시지_상태를_반복_조회해도_unreadCount는_항상_같은_값으로_수렴한다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId);
		joinActive(room, participantUserId);
		insertMessages(room.getId(), participantUserId, 5, "메시지 ");

		int first = chatRoomPreviewQueryService.findPreviews(hostUserId, Set.of(room.getId()))
			.get(room.getId()).unreadCount();
		int second = chatRoomPreviewQueryService.findPreviews(hostUserId, Set.of(room.getId()))
			.get(room.getId()).unreadCount();
		int third = chatRoomPreviewQueryService.findPreviews(hostUserId, Set.of(room.getId()))
			.get(room.getId()).unreadCount();

		assertEquals(5, first);
		assertEquals(first, second);
		assertEquals(second, third);
	}

	@Test
	void T8_CHAT_06_미구현으로_message_type_컬럼이_없고_현재_스키마의_모든_메시지는_본인_발신_제외_규칙만_적용된다() {
		List<String> columns = jdbcTemplate.queryForList(
			"select column_name from information_schema.columns where table_name = 'chat_messages'",
			String.class);
		assertFalse(
			columns.stream().anyMatch(column -> column.equalsIgnoreCase("message_type")),
			"CHAT-06이 아직 병합되지 않아 message_type 컬럼이 없어야 합니다.");

		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId);
		joinActive(room, participantUserId);
		insertMessages(room.getId(), hostUserId, 1, "내 메시지 ");
		insertMessages(room.getId(), participantUserId, 2, "손님 메시지 ");

		ChatRoomPreviewQuery.ChatRoomPreview preview = chatRoomPreviewQueryService
			.findPreviews(hostUserId, Set.of(room.getId()))
			.get(room.getId());

		assertEquals(2, preview.unreadCount());
		assertEquals("손님 메시지 1", preview.lastMessagePreview());
	}

	@Test
	void T9_30일_보존_만료로_커서보다_오래된_메시지가_물리_삭제돼도_미읽음_집계가_오류_없이_동작한다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId);
		joinActive(room, participantUserId);
		List<Long> messageIds = insertMessages(room.getId(), participantUserId, 3, "오래된 ");
		messageIds.forEach(chatMessageRepository::deleteById);

		Map<Long, ChatRoomPreviewQuery.ChatRoomPreview> previews = chatRoomPreviewQueryService
			.findPreviews(hostUserId, Set.of(room.getId()));
		ChatRoomPreviewQuery.ChatRoomPreview afterPurge = previews
			.getOrDefault(room.getId(), ChatRoomPreviewQuery.ChatRoomPreview.EMPTY);
		assertNull(afterPurge.lastMessagePreview());
		assertEquals(0, afterPurge.unreadCount());

		insertMessages(room.getId(), participantUserId, 1, "새 메시지 ");
		ChatRoomPreviewQuery.ChatRoomPreview afterNewMessage = chatRoomPreviewQueryService
			.findPreviews(hostUserId, Set.of(room.getId()))
			.get(room.getId());
		assertTrue(afterNewMessage.unreadCount() >= 1);
		assertEquals("새 메시지 0", afterNewMessage.lastMessagePreview());
	}

	private List<Long> insertMessages(long roomId, long senderUserId, int count, String contentPrefix) {
		long chatRoomInternalId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		List<Long> messageIds = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			ChatMessage saved = chatMessageRepository.save(
				ChatMessage.create(
					chatRoomInternalId, senderUserId, "preview-" + UUID.randomUUID(), contentPrefix + i,
					NOW.plusSeconds(i)));
			messageIds.add(saved.getId());
		}
		return messageIds;
	}

	private void joinActive(Room room, long userId) {
		Participation participation = participationRepository
			.saveAndFlush(Participation.createActive(room, userId, NOW));
		participationIds.add(participation.getId());
	}

	private long insertUser(String nickname) {
		String email = "chat-preview-" + UUID.randomUUID() + "@example.com";
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
				"미리보기 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대",
				4));
		roomIds.add(room.getId());
		ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		chatRoomIds.add(chatRoom.getId());
		return room;
	}
}
