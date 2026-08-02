package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class ChatRoomSchemaPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String GENERAL_MIGRATION_LOCATION = "classpath:db/migration";
	private static final String POSTGRES_MIGRATION_LOCATION = "classpath:db/vendor-migration/postgresql";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_room_test");

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Test
	void Flyway_적용후_기존_ROOM마다_CHAT_ROOMS가_정확히_하나이고_중복을_거절한다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, "5");
			long recruitingRoomId = insertRoom(schemaName, "RECRUITING");
			long closedRoomId = insertRoom(schemaName, "CLOSED");
			long canceledRoomId = insertRoom(schemaName, "CANCELED");
			long finishedRoomId = insertRoom(schemaName, "FINISHED");

			migrate(schemaName, null);

			assertEquals(4, chatRoomCount(schemaName));
			for (long roomId : List.of(recruitingRoomId, closedRoomId, canceledRoomId, finishedRoomId)) {
				assertEquals(1, chatRoomCountForRoom(schemaName, roomId));
			}
			assertUniqueViolation(
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "chat_rooms")
						+ " (room_id, created_at, updated_at) values (?, current_timestamp, current_timestamp)",
					recruitingRoomId));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void 기존_활성_ROOM은_보관_시각이_NULL로_초기화된다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, "5");
			long recruitingRoomId = insertRoom(schemaName, "RECRUITING");
			long closedRoomId = insertRoom(schemaName, "CLOSED");

			migrate(schemaName, null);

			assertRetention(schemaName, recruitingRoomId, null, null);
			assertRetention(schemaName, closedRoomId, null, null);
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void 기존_최종_상태_ROOM은_같은_기준_시각의_빈_보관_완료값을_가진다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, "5");
			long canceledRoomId = insertRoom(schemaName, "CANCELED");
			long finishedRoomId = insertRoom(schemaName, "FINISHED");

			migrate(schemaName, null);

			Retention canceledRetention = retention(schemaName, canceledRoomId);
			Retention finishedRetention = retention(schemaName, finishedRoomId);
			assertNotNull(canceledRetention.purgeAfter());
			assertEquals(canceledRetention.purgeAfter(), canceledRetention.messagesPurgedAt());
			assertEquals(canceledRetention, finishedRetention);
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void backfill_INSERT_재실행은_중복과_기존_보관값_변경을_만들지_않는다() throws IOException {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, "5");
			long existingRecruitingRoomId = insertRoom(schemaName, "RECRUITING");
			long existingCanceledRoomId = insertRoom(schemaName, "CANCELED");
			migrate(schemaName, null);
			Retention recruitingRetentionBeforeRetry = retention(schemaName, existingRecruitingRoomId);
			Retention retentionBeforeRetry = retention(schemaName, existingCanceledRoomId);
			long missingRecruitingRoomId = insertRoom(schemaName, "RECRUITING");

			executeBackfillInsert(schemaName);

			assertEquals(3, chatRoomCount(schemaName));
			assertEquals(1, chatRoomCountForRoom(schemaName, existingRecruitingRoomId));
			assertEquals(1, chatRoomCountForRoom(schemaName, existingCanceledRoomId));
			assertEquals(recruitingRetentionBeforeRetry, retention(schemaName, existingRecruitingRoomId));
			assertEquals(retentionBeforeRetry, retention(schemaName, existingCanceledRoomId));
			assertRetention(schemaName, missingRecruitingRoomId, null, null);
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void V7_부분_인덱스와_보관_완료_CHECK를_PostgreSQL에서_검증한다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, null);

			String indexDefinition = jdbcTemplate.queryForObject(
				"select indexdef from pg_indexes where schemaname = ? and indexname = 'idx_chat_rooms_pending_purge'",
				String.class,
				schemaName);
			assertNotNull(indexDefinition);
			assertTrue(indexDefinition.contains("(purge_after)"));
			assertTrue(indexDefinition.contains("WHERE ((purge_after IS NOT NULL) AND (messages_purged_at IS NULL))"));

			long roomId = insertRoom(schemaName, "RECRUITING");
			assertCheckViolation(
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "chat_rooms")
						+ " (room_id, messages_purged_at, created_at, updated_at) "
						+ "values (?, current_timestamp, current_timestamp, current_timestamp)",
					roomId));
			assertForeignKeyViolation(
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "chat_rooms")
						+ " (room_id, created_at, updated_at) "
						+ "values (?, current_timestamp, current_timestamp)",
					Long.MAX_VALUE));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void ChatRoom은_ROOM_Entity_관계없이_식별자와_보관_시각만_매핑한다() {
		EntityType<ChatRoom> entityType = entityManager.getMetamodel().entity(ChatRoom.class);
		assertEquals(
			java.util.Set.of("id", "roomId", "purgeAfter", "messagesPurgedAt", "createdAt", "updatedAt"),
			entityType.getAttributes().stream().map(attribute -> attribute.getName())
				.collect(java.util.stream.Collectors.toSet()));
		assertFalse(
			entityType.getAttributes().stream().anyMatch(attribute -> attribute.isAssociation()));
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
		return "chat_room_" + UUID.randomUUID().toString().replace("-", "");
	}

	private long insertRoom(String schemaName, String status) {
		long userId = jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "users")
				+ " (email, password_hash, nickname, created_at, updated_at) values "
				+ "(concat('chat-', nextval('" + schemaName + ".users_id_seq'), '@example.com'), 'hash', '사용자', "
				+ "current_timestamp, current_timestamp) returning id",
			Long.class);
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "rooms")
				+ " (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, "
				+ "active_participant_count, start_at, place, status, created_at, updated_at) values "
				+ "(?, 'PERSON_FOCUSED', '채팅방 스키마 검증 방', 'ALL_LEVELS', false, 1, 0, "
				+ "current_timestamp, '홍대', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			userId,
			status);
	}

	private void executeBackfillInsert(String schemaName) throws IOException {
		String migration = new ClassPathResource("db/migration/V6__create_p1_chat_room_schema.sql")
			.getContentAsString(StandardCharsets.UTF_8);
		String backfill = migration.substring(migration.indexOf("INSERT INTO chat_rooms"));
		jdbcTemplate.execute(backfill.replaceAll("(?m)\\bchat_rooms\\b", table(schemaName, "chat_rooms")).replaceAll(
			"(?m)\\brooms\\b", table(schemaName, "rooms")));
	}

	private int chatRoomCount(String schemaName) {
		return jdbcTemplate.queryForObject("select count(*) from " + table(schemaName, "chat_rooms"), Integer.class);
	}

	private int chatRoomCountForRoom(String schemaName, long roomId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from " + table(schemaName, "chat_rooms") + " where room_id = ?",
			Integer.class,
			roomId);
	}

	private void assertRetention(String schemaName, long roomId, Instant purgeAfter, Instant messagesPurgedAt) {
		assertEquals(new Retention(purgeAfter, messagesPurgedAt), retention(schemaName, roomId));
	}

	private Retention retention(String schemaName, long roomId) {
		return jdbcTemplate.queryForObject(
			"select purge_after, messages_purged_at from " + table(schemaName, "chat_rooms") + " where room_id = ?",
			(resultSet, rowNumber) -> new Retention(
				instantOrNull(resultSet.getTimestamp("purge_after")),
				instantOrNull(resultSet.getTimestamp("messages_purged_at"))),
			roomId);
	}

	private Instant instantOrNull(Timestamp timestamp) {
		return timestamp == null ? null : timestamp.toInstant();
	}

	private void assertUniqueViolation(org.junit.jupiter.api.function.Executable operation) {
		assertConstraintViolation("23505", "uq_chat_rooms_room", operation);
	}

	private void assertCheckViolation(org.junit.jupiter.api.function.Executable operation) {
		assertConstraintViolation("23514", "ck_chat_rooms_purge_completion", operation);
	}

	private void assertForeignKeyViolation(org.junit.jupiter.api.function.Executable operation) {
		assertConstraintViolation("23503", "fk_chat_rooms_room", operation);
	}

	private void assertConstraintViolation(
		String expectedSqlState,
		String expectedConstraint,
		org.junit.jupiter.api.function.Executable operation) {
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

	private record Retention(Instant purgeAfter, Instant messagesPurgedAt) {
	}
}
