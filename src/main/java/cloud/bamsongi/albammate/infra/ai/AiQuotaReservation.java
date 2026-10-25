package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

record AiQuotaReservation(
	AiQuotaReservationStatus status,
	String quotaSubject,
	String reservationToken,
	YearMonth quotaMonth,
	LocalDate quotaDay,
	BigDecimal reservedCostUsd) {

	static AiQuotaReservation acquired(String quotaSubject) {
		return acquired(quotaSubject, null, null, "", BigDecimal.ZERO);
	}

	static AiQuotaReservation acquired(
		String quotaSubject,
		YearMonth quotaMonth,
		String reservationToken,
		BigDecimal reservedCostUsd) {
		return acquired(quotaSubject, quotaMonth, null, reservationToken, reservedCostUsd);
	}

	static AiQuotaReservation acquired(
		String quotaSubject,
		YearMonth quotaMonth,
		LocalDate quotaDay,
		String reservationToken,
		BigDecimal reservedCostUsd) {
		return new AiQuotaReservation(
			AiQuotaReservationStatus.ACQUIRED,
			quotaSubject,
			reservationToken,
			quotaMonth,
			quotaDay,
			reservedCostUsd);
	}

	static AiQuotaReservation rejected(AiQuotaReservationStatus status) {
		return new AiQuotaReservation(status, "", "", null, null, BigDecimal.ZERO);
	}
}
