package cloud.bamsongi.albammate.infra.redis;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.match.contract.MatchChatMessageCommitted;
import cloud.bamsongi.albammate.chat.match.contract.MatchChatRealtimePublisher;

/** Redis 운영 프로필에서 커밋 후 {@code eventType}·{@code partyId}·{@code messageId}만 발행하는 adapter다. */
@Component
@Profile({"local", "production"})
public class RedisMatchChatRealtimePublisher implements MatchChatRealtimePublisher {

	private static final String LOCAL_CHANNEL = "albam-mate:local:match-chat:events";
	private static final String PRODUCTION_CHANNEL = "albam-mate:production:match-chat:events";

	private final StringRedisTemplate redisTemplate;
	private final String channel;

	public RedisMatchChatRealtimePublisher(RedisConnectionFactory redisConnectionFactory, Environment environment) {
		redisTemplate = new StringRedisTemplate(redisConnectionFactory);
		redisTemplate.afterPropertiesSet();
		channel = channelFor(environment);
	}

	@Override
	public void publish(MatchChatMessageCommitted event) {
		redisTemplate.convertAndSend(channel, encode(event));
	}

	static String channelFor(Environment environment) {
		return environment.acceptsProfiles(Profiles.of("production")) ? PRODUCTION_CHANNEL : LOCAL_CHANNEL;
	}

	static String encode(MatchChatMessageCommitted event) {
		return event.eventType() + ":" + event.partyId() + ":" + event.messageId();
	}
}
