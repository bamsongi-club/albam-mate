package cloud.bamsongi.albammate.infra.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
	void T1_성공과_모든_실패_usage_event의_token_latency_status_cost가_Micrometer에_도착한다() {
		try (AnnotationConfigApplicationContext context = usageObservationContext()) {
			AssistantUsageEventSink sink = context.getBean(AssistantUsageEventSink.class);
			SimpleMeterRegistry meterRegistry = context.getBean(SimpleMeterRegistry.class);
			List<AssistantUsageEvent> events = List.of(
				usage("SUCCESS", 11, 17, Duration.ofMillis(101), "0.01"),
				usage("PROVIDER_TIMEOUT", 12, 18, Duration.ofMillis(102), "0.02"),
				usage("PROVIDER_RATE_LIMITED", 13, 19, Duration.ofMillis(103), "0.03"),
				usage("INVALID_PROVIDER_SCHEMA", 14, 20, Duration.ofMillis(104), "0.04"),
				usage("QUOTA_EXCEEDED", 15, 21, Duration.ofMillis(105), "0.05"),
				usage("COST_CAP_REACHED", 16, 22, Duration.ofMillis(106), "0.06"));

			events.forEach(sink::record);

			for (AssistantUsageEvent event : events) {
				assertEquals(1.0, meterRegistry.get("assistant.usage.events")
					.tag("status", event.status()).counter().count());
				assertEquals(event.inputTokens(), meterRegistry.get("assistant.usage.tokens")
					.tags("status", event.status(), "token_type", "input").summary().totalAmount());
				assertEquals(event.outputTokens(), meterRegistry.get("assistant.usage.tokens")
					.tags("status", event.status(), "token_type", "output").summary().totalAmount());
				assertEquals(event.totalTokens(), meterRegistry.get("assistant.usage.tokens")
					.tags("status", event.status(), "token_type", "total").summary().totalAmount());
				assertEquals(event.latency().toMillis(), meterRegistry.get("assistant.usage.latency")
					.tag("status", event.status()).timer().totalTime(java.util.concurrent.TimeUnit.MILLISECONDS));
				assertEquals(event.costUsd().doubleValue(), meterRegistry.get("assistant.usage.cost.usd")
					.tag("status", event.status()).summary().totalAmount());
			}
		}
	}

	@Test
	void T2_관측_metric은_bounded_label만_사용하고_원문식별자는_노출하지_않는다() {
		try (AnnotationConfigApplicationContext context = usageObservationContext()) {
			AssistantUsageEventSink sink = context.getBean(AssistantUsageEventSink.class);
			SimpleMeterRegistry meterRegistry = context.getBean(SimpleMeterRegistry.class);
			sink.record(usage("SUCCESS", 11, 17, Duration.ofMillis(101), "0.01"));

			Set<String> expectedTagKeys = Set.of(
				"provider", "model", "feature", "prompt_version", "schema_version", "status", "token_type");
			Set<String> forbiddenLabelFragments = Set.of(
				"prompt", "response", "candidate", "user", "session", "secret", "content", "text");
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().startsWith("assistant.usage."))
				.flatMap(meter -> meter.getId().getTags().stream())
				.map(Tag::getKey)
				.allMatch(expectedTagKeys::contains));
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().startsWith("assistant.usage."))
				.flatMap(meter -> meter.getId().getTags().stream())
				.map(Tag::getValue)
				.noneMatch(value -> forbiddenLabelFragments.stream().anyMatch(value::contains)));
			assertEquals(Set.of("fake"), tagValues(meterRegistry, "assistant.usage.events", "provider"));
			assertEquals(Set.of("gpt-5.6-luna"), tagValues(meterRegistry, "assistant.usage.events", "model"));
			assertEquals(Set.of("AI-02"), tagValues(meterRegistry, "assistant.usage.events", "feature"));
			assertEquals(Set.of("AI-02-INSTRUCTION-V1"),
				tagValues(meterRegistry, "assistant.usage.events", "prompt_version"));
			assertEquals(Set.of("AI-02-SCHEMA-V1"),
				tagValues(meterRegistry, "assistant.usage.events", "schema_version"));
			assertEquals(Set.of("SUCCESS"), tagValues(meterRegistry, "assistant.usage.events", "status"));

			sink.record(new AssistantUsageEvent(
				"prompt=raw-user@example.com", "response-secret", "candidate-user-991",
				"prompt body", "session-token", 1, 1, 2, Duration.ofMillis(1),
				"user-id-991", new BigDecimal("0.01")));

			assertEquals(Set.of("fake", "unknown"), tagValues(meterRegistry, "assistant.usage.events", "provider"));
			assertEquals(Set.of("gpt-5.6-luna", "unknown"),
				tagValues(meterRegistry, "assistant.usage.events", "model"));
			assertEquals(Set.of("AI-02", "unknown"), tagValues(meterRegistry, "assistant.usage.events", "feature"));
			assertEquals(Set.of("AI-02-INSTRUCTION-V1", "unknown"),
				tagValues(meterRegistry, "assistant.usage.events", "prompt_version"));
			assertEquals(Set.of("AI-02-SCHEMA-V1", "unknown"),
				tagValues(meterRegistry, "assistant.usage.events", "schema_version"));
			assertEquals(Set.of("SUCCESS", "unknown"), tagValues(meterRegistry, "assistant.usage.events", "status"));
			assertTrue(meterRegistry.getMeters().stream()
				.filter(meter -> meter.getId().getName().startsWith("assistant.usage."))
				.flatMap(meter -> meter.getId().getTags().stream())
				.map(Tag::getValue)
				.noneMatch(value -> value.contains("raw-user@example.com")
					|| value.contains("response-secret")
					|| value.contains("user-991")
					|| value.contains("session-token")));
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
