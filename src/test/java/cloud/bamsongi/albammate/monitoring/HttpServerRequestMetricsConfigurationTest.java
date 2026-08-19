package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class HttpServerRequestMetricsConfigurationTest {

	@Test
	void T1_HTTP_timer는_동일_분포에서_percentile과_histogram을_기록한다() {
		Map<String, Object> metrics = metrics();
		Map<String, Object> distribution = map(metrics.get("distribution"));
		Map<String, Object> percentiles = map(distribution.get("percentiles"));
		Map<String, Object> histograms = map(distribution.get("percentiles-histogram"));

		assertEquals(List.of(0.5, 0.95, 0.99), percentiles.get("http.server.requests"));
		assertEquals(true, histograms.get("http.server.requests"));
	}

	@Test
	void T2_JVM_Tomcat_Hikari_meter는_같은_배포_식별자로_명시적으로_활성화한다() {
		Map<String, Object> metrics = metrics();
		Map<String, Object> enabled = map(metrics.get("enable"));
		Map<String, Object> tags = map(metrics.get("tags"));

		assertEquals(true, enabled.get("jvm"));
		assertEquals(true, enabled.get("tomcat"));
		assertEquals(true, enabled.get("hikaricp"));
		assertEquals(Map.of(
			"environment", "${ALBAM_MATE_ENVIRONMENT}",
			"stackId", "${ALBAM_MATE_STACK_ID}",
			"service", "albam-mate",
			"role", "${ALBAM_MATE_ROLE}",
			"instanceId", "${ALBAM_MATE_INSTANCE_ID}",
			"release", "${ALBAM_MATE_RELEASE}"), tags);
	}

	@Test
	void T3_pool_대기와_복구_증거도_같은_release_dimension을_사용한다() {
		Map<String, Object> tags = map(metrics().get("tags"));

		assertTrue(tags.containsKey("release"));
		assertEquals("${ALBAM_MATE_RELEASE}", tags.get("release"));
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
