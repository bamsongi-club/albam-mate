package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/**
 * #870 T4 — 접근 판정·30일 보존이 사용자 메시지와 같고, SYSTEM 행도 별도 예외 없이 만료 묶음 물리 삭제 대상에
 * 포함됨을 실제 PostgreSQL로 재현한다. 보존 로직({@link ChatMessageRetentionCoordinator})은 #870에서 변경하지
 * 않으며 이 테스트는 기존 삭제 질의가 {@code message_type}으로 걸러내지 않는지만 확인한다.
 */
@SpringBootTest(properties = "app.chat.retention.enabled=false")
class ChatSystemMessageRetentionPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private ChatMessageRetentionCoordinator coordinator;

	@AfterEach
	void tearDown() {
		jdbcTemplate.update(
			"delete from chat_messages where chat_room_id in (select id from chat_rooms where room_id in "
				+ "(select id from rooms where host_user_id in "
				+ "(select id from users where email like 'sys-retention-%')))");
		jdbcTemplate.update(
			"delete from chat_rooms where room_id in (select id from rooms where host_user_id in "
				+ "(select id from users where email like 'sys-retention-%'))");
		jdbcTemplate.update(
			"delete from rooms where host_user_id in (select id from users where email like 'sys-retention-%')");
		jdbcTemplate.update("delete from users where email like 'sys-retention-%'");
	}

	@Test
	void T4_최종_상태_전환_30일_뒤_SYSTEM_행도_예외_없이_삭제되고_완료_시각을_기록한다() {
		long hostUserId = insertUser("sys-retention-host@example.com", "방장");
		long subjectUserId = insertUser("sys-retention-subject@example.com", "참가자");
		long roomId = insertRoom(hostUserId, "FINISHED");
		long chatRoomId = insertChatRoom(roomId, Instant.now().minusSeconds(31L * 24 * 60 * 60));
		insertUserMessage(chatRoomId, hostUserId, "sys-retention-client-1");
		insertSystemMessage(chatRoomId, subjectUserId, "PARTICIPANT_ENTERED");
		insertSystemMessage(chatRoomId, subjectUserId, "PARTICIPANT_LEFT");

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertEquals(1, summary.purgedRoomCount());
		assertEquals(3, summary.deletedMessageCount(), "USER 1건과 SYSTEM 2건이 함께 삭제된다");
		assertEquals(
			0, jdbcTemplate.queryForObject("select count(*) from chat_messages where chat_room_id = ?", Integer.class,
				chatRoomId));
		assertNotNull(
			jdbcTemplate.queryForObject("select messages_purged_at from chat_rooms where id = ?", Instant.class,
				chatRoomId));
	}

	private long insertUser(String email, String nickname) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class, email, nickname);
	}

	private long insertRoom(long hostUserId, String status) {
		return jdbcTemplate.queryForObject(
			"""
				insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity,
				active_participant_count, start_at, place, status, created_at, updated_at)
				values (?, 'PERSON_FOCUSED', 'sys-retention', 'ALL_LEVELS', false, '홍대', 1, 0, current_timestamp,
				'테스트', ?, current_timestamp, current_timestamp) returning id
				""",
			Long.class, hostUserId, status);
	}

	private long insertChatRoom(long roomId, Instant purgeAfter) {
		return jdbcTemplate.queryForObject(
			"insert into chat_rooms (room_id, purge_after, messages_purged_at, created_at, updated_at) "
				+ "values (?, ?, null, current_timestamp, current_timestamp) returning id",
			Long.class, roomId, Timestamp.from(purgeAfter));
	}

	private void insertUserMessage(long chatRoomId, long senderUserId, String clientMessageId) {
		jdbcTemplate.update(
			"insert into chat_messages (chat_room_id, sender_user_id, client_message_id, content, message_type, "
				+ "created_at) values (?, ?, ?, 'message', 'USER', current_timestamp)",
			chatRoomId, senderUserId, clientMessageId);
	}

	private void insertSystemMessage(long chatRoomId, long subjectUserId, String systemEventKey) {
		jdbcTemplate.update(
			"insert into chat_messages (chat_room_id, message_type, system_event_key, subject_user_id, created_at) "
				+ "values (?, 'SYSTEM', ?, ?, current_timestamp)",
			chatRoomId, systemEventKey, subjectUserId);
	}
}
