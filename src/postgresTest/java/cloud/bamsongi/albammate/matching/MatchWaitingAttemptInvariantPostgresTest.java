package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.dto.MatchRequestCreateRequest;
import cloud.bamsongi.albammate.matching.recovery.MatchPreparingRecoveryExecutor;
import cloud.bamsongi.albammate.matching.recovery.MatchRetentionCleanupExecutor;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseService;
import cloud.bamsongi.albammate.matching.service.command.MatchRequestCommandService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class, properties = "spring.task.scheduling.enabled=false")
class MatchWaitingAttemptInvariantPostgresTest extends SharedPostgresIntegrationSupport {
	private static final Instant FIXTURE_NOW = Instant.parse("2026-01-01T00:00:00Z");
	private static final Instant EXPIRED_PROPOSAL_TIME = Instant.parse("2025-01-02T00:00:00Z");
	private static final Instant EXPIRED_PREPARING_TIME = Instant.parse("2025-01-03T00:00:00Z");
	private static final Instant DIRECT_REQUEUE_RESPOND_BY = Instant.parse("2099-01-01T00:00:00Z");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private MatchRetentionCleanupExecutor retentionCleanup;
	@Autowired
	private MatchProposalResponseService proposalResponseService;
	@Autowired
	private MatchPreparingRecoveryExecutor preparingRecovery;
	@Autowired
	private MatchRequestCommandService matchRequestCommandService;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table match_chat_messages, match_chat_rooms, match_proposal_members, "
			+ "match_proposals, match_party_participants, match_parties, match_requests, match_idempotency_records, "
			+ "users restart identity cascade");
	}

	@Test
	void 오래된_WAITING_요청은_시간_경과만으로_만료되거나_삭제되거나_상태가_변하지_않는다() {
		Instant originalAttemptTime = Instant.parse("2025-01-01T00:00:00Z");
		long requestId = insertRequest(insertUser("old-waiting"), "WAITING", originalAttemptTime);

		retentionCleanup.cleanUpExpiredRecords();

		assertRequestAttempt(requestId, "WAITING", originalAttemptTime);
	}

	@Test
	void 제안_만료와_PREPARING_복구_실패_자동_재대기는_기존_두_시각값을_보존한다() {
		Instant proposalAttemptTime = Instant.parse("2025-01-02T00:00:00Z");
		long proposalRequestId = insertRequest(insertUser("proposal-expired"), "PROPOSED", proposalAttemptTime);
		long proposalId = insertProposal("OPEN", EXPIRED_PROPOSAL_TIME);
		insertProposalMember(proposalId, proposalRequestId, requestUserId(proposalRequestId), "ACCEPTED");

		proposalResponseService.expireDueProposals();

		assertRequestAttempt(proposalRequestId, "WAITING", proposalAttemptTime);

		Instant preparingAttemptTime = Instant.parse("2025-01-03T00:00:00Z");
		long preparingRequestId = insertRequest(insertUser("preparing-expired"), "MATCHED", preparingAttemptTime);
		long preparingProposalId = insertProposal("CONFIRMED", FIXTURE_NOW);
		insertProposalMember(preparingProposalId, preparingRequestId, requestUserId(preparingRequestId), "ACCEPTED");
		long partyId = insertPreparingParty(preparingProposalId, EXPIRED_PREPARING_TIME);
		insertParticipant(partyId, requestUserId(preparingRequestId));

		preparingRecovery.recover(partyId);

		assertRequestAttempt(preparingRequestId, "WAITING", preparingAttemptTime);
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_parties where id = ?", Integer.class,
			partyId));
	}

	@Test
	void 사용자의_다시_찾기_직접_재대기_취소_후_새_요청은_새_두_시각값을_사용한다() {
		Instant directRequeueAttemptTime = Instant.parse("2025-01-04T00:00:00Z");
		long directRequeueRequestId = insertRequest(insertUser("direct-requeue"), "PROPOSED", directRequeueAttemptTime);
		long directRequeueProposalId = insertProposal("OPEN", DIRECT_REQUEUE_RESPOND_BY);
		insertProposalMember(directRequeueProposalId, directRequeueRequestId, requestUserId(directRequeueRequestId),
			"PENDING");

		proposalResponseService.respond(requestUserId(directRequeueRequestId), directRequeueProposalId,
			MatchProposalResponseAction.REQUEUE, "direct-requeue");

		assertNewRequestAttempt(directRequeueRequestId, directRequeueAttemptTime);

		Instant canceledAttemptTime = Instant.parse("2025-01-05T00:00:00Z");
		long cancelUserId = insertUser("cancel-and-create");
		insertRequest(cancelUserId, "WAITING", canceledAttemptTime);
		matchRequestCommandService.cancel(cancelUserId);
		matchRequestCommandService.create(cancelUserId, "cancel-and-create", new MatchRequestCreateRequest(2, 2));

		long canceledNewRequestId = jdbcTemplate.queryForObject(
			"select id from match_requests where user_id = ? and status = 'WAITING'", Long.class, cancelUserId);
		assertNewRequestAttempt(canceledNewRequestId, canceledAttemptTime);

		Instant pausedAttemptTime = Instant.parse("2025-01-06T00:00:00Z");
		long pausedUserId = insertUser("find-again");
		long pausedRequestId = insertRequest(pausedUserId, "PAUSED", pausedAttemptTime);

		matchRequestCommandService.create(pausedUserId, "find-again", new MatchRequestCreateRequest(2, 2));

		assertNewRequestAttempt(pausedRequestId, pausedAttemptTime);

		Instant rangeChangingAttemptTime = Instant.parse("2025-01-07T00:00:00Z");
		long rangeChangingUserId = insertUser("find-again-range-change");
		long oldPausedRequestId = insertRequest(
			rangeChangingUserId, "PAUSED", 2, 4, rangeChangingAttemptTime);

		CurrentMatchStateResponse rangeChanged = matchRequestCommandService.create(
			rangeChangingUserId, "find-again-range-change", new MatchRequestCreateRequest(3, 5)).response();

		assertEquals(3, rangeChanged.request().minPlayers());
		assertEquals(5, rangeChanged.request().maxPlayers());
		long rangeChangingNewRequestId = jdbcTemplate.queryForObject(
			"select id from match_requests where user_id = ? and status = 'WAITING'", Long.class, rangeChangingUserId);
		assertNotEquals(oldPausedRequestId, rangeChangingNewRequestId);
		assertEquals("CANCELED", requestStatus(oldPausedRequestId));
		assertEquals("WAITING", requestStatus(rangeChangingNewRequestId));
		assertRequestRange(rangeChangingNewRequestId, 3, 5);
		assertEquals(rangeChangingNewRequestId, jdbcTemplate.queryForObject(
			"select result_entity_id from match_idempotency_records where user_id = ? and idempotency_key = ?",
			Long.class, rangeChangingUserId, "find-again-range-change"));
		assertEquals("3:5", jdbcTemplate.queryForObject(
			"select payload_fingerprint from match_idempotency_records where user_id = ? and idempotency_key = ?",
			String.class, rangeChangingUserId, "find-again-range-change"));
	}

	private long insertUser(String role) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class, "match-t3-" + role + "-" + UUID.randomUUID() + "@example.com", role,
			Timestamp.from(FIXTURE_NOW), Timestamp.from(FIXTURE_NOW));
	}

	private long insertRequest(long userId, String status, Instant attemptTime) {
		return insertRequest(userId, status, 2, 2, attemptTime);
	}

	private long insertRequest(long userId, String status, int minPartySize, int maxPartySize, Instant attemptTime) {
		Timestamp attemptTimestamp = Timestamp.from(attemptTime);
		return jdbcTemplate.queryForObject(
			"""
				insert into match_requests
				(user_id, min_party_size, max_party_size, status, queued_at, priority_since, proposed_at, matched_at, created_at, updated_at)
				values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				returning id
				""",
			Long.class, userId, minPartySize, maxPartySize, status, attemptTimestamp, attemptTimestamp,
			"PROPOSED".equals(status) ? attemptTimestamp : null,
			"MATCHED".equals(status) ? attemptTimestamp : null,
			attemptTimestamp, attemptTimestamp);
	}

	private long insertProposal(String status, Instant respondBy) {
		return jdbcTemplate.queryForObject("""
			insert into match_proposals (party_size, status, respond_by, confirmed_at, created_at, updated_at)
			values (1, ?, ?, ?, ?, ?) returning id
			""", Long.class, status, Timestamp.from(respondBy),
			"CONFIRMED".equals(status) ? Timestamp.from(FIXTURE_NOW) : null,
			Timestamp.from(FIXTURE_NOW), Timestamp.from(FIXTURE_NOW));
	}

	private void insertProposalMember(long proposalId, long requestId, long userId, String responseStatus) {
		jdbcTemplate.update("""
			insert into match_proposal_members
			(proposal_id, match_request_id, user_id, response_status, responded_at, created_at, updated_at)
			values (?, ?, ?, ?, ?, ?, ?)
			""", proposalId, requestId, userId, responseStatus,
			"ACCEPTED".equals(responseStatus) ? Timestamp.from(FIXTURE_NOW) : null,
			Timestamp.from(FIXTURE_NOW), Timestamp.from(FIXTURE_NOW));
	}

	private long insertPreparingParty(long proposalId, Instant preparingStartedAt) {
		return jdbcTemplate.queryForObject("""
			insert into match_parties (proposal_id, status, preparing_started_at, created_at, updated_at)
			values (?, 'PREPARING', ?, ?, ?) returning id
			""", Long.class, proposalId, Timestamp.from(preparingStartedAt), Timestamp.from(preparingStartedAt),
			Timestamp.from(preparingStartedAt));
	}

	private void insertParticipant(long partyId, long userId) {
		jdbcTemplate.update("""
			insert into match_party_participants (party_id, user_id, participant_ref, created_at)
			values (?, ?, ?, ?)
			""", partyId, userId, UUID.randomUUID(), Timestamp.from(FIXTURE_NOW));
	}

	private long requestUserId(long requestId) {
		return jdbcTemplate.queryForObject("select user_id from match_requests where id = ?", Long.class, requestId);
	}

	private void assertRequestAttempt(long requestId, String expectedStatus, Instant expectedAttemptTime) {
		assertEquals(expectedStatus,
			jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId));
		assertEquals(expectedAttemptTime, requestTime(requestId, "queued_at"));
		assertEquals(expectedAttemptTime, requestTime(requestId, "priority_since"));
	}

	private void assertNewRequestAttempt(long requestId, Instant previousAttemptTime) {
		assertRequestAttemptIsWaiting(requestId);
		assertTrue(requestTime(requestId, "queued_at").isAfter(previousAttemptTime));
		assertTrue(requestTime(requestId, "priority_since").isAfter(previousAttemptTime));
	}

	private void assertRequestAttemptIsWaiting(long requestId) {
		assertEquals("WAITING",
			jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId));
	}

	private String requestStatus(long requestId) {
		return jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId);
	}

	private void assertRequestRange(long requestId, int expectedMinPartySize, int expectedMaxPartySize) {
		assertEquals(expectedMinPartySize, jdbcTemplate.queryForObject(
			"select min_party_size from match_requests where id = ?", Integer.class, requestId));
		assertEquals(expectedMaxPartySize, jdbcTemplate.queryForObject(
			"select max_party_size from match_requests where id = ?", Integer.class, requestId));
	}

	private Instant requestTime(long requestId, String columnName) {
		return jdbcTemplate
			.queryForObject("select " + columnName + " from match_requests where id = ?", Timestamp.class,
				requestId)
			.toInstant();
	}
}
