package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
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

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class ChatMessageSchemaPostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final String GENERAL_MIGRATION_LOCATION = "classpath:db/migration";
	private static final String POSTGRES_MIGRATION_LOCATION = "classpath:db/vendor-migration/postgresql";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_message_test");

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void V9_Flyway는_메시지의_방과_작성자_FK를_생성하고_유효한_관계를_저장한다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, "8");
			assertNull(jdbcTemplate.queryForObject(
				"select to_regclass(?)", String.class, schemaName + ".chat_messages"));

			migrate(schemaName, null);
			assertNotNull(jdbcTemplate.queryForObject(
				"select to_regclass(?)", String.class, schemaName + ".chat_messages"));
			long hostUserId = insertUser(schemaName, "host-t1@example.com");
			long senderUserId = insertUser(schemaName, "sender-t1@example.com");
			long chatRoomId = insertChatRoom(schemaName, hostUserId);
			long messageId = insertMessage(schemaName, chatRoomId, senderUserId, "client-message-1", "안녕하세요");

			assertEquals(1, messageCount(schemaName));
			assertTrue(messageId > 0);
			assertForeignKeyViolation("fk_chat_messages_chat_room", () -> insertMessage(
				schemaName, Long.MAX_VALUE, senderUserId, "client-message-2", "존재하지 않는 채팅방"));
			assertForeignKeyViolation("fk_chat_messages_sender_user", () -> insertMessage(
				schemaName, chatRoomId, Long.MAX_VALUE, "client-message-3", "존재하지 않는 작성자"));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void 같은_방_작성자_clientMessageId는_PostgreSQL_유일_제약으로_하나만_허용된다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, null);
			long senderUserId = insertUser(schemaName, "sender-t2@example.com");
			long otherSenderUserId = insertUser(schemaName, "other-sender-t2@example.com");
			long chatRoomId = insertChatRoom(schemaName, senderUserId);
			long otherChatRoomId = insertChatRoom(schemaName, senderUserId);
			insertMessage(schemaName, chatRoomId, senderUserId, "client-message-1", "첫 메시지");
			long otherRoomMessageId = insertMessage(
				schemaName, otherChatRoomId, senderUserId, "client-message-1", "다른 방 메시지");
			long otherSenderMessageId = insertMessage(
				schemaName, chatRoomId, otherSenderUserId, "client-message-1", "다른 작성자 메시지");

			assertTrue(otherRoomMessageId > 0);
			assertTrue(otherSenderMessageId > 0);
			assertEquals(3, messageCount(schemaName));

			assertUniqueViolation(() -> insertMessage(
				schemaName, chatRoomId, senderUserId, "client-message-1", "중복 메시지"));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void 방별_메시지_ID는_내림차순_이력_조회와_PostgreSQL_인덱스로_재현된다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, null);
			long senderUserId = insertUser(schemaName, "sender-t3@example.com");
			long chatRoomId = insertChatRoom(schemaName, senderUserId);
			long firstMessageId = insertMessage(schemaName, chatRoomId, senderUserId, "client-message-1", "첫 메시지");
			long secondMessageId = insertMessage(schemaName, chatRoomId, senderUserId, "client-message-2", "두 번째 메시지");
			long thirdMessageId = insertMessage(schemaName, chatRoomId, senderUserId, "client-message-3", "세 번째 메시지");

			List<Long> messageIds = jdbcTemplate.queryForList(
				"select id from " + table(schemaName, "chat_messages")
					+ " where chat_room_id = ? order by id desc",
				Long.class,
				chatRoomId);
			assertEquals(List.of(thirdMessageId, secondMessageId, firstMessageId), messageIds);

			String indexDefinition = jdbcTemplate.queryForObject(
				"select pg_get_indexdef(indexClass.oid) "
					+ "from pg_index indexRelation "
					+ "join pg_class indexClass on indexClass.oid = indexRelation.indexrelid "
					+ "join pg_namespace namespace on namespace.oid = indexClass.relnamespace "
					+ "where namespace.nspname = ? and indexClass.relname = 'idx_chat_messages_room_id_desc'",
				String.class,
				schemaName);
			assertTrue(indexDefinition.contains("(chat_room_id, id DESC)"));
		} finally {
			dropSchema(schemaName);
		}
	}

	private void migrate(String schemaName, String targetVersion) {
		var configuration = Flyway.configure()
			.dataSource(dataSource)
			.locations(GENERAL_MIGRATION_LOCATION, POSTGRES_MIGRATION_LOCATION)
			.schemas(schemaName)
			.defaultSchema(schemaName);
		if (targetVersion != null) {
			configuration.target(targetVersion);
		}
		configuration.load().migrate();
	}

	private String newSchemaName() {
		return "chat_message_" + UUID.randomUUID().toString().replace("-", "");
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
				+ "(?, 'PERSON_FOCUSED', '메시지 스키마 검증 방', 'ALL_LEVELS', false, 1, 0, "
				+ "current_timestamp, '홍대', 'RECRUITING', current_timestamp, current_timestamp) returning id",
			Long.class,
			hostUserId);
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "chat_rooms")
				+ " (room_id, created_at, updated_at) values (?, current_timestamp, current_timestamp) returning id",
			Long.class,
			roomId);
	}

	private long insertMessage(
		String schemaName, long chatRoomId, long senderUserId, String clientMessageId, String content) {
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "chat_messages")
				+ " (chat_room_id, sender_user_id, client_message_id, content, created_at) "
				+ "values (?, ?, ?, ?, current_timestamp) returning id",
			Long.class,
			chatRoomId,
			senderUserId,
			clientMessageId,
			content);
	}

	private int messageCount(String schemaName) {
		return jdbcTemplate.queryForObject("select count(*) from " + table(schemaName, "chat_messages"), Integer.class);
	}

	private void assertUniqueViolation(org.junit.jupiter.api.function.Executable operation) {
		assertConstraintViolation("23505", "uq_chat_messages_room_sender_client_message", operation);
	}

	private void assertForeignKeyViolation(
		String expectedConstraint, org.junit.jupiter.api.function.Executable operation) {
		assertConstraintViolation("23503", expectedConstraint, operation);
	}

	private void assertConstraintViolation(
		String expectedSqlState, String expectedConstraint, org.junit.jupiter.api.function.Executable operation) {
		DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation);
		SQLException sqlException = findSqlException(exception);
		assertEquals(expectedSqlState, sqlException.getSQLState());
		if (expectedConstraint != null) {
			assertTrue(exception.getMessage().contains(expectedConstraint));
		}
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
