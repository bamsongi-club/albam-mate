package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.mock.env.MockEnvironment;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentRequest;
import cloud.bamsongi.albammate.assistant.contract.AssistantIntentStatus;
import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;

class AiProviderRuntimeConfigurationTest {

	@Test
	void T1_fake와_local_openai_provider를_명시된_선택값에_따라_분리한다() {
		AiProviderProperties fakeProperties = new AiProviderProperties();
		fakeProperties.setProvider("fake");
		assertInstanceOf(
			DeterministicFakeAssistantProvider.class,
			AiProviderRuntimeConfiguration.selectProvider(fakeProperties, new MockEnvironment(),
				mock(ChatModel.class)));

		AiProviderProperties openAiProperties = new AiProviderProperties();
		openAiProperties.setProvider("local-openai");
		openAiProperties.setEnabled(true);
		openAiProperties.setProviderConfigured(true);
		openAiProperties.setNoRetentionVerified(false);
		openAiProperties.setNoTrainingVerified(true);
		openAiProperties.setRetentionMode("default-30d");
		openAiProperties.setPolicyVersion("OPENAI-POLICY-V1");
		openAiProperties.setPolicyUrl("https://example.com/openai-policy");
		openAiProperties.setPricingSnapshot("OPENAI-PRICING-V1");
		openAiProperties.setInputTokenPriceUsdPerMillion(new BigDecimal("1.00"));
		openAiProperties.setOutputTokenPriceUsdPerMillion(new BigDecimal("1.00"));
		MockEnvironment localEnvironment = new MockEnvironment();
		localEnvironment.setActiveProfiles("local");
		localEnvironment.setProperty("spring.ai.openai.api-key", "test-api-key");
		assertInstanceOf(
			OpenAiAssistantProvider.class,
			AiProviderRuntimeConfiguration.selectProvider(openAiProperties, localEnvironment, mock(ChatModel.class)));
	}

	@Test
	void T2_local_openai는_production에서_명시적_설정과_정책가격게이트가_모두_충족될_때만_선택한다() {
		AiProviderProperties properties = configuredOpenAiProperties();
		MockEnvironment productionEnvironment = new MockEnvironment();
		productionEnvironment.setActiveProfiles("production");
		productionEnvironment.setProperty("spring.ai.openai.api-key", "test-api-key");
		AiProviderRuntimeConfiguration configuration = new AiProviderRuntimeConfiguration();

		assertInstanceOf(
			OpenAiAssistantProvider.class,
			AiProviderRuntimeConfiguration.selectProvider(properties, productionEnvironment, mock(ChatModel.class)));
		assertInstanceOf(OpenAiAssistantProvider.class,
			configuration.aiProviderClient(properties, productionEnvironment));

		properties.setProviderConfigured(false);
		assertInstanceOf(
			UnavailableAiProvider.class,
			AiProviderRuntimeConfiguration.selectProvider(properties, productionEnvironment, mock(ChatModel.class)));
	}

	@Test
	void T2_provider_configured가_아니면_동의가_남아도_quota와_provider를_호출하지_않는다() {
		AiProviderRuntimeConfiguration configuration = new AiProviderRuntimeConfiguration();
		AiProviderProperties properties = configuredOpenAiProperties();
		properties.setProviderConfigured(false);
		AiProviderClient provider = mock(AiProviderClient.class);
		AiQuotaLedger quotaLedger = mock(AiQuotaLedger.class);
		@SuppressWarnings("unchecked") ObjectProvider<AiQuotaLedger> quotaLedgerProvider = mock(ObjectProvider.class);
		AssistantUsageEventSink usageEventSink = mock(AssistantUsageEventSink.class);
		when(quotaLedgerProvider.getIfAvailable(any())).thenReturn(quotaLedger);
		when(quotaLedger.reserve(anyString(), any(Instant.class), any(BigDecimal.class)))
			.thenReturn(AiQuotaReservation.acquired("42"));
		when(quotaLedger.complete(any(AiQuotaReservation.class), any(BigDecimal.class)))
			.thenReturn(AiQuotaCompletionStatus.COMPLETED);
		when(provider.providerName()).thenReturn("local-openai");
		when(provider.propose(any(AiProviderPayload.class)))
			.thenReturn(AiProviderResponse.success("RECOMMEND", List.of("PARTY"), 1, 1, BigDecimal.ZERO));

		AssistantIntentExtractor extractor = configuration.assistantIntentExtractor(
			provider, quotaLedgerProvider, usageEventSink, properties, new MockEnvironment());

		assertEquals(AssistantIntentStatus.NOT_ENABLED,
			extractor.extract(AssistantIntentRequest.forUser("42", "파티 게임을 추천해줘", List.of())).status());
		verifyNoInteractions(provider, quotaLedger, usageEventSink);
	}

	@Test
	void T2_local_openai에_API_key가_없으면_동의가_남아도_quota와_provider를_호출하지_않는다() {
		assertNotEnabledBeforeAnyAiInvocation(new MockEnvironment());
	}

	@Test
	void T2_local_openai가_허용되지_않은_profile이면_동의가_남아도_quota와_provider를_호출하지_않는다() {
		MockEnvironment unsupportedEnvironment = new MockEnvironment();
		unsupportedEnvironment.setActiveProfiles("test");
		unsupportedEnvironment.setProperty("spring.ai.openai.api-key", "test-api-key");

		assertNotEnabledBeforeAnyAiInvocation(unsupportedEnvironment);
	}

	@Test
	void T3_API_key나_허용_profile이_부족하면_Spring_기동대신_AI만_fail_closed한다() {
		AiProviderRuntimeConfiguration configuration = new AiProviderRuntimeConfiguration();
		AiProviderProperties properties = configuredOpenAiProperties();
		MockEnvironment productionEnvironment = new MockEnvironment();
		productionEnvironment.setActiveProfiles("production");

		assertInstanceOf(UnavailableAiProvider.class,
			configuration.aiProviderClient(properties, productionEnvironment));
		assertInstanceOf(
			UnavailableAiProvider.class,
			AiProviderRuntimeConfiguration.selectProvider(properties, new MockEnvironment(), mock(ChatModel.class)));
	}

	@Test
	void T4_운영에서_enabled를_다시_끄면_새_local_openai_호출을_차단한다() {
		AiProviderProperties properties = configuredOpenAiProperties();
		properties.setEnabled(false);
		MockEnvironment productionEnvironment = new MockEnvironment();
		productionEnvironment.setActiveProfiles("production");

		assertInstanceOf(
			UnavailableAiProvider.class,
			AiProviderRuntimeConfiguration.selectProvider(properties, productionEnvironment, mock(ChatModel.class)));
	}

	@Test
	void T1_fake_provider는_외부_보존학습_검증없이도_결정적_로컬_경로를_사용할_수_있다() {
		AiProviderSettings fakeSettings = new AiProviderSettings(
			"fake", true, true, false, false, "", "", "gpt-5.6-luna", Duration.ofSeconds(10), 0, false,
			"", BigDecimal.ZERO, BigDecimal.ZERO, 4096, 256, new BigDecimal("0.10"), "unverified");
		AiProviderSettings openAiSettings = new AiProviderSettings(
			"local-openai", true, true, false, false, "", "", "gpt-5.6-luna", Duration.ofSeconds(10), 0, false,
			"", BigDecimal.ZERO, BigDecimal.ZERO, 4096, 256, new BigDecimal("0.10"), "unverified");

		assertTrue(fakeSettings.readyForCall());
		assertFalse(openAiSettings.readyForCall());
	}

	@Test
	void T3_provider_보존과_학습_검증은_기본적으로_fail_closed다() {
		AiProviderProperties properties = new AiProviderProperties();

		assertFalse(properties.isNoRetentionVerified());
		assertFalse(properties.isNoTrainingVerified());
	}

	@Test
	void T6_runtime_usage_sink는_Spring_event로_usage를_전달한다() {
		AiProviderRuntimeConfiguration configuration = new AiProviderRuntimeConfiguration();
		AtomicBoolean published = new AtomicBoolean();
		ApplicationEventPublisher publisher = event -> published.set(event instanceof AssistantUsageEvent);

		AssistantUsageEventSink sink = configuration.assistantUsageEventSink(publisher);
		sink.record(mock(AssistantUsageEvent.class));

		assertTrue(published.get());
	}

	private AiProviderProperties configuredOpenAiProperties() {
		AiProviderProperties properties = new AiProviderProperties();
		properties.setProvider("local-openai");
		properties.setEnabled(true);
		properties.setProviderConfigured(true);
		properties.setNoRetentionVerified(false);
		properties.setNoTrainingVerified(true);
		properties.setRetentionMode("default-30d");
		properties.setPolicyVersion("OPENAI-POLICY-V1");
		properties.setPolicyUrl("https://example.com/openai-policy");
		properties.setPricingSnapshot("OPENAI-PRICING-V1");
		properties.setInputTokenPriceUsdPerMillion(new BigDecimal("1.00"));
		properties.setOutputTokenPriceUsdPerMillion(new BigDecimal("1.00"));
		return properties;
	}

	private void assertNotEnabledBeforeAnyAiInvocation(MockEnvironment environment) {
		AiProviderRuntimeConfiguration configuration = new AiProviderRuntimeConfiguration();
		AiProviderClient provider = mock(AiProviderClient.class);
		AiQuotaLedger quotaLedger = mock(AiQuotaLedger.class);
		@SuppressWarnings("unchecked") ObjectProvider<AiQuotaLedger> quotaLedgerProvider = mock(ObjectProvider.class);
		AssistantUsageEventSink usageEventSink = mock(AssistantUsageEventSink.class);
		when(quotaLedgerProvider.getIfAvailable(any())).thenReturn(quotaLedger);

		AssistantIntentExtractor extractor = configuration.assistantIntentExtractor(
			provider, quotaLedgerProvider, usageEventSink, configuredOpenAiProperties(), environment);

		assertEquals(AssistantIntentStatus.NOT_ENABLED,
			extractor.extract(AssistantIntentRequest.forUser("42", "파티 게임을 추천해줘", List.of())).status());
		verifyNoInteractions(provider, quotaLedger, usageEventSink);
	}

}
