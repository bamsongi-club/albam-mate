package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

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
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.dto.MatchRequestCreateRequest;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalCoordinator;
import cloud.bamsongi.albammate.matching.service.command.MatchRequestCommandService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(classes = {AlbamMateApplication.class, MatchRequestInvariantPostgresTest.ClockSkewConfiguration.class})
class MatchRequestInvariantPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private MatchRequestCommandService matchRequestCommandService;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private DataSource dataSource;
	@Autowired
	private MatchProposalCoordinator matchProposalCoordinator;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_idempotency_records, match_proposal_members, match_proposals, match_party_participants, match_parties, match_requests, users restart identity cascade");
	}

	@Test
	void 요청_생성과_요청_멱등성_기록은_왜곡된_애플리케이션_Clock이_아닌_PostgreSQL_시각을_쓴다() {
		long userId = insertUser();
		Instant databaseBefore = jdbcTemplate.queryForObject("select current_timestamp", Timestamp.class).toInstant();

		matchRequestCommandService.create(userId, "database-time-key", new MatchRequestCreateRequest(2, 2));

		Instant queuedAt = jdbcTemplate.queryForObject(
			"select queued_at from match_requests where user_id = ?", Timestamp.class, userId).toInstant();
		Instant idempotencyCreatedAt = jdbcTemplate.queryForObject(
			"select created_at from match_idempotency_records where user_id = ? and idempotency_key = ?",
			Timestamp.class, userId, "database-time-key").toInstant();
		assertTrue(!queuedAt.isBefore(databaseBefore));
		assertTrue(!idempotencyCreatedAt.isBefore(databaseBefore));
		assertTrue(queuedAt.isBefore(Instant.parse("2090-01-01T00:00:00Z")));
		assertTrue(idempotencyCreatedAt.isBefore(Instant.parse("2090-01-01T00:00:00Z")));
	}

	@Test
	void 서로_다른_두_키의_동시_요청도_사용자_행_잠금_뒤_하나의_대기_흐름으로_수렴한다() throws Exception {
		long userId = insertUser();
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			List<Future<AttemptResult>> attempts = new ArrayList<>();
			attempts.add(executor.submit(createAttempt(userId, "postgres-key-a", ready, start)));
			attempts.add(executor.submit(createAttempt(userId, "postgres-key-b", ready, start)));
			assertEquals(true, ready.await(5, TimeUnit.SECONDS));
			start.countDown();

			List<AttemptResult> results = List.of(
				attempts.get(0).get(10, TimeUnit.SECONDS),
				attempts.get(1).get(10, TimeUnit.SECONDS));
			long createdCount = results.stream().filter(AttemptResult::created).count();
			long activeConflictCount = results.stream()
				.filter(result -> result.errorCode() == ErrorCode.MATCH_REQUEST_ALREADY_ACTIVE)
				.count();
			assertEquals(1, createdCount);
			assertEquals(1, activeConflictCount);
			assertEquals(1, countCurrentRequests(userId));
		} finally {
			executor.shutdownNow();
		}
	}

	@Test
	void 같은_키의_재생과_24시간_만료_후_교체가_실제_PostgreSQL_행에서_원자적으로_동작한다() {
		long userId = insertUser();
		MatchRequestCommandService.CreateResult created = matchRequestCommandService.create(
			userId, "replay-key", new MatchRequestCreateRequest(2, 3));
		MatchRequestCommandService.CreateResult replayed = matchRequestCommandService.create(
			userId, "replay-key", new MatchRequestCreateRequest(2, 3));
		assertEquals(false, created.replayed());
		assertEquals(true, replayed.replayed());
		assertEquals(1, countCurrentRequests(userId));
		assertEquals("MATCH_REQUEST", jdbcTemplate.queryForObject(
			"select result_entity_type from match_idempotency_records where user_id = ? and idempotency_key = ?",
			String.class, userId, "replay-key"));

		assertThrows(BusinessException.class, () -> matchRequestCommandService.create(
			userId, "replay-key", new MatchRequestCreateRequest(1, 1)));
		jdbcTemplate.update("update match_requests set status = 'CANCELED' where user_id = ?", userId);
		jdbcTemplate.update(
			"update match_idempotency_records set expires_at = ? where user_id = ? and idempotency_key = ?",
			Timestamp.from(Instant.now().minusSeconds(1)), userId, "replay-key");
		MatchRequestCommandService.CreateResult replaced = matchRequestCommandService.create(
			userId, "replay-key", new MatchRequestCreateRequest(1, 1));
		assertEquals(false, replaced.replayed());
		assertEquals(1, countCurrentRequests(userId));
	}

	@Test
	void 같은_키_재생은_due_OPEN_Proposal을_사용자_잠금_밖에서_보정해_최신_상태를_반환한다() {
		long userId = insertUser();
		matchRequestCommandService.create(userId, "due-proposal-replay", new MatchRequestCreateRequest(1, 1));
		long requestId = jdbcTemplate.queryForObject(
			"select id from match_requests where user_id = ?", Long.class, userId);
		jdbcTemplate.update(
			"update match_requests set status = 'PROPOSED', proposed_at = current_timestamp where id = ?",
			requestId);
		long proposalId = jdbcTemplate.queryForObject("""
			insert into match_proposals (party_size, status, respond_by, created_at, updated_at)
			values (1, 'OPEN', current_timestamp - interval '1 second', current_timestamp, current_timestamp)
			returning id
			""", Long.class);
		jdbcTemplate.update("""
			insert into match_proposal_members
			(proposal_id, match_request_id, user_id, response_status, created_at, updated_at)
			values (?, ?, ?, 'PENDING', current_timestamp, current_timestamp)
			""", proposalId, requestId, userId);

		MatchRequestCommandService.CreateResult replayed = matchRequestCommandService.create(
			userId, "due-proposal-replay", new MatchRequestCreateRequest(1, 1));

		assertEquals(true, replayed.replayed());
		assertEquals(MatchCurrentState.PAUSED, replayed.response().state());
		assertEquals("EXPIRED", jdbcTemplate.queryForObject(
			"select status from match_proposals where id = ?", String.class, proposalId));
	}

	@Test
	void 취소가_claim_행_잠금을_기다린_뒤_제안_종결과_요청_상태를_함께_수렴한다() throws Exception {
		long cancelUserId = insertUser();
		long candidateUserId = insertUser();
		long cancelRequestId = insertWaitingRequest(cancelUserId, 10);
		long candidateRequestId = insertWaitingRequest(candidateUserId, 20);
		jdbcTemplate.execute(
			"create function wait_for_match_claim() returns trigger language plpgsql as $$ begin perform set_config('lock_timeout', '5s', true); perform pg_advisory_xact_lock(745001); return new; end; $$");
		jdbcTemplate.execute(
			"create trigger wait_for_match_claim_trigger before insert on match_proposals for each row execute function wait_for_match_claim()");
		try (Connection connection = dataSource.getConnection()) {
			executeWithTimeout(connection, "select pg_advisory_lock(745001)");
			ExecutorService pool = Executors.newFixedThreadPool(2);
			Future<?> matcher = null;
			Future<?> cancel = null;
			try {
				matcher = pool.submit(matchProposalCoordinator::claimAvailableCandidates);
				awaitLock(
					"select count(*) > 0 from pg_locks where locktype='advisory' and objid=745001 and not granted");
				cancel = pool.submit(() -> matchRequestCommandService.cancel(cancelUserId));
				Future<?> cancellation = cancel;
				assertThrows(TimeoutException.class, () -> cancellation.get(200, TimeUnit.MILLISECONDS));
				executeWithTimeout(connection, "select pg_advisory_unlock(745001)");
				matcher.get(10, TimeUnit.SECONDS);
				cancel.get(10, TimeUnit.SECONDS);
			} finally {
				executeWithTimeout(connection, "select pg_advisory_unlock(745001)");
				if (matcher != null) {
					matcher.cancel(true);
				}
				if (cancel != null) {
					cancel.cancel(true);
				}
				pool.shutdownNow();
				pool.awaitTermination(10, TimeUnit.SECONDS);
			}
		} finally {
			jdbcTemplate.execute("drop trigger if exists wait_for_match_claim_trigger on match_proposals");
			jdbcTemplate.execute("drop function if exists wait_for_match_claim()");
		}
		assertEquals("CANCELED", proposalStatus());
		assertEquals("CANCELED", requestStatus(cancelRequestId));
		assertEquals("WAITING", requestStatus(candidateRequestId));
	}

	private Callable<AttemptResult> createAttempt(long userId, String key, CountDownLatch ready, CountDownLatch start) {
		return () -> {
			ready.countDown();
			if (!start.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("동시 요청 시작 신호가 오지 않았습니다.");
			}
			try {
				MatchRequestCommandService.CreateResult result = matchRequestCommandService.create(
					userId, key, new MatchRequestCreateRequest(2, 4));
				return new AttemptResult(!result.replayed(), null);
			} catch (BusinessException exception) {
				return new AttemptResult(false, exception.getErrorCode());
			}
		};
	}

	private long insertUser() {
		Instant now = Instant.now();
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class,
			"match-request-invariant-" + UUID.randomUUID() + "@example.com",
			"매칭 사용자",
			Timestamp.from(now),
			Timestamp.from(now));
	}

	private int countCurrentRequests(long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from match_requests where user_id = ? and status in ('WAITING', 'PROPOSED', 'PAUSED')",
			Integer.class,
			userId);
	}

	private long insertWaitingRequest(long userId, int secondOffset) {
		Instant now = Instant.parse("2026-08-20T00:00:00Z").plusSeconds(secondOffset);
		return jdbcTemplate.queryForObject(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at) values (?, 2, 2, 'WAITING', ?, ?, ?, ?) returning id",
			Long.class, userId, Timestamp.from(now), Timestamp.from(now), Timestamp.from(now), Timestamp.from(now));
	}

	private String requestStatus(long requestId) {
		return jdbcTemplate.queryForObject("select status from match_requests where id = ?", String.class, requestId);
	}

	private String proposalStatus() {
		return jdbcTemplate.queryForObject("select status from match_proposals", String.class);
	}

	private void awaitLock(String sql) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			if (Boolean.TRUE.equals(jdbcTemplate.queryForObject(sql, Boolean.class))) {
				return;
			}
			Thread.sleep(25);
		}
		throw new AssertionError("PostgreSQL lock wait timeout");
	}

	private void executeWithTimeout(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.setQueryTimeout(5);
			statement.execute(sql);
		}
	}

	private record AttemptResult(boolean created, ErrorCode errorCode) {
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
