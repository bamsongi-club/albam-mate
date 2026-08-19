package cloud.bamsongi.albammate.chat.system;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;

/**
 * #869 T1 — 전진 migration 뒤 기존 사용자 메시지 저장·조회가 회귀하지 않고, {@code ck_chat_messages_kind}가
 * 잘못된 종류 조합과 허용값이 아닌 {@code system_event_key}를 각각 독립적으로 거절함을 PostgreSQL로 재현한다.
 *
 * <p>V9 기준 기존 USER 메시지 저장·FK·유일 제약 회귀는 {@link cloud.bamsongi.albammate.chat.ChatMessageSchemaPostgresTest}가
 * 이미 최신 migration까지 재현하므로 여기서는 V33이 더한 계약만 다룬다.
 */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class ChatSystemMessageSchemaPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String GENERAL_MIGRATION_LOCATION = "classpath:db/migration";
	private static final String POSTGRES_MIGRATION_LOCATION = "classpath:db/vendor-migration/postgresql";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_system_message_schema_test");

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void T1_message_type를_지정하지_않은_INSERT는_default_USER로_저장되고_기존_사용자_메시지_저장_조회는_회귀하지_않는다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName);
			long hostUserId = insertUser(schemaName, "legacy-host@example.com");
			long chatRoomId = insertChatRoom(schemaName, hostUserId);

			// V9 시절 컬럼만 지정하는 구버전 INSERT를 흉내낸다: message_type을 지정하지 않는다.
			long legacyMessageId = jdbcTemplate.queryForObject(
				"insert into " + table(schemaName, "chat_messages")
					+ " (chat_room_id, sender_user_id, client_message_id, content, created_at) "
					+ "values (?, ?, ?, ?, current_timestamp) returning id",
				Long.class,
				chatRoomId,
				hostUserId,
				"legacy-client-message-1",
				"구버전 클라이언트 메시지");

			String messageType = jdbcTemplate.queryForObject(
				"select message_type from " + table(schemaName, "chat_messages") + " where id = ?",
				String.class,
				legacyMessageId);
			assertEquals("USER", messageType);
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void T1_USER와_SYSTEM_각각의_유효한_행은_저장되고_잘못된_종류_조합은_ck_chat_messages_kind로_거절된다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName);
			long hostUserId = insertUser(schemaName, "kind-host@example.com");
			long subjectUserId = insertUser(schemaName, "kind-subject@example.com");
			long chatRoomId = insertChatRoom(schemaName, hostUserId);

			long userMessageId = insertUserMessage(schemaName, chatRoomId, hostUserId, "kind-client-1", "안녕하세요");
			long systemMessageId = insertSystemMessage(
				schemaName, chatRoomId, subjectUserId, "PARTICIPANT_ENTERED");
			assertTrue(userMessageId > 0);
			assertTrue(systemMessageId > 0);

			assertKindViolation(() -> jdbcTemplate.update(
				"insert into " + table(schemaName, "chat_messages")
					+ " (chat_room_id, sender_user_id, client_message_id, content, message_type, "
					+ "system_event_key, subject_user_id, created_at) "
					+ "values (?, ?, ?, ?, 'USER', 'PARTICIPANT_ENTERED', ?, current_timestamp)",
				chatRoomId, hostUserId, "kind-client-2", "잘못된 USER 조합", subjectUserId));
			assertKindViolation(() -> jdbcTemplate.update(
				"insert into " + table(schemaName, "chat_messages")
					+ " (chat_room_id, sender_user_id, message_type, system_event_key, subject_user_id, "
					+ "created_at) "
					+ "values (?, ?, 'SYSTEM', 'PARTICIPANT_ENTERED', ?, current_timestamp)",
				chatRoomId, hostUserId, subjectUserId));
			assertKindViolation(() -> insertSystemMessage(schemaName, chatRoomId, subjectUserId, null));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void T1_허용값이_아닌_system_event_key는_종류_조합과_무관하게_독립적으로_거절된다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName);
			long hostUserId = insertUser(schemaName, "event-key-host@example.com");
			long subjectUserId = insertUser(schemaName, "event-key-subject@example.com");
			long chatRoomId = insertChatRoom(schemaName, hostUserId);

			assertKindViolation(
				() -> insertSystemMessage(schemaName, chatRoomId, subjectUserId, "PARTICIPANT_PROMOTED"));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void T1_subject_user_id는_users_FK를_강제하고_기존_채팅방에_SYSTEM_행을_소급_생성하지_않는다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName);
			long hostUserId = insertUser(schemaName, "fk-host@example.com");
			long chatRoomId = insertChatRoom(schemaName, hostUserId);

			assertForeignKeyViolation(
				() -> insertSystemMessage(schemaName, chatRoomId, Long.MAX_VALUE, "PARTICIPANT_ENTERED"));
			assertEquals(0, systemMessageCount(schemaName, chatRoomId));
		} finally {
			dropSchema(schemaName);
		}
	}

	private void migrate(String schemaName) {
		Flyway.configure()
			.dataSource(dataSource)
			.locations(GENERAL_MIGRATION_LOCATION, POSTGRES_MIGRATION_LOCATION)
			.schemas(schemaName)
			.defaultSchema(schemaName)
			.load()
			.migrate();
	}

	private String newSchemaName() {
		return "chat_system_message_" + UUID.randomUUID().toString().replace("-", "");
	}

	private long insertUser(String schemaName, String email) {
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "users")
				+ " (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', '작성자', "
				+ "current_timestamp, current_timestamp) returning id",
			Long.class,
			email);
	}

	private long insertChatRoom(String schemaName, long hostUserId) {
		long roomId = jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "rooms")
				+ " (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, "
				+ "active_participant_count, start_at, place, status, created_at, updated_at) values "
				+ "(?, 'PERSON_FOCUSED', 'CHAT-06 스키마 검증 방', 'ALL_LEVELS', false, 1, 0, "
				+ "current_timestamp, '홍대', 'RECRUITING', current_timestamp, current_timestamp) returning id",
			Long.class,
			hostUserId);
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "chat_rooms")
				+ " (room_id, created_at, updated_at) values (?, current_timestamp, current_timestamp) returning id",
			Long.class,
			roomId);
	}

	private long insertUserMessage(
		String schemaName, long chatRoomId, long senderUserId, String clientMessageId, String content) {
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "chat_messages")
				+ " (chat_room_id, sender_user_id, client_message_id, content, message_type, created_at) "
				+ "values (?, ?, ?, ?, 'USER', current_timestamp) returning id",
			Long.class,
			chatRoomId,
			senderUserId,
			clientMessageId,
			content);
	}

	private long insertSystemMessage(String schemaName, long chatRoomId, long subjectUserId, String eventKey) {
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "chat_messages")
				+ " (chat_room_id, message_type, system_event_key, subject_user_id, created_at) "
				+ "values (?, 'SYSTEM', ?, ?, current_timestamp) returning id",
			Long.class,
			chatRoomId,
			eventKey,
			subjectUserId);
	}

	private int systemMessageCount(String schemaName, long chatRoomId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from " + table(schemaName, "chat_messages")
				+ " where chat_room_id = ? and message_type = 'SYSTEM'",
			Integer.class,
			chatRoomId);
	}

	private void assertKindViolation(org.junit.jupiter.api.function.Executable operation) {
		assertConstraintViolation("23514", "ck_chat_messages_kind", operation);
	}

	private void assertForeignKeyViolation(org.junit.jupiter.api.function.Executable operation) {
		assertConstraintViolation("23503", "fk_chat_messages_subject_user", operation);
	}

	private void assertConstraintViolation(
		String expectedSqlState, String expectedConstraint, org.junit.jupiter.api.function.Executable operation) {
		DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation);
		SQLException sqlException = findSqlException(exception);
		assertEquals(expectedSqlState, sqlException.getSQLState());
		assertTrue(exception.getMessage().contains(expectedConstraint));
	}

	private SQLException findSqlException(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SQLException sqlException) {
				return sqlException;
			}
		}
		throw new AssertionError("Expected a PostgreSQL SQLException cause", throwable);
	}

	private String table(String schemaName, String tableName) {
		return schemaName + "." + tableName;
	}

	private void dropSchema(String schemaName) {
		jdbcTemplate.execute("drop schema if exists " + schemaName + " cascade");
	}
}
