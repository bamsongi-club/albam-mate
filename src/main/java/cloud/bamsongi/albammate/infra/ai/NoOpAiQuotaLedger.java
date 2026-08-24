package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.Instant;

/** fake provider는 비용이 없는 결정적 호출이라 quota를 적용하지 않는다. */
final class NoOpAiQuotaLedger implements AiQuotaLedger {

	@Override
	public AiQuotaReservation reserve(String quotaSubject, Instant now) {
		return AiQuotaReservation.acquired(quotaSubject);
	}

	@Override
	public AiQuotaCompletionStatus complete(AiQuotaReservation reservation, BigDecimal costUsd) {
		return AiQuotaCompletionStatus.COMPLETED;
	}
}
