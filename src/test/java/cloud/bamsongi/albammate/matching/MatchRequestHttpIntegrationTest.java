package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;

@SpringBootTest(classes = AlbamMateApplication.class, properties = "spring.datasource.url=jdbc:h2:mem:match-request-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class MatchRequestHttpIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 현재_상태와_요청_등록_취소는_인증_CSRF_입력_경계를_지키고_대기_범위를_보존한다() throws Exception {
		long userId = insertUser();

		mockMvc.perform(get("/api/matches/current"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		mockMvc.perform(post("/api/matches/requests")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", "first-request")
			.content("{\"minPlayers\":2,\"maxPlayers\":4}")
			.with(authenticationFor(userId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

		mockMvc.perform(post("/api/matches/requests")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", "first-request")
			.content("{\"minPlayers\":2,\"maxPlayers\":4}")
			.with(authenticationFor(userId)).with(csrf()))
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.state").value("WAITING"))
			.andExpect(jsonPath("$.data.request.minPlayers").value(2))
			.andExpect(jsonPath("$.data.request.maxPlayers").value(4))
			.andExpect(jsonPath("$.data.proposal").doesNotExist());

		mockMvc.perform(delete("/api/matches/requests/me").with(authenticationFor(userId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").doesNotExist());
	}

	@Test
	void 멱등성_키는_ASCII_printable_범위와_공백을_검증하고_재생_충돌_만료교체를_구분한다() throws Exception {
		long userId = insertUser();

		for (String invalidKey : new String[] {"", " leading", "trailing ", "한글", "\u007f", "a".repeat(101)}) {
			mockMvc.perform(post("/api/matches/requests")
				.contentType(MediaType.APPLICATION_JSON)
				.header("Idempotency-Key", invalidKey)
				.content("{\"minPlayers\":1,\"maxPlayers\":1}")
				.with(authenticationFor(userId)).with(csrf()))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		}

		MvcResult first = create(userId, "same-key", 2, 3).andExpect(status().isCreated()).andReturn();
		MvcResult replay = create(userId, "same-key", 2, 3).andExpect(status().isOk()).andReturn();
		Object firstRequest = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(),
			"$.data.request");
		Object replayRequest = com.jayway.jsonpath.JsonPath.read(replay.getResponse().getContentAsString(),
			"$.data.request");
		assertEquals(firstRequest, replayRequest);
		create(userId, "same-key", 1, 1)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ErrorCode.IDEMPOTENCY_KEY_CONFLICT.getCode()));

		jdbcTemplate.update(
			"update match_idempotency_records set expires_at = ? where user_id = ? and idempotency_key = ?",
			Instant.now().minus(1, ChronoUnit.SECONDS), userId, "same-key");
		jdbcTemplate.update("update match_requests set status = 'CANCELED' where user_id = ?", userId);
		create(userId, "same-key", 1, 1)
			.andExpect(status().isCreated())
			.andExpect(jsonPath("$.data.request.minPlayers").value(1));
	}

	@Test
	void 현재_요청이나_PREPARING_ACTIVE_참가_관계가_있으면_동시_등록도_하나의_현재_흐름으로_수렴한다() throws Exception {
		long waitingUserId = insertUser();
		create(waitingUserId, "waiting", 1, 2).andExpect(status().isCreated());
		create(waitingUserId, "another", 1, 2)
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ErrorCode.MATCH_REQUEST_ALREADY_ACTIVE.getCode()));

		for (String partyStatus : new String[] {"PREPARING", "ACTIVE"}) {
			long participantUserId = insertUser();
			long partyId = insertParty(partyStatus);
			insertParticipant(partyId, participantUserId);
			create(participantUserId, "party-" + partyStatus, 1, 2)
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value(ErrorCode.MATCH_REQUEST_ALREADY_ACTIVE.getCode()));
		}
	}

	@Test
	void 열린_제안은_프로필_이미지와_인원만_공개하고_응답_경로의_인증_CSRF_입력_멱등성_경계를_지킨다() throws Exception {
		long firstUserId = insertUser();
		long secondUserId = insertUser();
		jdbcTemplate.update("update users set profile_image_url = ? where id = ?", "https://example.com/first.png",
			firstUserId);
		jdbcTemplate.update("update users set profile_image_url = ? where id = ?", "https://example.com/second.png",
			secondUserId);
		long firstRequestId = insertProposedRequest(firstUserId);
		long secondRequestId = insertProposedRequest(secondUserId);
		long proposalId = insertOpenProposal();
		insertProposalMember(proposalId, firstRequestId, firstUserId);
		insertProposalMember(proposalId, secondRequestId, secondUserId);

		mockMvc.perform(get("/api/matches/current").with(authenticationFor(firstUserId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("PROPOSED"))
			.andExpect(jsonPath("$.data.proposal.proposalId").value(proposalId))
			.andExpect(jsonPath("$.data.proposal.partySize").value(2))
			.andExpect(jsonPath("$.data.proposal.members[0].profileImageUrl").exists())
			.andExpect(jsonPath("$.data.proposal.members[0].userId").doesNotExist())
			.andExpect(jsonPath("$.data.proposal.members[0].nickname").doesNotExist())
			.andExpect(jsonPath("$.data.proposal.members[0].email").doesNotExist())
			.andExpect(jsonPath("$.data.proposal.members[0].minPlayers").doesNotExist());

		mockMvc.perform(post("/api/matches/proposals/{proposalId}/responses", proposalId)
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", "proposal-accept")
			.content("{\"action\":\"ACCEPT\"}")
			.with(authenticationFor(firstUserId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

		mockMvc.perform(post("/api/matches/proposals/{proposalId}/responses", 0)
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", "proposal-accept")
			.content("{\"action\":\"ACCEPT\"}")
			.with(authenticationFor(firstUserId)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));

		mockMvc.perform(post("/api/matches/proposals/{proposalId}/responses", proposalId)
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", "proposal-accept")
			.content("{\"action\":\"ACCEPT\"}")
			.with(authenticationFor(firstUserId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").value("PROPOSED"))
			.andExpect(jsonPath("$.data.proposal.myResponse").value("ACCEPTED"));
	}

	@Test
	void ACTIVE_Party의_명시적_나가기는_인증_CSRF_현재_참가자를_확인하고_마지막_나가기로_종료한다() throws Exception {
		long firstUserId = insertUser();
		long secondUserId = insertUser();
		long partyId = insertParty("ACTIVE");
		insertParticipant(partyId, firstUserId);
		insertParticipant(partyId, secondUserId);

		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", partyId))
			.andExpect(status().isUnauthorized());
		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", partyId)
			.with(authenticationFor(firstUserId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));

		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", partyId)
			.with(authenticationFor(firstUserId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").doesNotExist());
		assertEquals("ACTIVE", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, partyId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_party_participants where party_id = ? and left_at is not null", Integer.class,
			partyId));
		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", partyId)
			.with(authenticationFor(firstUserId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").doesNotExist());

		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", partyId)
			.with(authenticationFor(secondUserId)).with(csrf()))
			.andExpect(status().isOk());
		assertEquals("CLOSED", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, partyId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_parties where id = ? and closed_at is not null and purge_after is not null",
			Integer.class, partyId));
		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", partyId)
			.with(authenticationFor(secondUserId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").doesNotExist());
	}

	@Test
	void Party_나가기는_없는_Party와_비참가자_Party를_모두_FORBIDDEN으로_숨긴다() throws Exception {
		long userId = insertUser();
		long otherUserId = insertUser();
		long activePartyId = insertParty("ACTIVE");
		insertParticipant(activePartyId, otherUserId);
		long preparingPartyId = insertParty("PREPARING");

		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", 999_999L)
			.with(authenticationFor(userId)).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", activePartyId)
			.with(authenticationFor(userId)).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", preparingPartyId)
			.with(authenticationFor(userId)).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
	}

	@Test
	void Party_나가기는_기한이_지난_ACTIVE_Party를_남은_참가자_수와_무관하게_CLOSED로_보정한다() throws Exception {
		long leavingUserId = insertUser();
		long remainingUserId = insertUser();
		long partyId = insertParty("ACTIVE");
		insertParticipant(partyId, leavingUserId);
		insertParticipant(partyId, remainingUserId);
		jdbcTemplate.update("update match_parties set closes_at = ? where id = ?", Instant.now().minusSeconds(1), partyId);

		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", partyId)
			.with(authenticationFor(leavingUserId)).with(csrf()))
			.andExpect(status().isOk());

		assertEquals("CLOSED", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, partyId));
	}

	@Test
	void Party_나가기는_PREPARING_현재_참가자에게만_나가기_불가를_반환한다() throws Exception {
		long userId = insertUser();
		long preparingPartyId = insertParty("PREPARING");
		insertParticipant(preparingPartyId, userId);

		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", preparingPartyId)
			.with(authenticationFor(userId)).with(csrf()))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.code").value(ErrorCode.MATCH_PARTY_LEAVE_NOT_AVAILABLE.getCode()));
	}

	@Test
	void 예약_종료된_자기_CLOSED_Party_나가기는_상태와_보존시각을_바꾸지_않고_성공한다() throws Exception {
		long userId = insertUser();
		long partyId = insertParty("ACTIVE");
		insertParticipant(partyId, userId);
		Instant closedAt = Instant.now().minusSeconds(10);
		Instant purgeAfter = closedAt.plusSeconds(604_800);
		jdbcTemplate.update("update match_parties set status = 'CLOSED', closed_at = ?, purge_after = ? where id = ?",
			closedAt, purgeAfter, partyId);
		Instant storedPurgeAfter = jdbcTemplate.queryForObject(
			"select purge_after from match_parties where id = ?", Instant.class, partyId);

		mockMvc.perform(delete("/api/matches/parties/{partyId}/participants/me", partyId)
			.with(authenticationFor(userId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.state").doesNotExist());

		assertEquals("CLOSED", jdbcTemplate.queryForObject(
			"select status from match_parties where id = ?", String.class, partyId));
		assertEquals(storedPurgeAfter, jdbcTemplate.queryForObject(
			"select purge_after from match_parties where id = ?", Instant.class, partyId));
	}

	private org.springframework.test.web.servlet.ResultActions create(long userId, String key, int minPlayers,
		int maxPlayers)
		throws Exception {
		return mockMvc.perform(post("/api/matches/requests")
			.contentType(MediaType.APPLICATION_JSON)
			.header("Idempotency-Key", key)
			.content("{\"minPlayers\":" + minPlayers + ",\"maxPlayers\":" + maxPlayers + "}")
			.with(authenticationFor(userId)).with(csrf()));
	}

	private long insertUser() {
		String email = "match-request-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, ?, ?, ?, ?)",
			email, "hash", "사용자", Instant.now(), Instant.now());
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long insertParty(String status) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
			status, now, "ACTIVE".equals(status) ? now : null, now.plusSeconds(86_400), now, now);
		return jdbcTemplate.queryForObject("select max(id) from match_parties", Long.class);
	}

	private void insertParticipant(long partyId, long userId) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) values (?, ?, ?, ?)",
			partyId, userId, UUID.randomUUID(), Instant.now());
	}

	private long insertProposedRequest(long userId) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, proposed_at, created_at, updated_at) values (?, ?, ?, 'PROPOSED', ?, ?, ?, ?, ?)",
			userId, 2, 2, now, now, now, now, now);
		return jdbcTemplate.queryForObject("select max(id) from match_requests", Long.class);
	}

	private long insertOpenProposal() {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_proposals (party_size, status, respond_by, created_at, updated_at) values (?, 'OPEN', ?, ?, ?)",
			2, now.plusSeconds(30), now, now);
		return jdbcTemplate.queryForObject("select max(id) from match_proposals", Long.class);
	}

	private void insertProposalMember(long proposalId, long requestId, long userId) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_proposal_members (proposal_id, match_request_id, user_id, response_status, created_at, updated_at) values (?, ?, ?, 'PENDING', ?, ?)",
			proposalId, requestId, userId, now, now);
	}

	private org.springframework.test.web.servlet.request.RequestPostProcessor authenticationFor(long userId) {
		return authentication(new UsernamePasswordAuthenticationToken(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}
}
