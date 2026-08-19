package cloud.bamsongi.albammate.assistant.contract;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Objects;

/** 사용자 식별자나 원문 없이 OPS-04가 소비할 수 있는 월 비용 경고 이벤트다. */
public record AssistantCostWarningEvent(
	YearMonth quotaMonth,
	BigDecimal estimatedCostUsd,
	BigDecimal warningThresholdUsd) {

	public AssistantCostWarningEvent {
		quotaMonth = Objects.requireNonNull(quotaMonth, "quotaMonth");
		estimatedCostUsd = Objects.requireNonNull(estimatedCostUsd, "estimatedCostUsd");
		warningThresholdUsd = Objects.requireNonNull(warningThresholdUsd, "warningThresholdUsd");
	}
}
