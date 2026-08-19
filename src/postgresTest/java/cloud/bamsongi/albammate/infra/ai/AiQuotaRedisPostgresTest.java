package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.assistant.contract.AssistantCostWarningEvent;

@Testcontainers
class AiQuotaRedisPostgresTest {

	@Container
	static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.4-alpine")
		.withExposedPorts(6379)
		.waitingFor(Wait.forListeningPort());

	private LettuceConnectionFactory firstFactory;
	private LettuceConnectionFactory secondFactory;
	private StringRedisTemplate firstTemplate;
	private StringRedisTemplate secondTemplate;
	private RedisAiQuotaLedger firstLedger;
	private RedisAiQuotaLedger secondLedger;
	private List<AssistantCostWarningEvent> warningEvents;

	@BeforeEach
	void setUp() {
		firstFactory = connectionFactory();
		secondFactory = connectionFactory();
		firstTemplate = new StringRedisTemplate(firstFactory);
		secondTemplate = new StringRedisTemplate(secondFactory);
		firstTemplate.afterPropertiesSet();
		secondTemplate.afterPropertiesSet();
		firstTemplate.execute((RedisCallback<Object>)connection -> {
			connection.serverCommands().flushDb();
			return null;
		});
		warningEvents = new ArrayList<>();
		firstLedger = new RedisAiQuotaLedger(firstTemplate, Duration.ofSeconds(10), warningEvents::add);
		secondLedger = new RedisAiQuotaLedger(secondTemplate, Duration.ofSeconds(10), warningEvents::add);
	}

	@AfterEach
	void tearDown() {
		firstLedger.shutdownCompletionRetryExecutor();
		secondLedger.shutdownCompletionRetryExecutor();
		firstFactory.destroy();
		secondFactory.destroy();
	}

	@Test
	void T4_actual_Redis_Lua는_서로_다른_인스턴스의_동시_예약과_완료를_원자적으로_처리한다() {
		Instant now = Instant.parse("2026-08-19T03:00:00Z");
		AiQuotaReservation first = firstLedger.reserve("redis-concurrent", now, new BigDecimal("0.10"));
		AiQuotaReservation second = secondLedger.reserve("redis-concurrent", now, new BigDecimal("0.10"));

		assertEquals(AiQuotaReservationStatus.ACQUIRED, first.status());
		assertEquals(AiQuotaReservationStatus.CONCURRENT_LIMIT_REACHED, second.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			secondLedger.complete(first, new BigDecimal("0.10")));

		AiQuotaReservation afterCompletion = secondLedger.reserve(
			"redis-concurrent", now, new BigDecimal("0.10"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, afterCompletion.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			firstLedger.complete(afterCompletion, new BigDecimal("0.10")));
	}

	@Test
	void T5_actual_Redis_Lua는_KST_월경계와_비용_cap을_적용한다() {
		Instant augustLastSecond = Instant.parse("2026-08-31T14:59:59Z");
		Instant septemberFirstSecond = Instant.parse("2026-08-31T15:00:00Z");

		AiQuotaReservation august = firstLedger.reserve("august-user", augustLastSecond, new BigDecimal("0.10"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, august.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			firstLedger.complete(august, new BigDecimal("0.10")));

		AiQuotaReservation september = firstLedger.reserve(
			"september-user", septemberFirstSecond, new BigDecimal("0.10"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, september.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			firstLedger.complete(september, new BigDecimal("0.10")));

		for (int index = 0; index < 48; index++) {
			AiQuotaReservation reservation = firstLedger.reserve(
				"cost-user-" + index,
				septemberFirstSecond,
				new BigDecimal("0.10"));
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED,
				firstLedger.complete(reservation, new BigDecimal("0.10")));
		}

		AiQuotaReservation fractional = firstLedger.reserve(
			"fractional-cost-user",
			septemberFirstSecond,
			new BigDecimal("0.05"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, fractional.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			firstLedger.complete(fractional, new BigDecimal("0.05")));
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			firstLedger.reserve("after-cost-cap", septemberFirstSecond, new BigDecimal("0.10")).status());
	}

	@Test
	void T5_actual_Redis_Lua는_4달러_경고를_여러_인스턴스에서도_한번만_전달한다() {
		Instant now = Instant.parse("2026-08-19T03:00:00Z");
		for (int index = 0; index < 39; index++) {
			AiQuotaReservation reservation = firstLedger.reserve(
				"warning-user-" + index, now, new BigDecimal("0.10"));
			assertEquals(AiQuotaCompletionStatus.COMPLETED,
				firstLedger.complete(reservation, new BigDecimal("0.10")));
		}
		assertEquals(0, warningEvents.size());

		AiQuotaReservation threshold = secondLedger.reserve("warning-threshold", now, new BigDecimal("0.10"));
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			secondLedger.complete(threshold, new BigDecimal("0.10")));
		assertEquals(1, warningEvents.size());
		assertEquals(new BigDecimal("4.00"), warningEvents.getFirst().estimatedCostUsd());
		assertEquals(new BigDecimal("4.00"), warningEvents.getFirst().warningThresholdUsd());

		AiQuotaReservation after = firstLedger.reserve("warning-after", now, new BigDecimal("0.10"));
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			firstLedger.complete(after, new BigDecimal("0.10")));
		assertEquals(1, warningEvents.size());
	}

	private LettuceConnectionFactory connectionFactory() {
		LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
			.commandTimeout(Duration.ofSeconds(2))
			.build();
		LettuceConnectionFactory factory = new LettuceConnectionFactory(
			new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)),
			clientConfiguration);
		factory.setShareNativeConnection(false);
		factory.afterPropertiesSet();
		factory.start();
		return factory;
	}
}
