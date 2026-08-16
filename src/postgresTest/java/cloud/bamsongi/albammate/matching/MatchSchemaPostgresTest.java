package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
class MatchSchemaPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String COMMON_MIGRATION_LOCATION = "classpath:db/migration";
	private static final String POSTGRES_MIGRATION_LOCATION = "classpath:db/vendor-migration/postgresql";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_schema_test");

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void V28_MATCH_마이그레이션은_테이블과_이름있는_제약_그리고_partial_index를_생성한다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName);

			List<String> expectedTables = List.of(
				"match_requests",
				"match_proposals",
				"match_proposal_members",
				"match_parties",
				"match_party_participants",
				"match_chat_rooms",
				"match_chat_messages",
				"match_idempotency_records",
				"match_blocks",
				"match_reports");
			for (String tableName : expectedTables) {
				assertNotNull(jdbcTemplate.queryForObject(
					"select to_regclass(?)",
					String.class,
					schemaName + "." + tableName));
			}

			assertConstraintExists(schemaName, "ck_match_requests_party_size");
			assertConstraintExists(schemaName, "ck_match_proposal_members_response_lifecycle");
			assertConstraintExists(schemaName, "ck_match_parties_lifecycle");
			assertConstraintExists(schemaName, "fk_match_proposal_members_request_owner");
			assertConstraintExists(schemaName, "uq_match_blocks_direction");
			assertPartialIndexExists(schemaName, "uq_match_requests_active_user");
			assertPartialIndexIncludesStatuses(
				schemaName,
				"uq_match_requests_active_user",
				List.of("WAITING", "PROPOSED", "PAUSED"));
			assertPartialIndexExists(schemaName, "idx_match_parties_active_due");
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void MATCH_핵심_FK_CHECK_UNIQUE_위반은_PostgreSQL이_거절한다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName);
			long userId = insertUser(schemaName, "requester");
			long otherUserId = insertUser(schemaName, "other");
			long gameId = insertGame(schemaName);
			long requestId = insertRequest(schemaName, userId, gameId, "WAITING");
			long proposalId = jdbcTemplate.queryForObject(
				"insert into " + table(schemaName, "match_proposals")
					+ " (game_id, party_size, status, respond_by, created_at, updated_at) "
					+ "values (?, 2, 'OPEN', current_timestamp + interval '30 seconds', current_timestamp, current_timestamp) returning id",
				Long.class,
				gameId);

			assertConstraintViolation(
				"23514",
				"ck_match_requests_party_size",
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "match_requests")
						+ " (user_id, game_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) "
						+ "values (?, ?, 3, 2, 'WAITING', current_timestamp, current_timestamp, current_timestamp, current_timestamp)",
					userId,
					gameId));
			assertConstraintViolation(
				"23505",
				"uq_match_requests_active_user",
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "match_requests")
						+ " (user_id, game_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) "
						+ "values (?, ?, 2, 4, 'PAUSED', current_timestamp, current_timestamp, current_timestamp, current_timestamp)",
					userId,
					gameId));
			assertConstraintViolation(
				"23505",
				"uq_match_requests_active_user",
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "match_requests")
						+ " (user_id, game_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) "
						+ "values (?, ?, 2, 4, 'PROPOSED', current_timestamp, current_timestamp, current_timestamp, current_timestamp)",
					userId,
					gameId));
			assertConstraintViolation(
				"23503",
				"fk_match_proposal_members_request_owner",
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "match_proposal_members")
						+ " (proposal_id, match_request_id, user_id, response_status, created_at, updated_at) "
						+ "values (?, ?, ?, 'PENDING', current_timestamp, current_timestamp)",
					proposalId,
					requestId,
					otherUserId));
		} finally {
			dropSchema(schemaName);
		}
	}

	private void migrate(String schemaName) {
		Flyway.configure()
			.dataSource(dataSource)
			.locations(COMMON_MIGRATION_LOCATION, POSTGRES_MIGRATION_LOCATION)
			.schemas(schemaName)
			.defaultSchema(schemaName)
			.load()
			.migrate();
	}

	private long insertUser(String schemaName, String role) {
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "users")
				+ " (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			"match-" + role + "-" + UUID.randomUUID() + "@example.com",
			"매칭 " + role);
	}

	private long insertGame(String schemaName) {
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "games")
				+ " (bgg_id, name, english_name, supported_player_count, tag, estimated_play_time, description, detail_description, created_at, updated_at) "
				+ "values (?, 'MATCH 게임', 'MATCH Game', '2-4', '전략', '60', '설명', '상세 설명', current_timestamp, current_timestamp) returning id",
			Long.class,
			Math.abs(UUID.randomUUID().getMostSignificantBits()));
	}

	private long insertRequest(String schemaName, long userId, long gameId, String status) {
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "match_requests")
				+ " (user_id, game_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) "
				+ "values (?, ?, 2, 4, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp) returning id",
			Long.class,
			userId,
			gameId,
			status);
	}

	private void assertConstraintExists(String schemaName, String constraintName) {
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from pg_constraint constraint_info "
					+ "join pg_namespace namespace on namespace.oid = constraint_info.connamespace "
					+ "where namespace.nspname = ? and constraint_info.conname = ?",
				Integer.class,
				schemaName,
				constraintName));
	}

	private void assertPartialIndexExists(String schemaName, String indexName) {
		assertNotNull(partialIndexPredicate(schemaName, indexName));
	}

	private void assertPartialIndexIncludesStatuses(
		String schemaName, String indexName, List<String> expectedStatuses) {
		String predicate = partialIndexPredicate(schemaName, indexName);
		assertNotNull(predicate);
		for (String expectedStatus : expectedStatuses) {
			assertTrue(predicate.contains(expectedStatus));
		}
	}

	private String partialIndexPredicate(String schemaName, String indexName) {
		return jdbcTemplate.queryForObject(
			"select pg_get_expr(index_relation.indpred, index_relation.indrelid) "
				+ "from pg_index index_relation "
				+ "join pg_class index_class on index_class.oid = index_relation.indexrelid "
				+ "join pg_namespace namespace on namespace.oid = index_class.relnamespace "
				+ "where namespace.nspname = ? and index_class.relname = ?",
			String.class,
			schemaName,
			indexName);
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

	private String newSchemaName() {
		return "match_schema_" + UUID.randomUUID().toString().replace("-", "");
	}

	private String table(String schemaName, String tableName) {
		return schemaName + "." + tableName;
	}

	private void dropSchema(String schemaName) {
		jdbcTemplate.execute("drop schema if exists " + schemaName + " cascade");
	}
}
