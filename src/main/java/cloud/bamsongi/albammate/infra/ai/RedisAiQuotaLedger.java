package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import cloud.bamsongi.albammate.assistant.contract.AssistantCostWarningEvent;
import jakarta.annotation.PreDestroy;

/** local·production에서 여러 인스턴스가 공유하는 AI quota와 비용 예약 adapter다. */
final class RedisAiQuotaLedger implements AiQuotaLedger {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	private static final String KEY_PREFIX = "albam-mate:assistant:quota";
	private static final BigDecimal DEFAULT_RESERVATION_COST = new BigDecimal("0.10");
	private static final BigDecimal COST_CAP = new BigDecimal("5.00");
	private static final BigDecimal WARNING_CAP = new BigDecimal("4.00");
	private static final Duration ACTIVE_RESERVATION_GRACE = Duration.ofSeconds(20);
	private static final long MIN_ACTIVE_RESERVATION_TTL_SECONDS = 30;
	private static final long[] COMPLETION_RETRY_DELAYS_SECONDS = {1, 2, 4, 8, 12};

	private static final DefaultRedisScript<List> RESERVE_SCRIPT = new DefaultRedisScript<>("""
		local activeValue = redis.call('GET', KEYS[3])
		if activeValue then
		  return {0, 'CONCURRENT_LIMIT_REACHED'}
		end
		local daily = tonumber(redis.call('GET', KEYS[1]) or '0')
		local monthly = tonumber(redis.call('GET', KEYS[2]) or '0')
		local cost = tonumber(redis.call('GET', KEYS[4]) or '0')
		if daily >= tonumber(ARGV[1]) or monthly >= tonumber(ARGV[2]) then
		  return {0, 'USER_LIMIT_REACHED'}
		end
		if cost + tonumber(ARGV[4]) > tonumber(ARGV[5]) then
		  return {0, 'COST_CAP_REACHED'}
		end
		local activeCreated = redis.call('SET', KEYS[3], ARGV[9], 'EX', ARGV[6], 'NX')
		if not activeCreated then
		  return {0, 'CONCURRENT_LIMIT_REACHED'}
		end
		local dailyNext = redis.call('INCR', KEYS[1])
		if dailyNext == 1 then redis.call('EXPIRE', KEYS[1], ARGV[7]) end
		local monthlyNext = redis.call('INCR', KEYS[2])
		if monthlyNext == 1 then redis.call('EXPIRE', KEYS[2], ARGV[8]) end
		local costNext = redis.call('INCRBY', KEYS[4], ARGV[4])
		if costNext == tonumber(ARGV[4]) then redis.call('EXPIRE', KEYS[4], ARGV[8]) end
		return {1, ARGV[9]}
		""", List.class);

	private static final DefaultRedisScript<List> COMPLETE_SCRIPT = new DefaultRedisScript<>("""
		local completedValue = redis.call('GET', KEYS[4])
		local activeValue = redis.call('GET', KEYS[1])
		local cost = tonumber(redis.call('GET', KEYS[2]) or '0')
		if not activeValue or activeValue ~= ARGV[3] then
		  if completedValue then return {1, 0, cost} end
		  return {0, 'UNAVAILABLE', cost}
		end
		local nextCost = cost - tonumber(ARGV[2]) + tonumber(ARGV[1])
		if nextCost < 0 then nextCost = 0 end
		redis.call('SET', KEYS[2], nextCost, 'EX', ARGV[5])
		redis.call('DEL', KEYS[1])
		local warningRaised = 0
		if nextCost >= tonumber(ARGV[4]) then
		  if redis.call('SET', KEYS[3], '1', 'EX', ARGV[5], 'NX') then warningRaised = 1 end
		end
		redis.call('SET', KEYS[4], '1', 'EX', ARGV[5])
		return {1, warningRaised, nextCost}
		""", List.class);

	private final StringRedisTemplate redisTemplate;
	private final AiCostWarningEventSink costWarningEventSink;
	private final long activeReservationTtlSeconds;
	private final ScheduledExecutorService completionRetryExecutor = Executors.newSingleThreadScheduledExecutor(
		new DaemonThreadFactory());

	RedisAiQuotaLedger(StringRedisTemplate redisTemplate) {
		this(redisTemplate, Duration.ofSeconds(10));
	}

	RedisAiQuotaLedger(StringRedisTemplate redisTemplate, Duration providerTimeout) {
		this(redisTemplate, providerTimeout, event -> {});
	}

	RedisAiQuotaLedger(
		StringRedisTemplate redisTemplate,
		Duration providerTimeout,
		AiCostWarningEventSink costWarningEventSink) {
		this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
		this.costWarningEventSink = Objects.requireNonNull(costWarningEventSink, "costWarningEventSink");
		Objects.requireNonNull(providerTimeout, "providerTimeout");
		if (providerTimeout.isNegative()) {
			throw new IllegalArgumentException("providerTimeout must not be negative");
		}
		this.activeReservationTtlSeconds = Math.max(
			MIN_ACTIVE_RESERVATION_TTL_SECONDS,
			providerTimeout.plus(ACTIVE_RESERVATION_GRACE).toSeconds());
		this.redisTemplate.afterPropertiesSet();
	}

	@Override
	public AiQuotaReservation reserve(String quotaSubject, Instant now) {
		return reserve(quotaSubject, now, DEFAULT_RESERVATION_COST);
	}

	@Override
	public AiQuotaReservation reserve(String quotaSubject, Instant now, BigDecimal estimatedCostUsd) {
		Objects.requireNonNull(quotaSubject, "quotaSubject");
		Objects.requireNonNull(now, "now");
		Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
		if (estimatedCostUsd.signum() < 0) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.UNAVAILABLE);
		}
		YearMonth month = YearMonth.from(now.atZone(KOREA));
		LocalDate day = now.atZone(KOREA).toLocalDate();
		String subjectHash = hashSubject(quotaSubject);
		String token = UUID.randomUUID().toString();
		List<String> keys = List.of(
			dailyKey(month, day, subjectHash),
			monthlyKey(month, subjectHash),
			activeKey(subjectHash),
			costKey(month));
		List<?> result;
		try {
			result = redisTemplate.execute(
				RESERVE_SCRIPT,
				keys,
				"5",
				"150",
				"1",
				Long.toString(toCents(estimatedCostUsd)),
				Long.toString(toCents(COST_CAP)),
				Long.toString(activeReservationTtlSeconds),
				Long.toString(ttlSeconds(now, day.plusDays(1).atStartOfDay(KOREA).toInstant())),
				Long.toString(ttlSeconds(now, month.plusMonths(1).atDay(1).atStartOfDay(KOREA).toInstant())),
				token);
		} catch (RuntimeException exception) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.UNAVAILABLE);
		}
		if (result == null || result.size() < 2) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.UNAVAILABLE);
		}
		if (!"1".equals(String.valueOf(result.get(0)))) {
			return AiQuotaReservation.rejected(statusOf(result.get(1)));
		}
		return AiQuotaReservation.acquired(quotaSubject, month, token,
			estimatedCostUsd.setScale(2, RoundingMode.CEILING));
	}

	long activeReservationTtlSeconds() {
		return activeReservationTtlSeconds;
	}

	@Override
	public AiQuotaCompletionStatus complete(AiQuotaReservation reservation, BigDecimal costUsd) {
		if (reservation.status() != AiQuotaReservationStatus.ACQUIRED) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		if (reservation.quotaMonth() == null || reservation.reservationToken().isBlank()) {
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}
		Objects.requireNonNull(costUsd, "costUsd");
		String subjectHash = hashSubject(reservation.quotaSubject());
		List<?> result;
		try {
			result = redisTemplate.execute(
				COMPLETE_SCRIPT,
				List.of(
					activeKey(subjectHash),
					costKey(reservation.quotaMonth()),
					warningKey(reservation.quotaMonth()),
					completedKey(reservation.quotaMonth(), reservation.reservationToken())),
				Long.toString(toCents(costUsd)),
				Long.toString(toCents(reservation.reservedCostUsd())),
				reservation.reservationToken(),
				Long.toString(toCents(WARNING_CAP)),
				Long.toString(ttlSeconds(
					Instant.now(),
					reservation.quotaMonth().plusMonths(1).atDay(1).atStartOfDay(KOREA).toInstant())));
		} catch (RuntimeException exception) {
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}
		if (result == null || result.size() < 1 || !"1".equals(String.valueOf(result.get(0)))) {
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}
		if (result.size() >= 3 && "1".equals(String.valueOf(result.get(1)))) {
			costWarningEventSink.record(new AssistantCostWarningEvent(
				reservation.quotaMonth(), cents(result.get(2)), WARNING_CAP));
		}
		return AiQuotaCompletionStatus.COMPLETED;
	}

	@Override
	public void scheduleCompletionRetry(AiQuotaReservation reservation, BigDecimal costUsd) {
		if (reservation.status() != AiQuotaReservationStatus.ACQUIRED) {
			return;
		}
		scheduleCompletionRetry(reservation, costUsd, 0);
	}

	private void scheduleCompletionRetry(AiQuotaReservation reservation, BigDecimal costUsd, int attempt) {
		if (attempt >= COMPLETION_RETRY_DELAYS_SECONDS.length) {
			return;
		}
		long delaySeconds = COMPLETION_RETRY_DELAYS_SECONDS[attempt];
		completionRetryExecutor.schedule(() -> {
			if (complete(reservation, costUsd) == AiQuotaCompletionStatus.UNAVAILABLE) {
				scheduleCompletionRetry(reservation, costUsd, attempt + 1);
			}
		}, delaySeconds, TimeUnit.SECONDS);
	}

	@PreDestroy
	void shutdownCompletionRetryExecutor() {
		completionRetryExecutor.shutdownNow();
	}

	private AiQuotaReservationStatus statusOf(Object rawStatus) {
		try {
			return AiQuotaReservationStatus.valueOf(String.valueOf(rawStatus));
		} catch (IllegalArgumentException exception) {
			return AiQuotaReservationStatus.UNAVAILABLE;
		}
	}

	private long ttlSeconds(Instant now, Instant boundary) {
		return Math.max(1, Duration.between(now, boundary).toSeconds());
	}

	private long toCents(BigDecimal amount) {
		return amount.setScale(2, RoundingMode.CEILING).movePointRight(2).longValueExact();
	}

	private BigDecimal cents(Object rawCents) {
		return BigDecimal.valueOf(Long.parseLong(String.valueOf(rawCents)), 2);
	}

	private String dailyKey(YearMonth month, LocalDate day, String subjectHash) {
		return KEY_PREFIX + ":daily:" + month + ":" + day + ":" + subjectHash;
	}

	private String monthlyKey(YearMonth month, String subjectHash) {
		return KEY_PREFIX + ":monthly:" + month + ":" + subjectHash;
	}

	private String activeKey(String subjectHash) {
		return KEY_PREFIX + ":active:" + subjectHash;
	}

	private String costKey(YearMonth month) {
		return KEY_PREFIX + ":cost:" + month;
	}

	private String warningKey(YearMonth month) {
		return KEY_PREFIX + ":warning:" + month;
	}

	private String completedKey(YearMonth month, String reservationToken) {
		return KEY_PREFIX + ":completed:" + month + ":" + reservationToken;
	}

	private String hashSubject(String quotaSubject) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256")
				.digest(quotaSubject.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private static final class DaemonThreadFactory implements ThreadFactory {

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "ai-quota-completion-retry");
			thread.setDaemon(true);
			return thread;
		}
	}
}
