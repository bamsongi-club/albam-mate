package cloud.bamsongi.albammate.infra.ai;

import java.time.Clock;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import cloud.bamsongi.albammate.assistant.contract.AssistantIntentExtractor;

/** 기본 fake와 명시적 local-openai, profile별 quota ledger를 선택하는 구성이다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProviderProperties.class)
class AiProviderRuntimeConfiguration {

	@Bean
	AiProviderClient aiProviderClient(
		AiProviderProperties properties,
		Environment environment) {
		ChatModel openAiModel = null;
		if ("local-openai".equals(properties.getProvider())) {
			String apiKey = environment.getProperty("spring.ai.openai.api-key", "");
			if (apiKey.isBlank()) {
				throw new IllegalStateException("local-openai provider requires an API key");
			}
			openAiModel = OpenAiChatModel.builder()
				.options(OpenAiChatOptions.builder()
					.apiKey(apiKey)
					.model(properties.getModel())
					.timeout(properties.getTimeout())
					.maxRetries(0)
					.store(false)
					.build())
				.build();
		}
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
		AiQuotaLedger quotaLedger,
		AssistantUsageEventSink usageEventSink,
		AiProviderProperties properties) {
		return new AiProviderIntentExtractor(
			provider,
			quotaLedger,
			usageEventSink,
			settings(properties, true),
			Clock.systemUTC());
	}

	@Bean
	@Profile("local | production")
	AiQuotaLedger redisAiQuotaLedger(
		RedisConnectionFactory connectionFactory,
		AiProviderProperties properties,
		AiCostWarningEventSink costWarningEventSink) {
		return new RedisAiQuotaLedger(
			new StringRedisTemplate(connectionFactory), properties.getTimeout(), costWarningEventSink);
	}

	@Bean
	@Profile("!local & !production")
	AiQuotaLedger inMemoryAiQuotaLedger() {
		return new InMemoryAiQuotaLedger();
	}

	static AiProviderClient selectProvider(
		AiProviderProperties properties,
		Environment environment,
		ChatModel openAiModel) {
		if ("fake".equals(properties.getProvider())) {
			return new DeterministicFakeAssistantProvider();
		}
		if ("local-openai".equals(properties.getProvider())) {
			if (!environment.acceptsProfiles(Profiles.of("local")) || openAiModel == null) {
				throw new IllegalStateException("local-openai provider requires the local profile");
			}
			return new OpenAiAssistantProvider(openAiModel, settings(properties, true));
		}
		throw new IllegalStateException("unsupported assistant provider");
	}

	private static AiProviderSettings settings(AiProviderProperties properties, boolean providerConfigured) {
		return new AiProviderSettings(
			properties.getProvider(),
			properties.isEnabled(),
			providerConfigured,
			properties.isNoRetentionVerified(),
			properties.isNoTrainingVerified(),
			properties.getModel(),
			properties.getTimeout(),
			properties.getRetryCount(),
			properties.isStore(),
			properties.getReservationCostUsd());
	}
}
