package cloud.bamsongi.albammate.infra.redis;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;

/** 공용 Redis 연결 정보와 Lettuce connection factory 생성 정책을 구성한다. */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "production"})
@EnableConfigurationProperties(RedisConnectionProperties.class)
public class RedisConnectionRuntimeConfiguration {

	private static final Duration REDIS_CONNECT_TIMEOUT = Duration.ofSeconds(1);
	private static final Duration REDIS_COMMAND_TIMEOUT = Duration.ofSeconds(2);

	@Bean
	@Primary
	LettuceConnectionFactory redisConnectionFactory(RedisConnectionProperties properties) {
		return createConnectionFactory(properties);
	}

	LettuceConnectionFactory createConnectionFactory(RedisConnectionProperties properties) {
		ClientOptions clientOptions = ClientOptions.builder()
			.autoReconnect(true)
			.disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
			.socketOptions(SocketOptions.builder().connectTimeout(REDIS_CONNECT_TIMEOUT).build())
			.build();
		LettuceClientConfiguration clientConfiguration = LettuceClientConfiguration.builder()
			.clientOptions(clientOptions)
			.commandTimeout(REDIS_COMMAND_TIMEOUT)
			.build();
		LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(
			new RedisStandaloneConfiguration(properties.host(), properties.port()), clientConfiguration);
		connectionFactory.setShareNativeConnection(true);
		return connectionFactory;
	}
}
