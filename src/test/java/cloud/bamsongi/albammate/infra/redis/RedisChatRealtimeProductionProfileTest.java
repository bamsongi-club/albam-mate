package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.ChatRealtimeSignalGateway;

/** T7: production과 local이 같은 PostgreSQL catch-up용 Redis fan-out adapter를 등록하는지 검증한다. */
class RedisChatRealtimeProductionProfileTest {

	@Test
	void T7_production은_Redis_발행구독과_listener_container를_등록하고_NoOp을_대체한다() {
		try (AnnotationConfigApplicationContext context = redisRealtimeContext("production")) {
			ChatRealtimePublisher publisher = context.getBean(ChatRealtimePublisher.class);

			assertInstanceOf(RedisChatRealtimePublisher.class, publisher);
			assertEquals("albam-mate:production:chat:events", ReflectionTestUtils.getField(publisher, "channel"));
			assertEquals(1, context.getBeansOfType(ChatRealtimePublisher.class).size());
			assertEquals(1, context.getBeansOfType(RedisChatRealtimeSubscriber.class).size());
			assertEquals(1, context.getBeansOfType(RedisMessageListenerContainer.class).size());
			assertTrue(context.getBean(ThreadPoolTaskScheduler.class).isRunning());
		}
	}

	@Test
	void T7_production은_필수_Redis_host와_기본_port를_설정한다() {
		Properties properties = productionProperties();

		assertEquals("${ALBAM_MATE_REDIS_HOST}", properties.getProperty("spring.data.redis.host"));
		assertEquals("${ALBAM_MATE_REDIS_PORT:6379}", properties.getProperty("spring.data.redis.port"));
		assertEquals("${ALBAM_MATE_REDIS_HOST}", properties.getProperty("app.redis.host"));
		assertEquals("${ALBAM_MATE_REDIS_PORT:6379}", properties.getProperty("app.redis.port"));
	}

	@Test
	void T7_production_Compose와_환경예시와_배포검증은_Redis_연결값을_전달한다() throws IOException {
		String compose = Files.readString(Path.of("compose.production.yml"));
		String environmentExample = Files.readString(Path.of(".env.production.example"));
		String deploymentVerifier = Files.readString(Path.of("scripts/verify-docker-deployment.mjs"));

		assertTrue(
			compose.contains("ALBAM_MATE_REDIS_HOST: ${ALBAM_MATE_REDIS_HOST:?ALBAM_MATE_REDIS_HOST must be set}"));
		assertTrue(compose.contains("ALBAM_MATE_REDIS_PORT: ${ALBAM_MATE_REDIS_PORT:-6379}"));
		assertTrue(environmentExample.contains("ALBAM_MATE_REDIS_HOST=replace-with-redis-endpoint"));
		assertTrue(environmentExample.contains("ALBAM_MATE_REDIS_PORT=6379"));
		assertTrue(deploymentVerifier.contains("ALBAM_MATE_REDIS_HOST: 'redis.example.internal'"));
		assertTrue(deploymentVerifier.contains("ALBAM_MATE_REDIS_PORT: '6379'"));
	}

	@Test
	void T3_local은_Redis_발행구독과_listener_container를_등록한다() {
		try (AnnotationConfigApplicationContext context = redisRealtimeContext("local")) {
			ChatRealtimePublisher publisher = context.getBean(ChatRealtimePublisher.class);

			assertInstanceOf(RedisChatRealtimePublisher.class, publisher);
			assertEquals("albam-mate:local:chat:events", ReflectionTestUtils.getField(publisher, "channel"));
			assertEquals(1, context.getBeansOfType(ChatRealtimePublisher.class).size());
			assertEquals(1, context.getBeansOfType(RedisChatRealtimeSubscriber.class).size());
			assertEquals(1, context.getBeansOfType(RedisMessageListenerContainer.class).size());
		}
	}

	@Test
	void T7_초기_Redis_구독_실패_뒤_기본_간격으로_다시_시작한다() {
		RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
		ThreadPoolTaskScheduler retryScheduler = mock(ThreadPoolTaskScheduler.class);
		when(retryScheduler.isRunning()).thenReturn(true);
		doThrow(new IllegalStateException("redis unavailable")).doNothing().when(container).start();
		ArgumentCaptor<Runnable> retry = ArgumentCaptor.forClass(Runnable.class);
		ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
		Instant before = Instant.now();

		new RedisChatRealtimeListenerConfiguration().startQuietly(container, retryScheduler);

		Instant after = Instant.now();
		verify(container).stop();
		verify(retryScheduler).schedule(retry.capture(), retryAt.capture());
		assertFalse(retryAt.getValue().isBefore(
			before.plusMillis(RedisMessageListenerContainer.DEFAULT_RECOVERY_INTERVAL)));
		assertFalse(retryAt.getValue().isAfter(
			after.plusMillis(RedisMessageListenerContainer.DEFAULT_RECOVERY_INTERVAL)));

		retry.getValue().run();

		verify(container, times(2)).start();
		verify(container).stop();
		verify(retryScheduler, times(2)).isRunning();
		verifyNoMoreInteractions(retryScheduler);
	}

	@Test
	void T7_종료된_재시도_스케줄러는_Redis_구독을_다시_시작하지_않는다() {
		RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
		ThreadPoolTaskScheduler retryScheduler = mock(ThreadPoolTaskScheduler.class);
		when(retryScheduler.isRunning()).thenReturn(false);

		new RedisChatRealtimeListenerConfiguration().startQuietly(container, retryScheduler);

		verifyNoInteractions(container);
	}

	@Test
	void T7_재시도_스케줄러는_종료_뒤_대기_작업을_실행하지_않는다() {
		ThreadPoolTaskScheduler retryScheduler = (ThreadPoolTaskScheduler)new RedisChatRealtimeListenerConfiguration()
			.chatRealtimeSubscriptionRetryScheduler();
		retryScheduler.afterPropertiesSet();

		try {
			assertFalse(
				retryScheduler.getScheduledThreadPoolExecutor().getExecuteExistingDelayedTasksAfterShutdownPolicy());
		} finally {
			retryScheduler.shutdown();
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
