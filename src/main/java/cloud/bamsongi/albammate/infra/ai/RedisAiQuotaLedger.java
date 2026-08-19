package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import cloud.bamsongi.albammate.assistant.contract.AssistantCostWarningEvent;
import jakarta.annotation.PreDestroy;

/** Redis Lua로 사용자 quota·비용 예약·completion을 각각 원자적으로 전이한다. */
final class RedisAiQuotaLedger implements AiQuotaLedger {

	static final Duration ACTIVE_RESERVATION_TTL = Duration.ofMinutes(2);
	private static final String KEY_PREFIX = "albam:ai:quota:v1";
	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final BigDecimal WARNING_THRESHOLD_USD = new BigDecimal("4.00");
	private static final long WARNING_THRESHOLD_CENTS = 400;
	private static final long HARD_CAP_CENTS = 500;
	private static final long COMPLETION_RETRY_DELAY_SECONDS = 1;
	private static final int DAILY_LIMIT = 5;
	private static final int MONTHLY_LIMIT = 150;
	private static final DefaultRedisScript<List> RESERVE_SCRIPT = new DefaultRedisScript<>(
		"""
			local dayCount = tonumber(redis.call('GET', KEYS[1]) or '0')
			local monthCount = tonumber(redis.call('GET', KEYS[2]) or '0')
			redis.call('ZREMRANGEBYSCORE', KEYS[3], '-inf', ARGV[1])
			if redis.call('ZCARD', KEYS[3]) >= 1 then return {3, 0, '0'} end
			if dayCount >= tonumber(ARGV[2]) or monthCount >= tonumber(ARGV[3]) then return {2, 0, '0'} end
			local currentCostCents = tonumber(redis.call('GET', KEYS[4]) or '0')
			local nextCostCents = currentCostCents + tonumber(ARGV[4])
			if nextCostCents > tonumber(ARGV[5]) then return {4, 0, tostring(currentCostCents)} end
			if dayCount == 0 then redis.call('PSETEX', KEYS[1], ARGV[6], '1') else redis.call('INCR', KEYS[1]) end
			if monthCount == 0 then redis.call('PSETEX', KEYS[2], ARGV[7], '1') else redis.call('INCR', KEYS[2]) end
			if currentCostCents == 0 then redis.call('PSETEX', KEYS[4], ARGV[7], tostring(nextCostCents)) else redis.call('SET', KEYS[4], tostring(nextCostCents), 'KEEPTTL') end
			redis.call('ZADD', KEYS[3], ARGV[8], ARGV[9])
			redis.call('PEXPIRE', KEYS[3], ARGV[10])
			redis.call('HSET', KEYS[6], 'state', 'PENDING', 'reservedCostCents', ARGV[4], 'quotaMonth', ARGV[11], 'subjectHash', ARGV[13])
			redis.call('PEXPIRE', KEYS[6], ARGV[7])
			local warned = 0
			if nextCostCents >= tonumber(ARGV[12]) and redis.call('SET', KEYS[5], '1', 'NX', 'PX', ARGV[7]) then warned = 1 end
			return {1, warned, tostring(nextCostCents)}
			""",
		List.class);
	private static final DefaultRedisScript<List> COMPLETE_SCRIPT = new DefaultRedisScript<>(
		"""
			if redis.call('EXISTS', KEYS[1]) == 0 then return {2, 0, '0'} end
			if redis.call('HGET', KEYS[1], 'state') == 'COMPLETED' then
			  redis.call('ZREM', KEYS[5], ARGV[2])
			  return {1, 0, redis.call('GET', KEYS[3]) or '0'}
			end
			local reservedCostCents = tonumber(redis.call('HGET', KEYS[1], 'reservedCostCents') or '0')
			local currentCostCents = tonumber(redis.call('GET', KEYS[3]) or '0')
			local nextCostCents = currentCostCents + tonumber(ARGV[1]) - reservedCostCents
			redis.call('HSET', KEYS[1], 'state', 'COMPLETED', 'actualCostCents', ARGV[1])
			redis.call('HDEL', KEYS[1], 'pendingCompletionCostCents')
			redis.call('ZREM', KEYS[2], ARGV[2])
			redis.call('ZREM', KEYS[5], ARGV[2])
			redis.call('SET', KEYS[3], tostring(nextCostCents), 'KEEPTTL')
			local warned = 0
			if nextCostCents >= tonumber(ARGV[3]) and redis.call('SET', KEYS[4], '1', 'NX', 'PX', ARGV[4]) then warned = 1 end
			return {1, warned, tostring(nextCostCents)}
			""",
		List.class);
	private static final DefaultRedisScript<Long> SCHEDULE_COMPLETION_SCRIPT = new DefaultRedisScript<>(
		"""
			if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('HGET', KEYS[1], 'state') ~= 'PENDING' then return 0 end
			local reservationTtl = redis.call('PTTL', KEYS[1])
			if reservationTtl <= 0 then return 0 end
			redis.call('HSET', KEYS[1], 'pendingCompletionCostCents', ARGV[1])
			redis.call('ZADD', KEYS[2], 0, ARGV[2])
			local pendingTtl = redis.call('PTTL', KEYS[2])
			if pendingTtl < reservationTtl then redis.call('PEXPIRE', KEYS[2], reservationTtl) end
			return 1
			""",
		Long.class);

	private final StringRedisTemplate redisTemplate;
	private final AiCostWarningEventSink warningEventSink;
	private final Duration activeReservationTtl;
	private final ScheduledExecutorService completionRetryExecutor = Executors
		.newSingleThreadScheduledExecutor(new DaemonThreadFactory());

	RedisAiQuotaLedger(RedisConnectionFactory connectionFactory, AiCostWarningEventSink warningEventSink) {
		this(redisTemplate(connectionFactory), warningEventSink, ACTIVE_RESERVATION_TTL);
	}

	RedisAiQuotaLedger(StringRedisTemplate redisTemplate, AiCostWarningEventSink warningEventSink) {
		this(redisTemplate, warningEventSink, ACTIVE_RESERVATION_TTL);
	}

	RedisAiQuotaLedger(
		StringRedisTemplate redisTemplate,
		Duration activeReservationTtl,
		AiCostWarningEventSink warningEventSink) {
		this(redisTemplate, warningEventSink, activeReservationTtl);
	}

	RedisAiQuotaLedger(
		StringRedisTemplate redisTemplate,
		AiCostWarningEventSink warningEventSink,
		Duration activeReservationTtl) {
		this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
		this.warningEventSink = Objects.requireNonNull(warningEventSink, "warningEventSink");
		this.activeReservationTtl = Objects.requireNonNull(activeReservationTtl, "activeReservationTtl");
		if (activeReservationTtl.isNegative() || activeReservationTtl.isZero()) {
			throw new IllegalArgumentException("activeReservationTtl must be positive");
		}
		reconcilePendingCompletions();
	}

	@Override
	public AiQuotaReservation reserve(String quotaSubject, Instant now) {
		return reserve(quotaSubject, now, BigDecimal.ZERO);
	}

	@Override
	public AiQuotaReservation reserve(String quotaSubject, Instant now, BigDecimal estimatedCostUsd) {
		Objects.requireNonNull(quotaSubject, "quotaSubject");
		Objects.requireNonNull(now, "now");
		BigDecimal reservationCostUsd = normalizeCost(estimatedCostUsd);
		ZonedDateTime kst = now.atZone(KST);
		YearMonth quotaMonth = YearMonth.from(kst);
		String subjectHash = subjectHash(quotaSubject);
		String token = UUID.randomUUID().toString();
		try {
			List<?> result = redisTemplate.execute(RESERVE_SCRIPT,
				List.of(dayKey(subjectHash, kst), monthCountKey(subjectHash, quotaMonth), activeKey(subjectHash),
					costKey(quotaMonth), warningKey(quotaMonth), reservationKey(token)),
				Long.toString(now.toEpochMilli()), Integer.toString(DAILY_LIMIT), Integer.toString(MONTHLY_LIMIT),
				Long.toString(toCents(reservationCostUsd)), Long.toString(HARD_CAP_CENTS),
				Long.toString(dayTtlMillis(kst)),
				Long.toString(monthTtlMillis(kst)), Long.toString(now.plus(activeReservationTtl).toEpochMilli()), token,
				Long.toString(activeReservationTtl.toMillis()), quotaMonth.toString(),
				Long.toString(WARNING_THRESHOLD_CENTS), subjectHash);
			Decision decision = Decision.from(result);
			if (decision.status == AiQuotaReservationStatus.ACQUIRED) {
				publishWarningIfNeeded(decision.warning, quotaMonth, decision.costUsd);
				return AiQuotaReservation.acquired(quotaSubject, quotaMonth, token, reservationCostUsd);
			}
			return AiQuotaReservation.rejected(decision.status);
		} catch (RuntimeException exception) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.UNAVAILABLE);
		}
	}

	@Override
	public AiQuotaCompletionStatus complete(AiQuotaReservation reservation, BigDecimal costUsd) {
		if (reservation == null || reservation.status() != AiQuotaReservationStatus.ACQUIRED
			|| reservation.reservationToken().isBlank() || reservation.quotaMonth() == null) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		BigDecimal actualCostUsd = normalizeCost(costUsd);
		return complete(reservation.reservationToken(), reservation.quotaMonth(),
			subjectHash(reservation.quotaSubject()),
			actualCostUsd);
	}

	private AiQuotaCompletionStatus complete(
		String reservationToken, YearMonth quotaMonth, String subjectHash, BigDecimal actualCostUsd) {
		try {
			List<?> result = redisTemplate.execute(COMPLETE_SCRIPT,
				List.of(reservationKey(reservationToken), activeKey(subjectHash), costKey(quotaMonth),
					warningKey(quotaMonth),
					pendingCompletionKey()),
				Long.toString(toCents(actualCostUsd)), reservationToken,
				Long.toString(WARNING_THRESHOLD_CENTS),
				Long.toString(RECORD_RETENTION.toMillis()));
			CompletionDecision decision = CompletionDecision.from(result);
			if (decision.completed) {
				publishWarningIfNeeded(decision.warning, quotaMonth, decision.costUsd);
				return AiQuotaCompletionStatus.COMPLETED;
			}
			return decision.notAcquired ? AiQuotaCompletionStatus.NOT_ACQUIRED : AiQuotaCompletionStatus.UNAVAILABLE;
		} catch (RuntimeException exception) {
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}
	}

	@Override
	public void scheduleCompletionRetry(AiQuotaReservation reservation, BigDecimal costUsd) {
		if (reservation == null || reservation.status() != AiQuotaReservationStatus.ACQUIRED
			|| reservation.reservationToken().isBlank() || reservation.quotaMonth() == null) {
			return;
		}
		BigDecimal actualCostUsd = normalizeCost(costUsd);
		String subjectHash = subjectHash(reservation.quotaSubject());
		persistPendingCompletion(reservation.reservationToken(), actualCostUsd);
		scheduleCompletionRetry(reservation.reservationToken(), reservation.quotaMonth(), subjectHash, actualCostUsd);
	}

	private void persistPendingCompletion(String reservationToken, BigDecimal actualCostUsd) {
		try {
			redisTemplate.execute(SCHEDULE_COMPLETION_SCRIPT,
				List.of(reservationKey(reservationToken), pendingCompletionKey()),
				Long.toString(toCents(actualCostUsd)), reservationToken);
		} catch (RuntimeException exception) {
			// 현재 프로세스 재시도는 유지하고, Redis 복구 뒤에는 같은 token으로만 completion 한다.
		}
	}

	private void scheduleCompletionRetry(
		String reservationToken, YearMonth quotaMonth, String subjectHash, BigDecimal actualCostUsd) {
		if (completionRetryExecutor.isShutdown()) {
			return;
		}
		completionRetryExecutor.schedule(() -> {
			if (complete(reservationToken, quotaMonth, subjectHash,
				actualCostUsd) == AiQuotaCompletionStatus.UNAVAILABLE) {
				scheduleCompletionRetry(reservationToken, quotaMonth, subjectHash, actualCostUsd);
			}
		}, COMPLETION_RETRY_DELAY_SECONDS, TimeUnit.SECONDS);
	}

	@PreDestroy
	void shutdownCompletionRetryExecutor() {
		completionRetryExecutor.shutdown();
		try {
			if (!completionRetryExecutor.awaitTermination(250, TimeUnit.MILLISECONDS)) {
				completionRetryExecutor.shutdownNow();
			}
		} catch (InterruptedException exception) {
			completionRetryExecutor.shutdownNow();
			Thread.currentThread().interrupt();
		}
	}

	private static final Duration RECORD_RETENTION = Duration.ofDays(2);

	private static StringRedisTemplate redisTemplate(RedisConnectionFactory connectionFactory) {
		StringRedisTemplate template = new StringRedisTemplate(
			Objects.requireNonNull(connectionFactory, "connectionFactory"));
		template.afterPropertiesSet();
		return template;
	}

	private void publishWarningIfNeeded(boolean warning, YearMonth quotaMonth, BigDecimal costUsd) {
		if (warning) {
			try {
				warningEventSink.record(new AssistantCostWarningEvent(quotaMonth, costUsd, WARNING_THRESHOLD_USD));
			} catch (RuntimeException ignored) {
				// Redis Lua 상태 전이는 경고 전달 장애와 독립적으로 완료한다.
			}
		}
	}

	private String dayKey(String subjectHash, ZonedDateTime kst) {
		return KEY_PREFIX + ":{v1}:user:" + subjectHash + ":day:" + kst.toLocalDate();
	}

	private String monthCountKey(String subjectHash, YearMonth quotaMonth) {
		return KEY_PREFIX + ":{v1}:user:" + subjectHash + ":month:" + quotaMonth + ":count";
	}

	private String activeKey(String subjectHash) {
		return KEY_PREFIX + ":{v1}:user:" + subjectHash + ":active";
	}

	private String costKey(YearMonth quotaMonth) {
		return KEY_PREFIX + ":{v1}:cost:" + quotaMonth;
	}

	private String warningKey(YearMonth quotaMonth) {
		return KEY_PREFIX + ":{v1}:cost:" + quotaMonth + ":warned";
	}

	private String reservationKey(String token) {
		return KEY_PREFIX + ":{v1}:reservation:" + token;
	}

	private String pendingCompletionKey() {
		return KEY_PREFIX + ":{v1}:pending-completion";
	}

	private void reconcilePendingCompletions() {
		try {
			Set<String> pendingTokens = redisTemplate.opsForZSet().range(pendingCompletionKey(), 0, -1);
			if (pendingTokens == null) {
				return;
			}
			for (String reservationToken : pendingTokens) {
				reconcilePendingCompletion(reservationToken);
			}
		} catch (RuntimeException ignored) {
			// Redis fail-closed는 호출 시 reserve/complete가 판정하며, 기동 중 복구 스캔은 재시도한다.
		}
	}

	private void reconcilePendingCompletion(String reservationToken) {
		Map<Object, Object> state = redisTemplate.opsForHash().entries(reservationKey(reservationToken));
		Object pendingCostCents = state.get("pendingCompletionCostCents");
		if (!"PENDING".equals(state.get("state")) || pendingCostCents == null
			|| state.get("quotaMonth") == null || state.get("subjectHash") == null) {
			return;
		}
		try {
			scheduleCompletionRetry(reservationToken, YearMonth.parse(valueOf(state.get("quotaMonth"))),
				valueOf(state.get("subjectHash")), fromCents(pendingCostCents));
		} catch (IllegalArgumentException exception) {
			// 손상된 보류 기록은 provider 재호출 없이 retention 만료로 정리한다.
		}
	}

	private long dayTtlMillis(ZonedDateTime kst) {
		return Duration.between(kst, kst.toLocalDate().plusDays(1).atStartOfDay(KST)).toMillis();
	}

	private long monthTtlMillis(ZonedDateTime kst) {
		return Duration.between(kst, kst.toLocalDate().withDayOfMonth(1).plusMonths(1).atStartOfDay(KST))
			.plus(RECORD_RETENTION).toMillis();
	}

	private String subjectHash(String quotaSubject) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(quotaSubject.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable", exception);
		}
	}

	private BigDecimal normalizeCost(BigDecimal costUsd) {
		if (costUsd == null || costUsd.signum() < 0) {
			throw new IllegalArgumentException("costUsd must not be negative");
		}
		return costUsd.signum() == 0 ? BigDecimal.ZERO : costUsd.setScale(2, RoundingMode.CEILING);
	}

	private long toCents(BigDecimal costUsd) {
		return costUsd.movePointRight(2).longValueExact();
	}

	private static BigDecimal fromCents(Object value) {
		return BigDecimal.valueOf(Long.parseLong(valueOf(value)), 2);
	}

	private static final class DaemonThreadFactory implements ThreadFactory {

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "ai-quota-completion-retry");
			thread.setDaemon(true);
			return thread;
		}
	}

	private static String valueOf(Object value) {
		if (value instanceof byte[] bytes) {
			return new String(bytes, StandardCharsets.UTF_8);
		}
		return String.valueOf(value);
	}

	private record Decision(AiQuotaReservationStatus status, boolean warning, BigDecimal costUsd) {

		private static Decision from(List<?> result) {
			if (result == null || result.size() != 3 || !(result.getFirst() instanceof Number code)) {
				return new Decision(AiQuotaReservationStatus.UNAVAILABLE, false, BigDecimal.ZERO);
			}
			AiQuotaReservationStatus status = switch (code.intValue()) {
				case 1 -> AiQuotaReservationStatus.ACQUIRED;
				case 2 -> AiQuotaReservationStatus.USER_LIMIT_REACHED;
				case 3 -> AiQuotaReservationStatus.CONCURRENT_LIMIT_REACHED;
				case 4 -> AiQuotaReservationStatus.COST_CAP_REACHED;
				default -> AiQuotaReservationStatus.UNAVAILABLE;
			};
			boolean warning = result.get(1) instanceof Number number && number.intValue() == 1;
			try {
				return new Decision(status, warning, fromCents(result.get(2)));
			} catch (NumberFormatException exception) {
				return new Decision(AiQuotaReservationStatus.UNAVAILABLE, false, BigDecimal.ZERO);
			}
		}
	}

	private record CompletionDecision(boolean completed, boolean notAcquired, boolean warning, BigDecimal costUsd) {

		private static CompletionDecision from(List<?> result) {
			if (result == null || result.size() != 3 || !(result.getFirst() instanceof Number code)) {
				return new CompletionDecision(false, false, false, BigDecimal.ZERO);
			}
			try {
				return new CompletionDecision(code.intValue() == 1, code.intValue() == 2,
					result.get(1) instanceof Number number && number.intValue() == 1,
					BigDecimal.valueOf(Long.parseLong(valueOf(result.get(2))), 2));
			} catch (NumberFormatException exception) {
				return new CompletionDecision(false, false, false, BigDecimal.ZERO);
			}
		}
	}
}
