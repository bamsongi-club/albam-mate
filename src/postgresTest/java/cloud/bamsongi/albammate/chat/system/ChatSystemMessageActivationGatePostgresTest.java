package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;

/**
 * #869 T6 — gate 판정이 {@code enabled_at} 전후 사건의 안내 유무를 실제 PostgreSQL {@code clock_timestamp()}로
 * 가르며, 애플리케이션 {@code Clock}과 무관함을 재현한다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
@Import(ChatSystemMessageActivationGatePostgresTest.WrongClockConfiguration.class)
class ChatSystemMessageActivationGatePostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String GATE_NAME = "chat-system-message";
	private static final Instant ROOM_START_AT = Instant.parse("2026-07-28T01:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_system_message_gate_test");

	@Autowired
	private ChatSystemMessageActivationGateRepository gateRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ChatRoomRepository chatRoomRepository;
	@Autowired
	private ChatMessageRepository chatMessageRepository;

	@Test
	void T6_행이_없거나_enabled_at이_비어있으면_비활성으로_판정한다() {
		setEnabledAt(null);

		Optional<Boolean> active = gateRepository.isActiveNow(GATE_NAME);

		assertTrue(active.isEmpty());
	}

	@Test
	void T6_enabled_at이_DB_현재_시각보다_과거면_애플리케이션_Clock이_틀려도_활성으로_판정한다() {
		setEnabledAtRelativeToDbNow("-2 seconds");

		Optional<Boolean> active = gateRepository.isActiveNow(GATE_NAME);

		assertTrue(active.orElse(false));
	}

	@Test
	void T6_enabled_at이_DB_현재_시각보다_미래면_애플리케이션_Clock이_틀려도_비활성으로_판정한다() {
		setEnabledAtRelativeToDbNow("+1 hour");

		Optional<Boolean> active = gateRepository.isActiveNow(GATE_NAME);

		assertFalse(active.orElse(true));
	}

	@Test
	void T6_활성화_전후_SYSTEM_row_생성이_갈리고_재비활성화는_신규_안내만_멈추고_기존_row는_보존한다() {
		setEnabledAt(null);
		long hostUserId = insertUser("gate-lifecycle-host@example.com");
		Room room = createRoom(hostUserId);
		Long chatRoomId = chatRoomRepository.findByRoomId(room.getId()).orElseThrow().getId();
		long beforeActivationUserId = insertUser("gate-lifecycle-before@example.com");

		roomParticipationService.participate(beforeActivationUserId, room.getId());
		assertEquals(0, systemMessageCount(chatRoomId), "활성화 전 참가는 SYSTEM 행을 만들지 않는다");

		setEnabledAtRelativeToDbNow("-2 seconds");
		long afterActivationUserId = insertUser("gate-lifecycle-after@example.com");
		roomParticipationService.participate(afterActivationUserId, room.getId());
		assertEquals(1, systemMessageCount(chatRoomId), "활성화 이후 참가는 SYSTEM 행을 정확히 한 건 만든다");
		long enabledRowId = onlySystemMessage(chatRoomId).getId();

		setEnabledAt(null);
		long afterDeactivationUserId = insertUser("gate-lifecycle-deactivated@example.com");
		roomParticipationService.participate(afterDeactivationUserId, room.getId());
		assertEquals(
			1, systemMessageCount(chatRoomId), "재비활성화 이후 참가는 새 SYSTEM 행을 만들지 않는다");
		assertEquals(
			enabledRowId, onlySystemMessage(chatRoomId).getId(),
			"활성화 구간에서 만든 기존 행은 재비활성화 이후에도 지워지지 않는다");
	}

	private Room createRoom(long hostUserId) {
		Room room = roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"CHAT-06 gate lifecycle 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				ROOM_START_AT,
				"홍대 장소",
				4));
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

	private int systemMessageCount(Long chatRoomId) {
		return systemMessages(chatRoomId).size();
	}

	private ChatMessage onlySystemMessage(Long chatRoomId) {
		List<ChatMessage> messages = systemMessages(chatRoomId);
		assertEquals(1, messages.size());
		return messages.get(0);
	}

	private List<ChatMessage> systemMessages(Long chatRoomId) {
		return chatMessageRepository
			.findByChatRoomIdOrderByIdDesc(chatRoomId, Pageable.unpaged())
			.stream()
			.filter(message -> message.getSystemEventKey() != null)
			.toList();
	}

	private void setEnabledAt(Instant enabledAt) {
		jdbcTemplate.update(
			"update chat_system_message_activation set enabled_at = ?, updated_at = current_timestamp "
				+ "where gate_name = ?",
			enabledAt == null ? null : java.sql.Timestamp.from(enabledAt),
			GATE_NAME);
	}

	private void setEnabledAtRelativeToDbNow(String interval) {
		jdbcTemplate.update(
			"update chat_system_message_activation "
				+ "set enabled_at = clock_timestamp() + interval '" + interval + "', updated_at = current_timestamp "
				+ "where gate_name = ?",
			GATE_NAME);
	}

	/** gate 판정이 애플리케이션 Clock을 전혀 참조하지 않음을 증명하기 위해 일부러 틀린 시각을 고정한다. */
	@TestConfiguration(proxyBeanMethods = false)
	static class WrongClockConfiguration {

		@Bean
		@Primary
		Clock wrongClock() {
			return Clock.fixed(Instant.parse("2000-01-01T00:00:00Z"), ZoneOffset.UTC);
		}
	}
}
