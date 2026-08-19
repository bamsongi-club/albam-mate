package cloud.bamsongi.albammate.assistant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
	"app.assistant.policy-version=OPENAI-POLICY-2026-08",
	"app.assistant.policy-url=https://openai.com/policies/api-data-usage-policies",
	"app.assistant.store=false"
})
class AssistantConsentPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private JdbcTemplate jdbcTemplate;
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

	private long createUser(String email) {
		return userRepository.saveAndFlush(User.create(email, "{bcrypt}hash", email)).getId();
	}
}
