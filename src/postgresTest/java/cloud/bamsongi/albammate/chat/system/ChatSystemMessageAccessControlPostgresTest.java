package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatMessageHistoryQueryService;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;

/**
 * #870 T4 — SYSTEM 안내가 섞인 방에서도 참가 취소자·비참가자와 CANCELED·FINISHED 방의 접근이 기존 계약대로
 * 거절됨을 실제 PostgreSQL로 재현한다. 접근 판정은 room.contract.ChatAccessGuard를 그대로 재사용하며 이
 * 이슈에서 새 판정 로직을 추가하지 않는다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
@Import(ChatSystemMessageAccessControlPostgresTest.FixedClockConfiguration.class)
class ChatSystemMessageAccessControlPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String GATE_NAME = "chat-system-message";
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_system_access_test");

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
	void T4_참가_취소자와_비참가자는_SYSTEM_안내가_있는_방의_이력_조회도_거절된다() {
		activateGate();
		long hostUserId = insertUser("access-host@example.com", "방장");
		long canceledUserId = insertUser("access-canceled@example.com", "취소자");
		long outsiderUserId = insertUser("access-outsider@example.com", "비참가자");
		Room room = createRoom(hostUserId, 3);

		roomParticipationService.participate(canceledUserId, room.getId());
		roomParticipationCancelService.cancelParticipation(canceledUserId, room.getId());

		assertForbidden(() -> historyQueryService.history(canceledUserId, room.getId(), null, 10));
		assertForbidden(() -> historyQueryService.history(outsiderUserId, room.getId(), null, 10));

		// 호스트는 여전히 조회할 수 있고 방금 만든 SYSTEM 안내가 보인다.
		assertEquals(2, historyQueryService.history(hostUserId, room.getId(), null, 10).messages().size());
	}

	@Test
	void T4_CANCELED_방의_SYSTEM_안내_이력_조회는_참가자였던_사용자에게도_거절된다() {
		activateGate();
		long hostUserId = insertUser("canceled-room-host@example.com", "방장");
		long participantUserId = insertUser("canceled-room-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 3);
		roomParticipationService.participate(participantUserId, room.getId());
		jdbcTemplate.update("update rooms set status = 'CANCELED' where id = ?", room.getId());

		assertForbidden(() -> historyQueryService.history(participantUserId, room.getId(), null, 10));
		assertForbidden(() -> historyQueryService.history(hostUserId, room.getId(), null, 10));
	}

	private void assertForbidden(org.junit.jupiter.api.function.Executable operation) {
		BusinessException exception = assertThrows(BusinessException.class, operation);
		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
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
				"CHAT-06 접근제어 테스트 방",
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
