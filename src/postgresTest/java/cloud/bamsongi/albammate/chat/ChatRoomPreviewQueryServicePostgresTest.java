package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.contract.ChatRoomPreviewQuery;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatRoomPreviewQueryService;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * T1·T2: {@code ChatRoomLastMessageRow}가 실제 PostgreSQL JDBC 드라이버가 반환하는 {@code created_at} 값을
 * 예외·정밀도 손실 없이 매핑하는지 검증한다(issue #881,
 * https://github.com/bamsongi-club/albam-mate/issues/881#issuecomment-5341884487). 실제 HTTP 회귀
 * 경로(참가자 인증, {@code GET /api/users/me/rooms?role=joined}, unreadCount)는
 * {@code ChatRoomPreviewHttpPostgresTest}가 검증한다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class ChatRoomPreviewQueryServicePostgresTest extends SharedPostgresIntegrationSupport {

	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

	@Autowired
	private ChatRoomPreviewQueryService chatRoomPreviewQueryService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table chat_room_read_states, chat_messages, chat_rooms, rooms, users restart identity cascade");
	}

	@Test
	void T1_참가_중인_방에_실제_메시지가_있으면_실제_PostgreSQL에서_미리보기_시각_미읽음이_정상_반환된다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		insertMessages(room.getId(), hostUserId, 3, "메시지 ");

		Map<Long, ChatRoomPreviewQuery.ChatRoomPreview> previews = chatRoomPreviewQueryService
			.findPreviews(hostUserId, Set.of(room.getId()));

		ChatRoomPreviewQuery.ChatRoomPreview preview = previews.get(room.getId());
		assertEquals("메시지 2", preview.lastMessagePreview());
		assertEquals(NOW.plusSeconds(2), preview.lastMessageAt());
		assertEquals(0, preview.unreadCount());
	}

	@Test
	void T1_저장된_메시지_시각의_마이크로초_정밀도가_lastMessageAt에_그대로_보존된다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		Instant microPrecisionCreatedAt = NOW.plusNanos(123_456_000L);
		insertMessage(room.getId(), hostUserId, "마이크로초 메시지", microPrecisionCreatedAt);

		Map<Long, ChatRoomPreviewQuery.ChatRoomPreview> previews = chatRoomPreviewQueryService
			.findPreviews(hostUserId, Set.of(room.getId()));

		ChatRoomPreviewQuery.ChatRoomPreview preview = previews.get(room.getId());
		assertEquals(microPrecisionCreatedAt, preview.lastMessageAt());
	}

	@Test
	void T2_메시지가_없는_방은_실제_PostgreSQL에서도_빈_상태로_정상_응답한다() {
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

	private void insertMessages(long roomId, long senderUserId, int count, String contentPrefix) {
		long chatRoomInternalId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		for (int i = 0; i < count; i++) {
			chatMessageRepository.save(
				ChatMessage.create(
					chatRoomInternalId, senderUserId, "preview-pg-" + UUID.randomUUID(), contentPrefix + i,
					NOW.plusSeconds(i)));
		}
	}

	private void insertMessage(long roomId, long senderUserId, String content, Instant createdAt) {
		long chatRoomInternalId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		chatMessageRepository.save(
			ChatMessage.create(
				chatRoomInternalId, senderUserId, "preview-pg-" + UUID.randomUUID(), content, createdAt));
	}

	private long insertUser(String nickname) {
		String email = "chat-preview-pg-" + UUID.randomUUID() + "@example.com";
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			email,
			nickname);
	}

	private Room createChatRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"PostgreSQL 미리보기 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대",
				4));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}
}
