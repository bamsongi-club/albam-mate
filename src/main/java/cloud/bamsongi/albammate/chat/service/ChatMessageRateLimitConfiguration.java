package cloud.bamsongi.albammate.chat.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;
import cloud.bamsongi.albammate.infra.redis.RedisChatMessageRateLimiter;

/** local-multi와 production만 Redis 전송 제한을 적용하고, 그 외 프로필은 예약을 만들지 않는다. */
@Configuration(proxyBeanMethods = false)
class ChatMessageRateLimitConfiguration {

	private static final String LOCAL_MULTI_NAMESPACE = "albam-mate:local-multi:ratelimit";
	private static final String PRODUCTION_NAMESPACE = "albam-mate:production:ratelimit";

	@Bean
	@Profile("local-multi")
	ChatMessageRateLimiter localMultiChatMessageRateLimiter(RedisConnectionFactory redisConnectionFactory) {
		return new RedisChatMessageRateLimiter(redisConnectionFactory, LOCAL_MULTI_NAMESPACE);
	}

	@Bean
	@Profile("production")
	ChatMessageRateLimiter productionChatMessageRateLimiter(RedisConnectionFactory redisConnectionFactory) {
		return new RedisChatMessageRateLimiter(redisConnectionFactory, PRODUCTION_NAMESPACE);
	}

	@Bean
	@Profile("!local-multi & !production")
	ChatMessageRateLimiter nonLocalMultiChatMessageRateLimiter() {
		return (userId, roomId) -> () -> {};
	}
}
