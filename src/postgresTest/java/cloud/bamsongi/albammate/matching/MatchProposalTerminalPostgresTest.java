package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.dto.MatchRequestCreateRequest;
import cloud.bamsongi.albammate.matching.entity.MatchPartyParticipant;
import cloud.bamsongi.albammate.matching.repository.MatchPartyParticipantRepository;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseService;
import cloud.bamsongi.albammate.matching.service.command.MatchRequestCommandService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(classes = {AlbamMateApplication.class, MatchProposalTerminalPostgresTest.ClockSkewConfiguration.class})
class MatchProposalTerminalPostgresTest extends SharedPostgresIntegrationSupport {
	private static final String MATCH_PROPOSALS_TABLE = "match_proposals";
	private static final String MATCH_REQUESTS_TABLE = "match_requests";
	private static final long TERMINAL_RETENTION_SECONDS = 604_800;

	@Autowired
	private MatchProposalResponseService matchProposalResponseService;
	@Autowired
	private MatchRequestCommandService matchRequestCommandService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private DataSource dataSource;
	@MockitoSpyBean
	private MatchPartyParticipantRepository participantRepository;

	@AfterEach
	void tearDown() {
		reset(participantRepository);
		jdbcTemplate.execute(
			"truncate table match_idempotency_records, match_proposal_members, match_proposals, match_party_participants, match_parties, match_requests, users restart identity cascade");
	}

	@Test
	void 재대기_뒤_새_열린_제안을_취소하면_과거_DECLINED_멤버와_충돌하지_않고_현재_제안만_종결한다() {
		long userId = insertUser("requeue-cancel");
		long requestId = insertRequest(userId);
		long declinedProposalId = insertOpenProposal();
		insertProposalMember(declinedProposalId, requestId, userId);
		jdbcTemplate.update("update match_proposals set status = 'DECLINED' where id = ?", declinedProposalId);
		long openProposalId = insertOpenProposal();
		insertProposalMember(openProposalId, requestId, userId);

		matchRequestCommandService.cancel(userId);

		assertEquals("DECLINED", proposalStatus(declinedProposalId));
		assertEquals("CANCELED", proposalStatus(openProposalId));
		assertEquals("CANCELED", requestStatus(requestId));
	}

	@Test
	void 활성_요청_등록_키로_존재하지_않는_Proposal에_응답하면_상태보다_멱등성_충돌을_먼저_반환한다() {
		long userId = insertUser("response-idempotency-priority");
		String idempotencyKey = "request-key";
		matchRequestCommandService.create(userId, idempotencyKey, new MatchRequestCreateRequest(2, 2));

		BusinessException exception = assertThrows(BusinessException.class, () -> matchProposalResponseService.respond(
			userId, 999_999L, MatchProposalResponseAction.ACCEPT, idempotencyKey));

		assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, exception.getErrorCode());
	}

	@Test
	void 만료_스캔은_due_OPEN_Proposal을_100건까지만_처리한다() {
		for (int index = 0; index < 101; index++) {
			insertOpenProposal();
		}
		jdbcTemplate.update("update match_proposals set respond_by = current_timestamp - interval '1 second'");

		matchProposalResponseService.expireDueProposals();

		assertEquals(100, jdbcTemplate.queryForObject(
			"select count(*) from match_proposals where status = 'EXPIRED'", Integer.class));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_proposals where status = 'OPEN'", Integer.class));
	}

	@Test
	void 한_Proposal_만료_실패는_앞서_독립_트랜잭션으로_완료한_Proposal을_롤백하지_않는다() {
		long completedProposalId = insertOpenProposal();
		long failedProposalId = insertOpenProposal();
		jdbcTemplate.update("update match_proposals set respond_by = current_timestamp - interval '1 second'");
		jdbcTemplate.execute("""
			create function fail_one_match_proposal_expiry() returns trigger language plpgsql as $$
			begin
				if new.id = %d then
					raise exception 'forced proposal expiry failure';
				end if;
				return new;
			end;
			$$
			""".formatted(failedProposalId));
		jdbcTemplate.execute("""
			create trigger fail_one_match_proposal_expiry_trigger
			before update on match_proposals
			for each row execute function fail_one_match_proposal_expiry()
			""");
		try {
			assertThrows(RuntimeException.class, matchProposalResponseService::expireDueProposals);
		} finally {
			jdbcTemplate.execute("drop trigger if exists fail_one_match_proposal_expiry_trigger on match_proposals");
			jdbcTemplate.execute("drop function if exists fail_one_match_proposal_expiry()");
		}

		assertEquals("EXPIRED", proposalStatus(completedProposalId));
		assertEquals("OPEN", proposalStatus(failedProposalId));
	}

	@Test
	void 응답과_종료_멱등성_기록은_왜곡된_애플리케이션_Clock이_아닌_PostgreSQL_시각을_쓴다() {
		long userId = insertUser("database-time");
		long requestId = insertRequest(userId);
		long proposalId = insertOpenProposal();
		insertProposalMember(proposalId, requestId, userId);
		Instant databaseBefore = jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class).toInstant();

		matchProposalResponseService.respond(userId, proposalId, MatchProposalResponseAction.ACCEPT,
			"database-time-response");

		Instant respondedAt = jdbcTemplate.queryForObject(
			"select responded_at from match_proposal_members where proposal_id = ? and match_request_id = ?",
			Timestamp.class, proposalId, requestId).toInstant();
		Instant idempotencyCreatedAt = jdbcTemplate.queryForObject(
			"select created_at from match_idempotency_records where user_id = ? and idempotency_key = ?",
			Timestamp.class, userId, "database-time-response").toInstant();
		assertTrue(!respondedAt.isBefore(databaseBefore));
		assertTrue(!idempotencyCreatedAt.isBefore(databaseBefore));
		assertTrue(respondedAt.isBefore(Instant.parse("2090-01-01T00:00:00Z")));
		assertTrue(idempotencyCreatedAt.isBefore(Instant.parse("2090-01-01T00:00:00Z")));
	}

	@Test
	void 마지막_ACCEPT는_Proposal_응답과_Party_참가자_요청_PREPARING을_한_번에_확정한다() {
		long firstUserId = insertUser("first");
		long secondUserId = insertUser("second");
		long firstRequestId = insertRequest(firstUserId);
		long secondRequestId = insertRequest(secondUserId);
		long proposalId = insertOpenProposal();
		insertProposalMember(proposalId, firstRequestId, firstUserId);
		insertProposalMember(proposalId, secondRequestId, secondUserId);

		matchProposalResponseService.respond(firstUserId, proposalId, MatchProposalResponseAction.ACCEPT,
			"first-accept");
		assertEquals("ACCEPTED", responseStatus(proposalId, firstRequestId));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_parties", Integer.class));

		matchProposalResponseService.respond(secondUserId, proposalId, MatchProposalResponseAction.ACCEPT,
			"second-accept");

		assertEquals("CONFIRMED", proposalStatus(proposalId));
		assertEquals("MATCHED", requestStatus(firstRequestId));
		assertEquals("MATCHED", requestStatus(secondRequestId));
		long partyId = jdbcTemplate.queryForObject("select id from match_parties", Long.class);
		assertEquals("PREPARING",
			jdbcTemplate.queryForObject("select status from match_parties where id = ?", String.class, partyId));
		assertEquals(2, jdbcTemplate.queryForObject("select count(*) from match_party_participants where party_id = ?",
			Integer.class, partyId));
	}

	@Test
	void terminal_요청과_제안은_종료_시각부터_7일_뒤에만_물리_정리_대상이_된다() {
		long canceledUserId = insertUser("retention-canceled-request");
		long canceledRequestId = insertWaitingRequest(canceledUserId);

		CurrentMatchStateResponse canceled = matchRequestCommandService.cancel(canceledUserId);

		assertPurgeAfterExactly(MATCH_REQUESTS_TABLE, canceledRequestId, canceled.operationTime());

		long firstUserId = insertUser("retention-confirmed-first");
		long secondUserId = insertUser("retention-confirmed-second");
		long firstRequestId = insertRequest(firstUserId);
		long secondRequestId = insertRequest(secondUserId);
		long confirmedProposalId = insertOpenProposal();
		insertProposalMember(confirmedProposalId, firstRequestId, firstUserId);
		insertProposalMember(confirmedProposalId, secondRequestId, secondUserId);
		matchProposalResponseService.respond(firstUserId, confirmedProposalId, MatchProposalResponseAction.ACCEPT,
			"retention-first");
		matchProposalResponseService.respond(secondUserId, confirmedProposalId, MatchProposalResponseAction.ACCEPT,
			"retention-second");

		Instant confirmedAt = terminalTime(MATCH_PROPOSALS_TABLE, "confirmed_at", confirmedProposalId);
		Instant firstMatchedAt = terminalTime(MATCH_REQUESTS_TABLE, "matched_at", firstRequestId);
		Instant secondMatchedAt = terminalTime(MATCH_REQUESTS_TABLE, "matched_at", secondRequestId);
		assertPurgeAfterExactly(MATCH_PROPOSALS_TABLE, confirmedProposalId, confirmedAt);
		assertPurgeAfterExactly(MATCH_REQUESTS_TABLE, firstRequestId, firstMatchedAt);
		assertPurgeAfterExactly(MATCH_REQUESTS_TABLE, secondRequestId, secondMatchedAt);
	}

	@Test
	void REQUEUE는_열린_제안을_즉시_종료하고_선택한_요청만_새_대기_시도로_되돌린다() {
		long requeueUserId = insertUser("requeue");
		long waitingUserId = insertUser("waiting");
		long requeueRequestId = insertRequest(requeueUserId);
		long waitingRequestId = insertRequest(waitingUserId);
		long proposalId = insertOpenProposal();
		insertProposalMember(proposalId, requeueRequestId, requeueUserId);
		insertProposalMember(proposalId, waitingRequestId, waitingUserId);

		matchProposalResponseService.respond(requeueUserId, proposalId, MatchProposalResponseAction.REQUEUE,
			"requeue-key");

		assertEquals("DECLINED", proposalStatus(proposalId));
		assertEquals("REQUEUED", responseStatus(proposalId, requeueRequestId));
		assertEquals("WAITING", requestStatus(requeueRequestId));
		assertEquals("WAITING", requestStatus(waitingRequestId));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_parties", Integer.class));
	}

	@Test
	void proposal_응답_멱등성은_같은_키_재생과_의미_충돌과_만료_후_교체를_구분한다() {
		long userId = insertUser("response-idempotency");
		long requestId = insertRequest(userId);
		long proposalId = insertOpenProposal();
		insertProposalMember(proposalId, requestId, userId);

		matchProposalResponseService.respond(userId, proposalId, MatchProposalResponseAction.ACCEPT, "response-key");
		assertEquals("MATCH_PROPOSAL", jdbcTemplate.queryForObject(
			"select result_entity_type from match_idempotency_records where user_id = ? and idempotency_key = ?",
			String.class, userId, "response-key"));
		assertDoesNotThrow(() -> matchProposalResponseService.respond(
			userId, proposalId, MatchProposalResponseAction.ACCEPT, "response-key"));
		BusinessException conflict = assertThrows(BusinessException.class, () -> matchProposalResponseService.respond(
			userId, proposalId, MatchProposalResponseAction.CANCEL, "response-key"));
		assertEquals(ErrorCode.IDEMPOTENCY_KEY_CONFLICT, conflict.getErrorCode());
		jdbcTemplate.update(
			"update match_idempotency_records set expires_at = ? where user_id = ? and idempotency_key = ?",
			Timestamp.from(Instant.now().minusSeconds(1)), userId, "response-key");
		BusinessException expiredReplacement = assertThrows(BusinessException.class,
			() -> matchProposalResponseService.respond(
				userId, proposalId, MatchProposalResponseAction.CANCEL, "response-key"));
		assertEquals(ErrorCode.MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE, expiredReplacement.getErrorCode());
	}

	@Test
	void CANCEL은_열린_제안을_종료하고_선택_요청만_CANCELED로_확정한다() {
		long cancelUserId = insertUser("cancel");
		long waitingUserId = insertUser("cancel-waiting");
		long cancelRequestId = insertRequest(cancelUserId);
		long waitingRequestId = insertRequest(waitingUserId);
		long proposalId = insertOpenProposal();
		insertProposalMember(proposalId, cancelRequestId, cancelUserId);
		insertProposalMember(proposalId, waitingRequestId, waitingUserId);

		matchProposalResponseService.respond(cancelUserId, proposalId, MatchProposalResponseAction.CANCEL,
			"cancel-key");

		assertEquals("CANCELED", proposalStatus(proposalId));
		assertEquals("CANCELED", responseStatus(proposalId, cancelRequestId));
		assertEquals("CANCELED", requestStatus(cancelRequestId));
		assertEquals("WAITING", requestStatus(waitingRequestId));
	}

	@Test
	void 응답_기한이_지난_OPEN_제안은_미응답을_PAUSED로_수락자를_WAITING으로_종료한다() {
		long acceptedUserId = insertUser("expired-accepted");
		long pendingUserId = insertUser("expired-pending");
		long acceptedRequestId = insertRequest(acceptedUserId);
		long pendingRequestId = insertRequest(pendingUserId);
		long proposalId = insertOpenProposal();
		insertProposalMember(proposalId, acceptedRequestId, acceptedUserId);
		insertProposalMember(proposalId, pendingRequestId, pendingUserId);
		jdbcTemplate.update(
			"update match_proposal_members set response_status = 'ACCEPTED', responded_at = ? where proposal_id = ? and match_request_id = ?",
			Timestamp.from(Instant.now().minusSeconds(2)), proposalId, acceptedRequestId);
		jdbcTemplate.update("update match_proposals set respond_by = ? where id = ?",
			Timestamp.from(Instant.now().minusSeconds(1)), proposalId);

		matchProposalResponseService.expireDueProposals();

		assertEquals("EXPIRED", proposalStatus(proposalId));
		assertEquals("WAITING", requestStatus(acceptedRequestId));
		assertEquals("PAUSED", requestStatus(pendingRequestId));
		assertEquals("EXPIRED", responseStatus(proposalId, pendingRequestId));
		assertEquals(null, jdbcTemplate.queryForObject(
			"select responded_at from match_proposal_members where proposal_id = ? and match_request_id = ?",
			Timestamp.class, proposalId, pendingRequestId));
	}

	@Test
	void Proposal_잠금_대기중_응답_기한을_넘긴_ACCEPT는_수락으로_확정하지_않는다() throws Exception {
		long userId = insertUser("deadline-lock-wait");
		long requestId = insertRequest(userId);
		long proposalId = insertOpenProposal();
		insertProposalMember(proposalId, requestId, userId);
		jdbcTemplate.update(
			"update match_proposals set respond_by = current_timestamp + interval '1 second' where id = ?",
			proposalId);

		try (var proposalLock = dataSource.getConnection()) {
			proposalLock.setAutoCommit(false);
			try (var statement = proposalLock
				.prepareStatement("select id from match_proposals where id = ? for update")) {
				statement.setLong(1, proposalId);
				statement.executeQuery();
			}
			var pool = Executors.newSingleThreadExecutor();
			try {
				Future<ErrorCode> response = pool.submit(() -> {
					BusinessException exception = assertThrows(BusinessException.class,
						() -> matchProposalResponseService.respond(
							userId, proposalId, MatchProposalResponseAction.ACCEPT, "deadline-lock-wait"));
					return exception.getErrorCode();
				});
				awaitTransactionLockWait();
				TimeUnit.MILLISECONDS.sleep(1_200);
				proposalLock.rollback();

				assertEquals(ErrorCode.MATCH_PROPOSAL_RESPONSE_NOT_AVAILABLE, response.get(10, TimeUnit.SECONDS));
			} finally {
				pool.shutdownNow();
			}
		}
		assertEquals("OPEN", proposalStatus(proposalId));
		assertEquals("PENDING", responseStatus(proposalId, requestId));
	}

	@Test
	void 마지막_ACCEPT와_만료_경합은_CONFIRMED_Party_또는_EXPIRED_중_하나로만_수렴한다() throws Exception {
		long firstUser = insertUser("race-first");
		long secondUser = insertUser("race-second");
		long firstRequest = insertRequest(firstUser);
		long secondRequest = insertRequest(secondUser);
		long proposal = insertOpenProposal();
		insertProposalMember(proposal, firstRequest, firstUser);
		insertProposalMember(proposal, secondRequest, secondUser);
		jdbcTemplate.update(
			"update match_proposal_members set response_status='ACCEPTED', responded_at=? where proposal_id=? and match_request_id=?",
			Timestamp.from(Instant.now()), proposal, firstRequest);
		CountDownLatch start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			Future<RaceResult> accept = pool.submit(acceptAttempt(start, secondUser, proposal));
			Future<RaceResult> expire = pool.submit(expireAttempt(start, proposal));
			start.countDown();
			RaceResult acceptResult = accept.get(10, TimeUnit.SECONDS);
			RaceResult expireResult = expire.get(10, TimeUnit.SECONDS);
			assertEquals(true, acceptResult.completed() || expireResult.completed());
		} finally {
			pool.shutdownNow();
		}
		String status = proposalStatus(proposal);
		assertEquals(true, status.equals("CONFIRMED") || status.equals("EXPIRED"));
		assertEquals(status.equals("CONFIRMED") ? 1 : 0,
			jdbcTemplate.queryForObject("select count(*) from match_parties", Integer.class));
	}

	@Test
	void 요청_취소와_마지막_ACCEPT_경합은_CANCELED_Proposal_또는_CONFIRMED_Party_하나로만_수렴한다() throws Exception {
		long firstUser = insertUser("cancel-race-first");
		long secondUser = insertUser("cancel-race-second");
		long firstRequest = insertRequest(firstUser);
		long secondRequest = insertRequest(secondUser);
		long proposal = insertOpenProposal();
		insertProposalMember(proposal, firstRequest, firstUser);
		insertProposalMember(proposal, secondRequest, secondUser);
		matchProposalResponseService.respond(firstUser, proposal, MatchProposalResponseAction.ACCEPT,
			"cancel-race-first-accept");

		CountDownLatch start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			Future<RaceResult> cancel = pool.submit(cancelAttempt(start, secondUser));
			Future<RaceResult> accept = pool.submit(acceptAttempt(start, secondUser, proposal));
			start.countDown();
			cancel.get(10, TimeUnit.SECONDS);
			accept.get(10, TimeUnit.SECONDS);
		} finally {
			pool.shutdownNow();
		}

		String proposalStatus = proposalStatus(proposal);
		assertTrue(proposalStatus.equals("CANCELED") || proposalStatus.equals("CONFIRMED"));
		if (proposalStatus.equals("CANCELED")) {
			assertEquals("CANCELED", requestStatus(secondRequest));
			assertEquals("WAITING", requestStatus(firstRequest));
			assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_parties", Integer.class));
			return;
		}
		assertEquals("MATCHED", requestStatus(firstRequest));
		assertEquals("MATCHED", requestStatus(secondRequest));
		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_parties", Integer.class));
	}

	@Test
	void 마지막_ACCEPT와_새_요청_등록_경합에서_새_요청은_지고_성공_Party와_현재_흐름은_하나만_남는다() throws Exception {
		long firstUser = insertUser("create-race-first");
		long secondUser = insertUser("create-race-second");
		long firstRequest = insertRequest(firstUser);
		long secondRequest = insertRequest(secondUser);
		long proposal = insertOpenProposal();
		insertProposalMember(proposal, firstRequest, firstUser);
		insertProposalMember(proposal, secondRequest, secondUser);
		matchProposalResponseService.respond(firstUser, proposal, MatchProposalResponseAction.ACCEPT,
			"create-race-first-accept");

		CountDownLatch start = new CountDownLatch(1);
		var pool = Executors.newFixedThreadPool(2);
		try {
			Future<RaceResult> create = pool.submit(createAttempt(start, secondUser));
			Future<RaceResult> accept = pool.submit(acceptAttempt(start, secondUser, proposal));
			start.countDown();
			RaceResult createResult = create.get(10, TimeUnit.SECONDS);
			accept.get(10, TimeUnit.SECONDS);
			assertEquals(ErrorCode.MATCH_REQUEST_ALREADY_ACTIVE, createResult.errorCode());
		} finally {
			pool.shutdownNow();
		}

		assertEquals("CONFIRMED", proposalStatus(proposal));
		assertEquals("MATCHED", requestStatus(firstRequest));
		assertEquals("MATCHED", requestStatus(secondRequest));
		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_parties", Integer.class));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from match_requests where user_id = ? and status in ('WAITING', 'PROPOSED', 'PAUSED')",
			Integer.class, secondUser));
	}

	@Test
	void 마지막_ACCEPT의_Party_참가자_저장_실패는_Proposal_요청_Party를_함께_롤백한다() {
		long firstUser = insertUser("rollback-first");
		long secondUser = insertUser("rollback-second");
		long firstRequest = insertRequest(firstUser);
		long secondRequest = insertRequest(secondUser);
		long proposal = insertOpenProposal();
		insertProposalMember(proposal, firstRequest, firstUser);
		insertProposalMember(proposal, secondRequest, secondUser);
		matchProposalResponseService.respond(firstUser, proposal, MatchProposalResponseAction.ACCEPT,
			"rollback-first-accept");
		doThrow(new IllegalStateException("forced participant save failure"))
			.when(participantRepository).save(any(MatchPartyParticipant.class));

		assertThrows(IllegalStateException.class, () -> matchProposalResponseService.respond(
			secondUser, proposal, MatchProposalResponseAction.ACCEPT, "rollback-second-accept"));

		assertEquals("OPEN", proposalStatus(proposal));
		assertEquals("ACCEPTED", responseStatus(proposal, firstRequest));
		assertEquals("PENDING", responseStatus(proposal, secondRequest));
		assertEquals("PROPOSED", requestStatus(firstRequest));
		assertEquals("PROPOSED", requestStatus(secondRequest));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_parties", Integer.class));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_party_participants", Integer.class));
	}

	private Callable<RaceResult> acceptAttempt(CountDownLatch start, long userId, long proposalId) {
		return () -> {
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("start timeout");
			}
			try {
				matchProposalResponseService.respond(userId, proposalId, MatchProposalResponseAction.ACCEPT,
					"race-accept");
				return new RaceResult(true, null);
			} catch (BusinessException exception) {
				return new RaceResult(false, exception.getErrorCode());
			}
		};
	}

	private Callable<RaceResult> cancelAttempt(CountDownLatch start, long userId) {
		return () -> {
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("start timeout");
			}
			try {
				matchRequestCommandService.cancel(userId);
				return new RaceResult(true, null);
			} catch (BusinessException exception) {
				return new RaceResult(false, exception.getErrorCode());
			}
		};
	}

	private Callable<RaceResult> expireAttempt(CountDownLatch start, long proposalId) {
		return () -> {
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("start timeout");
			}
			jdbcTemplate.update("update match_proposals set respond_by=? where id=?",
				Timestamp.from(Instant.now().minusSeconds(1)), proposalId);
			matchProposalResponseService.expireDueProposals();
			return new RaceResult(true, null);
		};
	}

	private Callable<RaceResult> createAttempt(CountDownLatch start, long userId) {
		return () -> {
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("start timeout");
			}
			try {
				matchRequestCommandService.create(userId, "create-race-request", new MatchRequestCreateRequest(2, 2));
				return new RaceResult(true, null);
			} catch (BusinessException exception) {
				return new RaceResult(false, exception.getErrorCode());
			}
		};
	}

	private record RaceResult(boolean completed, ErrorCode errorCode) {
	}

	private long insertUser(String role) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class, "proposal-terminal-" + role + "-" + UUID.randomUUID() + "@example.com", role,
			Timestamp.from(now), Timestamp.from(now));
	}

	private long insertRequest(long userId) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, proposed_at, created_at, updated_at) values (?, 2, 2, 'PROPOSED', ?, ?, ?, ?, ?) returning id",
			Long.class, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now),
			Timestamp.from(now));
	}

	private long insertWaitingRequest(long userId) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) values (?, 2, 2, 'WAITING', ?, ?, ?, ?) returning id",
			Long.class, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
	}

	private void assertPurgeAfterExactly(String tableName, long entityId, Instant terminalTime) {
		Instant purgeAfter = terminalTime(tableName, "purge_after", entityId);
		assertEquals(terminalTime.plusSeconds(TERMINAL_RETENTION_SECONDS), purgeAfter);
	}

	private Instant terminalTime(String tableName, String columnName, long entityId) {
		return jdbcTemplate.queryForObject(
			"select " + columnName + " from " + tableName + " where id = ?", Timestamp.class, entityId).toInstant();
	}

	private long insertOpenProposal() {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into match_proposals (party_size, status, respond_by, created_at, updated_at) values (2, 'OPEN', ?, ?, ?) returning id",
			Long.class, Timestamp.from(now.plusSeconds(30)), Timestamp.from(now), Timestamp.from(now));
	}

	private void insertProposalMember(long proposalId, long requestId, long userId) {
		Instant now = Instant.now();
		jdbcTemplate.update(
			"insert into match_proposal_members (proposal_id, match_request_id, user_id, response_status, created_at, updated_at) values (?, ?, ?, 'PENDING', ?, ?)",
			proposalId, requestId, userId, Timestamp.from(now), Timestamp.from(now));
	}

	private String responseStatus(long proposalId, long requestId) {
		return jdbcTemplate.queryForObject(
			"select response_status from match_proposal_members where proposal_id = ? and match_request_id = ?",
			String.class,
			proposalId, requestId);
	}

	private String proposalStatus(long proposalId) {
		return jdbcTemplate.queryForObject("select status from match_proposals where id = ?", String.class, proposalId);
	}

	private String requestStatus(long requestId) {
		return jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId);
	}

	private void awaitTransactionLockWait() throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			Boolean waiting = jdbcTemplate.queryForObject(
				"select exists (select 1 from pg_locks where locktype = 'transactionid' and not granted)",
				Boolean.class);
			if (Boolean.TRUE.equals(waiting)) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(25);
		}
		throw new AssertionError("Proposal 잠금 대기 상태를 관찰하지 못했습니다.");
	}

	@TestConfiguration
	static class ClockSkewConfiguration {
		@Bean
		@Primary
		Clock matchingClock() {
			return Clock.fixed(Instant.parse("2099-01-01T00:00:00Z"), ZoneOffset.UTC);
		}
	}
}
