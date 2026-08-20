package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
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

import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatRoomReadService;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

/** CHAT-07 상단 채팅 아이콘 배지용 미읽음 방 개수(GET /api/users/me/chat/unread-summary)를 검증한다. */
@SpringBootTest
@Import(ChatUnreadSummaryQueryServiceTest.FixedClockConfiguration.class)
class ChatUnreadSummaryQueryServiceTest {

	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");
	private static final Instant RECRUITING_START = NOW.plusSeconds(3600);

	@Autowired
	private ChatUnreadSummaryQueryService chatUnreadSummaryQueryService;
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

	private final List<Long> userIds = new ArrayList<>();
	private final List<Long> roomIds = new ArrayList<>();
	private final List<Long> chatRoomIds = new ArrayList<>();

	@AfterEach
	void tearDown() {
		chatRoomIds
			.forEach(chatRoomId -> jdbcTemplate.update("delete from chat_messages where chat_room_id = ?", chatRoomId));
		chatRoomIds.forEach(
			chatRoomId -> jdbcTemplate.update("delete from chat_room_read_states where chat_room_id = ?", chatRoomId));
		chatRoomIds.forEach(chatRoomId -> jdbcTemplate.update("delete from chat_rooms where id = ?", chatRoomId));
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from rooms where id = ?", roomId));
		userIds.forEach(userId -> jdbcTemplate.update("delete from users where id = ?", userId));
	}

	@Test
	void T1_미읽음_메시지가_있는_방이_있으면_정확한_방_개수를_반환한다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");

		// 미읽음 메시지가 있는 chatAvailable 방
		Room unreadRoom = createChatRoom(hostUserId, RECRUITING_START);
		insertMessages(unreadRoom.getId(), participantUserId, 2);

		// 메시지가 없는 chatAvailable 방(미읽음 아님)
		createChatRoom(hostUserId, RECRUITING_START);

		// CANCELED라 chatAvailable=false인 방에 미읽음 메시지가 있어도 제외된다
		Room canceledRoom = createChatRoom(hostUserId, RECRUITING_START);
		insertMessages(canceledRoom.getId(), participantUserId, 1);
		canceledRoom.cancel();
		roomRepository.saveAndFlush(canceledRoom);

		int unreadRoomCount = chatUnreadSummaryQueryService.countUnreadRooms(hostUserId);

		assertEquals(1, unreadRoomCount);
	}

	@Test
	void T2_미읽음_방이_0개면_unreadRoomCount는_0이다() {
		long hostUserId = insertUser("host");
		createChatRoom(hostUserId, RECRUITING_START);
		createChatRoom(hostUserId, RECRUITING_START);

		int unreadRoomCount = chatUnreadSummaryQueryService.countUnreadRooms(hostUserId);

		assertEquals(0, unreadRoomCount);
	}

	@Test
	void T3_읽음_처리_성공_후_재조회하면_그_방이_unreadRoomCount에서_제외된다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId, RECRUITING_START);
		List<Long> messageIds = insertMessages(room.getId(), participantUserId, 3);

		assertEquals(1, chatUnreadSummaryQueryService.countUnreadRooms(hostUserId));

		chatRoomReadService.markRead(hostUserId, room.getId(), messageIds.get(messageIds.size() - 1));

		assertEquals(0, chatUnreadSummaryQueryService.countUnreadRooms(hostUserId));
	}

	private List<Long> insertMessages(long roomId, long senderUserId, int count) {
		long chatRoomInternalId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		List<Long> messageIds = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			ChatMessage saved = chatMessageRepository.save(
				ChatMessage.create(
					chatRoomInternalId, senderUserId, "unread-summary-" + UUID.randomUUID(), "메시지 " + i,
					NOW.plusSeconds(i)));
			messageIds.add(saved.getId());
		}
		return messageIds;
	}

	private long insertUser(String nickname) {
		String email = "chat-unread-summary-" + UUID.randomUUID() + "@example.com";
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

	private Room createChatRoom(long hostUserId, Instant startAt) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"미읽음 요약 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startAt,
				"홍대",
				4));
		roomIds.add(room.getId());
		ChatRoom chatRoom = chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		chatRoomIds.add(chatRoom.getId());
		return room;
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
