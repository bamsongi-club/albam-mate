package cloud.bamsongi.albammate.matching;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;

@SpringBootTest(classes = AlbamMateApplication.class, properties = "spring.datasource.url=jdbc:h2:mem:match-block-test;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class MatchBlockHttpIntegrationTest {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void 차단_목록은_본인_관계와_공개_프로필만_페이지로_반환하고_입력을_검증한다() throws Exception {
		long blockerUserId = insertUser("차단자", "https://cdn.example.com/blocker.png");
		long blockedUserId = insertUser("차단대상", "https://cdn.example.com/blocked.png");
		long otherBlockerUserId = insertUser("다른차단자", "https://cdn.example.com/other.png");
		long otherBlockedUserId = insertUser("다른대상", "https://cdn.example.com/other-blocked.png");
		long ownBlockId = insertBlock(blockerUserId, blockedUserId);
		insertBlock(otherBlockerUserId, otherBlockedUserId);

		mockMvc.perform(get("/api/matches/blocks").with(authenticationFor(blockerUserId)))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data.page").value(0))
			.andExpect(jsonPath("$.data.size").value(10))
			.andExpect(jsonPath("$.data.totalElements").value(1))
			.andExpect(jsonPath("$.data.content[0].blockId").value(ownBlockId))
			.andExpect(jsonPath("$.data.content[0].blockedUser.nickname").value("차단대상"))
			.andExpect(jsonPath("$.data.content[0].blockedUser.profileImageUrl")
				.value("https://cdn.example.com/blocked.png"))
			.andExpect(jsonPath("$.data.content[0].blockedAt").exists())
			.andExpect(jsonPath("$.data.content[0].blockedUser.userId").doesNotExist())
			.andExpect(jsonPath("$.data.content[0].blockedUser.email").doesNotExist());

		mockMvc.perform(get("/api/matches/blocks").param("page", "-1").with(authenticationFor(blockerUserId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(get("/api/matches/blocks").param("size", "0").with(authenticationFor(blockerUserId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(get("/api/matches/blocks").param("size", "101").with(authenticationFor(blockerUserId)))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(get("/api/matches/blocks"))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
	}

	@Test
	void 보존중인_파티의_다른_참가자를_차단하고_권한과_참가자_오류를_구분한다() throws Exception {
		long requesterUserId = insertUser("요청자", null);
		long blockedUserId = insertUser("차단대상", "https://cdn.example.com/blocked.png");
		long outsiderUserId = insertUser("외부인", null);
		long partyId = insertActiveParty();
		UUID requesterRef = UUID.randomUUID();
		UUID blockedRef = UUID.randomUUID();
		insertParticipant(partyId, requesterUserId, requesterRef, FIXED_TIME.minusSeconds(60));
		insertParticipant(partyId, blockedUserId, blockedRef, null);

		mockMvc.perform(blockPut(partyId, blockedRef).with(authenticationFor(requesterUserId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.data.blockedUser.nickname").value("차단대상"))
			.andExpect(jsonPath("$.data.blockedUser.userId").doesNotExist());
		assertBlockCount(requesterUserId, blockedUserId, 1);

		String expectedBlocks = blockSnapshot();
		mockMvc.perform(blockPut(partyId, blockedRef))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);

		mockMvc.perform(blockPut(partyId, blockedRef).with(authenticationFor(requesterUserId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);
		mockMvc.perform(blockPut(partyId, blockedRef)
			.with(authenticationFor(requesterUserId)).with(csrf().useInvalidToken()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);

		mockMvc.perform(put("/api/matches/parties/{partyId}/participants/{participantRef}/block", "not-a-number", blockedRef)
			.with(authenticationFor(requesterUserId)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);

		mockMvc.perform(blockPut(0L, blockedRef).with(authenticationFor(requesterUserId)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);

		mockMvc.perform(put("/api/matches/parties/{partyId}/participants/{participantRef}/block", partyId, "not-a-uuid")
			.with(authenticationFor(requesterUserId)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);

		mockMvc.perform(blockPut(9_999_999L, UUID.randomUUID()).with(authenticationFor(outsiderUserId)).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);

		mockMvc.perform(blockPut(partyId, blockedRef).with(authenticationFor(outsiderUserId)).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);
		mockMvc.perform(blockPut(partyId, UUID.randomUUID()).with(authenticationFor(requesterUserId)).with(csrf()))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.code").value(ErrorCode.MATCH_PARTICIPANT_NOT_FOUND.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);
		mockMvc.perform(blockPut(partyId, requesterRef).with(authenticationFor(requesterUserId)).with(csrf()))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
		assertBlockSnapshotEquals(expectedBlocks);
		assertBlockCount(requesterUserId, requesterUserId, 0);
	}

	@Test
	void 같은_대상_차단은_반복해도_한_관계로_수렴하고_파티를_바꾸지_않는다() throws Exception {
		long requesterUserId = insertUser("요청자", null);
		long blockedUserId = insertUser("차단대상", null);
		long partyId = insertActiveParty();
		UUID requesterRef = UUID.randomUUID();
		UUID blockedRef = UUID.randomUUID();
		insertParticipant(partyId, requesterUserId, requesterRef, null);
		insertParticipant(partyId, blockedUserId, blockedRef, null);

		mockMvc.perform(blockPut(partyId, blockedRef).with(authenticationFor(requesterUserId)).with(csrf()))
			.andExpect(status().isOk());
		mockMvc.perform(blockPut(partyId, blockedRef).with(authenticationFor(requesterUserId)).with(csrf()))
			.andExpect(status().isOk());

		assertBlockCount(requesterUserId, blockedUserId, 1);
		Integer activePartyCount = jdbcTemplate.queryForObject(
			"select count(*) from match_parties where id = ? and status = 'ACTIVE'", Integer.class, partyId);
		org.junit.jupiter.api.Assertions.assertEquals(1, activePartyCount);
	}

	@Test
	void 차단_해제는_본인_관계만_삭제하고_반복과_타인_소유를_같은_성공으로_처리한다() throws Exception {
		long ownerUserId = insertUser("소유자", null);
		long ownerBlockedUserId = insertUser("소유자대상", null);
		long otherUserId = insertUser("다른소유자", null);
		long otherBlockedUserId = insertUser("다른대상", null);
		long ownerBlockId = insertBlock(ownerUserId, ownerBlockedUserId);
		long otherBlockId = insertBlock(otherUserId, otherBlockedUserId);

		mockMvc.perform(delete("/api/matches/blocks/{blockId}", ownerBlockId)
			.with(authenticationFor(ownerUserId)).with(csrf()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.status").value(200))
			.andExpect(jsonPath("$.data").isMap())
			.andExpect(jsonPath("$.data").isEmpty());
		mockMvc.perform(delete("/api/matches/blocks/{blockId}", ownerBlockId)
			.with(authenticationFor(ownerUserId)).with(csrf()))
			.andExpect(status().isOk());
		mockMvc.perform(delete("/api/matches/blocks/{blockId}", otherBlockId)
			.with(authenticationFor(ownerUserId)).with(csrf()))
			.andExpect(status().isOk());
		assertBlockCount(ownerUserId, ownerBlockedUserId, 0);
		assertBlockCount(otherUserId, otherBlockedUserId, 1);

		mockMvc.perform(delete("/api/matches/blocks/{blockId}", "not-a-number")
			.with(authenticationFor(ownerUserId)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(delete("/api/matches/blocks/{blockId}", 0L)
			.with(authenticationFor(ownerUserId)).with(csrf()))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.code").value(ErrorCode.VALIDATION_ERROR.getCode()));
		mockMvc.perform(delete("/api/matches/blocks/{blockId}", otherBlockId).with(authenticationFor(ownerUserId)))
			.andExpect(status().isForbidden())
			.andExpect(jsonPath("$.code").value(ErrorCode.CSRF_TOKEN_INVALID.getCode()));
		mockMvc.perform(delete("/api/matches/blocks/{blockId}", otherBlockId))
			.andExpect(status().isUnauthorized())
			.andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
		assertBlockCount(otherUserId, otherBlockedUserId, 1);
	}

	private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder blockPut(
		long partyId, UUID participantRef) {
		return put("/api/matches/parties/{partyId}/participants/{participantRef}/block", partyId, participantRef);
	}

	private long insertUser(String nickname, String profileImageUrl) {
		String email = "match-block-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, profile_image_url, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
			email, "hash", nickname, profileImageUrl, FIXED_TIME, FIXED_TIME);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long insertActiveParty() {
		jdbcTemplate.update(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?)",
			"ACTIVE", FIXED_TIME, FIXED_TIME, FIXED_TIME.plusSeconds(3_600), FIXED_TIME, FIXED_TIME);
		return jdbcTemplate.queryForObject("select max(id) from match_parties", Long.class);
	}

	private void insertParticipant(long partyId, long userId, UUID participantRef, Instant leftAt) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, left_at, created_at) values (?, ?, ?, ?, ?)",
			partyId, userId, participantRef, leftAt, FIXED_TIME);
	}

	private long insertBlock(long blockerUserId, long blockedUserId) {
		jdbcTemplate.update(
			"insert into match_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, ?)",
			blockerUserId, blockedUserId, FIXED_TIME);
		return jdbcTemplate.queryForObject(
			"select id from match_blocks where blocker_user_id = ? and blocked_user_id = ?",
			Long.class, blockerUserId, blockedUserId);
	}

	private void assertBlockCount(long blockerUserId, long blockedUserId, int expectedCount) {
		Integer blockCount = jdbcTemplate.queryForObject(
			"select count(*) from match_blocks where blocker_user_id = ? and blocked_user_id = ?",
			Integer.class, blockerUserId, blockedUserId);
		org.junit.jupiter.api.Assertions.assertEquals(expectedCount, blockCount);
	}

	private String blockSnapshot() {
		return jdbcTemplate.queryForList(
			"select id, blocker_user_id, blocked_user_id, created_at from match_blocks order by id")
			.toString();
	}

	private void assertBlockSnapshotEquals(String expectedBlocks) {
		org.junit.jupiter.api.Assertions.assertEquals(expectedBlocks, blockSnapshot());
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(new UsernamePasswordAuthenticationToken(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}
}
