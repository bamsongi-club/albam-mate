package cloud.bamsongi.albammate.infra.redis;

import java.util.List;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * ADR-0080 축 1 — Redis 고정 창 이중 버킷 rate-limit의 원자 reserve/release Lua 스크립트와 그 결과 판정을 소유하는
 * 공유 stateless 유틸리티다.
 *
 * <p>key 네임스페이스, quota 수치, 예외 매핑(429/503)은 이 클래스가 아니라 각 호출자
 * ({@link RedisChatMessageRateLimiter}, {@link RedisMatchChatMessageRateLimiter})가 책임진다. 이 클래스는
 * 두 bucket을 하나의 Lua 실행으로 원자 판정·증가·보상하는 알고리즘 자체만 소유해, ROOM·MATCH 양쪽에 같은 정확성
 * 보장을 재사용한다.
 */
final class RedisFixedWindowDualBucketRateLimiter {

	private static final DefaultRedisScript<List> RESERVE_SCRIPT = new DefaultRedisScript<>("""
		local firstValue = redis.call('GET', KEYS[1])
		local secondValue = redis.call('GET', KEYS[2])
		local function toIncrCompatibleCount(rawValue)
		  if not rawValue then
		    return true, 0
		  end
		  if not string.match(rawValue, '^%-?%d+$') then
		    return false, 0
		  end
		  local number = tonumber(rawValue)
		  if number == nil or number < 0 then
		    return false, 0
		  end
		  return true, number
		end
		local firstValid, firstCount = toIncrCompatibleCount(firstValue)
		local secondValid, secondCount = toIncrCompatibleCount(secondValue)
		if not firstValid or not secondValid then
		  return {-1, 0}
		end
		local firstTtl = redis.call('PTTL', KEYS[1])
		local secondTtl = redis.call('PTTL', KEYS[2])
		if (firstValue and firstTtl <= 0) or (secondValue and secondTtl <= 0) then
		  return {-1, 0}
		end
		local firstExceeded = firstCount >= tonumber(ARGV[1])
		local secondExceeded = secondCount >= tonumber(ARGV[2])
		if firstExceeded or secondExceeded then
		  local retryAfterMillis = 0
		  if firstExceeded then retryAfterMillis = firstTtl end
		  if secondExceeded and secondTtl > retryAfterMillis then retryAfterMillis = secondTtl end
		  if retryAfterMillis <= 0 then return {-1, 0} end
		  return {0, retryAfterMillis}
		end
		local nextFirstCount = redis.call('INCR', KEYS[1])
		if nextFirstCount == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
		local nextSecondCount = redis.call('INCR', KEYS[2])
		if nextSecondCount == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[3]) end
		local nextFirstTtl = redis.call('PTTL', KEYS[1])
		local nextSecondTtl = redis.call('PTTL', KEYS[2])
		local firstReservationsExist = redis.call('EXISTS', KEYS[3]) == 1
		local secondReservationsExist = redis.call('EXISTS', KEYS[4]) == 1
		redis.call('SADD', KEYS[3], ARGV[4])
		redis.call('SADD', KEYS[4], ARGV[4])
		if not firstReservationsExist then redis.call('PEXPIRE', KEYS[3], nextFirstTtl) end
		if not secondReservationsExist then redis.call('PEXPIRE', KEYS[4], nextSecondTtl) end
		if nextFirstTtl <= 0 or nextSecondTtl <= 0 then
		  if firstCount == 0 then redis.call('DEL', KEYS[1]) else redis.call('DECR', KEYS[1]) end
		  if secondCount == 0 then redis.call('DEL', KEYS[2]) else redis.call('DECR', KEYS[2]) end
		  redis.call('SREM', KEYS[3], ARGV[4])
		  redis.call('SREM', KEYS[4], ARGV[4])
		  return {-1, 0}
		end
		return {1, 0}
		""", List.class);

	private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
		local function release(key, reservationsKey)
		  if redis.call('SREM', reservationsKey, ARGV[1]) == 1 then
		    local value = redis.call('GET', key)
		    local count = value and tonumber(value) or nil
		    if count and count > 0 and redis.call('PTTL', key) > 0 then
		      if redis.call('DECR', key) == 0 then redis.call('DEL', key, reservationsKey) end
		    end
		  end
		end
		release(KEYS[1], KEYS[3])
		release(KEYS[2], KEYS[4])
		return 1
		""", Long.class);

	private RedisFixedWindowDualBucketRateLimiter() {}

	/** 두 bucket을 하나의 Lua 실행으로 원자 판정·증가한다. 실행 자체의 {@link RuntimeException}은 호출자에게 그대로
	 * 전파하며, 그 예외를 503으로 매핑할지는 호출자가 결정한다. */
	static Decision reserve(
		StringRedisTemplate redisTemplate,
		String firstKey, String secondKey, String firstReservationsKey, String secondReservationsKey,
		int firstLimit, int secondLimit, long windowMillis, String reservationId) {
		List<?> result = redisTemplate.execute(
			RESERVE_SCRIPT,
			List.of(firstKey, secondKey, firstReservationsKey, secondReservationsKey),
			Integer.toString(firstLimit), Integer.toString(secondLimit), Long.toString(windowMillis), reservationId);
		return Decision.from(result);
	}

	/** 이전에 {@link #reserve}가 만든 예약을 두 bucket에서 함께 되돌린다. */
	static boolean release(
		StringRedisTemplate redisTemplate,
		String firstKey, String secondKey, String firstReservationsKey, String secondReservationsKey,
		String reservationId) {
		Long result = redisTemplate.execute(
			RELEASE_SCRIPT,
			List.of(firstKey, secondKey, firstReservationsKey, secondReservationsKey),
			reservationId);
		return result != null && result == 1L;
	}

	record Decision(boolean allowed, long retryAfterMillis) {

		static Decision from(List<?> result) {
			if (result == null || result.size() != 2
				|| !(result.get(0) instanceof Number status)
				|| !(result.get(1) instanceof Number ttlMillis)) {
				return new Decision(false, 0);
			}
			if (status.longValue() == 1L && ttlMillis.longValue() == 0L) {
				return new Decision(true, 0);
			}
			if (status.longValue() == 0L && ttlMillis.longValue() > 0L) {
				return new Decision(false, ttlMillis.longValue());
			}
			return new Decision(false, 0);
		}
	}
}
