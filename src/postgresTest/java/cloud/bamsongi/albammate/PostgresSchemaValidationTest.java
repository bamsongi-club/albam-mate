package cloud.bamsongi.albammate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.env.Environment;
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

    @Test
    void 빈_PostgreSQL에_Flyway_V1_V2와_Hibernate_스키마_검증이_적용된다() {
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
        assertTrue(appliedVersions.containsAll(Set.of("1", "2")));

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
}
