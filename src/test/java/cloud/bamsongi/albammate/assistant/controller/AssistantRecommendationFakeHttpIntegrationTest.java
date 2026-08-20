package cloud.bamsongi.albammate.assistant.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import cloud.bamsongi.albammate.game.contract.AssistantGameCandidateQuery;
import cloud.bamsongi.albammate.game.contract.GameSummary;
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
class AssistantRecommendationFakeHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserRepository userRepository;

	@MockitoBean
	private AssistantGameCandidateQuery assistantGameCandidateQuery;

	@Test
	void T5_fake_provider는_조건이_없으면_NEEDS_INPUT만_반환하고_후보를_조회하지_않는다() throws Exception {
		User user = userRepository.saveAndFlush(
			User.create("assistant-fake-needs-input@example.com", "{bcrypt}hash", "fake 추가질문 사용자"));
		grant(user);

		mockMvc.perform(recommendationPost("{\"message\":\"게임 추천해줘\"}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("NEEDS_INPUT"))
			.andExpect(jsonPath("$.data.missingFields[0]").value("GAME_STYLE"))
			.andExpect(jsonPath("$.data.missingFields.length()").value(1))
			.andExpect(jsonPath("$.data.candidates").isEmpty());

		verifyNoInteractions(assistantGameCandidateQuery);
	}

	@Test
	void T5_fake_provider는_지원하지_않는_요청을_UNSUPPORTED로_종결한다() throws Exception {
		User user = userRepository.saveAndFlush(
			User.create("assistant-fake-unsupported@example.com", "{bcrypt}hash", "fake 미지원 사용자"));
		grant(user);

		mockMvc.perform(recommendationPost("{\"message\":\"지원하지 않는 요청\"}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("UNSUPPORTED"))
			.andExpect(jsonPath("$.data.candidates").isEmpty());

		verifyNoInteractions(assistantGameCandidateQuery);
	}

	@Test
	void T5_fake_provider의_후속_요청은_클라이언트가_반환한_조건을_보존해_후보를_조회한다() throws Exception {
		User user = userRepository.saveAndFlush(
			User.create("assistant-fake-follow-up@example.com", "{bcrypt}hash", "fake 후속 사용자"));
		grant(user);
		given(assistantGameCandidateQuery.findCandidates(any())).willReturn(
			java.util.List.of(new GameSummary(101L, 9101L, "후속 후보")));

		mockMvc.perform(recommendationPost(
			"{\"message\":\"다른 조건\",\"conditions\":{\"categories\":[\"STRATEGY\"]}}")
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("RECOMMENDED"))
			.andExpect(jsonPath("$.data.conditions.categories[0]").value("STRATEGY"))
			.andExpect(jsonPath("$.data.candidates[0].id").value(101))
			.andExpect(jsonPath("$.data.candidates[0].name").value("후속 후보"));

		verify(assistantGameCandidateQuery).findCandidates(
			new AssistantGameCandidateQuery.Criteria(java.util.List.of("STRATEGY")));
	}

	private MockHttpServletRequestBuilder recommendationPost(String body) {
		return post("/api/assistant/recommendations")
			.contentType("application/json")
			.content(body);
	}

	private void grant(User user) throws Exception {
		mockMvc.perform(put("/api/assistant/consent")
			.contentType("application/json")
			.content("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}")
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
