package cloud.bamsongi.albammate.infra.ai;

import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.context.event.EventListener;

import cloud.bamsongi.albammate.assistant.contract.AssistantCostWarningEvent;

/** 같은 월·경고의 반복 event를 한 번의 관측 신호로 합친다. */
class AssistantCostWarningEventListener {

	private final AssistantUsageEventMetrics metrics;
	private final Set<CostWarningKey> recordedWarnings = ConcurrentHashMap.newKeySet();

	AssistantCostWarningEventListener(AssistantUsageEventMetrics metrics) {
		this.metrics = metrics;
	}

	@EventListener
	public void record(AssistantCostWarningEvent event) {
		CostWarningKey key = new CostWarningKey(event.quotaMonth(), normalize(event.warningThresholdUsd()));
		if (recordedWarnings.add(key)) {
			metrics.recordCostWarning(event);
		}
	}

	private BigDecimal normalize(BigDecimal warningThresholdUsd) {
		return warningThresholdUsd.stripTrailingZeros();
	}

	private record CostWarningKey(YearMonth quotaMonth, BigDecimal warningThresholdUsd) {
	}
}
