package cloud.bamsongi.albammate.assistant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentProposal;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

@SpringBootTest(properties = {
	"app.assistant.enabled=true",
	"app.assistant.provider=fake",
	"app.assistant.no-retention-verified=true",
	"app.assistant.no-training-verified=true",
	"app.assistant.policy-version=OPENAI-POLICY-2026-08",
	"app.assistant.policy-url=https://openai.com/policies/api-data-usage-policies",
	"app.assistant.store=false"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AssistantRecommendationValidationHttpIntegrationTest {

	private static final String PRIVATE_MECHANISM_CODE = "PRIVATE_AI_904_VALIDATION";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@MockitoBean
	private AssistantIntentExtractor assistantIntentExtractor;

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("delete from game_mechanisms where code = ?", PRIVATE_MECHANISM_CODE);
	}

	@Test
	void T5_존재하지_않는_카테고리는_후보_조회_전에_검증오류로_거절한다() throws Exception {
		User user = grantUser("assistant-validation-category@example.com", "카테고리 검증 사용자");
		givenRecommendWithoutProviderStyles();

		mockMvc.perform(recommendationPost(
			"{\"message\":\"게임 추천\",\"conditions\":{\"categories\":[\"NOT_REAL\"]}}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
	}

	@Test
	void T5_비공개_메커니즘은_공개_카탈로그_검증에서_거절한다() throws Exception {
		jdbcTemplate.update("""
			insert into game_mechanisms
				(bgg_mechanism_id, code, name_ko, name_en, description_ko, is_public,
				 source_reference, reviewed_by, reviewed_at, created_at, updated_at)
			values (990904001, ?, '비공개 검증', 'Private validation', '검증용', false,
				 'test', null, null, current_timestamp, current_timestamp)
			""", PRIVATE_MECHANISM_CODE);
		User user = grantUser("assistant-validation-mechanism@example.com", "메커니즘 검증 사용자");
		givenRecommendWithoutProviderStyles();

		mockMvc.perform(recommendationPost(
			"{\"message\":\"게임 추천\",\"conditions\":{\"mechanisms\":[\"PRIVATE_AI_904_VALIDATION\"]}}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
	}

	@Test
	void T5_존재하지_않는_gameId는_게임_카탈로그_검증에서_거절한다() throws Exception {
		User user = grantUser("assistant-validation-game@example.com", "게임 ID 검증 사용자");
		givenRecommendWithoutProviderStyles();

		mockMvc.perform(recommendationPost(
			"{\"message\":\"게임 추천\",\"conditions\":{\"gameId\":999999999}}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
	}

	private User grantUser(String email, String nickname) throws Exception {
		User user = userRepository.saveAndFlush(User.create(email, "{bcrypt}hash", nickname));
		mockMvc.perform(put("/api/assistant/consent")
			.contentType("application/json")
			.content("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk());
		return user;
	}

	private void givenRecommendWithoutProviderStyles() {
		given(assistantIntentExtractor.extract(any())).willReturn(new AssistantIntentExtraction(
			AssistantIntentStatus.SUCCESS,
			new AssistantIntentProposal("RECOMMEND", java.util.List.of()),
			null,
			false));
	}

	private MockHttpServletRequestBuilder recommendationPost(String body) {
		return post("/api/assistant/recommendations")
			.contentType("application/json")
			.content(body);
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticationFor(long userId) {
		return authentication(
			UsernamePasswordAuthenticationToken.authenticated(
				new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}
}
