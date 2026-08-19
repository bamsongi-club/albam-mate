package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.Instant;

/** Redis 응답을 확인할 수 없을 때의 fail-closed 표현이다. */
final class UnavailableAiQuotaLedger implements AiQuotaLedger {

	private int completedReservations;

	@Override
	public AiQuotaReservation reserve(String quotaSubject, Instant now) {
		return AiQuotaReservation.rejected(AiQuotaReservationStatus.UNAVAILABLE);
	}

	@Override
	public AiQuotaCompletionStatus complete(AiQuotaReservation reservation, BigDecimal costUsd) {
		completedReservations++;
		return AiQuotaCompletionStatus.UNAVAILABLE;
	}

	int completedReservations() {
		return completedReservations;
	}
}
