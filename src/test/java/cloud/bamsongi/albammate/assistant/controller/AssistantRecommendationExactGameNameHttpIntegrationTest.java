package cloud.bamsongi.albammate.assistant.controller;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
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
class AssistantRecommendationExactGameNameHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@MockitoBean
	private AssistantIntentExtractor assistantIntentExtractor;

	@Test
	void T1_인증_CSRF_동의_뒤_유일_정식명은_provider와_초안없이_후보DTO를_반환한다() throws Exception {
		User user = userRepository.saveAndFlush(User.create("exact-game@example.com", "{bcrypt}hash", "정확 게임 사용자"));
		long gameId = insertGame("카 탄", null, "공개 설명", "상세 설명");
		grant(user);

		mockMvc.perform(post("/api/assistant/recommendations")
			.contentType("application/json")
			.content("{\"message\":\"  카\\u3000탄  \"}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("RECOMMENDED"))
			.andExpect(jsonPath("$.data.conditions.gameId").value(gameId))
			.andExpect(jsonPath("$.data.candidates[0].id").value(gameId))
			.andExpect(jsonPath("$.data.candidates[0].name").value("카 탄"))
			.andExpect(jsonPath("$.data.candidates[0].imageUrl").isEmpty())
			.andExpect(jsonPath("$.data.candidates[0].description").value("공개 설명"));

		verifyNoInteractions(assistantIntentExtractor);
		org.junit.jupiter.api.Assertions.assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from assistant_drafts where user_id = ?", Integer.class,
				user.getId()));
		org.junit.jupiter.api.Assertions.assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class,
				user.getId()));
		org.junit.jupiter.api.Assertions.assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from chat_rooms", Integer.class));
		org.junit.jupiter.api.Assertions.assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from participations where user_id = ?", Integer.class,
				user.getId()));
	}

	private long insertGame(String name, String imageUrl, String description, String detailDescription) {
		jdbcTemplate.update(
			"""
				insert into games (bgg_id, name, english_name, image_url, supported_player_count, tag, estimated_play_time, description, detail_description, created_at, updated_at)
				values (?, ?, 'Catan', ?, '3~4명', '전략', '60분', ?, ?, current_timestamp, current_timestamp)
				""",
			9_600_001L, name, imageUrl, description, detailDescription);
		return jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, 9_600_001L);
	}

	private void grant(User user) throws Exception {
		mockMvc.perform(put("/api/assistant/consent").contentType("application/json")
			.content("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}")
			.with(authenticationFor(user.getId())).with(csrf())).andExpect(status().isOk());
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticationFor(long userId) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}
}
