package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.YearMonth;

record AiQuotaReservation(
	AiQuotaReservationStatus status,
	String quotaSubject,
	String reservationToken,
	YearMonth quotaMonth,
	BigDecimal reservedCostUsd) {

	static AiQuotaReservation acquired(String quotaSubject) {
		return acquired(quotaSubject, null, "", BigDecimal.ZERO);
	}

	static AiQuotaReservation acquired(
		String quotaSubject,
		YearMonth quotaMonth,
		String reservationToken,
		BigDecimal reservedCostUsd) {
		return new AiQuotaReservation(
			AiQuotaReservationStatus.ACQUIRED,
			quotaSubject,
			reservationToken,
			quotaMonth,
			reservedCostUsd);
	}

	static AiQuotaReservation rejected(AiQuotaReservationStatus status) {
		return new AiQuotaReservation(status, "", "", null, BigDecimal.ZERO);
	}
}
