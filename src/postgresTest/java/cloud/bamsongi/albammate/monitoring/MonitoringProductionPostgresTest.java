package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import io.micrometer.core.instrument.MeterRegistry;

@Testcontainers
@ActiveProfiles("production")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"ALBAM_MATE_DB_HOST=127.0.0.1",
	"ALBAM_MATE_DB_PORT=5432",
	"ALBAM_MATE_DB_NAME=albam_mate",
	"ALBAM_MATE_DB_USER=albam_mate",
	"ALBAM_MATE_DB_PASSWORD=not-a-secret",
	"ALBAM_MATE_REDIS_HOST=127.0.0.1",
	"ALBAM_MATE_ENVIRONMENT=test",
	"ALBAM_MATE_STACK_ID=issue-730",
	"ALBAM_MATE_ROLE=app1",
	"ALBAM_MATE_INSTANCE_ID=postgres-test",
	"ALBAM_MATE_RELEASE=test-release",
	"ALBAM_MATE_OTLP_METRICS_URL=http://127.0.0.1:1/v1/metrics",
	"logging.file.name=build/test-results/monitoring/production-structured.json"
})
class MonitoringProductionPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("monitoring_production_test");

	@Autowired
	private MeterRegistry meterRegistry;

	@LocalServerPort
	private int applicationPort;

	@Test
	void T1_OTLP_receiver가_도달_불가해도_대표_제품_요청은_성공한다() throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/games?size=1"))
				.GET()
				.build(),
			HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
	}

	@Test
	void T2_운영_PostgreSQL_context에서도_의존성_상태_meter가_분리되어_등록된다() {
		assertEquals("postgresql", meterRegistry.find("albam.dependency.health")
			.tag("dependency", "postgresql")
			.meter()
			.getId()
			.getTag("dependency"));
		assertEquals("redis", meterRegistry.find("albam.dependency.health")
			.tag("dependency", "redis")
			.meter()
			.getId()
			.getTag("dependency"));
	}

	@Test
	void T3_금지_MDC는_Logstash_JSON에_직렬화되지_않고_배포_field는_남는다() throws Exception {
		LoggerContext context = (LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory();
		Logger logger = context.getLogger(getClass());
		Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		Object file = root.getAppender("FILE");
		assertEquals(true, file instanceof FileAppender<?>);
		FileAppender<ILoggingEvent> appender = (FileAppender<ILoggingEvent>)file;
		assertEquals(true, appender.isStarted());
		Path logPath = Path.of(appender.getFile());
		long offset = Files.exists(logPath) ? Files.size(logPath) : 0;
		org.slf4j.MDC.put("email", "sentinel-email");
		org.slf4j.MDC.put("roomId", "sentinel-room");
		org.slf4j.MDC.put("requestBody", "sentinel-body");
		try {
			logger.warn("monitoring_contract_test");
		} finally {
			org.slf4j.MDC.clear();
		}
		byte[] all = Files.readAllBytes(logPath);
		String json = new String(Arrays.copyOfRange(all, Math.toIntExact(offset), all.length), StandardCharsets.UTF_8);
		assertEquals(false, json.isBlank());
		assertEquals(true, json.strip().startsWith("{"));
		assertEquals(true, json.contains("\"environment\":\"test\""));
		assertEquals(true, json.contains("\"stackId\":\"issue-730\""));
		assertEquals(true, json.contains("\"service\":\"albam-mate\""));
		assertEquals(true, json.contains("\"role\":\"app1\""));
		assertEquals(true, json.contains("\"instanceId\":\"postgres-test\""));
		assertEquals(true, json.contains("\"release\":\"test-release\""));
		assertEquals(false, json.contains("\"email\""));
		assertEquals(false, json.contains("\"roomId\""));
		assertEquals(false, json.contains("\"requestBody\""));
		assertEquals(false, json.contains("sentinel-email"));
		assertEquals(false, json.contains("sentinel-room"));
		assertEquals(false, json.contains("sentinel-body"));
	}
}
