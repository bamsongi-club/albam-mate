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
import cloud.bamsongi.albammate.global.security.ratelimit.AuthenticationRequestLimiterMetrics;
import cloud.bamsongi.albammate.global.security.ratelimit.LoginVerificationPermit;
import cloud.bamsongi.albammate.global.security.ratelimit.RateLimitDecision;

/** local과 production에서 인증 요청 제한 상태를 공용 Redis Lua 연산으로 처리한다. */
@Component
@Profile({"local", "production"})
public class RedisAuthenticationRequestLimiter implements AuthenticationRequestLimiter {

	private static final String LOCAL_NAMESPACE = "albam-mate:local:ratelimit";
	private static final String PRODUCTION_NAMESPACE = "albam-mate:production:ratelimit";

	private static final String REGISTRY_HELPERS = """
		local time = redis.call('TIME')
		local nowMillis = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000)
		redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', nowMillis)
		local function refreshRegistry()
		  local firstTtl = redis.call('PTTL', KEYS[1])
		  local secondTtl = redis.call('PTTL', KEYS[2])
		  local ttl = math.max(firstTtl > 0 and firstTtl or 0, secondTtl > 0 and secondTtl or 0)
		  if ttl > 0 then
		    redis.call('ZADD', KEYS[3], nowMillis + ttl, ARGV[4])
		  else
		    redis.call('ZREM', KEYS[3], ARGV[4])
		  end
		  return redis.call('ZCARD', KEYS[3])
		end
		""";

	private static final DefaultRedisScript<List> CHECK_AND_RECORD_SCRIPT = new DefaultRedisScript<>(
		REGISTRY_HELPERS + """
			local limit = tonumber(ARGV[1])
			local windowMillis = tonumber(ARGV[2])
			if not redis.call('ZSCORE', KEYS[3], ARGV[4]) and redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[5]) then
			  return {2, 0, redis.call('ZCARD', KEYS[3])}
			end
			redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMillis - windowMillis)
			local count = redis.call('ZCARD', KEYS[1])
			if count >= limit then
			  local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
			  if not oldest[2] then return {-1, 0, refreshRegistry()} end
			  local retryAfterMillis = tonumber(oldest[2]) + windowMillis - nowMillis
			  if retryAfterMillis <= 0 then return {-1, 0, refreshRegistry()} end
			  return {0, retryAfterMillis, refreshRegistry()}
			end
			redis.call('ZADD', KEYS[1], nowMillis, ARGV[3])
			redis.call('PEXPIRE', KEYS[1], windowMillis)
			if redis.call('PTTL', KEYS[1]) <= 0 then return {-1, 0, refreshRegistry()} end
			return {1, 0, refreshRegistry()}
			""", List.class);

	private static final DefaultRedisScript<List> CHECK_ALLOWED_SCRIPT = new DefaultRedisScript<>(REGISTRY_HELPERS + """
		local limit = tonumber(ARGV[1])
		local windowMillis = tonumber(ARGV[2])
		redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', nowMillis - windowMillis)
		local count = redis.call('ZCARD', KEYS[1])
		if count >= limit then
		  local oldest = redis.call('ZRANGE', KEYS[1], 0, 0, 'WITHSCORES')
		  if not oldest[2] then return {-1, 0, refreshRegistry()} end
		  local retryAfterMillis = tonumber(oldest[2]) + windowMillis - nowMillis
		  if retryAfterMillis <= 0 then return {-1, 0, refreshRegistry()} end
		  return {0, retryAfterMillis, refreshRegistry()}
		end
		return {1, 0, refreshRegistry()}
		""", List.class);

	private static final DefaultRedisScript<List> ACQUIRE_GATE_SCRIPT = new DefaultRedisScript<>(REGISTRY_HELPERS + """
		if not redis.call('ZSCORE', KEYS[3], ARGV[4]) and redis.call('ZCARD', KEYS[3]) >= tonumber(ARGV[5]) then
		  return {2, 0, redis.call('ZCARD', KEYS[3])}
		end
		if redis.call('SET', KEYS[1], ARGV[1], 'NX', 'PX', ARGV[2]) then
		  return {1, 0, refreshRegistry()}
		end
		return {0, 0, refreshRegistry()}
		""", List.class);

	private static final DefaultRedisScript<List> RESET_FAILURES_SCRIPT = new DefaultRedisScript<>(
		REGISTRY_HELPERS + """
			redis.call('DEL', KEYS[1])
			return {1, 0, refreshRegistry()}
			""", List.class);

	private static final DefaultRedisScript<List> RELEASE_GATE_SCRIPT = new DefaultRedisScript<>(REGISTRY_HELPERS + """
		if redis.call('GET', KEYS[1]) == ARGV[1] then redis.call('DEL', KEYS[1]) end
		return {1, 0, refreshRegistry()}
		""", List.class);

	private final StringRedisTemplate redisTemplate;
	private final String keyPrefix;
	private final AuthenticationRequestProtectionProperties properties;
	private final AuthenticationRequestLimiterMetrics metrics;

	public RedisAuthenticationRequestLimiter(
		RedisConnectionFactory redisConnectionFactory,
		Environment environment,
		AuthenticationRequestProtectionProperties properties,
		AuthenticationRequestLimiterMetrics metrics) {
		redisTemplate = new StringRedisTemplate(
			Objects.requireNonNull(redisConnectionFactory, "redisConnectionFactory"));
		redisTemplate.afterPropertiesSet();
		keyPrefix = namespaceFor(Objects.requireNonNull(environment, "environment")) + ":auth";
		this.properties = Objects.requireNonNull(properties, "properties");
		this.metrics = Objects.requireNonNull(metrics, "metrics");
	}

	@Override
	public RateLimitDecision checkAndRecordSignup(String remoteIp) {
		return checkAndRecord("ip", ipKey("signup", remoteIp), ipKey("login", remoteIp), ipMember(remoteIp),
			properties.getSignupLimit(), properties.getMaxIpKeys());
	}

	@Override
	public RateLimitDecision checkAndRecordLogin(String remoteIp) {
		return checkAndRecord("ip", ipKey("login", remoteIp), ipKey("signup", remoteIp), ipMember(remoteIp),
			properties.getLoginLimit(), properties.getMaxIpKeys());
	}

	@Override
	public RateLimitDecision checkLoginFailureAllowed(String normalizedEmail, String remoteIp) {
		String member = loginMember(normalizedEmail, remoteIp);
		return decision("failure", CHECK_ALLOWED_SCRIPT,
			List.of(loginKey("failure", normalizedEmail, remoteIp), loginKey("gate", normalizedEmail, remoteIp),
				registryKey("failure")),
			Integer.toString(properties.getLoginFailureLimit()), Long.toString(properties.getWindow().toMillis()),
			"unused", member);
	}

	@Override
	public RateLimitDecision recordLoginFailure(String normalizedEmail, String remoteIp) {
		String member = loginMember(normalizedEmail, remoteIp);
		return checkAndRecord("failure", loginKey("failure", normalizedEmail, remoteIp),
			loginKey("gate", normalizedEmail, remoteIp), member, properties.getLoginFailureLimit(),
			properties.getMaxFailureKeys());
	}

	@Override
	public void resetLoginFailures(String normalizedEmail, String remoteIp) {
		String member = loginMember(normalizedEmail, remoteIp);
		decision("failure", RESET_FAILURES_SCRIPT,
			List.of(loginKey("failure", normalizedEmail, remoteIp), loginKey("gate", normalizedEmail, remoteIp),
				registryKey("failure")),
			"unused", "unused", "unused", member);
	}

	@Override
	public Optional<LoginVerificationPermit> tryAcquireLoginVerification(String normalizedEmail, String remoteIp) {
		String gateKey = loginKey("gate", normalizedEmail, remoteIp);
		String failureKey = loginKey("failure", normalizedEmail, remoteIp);
		String member = loginMember(normalizedEmail, remoteIp);
		String ownerToken = UUID.randomUUID().toString();
		List<?> result = execute("failure", ACQUIRE_GATE_SCRIPT,
			List.of(gateKey, failureKey, registryKey("failure")), ownerToken,
			Long.toString(properties.getWindow().toMillis()), "unused", member,
			Integer.toString(properties.getMaxFailureKeys()));
		long status = status("failure", result);
		metrics.recordUsage("failure", (int)numberAt("failure", result, 2), properties.getMaxFailureKeys(),
			properties.getWindow());
		if (status == 1L) {
			return Optional.of(new RedisLoginVerificationPermit(gateKey, failureKey, member, ownerToken));
		}
		if (status == 0L) {
			return Optional.empty();
		}
		if (status == 2L) {
			metrics.incrementRejection("failure", "capacity_saturated");
			return capacitySaturated();
		}
		throw unavailable("failure");
	}

	static String namespaceFor(Environment environment) {
		return environment.acceptsProfiles(Profiles.of("production")) ? PRODUCTION_NAMESPACE : LOCAL_NAMESPACE;
	}

	private RateLimitDecision checkAndRecord(String family, String targetKey, String siblingKey, String member,
		int limit, int maxKeys) {
		return decision(family, CHECK_AND_RECORD_SCRIPT, List.of(targetKey, siblingKey, registryKey(family)),
			Integer.toString(limit), Long.toString(properties.getWindow().toMillis()), UUID.randomUUID().toString(),
			member,
			Integer.toString(maxKeys));
	}

	private RateLimitDecision decision(String family, DefaultRedisScript<List> script, List<String> keys,
		String... args) {
		List<?> result = execute(family, script, keys, args);
		long status = status(family, result);
		long ttlMillis = numberAt(family, result, 1);
		if (status == 1L && ttlMillis == 0L) {
			metrics.recordUsage(family, (int)numberAt(family, result, 2), maximumFor(family), properties.getWindow());
			return RateLimitDecision.permitted();
		}
		if (status == 0L && ttlMillis > 0L) {
			metrics.recordUsage(family, (int)numberAt(family, result, 2), maximumFor(family), properties.getWindow());
			return RateLimitDecision.rejected(roundUpSeconds(ttlMillis));
		}
		if (status == 2L && ttlMillis == 0L) {
			metrics.recordUsage(family, (int)numberAt(family, result, 2), maximumFor(family), properties.getWindow());
			metrics.incrementRejection(family, "capacity_saturated");
			return RateLimitDecision.capacitySaturated();
		}
		throw unavailable(family);
	}

	private int maximumFor(String family) {
		return "ip".equals(family) ? properties.getMaxIpKeys() : properties.getMaxFailureKeys();
	}

	private List<?> execute(String family, DefaultRedisScript<List> script, List<String> keys, String... args) {
		try {
			List<?> result = redisTemplate.execute(script, keys, (Object[])args);
			if (result == null || result.size() != 3 || !(result.get(2) instanceof Number)) {
				throw unavailable(family);
			}
			return result;
		} catch (BusinessException exception) {
			throw exception;
		} catch (RuntimeException exception) {
			throw unavailable(family);
		}
	}

	private long status(String family, List<?> result) {
		return numberAt(family, result, 0);
	}

	private long numberAt(String family, List<?> result, int index) {
		if (!(result.get(index) instanceof Number number)) {
			throw unavailable(family);
		}
		return number.longValue();
	}

	private Optional<LoginVerificationPermit> capacitySaturated() {
		RateLimitDecision.capacitySaturated().throwIfRejected();
		return Optional.empty();
	}

	private String registryKey(String family) {
		return keyPrefix + ":" + family + ":registry";
	}

	private String ipKey(String type, String remoteIp) {
		return keyPrefix + ":" + type + ":ip:" + digest(requiredPart(remoteIp, "remoteIp"));
	}

	private String ipMember(String remoteIp) {
		return digest(requiredPart(remoteIp, "remoteIp"));
	}

	private String loginKey(String type, String normalizedEmail, String remoteIp) {
		return keyPrefix + ":login:" + type + ":" + loginMember(normalizedEmail, remoteIp);
	}

	private String loginMember(String normalizedEmail, String remoteIp) {
		return digest(requiredPart(normalizedEmail, "normalizedEmail") + "\u0000" + requiredPart(remoteIp, "remoteIp"));
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

	private BusinessException unavailable(String family) {
		metrics.incrementRejection(family, "redis_unavailable");
		return unavailable();
	}

	private final class RedisLoginVerificationPermit implements LoginVerificationPermit {

		private final String gateKey;
		private final String failureKey;
		private final String member;
		private final String ownerToken;
		private final AtomicBoolean closed = new AtomicBoolean();

		private RedisLoginVerificationPermit(String gateKey, String failureKey, String member, String ownerToken) {
			this.gateKey = gateKey;
			this.failureKey = failureKey;
			this.member = member;
			this.ownerToken = ownerToken;
		}

		@Override
		public void close() {
			if (closed.compareAndSet(false, true)) {
				decision("failure", RELEASE_GATE_SCRIPT, List.of(gateKey, failureKey, registryKey("failure")),
					ownerToken, "unused", "unused", member);
			}
		}
	}
}
