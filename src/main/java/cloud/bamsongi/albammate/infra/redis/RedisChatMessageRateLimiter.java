package cloud.bamsongi.albammate.infra.redis;

import java.util.List;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.RateLimitExceededException;

/** local-multi 공용 Redis에서 채팅 전송 두 bucket을 Lua 한 번으로 예약하는 adapter다. */
@Component
@Profile("local-multi")
public class RedisChatMessageRateLimiter implements ChatMessageRateLimiter {

	private static final String KEY_PREFIX = "albam-mate:local-multi:ratelimit";
	private static final String RESERVATION_SUFFIX = ":reservations";
	private static final int USER_LIMIT = 5;
	private static final int ROOM_LIMIT = 30;
	private static final long WINDOW_MILLIS = 10_000L;

	private static final DefaultRedisScript<List> RESERVE_SCRIPT = new DefaultRedisScript<>("""
		local userValue = redis.call('GET', KEYS[1])
		local roomValue = redis.call('GET', KEYS[2])
		local userCount = userValue and tonumber(userValue) or 0
		local roomCount = roomValue and tonumber(roomValue) or 0
		if not userCount or not roomCount or userCount < 0 or roomCount < 0 then
		  return {-1, 0}
		end
		local userTtl = redis.call('PTTL', KEYS[1])
		local roomTtl = redis.call('PTTL', KEYS[2])
		if (userValue and userTtl <= 0) or (roomValue and roomTtl <= 0) then
		  return {-1, 0}
		end
		local userExceeded = userCount >= tonumber(ARGV[1])
		local roomExceeded = roomCount >= tonumber(ARGV[2])
		if userExceeded or roomExceeded then
		  local retryAfterMillis = 0
		  if userExceeded then retryAfterMillis = userTtl end
		  if roomExceeded and roomTtl > retryAfterMillis then retryAfterMillis = roomTtl end
		  if retryAfterMillis <= 0 then return {-1, 0} end
		  return {0, retryAfterMillis}
		end
		local nextUserCount = redis.call('INCR', KEYS[1])
		if nextUserCount == 1 then redis.call('PEXPIRE', KEYS[1], ARGV[3]) end
		local nextRoomCount = redis.call('INCR', KEYS[2])
		if nextRoomCount == 1 then redis.call('PEXPIRE', KEYS[2], ARGV[3]) end
		local nextUserTtl = redis.call('PTTL', KEYS[1])
		local nextRoomTtl = redis.call('PTTL', KEYS[2])
		local userReservationsExist = redis.call('EXISTS', KEYS[3]) == 1
		local roomReservationsExist = redis.call('EXISTS', KEYS[4]) == 1
		redis.call('SADD', KEYS[3], ARGV[4])
		redis.call('SADD', KEYS[4], ARGV[4])
		if not userReservationsExist then redis.call('PEXPIRE', KEYS[3], nextUserTtl) end
		if not roomReservationsExist then redis.call('PEXPIRE', KEYS[4], nextRoomTtl) end
		if nextUserTtl <= 0 or nextRoomTtl <= 0 then
		  if userCount == 0 then redis.call('DEL', KEYS[1]) else redis.call('DECR', KEYS[1]) end
		  if roomCount == 0 then redis.call('DEL', KEYS[2]) else redis.call('DECR', KEYS[2]) end
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

	private final StringRedisTemplate redisTemplate;

	public RedisChatMessageRateLimiter(RedisConnectionFactory redisConnectionFactory) {
		redisTemplate = new StringRedisTemplate(redisConnectionFactory);
		redisTemplate.afterPropertiesSet();
	}

	@Override
	public RateLimitReservation reserve(long userId, long roomId) {
		List<?> result;
		String reservationId = UUID.randomUUID().toString();
		try {
			result = redisTemplate.execute(
				RESERVE_SCRIPT,
				List.of(userKey(userId), roomKey(roomId), userReservationsKey(userId), roomReservationsKey(roomId)),
				Integer.toString(USER_LIMIT),
				Integer.toString(ROOM_LIMIT),
				Long.toString(WINDOW_MILLIS),
				reservationId);
		} catch (RuntimeException exception) {
			throw unavailable();
		}
		if (result == null) {
			throw unavailable();
		}
		Decision decision = Decision.from(result);
		if (decision.allowed()) {
			return () -> release(userId, roomId, reservationId);
		}
		if (decision.retryAfterMillis() > 0) {
			throw new RateLimitExceededException(roundUpSeconds(decision.retryAfterMillis()));
		}
		throw unavailable();
	}

	private void release(long userId, long roomId, String reservationId) {
		try {
			Long result = redisTemplate.execute(
				RELEASE_SCRIPT,
				List.of(userKey(userId), roomKey(roomId), userReservationsKey(userId), roomReservationsKey(roomId)),
				reservationId);
			if (result == null || result != 1L) {
				throw unavailable();
			}
		} catch (BusinessException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	private String userKey(long userId) {
		return KEY_PREFIX + ":user:" + userId;
	}

	private String roomKey(long roomId) {
		return KEY_PREFIX + ":room:" + roomId;
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

	private record Decision(boolean allowed, long retryAfterMillis) {

		private static Decision from(List<?> result) {
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
