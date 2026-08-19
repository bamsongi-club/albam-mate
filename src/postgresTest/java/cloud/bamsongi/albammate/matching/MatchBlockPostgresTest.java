package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;
import cloud.bamsongi.albammate.user.service.UserRowLockService;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
@AutoConfigureMockMvc
@Import(MatchBlockPostgresTest.CoordinatedUserRowLockConfiguration.class)
class MatchBlockPostgresTest {

	private static final Instant FIXED_TIME = Instant.parse("2026-08-19T00:00:00Z");
	private static final long WAIT_SECONDS = 10L;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4");

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private UserRowLockRaceGate userRowLockRaceGate;

	@Test
	void 같은_대상_동시_차단은_하나의_관계와_성공_응답으로_수렴한다() throws Exception {
		long requesterUserId = insertUser("요청자");
		long blockedUserId = insertUser("차단대상");
		long proposalId = insertConfirmedProposal();
		long partyId = insertActiveParty(proposalId);
		insertParticipant(partyId, requesterUserId, UUID.randomUUID());
		UUID blockedRef = UUID.randomUUID();
		insertParticipant(partyId, blockedUserId, blockedRef);
		MatchStorageSnapshot expectedSnapshot = snapshot(proposalId, partyId);
		CyclicBarrier startBarrier = new CyclicBarrier(2);
		UserRowLockRaceGate.Scenario race = userRowLockRaceGate.activate();

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Callable<MvcResult> blockRequest = () -> {
				startBarrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
				return mockMvc.perform(
					put("/api/matches/parties/{partyId}/participants/{participantRef}/block", partyId, blockedRef)
						.with(authenticationFor(requesterUserId)).with(csrf()))
					.andReturn();
			};
			Future<MvcResult> first = executor.submit(blockRequest);
			Future<MvcResult> second = executor.submit(blockRequest);

			assertTrue(
				race.firstLockAcquired.await(WAIT_SECONDS, TimeUnit.SECONDS),
				"첫 요청이 사용자 행 잠금을 획득하지 못했습니다.");
			assertTrue(
				race.secondLockAttempted.await(WAIT_SECONDS, TimeUnit.SECONDS),
				"두 번째 요청이 첫 요청의 잠금 이후 같은 잠금을 시도하지 않았습니다.");
			assertFalse(
				race.secondLockReturned.await(1, TimeUnit.SECONDS),
				"첫 요청의 잠금이 유지되는 동안 두 번째 요청의 잠금 획득이 완료됐습니다.");
			race.firstLockMayContinue.countDown();

			MvcResult firstResult = first.get(WAIT_SECONDS, TimeUnit.SECONDS);
			MvcResult secondResult = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
			assertSameBlockResponse(firstResult, secondResult);
		} finally {
			race.firstLockMayContinue.countDown();
			userRowLockRaceGate.deactivate();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
		}

		Integer blockCount = jdbcTemplate.queryForObject(
			"select count(*) from match_blocks where blocker_user_id = ? and blocked_user_id = ?",
			Integer.class, requesterUserId, blockedUserId);
		assertEquals(1, blockCount);
		assertEquals(expectedSnapshot, snapshot(proposalId, partyId));
	}

	private void assertSameBlockResponse(MvcResult first, MvcResult second) throws Exception {
		String firstResponse = first.getResponse().getContentAsString();
		String secondResponse = second.getResponse().getContentAsString();
		assertEquals(200, first.getResponse().getStatus());
		assertEquals(first.getResponse().getStatus(), second.getResponse().getStatus());
		Object firstData = com.jayway.jsonpath.JsonPath.read(firstResponse, "$.data");
		Object secondData = com.jayway.jsonpath.JsonPath.read(secondResponse, "$.data");
		Object firstBlockId = com.jayway.jsonpath.JsonPath.read(firstResponse, "$.data.blockId");
		Object secondBlockId = com.jayway.jsonpath.JsonPath.read(secondResponse, "$.data.blockId");
		Object firstBlockedAt = com.jayway.jsonpath.JsonPath.read(firstResponse, "$.data.blockedAt");
		Object secondBlockedAt = com.jayway.jsonpath.JsonPath.read(secondResponse, "$.data.blockedAt");

		assertTrue(firstBlockId != null);
		assertTrue(firstBlockedAt != null);
		assertEquals(firstData, secondData);
		assertEquals(firstBlockId, secondBlockId);
		assertEquals(firstBlockedAt, secondBlockedAt);
	}

	private long insertUser(String nickname) {
		String email = "match-block-postgres-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, ?, ?, ?, ?)",
			email, "hash", nickname, Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long insertConfirmedProposal() {
		return jdbcTemplate.queryForObject(
			"insert into match_proposals (party_size, status, respond_by, confirmed_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?) returning id",
			Long.class,
			(short)2,
			"CONFIRMED",
			Timestamp.from(FIXED_TIME.plusSeconds(60)),
			Timestamp.from(FIXED_TIME),
			Timestamp.from(FIXED_TIME),
			Timestamp.from(FIXED_TIME));
	}

	private long insertActiveParty(long proposalId) {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (proposal_id, status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?) returning id",
			Long.class,
			proposalId, "ACTIVE", Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME),
			Timestamp.from(FIXED_TIME.plusSeconds(3_600)),
			Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
	}

	private void insertParticipant(long partyId, long userId, UUID participantRef) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, created_at) values (?, ?, ?, ?)",
			partyId, userId, participantRef, Timestamp.from(FIXED_TIME));
	}

	private MatchStorageSnapshot snapshot(long proposalId, long partyId) {
		return new MatchStorageSnapshot(
			jdbcTemplate.queryForMap(
				"select id, party_size, status, respond_by, confirmed_at, purge_after, created_at, updated_at from match_proposals where id = ?",
				proposalId),
			jdbcTemplate.queryForMap(
				"select id, proposal_id, status, preparing_started_at, chat_opened_at, closes_at, closed_at, purge_after, created_at, updated_at from match_parties where id = ?",
				partyId),
			jdbcTemplate.queryForList(
				"select party_id, user_id, participant_ref, left_at, created_at from match_party_participants where party_id = ? order by user_id",
				partyId));
	}

	private record MatchStorageSnapshot(
		Map<String, Object> proposal,
		Map<String, Object> party,
		List<Map<String, Object>> participants) {
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(new UsernamePasswordAuthenticationToken(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CoordinatedUserRowLockConfiguration {

		@Bean
		UserRowLockRaceGate userRowLockRaceGate() {
			return new UserRowLockRaceGate();
		}

		@Bean
		@Primary
		UserRowLockPort coordinatedUserRowLockPort(UserRowLockService delegate, UserRowLockRaceGate gate) {
			return new CoordinatedUserRowLockPort(delegate, gate);
		}
	}

	static final class CoordinatedUserRowLockPort implements UserRowLockPort {

		private final UserRowLockPort delegate;
		private final UserRowLockRaceGate gate;

		private CoordinatedUserRowLockPort(UserRowLockPort delegate, UserRowLockRaceGate gate) {
			this.delegate = delegate;
			this.gate = gate;
		}

		@Override
		public Set<Long> lockExistingUsersInAscendingOrder(Collection<Long> userIds) {
			UserRowLockRaceGate.Scenario scenario = gate.currentScenario();
			if (scenario == null) {
				return delegate.lockExistingUsersInAscendingOrder(userIds);
			}
			if (scenario.firstLockCall.compareAndSet(false, true)) {
				Set<Long> result = delegate.lockExistingUsersInAscendingOrder(userIds);
				scenario.firstLockAcquired.countDown();
				await(scenario.firstLockMayContinue);
				return result;
			}
			await(scenario.firstLockAcquired);
			scenario.secondLockAttempted.countDown();
			Set<Long> result = delegate.lockExistingUsersInAscendingOrder(userIds);
			scenario.secondLockReturned.countDown();
			return result;
		}

		private void await(CountDownLatch latch) {
			try {
				assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "동시성 동기화 지점에 도달하지 못했습니다.");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("동시성 동기화 대기 중 인터럽트되었습니다.", exception);
			}
		}
	}

	static final class UserRowLockRaceGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		Scenario activate() {
			Scenario scenario = new Scenario();
			assertTrue(activeScenario.compareAndSet(null, scenario));
			return scenario;
		}

		Scenario currentScenario() {
			return activeScenario.get();
		}

		void deactivate() {
			activeScenario.set(null);
		}

		static final class Scenario {

			private final AtomicBoolean firstLockCall = new AtomicBoolean();
			private final CountDownLatch firstLockAcquired = new CountDownLatch(1);
			private final CountDownLatch firstLockMayContinue = new CountDownLatch(1);
			private final CountDownLatch secondLockAttempted = new CountDownLatch(1);
			private final CountDownLatch secondLockReturned = new CountDownLatch(1);
		}
	}
}
