package cloud.bamsongi.albammate.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
@SpringBootTest(classes = FlywayMigratorApplication.class, properties = {"spring.profiles.active=migrator",
	"spring.main.web-application-type=none",
	"spring.flyway.ignore-migration-patterns=*:pending"})
class FlywayMigratorPostgresTest {

	private static final org.testcontainers.utility.DockerImageName POSTGRES_IMAGE = cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages
		.postgres18();

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_migrator_test");

	@Autowired
	private Flyway flyway;

	@Autowired
	private FlywayMigrationStrategy flywayMigrationStrategy;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void PostgreSQL에서_validate_후_성공한_마이그레이션을_한번만_적용한다() {
		int appliedHistory = jdbcTemplate.queryForObject(
			"select count(*) from flyway_schema_history where success = true", Integer.class);
		assertTrue(appliedHistory > 0);
		assertEquals(flyway.info().applied().length, appliedHistory);
		assertTrue(flywayMigrationStrategy instanceof FlywayValidateThenMigrateStrategy);
		flyway.validate();
	}
}
