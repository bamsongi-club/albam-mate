package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();
	private static final String COMMON_MIGRATION_LOCATION = "classpath:db/migration";
	private static final String POSTGRES_MIGRATION_LOCATION = "classpath:db/vendor-migration/postgresql";
	private static final Map<String, Integer> EXPECTED_LEGACY_MIGRATION_CHECKSUMS = Map.of(
		"28", -295162230,
		"29", -1629277577);
	private static final List<ExpectedConstraint> EXPECTED_MATCH_CONSTRAINTS = List.of(
		new ExpectedConstraint("match_requests", "f", "fk_match_requests_user",
			"FOREIGN KEY (user_id) REFERENCES users(id)"),
		new ExpectedConstraint(
			"match_proposal_members",
			"f",
			"fk_match_proposal_members_proposal",
			"FOREIGN KEY (proposal_id) REFERENCES match_proposals(id) ON DELETE CASCADE"),
		new ExpectedConstraint("match_proposal_members", "f", "fk_match_proposal_members_user",
			"FOREIGN KEY (user_id) REFERENCES users(id)"),
		new ExpectedConstraint(
			"match_proposal_members",
			"f",
			"fk_match_proposal_members_request_owner",
			"FOREIGN KEY (match_request_id, user_id) REFERENCES match_requests(id, user_id) ON DELETE CASCADE"),
		new ExpectedConstraint(
			"match_parties",
			"f",
			"fk_match_parties_proposal",
			"FOREIGN KEY (proposal_id) REFERENCES match_proposals(id) ON DELETE SET NULL"),
		new ExpectedConstraint(
			"match_party_participants",
			"f",
			"fk_match_party_participants_party",
			"FOREIGN KEY (party_id) REFERENCES match_parties(id) ON DELETE CASCADE"),
		new ExpectedConstraint("match_party_participants", "f", "fk_match_party_participants_user",
			"FOREIGN KEY (user_id) REFERENCES users(id)"),
		new ExpectedConstraint(
			"match_chat_rooms",
			"f",
			"fk_match_chat_rooms_party",
			"FOREIGN KEY (party_id) REFERENCES match_parties(id) ON DELETE RESTRICT"),
		new ExpectedConstraint(
			"match_chat_messages",
			"f",
			"fk_match_chat_messages_room",
			"FOREIGN KEY (match_chat_room_id) REFERENCES match_chat_rooms(id) ON DELETE CASCADE"),
		new ExpectedConstraint("match_chat_messages", "f", "fk_match_chat_messages_sender",
			"FOREIGN KEY (sender_user_id) REFERENCES users(id)"),
		new ExpectedConstraint(
			"match_idempotency_records",
			"f",
			"fk_match_idempotency_records_user",
			"FOREIGN KEY (user_id) REFERENCES users(id)"),
		new ExpectedConstraint("match_blocks", "f", "fk_match_blocks_blocker",
			"FOREIGN KEY (blocker_user_id) REFERENCES users(id)"),
		new ExpectedConstraint("match_blocks", "f", "fk_match_blocks_blocked",
			"FOREIGN KEY (blocked_user_id) REFERENCES users(id)"),
		new ExpectedConstraint("match_reports", "f", "fk_match_reports_reporter",
			"FOREIGN KEY (reporter_user_id) REFERENCES users(id)"),
		new ExpectedConstraint("match_reports", "f", "fk_match_reports_reported",
			"FOREIGN KEY (reported_user_id) REFERENCES users(id)"),
		new ExpectedConstraint("match_requests", "p", "match_requests_pkey", "PRIMARY KEY (id)"),
		new ExpectedConstraint("match_proposals", "p", "match_proposals_pkey", "PRIMARY KEY (id)"),
		new ExpectedConstraint("match_proposal_members", "p", "pk_match_proposal_members",
			"PRIMARY KEY (proposal_id, match_request_id)"),
		new ExpectedConstraint("match_parties", "p", "match_parties_pkey", "PRIMARY KEY (id)"),
		new ExpectedConstraint("match_party_participants", "p", "pk_match_party_participants",
			"PRIMARY KEY (party_id, user_id)"),
		new ExpectedConstraint("match_chat_rooms", "p", "match_chat_rooms_pkey", "PRIMARY KEY (id)"),
		new ExpectedConstraint("match_chat_messages", "p", "match_chat_messages_pkey", "PRIMARY KEY (id)"),
		new ExpectedConstraint("match_idempotency_records", "p", "match_idempotency_records_pkey", "PRIMARY KEY (id)"),
		new ExpectedConstraint("match_blocks", "p", "match_blocks_pkey", "PRIMARY KEY (id)"),
		new ExpectedConstraint("match_reports", "p", "match_reports_pkey", "PRIMARY KEY (id)"),
		new ExpectedConstraint("match_requests", "c", "ck_match_requests_party_size"),
		new ExpectedConstraint("match_requests", "c", "ck_match_requests_status"),
		new ExpectedConstraint("match_proposals", "c", "ck_match_proposals_status"),
		new ExpectedConstraint("match_proposals", "c", "ck_match_proposals_party_size"),
		new ExpectedConstraint("match_proposal_members", "c", "ck_match_proposal_members_response_status"),
		new ExpectedConstraint("match_proposal_members", "c", "ck_match_proposal_members_response_lifecycle"),
		new ExpectedConstraint("match_parties", "c", "ck_match_parties_status"),
		new ExpectedConstraint("match_parties", "c", "ck_match_parties_lifecycle_common"),
		new ExpectedConstraint("match_chat_messages", "c", "ck_match_chat_messages_kind"),
		new ExpectedConstraint("match_idempotency_records", "c", "ck_match_idempotency_operation"),
		new ExpectedConstraint("match_blocks", "c", "ck_match_blocks_not_self"),
		new ExpectedConstraint("match_reports", "c", "ck_match_reports_reason"),
		new ExpectedConstraint("match_reports", "c", "ck_match_reports_not_self"),
		new ExpectedConstraint("match_parties", "c", "ck_match_parties_lifecycle"),
		new ExpectedConstraint("match_requests", "u", "uq_match_requests_id_user"),
		new ExpectedConstraint("match_proposal_members", "u", "uq_match_proposal_members_user"),
		new ExpectedConstraint("match_party_participants", "u", "uq_match_party_participants_ref"),
		new ExpectedConstraint("match_chat_rooms", "u", "uq_match_chat_rooms_party"),
		new ExpectedConstraint("match_idempotency_records", "u", "uq_match_idempotency_records_user_key"),
		new ExpectedConstraint("match_blocks", "u", "uq_match_blocks_direction"),
		new ExpectedConstraint("match_reports", "u", "uq_match_reports_reporter_reported"));
	private static final List<ExpectedPartialIndex> EXPECTED_MATCH_PARTIAL_INDEXES = List.of(
		new ExpectedPartialIndex(
			"match_requests",
			true,
			"uq_match_requests_active_user",
			"((status)::text = ANY ((ARRAY['WAITING'::character varying, 'PROPOSED'::character varying, 'PAUSED'::character varying])::text[]))"),
		new ExpectedPartialIndex(
			"match_requests",
			false,
			"idx_match_requests_waiting_candidate",
			"((status)::text = 'WAITING'::text)",
			"priority_since,id"),
		new ExpectedPartialIndex(
			"match_requests",
			false,
			"idx_match_requests_purge_after",
			"(purge_after IS NOT NULL)"),
		new ExpectedPartialIndex(
			"match_proposals",
			false,
			"idx_match_proposals_purge_after",
			"(purge_after IS NOT NULL)"),
		new ExpectedPartialIndex(
			"match_parties",
			true,
			"uq_match_parties_proposal",
			"(proposal_id IS NOT NULL)"),
		new ExpectedPartialIndex(
			"match_parties",
			false,
			"idx_match_parties_preparing_due",
			"((status)::text = 'PREPARING'::text)"),
		new ExpectedPartialIndex(
			"match_parties",
			false,
			"idx_match_parties_active_due",
			"((status)::text = 'ACTIVE'::text)"),
		new ExpectedPartialIndex(
			"match_parties",
			false,
			"idx_match_parties_purge_after",
			"(purge_after IS NOT NULL)"),
		new ExpectedPartialIndex(
			"match_party_participants",
			false,
			"idx_match_party_participants_current",
			"(left_at IS NULL)"),
		new ExpectedPartialIndex(
			"match_chat_messages",
			true,
			"uq_match_chat_messages_user_client",
			"(client_message_id IS NOT NULL)"),
		new ExpectedPartialIndex(
			"match_chat_messages",
			true,
			"uq_match_chat_messages_system_event",
			"(system_event_key IS NOT NULL)"));

	private record ExpectedConstraint(String tableName, String type, String constraintName, String definition) {
		private ExpectedConstraint(String tableName, String type, String constraintName) {
			this(tableName, type, constraintName, null);
		}
	}
	private record ExpectedPartialIndex(
		String tableName, boolean unique, String indexName, String predicate, String keyColumns) {
		private ExpectedPartialIndex(String tableName, boolean unique, String indexName, String predicate) {
			this(tableName, unique, indexName, predicate, null);
		}
	}

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_match_schema_test");

	@Autowired
	private DataSource dataSource;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 빈_데이터베이스에서_V31까지_순서대로_적용하고_Flyway_validation을_통과한다() {
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

			for (ExpectedConstraint constraint : EXPECTED_MATCH_CONSTRAINTS) {
				assertConstraintExists(schemaName, constraint);
			}
			for (ExpectedPartialIndex index : EXPECTED_MATCH_PARTIAL_INDEXES) {
				assertPartialIndexExists(schemaName, index);
				assertPartialIndexPredicate(schemaName, index);
				assertPartialIndexColumns(schemaName, index);
			}
			assertEquals(1, migrationCount(schemaName, "30"));
			assertEquals(1, migrationCount(schemaName, "31"));
			validate(schemaName);
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void V28_V29_적용_기존_데이터베이스는_기존_checksum을_보존한_채_V31까지_전진_적용한다() {
		String schemaName = newSchemaName();
		try {
			migrateTo(schemaName, "29");
			Map<String, Integer> existingChecksums = migrationChecksums(schemaName, List.of("28", "29"));
			assertEquals(EXPECTED_LEGACY_MIGRATION_CHECKSUMS, existingChecksums);

			migrate(schemaName);

			assertEquals(EXPECTED_LEGACY_MIGRATION_CHECKSUMS,
				migrationChecksums(schemaName, List.of("28", "29")));
			assertEquals(1, migrationCount(schemaName, "30"));
			assertEquals(1, migrationCount(schemaName, "31"));
			validate(schemaName);
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void migration_후_MATCH_세_테이블과_정의에서_game_id_참조를_제거한다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName);

			for (String tableName : List.of("match_requests", "match_proposals", "match_parties")) {
				assertEquals(0, jdbcTemplate.queryForObject(
					"select count(*) from information_schema.columns where table_schema = ? and table_name = ? and column_name = 'game_id'",
					Integer.class, schemaName, tableName));
			}
			assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from pg_constraint constraint_info "
					+ "join pg_namespace namespace_info on namespace_info.oid = constraint_info.connamespace "
					+ "join pg_class table_info on table_info.oid = constraint_info.conrelid "
					+ "where namespace_info.nspname = ? and table_info.relname in ('match_requests', 'match_proposals', 'match_parties') "
					+ "and pg_get_constraintdef(constraint_info.oid) ilike '%game_id%'",
				Integer.class, schemaName));
			assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from pg_indexes where schemaname = ? and tablename like 'match_%' and indexdef ilike '%game_id%'",
				Integer.class, schemaName));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void 기존_MATCH_관계_데이터는_game_id_외의_행_PK_상태_인원_시각을_보존한다() {
		String schemaName = newSchemaName();
		try {
			migrateTo(schemaName, "29");
			long userId = insertUser(schemaName, "legacy-owner");
			long gameId = insertGame(schemaName);
			long requestId = insertLegacyRequest(schemaName, userId, gameId, "WAITING");
			long proposalId = jdbcTemplate.queryForObject(
				"insert into " + table(schemaName, "match_proposals")
					+ " (game_id, party_size, status, respond_by, created_at, updated_at) "
					+ "values (?, 2, 'OPEN', current_timestamp + interval '30 seconds', current_timestamp, current_timestamp) returning id",
				Long.class, gameId);
			long partyId = jdbcTemplate.queryForObject(
				"insert into " + table(schemaName, "match_parties")
					+ " (proposal_id, game_id, status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) "
					+ "values (?, ?, 'ACTIVE', current_timestamp, current_timestamp, current_timestamp + interval '1 day', current_timestamp, current_timestamp) returning id",
				Long.class, proposalId, gameId);
			UUID participantRef = UUID.randomUUID();
			jdbcTemplate.update("insert into " + table(schemaName, "match_proposal_members")
				+ " (proposal_id, match_request_id, user_id, response_status, created_at, updated_at) values (?, ?, ?, 'PENDING', current_timestamp, current_timestamp)",
				proposalId, requestId, userId);
			jdbcTemplate.update("insert into " + table(schemaName, "match_party_participants")
				+ " (party_id, user_id, participant_ref, created_at) values (?, ?, ?, current_timestamp)",
				partyId, userId, participantRef);

			Map<String, Object> requestBefore = row(schemaName, "match_requests", requestId);
			Map<String, Object> proposalBefore = row(schemaName, "match_proposals", proposalId);
			Map<String, Object> partyBefore = row(schemaName, "match_parties", partyId);
			Map<String, Object> memberBefore = jdbcTemplate
				.queryForMap("select * from " + table(schemaName, "match_proposal_members")
					+ " where proposal_id = ? and match_request_id = ?", proposalId, requestId);
			Map<String, Object> participantBefore = jdbcTemplate
				.queryForMap("select * from " + table(schemaName, "match_party_participants")
					+ " where party_id = ? and user_id = ?", partyId, userId);

			migrate(schemaName);

			assertEquals(withoutGameId(requestBefore), row(schemaName, "match_requests", requestId));
			assertEquals(withoutGameId(proposalBefore), row(schemaName, "match_proposals", proposalId));
			assertEquals(withoutGameId(partyBefore), row(schemaName, "match_parties", partyId));
			assertEquals(memberBefore,
				jdbcTemplate.queryForMap("select * from " + table(schemaName, "match_proposal_members")
					+ " where proposal_id = ? and match_request_id = ?", proposalId, requestId));
			assertEquals(participantBefore,
				jdbcTemplate.queryForMap("select * from " + table(schemaName, "match_party_participants")
					+ " where party_id = ? and user_id = ?", partyId, userId));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void 게임_제거_후에도_비게임_제약은_PostgreSQL이_정상과_대표_위반을_구분한다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName);
			long userId = insertUser(schemaName, "requester");
			long otherUserId = insertUser(schemaName, "other");
			long requestId = insertRequest(schemaName, userId, "WAITING");
			long secondRequestId = insertRequest(schemaName, userId, "MATCHED");
			long proposalId = jdbcTemplate.queryForObject(
				"insert into " + table(schemaName, "match_proposals")
					+ " (party_size, status, respond_by, created_at, updated_at) "
					+ "values (2, 'OPEN', current_timestamp + interval '30 seconds', current_timestamp, current_timestamp) returning id",
				Long.class);

			assertConstraintViolation(
				"23514",
				"ck_match_requests_party_size",
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "match_requests")
						+ " (user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) "
						+ "values (?, 3, 2, 'WAITING', current_timestamp, current_timestamp, current_timestamp, current_timestamp)",
					userId));
			assertConstraintViolation(
				"23505",
				"uq_match_requests_active_user",
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "match_requests")
						+ " (user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) "
						+ "values (?, 2, 4, 'PAUSED', current_timestamp, current_timestamp, current_timestamp, current_timestamp)",
					userId));
			assertConstraintViolation(
				"23505",
				"uq_match_requests_active_user",
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "match_requests")
						+ " (user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) "
						+ "values (?, 2, 4, 'PROPOSED', current_timestamp, current_timestamp, current_timestamp, current_timestamp)",
					userId));
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
			jdbcTemplate.update(
				"insert into " + table(schemaName, "match_proposal_members")
					+ " (proposal_id, match_request_id, user_id, response_status, responded_at, created_at, updated_at) "
					+ "values (?, ?, ?, 'ACCEPTED', current_timestamp, current_timestamp, current_timestamp)",
				proposalId,
				requestId,
				userId);
			assertConstraintViolation(
				"23505",
				"uq_match_proposal_members_user",
				() -> jdbcTemplate.update(
					"insert into " + table(schemaName, "match_proposal_members")
						+ " (proposal_id, match_request_id, user_id, response_status, responded_at, created_at, updated_at) "
						+ "values (?, ?, ?, 'ACCEPTED', current_timestamp, current_timestamp, current_timestamp)",
					proposalId,
					secondRequestId,
					userId));
			assertConstraintViolation(
				"23514",
				"ck_match_parties_lifecycle",
				() -> jdbcTemplate.update("insert into " + table(schemaName, "match_parties")
					+ " (status, preparing_started_at, created_at, updated_at) values ('ACTIVE', current_timestamp, current_timestamp, current_timestamp)"));
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

	private void migrateTo(String schemaName, String targetVersion) {
		Flyway.configure()
			.dataSource(dataSource)
			.locations(COMMON_MIGRATION_LOCATION, POSTGRES_MIGRATION_LOCATION)
			.schemas(schemaName)
			.defaultSchema(schemaName)
			.target(targetVersion)
			.load()
			.migrate();
	}

	private void validate(String schemaName) {
		Flyway.configure()
			.dataSource(dataSource)
			.locations(COMMON_MIGRATION_LOCATION, POSTGRES_MIGRATION_LOCATION)
			.schemas(schemaName)
			.defaultSchema(schemaName)
			.load()
			.validate();
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

	private long insertRequest(String schemaName, long userId, String status) {
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "match_requests")
				+ " (user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) "
				+ "values (?, 2, 4, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp) returning id",
			Long.class,
			userId,
			status);
	}

	private long insertLegacyRequest(String schemaName, long userId, long gameId, String status) {
		return jdbcTemplate.queryForObject(
			"insert into " + table(schemaName, "match_requests")
				+ " (user_id, game_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) "
				+ "values (?, ?, 2, 4, ?, current_timestamp, current_timestamp, current_timestamp, current_timestamp) returning id",
			Long.class, userId, gameId, status);
	}

	private int migrationCount(String schemaName, String version) {
		return jdbcTemplate.queryForObject(
			"select count(*) from " + table(schemaName, "flyway_schema_history") + " where version = ? and success",
			Integer.class, version);
	}

	private Map<String, Integer> migrationChecksums(String schemaName, List<String> versions) {
		return jdbcTemplate.query("select version, checksum from " + table(schemaName, "flyway_schema_history")
			+ " where version in (?, ?)",
			resultSet -> {
				Map<String, Integer> checksums = new java.util.HashMap<>();
				while (resultSet.next()) {
					checksums.put(resultSet.getString("version"), resultSet.getInt("checksum"));
				}
				return checksums;
			}, versions.get(0), versions.get(1));
	}

	private Map<String, Object> row(String schemaName, String tableName, long id) {
		return jdbcTemplate.queryForMap("select * from " + table(schemaName, tableName) + " where id = ?", id);
	}

	private Map<String, Object> withoutGameId(Map<String, Object> row) {
		Map<String, Object> preserved = new java.util.HashMap<>(row);
		preserved.remove("game_id");
		return preserved;
	}

	private void assertConstraintExists(String schemaName, ExpectedConstraint expected) {
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from pg_constraint constraint_info "
					+ "join pg_namespace constraint_namespace on constraint_namespace.oid = constraint_info.connamespace "
					+ "join pg_class table_info on table_info.oid = constraint_info.conrelid "
					+ "join pg_namespace table_namespace on table_namespace.oid = table_info.relnamespace "
					+ "where constraint_namespace.nspname = ? and table_namespace.nspname = ? "
					+ "and table_info.relname = ? and constraint_info.conname = ? and constraint_info.contype = ?",
				Integer.class,
				schemaName,
				schemaName,
				expected.tableName(),
				expected.constraintName(),
				expected.type()));
		if (expected.definition() == null) {
			return;
		}
		String actualDefinition = jdbcTemplate.queryForObject(
			"select pg_get_constraintdef(constraint_info.oid) from pg_constraint constraint_info "
				+ "join pg_namespace constraint_namespace on constraint_namespace.oid = constraint_info.connamespace "
				+ "join pg_class table_info on table_info.oid = constraint_info.conrelid "
				+ "join pg_namespace table_namespace on table_namespace.oid = table_info.relnamespace "
				+ "where constraint_namespace.nspname = ? and table_namespace.nspname = ? "
				+ "and table_info.relname = ? and constraint_info.conname = ? and constraint_info.contype = ?",
			String.class,
			schemaName,
			schemaName,
			expected.tableName(),
			expected.constraintName(),
			expected.type());
		assertEquals(
			normalizeDefinition(expected.definition(), schemaName),
			normalizeDefinition(actualDefinition, schemaName),
			expected.constraintName());
	}

	private void assertPartialIndexExists(String schemaName, ExpectedPartialIndex expected) {
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from pg_index index_relation "
					+ "join pg_class index_info on index_info.oid = index_relation.indexrelid "
					+ "join pg_namespace index_namespace on index_namespace.oid = index_info.relnamespace "
					+ "join pg_class table_info on table_info.oid = index_relation.indrelid "
					+ "join pg_namespace table_namespace on table_namespace.oid = table_info.relnamespace "
					+ "where index_namespace.nspname = ? and index_info.relname = ? "
					+ "and table_namespace.nspname = ? and table_info.relname = ? "
					+ "and index_relation.indpred is not null and index_relation.indisunique = ?",
				Integer.class,
				schemaName,
				expected.indexName(),
				schemaName,
				expected.tableName(),
				expected.unique()));
	}

	private void assertPartialIndexPredicate(String schemaName, ExpectedPartialIndex expected) {
		assertEquals(
			normalizePredicate(expected.predicate()),
			normalizePredicate(partialIndexPredicate(schemaName, expected)),
			"Unexpected predicate for " + expected.indexName());
	}

	private void assertPartialIndexColumns(String schemaName, ExpectedPartialIndex expected) {
		if (expected.keyColumns() == null) {
			return;
		}
		assertEquals(
			normalizeIndexColumns(expected.keyColumns()),
			normalizeIndexColumns(partialIndexColumns(schemaName, expected)),
			"Unexpected key columns for " + expected.indexName());
	}

	private String normalizePredicate(String predicate) {
		return predicate.replaceAll("\\s+", "");
	}

	private String normalizeIndexColumns(String columns) {
		return columns.replaceAll("\\s+", "").replace("\"", "").toLowerCase(Locale.ROOT);
	}

	private String normalizeDefinition(String definition, String schemaName) {
		return definition.replace(schemaName + ".", "").replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
	}

	private String partialIndexPredicate(String schemaName, ExpectedPartialIndex expected) {
		return jdbcTemplate.queryForObject(
			"select pg_get_expr(index_relation.indpred, index_relation.indrelid) "
				+ "from pg_index index_relation "
				+ "join pg_class index_info on index_info.oid = index_relation.indexrelid "
				+ "join pg_namespace index_namespace on index_namespace.oid = index_info.relnamespace "
				+ "join pg_class table_info on table_info.oid = index_relation.indrelid "
				+ "join pg_namespace table_namespace on table_namespace.oid = table_info.relnamespace "
				+ "where index_namespace.nspname = ? and index_info.relname = ? "
				+ "and table_namespace.nspname = ? and table_info.relname = ?",
			String.class,
			schemaName,
			expected.indexName(),
			schemaName,
			expected.tableName());
	}

	private String partialIndexColumns(String schemaName, ExpectedPartialIndex expected) {
		return jdbcTemplate.queryForObject(
			"select string_agg(pg_get_indexdef(index_relation.indexrelid, key_column.position, true), ',' order by key_column.position) "
				+ "from pg_index index_relation "
				+ "join pg_class index_info on index_info.oid = index_relation.indexrelid "
				+ "join pg_namespace index_namespace on index_namespace.oid = index_info.relnamespace "
				+ "join pg_class table_info on table_info.oid = index_relation.indrelid "
				+ "join pg_namespace table_namespace on table_namespace.oid = table_info.relnamespace "
				+ "cross join lateral generate_series(1, index_relation.indnkeyatts) as key_column(position) "
				+ "where index_namespace.nspname = ? and index_info.relname = ? "
				+ "and table_namespace.nspname = ? and table_info.relname = ?",
			String.class,
			schemaName,
			expected.indexName(),
			schemaName,
			expected.tableName());
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
