package cloud.bamsongi.albammate.infra.redis;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.global.config.AuthenticationRequestProtectionProperties;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiter;
import cloud.bamsongi.albammate.global.security.ratelimit.LoginVerificationPermit;
import cloud.bamsongi.albammate.global.security.ratelimit.RateLimitDecision;

/** local과 production에서 인증 요청 제한 상태를 공용 Redis Lua 연산으로 처리한다. */
@Component
@Profile({"local", "production"})
public class RedisAuthenticationRequestLimiter implements AuthenticationRequestLimiter {

	private static final String LOCAL_NAMESPACE = "albam-mate:local:ratelimit";
	private static final String PRODUCTION_NAMESPACE = "albam-mate:production:ratelimit";

	private static final DefaultRedisScript<List> CHECK_AND_RECORD_SCRIPT = new DefaultRedisScript<>("""
		local limit = tonumber(ARGV[1])
		local windowMillis = tonumber(ARGV[2])
		local time = redis.call('TIME')
		local nowMillis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
		local expiryMillis = nowMillis - windowMillis
		redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', expiryMillis)
		local count = redis.call('ZCARD', KEYS[1])
		if count >= limit then
		  local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
		  if not oldest[2] then return {-1, 0} end
		  local retryAfterMillis = tonumber(oldest[2]) + windowMillis - nowMillis
		  if retryAfterMillis <= 0 then return {-1, 0} end
		  return {0, retryAfterMillis}
		end
		redis.call('ZADD', KEYS[1], nowMillis, ARGV[3])
		redis.call('PEXPIRE', KEYS[1], windowMillis)
		if redis.call('PTTL', KEYS[1]) <= 0 then return {-1, 0} end
		return {1, 0}
		""", List.class);

	private static final DefaultRedisScript<List> CHECK_ALLOWED_SCRIPT = new DefaultRedisScript<>("""
		local limit = tonumber(ARGV[1])
		local windowMillis = tonumber(ARGV[2])
		local time = redis.call('TIME')
		local nowMillis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
		local expiryMillis = nowMillis - windowMillis
		redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', expiryMillis)
		local count = redis.call('ZCARD', KEYS[1])
		if count >= limit then
		  local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
		  if not oldest[2] then return {-1, 0} end
		  local retryAfterMillis = tonumber(oldest[2]) + windowMillis - nowMillis
		  if retryAfterMillis <= 0 then return {-1, 0} end
		  return {0, retryAfterMillis}
		end
		return {1, 0}
		""", List.class);

	private static final DefaultRedisScript<Long> DELETE_SCRIPT = new DefaultRedisScript<>(
		"return redis.call('DEL', KEYS[1])", Long.class);
	private static final DefaultRedisScript<Long> ACQUIRE_GATE_SCRIPT = new DefaultRedisScript<>("""
		if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then return 1 end
		return 0
		""", Long.class);
	private static final DefaultRedisScript<Long> RELEASE_GATE_SCRIPT = new DefaultRedisScript<>("""
		if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) end
		return 0
		""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final String keyPrefix;
	private final AuthenticationRequestProtectionProperties properties;

	public RedisAuthenticationRequestLimiter(
		RedisConnectionFactory redisConnectionFactory,
		Environment environment,
		AuthenticationRequestProtectionProperties properties) {
		redisTemplate = new StringRedisTemplate(
			Objects.requireNonNull(redisConnectionFactory, "redisConnectionFactory"));
		redisTemplate.afterPropertiesSet();
		keyPrefix = namespaceFor(Objects.requireNonNull(environment, "environment")) + ":auth";
		this.properties = Objects.requireNonNull(properties, "properties");
	}

	@Override
	public RateLimitDecision checkAndRecordSignup(String remoteIp) {
		return checkAndRecord(ipKey("signup", remoteIp), properties.getSignupLimit());
	}

	@Override
	public RateLimitDecision checkAndRecordLogin(String remoteIp) {
		return checkAndRecord(ipKey("login", remoteIp), properties.getLoginLimit());
	}

	@Override
	public RateLimitDecision checkLoginFailureAllowed(String normalizedEmail, String remoteIp) {
		return checkAllowed(loginKey("failure", normalizedEmail, remoteIp), properties.getLoginFailureLimit());
	}

	@Override
	public RateLimitDecision recordLoginFailure(String normalizedEmail, String remoteIp) {
		return checkAndRecord(loginKey("failure", normalizedEmail, remoteIp), properties.getLoginFailureLimit());
	}

	@Override
	public void resetLoginFailures(String normalizedEmail, String remoteIp) {
		try {
			Long result = redisTemplate.execute(DELETE_SCRIPT, List.of(loginKey("failure", normalizedEmail, remoteIp)));
			if (result == null || (result != 0L && result != 1L)) {
				throw unavailable();
			}
		} catch (BusinessException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	@Override
	public Optional<LoginVerificationPermit> tryAcquireLoginVerification(String normalizedEmail, String remoteIp) {
		String key = loginKey("gate", normalizedEmail, remoteIp);
		String ownerToken = UUID.randomUUID().toString();
		try {
			Long result = redisTemplate.execute(ACQUIRE_GATE_SCRIPT, List.of(key), ownerToken,
				Long.toString(properties.getWindow().toMillis()));
			if (result == null || (result != 0L && result != 1L)) {
				throw unavailable();
			}
			return result == 1L ? Optional.of(new RedisLoginVerificationPermit(key, ownerToken)) : Optional.empty();
		} catch (BusinessException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	static String namespaceFor(Environment environment) {
		return environment.acceptsProfiles(Profiles.of("production")) ? PRODUCTION_NAMESPACE : LOCAL_NAMESPACE;
	}

	private RateLimitDecision checkAndRecord(String key, int limit) {
		return decision(
			CHECK_AND_RECORD_SCRIPT,
			key,
			Integer.toString(limit),
			Long.toString(properties.getWindow().toMillis()),
			UUID.randomUUID().toString());
	}

	private RateLimitDecision checkAllowed(String key, int limit) {
		return decision(
			CHECK_ALLOWED_SCRIPT,
			key,
			Integer.toString(limit),
			Long.toString(properties.getWindow().toMillis()));
	}

	private RateLimitDecision decision(DefaultRedisScript<List> script, String key, String... args) {
		try {
			List<?> result = redisTemplate.execute(script, List.of(key), (Object[])args);
			if (result == null || result.size() != 2
				|| !(result.get(0) instanceof Number status)
				|| !(result.get(1) instanceof Number ttlMillis)) {
				throw unavailable();
			}
			if (status.longValue() == 1L && ttlMillis.longValue() == 0L) {
				return RateLimitDecision.permitted();
			}
			if (status.longValue() == 0L && ttlMillis.longValue() > 0L) {
				return RateLimitDecision.rejected(roundUpSeconds(ttlMillis.longValue()));
			}
			throw unavailable();
		} catch (BusinessException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw unavailable();
		}
	}

	private String ipKey(String type, String remoteIp) {
		return keyPrefix + ":" + type + ":ip:" + digest(requiredPart(remoteIp, "remoteIp"));
	}

	private String loginKey(String type, String normalizedEmail, String remoteIp) {
		return keyPrefix + ":login:" + type + ":" + digest(
			requiredPart(normalizedEmail, "normalizedEmail") + "\u0000" + requiredPart(remoteIp, "remoteIp"));
	}

	private String requiredPart(String value, String name) {
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " must not be blank");
		}
		return value;
	}

	private String digest(String value) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
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

	private final class RedisLoginVerificationPermit implements LoginVerificationPermit {

		private final String key;
		private final String ownerToken;
		private final AtomicBoolean closed = new AtomicBoolean();

		private RedisLoginVerificationPermit(String key, String ownerToken) {
			this.key = key;
			this.ownerToken = ownerToken;
		}

		@Override
		public void close() {
			if (!closed.compareAndSet(false, true)) {
				return;
			}
			try {
				Long result = redisTemplate.execute(RELEASE_GATE_SCRIPT, List.of(key), ownerToken);
				if (result == null || (result != 0L && result != 1L)) {
					throw unavailable();
				}
			} catch (BusinessException exception) {
				throw exception;
			} catch (RuntimeException exception) {
				throw unavailable();
			}
		}
	}
}
