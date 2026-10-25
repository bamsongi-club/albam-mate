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
	void T1_InMemory는_KST_일_5_월_150_동시_1회와_경계를_동일하게_적용한다() {
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
			monthlyLedger.reserve("monthly-user", Instant.parse("2026-01-31T00:00:00Z"), BigDecimal.ZERO).status());
	}

	@Test
	void T2_월_최대_비용_예약은_40번째에_한번_경고하고_5달러에서_초과_예약을_차단한다() {
		AtomicInteger warnings = new AtomicInteger();
		InMemoryAiQuotaLedger ledger = new InMemoryAiQuotaLedger(event -> warnings.incrementAndGet());
		for (int index = 1; index <= 50; index++) {
			AiQuotaReservation reservation = ledger.reserve("user-" + index, JANUARY_31_KST, new BigDecimal("0.10"));
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(index >= 40 ? 1 : 0, warnings.get());
		}
		assertEquals(1, warnings.get());
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			ledger.reserve("user-51", JANUARY_31_KST, new BigDecimal("0.10")).status());
	}

	@Test
	void T2_경고_sink_실패에도_예약과_completion을_성공으로_반환한다() {
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
	void T4_usage가_불명확한_provider_실패는_slot을_보류하고_completion_정산은_멱등으로_유지한다() {
		InMemoryAiQuotaLedger ledger = new InMemoryAiQuotaLedger(event -> {});
		AiProviderIntentExtractor extractor = new AiProviderIntentExtractor(
			request -> AiProviderResponse.failure(AiProviderFailure.TIMEOUT), ledger, event -> {},
			AiProviderSettings.fakeDefaults(), java.time.Clock.fixed(JANUARY_31_KST, ZoneOffset.UTC));
		assertEquals(AssistantIntentStatus.PROVIDER_TIMEOUT,
			extractor.extract(AssistantIntentRequest.forUser("user-a", "전략 게임 추천", List.of())).status());
		assertEquals(AssistantIntentStatus.QUOTA_EXCEEDED,
			extractor.extract(AssistantIntentRequest.forUser("user-a", "전략 게임 추천", List.of())).status());

		InMemoryAiQuotaLedger settlementLedger = new InMemoryAiQuotaLedger(event -> {});
		AiQuotaReservation reservation = settlementLedger.reserve("user-b", JANUARY_31_KST, new BigDecimal("4.90"));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, settlementLedger.complete(reservation, new BigDecimal("0.01")));
		assertEquals(AiQuotaCompletionStatus.COMPLETED, settlementLedger.complete(reservation, new BigDecimal("0.01")));
		AiQuotaReservation afterRelease = settlementLedger.reserve(
			"user-c", JANUARY_31_KST, new BigDecimal("4.99"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, afterRelease.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			settlementLedger.complete(afterRelease, new BigDecimal("0.01")));

		InMemoryAiQuotaLedger overrunLedger = new InMemoryAiQuotaLedger(event -> {});
		AiQuotaReservation overrunReservation = overrunLedger.reserve(
			"user-d", JANUARY_31_KST, new BigDecimal("4.99"));
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			overrunLedger.complete(overrunReservation, new BigDecimal("5.01")));
		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			overrunLedger.reserve("user-e", JANUARY_31_KST, BigDecimal.ZERO).status());
		assertTrue(reservation.reservationToken().length() > 10);
		assertFalse(reservation.quotaSubject().isBlank());
	}
}
