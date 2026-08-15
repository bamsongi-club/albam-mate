package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;

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
	"logging.file.name=build/test-results/monitoring/production-structured.json"
})
class MonitoringProductionPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Set<String> FORBIDDEN_MDC_KEYS = Set.of(
		"email", "ip", "session", "cookie", "token", "authorization", "requestbody", "responsebody",
		"querystring", "prompt", "response", "toolargs", "toolresult", "chatcontent", "notificationpayload",
		"rawsql", "userid", "actoruserid", "roomid", "messageid", "sourceeventid");
	private static final AtomicInteger FAILED_OTLP_REQUESTS = new AtomicInteger();
	private static final AtomicReference<OtlpPayload> LAST_OTLP_PAYLOAD = new AtomicReference<>();
	private static final HttpServer FAILING_OTLP_RECEIVER = startFailingOtlpReceiver();

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("monitoring_production_test");

	@Autowired
	private MeterRegistry meterRegistry;

	@LocalServerPort
	private int applicationPort;

	@DynamicPropertySource
	static void monitoringProperties(DynamicPropertyRegistry registry) {
		registry.add("management.otlp.metrics.export.step", () -> "10ms");
		registry.add("management.otlp.metrics.export.url",
			() -> "http://127.0.0.1:" + FAILING_OTLP_RECEIVER.getAddress().getPort() + "/v1/metrics");
	}

	@AfterAll
	static void stopFailingOtlpReceiver() {
		FAILING_OTLP_RECEIVER.stop(0);
	}

	@Test
	void T1_OTLP_receiver가_도달_불가해도_대표_제품_요청은_성공한다() throws Exception {
		int requestsBefore = FAILED_OTLP_REQUESTS.get();
		LAST_OTLP_PAYLOAD.set(null);
		meterRegistry.counter("monitoring.contract.otlp.failure.probe").increment();
		HttpResponse<String> response = HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/games?size=1"))
				.GET()
				.build(),
			HttpResponse.BodyHandlers.ofString());

		assertEquals(200, response.statusCode());
		assertTrue(waitForFailedOtlpRequestAfter(requestsBefore));
		assertExpectedResourceAttributes(LAST_OTLP_PAYLOAD.get());
	}

	private static HttpServer startFailingOtlpReceiver() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			server.createContext("/v1/metrics", exchange -> {
				LAST_OTLP_PAYLOAD.set(new OtlpPayload(exchange.getRequestBody().readAllBytes(),
					exchange.getRequestHeaders().getFirst("Content-Encoding")));
				FAILED_OTLP_REQUESTS.incrementAndGet();
				exchange.sendResponseHeaders(503, -1);
				exchange.close();
			});
			server.start();
			return server;
		} catch (IOException exception) {
			throw new IllegalStateException("OTLP 실패 수신기를 시작하지 못했습니다", exception);
		}
	}

	private static boolean waitForFailedOtlpRequestAfter(int requestsBefore) throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
		while (System.nanoTime() < deadline) {
			if (FAILED_OTLP_REQUESTS.get() > requestsBefore) {
				return true;
			}
			Thread.sleep(20);
		}
		return FAILED_OTLP_REQUESTS.get() > requestsBefore;
	}

	private static void assertExpectedResourceAttributes(OtlpPayload payload) throws IOException {
		assertTrue(payload != null);
		ExportMetricsServiceRequest request = ExportMetricsServiceRequest.parseFrom(decode(payload));
		Map<String, String> attributes = request.getResourceMetricsList().stream()
			.flatMap(resourceMetrics -> resourceMetrics.getResource().getAttributesList().stream())
			.collect(java.util.stream.Collectors.toMap(attribute -> attribute.getKey(),
				attribute -> attribute.getValue().getStringValue(), (first, ignored) -> first));
		assertEquals("test", attributes.get("environment"));
		assertEquals("issue-730", attributes.get("stackId"));
		assertEquals("albam-mate", attributes.get("service"));
		assertEquals("app1", attributes.get("role"));
		assertEquals("postgres-test", attributes.get("instanceId"));
		assertEquals("test-release", attributes.get("release"));
	}

	private static byte[] decode(OtlpPayload payload) throws IOException {
		try (InputStream body = "gzip".equalsIgnoreCase(payload.contentEncoding())
			? new GZIPInputStream(new ByteArrayInputStream(payload.body()))
			: new ByteArrayInputStream(payload.body())) {
			return body.readAllBytes();
		}
	}

	private record OtlpPayload(byte[] body, String contentEncoding) {
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
		assertTrue(productionExcludedJsonFields().containsAll(FORBIDDEN_MDC_KEYS));
		Map<String, String> sentinels = FORBIDDEN_MDC_KEYS.stream()
			.collect(java.util.stream.Collectors.toMap(key -> key, key -> "sentinel-" + key));
		sentinels.forEach(org.slf4j.MDC::put);
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
		sentinels.forEach((key, value) -> {
			assertEquals(false, json.contains("\"" + key + "\""));
			assertEquals(false, json.contains(value));
		});
	}

	@SuppressWarnings("unchecked")
	private Set<String> productionExcludedJsonFields() throws IOException {
		Map<String, Object> root = new org.yaml.snakeyaml.Yaml()
			.load(Files.readString(Path.of("src/main/resources/application-production.yml")));
		Map<String, Object> logging = (Map<String, Object>)root.get("logging");
		Map<String, Object> structured = (Map<String, Object>)logging.get("structured");
		Map<String, Object> json = (Map<String, Object>)structured.get("json");
		return Set.of(String.valueOf(json.get("exclude")).toLowerCase(java.util.Locale.ROOT).split(","));
	}
}
