package cloud.bamsongi.albammate.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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

import cloud.bamsongi.albammate.chat.contract.ChatRoomPreviewQuery;
import cloud.bamsongi.albammate.chat.dto.ChatRoomReadStateResponse;
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

/** CHAT-07 읽음 처리 API(POST /api/rooms/{roomId}/chat/read)의 커서 전진·접근 판정을 검증한다. */
@SpringBootTest
@Import(ChatRoomReadServiceIntegrationTest.TestClockConfiguration.class)
class ChatRoomReadServiceIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

	@Autowired
	private ChatRoomReadService chatRoomReadService;
	@Autowired
	private TestClock testClock;
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
	void T4_읽음_처리_성공_후_unreadCount가_0으로_수렴하고_커서는_후퇴하지_않으며_재시도에_안전하다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId);
		joinActive(room, participantUserId);
		List<Long> messageIds = insertMessages(room.getId(), participantUserId, 5);
		long latestMessageId = messageIds.get(messageIds.size() - 1);
		long midMessageId = messageIds.get(2);

		ChatRoomReadStateResponse first = chatRoomReadService.markRead(hostUserId, room.getId(), latestMessageId);
		assertEquals(latestMessageId, first.lastReadMessageId());
		int unreadAfterFirst = chatRoomPreviewQueryService.findPreviews(hostUserId, Set.of(room.getId()))
			.get(room.getId()).unreadCount();
		assertEquals(0, unreadAfterFirst);

		testClock.advanceTo(NOW.plusSeconds(60));
		ChatRoomReadStateResponse retryWithOlderId = chatRoomReadService.markRead(hostUserId, room.getId(),
			midMessageId);
		assertEquals(latestMessageId, retryWithOlderId.lastReadMessageId(), "커서는 이전 값보다 후퇴하지 않습니다.");
		assertEquals(
			first.updatedAt(), retryWithOlderId.updatedAt(),
			"커서가 전진하지 않으면 시계가 흘러도 갱신 시각은 그대로여야 합니다.");

		ChatRoomReadStateResponse retrySameId = chatRoomReadService.markRead(hostUserId, room.getId(), latestMessageId);
		assertEquals(latestMessageId, retrySameId.lastReadMessageId(), "같은 요청의 재시도는 안전해야 합니다.");
	}

	@Test
	void T4_존재하지_않는_메시지_ID로_읽음_처리하면_VALIDATION_ERROR다() {
		long hostUserId = insertUser("host");
		Room room = createChatRoom(hostUserId);
		List<Long> messageIds = insertMessages(room.getId(), hostUserId, 1);
		long nonExistentMessageId = messageIds.get(0) + 999;

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> chatRoomReadService.markRead(hostUserId, room.getId(), nonExistentMessageId));

		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
	}

	@Test
	void T7_주최자도_현재_ACTIVE_참가자도_아닌_사용자는_읽음_처리할_수_없다() {
		long hostUserId = insertUser("host");
		long outsiderUserId = insertUser("outsider");
		Room room = createChatRoom(hostUserId);
		List<Long> messageIds = insertMessages(room.getId(), hostUserId, 1);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> chatRoomReadService.markRead(outsiderUserId, room.getId(), messageIds.get(0)));

		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	@Test
	void T7_참가_취소한_사용자는_읽음_처리할_수_없다() {
		long hostUserId = insertUser("host");
		long canceledParticipantUserId = insertUser("canceled");
		Room room = createChatRoom(hostUserId);
		Participation participation = participationRepository
			.saveAndFlush(Participation.createActive(room, canceledParticipantUserId, NOW));
		participationIds.add(participation.getId());
		participation.cancel(NOW.plusSeconds(1));
		participationRepository.saveAndFlush(participation);
		List<Long> messageIds = insertMessages(room.getId(), hostUserId, 1);

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> chatRoomReadService.markRead(canceledParticipantUserId, room.getId(), messageIds.get(0)));

		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	@Test
	void T9_보존_만료로_커서가_가리키던_메시지가_삭제돼도_새_메시지_읽음_처리는_오류_없이_동작한다() {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createChatRoom(hostUserId);
		joinActive(room, participantUserId);
		List<Long> oldMessageIds = insertMessages(room.getId(), participantUserId, 3);
		chatRoomReadService.markRead(hostUserId, room.getId(), oldMessageIds.get(2));
		oldMessageIds.forEach(chatMessageRepository::deleteById);
		List<Long> newMessageIds = insertMessages(room.getId(), participantUserId, 1);

		ChatRoomReadStateResponse response = chatRoomReadService
			.markRead(hostUserId, room.getId(), newMessageIds.get(0));

		assertEquals(newMessageIds.get(0), response.lastReadMessageId());
		long persistedCursor = jdbcTemplate.queryForObject(
			"select last_read_message_id from chat_room_read_states where user_id = ? and chat_room_id = ?",
			Long.class,
			hostUserId,
			chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getId());
		assertEquals(newMessageIds.get(0), persistedCursor, "커서가 실제로 DB에 전진 저장돼야 합니다.");
		int unreadCount = chatRoomPreviewQueryService.findPreviews(hostUserId, Set.of(room.getId()))
			.getOrDefault(room.getId(), ChatRoomPreviewQuery.ChatRoomPreview.EMPTY)
			.unreadCount();
		assertEquals(0, unreadCount);
	}

	private List<Long> insertMessages(long roomId, long senderUserId, int count) {
		long chatRoomInternalId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		List<Long> messageIds = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			ChatMessage saved = chatMessageRepository.save(
				ChatMessage.create(
					chatRoomInternalId, senderUserId, "read-" + UUID.randomUUID(), "메시지 " + i, NOW.plusSeconds(i)));
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
		String email = "chat-read-" + UUID.randomUUID() + "@example.com";
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
				"읽음 처리 방",
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

	/** 재시도 사이 시계를 실제로 흘려보내 커서 미전진 시 updatedAt이 그대로인지 구분할 수 있게 하는 테스트 전용 시계다. */
	static class TestClock extends Clock {

		private Instant current = NOW;

		void advanceTo(Instant instant) {
			this.current = instant;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return current;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class TestClockConfiguration {

		@Bean
		@Primary
		TestClock testClock() {
			return new TestClock();
		}
	}
}
