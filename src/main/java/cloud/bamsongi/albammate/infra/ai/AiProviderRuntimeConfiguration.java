package cloud.bamsongi.albammate.infra.ai;

import java.time.Clock;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;

/** 기본 fake와 명시적 local-openai, quota fail-closed seam을 선택하는 구성이다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProviderProperties.class)
class AiProviderRuntimeConfiguration {

	@Bean
	AiProviderClient aiProviderClient(
		AiProviderProperties properties,
		Environment environment) {
		ChatModel openAiModel = openAiModel(properties, environment);
		return selectProvider(properties, environment, openAiModel);
	}

	@Bean
	AssistantUsageEventSink assistantUsageEventSink(ApplicationEventPublisher eventPublisher) {
		return eventPublisher::publishEvent;
	}

	@Bean
	AiCostWarningEventSink aiCostWarningEventSink(ApplicationEventPublisher eventPublisher) {
		return eventPublisher::publishEvent;
	}

	@Bean
	AssistantIntentExtractor assistantIntentExtractor(
		AiProviderClient provider,
		ObjectProvider<AiQuotaLedger> quotaLedgerProvider,
		AssistantUsageEventSink usageEventSink,
		AiProviderProperties properties) {
		AiQuotaLedger configuredLedger = quotaLedgerProvider.getIfAvailable();
		AiQuotaLedger quotaLedger = configuredLedger != null && "fake".equals(properties.getProvider())
			? new NoOpAiQuotaLedger()
			: configuredLedger != null ? configuredLedger : new UnavailableAiQuotaLedger();
		return new AiProviderIntentExtractor(
			provider,
			quotaLedger,
			usageEventSink,
			settings(properties),
			Clock.systemUTC());
	}

	static AiProviderClient selectProvider(
		AiProviderProperties properties,
		Environment environment,
		ChatModel openAiModel) {
		if ("fake".equals(properties.getProvider())) {
			return new DeterministicFakeAssistantProvider();
		}
		if ("local-openai".equals(properties.getProvider())) {
			if (!allowsOpenAi(environment) || !settings(properties).readyForCall() || openAiModel == null) {
				return new UnavailableAiProvider();
			}
			return new OpenAiAssistantProvider(openAiModel, settings(properties, true));
		}
		return new UnavailableAiProvider();
	}

	private static ChatModel openAiModel(AiProviderProperties properties, Environment environment) {
		String apiKey = environment.getProperty("spring.ai.openai.api-key", "");
		if (!"local-openai".equals(properties.getProvider())
			|| !allowsOpenAi(environment)
			|| apiKey.isBlank()
			|| !settings(properties).readyForCall()) {
			return null;
		}
		return OpenAiChatModel.builder()
			.options(OpenAiChatOptions.builder()
				.apiKey(apiKey)
				.model(properties.getModel())
				.timeout(properties.getTimeout())
				.maxRetries(0)
				.maxCompletionTokens(properties.getMaxOutputTokens())
				.store(false)
				.build())
			.build();
	}

	private static boolean allowsOpenAi(Environment environment) {
		return environment.acceptsProfiles(Profiles.of("local", "production"));
	}

	private static AiProviderSettings settings(AiProviderProperties properties) {
		return settings(properties, "fake".equals(properties.getProvider()) || properties.isProviderConfigured());
	}

	private static AiProviderSettings settings(AiProviderProperties properties, boolean providerConfigured) {
		return new AiProviderSettings(
			properties.getProvider(),
			properties.isEnabled(),
			providerConfigured,
			properties.isNoRetentionVerified(),
			properties.isNoTrainingVerified(),
			properties.getPolicyVersion(),
			properties.getPolicyUrl(),
			properties.getModel(),
			properties.getTimeout(),
			properties.getRetryCount(),
			properties.isStore(),
			properties.getPricingSnapshot(),
			properties.getInputTokenPriceUsdPerMillion(),
			properties.getOutputTokenPriceUsdPerMillion(),
			properties.getMaxInputTokens(),
			properties.getMaxOutputTokens(),
			properties.getReservationCostUsd());
	}
}
