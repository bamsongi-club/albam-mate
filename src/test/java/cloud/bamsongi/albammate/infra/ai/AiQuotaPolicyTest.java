package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;

class AiQuotaPolicyTest {

	private static final Instant JANUARY_31_KST = Instant.parse("2026-01-31T14:59:00Z");

	@Test
	void T1_Redis_예약_전_장애는_provider_호출과_부수효과_없이_fail_closed한다() {
		StringRedisTemplate unavailableRedis = org.mockito.Mockito.mock(StringRedisTemplate.class);
		org.mockito.Mockito.when(unavailableRedis.execute(org.mockito.ArgumentMatchers.any(),
			org.mockito.ArgumentMatchers.anyList(), org.mockito.ArgumentMatchers.<String>any()))
			.thenThrow(new IllegalStateException("redis unavailable"));
		AtomicInteger providerCalls = new AtomicInteger();
		AtomicInteger usageEvents = new AtomicInteger();
		AiProviderIntentExtractor extractor = new AiProviderIntentExtractor(
			request -> {
				providerCalls.incrementAndGet();
				return AiProviderResponse.success("RECOMMEND", List.of("STRATEGY"), 1, 1,
					new BigDecimal("0.10"));
			},
			new RedisAiQuotaLedger(unavailableRedis, event -> {
				throw new AssertionError("cost warning must not be emitted");
			}),
			event -> usageEvents.incrementAndGet(), AiProviderSettings.fakeDefaults(),
			java.time.Clock.fixed(JANUARY_31_KST, ZoneOffset.UTC));

		assertEquals(AssistantIntentStatus.SERVICE_UNAVAILABLE,
			extractor.extract(AssistantIntentRequest.forUser("user-1", "전략 게임 추천", List.of())).status());
		assertEquals(0, providerCalls.get());
		assertEquals(0, usageEvents.get());
	}

	@Test
	void T2_KST_일월_경계와_사용자별_동시성_quota를_원자적으로_분리한다() {
		InMemoryAiQuotaLedger ledger = new InMemoryAiQuotaLedger(event -> {});
		Instant beforeMidnight = Instant.parse("2026-01-31T14:59:00Z");
		for (int index = 0; index < 5; index++) {
			AiQuotaReservation reservation = ledger.reserve("user-a", beforeMidnight, BigDecimal.ZERO);
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED, ledger.complete(reservation, BigDecimal.ZERO));
		}
		assertEquals(AiQuotaReservationStatus.USER_LIMIT_REACHED,
			ledger.reserve("user-a", beforeMidnight, BigDecimal.ZERO).status());

		Instant afterMidnight = Instant.parse("2026-01-31T15:00:00Z");
		AiQuotaReservation february = ledger.reserve("user-a", afterMidnight, BigDecimal.ZERO);
		assertEquals(AiQuotaReservationStatus.ACQUIRED, february.status());
		AiQuotaReservation otherUser = ledger.reserve("user-b", beforeMidnight, BigDecimal.ZERO);
		assertEquals(AiQuotaReservationStatus.ACQUIRED, otherUser.status());
		assertEquals(AiQuotaReservationStatus.CONCURRENT_LIMIT_REACHED,
			ledger.reserve("user-a", afterMidnight, BigDecimal.ZERO).status());

		InMemoryAiQuotaLedger monthlyLedger = new InMemoryAiQuotaLedger(event -> {});
		for (int index = 0; index < 150; index++) {
			Instant day = Instant.parse("2026-01-01T00:00:00Z").plusSeconds((index / 5) * 86_400L);
			AiQuotaReservation reservation = monthlyLedger.reserve("monthly-user", day, BigDecimal.ZERO);
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED, monthlyLedger.complete(reservation, BigDecimal.ZERO));
		}
		assertEquals(AiQuotaReservationStatus.USER_LIMIT_REACHED,
			monthlyLedger.reserve("monthly-user", Instant.parse("2026-01-30T00:00:00Z"), BigDecimal.ZERO).status());
	}

	@Test
	void T3_월간_비용_경고는_한번만_전달하고_hard_cap_뒤_예약을_막는다() {
		AtomicInteger warnings = new AtomicInteger();
		InMemoryAiQuotaLedger ledger = new InMemoryAiQuotaLedger(event -> warnings.incrementAndGet());
		AiQuotaReservation first = ledger.reserve("user-a", JANUARY_31_KST, new BigDecimal("4.50"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, first.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED, ledger.complete(first, new BigDecimal("4.00")));
		assertEquals(1, warnings.get());

		AiQuotaReservation second = ledger.reserve("user-b", JANUARY_31_KST, new BigDecimal("1.00"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, second.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED, ledger.complete(second, new BigDecimal("1.00")));
		assertEquals(1, warnings.get());
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			ledger.reserve("user-c", JANUARY_31_KST, new BigDecimal("0.01")).status());
	}

	@Test
	void T4_provider_실패도_quota에_포함하고_completion은_중복_청구하지_않는다() {
		InMemoryAiQuotaLedger ledger = new InMemoryAiQuotaLedger(event -> {});
		AiProviderIntentExtractor extractor = new AiProviderIntentExtractor(
			request -> AiProviderResponse.failure(AiProviderFailure.TIMEOUT), ledger, event -> {},
			AiProviderSettings.fakeDefaults(), java.time.Clock.fixed(JANUARY_31_KST, ZoneOffset.UTC));
		for (int index = 0; index < 5; index++) {
			assertEquals(AssistantIntentStatus.PROVIDER_TIMEOUT,
				extractor.extract(AssistantIntentRequest.forUser("user-a", "전략 게임 추천", List.of())).status());
		}
		assertEquals(AssistantIntentStatus.QUOTA_EXCEEDED,
			extractor.extract(AssistantIntentRequest.forUser("user-a", "전략 게임 추천", List.of())).status());

		AiQuotaReservation reservation = ledger.reserve("user-b", JANUARY_31_KST, new BigDecimal("0.10"));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, ledger.complete(reservation, new BigDecimal("0.20")));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, ledger.complete(reservation, new BigDecimal("0.20")));
		assertTrue(reservation.reservationToken().length() > 10);
		assertFalse(reservation.quotaSubject().isBlank());
	}
}
