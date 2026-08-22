package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;

@Testcontainers
class AiQuotaRedisPostgresTest {

	private static final Instant JANUARY_31_KST = Instant.parse("2026-01-31T14:59:00Z");
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

	private LettuceConnectionFactory connectionFactory;
	private StringRedisTemplate redis;
	private RedisAiQuotaLedger ledger;

	@BeforeEach
	void setUp() {
		connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
			REDIS.getHost(), REDIS.getMappedPort(6379)));
		connectionFactory.afterPropertiesSet();
		redis = new StringRedisTemplate(connectionFactory);
		redis.afterPropertiesSet();
		redis.getConnectionFactory().getConnection().serverCommands().flushAll();
		ledger = new RedisAiQuotaLedger(redis, event -> {});
	}

	@AfterEach
	void tearDown() {
		connectionFactory.destroy();
	}

	@Test
	void T1_여러_ledger_인스턴스도_KST_일_10회_월_150회와_사용자별_동시성을_공유한다() {
		RedisAiQuotaLedger otherInstance = new RedisAiQuotaLedger(redis, event -> {});
		for (int index = 0; index < 10; index++) {
			AiQuotaReservation reservation = ledger.reserve("sensitive-user-id", JANUARY_31_KST, BigDecimal.ZERO);
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED, otherInstance.complete(reservation, BigDecimal.ZERO));
		}
		assertEquals(AiQuotaReservationStatus.USER_LIMIT_REACHED,
			otherInstance.reserve("sensitive-user-id", JANUARY_31_KST, BigDecimal.ZERO).status());
		assertEquals(AiQuotaReservationStatus.ACQUIRED,
			otherInstance.reserve("sensitive-user-id", Instant.parse("2026-01-31T15:00:00Z"), BigDecimal.ZERO)
				.status());
		for (int index = 0; index < 150; index++) {
			Instant day = Instant.parse("2026-01-01T00:00:00Z").plusSeconds((index / 10) * 86_400L);
			AiQuotaReservation reservation = ledger.reserve("monthly-user", day, BigDecimal.ZERO);
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED, otherInstance.complete(reservation, BigDecimal.ZERO));
		}
		assertEquals(AiQuotaReservationStatus.USER_LIMIT_REACHED,
			ledger.reserve("monthly-user", Instant.parse("2026-01-30T00:00:00Z"), BigDecimal.ZERO).status());
		assertEquals(AiQuotaReservationStatus.ACQUIRED,
			ledger.reserve("monthly-user", Instant.parse("2026-01-31T15:00:00Z"), BigDecimal.ZERO).status());
		Set<String> keys = redis.keys("albam:ai:quota:*");
		assertTrue(keys != null && !keys.isEmpty());
		assertFalse(keys.stream().collect(Collectors.joining(" ")).contains("sensitive-user-id"));
	}

	@Test
	void T2_서로_다른_ledger_인스턴스의_동시_예약은_정확히_하나만_획득한다() throws Exception {
		RedisAiQuotaLedger otherInstance = new RedisAiQuotaLedger(redis, event -> {});
		CountDownLatch ready = new CountDownLatch(2);
		CountDownLatch start = new CountDownLatch(1);
		try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
			Future<AiQuotaReservation> first = executor.submit(() -> reserveWhenStarted(ledger, ready, start));
			Future<AiQuotaReservation> second = executor.submit(() -> reserveWhenStarted(otherInstance, ready, start));
			assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
			start.countDown();

			List<AiQuotaReservationStatus> statuses = List.of(first.get().status(), second.get().status());
			assertEquals(1, statuses.stream().filter(status -> status == AiQuotaReservationStatus.ACQUIRED).count());
			assertEquals(1,
				statuses.stream().filter(status -> status == AiQuotaReservationStatus.CONCURRENT_LIMIT_REACHED)
					.count());
		}
	}

	@Test
	void T3_Redis_고정_USD_0_10_예약은_40번째_경고_50번째_허용_51번째_차단을_원자적으로_보장한다() {
		java.util.concurrent.atomic.AtomicInteger warnings = new java.util.concurrent.atomic.AtomicInteger();
		ledger = new RedisAiQuotaLedger(redis, event -> warnings.incrementAndGet());
		for (int index = 1; index <= 50; index++) {
			AiQuotaReservation reservation = ledger.reserve("cost-user-" + index, JANUARY_31_KST,
				new BigDecimal("0.10"));
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED, ledger.complete(reservation, new BigDecimal("0.01")));
			assertEquals(index >= 40 ? 1 : 0, warnings.get());
		}
		assertEquals(1, warnings.get());
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			ledger.reserve("cost-user-51", JANUARY_31_KST, new BigDecimal("0.10")).status());
	}

	@Test
	void T3_경고_sink_실패에도_예약과_completion을_성공으로_반환한다() {
		ledger = new RedisAiQuotaLedger(redis, event -> {
			throw new IllegalStateException("warning delivery unavailable");
		});
		AiQuotaReservation reserveWarningReservation = ledger.reserve(
			"user-a", JANUARY_31_KST, new BigDecimal("4.00"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, reserveWarningReservation.status());
		assertFalse(reserveWarningReservation.reservationToken().isBlank());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			ledger.complete(reserveWarningReservation, new BigDecimal("4.00")));

		redis.getConnectionFactory().getConnection().serverCommands().flushAll();
		AiQuotaReservation completionWarningReservation = ledger.reserve("user-b", JANUARY_31_KST, BigDecimal.ZERO);
		assertEquals(AiQuotaReservationStatus.ACQUIRED, completionWarningReservation.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			ledger.complete(completionWarningReservation, new BigDecimal("4.00")));
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			ledger.complete(completionWarningReservation, new BigDecimal("4.00")));
	}

	@Test
	void T2_재기동과_active_TTL_이후에도_같은_token_completion은_고정_예약을_재정산하지_않는다() {
		ledger = new RedisAiQuotaLedger(redis, event -> {}, Duration.ofMillis(25));
		AiQuotaReservation expiredActive = ledger.reserve("ttl-user", JANUARY_31_KST, BigDecimal.ZERO);
		assertEquals(AiQuotaReservationStatus.ACQUIRED, expiredActive.status());
		awaitActiveTtl();
		AiQuotaReservation afterTtl = ledger.reserve("ttl-user", JANUARY_31_KST, BigDecimal.ZERO);
		assertEquals(AiQuotaReservationStatus.ACQUIRED, afterTtl.status());

		AiQuotaReservation reservation = ledger.reserve("user-a", JANUARY_31_KST, new BigDecimal("0.10"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
		ledger.scheduleCompletionRetry(reservation, new BigDecimal("0.20"));
		ledger.shutdownCompletionRetryExecutor();
		RedisAiQuotaLedger afterRestart = new RedisAiQuotaLedger(redis, event -> {});
		assertEquals(AiQuotaCompletionStatus.COMPLETED, afterRestart.complete(expiredActive, BigDecimal.ZERO));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, afterRestart.complete(afterTtl, BigDecimal.ZERO));
		awaitCostCents("10");
		assertEquals(AiQuotaCompletionStatus.COMPLETED, afterRestart.complete(reservation, new BigDecimal("0.20")));
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			afterRestart.reserve("user-b", JANUARY_31_KST, new BigDecimal("4.91")).status());
	}

	@Test
	void T4_실제_Redis_ledger에서_KST_월_150회_완료_뒤_151번째_AI_의도는_provider_호출_없이_quota를_초과한다() {
		YearMonth quotaMonth = nextThirtyDayMonth();
		java.util.concurrent.atomic.AtomicInteger providerCalls = new java.util.concurrent.atomic.AtomicInteger();
		AiProviderClient provider = request -> {
			providerCalls.incrementAndGet();
			return AiProviderResponse.success("RECOMMEND", List.of("STRATEGY"), 1, 1, BigDecimal.ZERO);
		};
		AiProviderSettings settings = zeroCostFakeSettings();
		AssistantIntentRequest request = AssistantIntentRequest.forUser(
			"monthly-quota-user", "전략 게임 추천", List.of());
		for (int index = 0; index < 150; index++) {
			Instant callTime = quotaMonth.atDay(index / 5 + 1).atTime(12, 0).atZone(KST).toInstant();
			AiProviderIntentExtractor extractor = new AiProviderIntentExtractor(
				provider, ledger, event -> {}, settings, Clock.fixed(callTime, KST));
			assertEquals(AssistantIntentStatus.SUCCESS, extractor.extract(request).status());
		}

		int callsBeforeRejectedIntent = providerCalls.get();
		Instant rejectedCallTime = quotaMonth.atDay(30).atTime(12, 0).atZone(KST).toInstant();
		AiProviderIntentExtractor rejectedExtractor = new AiProviderIntentExtractor(
			provider, ledger, event -> {}, settings, Clock.fixed(rejectedCallTime, KST));

		assertEquals(AssistantIntentStatus.QUOTA_EXCEEDED, rejectedExtractor.extract(request).status());
		assertEquals(150, callsBeforeRejectedIntent);
		assertEquals(callsBeforeRejectedIntent, providerCalls.get());
	}

	private YearMonth nextThirtyDayMonth() {
		YearMonth quotaMonth = YearMonth.now(KST).plusMonths(1);
		while (quotaMonth.lengthOfMonth() < 30) {
			quotaMonth = quotaMonth.plusMonths(1);
		}
		return quotaMonth;
	}

	private AiProviderSettings zeroCostFakeSettings() {
		return new AiProviderSettings(
			"fake", true, true, true, true, "", "", "gpt-5.6-luna", Duration.ofSeconds(10), 0, false,
			"TEST-PRICING-V1", BigDecimal.ONE, BigDecimal.ONE, 4096, 256, BigDecimal.ZERO, "unverified");
	}

	private AiQuotaReservation reserveWhenStarted(
		RedisAiQuotaLedger quotaLedger, CountDownLatch ready, CountDownLatch start) {
		ready.countDown();
		try {
			if (!start.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
				throw new AssertionError("동시 예약 시작 신호를 받지 못했습니다");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("동시 예약 중 인터럽트되었습니다", exception);
		}
		return quotaLedger.reserve("concurrent-user", JANUARY_31_KST, BigDecimal.ZERO);
	}

	private void awaitCostCents(String expectedCostCents) {
		String costKey = "albam:ai:quota:v1:{v1}:cost:2026-01";
		for (int attempt = 0; attempt < 30; attempt++) {
			if (expectedCostCents.equals(redis.opsForValue().get(costKey))) {
				return;
			}
			awaitActiveTtl();
		}
		assertEquals(expectedCostCents, redis.opsForValue().get(costKey));
	}

	private void awaitActiveTtl() {
		try {
			Thread.sleep(75);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("active reservation TTL 대기 중 인터럽트되었습니다", exception);
		}
	}
}
