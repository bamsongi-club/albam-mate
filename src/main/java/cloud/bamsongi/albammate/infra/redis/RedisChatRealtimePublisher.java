package cloud.bamsongi.albammate.infra.redis;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;

/** local-multi 공용 Redis로 커밋 후 {@code eventType}·{@code roomId}·{@code messageId}만 발행하는 adapter다. */
@Component
@Profile("local-multi")
public class RedisChatRealtimePublisher implements ChatRealtimePublisher {

	static final String CHANNEL = "albam-mate:local-multi:chat:events";

	private final StringRedisTemplate redisTemplate;

	public RedisChatRealtimePublisher(RedisConnectionFactory redisConnectionFactory) {
		redisTemplate = new StringRedisTemplate(redisConnectionFactory);
		redisTemplate.afterPropertiesSet();
	}

	@Override
	public void publish(MessageCommitted event) {
		redisTemplate.convertAndSend(CHANNEL, encode(event));
	}

	static String encode(MessageCommitted event) {
		return event.eventType() + ":" + event.roomId() + ":" + event.messageId();
	}
}
