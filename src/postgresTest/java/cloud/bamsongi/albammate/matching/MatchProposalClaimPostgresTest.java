package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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
@SpringBootTest(
	classes = {AlbamMateApplication.class, MatchProposalClaimPostgresTest.ClockSkewConfiguration.class},
	properties = "spring.task.scheduling.enabled=false")
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
	void 최소_인원이_1인_anchor는_혼자서_partySize와_Member_수가_1인_Proposal로_claim한다() {
		long anchorUserId = insertUser("single-anchor");
		long laterUserId = insertUser("single-later");
		long anchorRequestId = insertRequest(anchorUserId, 1, 3, 10);
		long laterRequestId = insertRequest(laterUserId, 1, 3, 20);

		matchProposalCoordinator.claimAvailableCandidates();

		assertEquals("PROPOSED", requestStatus(anchorRequestId));
		assertEquals("WAITING", requestStatus(laterRequestId));
		long proposalId = jdbcTemplate.queryForObject("select id from match_proposals", Long.class);
		assertEquals(1, jdbcTemplate.queryForObject(
			"select party_size from match_proposals where id = ?", Integer.class, proposalId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_proposal_members where proposal_id = ?", Integer.class, proposalId));
	}

	@Test
	void 최소와_최대_인원이_모두_1인_단독_anchor는_WAITING에_남지_않고_1인_Proposal로_claim한다() {
		long anchorUserId = insertUser("single-exact-anchor");
		long anchorRequestId = insertRequest(anchorUserId, 1, 1, 10);

		matchProposalCoordinator.claimAvailableCandidates();

		assertEquals("PROPOSED", requestStatus(anchorRequestId));
		long proposalId = jdbcTemplate.queryForObject("select id from match_proposals", Long.class);
		assertEquals(1, jdbcTemplate.queryForObject(
			"select party_size from match_proposals where id = ?", Integer.class, proposalId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_proposal_members where proposal_id = ?", Integer.class, proposalId));
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
	void claim은_선택_사용자_잠금_뒤_먼저_확정된_양방향_차단을_재검사한다() throws Exception {
		long firstUserId = insertUser("block-race-first");
		long secondUserId = insertUser("block-race-second");
		long firstRequestId = insertRequest(firstUserId, 2, 2, 10);
		long secondRequestId = insertRequest(secondUserId, 2, 2, 20);

		try (Connection userLock = dataSource.getConnection()) {
			userLock.setAutoCommit(false);
			try (var statement = userLock
				.prepareStatement("select id from users where id in (?, ?) order by id for update")) {
				statement.setLong(1, firstUserId);
				statement.setLong(2, secondUserId);
				statement.executeQuery();
			}
			ExecutorService executor = Executors.newFixedThreadPool(2);
			try {
				Future<?> block = executor.submit(() -> jdbcTemplate.update("""
					insert into match_blocks (blocker_user_id, blocked_user_id, created_at)
					values (?, ?, current_timestamp)
					""", firstUserId, secondUserId));
				awaitTransactionLockWait();
				Future<?> claim = executor.submit(matchProposalCoordinator::claimAvailableCandidates);
				assertThrows(java.util.concurrent.TimeoutException.class,
					() -> claim.get(200, TimeUnit.MILLISECONDS));
				userLock.rollback();
				block.get(10, TimeUnit.SECONDS);
				claim.get(10, TimeUnit.SECONDS);
			} finally {
				executor.shutdownNow();
			}
		}

		assertEquals("WAITING", requestStatus(firstRequestId));
		assertEquals("WAITING", requestStatus(secondRequestId));
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
	void 허용_범위의_101개_요청은_고정_100건_경계없이_하나의_Proposal로_claim한다() {
		for (int index = 1; index <= 101; index++) {
			long userId = insertUser("bounded-" + index);
			insertRequest(userId, 101, 101, index);
		}

		matchProposalCoordinator.claimAvailableCandidates();

		long proposalId = jdbcTemplate.queryForObject("select id from match_proposals", Long.class);
		assertEquals(101, jdbcTemplate.queryForObject(
			"select party_size from match_proposals where id = ?", Integer.class, proposalId));
		assertEquals(101, jdbcTemplate.queryForObject(
			"select count(*) from match_proposal_members where proposal_id = ?", Integer.class, proposalId));
	}

	@Test
	void 고정_32767개_접두사_뒤의_호환_후보까지_keyset_page로_탐색해_claim한다() {
		long anchorUserId = insertUser("keyset-anchor");
		long anchorRequestId = insertRequest(anchorUserId, 2, 2, 10);
		insertIncompatibleWaitingPrefix(Short.MAX_VALUE - 1);
		long compatibleUserId = insertUser("keyset-compatible");
		long compatibleRequestId = insertRequest(compatibleUserId, 2, 2, 30);

		matchProposalCoordinator.claimAvailableCandidates();

		assertEquals("PROPOSED", requestStatus(anchorRequestId));
		assertEquals("PROPOSED", requestStatus(compatibleRequestId));
		assertEquals(List.of(anchorRequestId, compatibleRequestId), jdbcTemplate.queryForList(
			"select match_request_id from match_proposal_members order by match_request_id", Long.class));
	}

	@Test
	void target_범위에_포함된_차단_후보_100건_뒤의_호환_후보까지_다음_keyset_page를_탐색한다() {
		long anchorUserId = insertUser("keyset-page-anchor");
		long anchorRequestId = insertRequest(anchorUserId, 2, 2, 10);
		insertBlockedCompatibleWaitingPrefix(anchorUserId, 100);
		long compatibleUserId = insertUser("keyset-page-compatible");
		long compatibleRequestId = insertRequest(compatibleUserId, 2, 2, 30);

		matchProposalCoordinator.claimAvailableCandidates();

		assertEquals("PROPOSED", requestStatus(anchorRequestId));
		assertEquals("PROPOSED", requestStatus(compatibleRequestId));
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

	private void insertIncompatibleWaitingPrefix(int count) {
		Instant time = Instant.parse("2026-08-20T00:00:20Z");
		jdbcTemplate.update("""
			insert into users (email, password_hash, nickname, created_at, updated_at)
			select 'match-claim-keyset-incompatible-' || value || '@example.com', 'hash',
				'keyset-incompatible-' || value, ?, ?
			from generate_series(1, ?) value
			""", Timestamp.from(time), Timestamp.from(time), count);
		jdbcTemplate.update("""
			insert into match_requests
			(user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at)
			select id, 3, 3, 'WAITING', ?, ?, ?, ?
			from users
			where email like 'match-claim-keyset-incompatible-%'
			""", Timestamp.from(time), Timestamp.from(time), Timestamp.from(time), Timestamp.from(time));
	}

	private void insertBlockedCompatibleWaitingPrefix(long anchorUserId, int count) {
		Instant time = Instant.parse("2026-08-20T00:00:20Z");
		jdbcTemplate.update("""
			insert into users (email, password_hash, nickname, created_at, updated_at)
			select 'match-claim-keyset-blocked-' || value || '@example.com', 'hash',
				'keyset-blocked-' || value, ?, ?
			from generate_series(1, ?) value
			""", Timestamp.from(time), Timestamp.from(time), count);
		jdbcTemplate.update("""
			insert into match_requests
			(user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at)
			select id, 2, 2, 'WAITING', ?, ?, ?, ?
			from users
			where email like 'match-claim-keyset-blocked-%'
			""", Timestamp.from(time), Timestamp.from(time), Timestamp.from(time), Timestamp.from(time));
		jdbcTemplate.update("""
			insert into match_blocks (blocker_user_id, blocked_user_id, created_at)
			select ?, id, ?
			from users
			where email like 'match-claim-keyset-blocked-%'
			""", anchorUserId, Timestamp.from(time));
	}

	private String requestStatus(long requestId) {
		return jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId);
	}

	private void awaitTransactionLockWait() throws InterruptedException {
		for (int attempt = 0; attempt < 200; attempt++) {
			Boolean waiting = jdbcTemplate.queryForObject(
				"select exists (select 1 from pg_locks where locktype = 'transactionid' and not granted)",
				Boolean.class);
			if (Boolean.TRUE.equals(waiting)) {
				return;
			}
			TimeUnit.MILLISECONDS.sleep(25);
		}
		throw new AssertionError("차단 생성의 사용자 행 잠금을 관찰하지 못했습니다.");
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
