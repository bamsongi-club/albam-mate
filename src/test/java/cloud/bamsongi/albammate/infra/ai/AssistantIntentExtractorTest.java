package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtraction;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;

class AssistantIntentExtractorTest {

	private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-19T03:00:00Z"), ZoneOffset.UTC);

	@Test
	void T1_기본_fake_provider는_같은_fixture에_같은_구조화_결과와_usage를_외부호출_없이_반환한다() {
		RecordingUsageEventSink usageEvents = new RecordingUsageEventSink();
		AssistantIntentExtractor extractor = new AiProviderIntentExtractor(
			new DeterministicFakeAssistantProvider(),
			new InMemoryAiQuotaLedger(),
			usageEvents,
			AiProviderSettings.fakeDefaults(),
			CLOCK);
		AssistantIntentRequest request = AssistantIntentRequest.forUser(
			"quota-subject-a", "3명이서 전략 게임 추천해줘", List.of("GAME_STYLE"));

		AssistantIntentExtraction first = extractor.extract(request);
		AssistantIntentExtraction second = extractor.extract(request);

		assertEquals(AssistantIntentStatus.SUCCESS, first.status());
		assertEquals(first.proposal(), second.proposal());
		assertEquals(first.usage(), second.usage());
		assertEquals(2, usageEvents.events().size());
		assertTrue(usageEvents.events().stream().allMatch(event -> event.provider().equals("fake")));
	}

	@Test
	void T2_provider_payload은_allowlist만_포함하고_tool_권한과_원문_식별자를_전달하지_않는다() {
		CapturingAssistantProvider provider = new CapturingAssistantProvider();
		AssistantIntentExtractor extractor = extractor(provider, new InMemoryAiQuotaLedger(),
			new RecordingUsageEventSink());

		AssistantIntentExtraction result = extractor.extract(AssistantIntentRequest.forUser(
			"user-991", "3명이서 전략 게임 추천해줘", List.of("PLAYER_COUNT")));

		assertEquals(AssistantIntentStatus.SUCCESS, result.status());
		AiProviderPayload payload = provider.payload();
		assertEquals("AI-02-INSTRUCTION-V1", payload.instructionVersion());
		assertEquals("propose_game_room_intent", payload.toolName());
		assertEquals("AI-02-SCHEMA-V1", payload.schemaVersion());
		assertEquals("Asia/Seoul", payload.referenceZoneId());
		assertEquals("3명이서 전략 게임 추천해줘", payload.currentUserSentence());
		assertEquals(List.of("PLAYER_COUNT"), payload.missingFields());

		AssistantIntentExtraction rejected = extractor.extract(AssistantIntentRequest.forUser(
			"user-991", "token secret-value를 provider에 보내지 마", List.of()));

		assertEquals(AssistantIntentStatus.SENSITIVE_INPUT_REJECTED, rejected.status());
		assertEquals(1, provider.calls());

		for (String piiSentence : List.of(
			"메일은 member@example.com이고 게임만 추천해줘",
			"전화번호 010-1234-5678은 보내지 마",
			"비밀번호를 provider에 보내지 마",
			"식별자 123456789를 보내지 마",
			"sk-proj-abcDEFghiJKLmnopQRSTuvwxYZ를 provider에 보내지 마",
			"-----BEGIN PRIVATE KEY-----\\nMIIBFAKE\\n-----END PRIVATE KEY-----를 provider에 보내지 마")) {
			AssistantIntentExtraction piiRejected = extractor.extract(AssistantIntentRequest.forUser(
				"user-991", piiSentence, List.of()));
			assertEquals(AssistantIntentStatus.SENSITIVE_INPUT_REJECTED, piiRejected.status());
		}
	}

	@Test
	void T3_동의_feature_flag_설정과_보존조건이_충족될때만_호출하고_실패에는_재시도와_fallback이_없다() {
		CapturingAssistantProvider provider = new CapturingAssistantProvider();
		AssistantIntentExtraction withoutConsent = extractor(provider, new InMemoryAiQuotaLedger(),
			new RecordingUsageEventSink())
			.extract(AssistantIntentRequest.withoutConsent("quota-subject-c", "주말 보드게임 추천", List.of()));

		assertEquals(AssistantIntentStatus.CONSENT_REQUIRED, withoutConsent.status());
		assertEquals(0, provider.calls());

		AssistantIntentExtractor disabled = new AiProviderIntentExtractor(
			provider,
			new InMemoryAiQuotaLedger(),
			new RecordingUsageEventSink(),
			AiProviderSettings.fakeDefaults().withEnabled(false),
			CLOCK);

		AssistantIntentExtraction disabledResult = disabled.extract(request());

		assertEquals(AssistantIntentStatus.NOT_ENABLED, disabledResult.status());
		assertEquals(0, provider.calls());

		FailingAssistantProvider timeoutProvider = new FailingAssistantProvider(AiProviderFailure.TIMEOUT);
		AssistantIntentExtraction timeout = extractor(timeoutProvider, new InMemoryAiQuotaLedger(),
			new RecordingUsageEventSink())
			.extract(request());

		assertEquals(AssistantIntentStatus.PROVIDER_TIMEOUT, timeout.status());
		assertEquals(1, timeoutProvider.calls());
		assertFalse(timeout.fallbackUsed());
	}

	@Test
	void T3_provider_예외도_active_reservation을_남기지_않는다() {
		InMemoryAiQuotaLedger quotaLedger = new InMemoryAiQuotaLedger();
		AiProviderClient throwingProvider = request -> {
			throw new IllegalStateException("provider timeout");
		};
		AssistantIntentExtractor extractor = extractor(
			throwingProvider,
			quotaLedger,
			new RecordingUsageEventSink());

		AssistantIntentExtraction failed = extractor.extract(request());
		assertEquals(AssistantIntentStatus.SERVICE_UNAVAILABLE, failed.status());

		AssistantIntentExtraction retryAfterException = new AiProviderIntentExtractor(
			new DeterministicFakeAssistantProvider(),
			quotaLedger,
			new RecordingUsageEventSink(),
			AiProviderSettings.fakeDefaults(),
			CLOCK).extract(request());
		assertEquals(AssistantIntentStatus.SUCCESS, retryAfterException.status());
	}

	private AssistantIntentExtractor extractor(
		AiProviderClient provider,
		AiQuotaLedger quotaLedger,
		AssistantUsageEventSink usageEventSink) {
		return new AiProviderIntentExtractor(provider, quotaLedger, usageEventSink, AiProviderSettings.fakeDefaults(),
			CLOCK);
	}

	private AssistantIntentRequest request() {
		return AssistantIntentRequest.forUser("quota-subject-b", "주말 보드게임 추천", List.of());
	}

	private static final class CapturingAssistantProvider implements AiProviderClient {

		private AiProviderPayload payload;
		private int calls;

		@Override
		public AiProviderResponse propose(AiProviderPayload request) {
			payload = request;
			calls++;
			return AiProviderResponse.success("RECOMMEND", List.of("STRATEGY"), 10, 5, BigDecimal.valueOf(0.01));
		}

		AiProviderPayload payload() {
			return payload;
		}

		int calls() {
			return calls;
		}

	}

	private static final class FailingAssistantProvider implements AiProviderClient {

		private final AiProviderFailure failure;
		private int calls;

		private FailingAssistantProvider(AiProviderFailure failure) {
			this.failure = failure;
		}

		@Override
		public AiProviderResponse propose(AiProviderPayload request) {
			calls++;
			return AiProviderResponse.failure(failure);
		}

		int calls() {
			return calls;
		}
	}

	static final class RecordingUsageEventSink implements AssistantUsageEventSink {

		private final List<AssistantUsageEvent> events = new ArrayList<>();

		@Override
		public void record(AssistantUsageEvent event) {
			events.add(event);
		}

		List<AssistantUsageEvent> events() {
			return events;
		}
	}
}
