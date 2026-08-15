package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class P2MonitoringContractTest {

	@Test
	void T1_관리와_OTLP_수집_경계는_같은_host에만_남기고_외부로_publish하지_않는다() {
		String production = read("src/main/resources/application-production.yml");
		String app1 = read("compose.production.yml");
		String app2 = read("compose.app2.yml");

		assertTrue(production.contains("management:"));
		assertTrue(production.contains("address: 127.0.0.1"));
		assertTrue(production.contains("include: health,metrics"));
		assertTrue(production.contains("url: ${ALBAM_MATE_OTLP_METRICS_URL"));
		assertTrue(production.contains("connect-timeout: 1s"));
		assertTrue(production.contains("read-timeout: 2s"));
		assertTrue(app1.contains("host.docker.internal:host-gateway"));
		assertTrue(app2.contains("host.docker.internal:host-gateway"));
		assertPortsAreNotPublished(app1);
		assertPortsAreNotPublished(app2);
		assertManagementIsNotExposed(app1);
		assertManagementIsNotExposed(app2);
		assertTrue(app1.contains("http://127.0.0.1:9090/actuator/health"));
		assertTrue(app2.contains("http://127.0.0.1:9090/actuator/health"));
	}

	@Test
	void T3_배포_식별자와_구조화_로그_sink에는_허용된_고정값만_쓴다() {
		String production = read("src/main/resources/application-production.yml");
		String app1 = read("compose.production.yml");
		String app2 = read("compose.app2.yml");
		String logging = production.substring(production.indexOf("logging:"), production.indexOf("app:"));
		Map<String, Object> productionRoot = new Yaml().load(production);
		Map<String, Object> management = map(productionRoot.get("management"));
		Map<String, Object> resourceAttributes = map(map(map(map(management.get("otlp")).get("metrics")).get("export"))
			.get("resource-attributes"));
		Map<String, Object> openTelemetryResourceAttributes = map(map(management.get("opentelemetry"))
			.get("resource-attributes"));

		for (String allowed : new String[] {"environment", "stackId", "service", "role", "instanceId", "release"}) {
			assertTrue(logging.contains(allowed + ": "));
		}
		assertTrue(logging.contains("service: albam-mate"));
		assertTrue(logging.contains("console: logstash"));
		assertTrue(logging.contains("file: logstash"));
		assertTrue(logging.contains("name: /var/log/albam-mate/events.json"));
		assertTrue(logging.contains("max-file-size: 10MB"));
		assertTrue(logging.contains("total-size-cap: 50MB"));
		assertTrue(app1.contains("/var/log/albam-mate"));
		assertTrue(app2.contains("/var/log/albam-mate"));
		for (String attribute : new String[] {"environment", "stackId", "service", "role", "instanceId", "release"}) {
			assertTrue(resourceAttributes.containsKey(attribute));
			assertEquals(resourceAttributes.get(attribute), openTelemetryResourceAttributes.get(attribute));
		}
		assertEquals("${ALBAM_MATE_ROLE:-app1}", springEnvironment(app1).get("ALBAM_MATE_ROLE"));
		assertEquals("${ALBAM_MATE_ROLE:-app2}", springEnvironment(app2).get("ALBAM_MATE_ROLE"));
		assertTrue(springEnvironment(app1).containsKey("ALBAM_MATE_RELEASE"));
		assertTrue(springEnvironment(app2).containsKey("ALBAM_MATE_RELEASE"));
		assertFalse(logging.contains("requestId: ${"));
		assertFalse(logging.contains("userId: ${"));
		assertFalse(logging.contains("roomId: ${"));
		assertFalse(logging.contains("messageId: ${"));
	}

	@Test
	void T3_OPS01_상태는_공개_앱_범위의_부분_구현과_부분_검증으로만_표시한다() {
		String readme = read("docs/p2/README.md");

		assertTrue(readme.contains("OPS-01 공개 앱 범위 부분 구현·부분 검증"));
		assertFalse(readme.contains("OPS-01-AC1`~`OPS-01-AC3` 공개 구현·자동 검증 완료"));
	}

	private String read(String relativePath) {
		try {
			return Files.readString(Path.of(relativePath));
		} catch (IOException exception) {
			throw new IllegalStateException("운영 관측 계약 파일을 읽지 못했습니다: " + relativePath, exception);
		}
	}

	@SuppressWarnings("unchecked")
	private void assertPortsAreNotPublished(String compose) {
		Map<String, Object> root = new Yaml().load(compose);
		Map<String, Object> services = (Map<String, Object>)root.get("services");
		for (Object serviceValue : services.values()) {
			Map<String, Object> service = (Map<String, Object>)serviceValue;
			Object ports = service.get("ports");
			if (ports == null) {
				continue;
			}
			for (Object port : (Iterable<Object>)ports) {
				String target = port instanceof Map<?, ?> map ? String.valueOf(map.get("target"))
					: String.valueOf(port);
				assertFalse(target.matches(".*(^|:)9090(/.*)?$"));
				assertFalse(target.matches(".*(^|:)4318(/.*)?$"));
			}
		}
	}

	@SuppressWarnings("unchecked")
	private void assertManagementIsNotExposed(String compose) {
		Map<String, Object> root = new Yaml().load(compose);
		Map<String, Object> services = map(root.get("services"));
		Map<String, Object> spring = map(services.get("spring"));
		Object expose = spring.get("expose");
		if (expose == null) {
			return;
		}
		for (Object target : (Iterable<Object>)expose) {
			assertFalse("9090".equals(String.valueOf(target)));
		}
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> springEnvironment(String compose) {
		return map(map(map(map(new Yaml().load(compose)).get("services")).get("spring")).get("environment"));
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> map(Object value) {
		return (Map<String, Object>)value;
	}
}
