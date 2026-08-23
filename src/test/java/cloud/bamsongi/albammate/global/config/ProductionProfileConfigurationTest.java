package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ProductionProfileConfigurationTest {

	private static final String PRODUCTION_JDBC_URL = "jdbc:postgresql://${ALBAM_MATE_DB_HOST}:${ALBAM_MATE_DB_PORT}/${ALBAM_MATE_DB_NAME}"
		+ "?sslmode=disable&ApplicationName=albam-mate";

	@Test
	void production_프로필은_필수_RDS_환경변수와_TLS_검증_URL을_사용한다() throws IOException {
		Properties properties = productionProperties();

		assertEquals("production", properties.getProperty("spring.config.activate.on-profile"));
		assertEquals(PRODUCTION_JDBC_URL, properties.getProperty("spring.datasource.url"));
		assertEquals("${ALBAM_MATE_DB_USER}", properties.getProperty("spring.datasource.username"));
		assertEquals("${ALBAM_MATE_DB_PASSWORD}", properties.getProperty("spring.datasource.password"));
		assertFalse(productionConfiguration().contains("ALBAM_MATE_DB_PORT:"));
	}

	@Test
	void production_프로필은_운영_데이터소스와_서버_계약을_유지한다() throws IOException {
		Properties properties = productionProperties();

		assertEquals("${ALBAM_MATE_DB_MAXIMUM_POOL_SIZE:8}",
			properties.getProperty("spring.datasource.hikari.maximum-pool-size"));
		assertEquals("${ALBAM_MATE_DB_MINIMUM_IDLE:2}",
			properties.getProperty("spring.datasource.hikari.minimum-idle"));
		assertEquals("SET TIME ZONE 'UTC'", properties.getProperty("spring.datasource.hikari.connection-init-sql"));
		assertEquals("true", properties.getProperty("spring.flyway.enabled"));
		assertEquals(
			"classpath:db/migration,classpath:db/vendor-migration/postgresql",
			properties.getProperty("spring.flyway.locations"));
		assertEquals("*:pending", properties.getProperty("spring.flyway.ignore-migration-patterns"));
		assertEquals("validate", properties.getProperty("spring.jpa.hibernate.ddl-auto"));
		assertEquals("UTC", properties.getProperty("spring.jpa.properties.hibernate.jdbc.time_zone"));
		assertEquals("30s", properties.getProperty("spring.lifecycle.timeout-per-shutdown-phase"));
		assertEquals("framework", properties.getProperty("server.forward-headers-strategy"));
		assertEquals("graceful", properties.getProperty("server.shutdown"));
		assertEquals("true", properties.getProperty("app.security.cookie.secure"));
	}

	@Test
	void local_프로필은_PostgreSQL_vendor와_기존_로컬_seed_경로를_함께_사용한다() {
		Properties properties = localProperties();

		assertEquals(
			"classpath:db/migration,classpath:db/vendor-migration/postgresql,classpath:db/local",
			properties.getProperty("spring.flyway.locations"));
	}

	@Test
	void production_프로필에는_실제_RDS_엔드포인트나_비밀값이_없다() throws IOException {
		String configuration = productionConfiguration();

		assertFalse(configuration.contains("amazonaws.com"));
		assertEquals(1, occurrences(configuration, "password: ${ALBAM_MATE_DB_PASSWORD}"));
	}

	@Test
	void T2_production_AI와_Cloudflare_검색은_기본_활성화하고_명시적_환경_gate를_사용한다() {
		Properties properties = productionProperties();

		assertEquals("${ALBAM_MATE_SEARCH_CLOUDFLARE_ENABLED:true}",
			properties.getProperty("app.search.cloudflare.enabled"));
		assertEquals("${ALBAM_MATE_ASSISTANT_ENABLED:true}", properties.getProperty("app.assistant.enabled"));
		assertEquals("${ALBAM_MATE_ASSISTANT_PROVIDER:fake}", properties.getProperty("app.assistant.provider"));
		assertEquals("${ALBAM_MATE_ASSISTANT_PROVIDER_CONFIGURED:true}",
			properties.getProperty("app.assistant.provider-configured"));
		assertEquals("${ALBAM_MATE_ASSISTANT_RETENTION_MODE:unverified}",
			properties.getProperty("app.assistant.retention-mode"));
		assertEquals("${ALBAM_MATE_ASSISTANT_POLICY_VERSION:}",
			properties.getProperty("app.assistant.policy-version"));
		assertEquals("${ALBAM_MATE_ASSISTANT_PRICING_SNAPSHOT:}",
			properties.getProperty("app.assistant.pricing-snapshot"));
		assertEquals("${ALBAM_MATE_ASSISTANT_OPENAI_API_KEY:}",
			properties.getProperty("spring.ai.openai.api-key"));
	}

	private String productionConfiguration() throws IOException {
		try (var inputStream = new ClassPathResource("application-production.yml").getInputStream()) {
			return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

	private Properties productionProperties() {
		return properties("application-production.yml");
	}

	private Properties localProperties() {
		return properties("application-local.yml");
	}

	private Properties properties(String path) {
		YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
		factory.setResources(new ClassPathResource(path));
		return factory.getObject();
	}

	private int occurrences(String value, String token) {
		return value.split(java.util.regex.Pattern.quote(token), -1).length - 1;
	}
}
