package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.entity.ChatSystemEventKey;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.room.dto.RoomParticipationResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * #869 T2·T5 — 참가·재참가·취소 lifecycle과 gate off 상태의 기존 계약 유지를 실제 PostgreSQL로 재현한다.
 *
 * <p>T1~T6 전체가 PostgreSQL 통합 검증을 요구하는 확정 계약에 따라, H2 {@code src/test}의
 * {@code ChatSystemMessageWriterIntegrationTest}는 빠른 개발자 피드백용으로만 남기고 이 클래스가 정본 증거를
 * 소유한다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
@Import(ChatSystemMessageWriterLifecyclePostgresTest.FixedClockConfiguration.class)
class ChatSystemMessageWriterLifecyclePostgresTest extends SharedPostgresIntegrationSupport {

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
	private ChatMessageRepository chatMessageRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RecordingChatRealtimePublisher recordingChatRealtimePublisher;

	@Test
	void T3_참가_확정_커밋_후_실시간_전달_신호가_ChatRealtimePublisher까지_도달한다() {
		activateGateAt(NOW.minusSeconds(3600));
		long hostUserId = insertUser("realtime-host@example.com");
		long participantUserId = insertUser("realtime-member@example.com");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		Long chatRoomId = chatRoomIdOf(room.getId());
		recordingChatRealtimePublisher.clear();

		roomParticipationService.participate(participantUserId, room.getId());

		List<ChatMessage> systemMessages = orderedSystemMessages(chatRoomId);
		assertEquals(1, systemMessages.size());
		assertEventRow(systemMessages.get(0), ChatSystemEventKey.PARTICIPANT_ENTERED, participantUserId);
		assertEquals(
			List.of(MessageCommitted.messageCreated(room.getId(), systemMessages.get(0).getId())),
			recordingChatRealtimePublisher.events());
	}

	@Test
	void T2_방_생성_직후에는_안내가_없고_참가_재참가_취소_각각_정확히_한_건이며_비참가자_취소와_중복_참가는_안내를_남기지_않는다() {
		activateGateAt(NOW.minusSeconds(3600));
		long hostUserId = insertUser("lifecycle-host@example.com");
		long participantUserId = insertUser("lifecycle-member@example.com");
		long nonParticipantUserId = insertUser("lifecycle-outsider@example.com");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		Long chatRoomId = chatRoomIdOf(room.getId());

		assertEquals(0, systemMessageCount(chatRoomId), "방 생성·존재만으로는 안내를 남기지 않는다");

		assertThrows(
			BusinessException.class,
			() -> roomParticipationCancelService.cancelParticipation(nonParticipantUserId, room.getId()));
		assertEquals(0, systemMessageCount(chatRoomId), "참가 관계 없는 사용자의 취소는 안내를 남기지 않는다");

		roomParticipationService.participate(participantUserId, room.getId());
		assertThrows(
			BusinessException.class,
			() -> roomParticipationService.participate(participantUserId, room.getId()));
		assertEquals(
			1, systemMessageCount(chatRoomId), "이미 ACTIVE인 사용자의 중복 참가는 안내를 남기지 않는다");

		roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());
		roomParticipationService.participate(participantUserId, room.getId());

		List<ChatMessage> ordered = orderedSystemMessages(chatRoomId);
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
		long hostUserId = insertUser("gate-off-host@example.com");
		long participantUserId = insertUser("gate-off-member@example.com");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		Long chatRoomId = chatRoomIdOf(room.getId());

		RoomParticipationResponse joinResponse = roomParticipationService.participate(participantUserId, room.getId());
		RoomParticipationResponse cancelResponse = roomParticipationCancelService.cancelParticipation(
			participantUserId, room.getId());

		assertEquals(2, joinResponse.participantCount());
		assertEquals(ParticipationStatus.CANCELED, cancelResponse.participationStatus());
		assertEquals(0, systemMessageCount(chatRoomId));
	}

	private void assertEventRow(ChatMessage message, ChatSystemEventKey expectedKey, long expectedSubjectUserId) {
		assertEquals(expectedKey, message.getSystemEventKey());
		assertEquals(expectedSubjectUserId, message.getSubjectUserId());
	}

	private Long chatRoomIdOf(long roomId) {
		return chatRoomRepository.findByRoomId(roomId).orElseThrow().getId();
	}

	private int systemMessageCount(Long chatRoomId) {
		return orderedSystemMessages(chatRoomId).size();
	}

	private List<ChatMessage> orderedSystemMessages(Long chatRoomId) {
		return chatMessageRepository
			.findByChatRoomIdOrderByIdDesc(chatRoomId, Pageable.unpaged())
			.stream()
			.filter(message -> message.getSystemEventKey() != null)
			.sorted((a, b) -> a.getId().compareTo(b.getId()))
			.toList();
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
				"CHAT-06 lifecycle 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startAt,
				"홍대 장소",
				capacity));
		chatRoomRepository.saveAndFlush(ChatRoom.create(room.getId()));
		return room;
	}

	private long insertUser(String email) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', '참가자', current_timestamp, current_timestamp)",
			email);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		@Primary
		RecordingChatRealtimePublisher recordingChatRealtimePublisher() {
			return new RecordingChatRealtimePublisher();
		}
	}

	static class RecordingChatRealtimePublisher implements ChatRealtimePublisher {

		private final List<MessageCommitted> events = new CopyOnWriteArrayList<>();

		@Override
		public void publish(MessageCommitted event) {
			events.add(event);
		}

		List<MessageCommitted> events() {
			return List.copyOf(events);
		}

		void clear() {
			events.clear();
		}
	}
}
