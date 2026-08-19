package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

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
import cloud.bamsongi.albammate.chat.entity.ChatMessageType;
import cloud.bamsongi.albammate.chat.entity.ChatSystemEventKey;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import jakarta.persistence.EntityManager;

/**
 * #869 T2·T5 — 참가·재참가·취소가 SYSTEM 안내 행 저장에 미치는 영향과 gate off 시 동작을 검증한다.
 *
 * <p>gate on 상태에서의 정확한 시각 경계 수렴(T6)과 CHECK 제약(T1)은 PostgreSQL 전용 검증이 필요해 별도
 * postgresTest에서 재현한다.
 */
@SpringBootTest
@Import(ChatSystemMessageWriterIntegrationTest.FixedClockConfiguration.class)
class ChatSystemMessageWriterIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
	private static final String GATE_NAME = "chat-system-message";

	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EntityManager entityManager;

	private final List<Long> roomIds = new java.util.ArrayList<>();
	private final List<Long> userIds = new java.util.ArrayList<>();

	/** 공유 H2 인스턴스 전역 gate와 이 테스트가 만든 방·사용자가 다른 테스트 클래스로 새는 것을 막는다. */
	@AfterEach
	void tearDown() {
		deactivateGate();
		for (Long roomId : roomIds) {
			jdbcTemplate.update("delete from chat_messages where chat_room_id in "
				+ "(select id from chat_rooms where room_id = ?)", roomId);
			jdbcTemplate.update("delete from chat_rooms where room_id = ?", roomId);
			jdbcTemplate.update("delete from notification_outbox_events where room_id = ?", roomId);
			jdbcTemplate.update("delete from notifications where room_id = ?", roomId);
			jdbcTemplate.update("delete from room_waitlists where room_id = ?", roomId);
			jdbcTemplate.update("delete from participations where room_id = ?", roomId);
			jdbcTemplate.update("delete from rooms where id = ?", roomId);
		}
		for (Long userId : userIds) {
			jdbcTemplate.update("delete from users where id = ?", userId);
		}
	}

	@Test
	void T2_참가와_재참가와_취소를_반복하면_사건마다_별도_SYSTEM_행이_생기고_중복_참가는_행을_남기지_않는다() {
		activateGateAt(NOW.minusSeconds(3600));
		long hostUserId = insertUser("t2-host@example.com", "방장");
		long participantUserId = insertUser("t2-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		Long chatRoomId = chatRoomIdOf(room.getId());

		roomParticipationService.participate(participantUserId, room.getId());
		clearPersistenceContext();
		assertThrows(
			BusinessException.class,
			() -> roomParticipationService.participate(participantUserId, room.getId()));
		clearPersistenceContext();
		roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());
		clearPersistenceContext();
		roomParticipationService.participate(participantUserId, room.getId());
		clearPersistenceContext();

		List<ChatMessage> systemMessages = chatMessageRepository.findByChatRoomIdOrderByIdDesc(
			chatRoomId, org.springframework.data.domain.Pageable.unpaged());
		List<ChatMessage> ordered = systemMessages.stream().sorted((a, b) -> a.getId().compareTo(b.getId())).toList();

		assertEquals(3, ordered.size());
		assertEventRow(ordered.get(0), ChatSystemEventKey.PARTICIPANT_ENTERED, participantUserId);
		assertEventRow(ordered.get(1), ChatSystemEventKey.PARTICIPANT_LEFT, participantUserId);
		assertEventRow(ordered.get(2), ChatSystemEventKey.PARTICIPANT_ENTERED, participantUserId);
		assertTrue(ordered.get(0).getId() < ordered.get(1).getId());
		assertTrue(ordered.get(1).getId() < ordered.get(2).getId());
	}

	@Test
	void T5_gate가_비활성이면_참가와_참가_취소는_기존_계약대로_성공하고_SYSTEM_행은_생기지_않는다() {
		deactivateGate();
		long hostUserId = insertUser("t5-host@example.com", "방장");
		long participantUserId = insertUser("t5-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		Long chatRoomId = chatRoomIdOf(room.getId());

		RoomParticipationResponse joinResponse = roomParticipationService.participate(participantUserId, room.getId());
		clearPersistenceContext();
		RoomParticipationResponse cancelResponse = roomParticipationCancelService.cancelParticipation(
			participantUserId, room.getId());
		clearPersistenceContext();

		assertEquals(2, joinResponse.participantCount());
		assertEquals(cloud.bamsongi.albammate.room.enums.ParticipationStatus.CANCELED,
			cancelResponse.participationStatus());
		List<ChatMessage> systemMessages = chatMessageRepository.findByChatRoomIdOrderByIdDesc(
			chatRoomId, org.springframework.data.domain.Pageable.unpaged());
		assertEquals(0, systemMessages.size());
	}

	private void assertEventRow(ChatMessage message, ChatSystemEventKey expectedKey, long expectedSubjectUserId) {
		assertEquals(ChatMessageType.SYSTEM, message.getMessageType());
		assertEquals(expectedKey, message.getSystemEventKey());
		assertEquals(expectedSubjectUserId, message.getSubjectUserId());
	}

	private Long chatRoomIdOf(long roomId) {
		return chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
	}

	private void activateGateAt(Instant enabledAt) {
		jdbcTemplate.update(
			"update chat_system_message_activation set enabled_at = ?, updated_at = current_timestamp "
				+ "where gate_name = ?",
			Timestamp.from(enabledAt), GATE_NAME);
	}

	private void deactivateGate() {
		jdbcTemplate.update(
			"update chat_system_message_activation set enabled_at = null, updated_at = current_timestamp "
				+ "where gate_name = ?",
			GATE_NAME);
	}

	private Room createRoom(long hostUserId, int capacity, Instant startAt) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"CHAT-06 gate 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startAt,
				"홍대 장소",
				capacity));
		chatRoomRepository.saveAndFlush(cloud.bamsongi.albammate.chat.entity.ChatRoom.create(room.getId()));
		roomIds.add(room.getId());
		return room;
	}

	private long insertUser(String email, String nickname) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z')",
			email,
			nickname);
		long userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		userIds.add(userId);
		return userId;
	}

	private void clearPersistenceContext() {
		entityManager.clear();
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
