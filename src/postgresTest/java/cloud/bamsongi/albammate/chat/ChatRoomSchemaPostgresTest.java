package cloud.bamsongi.albammate.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class ChatRoomSchemaPostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
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
	void V6_Flyway는_기존_ROOM을_backfill하지_않고_CHAT_ROOMS_스키마만_생성한다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, "5");
			insertRoom(schemaName, "RECRUITING");
			insertRoom(schemaName, "CLOSED");
			insertRoom(schemaName, "CANCELED");
			insertRoom(schemaName, "FINISHED");

			migrate(schemaName, "7");

			assertNotNull(
				jdbcTemplate.queryForObject("select to_regclass(?)", String.class, schemaName + ".chat_rooms"));
			assertEquals(0, chatRoomCount(schemaName));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void 존재하지_않는_ROOM과_중복_room_id는_FK와_유일_제약으로_거절된다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, null);
			long roomId = insertRoom(schemaName, "RECRUITING");
			assertForeignKeyViolation(
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "chat_rooms")
						+ " (room_id, created_at, updated_at) values (?, current_timestamp, current_timestamp)",
					Long.MAX_VALUE));
			jdbcTemplate.update(
				"insert into " + table(schemaName, "chat_rooms")
					+ " (room_id, created_at, updated_at) values (?, current_timestamp, current_timestamp)",
				roomId);
			assertUniqueViolation(
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "chat_rooms")
						+ " (room_id, created_at, updated_at) values (?, current_timestamp, current_timestamp)",
					roomId));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void messages_purged_at만_채운_행은_보관_완료_CHECK로_거절되고_보류_보관_인덱스_predicate가_고정된다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, null);

			String pendingPurgeIndexPredicate = jdbcTemplate.queryForObject(
				"select pg_get_expr(indexRelation.indpred, indexRelation.indrelid) "
					+ "from pg_index indexRelation "
					+ "join pg_class indexClass on indexClass.oid = indexRelation.indexrelid "
					+ "join pg_namespace namespace on namespace.oid = indexClass.relnamespace "
					+ "where namespace.nspname = ? and indexClass.relname = 'idx_chat_rooms_pending_purge'",
				String.class,
				schemaName);
			assertEquals("((purge_after IS NOT NULL) AND (messages_purged_at IS NULL))", pendingPurgeIndexPredicate);

			long pendingPurgeRoomId = insertRoom(schemaName, "RECRUITING");
			assertEquals(
				1,
				jdbcTemplate.update(
					"insert into " + table(schemaName, "chat_rooms")
						+ " (room_id, purge_after, created_at, updated_at) "
						+ "values (?, current_timestamp, current_timestamp, current_timestamp)",
					pendingPurgeRoomId));

			long completedPurgeRoomId = insertRoom(schemaName, "RECRUITING");
			assertEquals(
				1,
				jdbcTemplate.update(
					"insert into " + table(schemaName, "chat_rooms")
						+ " (room_id, purge_after, messages_purged_at, created_at, updated_at) "
						+ "values (?, current_timestamp, current_timestamp, current_timestamp, current_timestamp)",
					completedPurgeRoomId));

			long roomId = insertRoom(schemaName, "RECRUITING");
			assertCheckViolation(
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "chat_rooms")
						+ " (room_id, messages_purged_at, created_at, updated_at) "
						+ "values (?, current_timestamp, current_timestamp, current_timestamp)",
					roomId));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void ChatRoom_Entity는_ROOM_Entity_연관_없이_필드_집합을_스칼라로_매핑한다() {
		EntityType<ChatRoom> entityType = entityManager.getMetamodel().entity(ChatRoom.class);
		assertEquals(
			java.util.Set.of("id", "roomId", "purgeAfter", "messagesPurgedAt", "createdAt", "updatedAt"),
			entityType.getAttributes().stream().map(attribute -> attribute.getName())
				.collect(java.util.stream.Collectors.toSet()));
		assertEquals(
			jakarta.persistence.metamodel.Attribute.PersistentAttributeType.BASIC,
			entityType.getAttribute("roomId").getPersistentAttributeType());
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

	private int chatRoomCount(String schemaName) {
		return jdbcTemplate.queryForObject("select count(*) from " + table(schemaName, "chat_rooms"), Integer.class);
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

}
