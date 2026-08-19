package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** test와 기본 fake profile의 결정적 quota 구현이다. */
final class InMemoryAiQuotaLedger implements AiQuotaLedger {

	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
	private static final BigDecimal DEFAULT_RESERVATION_COST = new BigDecimal("0.10");
	private static final BigDecimal COST_CAP = new BigDecimal("5.00");
	private static final BigDecimal WARNING_CAP = new BigDecimal("4.00");

	private final Map<String, Integer> dailyCounts = new HashMap<>();
	private final Map<String, Integer> monthlyCounts = new HashMap<>();
	private final Set<String> activeSubjects = new HashSet<>();
	private final Map<YearMonth, BigDecimal> monthlyCosts = new HashMap<>();
	private final Set<YearMonth> warningMonths = new HashSet<>();
	private final Set<String> completedReservations = new HashSet<>();
	private YearMonth lastObservedMonth;

	@Override
	public synchronized AiQuotaReservation reserve(String quotaSubject, Instant now) {
		return reserve(quotaSubject, now, DEFAULT_RESERVATION_COST);
	}

	@Override
	public synchronized AiQuotaReservation reserve(
		String quotaSubject,
		Instant now,
		BigDecimal estimatedCostUsd) {
		if (estimatedCostUsd == null || estimatedCostUsd.signum() < 0) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.UNAVAILABLE);
		}
		LocalDate day = now.atZone(KOREA).toLocalDate();
		YearMonth month = YearMonth.from(now.atZone(KOREA));
		lastObservedMonth = month;
		BigDecimal reservationCost = estimatedCostUsd.setScale(2, RoundingMode.CEILING);
		String dailyKey = quotaSubject + ":" + day;
		String monthlyKey = quotaSubject + ":" + month;
		if (activeSubjects.contains(quotaSubject)) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.CONCURRENT_LIMIT_REACHED);
		}
		if (monthlyCost(month).add(reservationCost).compareTo(COST_CAP) > 0) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.COST_CAP_REACHED);
		}
		if (dailyCounts.getOrDefault(dailyKey, 0) >= 5 || monthlyCounts.getOrDefault(monthlyKey, 0) >= 150) {
			return AiQuotaReservation.rejected(AiQuotaReservationStatus.USER_LIMIT_REACHED);
		}
		dailyCounts.merge(dailyKey, 1, Integer::sum);
		monthlyCounts.merge(monthlyKey, 1, Integer::sum);
		activeSubjects.add(quotaSubject);
		monthlyCosts.put(month, monthlyCost(month).add(reservationCost));
		return AiQuotaReservation.acquired(quotaSubject, month, UUID.randomUUID().toString(), reservationCost);
	}

	@Override
	public synchronized AiQuotaCompletionStatus complete(AiQuotaReservation reservation, BigDecimal costUsd) {
		if (reservation.status() != AiQuotaReservationStatus.ACQUIRED) {
			return AiQuotaCompletionStatus.NOT_ACQUIRED;
		}
		if (!reservation.reservationToken().isBlank()
			&& completedReservations.contains(reservation.reservationToken())) {
			return AiQuotaCompletionStatus.COMPLETED;
		}
		if (costUsd == null || costUsd.signum() < 0) {
			return AiQuotaCompletionStatus.UNAVAILABLE;
		}
		activeSubjects.remove(reservation.quotaSubject());
		YearMonth month = reservation.quotaMonth();
		if (month == null) {
			month = lastObservedMonth;
		}
		lastObservedMonth = month;
		BigDecimal updatedCost = monthlyCost(month)
			.subtract(reservation.reservedCostUsd())
			.add(costUsd)
			.max(BigDecimal.ZERO)
			.setScale(2);
		monthlyCosts.put(month, updatedCost);
		if (updatedCost.compareTo(WARNING_CAP) >= 0) {
			warningMonths.add(month);
		}
		if (!reservation.reservationToken().isBlank()) {
			completedReservations.add(reservation.reservationToken());
		}
		return AiQuotaCompletionStatus.COMPLETED;
	}

	BigDecimal currentMonthCost() {
		return lastObservedMonth == null ? BigDecimal.ZERO.setScale(2) : monthlyCost(lastObservedMonth);
	}

	boolean warningRaised() {
		return lastObservedMonth != null && warningRaised(lastObservedMonth);
	}

	BigDecimal monthlyCost(YearMonth month) {
		return monthlyCosts.getOrDefault(month, BigDecimal.ZERO.setScale(2));
	}

	boolean warningRaised(YearMonth month) {
		return warningMonths.contains(month);
	}
}
