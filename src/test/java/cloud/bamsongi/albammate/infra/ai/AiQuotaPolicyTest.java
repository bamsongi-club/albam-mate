package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;

class AiQuotaPolicyTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-19T03:00:00Z"), ZoneOffset.UTC);

	@Test
	void T4_Redis_확인전_장애는_SERVICE_UNAVAILABLE이고_provider와_quota와_fake_fallback을_시작하지_않는다() {
		CapturingProvider provider = new CapturingProvider();
		UnavailableAiQuotaLedger quotaLedger = new UnavailableAiQuotaLedger();
		AssistantIntentExtraction result = extractor(provider, quotaLedger,
			new AssistantIntentExtractorTest.RecordingUsageEventSink())
			.extract(request("member-a"));

		assertEquals(AssistantIntentStatus.SERVICE_UNAVAILABLE, result.status());
		assertEquals(0, provider.calls());
		assertEquals(0, quotaLedger.completedReservations());
		assertFalse(result.fallbackUsed());
	}

	@Test
	void T4_Redis_command_예외는_UNAVAILABLE로_닫히고_provider_예약으로_승격되지_않는다() {
		StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
		doThrow(new IllegalStateException("redis unavailable")).when(redisTemplate)
			.execute(any(RedisScript.class), anyList(), any(Object[].class));
		RedisAiQuotaLedger quotaLedger = new RedisAiQuotaLedger(redisTemplate);

		AiQuotaReservation reservation = quotaLedger.reserve(
			"member-a", CLOCK.instant(), new BigDecimal("0.10"));

		assertEquals(AiQuotaReservationStatus.UNAVAILABLE, reservation.status());
	}

	@Test
	void T4_quota_completion_일시_실패는_provider를_재호출하지_않고_같은_reservation을_재조정한다() {
		RetryingQuotaLedger quotaLedger = new RetryingQuotaLedger();
		CapturingProvider provider = new CapturingProvider();
		AssistantIntentExtraction result = extractor(
			provider,
			quotaLedger,
			new AssistantIntentExtractorTest.RecordingUsageEventSink())
			.extract(request("completion-retry"));

		assertEquals(AssistantIntentStatus.SUCCESS, result.status());
		assertEquals(1, provider.calls());
		assertEquals(2, quotaLedger.completionCalls());
	}

	@Test
	void T4_quota_completion이_계속_실패하면_복구_후_재조정을_예약한다() {
		PersistentlyUnavailableQuotaLedger quotaLedger = new PersistentlyUnavailableQuotaLedger();
		CapturingProvider provider = new CapturingProvider();
		AssistantIntentExtraction result = extractor(
			provider,
			quotaLedger,
			new AssistantIntentExtractorTest.RecordingUsageEventSink())
			.extract(request("completion-reconcile"));

		assertEquals(AssistantIntentStatus.SERVICE_UNAVAILABLE, result.status());
		assertEquals(1, provider.calls());
		assertEquals(2, quotaLedger.completionCalls());
		assertEquals(1, quotaLedger.scheduledRetries());
	}

	@Test
	void T5_KST_일월_사용자별_한도와_앱_비용_cap을_중복없이_적용하고_한도뒤_호출을_막는다() {
		CapturingProvider provider = new CapturingProvider();
		InMemoryAiQuotaLedger quotaLedger = new InMemoryAiQuotaLedger();
		AssistantIntentExtractor extractor = extractor(provider, quotaLedger,
			new AssistantIntentExtractorTest.RecordingUsageEventSink());

		for (int index = 0; index < 5; index++) {
			assertEquals(AssistantIntentStatus.SUCCESS, extractor.extract(request("daily-user")).status());
		}
		assertEquals(AssistantIntentStatus.QUOTA_EXCEEDED, extractor.extract(request("daily-user")).status());
		assertEquals(5, provider.calls());

		for (int index = 0; index < 45; index++) {
			extractor.extract(request("cost-user-" + index));
		}
		assertTrue(quotaLedger.warningRaised());
		assertEquals(new BigDecimal("5.00"), quotaLedger.currentMonthCost());
		assertEquals(AssistantIntentStatus.COST_CAP_REACHED, extractor.extract(request("after-cap")).status());
		assertEquals(50, provider.calls());
	}

	@Test
	void T5_KST_월_경계는_이전_월의_cost와_warning을_새_월로_이월하지_않는다() {
		InMemoryAiQuotaLedger quotaLedger = new InMemoryAiQuotaLedger();
		Instant augustLastSecond = Instant.parse("2026-08-31T14:59:59Z");
		Instant septemberFirstSecond = Instant.parse("2026-08-31T15:00:00Z");

		AiQuotaReservation august = quotaLedger.reserve("august-user", augustLastSecond);
		quotaLedger.complete(august, new BigDecimal("5.00"));
		assertEquals(new BigDecimal("5.00"), quotaLedger.monthlyCost(YearMonth.of(2026, 8)));
		assertTrue(quotaLedger.warningRaised(YearMonth.of(2026, 8)));

		AiQuotaReservation september = quotaLedger.reserve("september-user", septemberFirstSecond);
		assertEquals(AiQuotaReservationStatus.ACQUIRED, september.status());
		assertEquals(new BigDecimal("0.10"), quotaLedger.monthlyCost(YearMonth.of(2026, 9)));
		assertFalse(quotaLedger.warningRaised(YearMonth.of(2026, 9)));
	}

	@Test
	void T5_fake_quota도_예약_비용을_앱_cost_cap에_반영한다() {
		InMemoryAiQuotaLedger quotaLedger = new InMemoryAiQuotaLedger();
		for (int index = 0; index < 49; index++) {
			AiQuotaReservation reservation = quotaLedger.reserve(
				"estimated-cost-user-" + index,
				CLOCK.instant(),
				new BigDecimal("0.10"));
			assertEquals(AiQuotaReservationStatus.ACQUIRED, reservation.status());
			assertEquals(AiQuotaCompletionStatus.COMPLETED,
				quotaLedger.complete(reservation, new BigDecimal("0.10")));
		}

		AiQuotaReservation fractional = quotaLedger.reserve(
			"estimated-cost-fractional",
			CLOCK.instant(),
			new BigDecimal("0.05"));
		assertEquals(AiQuotaReservationStatus.ACQUIRED, fractional.status());
		assertEquals(AiQuotaCompletionStatus.COMPLETED,
			quotaLedger.complete(fractional, new BigDecimal("0.05")));

		assertEquals(AiQuotaReservationStatus.COST_CAP_REACHED,
			quotaLedger.reserve("estimated-cost-after-cap", CLOCK.instant(), new BigDecimal("0.10")).status());
	}

	@Test
	void T5_Redis_active_reservation_TTL은_provider_timeout과_유예를_포함한다() {
		RedisAiQuotaLedger quotaLedger = new RedisAiQuotaLedger(
			mock(StringRedisTemplate.class),
			Duration.ofSeconds(45));
		try {
			assertEquals(65, quotaLedger.activeReservationTtlSeconds());
		} finally {
			quotaLedger.shutdownCompletionRetryExecutor();
		}
	}

	@Test
	void T6_usage_event는_허용된_토큰_지연_상태_비용만_기록하고_원문과_식별자를_포함하지_않는다() {
		AssistantIntentExtractorTest.RecordingUsageEventSink usageEvents = new AssistantIntentExtractorTest.RecordingUsageEventSink();
		AssistantIntentExtraction result = extractor(new CapturingProvider(), new InMemoryAiQuotaLedger(), usageEvents)
			.extract(request("member-private"));

		assertEquals(AssistantIntentStatus.SUCCESS, result.status());
		AssistantUsageEvent event = usageEvents.events().getFirst();
		assertEquals(7, event.inputTokens());
		assertEquals(3, event.outputTokens());
		assertEquals(10, event.totalTokens());
		assertEquals("capturing-provider", event.provider());
		assertEquals("gpt-5.6-luna", event.model());
		assertEquals("AI-02-INSTRUCTION-V1", event.promptVersion());
		assertEquals("AI-02-SCHEMA-V1", event.schemaVersion());
		assertEquals("AI-02", event.feature());
		assertEquals("SUCCESS", event.status());
		assertEquals(new BigDecimal("0.10"), event.costUsd());
	}

	private AssistantIntentExtractor extractor(
		AiProviderClient provider,
		AiQuotaLedger quotaLedger,
		AssistantUsageEventSink usageEventSink) {
		return new AiProviderIntentExtractor(provider, quotaLedger, usageEventSink, AiProviderSettings.fakeDefaults(),
			CLOCK);
	}

	private AssistantIntentRequest request(String subject) {
		return AssistantIntentRequest.forUser(subject, "보드게임 추천", List.of());
	}

	private static final class CapturingProvider implements AiProviderClient {

		private int calls;

		@Override
		public AiProviderResponse propose(AiProviderPayload request) {
			calls++;
			return AiProviderResponse.success("RECOMMEND", List.of("STRATEGY"), 7, 3, new BigDecimal("0.10"));
		}

		@Override
		public String providerName() {
			return "capturing-provider";
		}

		int calls() {
			return calls;
		}
	}

	private static final class RetryingQuotaLedger implements AiQuotaLedger {

		private int completionCalls;

		@Override
		public AiQuotaReservation reserve(String quotaSubject, Instant now) {
			return AiQuotaReservation.acquired(quotaSubject);
		}

		@Override
		public AiQuotaCompletionStatus complete(AiQuotaReservation reservation, BigDecimal costUsd) {
			completionCalls++;
			return completionCalls == 1
				? AiQuotaCompletionStatus.UNAVAILABLE
				: AiQuotaCompletionStatus.COMPLETED;
		}

		int completionCalls() {
			return completionCalls;
		}
	}

	private static final class PersistentlyUnavailableQuotaLedger implements AiQuotaLedger {

		private int completionCalls;
		private int scheduledRetries;

		@Override
		public AiQuotaReservation reserve(String quotaSubject, Instant now) {
			return AiQuotaReservation.acquired(quotaSubject);
		}

		@Override
		public AiQuotaCompletionStatus complete(AiQuotaReservation reservation, BigDecimal costUsd) {
			completionCalls++;
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}

		@Override
		public void scheduleCompletionRetry(AiQuotaReservation reservation, BigDecimal costUsd) {
			scheduledRetries++;
		}

		int completionCalls() {
			return completionCalls;
		}

		int scheduledRetries() {
			return scheduledRetries;
		}
	}
}
