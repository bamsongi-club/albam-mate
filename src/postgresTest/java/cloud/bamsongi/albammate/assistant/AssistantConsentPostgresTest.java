package cloud.bamsongi.albammate.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.assistant.contract.AssistantConsentGate;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentDecision;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentStatus;
import cloud.bamsongi.albammate.assistant.service.AssistantConsentService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@SpringBootTest(classes = AlbamMateApplication.class, properties = {
	"app.assistant.enabled=true",
	"app.assistant.no-retention-verified=true",
	"app.assistant.no-training-verified=true",
	"app.assistant.retention-mode=zero-data-retention",
	"app.assistant.policy-version=OPENAI-POLICY-2026-08",
	"app.assistant.policy-url=https://openai.com/policies/api-data-usage-policies",
	"app.assistant.store=false"
})
class AssistantConsentPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private DataSource dataSource;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private AssistantConsentService consentService;
	@Autowired
	private AssistantConsentGate consentGate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table assistant_consents, users restart identity cascade");
	}

	@Test
	void T1_PostgreSQL_동의_행이_없는_사용자는_NOT_GRANTED이고_사용자별_행을_격리한다() {
		long userId = createUser("assistant-pg-t1@example.com");

		assertEquals(AssistantConsentStatus.NOT_GRANTED, consentService.getConsent(userId).status());
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from assistant_consents where user_id = ?", Integer.class, userId));
	}

	@Test
	void T1_V38은_기존_동의_행을_unverified로_백필해_자동_승격하지_않는다() {
		String schemaName = newSchemaName();
		try {
			migrate(schemaName, "37");
			long userId = jdbcTemplate.queryForObject(
				"insert into " + table(schemaName, "users")
					+ " (email, password_hash, nickname, created_at, updated_at) values "
					+ "('assistant-v38@example.com', 'hash', 'V38 사용자', current_timestamp, current_timestamp) returning id",
				Long.class);
			jdbcTemplate.update(
				"insert into " + table(schemaName, "assistant_consents")
					+ " (user_id, status, consent_version, provider, policy_version, policy_url, store, granted_at, updated_at) values "
					+ "(?, 'GRANTED', 'AI-01-CONSENT-V1', 'OPENAI', 'OPENAI-POLICY-2026-08', "
					+ "'https://openai.com/policies/api-data-usage-policies', false, current_timestamp, current_timestamp)",
				userId);

			migrate(schemaName, "38");

			assertEquals("unverified", jdbcTemplate.queryForObject(
				"select retention_mode from " + table(schemaName, "assistant_consents") + " where user_id = ?",
				String.class,
				userId));
			assertCheckViolation(() -> jdbcTemplate.update(
				"update " + table(schemaName, "assistant_consents")
					+ " set retention_mode = 'unsafe' where user_id = ?",
				userId));
		} finally {
			dropSchema(schemaName);
		}
	}

	@Test
	void T2_PostgreSQL_GRANT는_정책_메타데이터와_store_false를_저장한다() {
		long userId = createUser("assistant-pg-t2@example.com");

		consentService.changeConsent(
			userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));

		assertEquals("GRANTED", jdbcTemplate.queryForObject(
			"select status from assistant_consents where user_id = ?", String.class, userId));
		assertFalse(jdbcTemplate.queryForObject(
			"select store from assistant_consents where user_id = ?", Boolean.class, userId));
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
			"update assistant_consents set store = true where user_id = ?", userId));
	}

	@Test
	void T3_PostgreSQL_REVOKE는_동의_행을_철회하고_AI_진입을_차단한다() {
		long userId = createUser("assistant-pg-t3@example.com");
		consentService.changeConsent(
			userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));

		consentService.changeConsent(
			userId,
			new AssistantConsentRequest(AssistantConsentDecision.REVOKE, null));

		assertEquals("REVOKED", jdbcTemplate.queryForObject(
			"select status from assistant_consents where user_id = ?", String.class, userId));
		assertFalse(consentGate.isGranted(userId));
	}

	@Test
	void T5_PostgreSQL은_retention_mode를_저장하고_모드_변경_뒤_provider_gate를_차단한다() {
		long userId = createUser("assistant-pg-t5@example.com");

		consentService.changeConsent(
			userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));

		assertEquals("zero-data-retention", jdbcTemplate.queryForObject(
			"select retention_mode from assistant_consents where user_id = ?", String.class, userId));
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
			"update assistant_consents set retention_mode = 'unsafe' where user_id = ?", userId));
		assertTrue(consentGate.isGranted(userId));
	}

	private void migrate(String schemaName, String targetVersion) {
		var configuration = Flyway.configure()
			.dataSource(dataSource)
			.locations("classpath:db/migration", "classpath:db/vendor-migration/postgresql")
			.schemas(schemaName)
			.defaultSchema(schemaName);
		if (targetVersion != null) {
			configuration.target(targetVersion);
		}
		configuration.load().migrate();
	}

	private String newSchemaName() {
		return "assistant_consent_" + UUID.randomUUID().toString().replace("-", "");
	}

	private String table(String schemaName, String tableName) {
		return schemaName + "." + tableName;
	}

	private void dropSchema(String schemaName) {
		jdbcTemplate.execute("drop schema if exists " + schemaName + " cascade");
	}

	private void assertCheckViolation(org.junit.jupiter.api.function.Executable operation) {
		DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation);
		SQLException sqlException = findSqlException(exception);
		assertEquals("23514", sqlException.getSQLState());
		assertTrue(exception.getMessage().contains("ck_assistant_consents_retention_mode"));
	}

	private SQLException findSqlException(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SQLException sqlException) {
				return sqlException;
			}
		}
		throw new AssertionError("Expected a PostgreSQL SQLException cause", throwable);
	}

	private long createUser(String email) {
		return userRepository.saveAndFlush(User.create(email, "{bcrypt}hash", email)).getId();
	}
}
