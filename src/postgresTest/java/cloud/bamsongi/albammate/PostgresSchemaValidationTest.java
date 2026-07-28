package cloud.bamsongi.albammate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest
class PostgresSchemaValidationTest {

    private static final String POSTGRES_IMAGE = "postgres:18.4";
    private static final Set<String> EXPECTED_TABLES =
            Set.of("users", "games", "rooms", "participations");

    @Container @ServiceConnection
    static final PostgreSQLContainer postgres =
            new PostgreSQLContainer(POSTGRES_IMAGE).withDatabaseName("albam_mate_test");

    @Autowired private Environment environment;

    @Autowired private Flyway flyway;

    @Autowired private JdbcTemplate jdbcTemplate;

    @Autowired private DataSource dataSource;

    @Test
    void 빈_PostgreSQL에_Flyway_V1_V2_V3와_Hibernate_스키마_검증이_적용된다() {
        assertEquals("validate", environment.getProperty("spring.jpa.hibernate.ddl-auto"));

        flyway.validate();

        Set<String> appliedVersions =
                jdbcTemplate
                        .query(
                                "select version from flyway_schema_history "
                                        + "where success = true",
                                (resultSet, rowNumber) -> resultSet.getString("version"))
                        .stream()
                        .collect(java.util.stream.Collectors.toSet());
        assertTrue(appliedVersions.containsAll(Set.of("1", "2", "3")));

        Set<String> actualTables =
                jdbcTemplate
                        .query(
                                "select table_name from information_schema.tables "
                                        + "where table_schema = current_schema()",
                                (resultSet, rowNumber) -> resultSet.getString("table_name"))
                        .stream()
                        .map(String::toLowerCase)
                        .collect(java.util.stream.Collectors.toSet());
        assertTrue(actualTables.containsAll(EXPECTED_TABLES));
    }

    @Test
    void V3_컬럼_rename은_V2에서_저장한_기존_행의_값을_보존한다() {
        String schemaName = "game_column_rename_" + UUID.randomUUID().toString().replace("-", "");
        try {
            migrate(schemaName, "2");
            jdbcTemplate.update(
                    "insert into "
                            + schemaName
                            + ".games "
                            + "(bgg_id, name, english_name, recommended_player_count, tag, "
                            + "estimated_play_time, description, detail_description, created_at, updated_at) "
                            + "values (9001, '마이그레이션 테스트 게임', 'Migration Test Game', '2~4명', '전략', "
                            + "'60~90분', '설명', '상세 설명', "
                            + "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
                            + "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')");

            migrate(schemaName, null);

            assertEquals(
                    "2~4명",
                    jdbcTemplate.queryForObject(
                            "select supported_player_count from "
                                    + schemaName
                                    + ".games where bgg_id = 9001",
                            String.class));
            assertEquals(
                    0,
                    jdbcTemplate.queryForObject(
                            "select count(*) from information_schema.columns "
                                    + "where table_schema = ? and table_name = 'games' "
                                    + "and column_name = 'recommended_player_count'",
                            Integer.class,
                            schemaName));
        } finally {
            jdbcTemplate.execute("drop schema if exists " + schemaName + " cascade");
        }
    }

    @Test
    void PostgreSQL_연결과_기본_설정으로_컨텍스트가_기동된다() throws SQLException {
        assertEquals(
                "none", environment.getProperty("spring.datasource.embedded-database-connection"));

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals("PostgreSQL", metadata.getDatabaseProductName());
            assertEquals(18, metadata.getDatabaseMajorVersion());
        }
    }

    @Test
    void PostgreSQL_실제_INSERT는_핵심_CHECK와_FK를_거절한다() {
        insertUser(1001L, "postgres-host@example.com");
        insertUser(1002L, "postgres-participant@example.com");
        insertRoom(2001L, 1001L, 2);

        assertConstraintViolation("23514", "ck_rooms_capacity", () -> insertRoom(2002L, 1001L, 0));
        assertConstraintViolation(
                "23514",
                "ck_participations_status_canceled_at",
                () -> insertParticipation(3001L, 2001L, 1002L, "ACTIVE", true));
        assertConstraintViolation(
                "23503",
                "fk_participations_user",
                () -> insertParticipation(3002L, 2001L, 9999L, "ACTIVE", false));
        assertConstraintViolation(
                "23503",
                "fk_participations_room",
                () -> insertParticipation(3003L, 9999L, 1002L, "ACTIVE", false));
    }

    private void assertConstraintViolation(
            String expectedSqlState,
            String expectedConstraint,
            org.junit.jupiter.api.function.Executable operation) {
        DataIntegrityViolationException exception =
                assertThrows(DataIntegrityViolationException.class, operation);
        SQLException sqlException = findSqlException(exception);

        assertEquals(expectedSqlState, sqlException.getSQLState());
        assertTrue(
                containsMessage(exception, expectedConstraint),
                () -> "Expected PostgreSQL constraint in exception: " + expectedConstraint);
    }

    private boolean containsMessage(Throwable throwable, String expectedText) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current.getMessage() != null && current.getMessage().contains(expectedText)) {
                return true;
            }
        }
        return false;
    }

    private SQLException findSqlException(Throwable throwable) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        throw new AssertionError("Expected a PostgreSQL SQLException cause", throwable);
    }

    private void migrate(String schemaName, String target) {
        var configuration =
                Flyway.configure()
                        .dataSource(dataSource)
                        .locations("classpath:db/migration")
                        .schemas(schemaName)
                        .defaultSchema(schemaName);
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private void insertUser(long id, String email) {
        jdbcTemplate.update(
                "insert into users "
                        + "(id, email, password_hash, nickname, created_at, updated_at) "
                        + "values (?, ?, 'postgres-test-hash', 'PostgreSQL 테스트 사용자', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')",
                id,
                email);
    }

    private void insertRoom(long id, long hostUserId, int capacity) {
        jdbcTemplate.update(
                "insert into rooms "
                        + "(id, host_user_id, room_type, title, experience_level, "
                        + "is_rulemaster_led, capacity, active_participant_count, start_at, place, "
                        + "status, created_at, updated_at) "
                        + "values (?, ?, 'PERSON_FOCUSED', 'PostgreSQL 제약 테스트 방', 'ALL_LEVELS', "
                        + "true, ?, 0, TIMESTAMP WITH TIME ZONE '2026-07-27T01:00:00Z', "
                        + "'PostgreSQL 테스트 장소', 'RECRUITING', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')",
                id,
                hostUserId,
                capacity);
    }

    private void insertParticipation(
            long id, long roomId, long userId, String status, boolean canceledAtPresent) {
        String canceledAt =
                canceledAtPresent ? "TIMESTAMP WITH TIME ZONE '2026-07-27T02:00:00Z'" : "NULL";
        jdbcTemplate.update(
                "insert into participations "
                        + "(id, room_id, user_id, status, joined_at, canceled_at, created_at, updated_at) "
                        + "values (?, ?, ?, ?, TIMESTAMP WITH TIME ZONE '2026-07-27T01:30:00Z', "
                        + canceledAt
                        + ", TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z', "
                        + "TIMESTAMP WITH TIME ZONE '2026-07-27T00:00:00Z')",
                id,
                roomId,
                userId,
                status);
    }
}
