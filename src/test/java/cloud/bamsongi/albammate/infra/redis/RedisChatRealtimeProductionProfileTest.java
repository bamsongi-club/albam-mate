package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.ChatRealtimeSignalGateway;

/** T7: production과 local-multi가 같은 PostgreSQL catch-up용 Redis fan-out adapter를 등록하는지 검증한다. */
class RedisChatRealtimeProductionProfileTest {

	@Test
	void T7_production은_Redis_발행구독과_listener_container를_등록하고_NoOp을_대체한다() {
		try (AnnotationConfigApplicationContext context = redisRealtimeContext("production")) {
			ChatRealtimePublisher publisher = context.getBean(ChatRealtimePublisher.class);

			assertInstanceOf(RedisChatRealtimePublisher.class, publisher);
			assertEquals("albam-mate:production:chat:events", ReflectionTestUtils.getField(publisher, "channel"));
			assertEquals(1, context.getBeansOfType(ChatRealtimePublisher.class).size());
			assertFalse(context.getBeansOfType(RedisChatRealtimeSubscriber.class).isEmpty());
			assertFalse(context.getBeansOfType(RedisMessageListenerContainer.class).isEmpty());
		}
	}

	@Test
	void T7_production은_필수_Redis_host와_기본_port를_설정한다() {
		Properties properties = productionProperties();

		assertEquals("${ALBAM_MATE_REDIS_HOST}", properties.getProperty("spring.data.redis.host"));
		assertEquals("${ALBAM_MATE_REDIS_PORT:6379}", properties.getProperty("spring.data.redis.port"));
	}

	@Test
	void T7_production_Compose와_환경예시와_배포검증은_Redis_연결값을_전달한다() throws IOException {
		String compose = Files.readString(Path.of("compose.production.yml"));
		String environmentExample = Files.readString(Path.of(".env.production.example"));
		String deploymentVerifier = Files.readString(Path.of("scripts/verify-docker-deployment.mjs"));

		assertTrue(compose.contains("ALBAM_MATE_REDIS_HOST: ${ALBAM_MATE_REDIS_HOST:?ALBAM_MATE_REDIS_HOST must be set}"));
		assertTrue(compose.contains("ALBAM_MATE_REDIS_PORT: ${ALBAM_MATE_REDIS_PORT:-6379}"));
		assertTrue(environmentExample.contains("ALBAM_MATE_REDIS_HOST=replace-with-redis-endpoint"));
		assertTrue(environmentExample.contains("ALBAM_MATE_REDIS_PORT=6379"));
		assertTrue(deploymentVerifier.contains("ALBAM_MATE_REDIS_HOST: 'redis.example.internal'"));
		assertTrue(deploymentVerifier.contains("ALBAM_MATE_REDIS_PORT: '6379'"));
	}

	@Test
	void T7_local_multi는_Redis_발행구독과_listener_container를_등록한다() {
		try (AnnotationConfigApplicationContext context = redisRealtimeContext("local-multi")) {
			ChatRealtimePublisher publisher = context.getBean(ChatRealtimePublisher.class);

			assertInstanceOf(RedisChatRealtimePublisher.class, publisher);
			assertEquals("albam-mate:local-multi:chat:events", ReflectionTestUtils.getField(publisher, "channel"));
			assertEquals(1, context.getBeansOfType(ChatRealtimePublisher.class).size());
			assertFalse(context.getBeansOfType(RedisChatRealtimeSubscriber.class).isEmpty());
			assertFalse(context.getBeansOfType(RedisMessageListenerContainer.class).isEmpty());
		}
	}

	private Properties productionProperties() {
		YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
		yaml.setResources(new ClassPathResource("application-production.yml"));
		return yaml.getObject();
	}

	private AnnotationConfigApplicationContext redisRealtimeContext(String profile) {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		ConfigurableEnvironment environment = context.getEnvironment();
		environment.setActiveProfiles(profile);
		context.registerBean(RedisConnectionFactory.class, () -> mock(RedisConnectionFactory.class));
		context.registerBean(ChatRealtimeSignalGateway.class, () -> mock(ChatRealtimeSignalGateway.class));
		context.register(
			RedisChatRealtimePublisher.class,
			RedisChatRealtimeSubscriber.class,
			RedisChatRealtimeListenerConfiguration.class,
			noOpPublisherClass());
		context.refresh();
		return context;
	}

	@SuppressWarnings("unchecked")
	private Class<? extends ChatRealtimePublisher> noOpPublisherClass() {
		try {
			return (Class<? extends ChatRealtimePublisher>)Class.forName(
				"cloud.bamsongi.albammate.chat.service.NoOpChatRealtimePublisher");
		} catch (ClassNotFoundException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
