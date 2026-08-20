package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
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
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalCoordinator;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(classes = {AlbamMateApplication.class, MatchProposalClaimPostgresTest.ClockSkewConfiguration.class})
class MatchProposalClaimPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private MatchProposalCoordinator matchProposalCoordinator;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private DataSource dataSource;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_idempotency_records, match_proposal_members, match_proposals, match_party_participants, match_parties, match_requests, match_blocks, users restart identity cascade");
	}

	@Test
	void 후보_claim의_응답_기한은_왜곡된_애플리케이션_Clock이_아닌_PostgreSQL_시각에서_30초를_더한다() {
		long firstUserId = insertUser("database-time-first");
		long secondUserId = insertUser("database-time-second");
		insertRequest(firstUserId, 2, 2, 10);
		insertRequest(secondUserId, 2, 2, 20);
		Instant databaseBefore = jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class).toInstant();

		matchProposalCoordinator.claimAvailableCandidates();

		Instant respondBy = jdbcTemplate.queryForObject("select respond_by from match_proposals", Timestamp.class)
			.toInstant();
		assertEquals(true, !respondBy.isBefore(databaseBefore.plusSeconds(29)));
		assertEquals(true, respondBy.isBefore(Instant.parse("2090-01-01T00:00:00Z")));
	}

	@Test
	void 가장_오래된_anchor에서_target_인원을_오름차순으로_탐색해_FIFO_교집합_후보만_제안으로_claim한다() {
		long anchorUserId = insertUser("anchor");
		long secondUserId = insertUser("second");
		long thirdUserId = insertUser("third");
		long anchorRequestId = insertRequest(anchorUserId, 2, 3, 10);
		long secondRequestId = insertRequest(secondUserId, 3, 4, 20);
		long thirdRequestId = insertRequest(thirdUserId, 1, 3, 30);

		matchProposalCoordinator.claimAvailableCandidates();

		assertEquals("PROPOSED", requestStatus(anchorRequestId));
		assertEquals("WAITING", requestStatus(secondRequestId));
		assertEquals("PROPOSED", requestStatus(thirdRequestId));
		long proposalId = jdbcTemplate.queryForObject("select id from match_proposals", Long.class);
		assertEquals(2, jdbcTemplate.queryForObject("select party_size from match_proposals where id = ?",
			Integer.class, proposalId));
		assertEquals(List.of(anchorRequestId, thirdRequestId), jdbcTemplate.queryForList(
			"select match_request_id from match_proposal_members where proposal_id = ? order by created_at, match_request_id",
			Long.class, proposalId));
	}

	@Test
	void 양방향_차단이나_교집합_불일치_후보는_건너뛰고_anchor는_WAITING으로_남긴다() {
		long anchorUserId = insertUser("blocked-anchor");
		long blockedUserId = insertUser("blocked-candidate");
		long incompatibleUserId = insertUser("incompatible-candidate");
		long anchorRequestId = insertRequest(anchorUserId, 2, 2, 10);
		insertRequest(blockedUserId, 2, 2, 20);
		insertRequest(incompatibleUserId, 3, 3, 30);
		jdbcTemplate.update("insert into match_blocks (blocker_user_id, blocked_user_id, created_at) values (?, ?, ?)",
			blockedUserId, anchorUserId, Timestamp.from(Instant.now()));

		matchProposalCoordinator.claimAvailableCandidates();

		assertEquals("WAITING", requestStatus(anchorRequestId));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_proposals", Integer.class));
	}

	@Test
	void target_인원을_받지_못하는_앞선_FIFO_후보는_건너뛰고_뒤의_호환_후보를_선택한다() {
		long anchorUserId = insertUser("target-anchor");
		long incompatibleUserId = insertUser("target-incompatible");
		long compatibleUserId = insertUser("target-compatible");
		long anchorRequestId = insertRequest(anchorUserId, 2, 2, 10);
		long incompatibleRequestId = insertRequest(incompatibleUserId, 3, 3, 20);
		long compatibleRequestId = insertRequest(compatibleUserId, 2, 2, 30);

		matchProposalCoordinator.claimAvailableCandidates();

		assertEquals("PROPOSED", requestStatus(anchorRequestId));
		assertEquals("WAITING", requestStatus(incompatibleRequestId));
		assertEquals("PROPOSED", requestStatus(compatibleRequestId));
		assertEquals(List.of(anchorRequestId, compatibleRequestId), jdbcTemplate.queryForList(
			"select match_request_id from match_proposal_members order by match_request_id", Long.class));
	}

	@Test
	void 가장_오래된_anchor가_구성_불가하면_젊은_요청끼리_제안하지_않고_모두_WAITING으로_남긴다() {
		long oldestUserId = insertUser("oldest-unmatchable");
		long youngerFirstUserId = insertUser("younger-first");
		long youngerSecondUserId = insertUser("younger-second");
		long oldestRequestId = insertRequest(oldestUserId, 3, 3, 10);
		long youngerFirstRequestId = insertRequest(youngerFirstUserId, 2, 2, 20);
		long youngerSecondRequestId = insertRequest(youngerSecondUserId, 2, 2, 30);

		matchProposalCoordinator.claimAvailableCandidates();

		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_proposals", Integer.class));
		assertEquals("WAITING", requestStatus(oldestRequestId));
		assertEquals("WAITING", requestStatus(youngerFirstRequestId));
		assertEquals("WAITING", requestStatus(youngerSecondRequestId));
	}

	@Test
	void 다른_트랜잭션이_anchor를_잠근_동안에도_SKIP_LOCKED로_다음_FIFO_후보를_기한_안에_claim한다() throws Exception {
		long lockedAnchorUserId = insertUser("locked-anchor");
		long firstAvailableUserId = insertUser("first-available");
		long secondAvailableUserId = insertUser("second-available");
		long lockedAnchorRequestId = insertRequest(lockedAnchorUserId, 2, 2, 10);
		long firstAvailableRequestId = insertRequest(firstAvailableUserId, 2, 2, 20);
		long secondAvailableRequestId = insertRequest(secondAvailableUserId, 2, 2, 30);

		try (Connection lockConnection = dataSource.getConnection()) {
			lockConnection.setAutoCommit(false);
			try (var statement = lockConnection
				.prepareStatement("select id from match_requests where id = ? for update")) {
				statement.setLong(1, lockedAnchorRequestId);
				statement.executeQuery();
			}
			ExecutorService executor = Executors.newSingleThreadExecutor();
			try {
				Future<?> claim = executor.submit(matchProposalCoordinator::claimAvailableCandidates);
				claim.get(5, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
			}
			assertEquals("WAITING", requestStatus(lockedAnchorRequestId));
			assertEquals("PROPOSED", requestStatus(firstAvailableRequestId));
			assertEquals("PROPOSED", requestStatus(secondAvailableRequestId));
			long proposalId = jdbcTemplate.queryForObject("select id from match_proposals", Long.class);
			assertEquals(2, jdbcTemplate.queryForObject("select party_size from match_proposals where id = ?",
				Integer.class, proposalId));
			assertEquals(List.of(firstAvailableRequestId, secondAvailableRequestId), jdbcTemplate.queryForList(
				"select match_request_id from match_proposal_members where proposal_id = ? order by created_at, match_request_id",
				Long.class, proposalId));
			lockConnection.rollback();
		}
	}

	@Test
	void 후보_claim은_FIFO_상위_100건만_잠그고_101번째_요청은_다른_트랜잭션이_잠글_수_있다() throws Exception {
		List<Long> requestIds = new ArrayList<>();
		for (int index = 1; index <= 101; index++) {
			long userId = insertUser("bounded-" + index);
			requestIds.add(insertRequest(userId, 2, 2, index));
		}

		jdbcTemplate.execute("""
			create function pause_match_request_proposal_update() returns trigger language plpgsql as $$
			begin
				if new.status = 'PROPOSED' then
					perform pg_advisory_xact_lock(745002);
				end if;
				return new;
			end;
			$$
			""");
		jdbcTemplate.execute("""
			create trigger pause_match_request_proposal_update_trigger
			before update on match_requests
			for each row execute function pause_match_request_proposal_update()
			""");

		try (Connection advisoryConnection = dataSource.getConnection()) {
			advisoryConnection.setAutoCommit(true);
			advisoryConnection.createStatement().execute("select pg_advisory_lock(745002)");
			ExecutorService executor = Executors.newSingleThreadExecutor();
			Future<?> claim = executor.submit(matchProposalCoordinator::claimAvailableCandidates);
			try {
				awaitMatcherWaitingAtProposalUpdate();
				assertDoesNotThrow(() -> lockRequestNowait(requestIds.get(100)));
			} finally {
				advisoryConnection.createStatement().execute("select pg_advisory_unlock(745002)");
				claim.get(5, TimeUnit.SECONDS);
				executor.shutdownNow();
			}
		} finally {
			jdbcTemplate.execute(
				"drop trigger if exists pause_match_request_proposal_update_trigger on match_requests");
			jdbcTemplate.execute("drop function if exists pause_match_request_proposal_update()");
		}
	}

	@Test
	void ProposalMember_저장_실패는_Proposal과_모든_요청_상태를_함께_롤백한다() {
		long firstUserId = insertUser("rollback-first");
		long secondUserId = insertUser("rollback-second");
		long firstRequestId = insertRequest(firstUserId, 2, 2, 10);
		long secondRequestId = insertRequest(secondUserId, 2, 2, 20);
		jdbcTemplate.execute("""
			create function fail_match_proposal_member_insert() returns trigger language plpgsql as $$
			begin
				raise exception 'forced proposal member failure';
			end;
			$$
			""");
		jdbcTemplate.execute("""
			create trigger fail_match_proposal_member_insert_trigger
			before insert on match_proposal_members
			for each row execute function fail_match_proposal_member_insert()
			""");
		try {
			assertThrows(RuntimeException.class, matchProposalCoordinator::claimAvailableCandidates);
		} finally {
			jdbcTemplate
				.execute("drop trigger if exists fail_match_proposal_member_insert_trigger on match_proposal_members");
			jdbcTemplate.execute("drop function if exists fail_match_proposal_member_insert()");
		}

		assertEquals("WAITING", requestStatus(firstRequestId));
		assertEquals("WAITING", requestStatus(secondRequestId));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_proposals", Integer.class));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from match_proposal_members", Integer.class));
	}

	private long insertUser(String role) {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class,
			"match-claim-" + role + "-" + UUID.randomUUID() + "@example.com",
			role,
			Timestamp.from(now),
			Timestamp.from(now));
	}

	private long insertRequest(long userId, int minPartySize, int maxPartySize, int secondOffset) {
		Instant time = Instant.parse("2026-08-20T00:00:00Z").plusSeconds(secondOffset);
		return jdbcTemplate.queryForObject(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) values (?, ?, ?, 'WAITING', ?, ?, ?, ?) returning id",
			Long.class,
			userId, minPartySize, maxPartySize, Timestamp.from(time), Timestamp.from(time), Timestamp.from(time),
			Timestamp.from(time));
	}

	private String requestStatus(long requestId) {
		return jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId);
	}

	private void awaitMatcherWaitingAtProposalUpdate() throws InterruptedException {
		for (int attempt = 0; attempt < 50; attempt++) {
			Boolean isWaiting = jdbcTemplate.queryForObject("""
				select exists (
					select 1 from pg_locks
					where locktype = 'advisory'
					  and objid = 745002
					  and not granted
				)
				""", Boolean.class);
			if (Boolean.TRUE.equals(isWaiting)) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(100);
		}
		throw new AssertionError("후보 claim 트랜잭션이 요청 상태 갱신 지점까지 도달하지 않았습니다.");
	}

	private void lockRequestNowait(long requestId) throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(false);
			try (var statement = connection
				.prepareStatement("select id from match_requests where id = ? for update nowait")) {
				statement.setLong(1, requestId);
				statement.executeQuery();
			} finally {
				connection.rollback();
			}
		}
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
