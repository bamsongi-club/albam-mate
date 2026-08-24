package cloud.bamsongi.albammate.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(classes = FlywayMigratorApplication.class, properties = {"spring.profiles.active=migrator",
	"spring.main.web-application-type=none",
	"spring.flyway.ignore-migration-patterns=*:pending"})
class FlywayMigratorApplicationTest {

	@Autowired
	private Environment environment;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private Flyway flyway;

	@Autowired
	private FlywayMigrationStrategy flywayMigrationStrategy;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void H2에서_migrator_컨텍스트는_web없이_전체_스키마를_한번_준비한다() throws Exception {
		assertEquals("migrator", environment.getProperty("spring.profiles.active"));
		assertEquals("H2", dataSource.getConnection().getMetaData().getDatabaseProductName());
		assertTrue(jdbcTemplate.queryForObject(
			"select count(*) > 0 from flyway_schema_history where success = true", Boolean.class));
		flyway.validate();
	}

	@Test
	void 전용_컨텍스트는_Boot_FlywayMigrationStrategy만_공개한다() {
		assertNotNull(flywayMigrationStrategy);
		assertEquals(WebApplicationType.NONE,
			FlywayMigratorApplication.create().getWebApplicationType());
	}
}
