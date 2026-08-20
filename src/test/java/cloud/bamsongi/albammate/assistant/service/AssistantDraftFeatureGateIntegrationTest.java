package cloud.bamsongi.albammate.assistant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.assistant.entity.AssistantDraft;
import cloud.bamsongi.albammate.assistant.repository.AssistantDraftRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

/** T6: feature gate는 기존 초안 확인의 재생과 생성보다 먼저 fail-closed 한다. */
@SpringBootTest(properties = {
	"app.assistant.enabled=false", "app.assistant.no-retention-verified=true",
	"app.assistant.no-training-verified=true", "app.assistant.policy-version=OPENAI-POLICY-2026-08",
	"app.assistant.policy-url=https://openai.com/policies/api-data-usage-policies"
})
class AssistantDraftFeatureGateIntegrationTest {

	@Autowired
	private AssistantDraftService draftService;
	@Autowired
	private AssistantDraftRepository draftRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void T6_AI_비활성은_confirm을_차단하고_초안과_Room을_바꾸지_않는다() {
		long userId = userRepository.saveAndFlush(User.create("disabled-ai03@example.com", "{bcrypt}hash", "비활성 사용자"))
			.getId();
		long draftId = draftRepository.saveAndFlush(AssistantDraft.create(userId, "PERSON_FOCUSED", "비활성 초안", null,
			null, "ALL_LEVELS", false, "홍대", 3, Instant.parse("2030-01-01T12:00:00Z"), "카페", Instant.now())).getId();

		BusinessException error = assertThrows(BusinessException.class,
			() -> draftService.confirm(userId, draftId, 0, "disabled-feature-key"));

		assertEquals(ErrorCode.ASSISTANT_NOT_ENABLED, error.getErrorCode());
		assertEquals("ACTIVE",
			jdbcTemplate.queryForObject("select status from assistant_drafts where id = ?", String.class, draftId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from assistant_idempotency_records where draft_id = ?", Integer.class, draftId));
		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class, userId));
	}

	@Test
	void T1_AI_비활성과_동의_부재도_active_조회는_차단하지_않는다() {
		long userId = userRepository
			.saveAndFlush(User.create("disabled-active-query@example.com", "{bcrypt}hash", "조회 사용자"))
			.getId();
		long draftId = draftRepository.saveAndFlush(AssistantDraft.create(userId, "PERSON_FOCUSED", "조회 초안", null,
			null, "ALL_LEVELS", false, "홍대", 3, Instant.parse("2030-01-01T12:00:00Z"), null, Instant.now())).getId();

		assertEquals(draftId, draftService.getActive(userId).orElseThrow().draftId());
	}

}
