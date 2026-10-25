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
	private static final long WARNING_THRESHOLD_MICROS = 4_000_000;
	private static final long HARD_CAP_MICROS = 5_000_000;
	private static final long MICROS_PER_CENT = 10_000;
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
			local compatibilityCents = tonumber(redis.call('GET', KEYS[4]) or '0')
			local compatibilityMirrorCents = tonumber(redis.call('GET', KEYS[8]) or '0')
			if compatibilityMirrorCents > compatibilityCents then return {0, 0, '0'} end
			local legacyCostMicros = (compatibilityCents - compatibilityMirrorCents) * tonumber(ARGV[15])
			local currentCostMicros = tonumber(redis.call('GET', KEYS[7]) or '0')
			local currentTotalMicros = legacyCostMicros + currentCostMicros
			local nextCostMicros = currentTotalMicros + tonumber(ARGV[4])
			if nextCostMicros > tonumber(ARGV[5]) then return {4, 0, tostring(currentTotalMicros)} end
			if dayCount == 0 then redis.call('PSETEX', KEYS[1], ARGV[6], '1') else redis.call('INCR', KEYS[1]) end
			if monthCount == 0 then redis.call('PSETEX', KEYS[2], ARGV[7], '1') else redis.call('INCR', KEYS[2]) end
			local mirrorIncrementCents = tonumber(ARGV[14])
			local nextCompatibilityCents = compatibilityCents + mirrorIncrementCents
			local nextCompatibilityMirrorCents = compatibilityMirrorCents + mirrorIncrementCents
			if redis.call('EXISTS', KEYS[4]) == 0 then
			  redis.call('PSETEX', KEYS[4], ARGV[7], tostring(nextCompatibilityCents))
			else
			  redis.call('SET', KEYS[4], tostring(nextCompatibilityCents), 'KEEPTTL')
			end
			if redis.call('EXISTS', KEYS[8]) == 0 then
			  redis.call('PSETEX', KEYS[8], ARGV[7], tostring(nextCompatibilityMirrorCents))
			else
			  redis.call('SET', KEYS[8], tostring(nextCompatibilityMirrorCents), 'KEEPTTL')
			end
			if redis.call('EXISTS', KEYS[7]) == 0 then
			  redis.call('PSETEX', KEYS[7], ARGV[7], ARGV[4])
			else
			  redis.call('INCRBY', KEYS[7], ARGV[4])
			end
			redis.call('ZADD', KEYS[3], ARGV[8], ARGV[9])
			redis.call('PEXPIRE', KEYS[3], ARGV[10])
			redis.call('HSET', KEYS[6], 'state', 'PENDING', 'reservedCostMicros', ARGV[4],
			  'compatibilityMirrorCents', ARGV[14], 'quotaMonth', ARGV[11], 'quotaDay', ARGV[16],
			  'subjectHash', ARGV[13])
			redis.call('PEXPIRE', KEYS[6], ARGV[7])
			local warned = 0
			if nextCostMicros >= tonumber(ARGV[12]) and redis.call('SET', KEYS[5], '1', 'NX', 'PX', ARGV[7]) then warned = 1 end
			return {1, warned, tostring(nextCostMicros)}
			""",
		List.class);
	private static final DefaultRedisScript<List> COMPLETE_SCRIPT = new DefaultRedisScript<>(
		"""
			if redis.call('EXISTS', KEYS[1]) == 0 then return {2, 0, '0'} end
			if redis.call('HGET', KEYS[1], 'state') == 'RELEASED' then return {2, 0, '0'} end
			if redis.call('HGET', KEYS[1], 'state') == 'COMPLETED' then
			  redis.call('ZREM', KEYS[5], ARGV[2])
			  local oldCents = tonumber(redis.call('GET', KEYS[6]) or '0')
			  local oldMirror = tonumber(redis.call('GET', KEYS[7]) or '0')
			  local oldMicros = tonumber(redis.call('GET', KEYS[3]) or '0')
			  return {1, 0, tostring((oldCents - oldMirror) * 10000 + oldMicros)}
			end
			local costTtl = redis.call('PTTL', KEYS[1])
			if costTtl <= 0 then return {0, 0, '0'} end
			local compatibilityCents = tonumber(redis.call('GET', KEYS[6]) or '0')
			local compatibilityMirrorCents = tonumber(redis.call('GET', KEYS[7]) or '0')
			if compatibilityMirrorCents > compatibilityCents then return {0, 0, '0'} end
			local legacyCostMicros = (compatibilityCents - compatibilityMirrorCents) * 10000
			local currentCostMicros = tonumber(redis.call('GET', KEYS[3]) or '0')
			local nextCostMicros = currentCostMicros
			local reservedCostMicros = redis.call('HGET', KEYS[1], 'reservedCostMicros')
			if reservedCostMicros then
			  nextCostMicros = currentCostMicros - tonumber(reservedCostMicros) + tonumber(ARGV[1])
			  if nextCostMicros < 0 then return {0, 0, '0'} end
			  local originalMirrorCents = tonumber(redis.call('HGET', KEYS[1], 'compatibilityMirrorCents') or '0')
			  local targetMirrorCents = math.ceil(tonumber(ARGV[1]) / 10000)
			  local mirrorIncrementCents = math.max(0, targetMirrorCents - originalMirrorCents)
			  if mirrorIncrementCents > 0 then
			    local nextCompatibilityCents = compatibilityCents + mirrorIncrementCents
			    local nextCompatibilityMirrorCents = compatibilityMirrorCents + mirrorIncrementCents
			    if redis.call('EXISTS', KEYS[6]) == 0 then
			      redis.call('PSETEX', KEYS[6], costTtl, tostring(nextCompatibilityCents))
			    else
			      redis.call('SET', KEYS[6], tostring(nextCompatibilityCents), 'KEEPTTL')
			    end
			    if redis.call('EXISTS', KEYS[7]) == 0 then
			      redis.call('PSETEX', KEYS[7], costTtl, tostring(nextCompatibilityMirrorCents))
			    else
			      redis.call('SET', KEYS[7], tostring(nextCompatibilityMirrorCents), 'KEEPTTL')
			    end
			    compatibilityCents = nextCompatibilityCents
			    compatibilityMirrorCents = nextCompatibilityMirrorCents
			  end
			  if redis.call('EXISTS', KEYS[3]) == 0 then
			    redis.call('PSETEX', KEYS[3], costTtl, tostring(nextCostMicros))
			  else
			    redis.call('SET', KEYS[3], tostring(nextCostMicros), 'KEEPTTL')
			  end
			end
			local nextTotalMicros = legacyCostMicros + nextCostMicros
			local warned = 0
			if nextTotalMicros >= tonumber(ARGV[3])
			  and redis.call('SET', KEYS[4], '1', 'NX', 'PX', costTtl) then warned = 1 end
			redis.call('HSET', KEYS[1], 'state', 'COMPLETED')
			redis.call('HDEL', KEYS[1], 'pendingCompletionCostCents', 'pendingCompletionCostMicros')
			redis.call('ZREM', KEYS[2], ARGV[2])
			redis.call('ZREM', KEYS[5], ARGV[2])
			return {1, warned, tostring(nextTotalMicros)}
			""",
		List.class);
	private static final DefaultRedisScript<List> RELEASE_SCRIPT = new DefaultRedisScript<>(
		"""
			if redis.call('EXISTS', KEYS[1]) == 0 then return {2, 0, '0'} end
			local state = redis.call('HGET', KEYS[1], 'state')
			if state == 'RELEASED' then return {1, 0, '0'} end
			if state ~= 'PENDING' then return {2, 0, '0'} end
			local costTtl = redis.call('PTTL', KEYS[1])
			if costTtl <= 0 then return {0, 0, '0'} end
			local reservedMicros = tonumber(redis.call('HGET', KEYS[1], 'reservedCostMicros') or '0')
			local reservationMirrorCents = tonumber(redis.call('HGET', KEYS[1], 'compatibilityMirrorCents') or '0')
			local currentMicros = tonumber(redis.call('GET', KEYS[5]) or '0')
			local currentCents = tonumber(redis.call('GET', KEYS[6]) or '0')
			local currentMirrorCents = tonumber(redis.call('GET', KEYS[7]) or '0')
			if currentMicros < reservedMicros or currentMirrorCents > currentCents
			  or reservationMirrorCents > currentMirrorCents then return {0, 0, '0'} end
			local nextMicros = currentMicros - reservedMicros
			local nextCents = currentCents - reservationMirrorCents
			local nextMirrorCents = currentMirrorCents - reservationMirrorCents
			if redis.call('EXISTS', KEYS[5]) == 0 then
			  if nextMicros > 0 then redis.call('PSETEX', KEYS[5], costTtl, tostring(nextMicros)) end
			else
			  redis.call('SET', KEYS[5], tostring(nextMicros), 'KEEPTTL')
			end
			if redis.call('EXISTS', KEYS[6]) == 0 then
			  if nextCents > 0 then redis.call('PSETEX', KEYS[6], costTtl, tostring(nextCents)) end
			else
			  redis.call('SET', KEYS[6], tostring(nextCents), 'KEEPTTL')
			end
			if redis.call('EXISTS', KEYS[7]) == 0 then
			  if nextMirrorCents > 0 then redis.call('PSETEX', KEYS[7], costTtl, tostring(nextMirrorCents)) end
			else
			  redis.call('SET', KEYS[7], tostring(nextMirrorCents), 'KEEPTTL')
			end
			local dayCount = tonumber(redis.call('GET', KEYS[2]) or '0')
			if dayCount > 1 then redis.call('DECR', KEYS[2]) elseif dayCount == 1 then redis.call('DEL', KEYS[2]) end
			local monthCount = tonumber(redis.call('GET', KEYS[3]) or '0')
			if monthCount > 1 then redis.call('DECR', KEYS[3]) elseif monthCount == 1 then redis.call('DEL', KEYS[3]) end
			redis.call('ZREM', KEYS[4], ARGV[1])
			redis.call('ZREM', KEYS[8], ARGV[1])
			redis.call('HSET', KEYS[1], 'state', 'RELEASED')
			redis.call('HDEL', KEYS[1], 'pendingCompletionCostCents', 'pendingCompletionCostMicros')
			local legacyCostMicros = (nextCents - nextMirrorCents) * 10000
			return {1, 0, tostring(legacyCostMicros + nextMicros)}
			""",
		List.class);
	private static final DefaultRedisScript<Long> SCHEDULE_COMPLETION_SCRIPT = new DefaultRedisScript<>(
		"""
			if redis.call('EXISTS', KEYS[1]) == 0 or redis.call('HGET', KEYS[1], 'state') ~= 'PENDING' then return 0 end
			local reservationTtl = redis.call('PTTL', KEYS[1])
			if reservationTtl <= 0 then return 0 end
			redis.call('HSET', KEYS[1], 'pendingCompletionCostMicros', ARGV[1],
			  'pendingCompletionCostCents', ARGV[2])
			redis.call('ZADD', KEYS[2], 0, ARGV[3])
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
					costKey(quotaMonth), warningKey(quotaMonth), reservationKey(token), microCostKey(quotaMonth),
					compatibilityMirrorKey(quotaMonth)),
				Long.toString(now.toEpochMilli()), Integer.toString(DAILY_LIMIT), Integer.toString(MONTHLY_LIMIT),
				Long.toString(toMicros(reservationCostUsd)), Long.toString(HARD_CAP_MICROS),
				Long.toString(dayTtlMillis(kst)),
				Long.toString(monthTtlMillis(kst)), Long.toString(now.plus(activeReservationTtl).toEpochMilli()), token,
				Long.toString(activeReservationTtl.toMillis()), quotaMonth.toString(),
				Long.toString(WARNING_THRESHOLD_MICROS), subjectHash,
				Long.toString(toCompatibilityCents(reservationCostUsd)), Long.toString(MICROS_PER_CENT),
				kst.toLocalDate().toString());
			Decision decision = Decision.from(result);
			if (decision.status == AiQuotaReservationStatus.ACQUIRED) {
				publishWarningIfNeeded(decision.warning, quotaMonth, decision.costUsd);
				return AiQuotaReservation.acquired(quotaSubject, quotaMonth, kst.toLocalDate(), token,
					reservationCostUsd);
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
		BigDecimal settlementCostUsd = normalizeCost(costUsd);
		return complete(reservation.reservationToken(), reservation.quotaMonth(),
			subjectHash(reservation.quotaSubject()),
			settlementCostUsd);
	}

	@Override
	public AiQuotaCompletionStatus release(AiQuotaReservation reservation) {
		if (reservation == null || reservation.status() != AiQuotaReservationStatus.ACQUIRED
			|| reservation.reservationToken().isBlank() || reservation.quotaMonth() == null
			|| reservation.quotaDay() == null) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		String subjectHash = subjectHash(reservation.quotaSubject());
		try {
			List<?> result = redisTemplate.execute(RELEASE_SCRIPT,
				List.of(reservationKey(reservation.reservationToken()),
					dayKey(subjectHash, reservation.quotaDay().atStartOfDay(KST)),
					monthCountKey(subjectHash, reservation.quotaMonth()), activeKey(subjectHash),
					microCostKey(reservation.quotaMonth()), costKey(reservation.quotaMonth()),
					compatibilityMirrorKey(reservation.quotaMonth()), pendingCompletionKey()),
				reservation.reservationToken());
			CompletionDecision decision = CompletionDecision.from(result);
			if (decision.completed) {
				return AiQuotaCompletionStatus.COMPLETED;
			}
			return decision.notAcquired ? AiQuotaCompletionStatus.NOT_ACQUIRED : AiQuotaCompletionStatus.UNAVAILABLE;
		} catch (RuntimeException exception) {
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}
	}

	private AiQuotaCompletionStatus complete(
		String reservationToken, YearMonth quotaMonth, String subjectHash, BigDecimal settlementCostUsd) {
		try {
			List<?> result = redisTemplate.execute(COMPLETE_SCRIPT,
				List.of(reservationKey(reservationToken), activeKey(subjectHash), microCostKey(quotaMonth),
					warningKey(quotaMonth), pendingCompletionKey(), costKey(quotaMonth),
					compatibilityMirrorKey(quotaMonth)),
				Long.toString(toMicros(settlementCostUsd)), reservationToken,
				Long.toString(WARNING_THRESHOLD_MICROS));
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
		BigDecimal settlementCostUsd = normalizeCost(costUsd);
		String subjectHash = subjectHash(reservation.quotaSubject());
		persistPendingCompletion(reservation.reservationToken(), settlementCostUsd);
		scheduleCompletionRetry(reservation.reservationToken(), reservation.quotaMonth(), subjectHash,
			settlementCostUsd);
	}

	private void persistPendingCompletion(String reservationToken, BigDecimal settlementCostUsd) {
		try {
			redisTemplate.execute(SCHEDULE_COMPLETION_SCRIPT,
				List.of(reservationKey(reservationToken), pendingCompletionKey()),
				Long.toString(toMicros(settlementCostUsd)),
				Long.toString(toCompatibilityCents(settlementCostUsd)), reservationToken);
		} catch (RuntimeException exception) {
			// 현재 프로세스 재시도는 유지하고, Redis 복구 뒤에는 같은 token으로만 completion 한다.
		}
	}

	private void scheduleCompletionRetry(
		String reservationToken, YearMonth quotaMonth, String subjectHash, BigDecimal settlementCostUsd) {
		if (completionRetryExecutor.isShutdown()) {
			return;
		}
		completionRetryExecutor.schedule(() -> {
			if (complete(reservationToken, quotaMonth, subjectHash,
				settlementCostUsd) == AiQuotaCompletionStatus.UNAVAILABLE) {
				scheduleCompletionRetry(reservationToken, quotaMonth, subjectHash, settlementCostUsd);
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

	private String microCostKey(YearMonth quotaMonth) {
		return KEY_PREFIX + ":{v1}:cost-micros:" + quotaMonth;
	}

	private String compatibilityMirrorKey(YearMonth quotaMonth) {
		return KEY_PREFIX + ":{v1}:cost-compat-shadow:" + quotaMonth;
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
		Object pendingCostMicros = state.get("pendingCompletionCostMicros");
		Object pendingCostCents = state.get("pendingCompletionCostCents");
		if (!"PENDING".equals(state.get("state")) || (pendingCostMicros == null && pendingCostCents == null)
			|| state.get("quotaMonth") == null || state.get("subjectHash") == null) {
			return;
		}
		try {
			BigDecimal pendingCost = pendingCostMicros == null
				? fromCents(pendingCostCents)
				: fromMicros(pendingCostMicros);
			scheduleCompletionRetry(reservationToken, YearMonth.parse(valueOf(state.get("quotaMonth"))),
				valueOf(state.get("subjectHash")), pendingCost);
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
		return AiTokenCostCalculator.normalize(costUsd);
	}

	private long toMicros(BigDecimal costUsd) {
		return costUsd.movePointRight(AiTokenCostCalculator.USD_SCALE).longValueExact();
	}

	private long toCompatibilityCents(BigDecimal costUsd) {
		return costUsd.movePointRight(2).setScale(0, RoundingMode.CEILING).longValueExact();
	}

	private static BigDecimal fromCents(Object value) {
		return BigDecimal.valueOf(Long.parseLong(valueOf(value)), 2);
	}

	private static BigDecimal fromMicros(Object value) {
		return BigDecimal.valueOf(Long.parseLong(valueOf(value)), AiTokenCostCalculator.USD_SCALE);
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
				return new Decision(status, warning, fromMicros(result.get(2)));
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
					BigDecimal.valueOf(Long.parseLong(valueOf(result.get(2))), AiTokenCostCalculator.USD_SCALE));
			} catch (NumberFormatException exception) {
				return new CompletionDecision(false, false, false, BigDecimal.ZERO);
			}
		}
	}
}
