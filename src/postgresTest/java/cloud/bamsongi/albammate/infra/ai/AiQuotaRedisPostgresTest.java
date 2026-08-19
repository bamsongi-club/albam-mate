package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
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

@Testcontainers
class AiQuotaRedisPostgresTest {

	private static final Instant JANUARY_31_KST = Instant.parse("2026-01-31T14:59:00Z");

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
	void T2_여러_ledger_인스턴스도_KST_경계와_사용자별_동시성을_공유한다() {
		RedisAiQuotaLedger otherInstance = new RedisAiQuotaLedger(redis, event -> {});
		for (int index = 0; index < 5; index++) {
			AiQuotaReservation reservation = ledger.reserve("sensitive-user-id", JANUARY_31_KST, BigDecimal.ZERO);
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED, otherInstance.complete(reservation, BigDecimal.ZERO));
		}
		assertEquals(AiQuotaReservationStatus.USER_LIMIT_REACHED,
			otherInstance.reserve("sensitive-user-id", JANUARY_31_KST, BigDecimal.ZERO).status());
		assertEquals(AiQuotaReservationStatus.ACQUIRED,
			otherInstance.reserve("sensitive-user-id", Instant.parse("2026-01-31T15:00:00Z"), BigDecimal.ZERO)
				.status());
		Set<String> keys = redis.keys("albam:ai:quota:*");
		assertTrue(keys != null && !keys.isEmpty());
		assertFalse(keys.stream().collect(Collectors.joining(" ")).contains("sensitive-user-id"));
	}

	@Test
	void T3_Redis_비용_예약은_경고를_한번만_기록하고_hard_cap을_차단한다() {
		java.util.concurrent.atomic.AtomicInteger warnings = new java.util.concurrent.atomic.AtomicInteger();
		ledger = new RedisAiQuotaLedger(redis, event -> warnings.incrementAndGet());
		AiQuotaReservation first = ledger.reserve("user-a", JANUARY_31_KST, new BigDecimal("4.50"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, first.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED, ledger.complete(first, new BigDecimal("4.00")));
		AiQuotaReservation second = ledger.reserve("user-b", JANUARY_31_KST, new BigDecimal("1.00"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, second.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED, ledger.complete(second, new BigDecimal("1.00")));
		assertEquals(1, warnings.get());
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			ledger.reserve("user-c", JANUARY_31_KST, new BigDecimal("0.01")).status());
	}

	@Test
	void T4_재기동과_active_TTL_이후에도_같은_token_completion은_한번만_재조정한다() {
		ledger = new RedisAiQuotaLedger(redis, event -> {}, Duration.ofMillis(25));
		AiQuotaReservation expiredActive = ledger.reserve("ttl-user", JANUARY_31_KST, BigDecimal.ZERO);
		assertEquals(AiQuotaReservationStatus.ACQUIRED, expiredActive.status());
		awaitActiveTtl();
		AiQuotaReservation afterTtl = ledger.reserve("ttl-user", JANUARY_31_KST, BigDecimal.ZERO);
		assertEquals(AiQuotaReservationStatus.ACQUIRED, afterTtl.status());

		AiQuotaReservation reservation = ledger.reserve("user-a", JANUARY_31_KST, new BigDecimal("0.10"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
		StringRedisTemplate unavailableRedis = org.mockito.Mockito.mock(StringRedisTemplate.class);
		org.mockito.Mockito.when(unavailableRedis.execute(org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.<String>any()))
			.thenThrow(new IllegalStateException("redis temporarily unavailable"));
		assertEquals(AiQuotaCompletionStatus.UNAVAILABLE,
			new RedisAiQuotaLedger(unavailableRedis, event -> {}).complete(reservation, new BigDecimal("0.20")));
		RedisAiQuotaLedger afterRestart = new RedisAiQuotaLedger(redis, event -> {});
		assertEquals(AiQuotaCompletionStatus.COMPLETED, afterRestart.complete(expiredActive, BigDecimal.ZERO));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, afterRestart.complete(afterTtl, BigDecimal.ZERO));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, afterRestart.complete(reservation, new BigDecimal("0.20")));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, afterRestart.complete(reservation, new BigDecimal("0.20")));
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			afterRestart.reserve("user-b", JANUARY_31_KST, new BigDecimal("4.81")).status());
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
