package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.mock.env.MockEnvironment;

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
		MockEnvironment localEnvironment = new MockEnvironment();
		localEnvironment.setActiveProfiles("local");
		assertInstanceOf(
			OpenAiAssistantProvider.class,
			AiProviderRuntimeConfiguration.selectProvider(openAiProperties, localEnvironment, mock(ChatModel.class)));
	}

	@Test
	void T1_fake_provider는_외부_보존학습_검증없이도_결정적_로컬_경로를_사용할_수_있다() {
		AiProviderSettings fakeSettings = new AiProviderSettings(
			"fake", true, true, false, false, "gpt-5.6-luna", Duration.ofSeconds(10), 0, false,
			new BigDecimal("0.10"));
		AiProviderSettings openAiSettings = new AiProviderSettings(
			"local-openai", true, true, false, false, "gpt-5.6-luna", Duration.ofSeconds(10), 0, false,
			new BigDecimal("0.10"));

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
	void T4_local과_production_runtime_quota는_공유_Redis_adapter를_사용한다() {
		assertRedisQuotaLedger("local");
		assertRedisQuotaLedger("production");
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

	private void assertRedisQuotaLedger(String profile) {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles(profile);
			context.registerBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));
			context.register(AiProviderRuntimeConfiguration.class);
			context.refresh();

			assertInstanceOf(RedisAiQuotaLedger.class, context.getBean(AiQuotaLedger.class));
		}
	}
}
