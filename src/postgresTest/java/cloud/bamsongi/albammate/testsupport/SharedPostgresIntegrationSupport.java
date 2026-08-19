package cloud.bamsongi.albammate.testsupport;

import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeAll;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

public abstract class SharedPostgresIntegrationSupport {

	protected static final PostgreSQLContainer POSTGRES = SharedPostgresContainer.INSTANCE;

	@DynamicPropertySource
	protected static void configureSharedPostgres(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
	}

	@BeforeAll
	protected static void resetSharedDatabase(@Autowired
	DataSource dataSource) throws SQLException {
		PostgresDatabaseCleaner.clean(dataSource);
	}

	private static final class SharedPostgresContainer extends PostgreSQLContainer {

		private static final String POSTGRES_IMAGE = "postgres:18.4";
		private static final SharedPostgresContainer INSTANCE = new SharedPostgresContainer();

		private SharedPostgresContainer() {
			super(POSTGRES_IMAGE);
			withDatabaseName("albam_mate_shared_test");
			start();
		}
	}
}
