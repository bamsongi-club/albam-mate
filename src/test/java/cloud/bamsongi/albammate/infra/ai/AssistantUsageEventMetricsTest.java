package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AssistantUsageEventMetricsTest {

	@Test
	void T1_usage_metric은_허용된_status와_token_type만_label로_사용한다() {
		try (AnnotationConfigApplicationContext context = usageObservationContext()) {
			AssistantUsageEventSink sink = context.getBean(AssistantUsageEventSink.class);
			SimpleMeterRegistry meterRegistry = context.getBean(SimpleMeterRegistry.class);
			sink.record(usage("SUCCESS", 11, 17, Duration.ofMillis(101), "0.01"));
			sink.record(new AssistantUsageEvent(
				"provider=raw-user@example.com", "model-secret", "feature-user-991",
				"prompt body", "schema-session-token", 1, 1, 2, Duration.ofMillis(1),
				"status-secret", new BigDecimal("0.01")));

			Set<String> allowedTagKeys = Set.of("status", "token_type");
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().startsWith("assistant.usage."))
				.flatMap(meter -> meter.getId().getTags().stream())
				.map(Tag::getKey)
				.allMatch(allowedTagKeys::contains));
			assertEquals(Set.of("SUCCESS", "unknown"), tagValues(meterRegistry, "assistant.usage.events", "status"));
			assertFalse(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().startsWith("assistant.usage."))
				.flatMap(meter -> meter.getId().getTags().stream())
				.anyMatch(tag -> Set.of("provider", "model", "feature", "prompt_version", "schema_version")
					.contains(tag.getKey())));
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().startsWith("assistant.usage."))
				.flatMap(meter -> meter.getId().getTags().stream())
				.map(Tag::getValue)
				.noneMatch(value -> value.contains("raw-user@example.com")
					|| value.contains("model-secret")
					|| value.contains("user-991")
					|| value.contains("session-token")));
		}
	}

	@Test
	void T2_input_output_token은_분리_누적하고_total은_공유_event값_대신_재계산한다() {
		try (AnnotationConfigApplicationContext context = usageObservationContext()) {
			AssistantUsageEventSink sink = context.getBean(AssistantUsageEventSink.class);
			SimpleMeterRegistry meterRegistry = context.getBean(SimpleMeterRegistry.class);
			sink.record(usage("SUCCESS", 11, 17, Duration.ofMillis(101), "0.01"));
			sink.record(new AssistantUsageEvent(
				"fake", "gpt-5.6-luna", "AI-02", "AI-02-INSTRUCTION-V1", "AI-02-SCHEMA-V1",
				13, 19, 999, Duration.ofMillis(102), "SUCCESS", new BigDecimal("0.02")));

			assertEquals(24.0, meterRegistry.get("assistant.usage.tokens")
				.tags("status", "SUCCESS", "token_type", "input").summary().totalAmount());
			assertEquals(36.0, meterRegistry.get("assistant.usage.tokens")
				.tags("status", "SUCCESS", "token_type", "output").summary().totalAmount());
			assertEquals(60.0, meterRegistry.get("assistant.usage.tokens")
				.tags("status", "SUCCESS", "token_type", "total").summary().totalAmount());
			assertFalse(meterRegistry.getMeters().stream()
				.anyMatch(meter -> meter.getId().getName().equals("assistant.usage.cost.usd")));
		}
	}

	private AnnotationConfigApplicationContext usageObservationContext() {
		AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.registerBean(SimpleMeterRegistry.class);
		context.scan("cloud.bamsongi.albammate.infra.ai");
		context.refresh();
		return context;
	}

	private AssistantUsageEvent usage(
		String status,
		int inputTokens,
		int outputTokens,
		Duration latency,
		String costUsd) {
		return new AssistantUsageEvent(
			"fake", "gpt-5.6-luna", "AI-02", "AI-02-INSTRUCTION-V1", "AI-02-SCHEMA-V1",
			inputTokens, outputTokens, inputTokens + outputTokens, latency, status, new BigDecimal(costUsd));
	}

	private Set<String> tagValues(SimpleMeterRegistry meterRegistry, String meterName, String tagKey) {
		return meterRegistry.getMeters().stream()
			.filter(meter -> meter.getId().getName().equals(meterName))
			.map(Meter::getId)
			.flatMap(id -> id.getTags().stream())
			.filter(tag -> tag.getKey().equals(tagKey))
			.map(Tag::getValue)
			.collect(java.util.stream.Collectors.toSet());
	}
}
