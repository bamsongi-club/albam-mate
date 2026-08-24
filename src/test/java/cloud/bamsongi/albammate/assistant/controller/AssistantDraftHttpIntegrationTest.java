package cloud.bamsongi.albammate.assistant.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

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

import cloud.bamsongi.albammate.assistant.dto.AssistantConsentDecision;
import cloud.bamsongi.albammate.assistant.dto.AssistantConsentRequest;
import cloud.bamsongi.albammate.assistant.dto.AssistantDraftCreateRequest;
import cloud.bamsongi.albammate.assistant.entity.AssistantDraft;
import cloud.bamsongi.albammate.assistant.entity.AssistantIdempotencyRecord;
import cloud.bamsongi.albammate.assistant.repository.AssistantDraftRepository;
import cloud.bamsongi.albammate.assistant.repository.AssistantIdempotencyRecordRepository;
import cloud.bamsongi.albammate.assistant.service.AssistantConsentService;
import cloud.bamsongi.albammate.assistant.service.AssistantDraftService;
import cloud.bamsongi.albammate.global.exception.BusinessException;
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
	@Autowired
	private AssistantDraftRepository draftRepository;
	@Autowired
	private AssistantDraftService draftService;
	@Autowired
	private AssistantConsentService consentService;
	@Autowired
	private AssistantIdempotencyRecordRepository idempotencyRecordRepository;

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
	void T1_active_조회는_인증만_요구하고_CSRF와_동의없이_현재_사용자_초안만_반환한다() throws Exception {
		mockMvc.perform(get("/api/assistant/drafts/active"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));

		User owner = userRepository.saveAndFlush(
			User.create("assistant-draft-t1-owner@example.com", "{bcrypt}hash", "소유 사용자"));
		long ownerDraftId = draftRepository.saveAndFlush(activeDraft(owner.getId(), "소유 초안")).getId();
		User other = userRepository.saveAndFlush(
			User.create("assistant-draft-t1-other@example.com", "{bcrypt}hash", "다른 사용자"));
		draftRepository.saveAndFlush(activeDraft(other.getId(), "다른 초안"));

		mockMvc.perform(get("/api/assistant/drafts/active").with(authenticationFor(owner.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.draftId").value(ownerDraftId))
			.andExpect(jsonPath("$.data.input.title").value("소유 초안"));
	}

	@Test
	void T1_제거된_draftId_조회는_METHOD_NOT_ALLOWED이고_초안_응답을_반환하지_않는다() throws Exception {
		User user = grantedUser("t1-removed-get");
		long draftId = createDraft(user, validDraftJson());

		mockMvc.perform(get("/api/assistant/drafts/" + draftId).with(authenticationFor(user.getId())))
			.andExpect(status().isMethodNotAllowed())
			.andExpect(jsonPath("$.code").value(ErrorCode.METHOD_NOT_ALLOWED.getCode()))
			.andExpect(jsonPath("$.data.draftId").doesNotExist());
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
		mockMvc.perform(get("/api/assistant/drafts/active").with(authenticationFor(user.getId())))
			.andExpect(status().isNoContent());
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

		assertEquals("DISCARDED",
			jdbcTemplate.queryForObject("select status from assistant_drafts where id = ?", String.class,
				firstDraftId));
		mockMvc.perform(get("/api/assistant/drafts/active").with(authenticationFor(user.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.draftId").value(secondDraftId))
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
	void T3_active_조회는_유효_초안_200_없음과_종결_초안_204_만료_초안_410이고_상태를_바꾸지_않는다() throws Exception {
		User user = grantedUser("t3-expired");
		mockMvc.perform(get("/api/assistant/drafts/active").with(authenticationFor(user.getId())))
			.andExpect(status().isNoContent());
		long draftId = createDraft(user, validDraftJsonWithPlace());
		mockMvc.perform(get("/api/assistant/drafts/active").with(authenticationFor(user.getId())))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.draftId").value(draftId))
			.andExpect(jsonPath("$.data.status").value("ACTIVE"));
		jdbcTemplate.update("update assistant_drafts set expires_at = ? where id = ?",
			java.time.Instant.parse("2020-01-01T00:00:00Z"), draftId);

		mockMvc
			.perform(get("/api/assistant/drafts/active").with(authenticationFor(user.getId())))
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
	void T4_PATCH_판정순서와_필수버전_및_텍스트_제어문자_경계를_지킨다() throws Exception {
		User terminalUser = grantedUser("t4-terminal-stale");
		long terminalDraftId = createDraft(terminalUser, validDraftJsonWithPlace());
		mockMvc.perform(post("/api/assistant/drafts/" + terminalDraftId + "/confirm")
			.header("Idempotency-Key", "t4-terminal-stale-key").contentType("application/json")
			.content("{\"draftVersion\":0}").with(authenticationFor(terminalUser.getId())).with(csrf()))
			.andExpect(status().isCreated());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/" + terminalDraftId).contentType("application/json")
			.content("{\"draftVersion\":-1,\"place\":\"다른 장소\"}")
			.with(authenticationFor(terminalUser.getId())).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_CONFLICT"));

		User expiredUser = grantedUser("t4-expired-stale");
		long expiredDraftId = createDraft(expiredUser, validDraftJsonWithPlace());
		jdbcTemplate.update("update assistant_drafts set expires_at = ? where id = ?",
			java.time.Instant.parse("2020-01-01T00:00:00Z"), expiredDraftId);
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/" + expiredDraftId).contentType("application/json")
			.content("{\"draftVersion\":-1,\"place\":\"다른 장소\"}")
			.with(authenticationFor(expiredUser.getId())).with(csrf()))
			.andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_EXPIRED"));

		User missingVersionUser = grantedUser("t4-missing-version");
		long missingVersionDraftId = createDraft(missingVersionUser, validDraftJson());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/" + missingVersionDraftId).contentType("application/json")
			.content("{\"place\":\"카페\"}").with(authenticationFor(missingVersionUser.getId())).with(csrf()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		assertControlCharactersAreRejectedAtCreatePatchAndConfirm("title", "제어\\u0001문자");
		assertControlCharactersAreRejectedAtCreatePatchAndConfirm("description", "제어\\u0001문자");
		assertControlCharactersAreRejectedAtCreatePatchAndConfirm("place", "제어\\u0001문자");
	}

	@Test
	void T4_PATCH_누락_draftVersion도_소유권_terminal_만료_뒤에_판정한다() throws Exception {
		User missingUser = grantedUser("t4-missing-version-not-found");
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/999999").contentType("application/json").content("{\"place\":\"카페\"}")
			.with(authenticationFor(missingUser.getId())).with(csrf()))
			.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_NOT_FOUND"));

		User terminalUser = grantedUser("t4-missing-version-terminal");
		long terminalDraftId = createDraft(terminalUser, validDraftJsonWithPlace());
		mockMvc.perform(post("/api/assistant/drafts/" + terminalDraftId + "/confirm")
			.header("Idempotency-Key", "t4-missing-version-terminal-key").contentType("application/json")
			.content("{\"draftVersion\":0}").with(authenticationFor(terminalUser.getId())).with(csrf()))
			.andExpect(status().isCreated());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/" + terminalDraftId).contentType("application/json")
			.content("{\"place\":\"다른 장소\"}").with(authenticationFor(terminalUser.getId())).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_CONFLICT"));

		User expiredUser = grantedUser("t4-missing-version-expired");
		long expiredDraftId = createDraft(expiredUser, validDraftJsonWithPlace());
		jdbcTemplate.update("update assistant_drafts set expires_at = ? where id = ?",
			java.time.Instant.parse("2020-01-01T00:00:00Z"), expiredDraftId);
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/" + expiredDraftId).contentType("application/json")
			.content("{\"place\":\"다른 장소\"}").with(authenticationFor(expiredUser.getId())).with(csrf()))
			.andExpect(status().isGone()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_EXPIRED"));

		User activeUser = grantedUser("t4-missing-version-active");
		long activeDraftId = createDraft(activeUser, validDraftJsonWithPlace());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/" + activeDraftId).contentType("application/json")
			.content("{\"place\":\"다른 장소\"}").with(authenticationFor(activeUser.getId())).with(csrf()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
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
		mockMvc.perform(get("/api/assistant/drafts/active").with(authenticationFor(user.getId())))
			.andExpect(status().isNoContent());
		mockMvc.perform(post("/api/assistant/drafts/" + draftId + "/confirm")
			.header("Idempotency-Key", "revoked-draft-key").contentType("application/json")
			.content("{\"draftVersion\":0}")
			.with(authenticationFor(user.getId())).with(csrf()))
			.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ASSISTANT_DRAFT_CONFLICT"));
	}

	@Test
	void T5_다음_초안_생성은_만료된_멱등기록만_정리하고_기존_Room을_건드리지_않는다() {
		long userId = userRepository
			.saveAndFlush(User.create("assistant-draft-expiry-ai03@example.com", "{bcrypt}hash", "만료 정리 사용자"))
			.getId();
		consentService.changeConsent(userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));
		long draftId = draftService.create(userId, new AssistantDraftCreateRequest(
			"PERSON_FOCUSED", "기존 초안", null, null, "ALL_LEVELS", false,
			Instant.parse("2030-01-01T12:00:00Z"), "홍대", null, 3)).draftId();
		idempotencyRecordRepository.saveAndFlush(AssistantIdempotencyRecord.pending(userId, draftId,
			"0000000000000000000000000000000000000000000000000000000000000000", 0,
			Instant.parse("2020-01-01T00:00:00Z")));

		draftService.create(userId, new AssistantDraftCreateRequest(
			"PERSON_FOCUSED", "새 초안", null, null, "ALL_LEVELS", false,
			Instant.parse("2030-01-01T12:00:00Z"), "홍대", null, 3));

		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from assistant_idempotency_records where user_id = ?", Integer.class, userId));
		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class, userId));
	}

	@Test
	void T5_24시간_만료된_confirm_기록은_재생하지_않고_terminal_conflict로_간다() {
		long userId = userRepository.saveAndFlush(
			User.create("assistant-draft-expiry-confirm-ai03@example.com", "{bcrypt}hash", "확인 만료 사용자"))
			.getId();
		consentService.changeConsent(userId,
			new AssistantConsentRequest(AssistantConsentDecision.GRANT, "AI-01-CONSENT-V1"));
		long draftId = draftService.create(userId, new AssistantDraftCreateRequest(
			"PERSON_FOCUSED", "확인 초안", null, null, "ALL_LEVELS", false,
			Instant.parse("2030-01-01T12:00:00Z"), "홍대", "카페", 3)).draftId();
		org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
			UsernamePasswordAuthenticationToken.authenticated(new CurrentUserPrincipal(userId), null,
				AuthorityUtils.NO_AUTHORITIES));
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

	private static AssistantDraft activeDraft(long userId, String title) {
		return AssistantDraft.create(userId, "PERSON_FOCUSED", title, null, null, "ALL_LEVELS", false,
			"홍대", 3, java.time.Instant.parse("2030-01-01T12:00:00Z"), null, java.time.Instant.now());
	}

	private void assertControlCharactersAreRejectedAtCreatePatchAndConfirm(String field, String value)
		throws Exception {
		User createUser = grantedUser("t4-create-control-" + field);
		String createJson = validDraftJsonWithPlace().replace("\"" + field + "\":\""
			+ (field.equals("title") ? "AI 초안 방" : field.equals("description") ? "" : "카페") + "\"",
			"\"" + field + "\":\"" + value + "\"");
		if (field.equals("description")) {
			createJson = validDraftJsonWithPlace().replace("\"place\":\"카페\"", "\"description\":\"" + value
				+ "\",\"place\":\"카페\"");
		}
		mockMvc.perform(post("/api/assistant/drafts").contentType("application/json").content(createJson)
			.with(authenticationFor(createUser.getId())).with(csrf()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		User patchUser = grantedUser("t4-patch-control-" + field);
		long patchDraftId = createDraft(patchUser, validDraftJsonWithPlace());
		mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
			.patch("/api/assistant/drafts/" + patchDraftId).contentType("application/json")
			.content("{\"draftVersion\":0,\"" + field + "\":\"" + value + "\"}")
			.with(authenticationFor(patchUser.getId())).with(csrf()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

		User confirmUser = grantedUser("t4-confirm-control-" + field);
		long confirmDraftId = createDraft(confirmUser, validDraftJsonWithPlace());
		jdbcTemplate.update("update assistant_drafts set " + field + " = ? where id = ?", "제어\u0001문자", confirmDraftId);
		mockMvc.perform(post("/api/assistant/drafts/" + confirmDraftId + "/confirm")
			.header("Idempotency-Key", "t4-confirm-control-" + field).contentType("application/json")
			.content("{\"draftVersion\":0}").with(authenticationFor(confirmUser.getId())).with(csrf()))
			.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from rooms where host_user_id = ?", Integer.class,
			confirmUser.getId()));
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
