package cloud.bamsongi.albammate.infra.redis;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;

/** local과 production에서 공용 Redis에 채팅 전송 두 bucket을 Lua 한 번으로 예약하는 adapter다. 자기 자신을 등록하고
 * {@link Environment}로 프로필별 namespace를 골라 key를 분리한다.
 *
 * <p>ADR-0080 축 1 — 원자 이중 버킷 reserve/release Lua 스크립트 자체는
 * {@link RedisFixedWindowDualBucketRateLimiter}로 추출해 {@link RedisMatchChatMessageRateLimiter}와 공유한다.
 * 이 클래스는 key 네임스페이스('room' bucket)·quota·429/503 예외 매핑만 소유한다. */
@Component
@Profile({"local", "production"})
public class RedisChatMessageRateLimiter implements ChatMessageRateLimiter {

	private static final String LOCAL_NAMESPACE = "albam-mate:local:ratelimit";
	private static final String PRODUCTION_NAMESPACE = "albam-mate:production:ratelimit";
	private static final String RESERVATION_SUFFIX = ":reservations";

	private final StringRedisTemplate redisTemplate;
	private final String keyPrefix;
	private final ChatMessageRateLimitProperties rateLimitProperties;

	public RedisChatMessageRateLimiter(
		RedisConnectionFactory redisConnectionFactory, Environment environment,
		ChatMessageRateLimitProperties rateLimitProperties) {
		redisTemplate = new StringRedisTemplate(redisConnectionFactory);
		redisTemplate.afterPropertiesSet();
		keyPrefix = namespaceFor(environment);
		this.rateLimitProperties = rateLimitProperties;
	}

	static String namespaceFor(Environment environment) {
		return environment.acceptsProfiles(Profiles.of("production")) ? PRODUCTION_NAMESPACE : LOCAL_NAMESPACE;
	}

	@Override
	public RateLimitReservation reserve(long userId, long roomId) {
		String reservationId = UUID.randomUUID().toString();
		RedisFixedWindowDualBucketRateLimiter.Decision decision;
		try {
			decision = RedisFixedWindowDualBucketRateLimiter.reserve(
				redisTemplate, userKey(userId), roomKey(roomId), userReservationsKey(userId),
				roomReservationsKey(roomId), rateLimitProperties.userLimit(), rateLimitProperties.roomLimit(),
				rateLimitProperties.window().toMillis(), reservationId);
		} catch (RuntimeException exception) {
			throw unavailable();
		}
		if (decision.allowed()) {
			return () -> release(userId, roomId, reservationId);
		}
		if (decision.retryAfterMillis() > 0) {
			throw new RateLimitExceededException(roundUpSeconds(decision.retryAfterMillis()));
		}
		throw unavailable();
	}

	private void release(long userId, long roomId, String reservationId) {
		boolean released;
		try {
			released = RedisFixedWindowDualBucketRateLimiter.release(
				redisTemplate, userKey(userId), roomKey(roomId), userReservationsKey(userId),
				roomReservationsKey(roomId), reservationId);
		} catch (RuntimeException exception) {
			throw unavailable();
		}
		if (!released) {
			throw unavailable();
		}
	}

	private String userKey(long userId) {
		return keyPrefix + ":user:" + userId;
	}

	private String roomKey(long roomId) {
		return keyPrefix + ":room:" + roomId;
	}

	private String userReservationsKey(long userId) {
		return userKey(userId) + RESERVATION_SUFFIX;
	}

	private String roomReservationsKey(long roomId) {
		return roomKey(roomId) + RESERVATION_SUFFIX;
	}

	private int roundUpSeconds(long ttlMillis) {
		long seconds = (ttlMillis + 999L) / 1_000L;
		if (seconds <= 0 || seconds > Integer.MAX_VALUE) {
			throw unavailable();
		}
		return (int)seconds;
	}

	private BusinessException unavailable() {
		return new BusinessException(ErrorCode.SERVICE_UNAVAILABLE);
	}
}
