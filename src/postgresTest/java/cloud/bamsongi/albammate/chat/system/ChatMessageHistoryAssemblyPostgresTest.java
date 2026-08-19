package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.dto.ChatMessagePageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.entity.ChatMessageType;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.entity.ChatSystemEventKey;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatMessageHistoryQueryService;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * #870 T1·T2 — 이력 조회가 SYSTEM 안내를 서버가 조립한 문장으로 반환하고, 닉네임 변경·프로필 미조회 fallback,
 * USER·SYSTEM이 섞인 페이지네이션을 실제 PostgreSQL로 재현한다.
 */
@SpringBootTest(classes = AlbamMateApplication.class)
@Import(ChatMessageHistoryAssemblyPostgresTest.FixedClockConfiguration.class)
class ChatMessageHistoryAssemblyPostgresTest extends SharedPostgresIntegrationSupport {

	private static final String GATE_NAME = "chat-system-message";
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private ChatMessageHistoryQueryService historyQueryService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void T1_이력이_SYSTEM_안내를_서버_조립_문장으로_반환하고_닉네임_변경을_반영한다() {
		activateGate();
		long hostUserId = insertUser("history-host@example.com", "방장");
		long participantUserId = insertUser("history-member@example.com", "최초닉네임");
		Room room = createRoom(hostUserId, 2);

		roomParticipationService.participate(participantUserId, room.getId());
		jdbcTemplate.update("update users set nickname = ? where id = ?", "바뀐닉네임", participantUserId);
		roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());

		ChatMessagePageResponse page = historyQueryService.history(hostUserId, room.getId(), null, 10);
		List<ChatMessageResponse> ordered = page.messages().reversed();

		assertEquals(2, ordered.size());
		ChatMessageResponse entered = ordered.get(0);
		assertEquals(ChatMessageType.SYSTEM, entered.messageType());
		assertEquals(ChatSystemEventKey.PARTICIPANT_ENTERED, entered.systemEvent());
		assertEquals("바뀐닉네임님이 입장했어요.", entered.content(), "과거 안내도 현재 닉네임으로 조립된다");
		assertEquals("바뀐닉네임", entered.subject().nickname());
		assertNull(entered.sender());
		assertNull(entered.clientMessageId());
		assertFalse(entered.isMine());

		ChatMessageResponse left = ordered.get(1);
		assertEquals(ChatSystemEventKey.PARTICIPANT_LEFT, left.systemEvent());
		assertEquals("바뀐닉네임님이 나갔어요.", left.content());
	}

	@Test
	void T1_대상_공개_프로필을_찾지_못해도_이력_조회가_실패하지_않고_고정_대체_표시명을_반환한다() {
		activateGate();
		long hostUserId = insertUser("history-fallback-host@example.com", "방장");
		Room room = createRoom(hostUserId, 2);
		long phantomSubjectUserId = insertPhantomSystemMessageWithMissingSubject(room.getId());

		ChatMessagePageResponse page = historyQueryService.history(hostUserId, room.getId(), null, 10);

		assertEquals(1, page.messages().size());
		ChatMessageResponse response = page.messages().getFirst();
		assertEquals(ChatMessageType.SYSTEM, response.messageType());
		assertEquals("알 수 없는 사용자님이 입장했어요.", response.content());
		assertEquals("알 수 없는 사용자", response.subject().nickname());
		assertNull(response.subject().profileImageUrl());
		assertTrue(phantomSubjectUserId > 0);
	}

	/**
	 * {@code fk_chat_messages_subject_user}는 {@code ON DELETE NO ACTION}이라 참가·채팅 이력이 있는 사용자를
	 * 삭제할 수 없다. 대상 공개 프로필 미조회 fallback은 실제 운영에서 사용자 삭제로는 재현할 수 없으므로, 세션
	 * 트리거를 잠시 끄고 존재하지 않는 사용자를 가리키는 SYSTEM 행을 직접 삽입해 그 상태를 재현한다.
	 */
	private long insertPhantomSystemMessageWithMissingSubject(long roomId) {
		Long chatRoomId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		long phantomSubjectUserId = jdbcTemplate.queryForObject("select coalesce(max(id), 0) + 1000 from users",
			Long.class);
		jdbcTemplate.execute("set session_replication_role = 'replica'");
		try {
			jdbcTemplate.update(
				"insert into chat_messages (chat_room_id, message_type, system_event_key, subject_user_id, "
					+ "created_at) values (?, 'SYSTEM', 'PARTICIPANT_ENTERED', ?, current_timestamp)",
				chatRoomId, phantomSubjectUserId);
		} finally {
			jdbcTemplate.execute("set session_replication_role = 'origin'");
		}
		return phantomSubjectUserId;
	}

	@Test
	void T2_사용자_메시지와_SYSTEM_안내가_섞인_이력에서_beforeMessageId_구간이_끊기지_않고_size가_합산된다() {
		activateGate();
		long hostUserId = insertUser("page-host@example.com", "방장");
		long participantUserId = insertUser("page-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 2);

		roomParticipationService.participate(participantUserId, room.getId());
		insertUserMessage(room.getId(), hostUserId, "page-client-1", "안녕하세요");
		roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());
		insertUserMessage(room.getId(), hostUserId, "page-client-2", "잘가요");

		ChatMessagePageResponse firstPage = historyQueryService.history(hostUserId, room.getId(), null, 2);
		assertEquals(2, firstPage.messages().size());
		assertTrue(firstPage.hasNext());

		ChatMessagePageResponse secondPage = historyQueryService.history(
			hostUserId, room.getId(), firstPage.nextBeforeMessageId(), 2);
		assertEquals(2, secondPage.messages().size());
		assertFalse(secondPage.hasNext());

		long totalDistinctMessageIds = java.util.stream.Stream
			.concat(firstPage.messages().stream(), secondPage.messages().stream())
			.map(ChatMessageResponse::messageId)
			.distinct()
			.count();
		assertEquals(4, totalDistinctMessageIds, "USER·SYSTEM을 합해 정확히 4건이 중복·누락 없이 나뉜다");
	}

	private void insertUserMessage(long roomId, long senderUserId, String clientMessageId, String content) {
		Long chatRoomId = chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
		jdbcTemplate.update(
			"insert into chat_messages (chat_room_id, sender_user_id, client_message_id, content, message_type, "
				+ "created_at) values (?, ?, ?, ?, 'USER', current_timestamp)",
			chatRoomId, senderUserId, clientMessageId, content);
	}

	private void activateGate() {
		jdbcTemplate.update(
			"update chat_system_message_activation set enabled_at = ?, updated_at = current_timestamp "
				+ "where gate_name = ?",
			Timestamp.from(NOW.minusSeconds(3600)), GATE_NAME);
	}

	private Room createRoom(long hostUserId, int capacity) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"CHAT-06 이력 조립 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대 장소",
				capacity));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}

	private long insertUser(String email, String nickname) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, current_timestamp, current_timestamp)",
			email, nickname);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
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
