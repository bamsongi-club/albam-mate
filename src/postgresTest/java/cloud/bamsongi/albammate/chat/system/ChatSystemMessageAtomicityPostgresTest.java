package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * #869 T3 — SYSTEM 안내 저장 실패가 참가 상태 전이·점유 인원과 함께 원자적으로 롤백되고, 참가 자체가 실패한 요청은
 * 안내를 저장하지 않음을 실제 PostgreSQL 트랜잭션으로 재현한다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
@Import(ChatSystemMessageAtomicityPostgresTest.FixedClockConfiguration.class)
class ChatSystemMessageAtomicityPostgresTest extends SharedPostgresIntegrationSupport {

	private static final String GATE_NAME = "chat-system-message";
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@MockitoBean
	private ChatMessageRepository chatMessageRepository;

	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void T3_안내_저장이_실패하면_참가_상태_전이와_점유_인원도_함께_롤백된다() {
		activateGate();
		long hostUserId = insertUser("atomicity-host@example.com");
		long participantUserId = insertUser("atomicity-member@example.com");
		Room room = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		when(chatMessageRepository.save(any(ChatMessage.class)))
			.thenThrow(new RuntimeException("system message save failure"));

		RuntimeException exception = assertThrows(
			RuntimeException.class, () -> roomParticipationService.participate(participantUserId, room.getId()));

		assertEquals("system message save failure", exception.getMessage());
		Room reloadedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(0, reloadedRoom.getActiveParticipantCount());
		assertTrue(
			participationRepository.findByRoomIdAndUserId(room.getId(), participantUserId).isEmpty());
	}

	@Test
	void T3_참가가_실패하면_안내가_저장되지_않는다() {
		activateGate();
		long hostUserId = insertUser("atomicity-canceled-host@example.com");
		long participantUserId = insertUser("atomicity-canceled-member@example.com");
		Room canceledRoom = createRoom(hostUserId, 2, NOW.plusSeconds(3600));
		jdbcTemplate.update(
			"update rooms set status = 'CANCELED' where id = ?", canceledRoom.getId());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> roomParticipationService.participate(participantUserId, canceledRoom.getId()));

		assertEquals(ErrorCode.ROOM_NOT_RECRUITING, exception.getErrorCode());
		verify(chatMessageRepository, never()).save(any(ChatMessage.class));
	}

	private void activateGate() {
		jdbcTemplate.update(
			"update chat_system_message_activation set enabled_at = ?, updated_at = current_timestamp "
				+ "where gate_name = ?",
			Timestamp.from(NOW.minusSeconds(3600)),
			GATE_NAME);
	}

	private Room createRoom(long hostUserId, int capacity, Instant startAt) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"CHAT-06 원자성 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startAt,
				"홍대 장소",
				capacity));
		chatRoomRepository.saveAndFlush(cloud.bamsongi.albammate.chat.entity.ChatRoom.create(room.getId()));
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
	}
}
