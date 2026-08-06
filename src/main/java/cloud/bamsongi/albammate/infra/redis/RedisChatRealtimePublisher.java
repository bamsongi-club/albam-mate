package cloud.bamsongi.albammate.infra.redis;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;

/** Redis 운영 프로필에서 커밋 후 {@code eventType}·{@code roomId}·{@code messageId}만 발행하는 adapter다. */
@Component
@Profile({"local-multi", "production"})
public class RedisChatRealtimePublisher implements ChatRealtimePublisher {

	private static final String LOCAL_MULTI_CHANNEL = "albam-mate:local-multi:chat:events";
	private static final String PRODUCTION_CHANNEL = "albam-mate:production:chat:events";

	private final StringRedisTemplate redisTemplate;
	private final String channel;

	public RedisChatRealtimePublisher(RedisConnectionFactory redisConnectionFactory, Environment environment) {
		redisTemplate = new StringRedisTemplate(redisConnectionFactory);
		redisTemplate.afterPropertiesSet();
		channel = channelFor(environment);
	}

	@Override
	public void publish(MessageCommitted event) {
		redisTemplate.convertAndSend(channel, encode(event));
	}

	static String channelFor(Environment environment) {
		return environment.acceptsProfiles(Profiles.of("production")) ? PRODUCTION_CHANNEL : LOCAL_MULTI_CHANNEL;
	}

	static String encode(MessageCommitted event) {
		return event.eventType() + ":" + event.roomId() + ":" + event.messageId();
	}
}
