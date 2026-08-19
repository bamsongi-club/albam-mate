package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.dto.ChatRoomReadStateResponse;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatRoomReadService;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/**
 * T4: CHAT_ROOM_READ_STATES의 GREATEST 기반 UPSERT native query가 실제 PostgreSQL에서
 * Flyway V32 스키마 위에 커서를 후퇴 없이 전진시키고 재시도에 안전한지 검증한다(ADR-0079, postgresRequirementReasons #2).
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class ChatRoomReadStatePostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_read_state_test");

	@Autowired
	private ChatRoomReadService chatRoomReadService;
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
	void T4_GREATEST_UPSERT는_실제_PostgreSQL에서_커서를_후퇴시키지_않고_재시도에_안전하다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		List<Long> messageIds = insertMessages(room.getId(), hostUserId, 5);
		long latestMessageId = messageIds.get(4);
		long midMessageId = messageIds.get(2);

		ChatRoomReadStateResponse first = chatRoomReadService.markRead(hostUserId, room.getId(), latestMessageId);
		assertEquals(latestMessageId, first.lastReadMessageId());

		ChatRoomReadStateResponse retryWithOlderId = chatRoomReadService.markRead(hostUserId, room.getId(),
			midMessageId);
		assertEquals(latestMessageId, retryWithOlderId.lastReadMessageId(), "GREATEST UPSERT는 커서를 후퇴시키지 않아야 합니다.");

		ChatRoomReadStateResponse retrySameId = chatRoomReadService.markRead(hostUserId, room.getId(), latestMessageId);
		assertEquals(latestMessageId, retrySameId.lastReadMessageId(), "같은 요청의 재시도는 안전해야 합니다.");

		long persistedCursor = jdbcTemplate.queryForObject(
			"select last_read_message_id from chat_room_read_states where user_id = ? and chat_room_id = ?",
			Long.class,
			hostUserId,
			chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getId());
		assertEquals(latestMessageId, persistedCursor);
	}

	private List<Long> insertMessages(long roomId, long senderUserId, int count) {
		long chatRoomInternalId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		return IntStream.range(0, count)
			.mapToObj(
				i -> chatMessageRepository.save(
					ChatMessage.create(
						chatRoomInternalId, senderUserId, "pg-read-" + UUID.randomUUID(), "메시지 " + i,
						NOW.plusSeconds(i)))
					.getId())
			.toList();
	}

	private long insertUser(String nickname) {
		String email = "chat-read-pg-" + UUID.randomUUID() + "@example.com";
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
				"PostgreSQL 읽음 처리 방",
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
