package cloud.bamsongi.albammate.assistant.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.entity.User;
import cloud.bamsongi.albammate.user.repository.UserRepository;

/** AI-03 공개 초안 경계의 T1~T6 계약을 HTTP에서 직접 고정한다. */
@SpringBootTest(properties = {
	"app.assistant.enabled=true",
	"app.assistant.no-retention-verified=true",
	"app.assistant.no-training-verified=true",
	"app.assistant.policy-version=OPENAI-POLICY-2026-08",
	"app.assistant.policy-url=https://openai.com/policies/api-data-usage-policies"
})
@AutoConfigureMockMvc
class AssistantDraftHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private UserRepository userRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.update(
			"delete from assistant_idempotency_records where user_id in (select id from users where email like 'assistant-draft-%@example.com')");
		jdbcTemplate.update(
			"delete from assistant_drafts where user_id in (select id from users where email like 'assistant-draft-%@example.com')");
		jdbcTemplate.update(
			"delete from chat_rooms where room_id in (select id from rooms where host_user_id in (select id from users where email like 'assistant-draft-%@example.com'))");
		jdbcTemplate.update(
			"delete from participations where room_id in (select id from rooms where host_user_id in (select id from users where email like 'assistant-draft-%@example.com'))");
		jdbcTemplate.update(
			"delete from rooms where host_user_id in (select id from users where email like 'assistant-draft-%@example.com')");
		jdbcTemplate.update(
			"delete from assistant_consents where user_id in (select id from users where email like 'assistant-draft-%@example.com')");
		jdbcTemplate.update("delete from users where email like 'assistant-draft-%@example.com'");
	}

	@Test
	void T1_초안은_인증_CSRF_소유권과_terminal_상태를_보호한다() throws Exception {
		mockMvc.perform(get("/api/assistant/drafts/999"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		mockMvc.perform(get("/api/assistant/drafts/999").with(authenticationFor(1L)))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_NOT_FOUND"));

		User owner = grantedUser("t1-owner");
		long draftId = createDraft(owner, validDraftJson());
		User other = grantedUser("t1-other");
		mockMvc.perform(get("/api/assistant/drafts/" + draftId).with(authenticationFor(other.getId())))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_NOT_FOUND"));
		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/assistant/drafts/" + draftId)
				.with(authenticationFor(owner.getId())))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
	}

	@Test
	void T1_CONFIRMED_초안의_수정과_폐기는_409이고_결과를_바꾸지_않는다() throws Exception {
		User user = grantedUser("t1-terminal");
		long draftId = createDraft(user, validDraftJsonWithPlace());
		mockMvc
			.perform(post("/api/assistant/drafts/" + draftId + "/confirm").header("Idempotency-Key", "t1-terminal-key")
				.contentType("application/json").content("{\"draftVersion\":0}").with(authenticationFor(user.getId()))
				.with(csrf()))
			.andExpect(status().isCreated());
		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/api/assistant/drafts/" + draftId)
				.contentType("application/json").content("{\"draftVersion\":0,\"place\":\"다른 장소\"}")
				.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_CONFLICT"));
		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/assistant/drafts/" + draftId)
				.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_CONFLICT"));
		mockMvc.perform(get("/api/assistant/drafts/" + draftId).with(authenticationFor(user.getId())))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("CONFIRMED"));
	}

	@Test
	void T2_완성된_CREATE_ROOM_입력만_ACTIVE_초안을_만든다() throws Exception {
		User user = grantedUser("t2");
		mockMvc.perform(post("/api/assistant/drafts")
			.contentType("application/json")
			.content(validDraftJson())
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.status").value("ACTIVE"))
			.andExpect(jsonPath("$.data.input.place").isEmpty());
	}

	@Test
	void T2_필수_입력_누락은_draft_Room_ChatRoom_참가관계를_만들지_않는다() throws Exception {
		User user = grantedUser("t2-missing");
		mockMvc.perform(post("/api/assistant/drafts").contentType("application/json")
			.content("{\"roomType\":\"PERSON_FOCUSED\"}").with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isBadRequest());
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from assistant_drafts where user_id = ?",
			Integer.class, user.getId()));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class,
			user.getId()));
		assertEquals(0,
			jdbcTemplate.queryForObject(
				"select count(*) from chat_rooms where room_id in (select id from rooms where host_user_id = ?)",
				Integer.class, user.getId()));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from participations where user_id = ?",
			Integer.class, user.getId()));
	}

	@Test
	void T3_새_초안은_기존_ACTIVE를_DISCARDED로_종결한다() throws Exception {
		User user = grantedUser("t3");
		long firstDraftId = createDraft(user, validDraftJson());
		long secondDraftId = createDraft(user, validDraftJson().replace("AI 초안 방", "다음 AI 초안 방"));

		mockMvc.perform(get("/api/assistant/drafts/" + firstDraftId).with(authenticationFor(user.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("DISCARDED"));
		mockMvc.perform(get("/api/assistant/drafts/" + secondDraftId).with(authenticationFor(user.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.status").value("ACTIVE"));
	}

	@Test
	void T3_명시_폐기는_반복해도_성공하고_CONFIRMED_초안은_거절한다() throws Exception {
		User user = grantedUser("t3-delete");
		long draftId = createDraft(user, validDraftJson());
		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/assistant/drafts/" + draftId)
				.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk());
		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.delete("/api/assistant/drafts/" + draftId)
				.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk());
	}

	@Test
	void T3_lazy_만료는_410이고_Room과_초안_상태를_바꾸지_않는다() throws Exception {
		User user = grantedUser("t3-expired");
		long draftId = createDraft(user, validDraftJsonWithPlace());
		jdbcTemplate.update("update assistant_drafts set expires_at = ? where id = ?",
			java.time.Instant.parse("2020-01-01T00:00:00Z"), draftId);

		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/api/assistant/drafts/" + draftId)
				.contentType("application/json").content("{\"draftVersion\":0,\"place\":\"다른 카페\"}")
				.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_EXPIRED"));
		assertEquals("ACTIVE",
			jdbcTemplate.queryForObject("select status from assistant_drafts where id = ?", String.class, draftId));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class,
			user.getId()));
	}

	@Test
	void T4_확인_전_장소_PATCH와_지역_기본값을_적용한다() throws Exception {
		User user = grantedUser("t4-patch");
		long draftId = createDraft(user, validDraftJson().replace(",\"region\":\"강남\"", ""));
		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/api/assistant/drafts/" + draftId)
				.contentType("application/json").content("{\"draftVersion\":0,\"place\":\"카페\"}")
				.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.input.region").value("홍대"))
			.andExpect(jsonPath("$.data.input.place").value("카페"));
	}

	@Test
	void T4_ACTIVE_초안은_장소_외_허용_입력도_PATCH하고_버전을_증가시킨다() throws Exception {
		User user = grantedUser("t4-full-patch");
		long draftId = createDraft(user, validDraftJson());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/" + draftId)
			.contentType("application/json")
			.content(
				"{\"draftVersion\":0,\"title\":\"수정한 AI 초안 방\",\"description\":\"수정 설명\",\"recruitmentCapacity\":4}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.draftVersion").value(1))
			.andExpect(jsonPath("$.data.input.title").value("수정한 AI 초안 방"))
			.andExpect(jsonPath("$.data.input.description").value("수정 설명"))
			.andExpect(jsonPath("$.data.input.recruitmentCapacity").value(4));
	}

	@Test
	void T5_같은_멱등키_confirm은_하나의_Room과_ChatRoom만_반환한다() throws Exception {
		User user = grantedUser("t5");
		long draftId = createDraft(user, validDraftJsonWithPlace());
		String first = mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "T5-idempotency-key")
			.contentType("application/json").content("{\"draftVersion\":0}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		Number roomId = com.jayway.jsonpath.JsonPath.read(first, "$.data.roomId");

		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "T5-idempotency-key")
			.contentType("application/json").content("{\"draftVersion\":0}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.roomId").value(roomId.longValue()));
		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "T5-different-key")
			.contentType("application/json").content("{\"draftVersion\":0}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_CONFLICT"));
	}

	@Test
	void T5_confirm의_누락된_draftVersion은_400이고_Room과_ChatRoom을_만들지_않는다() throws Exception {
		User user = grantedUser("t5-missing-version");
		long draftId = createDraft(user, validDraftJsonWithPlace());

		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "missing-draft-version-key").contentType("application/json").content("{}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class,
			user.getId()));
		assertEquals(0,
			jdbcTemplate.queryForObject(
				"select count(*) from chat_rooms where room_id in (select id from rooms where host_user_id = ?)",
				Integer.class, user.getId()));
	}

	@Test
	void T5_오래된_version과_범위_밖_키는_Room을_새로_만들지_않는다() throws Exception {
		User user = grantedUser("t5-stale");
		long draftId = createDraft(user, validDraftJsonWithPlace());
		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
				.patch("/api/assistant/drafts/" + draftId)
				.contentType("application/json").content("{\"draftVersion\":0,\"place\":\"수정 카페\"}")
				.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk());
		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "stale-version-key").contentType("application/json")
			.content("{\"draftVersion\":0}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_CONFLICT"));
		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", " bad-key").contentType("application/json").content("{\"draftVersion\":1}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class,
			user.getId()));
		assertEquals(0,
			jdbcTemplate.queryForObject(
				"select count(*) from chat_rooms where room_id in (select id from rooms where host_user_id = ?)",
				Integer.class, user.getId()));
	}

	@Test
	void T5_만료된_초안은_오래된_version이어도_410이다() throws Exception {
		User user = grantedUser("t5-expired-stale");
		long draftId = createDraft(user, validDraftJsonWithPlace());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/" + draftId)
			.contentType("application/json").content("{\"draftVersion\":0,\"place\":\"수정 카페\"}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk());
		jdbcTemplate.update("update assistant_drafts set expires_at = ? where id = ?",
			java.time.Instant.parse("2020-01-01T00:00:00Z"), draftId);

		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "expired-stale-version-key").contentType("application/json")
			.content("{\"draftVersion\":0}").with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_EXPIRED"));
	}

	@Test
	void T5_같은_키라도_다른_draftVersion_재생은_409이다() throws Exception {
		User user = grantedUser("t5-replay-version");
		long draftId = createDraft(user, validDraftJsonWithPlace());
		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "same-key-different-version").contentType("application/json")
			.content("{\"draftVersion\":0}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isCreated());
		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "same-key-different-version").contentType("application/json")
			.content("{\"draftVersion\":1}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_CONFLICT"));
	}

	@Test
	void T6_철회는_ACTIVE_초안을_종결하고_확인을_차단한다() throws Exception {
		User user = grantedUser("t6-revoke");
		long draftId = createDraft(user, validDraftJsonWithPlace());
		mockMvc
			.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/assistant/consent")
				.contentType("application/json").content("{\"decision\":\"REVOKE\"}")
				.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk());
		mockMvc.perform(get("/api/assistant/drafts/" + draftId).with(authenticationFor(user.getId())))
			.andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("DISCARDED"));
		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "revoked-draft-key").contentType("application/json")
			.content("{\"draftVersion\":0}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_CONFLICT"));
	}

	/*
	 * T2의 생성 경계는 place null을 허용한다. 이후 T3/T4는 그 ACTIVE 상태를 terminal 전이와
	 * 입력 보완으로 직접 검증한다.
	 */
	@Test
	void T2_완성된_CREATE_ROOM_입력만_ACTIVE_초안을_만든다_보조_회귀() throws Exception {
		User user = grantedUser("t2-regression");
		mockMvc.perform(post("/api/assistant/drafts")
			.contentType("application/json")
			.content(validDraftJson())
			.with(authenticationFor(user.getId()))
			.with(csrf()))
			.andExpect(status().isCreated());
	}

	private static String validDraftJson() {
		return "{\"roomType\":\"PERSON_FOCUSED\",\"title\":\"AI 초안 방\","
			+ "\"experienceLevel\":\"ALL_LEVELS\",\"isRulemasterLed\":false,"
			+ "\"startsAt\":\"2030-01-01T12:00:00Z\",\"region\":\"강남\","
			+ "\"recruitmentCapacity\":3}";
	}

	private static String validDraftJsonWithPlace() {
		return validDraftJson().replace("\"recruitmentCapacity\":3}", "\"place\":\"카페\",\"recruitmentCapacity\":3}");
	}

	private User grantedUser(String suffix) throws Exception {
		User user = userRepository
			.saveAndFlush(User.create("assistant-draft-" + suffix + "@example.com", "{bcrypt}hash", "초안 사용자"));
		mockMvc.perform(consentPut().with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isOk());
		return user;
	}

	private long createDraft(User user, String body) throws Exception {
		String response = mockMvc.perform(post("/api/assistant/drafts").contentType("application/json").content(body)
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		return ((Number)com.jayway.jsonpath.JsonPath.read(response, "$.data.draftId")).longValue();
	}

	private static MockHttpServletRequestBuilder consentPut() {
		return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/assistant/consent")
			.contentType("application/json")
			.content("{\"decision\":\"GRANT\",\"consentVersion\":\"AI-01-CONSENT-V1\"}");
	}

	private static org.springframework.test.web.servlet.request.RequestPostProcessor authenticationFor(long userId) {
		return authentication(UsernamePasswordAuthenticationToken.authenticated(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}
}
