package cloud.bamsongi.albammate.infra.ai;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.micrometer.core.instrument.MeterRegistry;

/** Provider event seam을 Micrometer 사용량·비용 경고 관측 소비자로 연결한다. */
@Configuration(proxyBeanMethods = false)
class AiUsageRuntimeConfiguration {

	@Bean
	AssistantUsageEventMetrics assistantUsageEventMetrics(MeterRegistry meterRegistry) {
		return new AssistantUsageEventMetrics(meterRegistry);
	}

	@Bean
	AssistantUsageEventListener assistantUsageEventListener(AssistantUsageEventMetrics metrics) {
		return new AssistantUsageEventListener(metrics);
	}

	@Bean
	AssistantCostWarningEventListener assistantCostWarningEventListener(AssistantUsageEventMetrics metrics) {
		return new AssistantCostWarningEventListener(metrics);
	}
}
