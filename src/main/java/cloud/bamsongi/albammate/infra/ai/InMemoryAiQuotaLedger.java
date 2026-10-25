package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import cloud.bamsongi.albammate.assistant.contract.AssistantCostWarningEvent;

/** 테스트·fake 경로에서 Redis 정책과 같은 quota 상태 전이를 결정적으로 재현한다. */
final class InMemoryAiQuotaLedger implements AiQuotaLedger {

	private static final ZoneId KST = ZoneId.of("Asia/Seoul");
	private static final BigDecimal WARNING_THRESHOLD_USD = new BigDecimal("4.00");
	private static final BigDecimal HARD_CAP_USD = new BigDecimal("5.000000");
	private static final int DAILY_LIMIT = 5;
	private static final int MONTHLY_LIMIT = 150;

	private final AiCostWarningEventSink warningEventSink;
	private final Map<LocalDate, Map<String, Integer>> dailyCounts = new HashMap<>();
	private final Map<YearMonth, Map<String, Integer>> monthlyCounts = new HashMap<>();
	private final Map<String, ReservationState> reservations = new HashMap<>();
	private final Map<String, String> activeReservations = new HashMap<>();
	private final Map<YearMonth, BigDecimal> monthlyCosts = new HashMap<>();
	private final Set<YearMonth> warnedMonths = new HashSet<>();

	InMemoryAiQuotaLedger() {
		this(event -> {});
	}

	InMemoryAiQuotaLedger(AiCostWarningEventSink warningEventSink) {
		this.warningEventSink = Objects.requireNonNull(warningEventSink, "warningEventSink");
	}

	@Override
	public AiQuotaReservation reserve(String quotaSubject, Instant now) {
		return reserve(quotaSubject, now, BigDecimal.ZERO);
	}

	@Override
	public synchronized AiQuotaReservation reserve(String quotaSubject, Instant now, BigDecimal estimatedCostUsd) {
		Objects.requireNonNull(quotaSubject, "quotaSubject");
		Objects.requireNonNull(now, "now");
		BigDecimal reservationCostUsd = normalizeCost(estimatedCostUsd);
		ZonedDateTime kst = now.atZone(KST);
		LocalDate quotaDay = kst.toLocalDate();
		YearMonth quotaMonth = YearMonth.from(kst);
		activeReservations.entrySet().removeIf(entry -> {
			ReservationState state = reservations.get(entry.getValue());
			return state == null || !state.expiresAt.isAfter(now);
		});
		if (activeReservations.containsKey(quotaSubject)) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.CONCURRENT_LIMIT_REACHED);
		}
		if (count(dailyCounts, quotaDay, quotaSubject) >= DAILY_LIMIT
			|| count(monthlyCounts, quotaMonth, quotaSubject) >= MONTHLY_LIMIT) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.USER_LIMIT_REACHED);
		}
		BigDecimal nextCost = monthlyCosts.getOrDefault(quotaMonth, BigDecimal.ZERO).add(reservationCostUsd);
		if (nextCost.compareTo(HARD_CAP_USD) > 0) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.COST_CAP_REACHED);
		}
		increment(dailyCounts, quotaDay, quotaSubject);
		increment(monthlyCounts, quotaMonth, quotaSubject);
		String token = UUID.randomUUID().toString();
		activeReservations.put(quotaSubject, token);
		reservations.put(token, new ReservationState(
			quotaSubject, quotaMonth, quotaDay, now.plus(RedisAiQuotaLedger.ACTIVE_RESERVATION_TTL),
			reservationCostUsd));
		monthlyCosts.put(quotaMonth, nextCost);
		publishWarningIfFirstReached(quotaMonth, nextCost);
		return AiQuotaReservation.acquired(quotaSubject, quotaMonth, quotaDay, token, reservationCostUsd);
	}

	@Override
	public synchronized AiQuotaCompletionStatus complete(AiQuotaReservation reservation, BigDecimal costUsd) {
		if (reservation == null || reservation.status() != AiQuotaReservationStatus.ACQUIRED
			|| reservation.reservationToken().isBlank()) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		ReservationState state = reservations.get(reservation.reservationToken());
		if (state == null) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		if (state.released) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		if (state.completed) {
			return AiQuotaCompletionStatus.COMPLETED;
		}
		BigDecimal settledCostUsd = normalizeCost(costUsd);
		BigDecimal currentCostUsd = monthlyCosts.getOrDefault(state.quotaMonth, BigDecimal.ZERO);
		BigDecimal nextCostUsd = currentCostUsd.subtract(state.reservedCostUsd).add(settledCostUsd);
		if (nextCostUsd.signum() < 0) {
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}
		state.completed = true;
		activeReservations.remove(state.quotaSubject, reservation.reservationToken());
		monthlyCosts.put(state.quotaMonth, nextCostUsd);
		publishWarningIfFirstReached(state.quotaMonth, nextCostUsd);
		return AiQuotaCompletionStatus.COMPLETED;
	}

	@Override
	public synchronized AiQuotaCompletionStatus release(AiQuotaReservation reservation) {
		if (reservation == null || reservation.status() != AiQuotaReservationStatus.ACQUIRED
			|| reservation.reservationToken().isBlank()) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		ReservationState state = reservations.get(reservation.reservationToken());
		if (state == null) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		if (state.released) {
			return AiQuotaCompletionStatus.COMPLETED;
		}
		if (state.completed) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		BigDecimal currentCostUsd = monthlyCosts.getOrDefault(state.quotaMonth, BigDecimal.ZERO);
		BigDecimal nextCostUsd = currentCostUsd.subtract(state.reservedCostUsd);
		if (nextCostUsd.signum() < 0) {
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}
		state.released = true;
		activeReservations.remove(state.quotaSubject, reservation.reservationToken());
		decrement(dailyCounts, state.quotaDay, state.quotaSubject);
		decrement(monthlyCounts, state.quotaMonth, state.quotaSubject);
		monthlyCosts.put(state.quotaMonth, nextCostUsd);
		return AiQuotaCompletionStatus.COMPLETED;
	}

	private void publishWarningIfFirstReached(YearMonth quotaMonth, BigDecimal costUsd) {
		if (costUsd.compareTo(WARNING_THRESHOLD_USD) >= 0 && warnedMonths.add(quotaMonth)) {
			try {
				warningEventSink.record(new AssistantCostWarningEvent(quotaMonth, costUsd, WARNING_THRESHOLD_USD));
			} catch (RuntimeException ignored) {
				// quota 상태 전이는 경고 전달 장애와 독립적으로 완료한다.
			}
		}
	}

	private <K> int count(Map<K, Map<String, Integer>> counts, K period, String quotaSubject) {
		return counts.getOrDefault(period, Map.of()).getOrDefault(quotaSubject, 0);
	}

	private <K> void increment(Map<K, Map<String, Integer>> counts, K period, String quotaSubject) {
		counts.computeIfAbsent(period, ignored -> new HashMap<>()).merge(quotaSubject, 1, Integer::sum);
	}

	private <K> void decrement(Map<K, Map<String, Integer>> counts, K period, String quotaSubject) {
		Map<String, Integer> periodCounts = counts.get(period);
		if (periodCounts == null) {
			return;
		}
		int currentCount = periodCounts.getOrDefault(quotaSubject, 0);
		if (currentCount <= 1) {
			periodCounts.remove(quotaSubject);
		} else {
			periodCounts.put(quotaSubject, currentCount - 1);
		}
		if (periodCounts.isEmpty()) {
			counts.remove(period);
		}
	}

	private BigDecimal normalizeCost(BigDecimal costUsd) {
		if (costUsd == null || costUsd.signum() < 0) {
			throw new IllegalArgumentException("costUsd must not be negative");
		}
		return AiTokenCostCalculator.normalize(costUsd);
	}

	private static final class ReservationState {

		private final String quotaSubject;
		private final YearMonth quotaMonth;
		private final LocalDate quotaDay;
		private final Instant expiresAt;
		private final BigDecimal reservedCostUsd;
		private boolean completed;
		private boolean released;

		private ReservationState(
			String quotaSubject, YearMonth quotaMonth, LocalDate quotaDay, Instant expiresAt,
			BigDecimal reservedCostUsd) {
			this.quotaSubject = quotaSubject;
			this.quotaMonth = quotaMonth;
			this.quotaDay = quotaDay;
			this.expiresAt = expiresAt;
			this.reservedCostUsd = reservedCostUsd;
		}
	}
}
