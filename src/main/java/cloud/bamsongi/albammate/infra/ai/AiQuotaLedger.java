package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.Instant;

interface AiQuotaLedger {

	AiQuotaReservation reserve(String quotaSubject, Instant now);

	default AiQuotaReservation reserve(String quotaSubject, Instant now, BigDecimal estimatedCostUsd) {
		return reserve(quotaSubject, now);
	}

	AiQuotaCompletionStatus complete(AiQuotaReservation reservation, BigDecimal costUsd);

	default void scheduleCompletionRetry(AiQuotaReservation reservation, BigDecimal costUsd) {
		// Redis 장애 시 공유 ledger가 복구 후 재조정한다. fake ledger에는 별도 작업이 없다.
	}
}
