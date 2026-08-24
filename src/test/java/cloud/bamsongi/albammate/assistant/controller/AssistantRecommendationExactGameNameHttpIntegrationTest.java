package cloud.bamsongi.albammate.assistant.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;
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

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentProposal;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
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

	private static final String TEST_EMAIL = "exact-game@example.com";
	private static final long TEST_BGG_ID = 9_600_001L;

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
		Long userId = jdbcTemplate.query(
			"select id from users where email = ?", resultSet -> resultSet.next() ? resultSet.getLong(1) : null,
			TEST_EMAIL);
		if (userId != null) {
			jdbcTemplate.update("delete from assistant_drafts where user_id = ?", userId);
			jdbcTemplate.update("delete from assistant_consents where user_id = ?", userId);
			jdbcTemplate.update("delete from participations where user_id = ?", userId);
			jdbcTemplate.update("delete from users where id = ?", userId);
		}
		jdbcTemplate.update("delete from games where bgg_id between ? and ?", TEST_BGG_ID, TEST_BGG_ID + 1);
	}

	@Test
	void T1_인증_CSRF_동의_뒤_유일_정식명은_provider와_초안없이_후보DTO를_반환한다() throws Exception {
		User user = userRepository.saveAndFlush(User.create(TEST_EMAIL, "{bcrypt}hash", "정확 게임 사용자"));
		long gameId = insertGame(TEST_BGG_ID, "카 탄", null, "공개 설명", "상세 설명");
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

	@Test
	void T2_인증_CSRF_동의_뒤_문장에_포함된_유일한_정식명은_provider없이_후보를_반환한다() throws Exception {
		User user = userRepository.saveAndFlush(User.create(TEST_EMAIL, "{bcrypt}hash", "정확 게임 사용자"));
		long gameId = insertGame(TEST_BGG_ID, "카탄", null, "공개 설명", "상세 설명");
		grant(user);

		mockMvc.perform(post("/api/assistant/recommendations")
			.contentType("application/json")
			.content("{\"message\":\"카탄 모임 만들어줘\"}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("RECOMMENDED"))
			.andExpect(jsonPath("$.data.conditions.gameId").value(gameId))
			.andExpect(jsonPath("$.data.candidates[0].id").value(gameId));

		verifyNoInteractions(assistantIntentExtractor);
	}

	@Test
	void T3_인증_CSRF_동의_뒤_복수_정규화_매치는_provider로_한번만_fallback하고_direct_후보를_만들지_않는다()
		throws Exception {
		User user = userRepository.saveAndFlush(User.create(TEST_EMAIL, "{bcrypt}hash", "정확 게임 사용자"));
		insertGame(TEST_BGG_ID, "카 탄", null, "공개 설명", "상세 설명");
		insertGame(TEST_BGG_ID + 1, "카\u3000탄", null, "다른 공개 설명", "다른 상세 설명");
		grant(user);
		when(assistantIntentExtractor.extract(org.mockito.ArgumentMatchers.any()))
			.thenReturn(new AssistantIntentExtraction(
				AssistantIntentStatus.SUCCESS, new AssistantIntentProposal("RECOMMEND", java.util.List.of()), null,
				false));

		mockMvc.perform(post("/api/assistant/recommendations")
			.contentType("application/json")
			.content("{\"message\":\"  카\\u3000탄  \"}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("NEEDS_INPUT"))
			.andExpect(jsonPath("$.data.conditions.gameId").isEmpty())
			.andExpect(jsonPath("$.data.candidates").isEmpty());

		org.mockito.Mockito.verify(assistantIntentExtractor).extract(org.mockito.ArgumentMatchers.any());
		verifyNoMoreInteractions(assistantIntentExtractor);
		org.junit.jupiter.api.Assertions.assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from assistant_drafts where user_id = ?", Integer.class,
				user.getId()));
	}

	private long insertGame(long bggId, String name, String imageUrl, String description, String detailDescription) {
		jdbcTemplate.update(
			"""
				insert into games (bgg_id, name, english_name, image_url, supported_player_count, tag, estimated_play_time, description, detail_description, created_at, updated_at)
				values (?, ?, 'Catan', ?, '3~4명', '전략', '60분', ?, ?, current_timestamp, current_timestamp)
				""",
			bggId, name, imageUrl, description, detailDescription);
		return jdbcTemplate.queryForObject("select id from games where bgg_id = ?", Long.class, bggId);
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
