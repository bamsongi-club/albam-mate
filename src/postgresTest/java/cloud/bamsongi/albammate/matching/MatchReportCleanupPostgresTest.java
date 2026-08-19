package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.matching.recovery.MatchReportCleanupExecutor;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;
import cloud.bamsongi.albammate.user.service.UserRowLockService;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
@AutoConfigureMockMvc
@org.springframework.context.annotation.Import(MatchReportCleanupPostgresTest.LockGateConfiguration.class)
class MatchReportCleanupPostgresTest extends SharedPostgresIntegrationSupport {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private MatchReportCleanupExecutor cleanupExecutor;
	@Autowired
	private CleanupLockGate cleanupLockGate;

	@AfterEach
	void tearDown() {
		cleanupLockGate.release();
		jdbcTemplate.execute(
			"truncate table match_reports, match_party_participants, match_parties, users restart identity cascade");
	}

	@Test
	void T4_cleanup_최초_조회_뒤_재신고가_커밋되면_새_신고를_삭제하지_않는다() throws Exception {
		long reporterUserId = insertUser("stale-reporter");
		long reportedUserId = insertUser("stale-reported");
		long partyId = insertActiveParty();
		UUID participantRef = UUID.randomUUID();
		insertParticipant(partyId, reporterUserId, UUID.randomUUID());
		insertParticipant(partyId, reportedUserId, participantRef);
		long reportId = insertReportReturningId(
			reporterUserId, reportedUserId, Instant.now().minusSeconds(1));
		cleanupLockGate.blockNextCalls(1);

		ExecutorService executorService = Executors.newSingleThreadExecutor();
		try {
			Future<Integer> cleanup = executorService.submit(() -> cleanupExecutor.cleanupOneBatch(10));
			assertTrue(cleanupLockGate.awaitBlockedCalls());

			mockMvc.perform(post("/api/matches/parties/" + partyId + "/reports")
				.with(authenticationFor(reporterUserId)).with(csrf()).contentType(MediaType.APPLICATION_JSON)
				.content(requestBody(participantRef, "HATE_OR_DISCRIMINATION")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.data.alreadyReceived").value(false));

			cleanupLockGate.release();
			assertEquals(0, cleanup.get(10, TimeUnit.SECONDS));
		} finally {
			cleanupLockGate.release();
			executorService.shutdownNow();
			assertTrue(executorService.awaitTermination(10, TimeUnit.SECONDS));
		}

		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_reports where id = ?", Integer.class, reportId));
		assertEquals("HATE_OR_DISCRIMINATION", jdbcTemplate.queryForObject(
			"select reason from match_reports where id = ?", String.class, reportId));
		assertTrue(jdbcTemplate.queryForObject(
			"select reported_at > ? from match_reports where id = ?", Boolean.class,
			Timestamp.from(FIXED_TIME.minusSeconds(60)), reportId));
		assertTrue(jdbcTemplate.queryForObject(
			"select purge_after > current_timestamp from match_reports where id = ?", Boolean.class, reportId));
		assertTrue(jdbcTemplate.queryForObject(
			"select purge_after = reported_at + interval '7 days' from match_reports where id = ?",
			Boolean.class, reportId));
	}

	@Test
	void T5_동시와_반복_cleanup은_만료_신고_하나만_삭제하고_기한_전_신고를_보존한다() throws Exception {
		Instant operationTime = Instant.now();
		long expiredReporter = insertUser("expired-reporter");
		long expiredReported = insertUser("expired-reported");
		long retainedReporter = insertUser("retained-reporter");
		long retainedReported = insertUser("retained-reported");
		insertReport(expiredReporter, expiredReported, operationTime.minusSeconds(1));
		insertReport(retainedReporter, retainedReported, operationTime.plusSeconds(3600));
		cleanupLockGate.blockNextCalls(2);

		ExecutorService executorService = Executors.newFixedThreadPool(2);
		try {
			Future<Integer> first = executorService.submit(() -> cleanupExecutor.cleanupOneBatch(10));
			Future<Integer> second = executorService.submit(() -> cleanupExecutor.cleanupOneBatch(10));
			assertTrue(cleanupLockGate.awaitBlockedCalls());
			cleanupLockGate.release();
			assertEquals(1, first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS));
			assertEquals(0, cleanupExecutor.cleanupOneBatch(10));
		} finally {
			cleanupLockGate.release();
			executorService.shutdownNow();
			assertTrue(executorService.awaitTermination(10, TimeUnit.SECONDS));
		}

		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from match_reports where reporter_user_id = ?", Integer.class, expiredReporter));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from match_reports where reporter_user_id = ?", Integer.class, retainedReporter));
	}

	private long insertUser(String suffix) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class, suffix + "-" + UUID.randomUUID() + "@example.com", suffix,
			Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
	}

	private void insertReport(long reporterUserId, long reportedUserId, Instant purgeAfter) {
		jdbcTemplate.update(
			"insert into match_reports (reporter_user_id, reported_user_id, reason, reported_at, purge_after) values (?, ?, 'SPAM_OR_SCAM', ?, ?)",
			reporterUserId, reportedUserId, Timestamp.from(FIXED_TIME.minusSeconds(60)), Timestamp.from(purgeAfter));
	}

	private long insertReportReturningId(long reporterUserId, long reportedUserId, Instant purgeAfter) {
		return jdbcTemplate.queryForObject(
			"insert into match_reports (reporter_user_id, reported_user_id, reason, reported_at, purge_after) values (?, ?, 'SPAM_OR_SCAM', ?, ?) returning id",
			Long.class, reporterUserId, reportedUserId, Timestamp.from(FIXED_TIME.minusSeconds(60)),
			Timestamp.from(purgeAfter));
	}

	private long insertActiveParty() {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values ('ACTIVE', ?, ?, ?, ?, ?) returning id",
			Long.class, Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME),
			Timestamp.from(FIXED_TIME.plusSeconds(86400)), Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
	}

	private void insertParticipant(long partyId, long userId, UUID participantRef) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) values (?, ?, ?, ?)",
			partyId, userId, participantRef, Timestamp.from(FIXED_TIME));
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
			new CurrentUserPrincipal(userId), null,
			org.springframework.security.core.authority.AuthorityUtils.NO_AUTHORITIES));
	}

	private String requestBody(UUID participantRef, String reason) {
		return "{\"participantRef\":\"" + participantRef + "\",\"reason\":\"" + reason + "\"}";
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class LockGateConfiguration {

		@Bean
		CleanupLockGate cleanupLockGate() {
			return new CleanupLockGate();
		}

		@Bean
		@Primary
		UserRowLockPort controlledUserRowLockPort(UserRowLockService delegate, CleanupLockGate gate) {
			return userIds -> {
				gate.beforeLock();
				return delegate.lockExistingUsersInAscendingOrder(userIds);
			};
		}
	}

	static class CleanupLockGate {

		private volatile CountDownLatch blockedCalls = new CountDownLatch(0);
		private volatile CountDownLatch release = new CountDownLatch(0);
		private int remainingCalls;

		synchronized void blockNextCalls(int count) {
			remainingCalls = count;
			blockedCalls = new CountDownLatch(count);
			release = new CountDownLatch(1);
		}

		boolean awaitBlockedCalls() throws InterruptedException {
			return blockedCalls.await(10, TimeUnit.SECONDS);
		}

		void release() {
			release.countDown();
		}

		void beforeLock() {
			boolean shouldBlock;
			synchronized (this) {
				shouldBlock = remainingCalls > 0;
				if (shouldBlock) {
					remainingCalls--;
				}
			}
			if (!shouldBlock) {
				return;
			}
			blockedCalls.countDown();
			try {
				if (!release.await(10, TimeUnit.SECONDS)) {
					throw new AssertionError("사용자 행 잠금 barrier가 해제되지 않았습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("사용자 행 잠금 barrier 대기 중 인터럽트되었습니다.", exception);
			}
		}
	}
}
