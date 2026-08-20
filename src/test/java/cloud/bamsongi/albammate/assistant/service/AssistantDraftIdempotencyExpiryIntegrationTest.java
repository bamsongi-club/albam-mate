package cloud.bamsongi.albammate.assistant.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.assistant.dto.AssistantConsentDecision;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftCreateRequest;
import cloud.bamsongi.albammate.assistant.entity.AssistantIdempotencyRecord;
import cloud.bamsongi.albammate.assistant.repository.AssistantIdempotencyRecordRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@SpringBootTest(properties = {
	"app.assistant.enabled=true", "app.assistant.no-retention-verified=true",
	"app.assistant.no-training-verified=true", "app.assistant.policy-version=OPENAI-POLICY-2026-08",
	"app.assistant.policy-url=https://openai.com/policies/api-data-usage-policies"
})
class AssistantDraftIdempotencyExpiryIntegrationTest {
	@Autowired
	private AssistantDraftService draftService;
	@Autowired
	private AssistantConsentService consentService;
	@Autowired
	private AssistantIdempotencyRecordRepository idempotencyRecordRepository;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void T5_다음_초안_생성은_만료된_멱등기록만_정리하고_기존_Room을_건드리지_않는다() {
		long userId = userRepository.saveAndFlush(User.create("expiry-ai03@example.com", "{bcrypt}hash", "만료 정리 사용자"))
			.getId();
		consentService.changeConsent(userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));
		long draftId = draftService.create(userId, request("기존 초안")).draftId();
		idempotencyRecordRepository.saveAndFlush(AssistantIdempotencyRecord.pending(userId, draftId,
			"0000000000000000000000000000000000000000000000000000000000000000", 0,
			Instant.parse("2020-01-01T00:00:00Z")));

		draftService.create(userId, request("새 초안"));

		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from assistant_idempotency_records where user_id = ?", Integer.class, userId));
		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class, userId));
	}

	@Test
	void T5_24시간_만료된_confirm_기록은_재생하지_않고_terminal_conflict로_간다() {
		long userId = userRepository
			.saveAndFlush(User.create("expiry-confirm-ai03@example.com", "{bcrypt}hash", "확인 만료 사용자"))
			.getId();
		consentService.changeConsent(userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));
		long draftId = draftService.create(userId, requestWithPlace("확인 초안")).draftId();
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
			org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(userId), null,
				org.springframework.security.core.authority.AuthorityUtils.NO_AUTHORITIES));
		try {
			draftService.confirm(userId, draftId, 0, "expired-confirm-key");
		} finally {
			org.springframework.security.core.context.SecurityContextHolder.clearContext();
		}
		jdbcTemplate.update("update assistant_idempotency_records set expires_at = ? where draft_id = ?",
			Instant.parse("2020-01-01T00:00:00Z"), draftId);

		BusinessException exception = assertThrows(BusinessException.class,
			() -> draftService.confirm(userId, draftId, 0, "expired-confirm-key"));
		assertEquals(ErrorCode.ASSISTANT_DRAFT_CONFLICT, exception.getErrorCode());
	}

	private AssistantDraftCreateRequest request(String title) {
		return new AssistantDraftCreateRequest("PERSON_FOCUSED", title, null, null, "ALL_LEVELS", false,
			Instant.parse("2030-01-01T12:00:00Z"), "홍대", null, 3);
	}

	private AssistantDraftCreateRequest requestWithPlace(String title) {
		return new AssistantDraftCreateRequest("PERSON_FOCUSED", title, null, null, "ALL_LEVELS", false,
			Instant.parse("2030-01-01T12:00:00Z"), "홍대", "카페", 3);
	}
}
