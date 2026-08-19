package cloud.bamsongi.albammate.infra.ai;

import org.springframework.context.event.EventListener;

import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;

/** Provider가 발행한 사용량 event를 원문 보존 없이 관측 기록기로 전달한다. */
class AssistantUsageEventListener {

	private final AssistantUsageEventMetrics metrics;

	AssistantUsageEventListener(AssistantUsageEventMetrics metrics) {
		this.metrics = metrics;
	}

	@EventListener
	public void record(AssistantUsageEvent event) {
		metrics.recordUsage(event);
	}
}
