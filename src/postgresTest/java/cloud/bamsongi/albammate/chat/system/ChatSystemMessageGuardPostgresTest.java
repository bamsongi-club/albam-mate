package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;
import cloud.bamsongi.albammate.chat.dto.ChatMessagePageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatMessageType;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.chat.service.ChatMessageCommandService;
import cloud.bamsongi.albammate.chat.service.ChatMessageHistoryQueryService;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;

/**
 * #870 T5·T6·T7 — 안내 문장·닉네임·사용자 ID가 로그에 남지 않고, 전송 API로는 SYSTEM 행을 만들 수 없으며, 안내
 * 저장이 전송 제한 quota를 소비하지 않음을 실제 PostgreSQL로 재현한다.
 */
@Testcontainers
@SpringBootTest(classes = cloud.bamsongi.albammate.AlbamMateApplication.class)
@Import(ChatSystemMessageGuardPostgresTest.FixedClockConfiguration.class)
class ChatSystemMessageGuardPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String GATE_NAME = "chat-system-message";
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_system_guard_test");

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
	private ChatMessageCommandService chatMessageCommandService;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private ChatMessageRateLimiter chatMessageRateLimiter;

	@Test
	void T5_안내_저장과_조회_로그에_문장_닉네임_사용자_ID가_남지_않는다() {
		activateGate();
		long hostUserId = insertUser("guard-privacy-host@example.com", "방장");
		long participantUserId = insertUser("guard-privacy-member@example.com", "은밀한닉네임");
		Room room = createRoom(hostUserId, 2);
		ListAppender<ILoggingEvent> appender = attachRootLogAppender();

		try {
			roomParticipationService.participate(participantUserId, room.getId());
			roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());
			historyQueryService.history(hostUserId, room.getId(), null, 10);

			for (ILoggingEvent event : appender.list) {
				String formatted = event.getFormattedMessage();
				assertFalse(formatted.contains("은밀한닉네임"), "닉네임이 로그에 남으면 안 된다: " + formatted);
				assertFalse(formatted.contains("입장했어요"), "안내 문장이 로그에 남으면 안 된다: " + formatted);
				assertFalse(formatted.contains("나갔어요"), "안내 문장이 로그에 남으면 안 된다: " + formatted);
				assertFalse(
					formatted.contains(String.valueOf(participantUserId)), "대상 사용자 ID가 로그에 남으면 안 된다: " + formatted);
			}
		} finally {
			detachRootLogAppender(appender);
		}
	}

	@Test
	void T6_전송_API는_요청_DTO에_종류_필드가_없어_SYSTEM_행을_만들_수_없다() {
		activateGate();
		long hostUserId = insertUser("guard-forge-host@example.com", "방장");
		Room room = createRoom(hostUserId, 2);

		ChatMessageSendRequest request = new ChatMessageSendRequest("guard-forge-client-1", "일반 메시지");
		ChatMessageResponse response = chatMessageCommandService.send(hostUserId, room.getId(), request).message();

		assertEquals(ChatMessageType.USER, response.messageType());
		String storedType = jdbcTemplate.queryForObject(
			"select message_type from chat_messages where id = ?", String.class, response.messageId());
		assertEquals("USER", storedType);
	}

	@Test
	void T7_SYSTEM_안내_저장은_전송_제한_quota를_소비하지_않는다() {
		activateGate();
		long hostUserId = insertUser("guard-rate-host@example.com", "방장");
		long participantUserId = insertUser("guard-rate-member@example.com", "참가자");
		Room room = createRoom(hostUserId, 2);

		when(chatMessageRateLimiter.reserve(anyLong(), anyLong()))
			.thenThrow(new AssertionError("SYSTEM 안내 저장은 rate limiter를 호출하면 안 된다"));

		roomParticipationService.participate(participantUserId, room.getId());
		roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());

		verifyNoInteractions(chatMessageRateLimiter);
		ChatMessagePageResponse page = historyQueryService.history(hostUserId, room.getId(), null, 10);
		assertEquals(2, page.messages().size());
	}

	private ListAppender<ILoggingEvent> attachRootLogAppender() {
		Logger logger = (Logger)LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		((ch.qos.logback.classic.Logger)logger).addAppender(appender);
		return appender;
	}

	private void detachRootLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
		((ch.qos.logback.classic.Logger)logger).detachAppender(appender);
		appender.stop();
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
				"CHAT-06 가드 테스트 방",
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
