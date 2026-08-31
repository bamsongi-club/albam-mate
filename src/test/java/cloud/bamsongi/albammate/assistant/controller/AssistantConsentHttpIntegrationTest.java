package cloud.bamsongi.albammate.assistant.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cloud.bamsongi.albammate.assistant.contract.AssistantConsentGate;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentProposal;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
import cloud.bamsongi.albammate.assistant.service.AssistantConsentProperties;
import cloud.bamsongi.albammate.assistant.service.AssistantIntentOrchestrationService;
import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.AssistantVocabularyQuery;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@SpringBootTest(properties = {
	"app.assistant.enabled=true",
	"app.assistant.no-retention-verified=false",
	"app.assistant.no-training-verified=true",
	"app.assistant.policy-version=OPENAI-POLICY-2026-08",
	"app.assistant.policy-url=https://openai.com/policies/api-data-usage-policies",
	"app.assistant.retention-mode=default-30d",
	"app.assistant.store=false"
})
@AutoConfigureMockMvc
class AssistantConsentHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private AssistantIntentOrchestrationService assistantIntentOrchestrationService;
	@Autowired
	private AssistantConsentGate assistantConsentGate;
	@Autowired
	private AssistantConsentProperties assistantConsentProperties;

	@MockitoBean
	private AssistantIntentExtractor assistantIntentExtractor;
	@MockitoBean
	private AssistantGameCandidateQuery assistantGameCandidateQuery;
	// 이 테스트는 인증·CSRF 경계와 위임을 검증한다. 어휘 해석은 game 모듈 책임이므로 통과 구현으로 고정한다.
	@MockitoBean
	private AssistantVocabularyQuery assistantVocabularyQuery;

	@org.junit.jupiter.api.BeforeEach
	void 어휘_해석은_넘긴_값을_그대로_돌려준다() {
		given(assistantVocabularyQuery.resolve(any(), any(), any())).willAnswer(
			invocation -> new AssistantVocabularyQuery.Resolved(invocation.getArgument(0), invocation.getArgument(1),
				invocation.getArgument(2)));
	}

	@Test
	void T1_비로그인_조회는_401이고_동의가_없는_인증_사용자는_NOT_GRANTED를_받는다() throws Exception {
		mockMvc.perform(get("/api/assistant/consent"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		mockMvc.perform(get("/api/assistant/consent").with(authenticationFor(11L)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("NOT_GRANTED"))
			.andExpect(jsonPath("$.data.provider").value("OPENAI"))
			.andExpect(jsonPath("$.data.store").value(false));

		assertEquals(0, countConsentRows(11L));
	}

	@Test
	void T2_CSRF_오류는_동의를_바꾸지_않고_유효한_GRANT는_정책과_retention_mode를_저장한다() throws Exception {
		User user = userRepository.saveAndFlush(User.create("assistant-t2@example.com", "{bcrypt}hash", "T2 사용자"));

		mockMvc.perform(consentPut("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}")
			.with(authenticationFor(user.getId())))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		assertEquals(0, countConsentRows(user.getId()));

		mockMvc.perform(consentPut("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}")
			.with(authenticationFor(user.getId()))
			.with(csrf().useInvalidToken()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		assertEquals(0, countConsentRows(user.getId()));

		mockMvc.perform(consentPut("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("GRANTED"))
			.andExpect(jsonPath("$.data.provider").value("OPENAI"))
			.andExpect(jsonPath("$.data.policyVersion").value("OPENAI-POLICY-2026-08"))
			.andExpect(jsonPath("$.data.policyUrl").value("https://openai.com/policies/api-data-usage-policies"))
			.andExpect(jsonPath("$.data.retentionMode").value("default-30d"))
			.andExpect(jsonPath("$.data.store").value(false));

		assertEquals(1, countConsentRows(user.getId()));
		String persisted = jdbcTemplate.queryForObject(
			"select status || '|' || consent_version || '|' || provider || '|' || policy_version || '|' || policy_url || '|' || retention_mode || '|' || store "
				+ "from assistant_consents where user_id = ?",
			String.class,
			user.getId());
		assertEquals(
			"GRANTED|AI-01-CONSENT-V1|OPENAI|OPENAI-POLICY-2026-08|https://openai.com/policies/api-data-usage-policies|default-30d|FALSE",
			persisted);
		assertTrue(assistantConsentGate.isGranted(user.getId()));
		assistantConsentGate.requireGranted(user.getId());
	}

	@Test
	void T3_zero_data_retention에서_default_30d로_바뀌면_기존_GRANT는_NOT_GRANTED가_되고_provider_진입을_차단한다() throws Exception {
		User user = userRepository
			.saveAndFlush(User.create("assistant-t3-retention@example.com", "{bcrypt}hash", "T3 사용자"));

		String originalRetentionMode = assistantConsentProperties.getRetentionMode();
		try {
			assistantConsentProperties.setRetentionMode("zero-data-retention");
			assistantConsentProperties.setNoRetentionVerified(true);
			grant(user);
			assistantConsentProperties.setRetentionMode("default-30d");
			assistantConsentProperties.setNoRetentionVerified(false);

			mockMvc.perform(get("/api/assistant/consent").with(authenticationFor(user.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("NOT_GRANTED"))
				.andExpect(jsonPath("$.data.retentionMode").value("default-30d"));
			assertFalse(assistantConsentGate.isGranted(user.getId()));
			BusinessException exception = assertThrows(
				BusinessException.class,
				() -> assistantIntentOrchestrationService.extract(
					user.getId(),
					AssistantIntentRequest.forUser(Long.toString(user.getId()), "협력 게임 추천", java.util.List.of())));
			assertEquals(ErrorCode.ASSISTANT_CONSENT_REQUIRED, exception.getErrorCode());
		} finally {
			assistantConsentProperties.setRetentionMode(originalRetentionMode);
			assistantConsentProperties.setNoRetentionVerified(false);
		}
	}

	@Test
	void T3_REVOKE는_활성_여부와_무관하게_철회되고_동의_없는_provider_진입은_fail_closed다() throws Exception {
		User user = userRepository.saveAndFlush(User.create("assistant-t3@example.com", "{bcrypt}hash", "T3 사용자"));

		mockMvc.perform(consentPut("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("GRANTED"));

		mockMvc.perform(consentPut("{\"decision\":\"REVOKE\"}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("REVOKED"))
			.andExpect(jsonPath("$.data.grantedAt").isEmpty())
			.andExpect(jsonPath("$.data.revokedAt").isNotEmpty());

		assertEquals("REVOKED", jdbcTemplate.queryForObject(
			"select status from assistant_consents where user_id = ?", String.class, user.getId()));
		BusinessException providerEntry = assertThrows(
			BusinessException.class,
			() -> assistantIntentOrchestrationService.extract(
				user.getId(),
				AssistantIntentRequest.forUser(Long.toString(user.getId()), "협력 게임 추천", java.util.List.of())));
		assertEquals(ErrorCode.ASSISTANT_CONSENT_REQUIRED, providerEntry.getErrorCode());
		assertFalse(assistantConsentGate.isGranted(user.getId()));
		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> assistantConsentGate.requireGranted(user.getId()));
		assertEquals(ErrorCode.ASSISTANT_CONSENT_REQUIRED, exception.getErrorCode());
		assertFalse(jdbcTemplate.queryForObject(
			"select store from assistant_consents where user_id = ?", Boolean.class, user.getId()));
	}

	@Test
	void T4_현재_동의문이_바뀌면_기존_GRANT는_무효화되고_조회는_현재_정책을_반환한다() throws Exception {
		User user = userRepository.saveAndFlush(User.create("assistant-t4@example.com", "{bcrypt}hash", "T4 사용자"));
		mockMvc.perform(consentPut("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk());

		String originalConsentVersion = assistantConsentProperties.getConsentVersion();
		String originalPolicyVersion = assistantConsentProperties.getPolicyVersion();
		String originalPolicyUrl = assistantConsentProperties.getPolicyUrl();
		try {
			assistantConsentProperties.setConsentVersion("AI-01-CONSENT-V2");
			assistantConsentProperties.setPolicyVersion("OPENAI-POLICY-2026-09");
			assistantConsentProperties.setPolicyUrl("https://openai.com/policies/api-data-usage-policies-v2");

			mockMvc.perform(get("/api/assistant/consent").with(authenticationFor(user.getId())))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("NOT_GRANTED"))
				.andExpect(jsonPath("$.data.consentVersion").value("AI-01-CONSENT-V2"))
				.andExpect(jsonPath("$.data.policyVersion").value("OPENAI-POLICY-2026-09"))
				.andExpect(jsonPath("$.data.policyUrl")
					.value("https://openai.com/policies/api-data-usage-policies-v2"));
			assertFalse(assistantConsentGate.isGranted(user.getId()));
			BusinessException exception = assertThrows(
				BusinessException.class,
				() -> assistantConsentGate.requireGranted(user.getId()));
			assertEquals(ErrorCode.ASSISTANT_CONSENT_REQUIRED, exception.getErrorCode());
		} finally {
			assistantConsentProperties.setConsentVersion(originalConsentVersion);
			assistantConsentProperties.setPolicyVersion(originalPolicyVersion);
			assistantConsentProperties.setPolicyUrl(originalPolicyUrl);
		}
	}

	@Test
	void T4_추천은_인증과_CSRF를_먼저_검증하고_유효_요청만_intent_extractor에_전달한다() throws Exception {
		User user = userRepository
			.saveAndFlush(User.create("assistant-recommendation-t4@example.com", "{bcrypt}hash", "T4 사용자"));
		mockMvc.perform(recommendationPost("협력 게임 추천"))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(recommendationPost("협력 게임 추천").with(authenticationFor(user.getId())))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		mockMvc.perform(recommendationPost("협력 게임 추천")
			.with(authenticationFor(user.getId()))
			.with(csrf().useInvalidToken()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		verifyNoInteractions(assistantIntentExtractor);

		grant(user);
		given(assistantIntentExtractor.extract(any())).willReturn(new AssistantIntentExtraction(
			AssistantIntentStatus.SUCCESS,
			new AssistantIntentProposal("RECOMMEND", java.util.List.of("STRATEGY")),
			null,
			false));
		given(assistantGameCandidateQuery.findCandidates(any())).willReturn(java.util.List.of());
		mockMvc.perform(recommendationPost("협력\\u0000게임")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isBadRequest());
		verifyNoInteractions(assistantIntentExtractor, assistantGameCandidateQuery);

		mockMvc.perform(recommendationPost("협력 게임 추천")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk());
		verify(assistantIntentExtractor).extract(any(AssistantIntentRequest.class));
		verify(assistantGameCandidateQuery).findCandidates(
			new AssistantGameCandidateQuery.Criteria(java.util.List.of("STRATEGY")));
	}

	@Test
	void T5_추천_조건이_없으면_NEEDS_INPUT과_GAME_STYLE만_반환한다() throws Exception {
		User user = userRepository
			.saveAndFlush(User.create("assistant-recommendation-t5@example.com", "{bcrypt}hash", "T5 사용자"));
		grant(user);
		given(assistantIntentExtractor.extract(any())).willReturn(new AssistantIntentExtraction(
			AssistantIntentStatus.SUCCESS,
			new AssistantIntentProposal("RECOMMEND", java.util.List.of()),
			null,
			false));

		mockMvc.perform(recommendationPost("게임 추천해줘")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("NEEDS_INPUT"))
			.andExpect(jsonPath("$.data.missingFields[0]").value("GAME_STYLE"))
			.andExpect(jsonPath("$.data.missingFields.length()").value(1))
			.andExpect(jsonPath("$.data.candidates.length()").value(0));
		verifyNoInteractions(assistantGameCandidateQuery);
	}

	@ParameterizedTest
	@MethodSource("intentFailureMappings")
	void T4_의도_추출_실패_상태는_공개_AI_오류_코드로_변환된다(
		AssistantIntentStatus intentStatus,
		ErrorCode errorCode) throws Exception {
		User user = userRepository.saveAndFlush(User.create(
			"assistant-error-" + intentStatus.name().toLowerCase(java.util.Locale.ROOT) + "@example.com",
			"{bcrypt}hash",
			"AI 오류 사용자"));
		grant(user);
		given(assistantIntentExtractor.extract(any())).willReturn(
			new AssistantIntentExtraction(intentStatus, null, null, false));

		mockMvc.perform(recommendationPost("전략 게임 추천")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().is(errorCode.getStatus()))
			.andExpect(jsonPath("$.code").value(errorCode.getCode()))
			.andExpect(jsonPath("$.message").value(errorCode.getMessage()));
	}

	private static Stream<Arguments> intentFailureMappings() {
		return Stream.of(
			Arguments.of(AssistantIntentStatus.NOT_ENABLED, ErrorCode.ASSISTANT_NOT_ENABLED),
			Arguments.of(AssistantIntentStatus.CONSENT_REQUIRED, ErrorCode.ASSISTANT_CONSENT_REQUIRED),
			Arguments.of(AssistantIntentStatus.SENSITIVE_INPUT_REJECTED, ErrorCode.ASSISTANT_INPUT_NOT_ALLOWED),
			Arguments.of(AssistantIntentStatus.SERVICE_UNAVAILABLE, ErrorCode.SERVICE_UNAVAILABLE),
			Arguments.of(AssistantIntentStatus.QUOTA_EXCEEDED, ErrorCode.RATE_LIMIT_EXCEEDED),
			Arguments.of(AssistantIntentStatus.COST_CAP_REACHED, ErrorCode.ASSISTANT_COST_LIMIT_EXCEEDED),
			Arguments.of(AssistantIntentStatus.PROVIDER_TIMEOUT, ErrorCode.ASSISTANT_PROVIDER_UNAVAILABLE),
			Arguments.of(AssistantIntentStatus.PROVIDER_RATE_LIMITED, ErrorCode.ASSISTANT_PROVIDER_UNAVAILABLE),
			Arguments.of(AssistantIntentStatus.PROVIDER_INPUT_TOO_LARGE, ErrorCode.ASSISTANT_PROVIDER_UNAVAILABLE),
			Arguments.of(AssistantIntentStatus.INVALID_PROVIDER_SCHEMA, ErrorCode.ASSISTANT_PROVIDER_RESPONSE_INVALID));
	}

	private int countConsentRows(long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from assistant_consents where user_id = ?", Integer.class, userId);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder consentPut(String body) {
		return put("/api/assistant/consent")
			.contentType("application/json")
			.content(body);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder recommendationPost(
		String message) {
		return post("/api/assistant/recommendations")
			.contentType("application/json")
			.content("{\"message\":\"" + message + "\"}");
	}

	private void grant(User user) throws Exception {
		mockMvc.perform(consentPut("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk());
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}
}
