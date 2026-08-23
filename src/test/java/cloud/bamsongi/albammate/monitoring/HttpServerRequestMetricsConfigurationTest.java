package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class HttpServerRequestMetricsConfigurationTest {

	@Test
	void T3_production_OTLP는_정본_inventory의_exact_meter만_export한다() {
		Map<String, Object> metrics = metrics();
		Map<String, Object> enabled = map(metrics.get("enable"));
		Map<String, Object> distribution = map(metrics.get("distribution"));
		Map<String, Object> percentiles = map(distribution.get("percentiles"));
		Map<String, Object> histograms = map(distribution.get("percentiles-histogram"));

		assertEquals(false, enabled.get("all"));
		assertEquals(Set.of(
			"http.server.requests", "jvm.memory.used", "jvm.memory.max", "jvm.gc.pause", "jvm.threads.live",
			"tomcat.threads.busy", "tomcat.threads.current", "tomcat.threads.config.max",
			"hikaricp.connections.active", "hikaricp.connections.idle", "hikaricp.connections.pending",
			"hikaricp.connections.max", "hikaricp.connections.timeout", "albam.dependency.health",
			"auth.request.limiter.rejections", "auth.request.limiter.capacity.utilization",
			"chat.websocket.connections.active", "chat.websocket.delivery.latency",
			"chat.websocket.delivery.failures", "chat.websocket.recovery.messages",
			"chat.message.delivery.duration", "chat.message.delivery.failures", "chat.message.operations",
			"chat.message.retention.lock.skipped", "chat.message.retention.rooms.purged",
			"chat.message.retention.messages.deleted", "chat.message.retention.failures",
			"chat.message.retention.lease.guard.aborted", "chat.message.retention.backlog.remaining",
			"chat.message.retention.execution.duration", "chat.message.retention.delay",
			"notification.relay.events", "notification.relay.delivery.duration",
			"notification.relay.oldest.processable.age", "room.status.correction.runs",
			"room.status.correction.duration", "room.waitlist.operations", "assistant.usage.events",
			"assistant.usage.tokens", "assistant.usage.latency", "assistant.cost.warning.events"),
			enabled.keySet().stream().filter(key -> !key.equals("all")).collect(java.util.stream.Collectors.toSet()));
		assertTrue(enabled.entrySet().stream()
			.filter(entry -> !entry.getKey().equals("all"))
			.allMatch(entry -> Boolean.TRUE.equals(entry.getValue())));
		assertFalse(metrics.containsKey("tags"));
		assertEquals(List.of(0.5, 0.95, 0.99), percentiles.get("http.server.requests"));
		assertEquals(true, histograms.get("http.server.requests"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> metrics() {
		Map<String, Object> root = new Yaml().load(readProduction());
		return map(map(root.get("management")).get("metrics"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return (Map<String, Object>)value;
	}

	private String readProduction() {
		try {
			return Files.readString(Path.of("src/main/resources/application-production.yml"));
		} catch (IOException exception) {
			throw new IllegalStateException("production 관측 설정을 읽지 못했습니다", exception);
		}
	}
}
