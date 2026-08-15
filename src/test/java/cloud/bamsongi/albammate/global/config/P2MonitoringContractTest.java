package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class P2MonitoringContractTest {

	@Test
	void T1_관리와_OTLP_수집_경계는_같은_host에만_남기고_외부로_publish하지_않는다() {
		String production = read("src/main/resources/application-production.yml");
		String app1 = read("compose.production.yml");
		String app2 = read("compose.app2.yml");

		assertTrue(production.contains("management:"));
		assertTrue(production.contains("address: 0.0.0.0"));
		assertTrue(production.contains("include: health,metrics"));
		assertTrue(production.contains("url: ${ALBAM_MATE_OTLP_METRICS_URL"));
		assertTrue(production.contains("connect-timeout: 1s"));
		assertTrue(production.contains("read-timeout: 2s"));
		assertTrue(app1.contains("host.docker.internal:host-gateway"));
		assertTrue(app2.contains("host.docker.internal:host-gateway"));
		assertFalse(app1.contains("9090:9090"));
		assertFalse(app2.contains("9090:9090"));
		assertFalse(app1.contains("4318:4318"));
		assertFalse(app2.contains("4318:4318"));
	}

	@Test
	void T3_배포_식별자와_구조화_로그_sink에는_허용된_고정값만_쓴다() {
		String production = read("src/main/resources/application-production.yml");
		String app1 = read("compose.production.yml");
		String app2 = read("compose.app2.yml");
		String logging = production.substring(production.indexOf("logging:"), production.indexOf("app:"));

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
		assertFalse(logging.contains("requestId: ${"));
		assertFalse(logging.contains("userId: ${"));
		assertFalse(logging.contains("roomId: ${"));
		assertFalse(logging.contains("messageId: ${"));
	}

	private String read(String relativePath) {
		try {
			return Files.readString(Path.of(relativePath));
		} catch (IOException exception) {
			throw new IllegalStateException("운영 관측 계약 파일을 읽지 못했습니다: " + relativePath, exception);
		}
	}
}
