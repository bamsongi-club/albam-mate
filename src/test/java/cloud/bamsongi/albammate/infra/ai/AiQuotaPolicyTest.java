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
	void T2_Redis_예약_전_장애는_provider_호출과_부수효과_없이_fail_closed한다() {
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
	void T1_InMemory는_KST_일_10_월_150_동시_1회와_경계를_동일하게_적용한다() {
		InMemoryAiQuotaLedger ledger = new InMemoryAiQuotaLedger(event -> {});
		Instant beforeMidnight = Instant.parse("2026-01-31T14:59:00Z");
		for (int index = 0; index < 10; index++) {
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
			Instant day = Instant.parse("2026-01-01T00:00:00Z").plusSeconds((index / 10) * 86_400L);
			AiQuotaReservation reservation = monthlyLedger.reserve("monthly-user", day, BigDecimal.ZERO);
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED, monthlyLedger.complete(reservation, BigDecimal.ZERO));
		}
		assertEquals(AiQuotaReservationStatus.USER_LIMIT_REACHED,
			monthlyLedger.reserve("monthly-user", Instant.parse("2026-01-30T00:00:00Z"), BigDecimal.ZERO).status());
	}

	@Test
	void T3_월_40번째_고정_예약에서_한번만_경고하고_50까지_허용한_뒤_51번째를_차단한다() {
		AtomicInteger warnings = new AtomicInteger();
		InMemoryAiQuotaLedger ledger = new InMemoryAiQuotaLedger(event -> warnings.incrementAndGet());
		for (int index = 1; index <= 50; index++) {
			AiQuotaReservation reservation = ledger.reserve("user-" + index, JANUARY_31_KST, new BigDecimal("0.10"));
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED, ledger.complete(reservation, new BigDecimal("0.20")));
			assertEquals(index >= 40 ? 1 : 0, warnings.get());
		}
		assertEquals(1, warnings.get());
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			ledger.reserve("user-51", JANUARY_31_KST, new BigDecimal("0.10")).status());
	}

	@Test
	void T3_경고_sink_실패에도_예약과_completion을_성공으로_반환한다() {
		InMemoryAiQuotaLedger reserveWarningLedger = new InMemoryAiQuotaLedger(event -> {
			throw new IllegalStateException("warning delivery unavailable");
		});
		AiQuotaReservation reserveWarningReservation = reserveWarningLedger.reserve(
			"user-a", JANUARY_31_KST, new BigDecimal("4.00"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, reserveWarningReservation.status());
		assertFalse(reserveWarningReservation.reservationToken().isBlank());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			reserveWarningLedger.complete(reserveWarningReservation, new BigDecimal("4.00")));

		InMemoryAiQuotaLedger completionWarningLedger = new InMemoryAiQuotaLedger(event -> {
			throw new IllegalStateException("warning delivery unavailable");
		});
		AiQuotaReservation completionWarningReservation = completionWarningLedger.reserve(
			"user-b", JANUARY_31_KST, BigDecimal.ZERO);
		assertEquals(AiQuotaReservationStatus.ACQUIRED, completionWarningReservation.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			completionWarningLedger.complete(completionWarningReservation, new BigDecimal("4.00")));
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			completionWarningLedger.complete(completionWarningReservation, new BigDecimal("4.00")));
	}

	@Test
	void T2_provider_실패도_고정_예약을_소비하고_completion은_응답비용으로_재정산하지_않는다() {
		InMemoryAiQuotaLedger ledger = new InMemoryAiQuotaLedger(event -> {});
		AiProviderIntentExtractor extractor = new AiProviderIntentExtractor(
			request -> AiProviderResponse.failure(AiProviderFailure.TIMEOUT), ledger, event -> {},
			AiProviderSettings.fakeDefaults(), java.time.Clock.fixed(JANUARY_31_KST, ZoneOffset.UTC));
		for (int index = 0; index < 10; index++) {
			assertEquals(AssistantIntentStatus.PROVIDER_TIMEOUT,
				extractor.extract(AssistantIntentRequest.forUser("user-a", "전략 게임 추천", List.of())).status());
		}
		assertEquals(AssistantIntentStatus.QUOTA_EXCEEDED,
			extractor.extract(AssistantIntentRequest.forUser("user-a", "전략 게임 추천", List.of())).status());

		InMemoryAiQuotaLedger fixedCostLedger = new InMemoryAiQuotaLedger(event -> {});
		AiQuotaReservation reservation = fixedCostLedger.reserve("user-b", JANUARY_31_KST, new BigDecimal("0.10"));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, fixedCostLedger.complete(reservation, new BigDecimal("0.20")));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, fixedCostLedger.complete(reservation, new BigDecimal("0.20")));
		for (int index = 0; index < 49; index++) {
			AiQuotaReservation fixedReservation = fixedCostLedger.reserve(
				"fixed-cost-" + index, JANUARY_31_KST, new BigDecimal("0.10"));
			assertEquals(AiQuotaReservationStatus.ACQUIRED, fixedReservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED,
				fixedCostLedger.complete(fixedReservation, new BigDecimal("0.01")));
		}
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			fixedCostLedger.reserve("fixed-cost-51", JANUARY_31_KST, new BigDecimal("0.10")).status());
		assertTrue(reservation.reservationToken().length() > 10);
		assertFalse(reservation.quotaSubject().isBlank());
	}
}
