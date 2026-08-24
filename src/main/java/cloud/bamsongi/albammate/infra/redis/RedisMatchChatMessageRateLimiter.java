package cloud.bamsongi.albammate.infra.redis;

import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.match.contract.MatchChatMessageRateLimiter;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;

/** local과 production에서 공용 Redis에 MATCH 채팅 전송 두 bucket(사용자·Party)을 Lua 한 번으로 예약하는 adapter다.
 *
 * <p>ADR-0080 축 1 — {@link RedisChatMessageRateLimiter}(P1 ROOM)와 같은
 * {@link RedisChatMessageRateLimiter#namespaceFor(Environment)} namespace 패턴을 쓰되 두 번째 bucket
 * key만 {@code party}로 분리해 ROOM의 {@code room} key와 절대 겹치지 않는다. 원자 이중 버킷 reserve/release
 * Lua 스크립트 자체는 {@link RedisFixedWindowDualBucketRateLimiter}를 공유한다. */
@Component
@Profile({"local", "production"})
public class RedisMatchChatMessageRateLimiter implements MatchChatMessageRateLimiter {

	private static final String RESERVATION_SUFFIX = ":reservations";

	private final StringRedisTemplate redisTemplate;
	private final String keyPrefix;
	private final MatchChatMessageRateLimitProperties rateLimitProperties;

	public RedisMatchChatMessageRateLimiter(
		RedisConnectionFactory redisConnectionFactory, Environment environment,
		MatchChatMessageRateLimitProperties rateLimitProperties) {
		redisTemplate = new StringRedisTemplate(redisConnectionFactory);
		redisTemplate.afterPropertiesSet();
		keyPrefix = RedisChatMessageRateLimiter.namespaceFor(environment) + ":match";
		this.rateLimitProperties = rateLimitProperties;
	}

	@Override
	public RateLimitReservation reserve(long userId, long partyId) {
		String reservationId = UUID.randomUUID().toString();
		RedisFixedWindowDualBucketRateLimiter.Decision decision;
		try {
			decision = RedisFixedWindowDualBucketRateLimiter.reserve(
				redisTemplate, userKey(userId), partyKey(partyId), userReservationsKey(userId),
				partyReservationsKey(partyId), rateLimitProperties.userLimit(), rateLimitProperties.partyLimit(),
				rateLimitProperties.window().toMillis(), reservationId);
		} catch (RuntimeException exception) {
			throw unavailable();
		}
		if (decision.allowed()) {
			return () -> release(userId, partyId, reservationId);
		}
		if (decision.retryAfterMillis() > 0) {
			throw new RateLimitExceededException(roundUpSeconds(decision.retryAfterMillis()));
		}
		throw unavailable();
	}

	private void release(long userId, long partyId, String reservationId) {
		boolean released;
		try {
			released = RedisFixedWindowDualBucketRateLimiter.release(
				redisTemplate, userKey(userId), partyKey(partyId), userReservationsKey(userId),
				partyReservationsKey(partyId), reservationId);
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

	private String partyKey(long partyId) {
		return keyPrefix + ":party:" + partyId;
	}

	private String userReservationsKey(long userId) {
		return userKey(userId) + RESERVATION_SUFFIX;
	}

	private String partyReservationsKey(long partyId) {
		return partyKey(partyId) + RESERVATION_SUFFIX;
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
