package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import cloud.bamsongi.albammate.assistant.contract.AssistantUsageEvent;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AssistantUsageEventMetricsTest {

	@Test
	void T1_PROVIDER_INPUT_TOO_LARGE는_REJECTED이고_임의_미지_status는_unknown으로_남긴다() {
		try (AnnotationConfigApplicationContext context = usageObservationContext()) {
			AssistantUsageEventSink sink = context.getBean(AssistantUsageEventSink.class);
			SimpleMeterRegistry meterRegistry = context.getBean(SimpleMeterRegistry.class);
			sink.record(usage("PROVIDER_INPUT_TOO_LARGE", 1, 1, Duration.ofMillis(1), "0.01"));
			sink.record(usage("unrecognized-future-status", 1, 1, Duration.ofMillis(1), "0.01"));

			assertEquals(Set.of("REJECTED", "unknown"),
				tagValues(meterRegistry, "assistant.usage.events", "status"));
		}
	}

	@Test
	void T1_usage_event만_승인된_status를_label로_사용하고_token_latency는_식별자를_남기지_않는다() {
		try (AnnotationConfigApplicationContext context = usageObservationContext()) {
			AssistantUsageEventSink sink = context.getBean(AssistantUsageEventSink.class);
			SimpleMeterRegistry meterRegistry = context.getBean(SimpleMeterRegistry.class);
			sink.record(usage("SUCCESS", 11, 17, Duration.ofMillis(101), "0.01"));
			sink.record(usage("NOT_ENABLED", 1, 1, Duration.ofMillis(1), "0.01"));
			sink.record(usage("CONSENT_REQUIRED", 1, 1, Duration.ofMillis(1), "0.01"));
			sink.record(usage("SERVICE_UNAVAILABLE", 1, 1, Duration.ofMillis(1), "0.01"));
			sink.record(new AssistantUsageEvent(
				"provider=raw-user@example.com", "model-secret", "feature-user-991",
				"prompt body", "schema-session-token", 1, 1, 2, Duration.ofMillis(1),
				"status-secret", new BigDecimal("0.01")));

			assertEquals(Set.of("SUCCESS", "NOT_CALLED", "REJECTED", "FAILED", "unknown"),
				tagValues(meterRegistry, "assistant.usage.events", "status"));
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().equals("assistant.usage.events"))
				.allMatch(meter -> meter.getId().getTags()
					.equals(List.of(Tag.of("status", meter.getId().getTag("status"))))));
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().equals("assistant.usage.tokens"))
				.allMatch(meter -> meter.getId().getTags()
					.equals(List.of(Tag.of("token_type", meter.getId().getTag("token_type"))))));
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().equals("assistant.usage.latency"))
				.allMatch(meter -> meter.getId().getTags().isEmpty()));
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
	void T2_input_output_token은_OTLP_호환_counter로_분리_누적하고_total_series없이_조회에서_재계산한다() {
		try (AnnotationConfigApplicationContext context = usageObservationContext()) {
			AssistantUsageEventSink sink = context.getBean(AssistantUsageEventSink.class);
			SimpleMeterRegistry meterRegistry = context.getBean(SimpleMeterRegistry.class);
			sink.record(usage("SUCCESS", 11, 17, Duration.ofMillis(101), "0.01"));
			sink.record(new AssistantUsageEvent(
				"fake", "gpt-5.6-luna", "AI-02", "AI-02-INSTRUCTION-V1", "AI-02-SCHEMA-V1",
				13, 19, 999, Duration.ofMillis(102), "SUCCESS", new BigDecimal("0.02")));

			assertEquals(24.0, meterRegistry.get("assistant.usage.tokens")
				.tags("token_type", "input").counter().count());
			assertEquals(36.0, meterRegistry.get("assistant.usage.tokens")
				.tags("token_type", "output").counter().count());
			assertEquals(60.0, meterRegistry.get("assistant.usage.tokens")
				.tags("token_type", "input").counter().count()
				+ meterRegistry.get("assistant.usage.tokens").tags("token_type", "output").counter().count());
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().equals("assistant.usage.tokens"))
				.allMatch(meter -> meter.getId().getType() == Meter.Type.COUNTER));
			assertFalse(meterRegistry.getMeters().stream()
				.anyMatch(meter -> meter.getId().getName().equals("assistant.usage.tokens")
					&& "total".equals(meter.getId().getTag("token_type"))));
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().equals("assistant.usage.tokens"))
				.allMatch(meter -> meter.getId().getTags().equals(List.of(
					Tag.of("token_type", meter.getId().getTag("token_type"))))));
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
