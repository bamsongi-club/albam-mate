package cloud.bamsongi.albammate.infra.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import io.lettuce.core.ClientOptions;

class RedisConnectionRuntimeConfigurationTest {

	@Test
	void local과_production은_app_redis와_공통_Lettuce_연결_정책을_사용한다() {
		assertRedisConnectionRuntime("local");
		assertRedisConnectionRuntime("production");
	}

	private void assertRedisConnectionRuntime(String profile) {
		try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
			context.getEnvironment().setActiveProfiles(profile);
			TestPropertyValues.of("app.redis.host=redis", "app.redis.port=6379").applyTo(context);
			context.register(RedisConnectionRuntimeConfiguration.class);
			context.refresh();

			LettuceConnectionFactory connectionFactory = context.getBean(
				"redisConnectionFactory", LettuceConnectionFactory.class);
			ClientOptions clientOptions = connectionFactory.getClientConfiguration()
				.getClientOptions()
				.orElseThrow();

			assertEquals("redis", connectionFactory.getHostName());
			assertEquals(6379, connectionFactory.getPort());
			assertTrue(connectionFactory.getShareNativeConnection());
			assertEquals(Duration.ofSeconds(2), connectionFactory.getClientConfiguration().getCommandTimeout());
			assertEquals(Duration.ofSeconds(1), clientOptions.getSocketOptions().getConnectTimeout());
			assertTrue(clientOptions.isAutoReconnect());
			assertEquals(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS, clientOptions.getDisconnectedBehavior());
		}
	}
}
