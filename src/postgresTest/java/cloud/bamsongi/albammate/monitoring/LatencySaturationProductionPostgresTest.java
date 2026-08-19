package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.DoublePredicate;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.google.protobuf.MessageOrBuilder;
import com.sun.net.httpserver.HttpServer;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;

@Testcontainers
@ActiveProfiles("production")
@Import(LatencySaturationProductionPostgresTest.ControlledHttpRequestConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"ALBAM_MATE_DB_HOST=127.0.0.1",
	"ALBAM_MATE_DB_PORT=5432",
	"ALBAM_MATE_DB_NAME=albam_mate",
	"ALBAM_MATE_DB_USER=albam_mate",
	"ALBAM_MATE_DB_PASSWORD=not-a-secret",
	"ALBAM_MATE_REDIS_HOST=127.0.0.1",
	"ALBAM_MATE_ENVIRONMENT=test",
	"ALBAM_MATE_STACK_ID=issue-732",
	"ALBAM_MATE_ROLE=app1",
	"ALBAM_MATE_INSTANCE_ID=postgres-test",
	"ALBAM_MATE_RELEASE=test-release",
	"spring.datasource.hikari.maximum-pool-size=1",
	"spring.datasource.hikari.minimum-idle=0"
})
class LatencySaturationProductionPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Map<String, String> DEPLOYMENT_TAGS = Map.of(
		"environment", "test",
		"stackId", "issue-732",
		"service", "albam-mate",
		"role", "app1",
		"instanceId", "postgres-test",
		"release", "test-release");
	private static final Set<String> REQUIRED_SATURATION_METERS = Set.of(
		"jvm.memory.used",
		"jvm.threads.live",
		"tomcat.threads.busy",
		"tomcat.threads.current",
		"tomcat.threads.config.max",
		"hikaricp.connections.active",
		"hikaricp.connections.idle",
		"hikaricp.connections.pending",
		"hikaricp.connections.max",
		"hikaricp.connections.timeout");
	private static final Set<String> TOMCAT_THREAD_METERS = Set.of(
		"tomcat.threads.busy",
		"tomcat.threads.current",
		"tomcat.threads.config.max");
	private static final ConcurrentLinkedQueue<OtlpPayload> OTLP_PAYLOADS = new ConcurrentLinkedQueue<>();
	private static final AtomicLong OTLP_RECEIVER_SEQUENCE = new AtomicLong();
	private static final HttpServer OTLP_RECEIVER = startOtlpReceiver();

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("latency_saturation_production_test");

	@Autowired
	private DataSource dataSource;

	@Autowired
	private MeterRegistry meterRegistry;

	@LocalServerPort
	private int applicationPort;

	@DynamicPropertySource
	static void monitoringProperties(DynamicPropertyRegistry registry) {
		registry.add("management.otlp.metrics.export.step", () -> "250ms");
		registry.add("management.otlp.metrics.export.url",
			() -> "http://127.0.0.1:" + OTLP_RECEIVER.getAddress().getPort() + "/v1/metrics");
	}

	@BeforeEach
	void clearOtlpPayloads() {
		OTLP_PAYLOADS.clear();
	}

	@AfterAll
	static void stopOtlpReceiver() {
		OTLP_RECEIVER.stop(0);
	}

	@Test
	void T1_정상과_느린_HTTP_요청은_같은_timer의_정규화_route와_배포_dimension으로_export된다()
		throws Exception {
		assertEquals(200, request("/monitoring-contract/normal/711?requestId=private-id").statusCode());
		assertEquals(200, request("/monitoring-contract/slow/712?query=private-value").statusCode());

		assertTrue(await(() -> httpRequestPoints().stream().anyMatch(point -> pointHas(point,
			Map.of("method", "GET", "uri", "/monitoring-contract/normal/{id}", "status", "200",
				"outcome", "SUCCESS")))));
		assertTrue(await(() -> httpRequestPoints().stream().anyMatch(point -> pointHas(point,
			Map.of("method", "GET", "uri", "/monitoring-contract/slow/{id}", "status", "200",
				"outcome", "SUCCESS")))));
		assertTrue(httpRequestPoints().stream().anyMatch(this::hasDeploymentTags));
		assertFalse(httpRequestPoints().stream().flatMap(point -> attributes(point).keySet().stream())
			.anyMatch(
				key -> key.equals("requestId") || key.equals("query") || key.equals("userId") || key.equals("roomId")
					|| key.equals("messageId") || key.equals("notificationId")));
	}

	@Test
	void T2_JVM_Tomcat_Hikari_meter는_서로_다른_이름과_같은_release로_OTLP에_export된다() throws Exception {
		REQUIRED_SATURATION_METERS.forEach(this::assertRegistered);
		assertEquals(200, request("/monitoring-contract/normal/721").statusCode());

		assertTrue(await(() -> exportedMetricNames().containsAll(REQUIRED_SATURATION_METERS)));
		assertTrue(metricRequests().stream().allMatch(this::hasDeploymentResourceAttributes));
		Set<String> connectorNames = TOMCAT_THREAD_METERS.stream()
			.flatMap(meterName -> gaugePoints(meterName).stream())
			.map(point -> attributes(point).get("name"))
			.filter(java.util.Objects::nonNull)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
		assertEquals(1, connectorNames.size());
		assertTrue(connectorNames.iterator().next().contains("http-nio-auto-"));
	}

	@Test
	void T3_pool_대기_해제_뒤_pending이_복구되고_후속_HTTP_요청이_성공한다() throws Exception {
		ExecutorService executor = Executors.newSingleThreadExecutor();
		OtlpPayload pendingPayload = null;
		try (Connection heldConnection = dataSource.getConnection()) {
			long pendingProbeStart = OTLP_RECEIVER_SEQUENCE.get();
			Future<Connection> waitingConnection = executor.submit((Callable<Connection>)dataSource::getConnection);
			assertTrue(await(() -> meterValue("hikaricp.connections.pending") >= 1.0));
			pendingPayload = awaitPayloadAfter(pendingProbeStart,
				payload -> payloadHasGaugeValue(payload, "hikaricp.connections.pending", value -> value >= 1.0));
			assertNotNull(pendingPayload);
			heldConnection.close();
			try (Connection acquiredConnection = waitingConnection.get(2, TimeUnit.SECONDS)) {
				assertTrue(acquiredConnection.isValid(1));
			}
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
		}

		assertTrue(await(() -> meterValue("hikaricp.connections.pending") == 0.0));
		assertNotNull(pendingPayload);
		assertNotNull(awaitPayloadAfter(pendingPayload.sequence(),
			payload -> payloadHasGaugeValue(payload, "hikaricp.connections.pending", value -> value == 0.0)));
		assertEquals(200, request("/monitoring-contract/normal/731").statusCode());
		assertTrue(await(() -> httpRequestPoints().stream().anyMatch(this::hasDeploymentTags)));
	}

	private HttpResponse<String> request(String path) throws IOException, InterruptedException {
		return HttpClient.newHttpClient().send(
			HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort + path)).GET().build(),
			HttpResponse.BodyHandlers.ofString());
	}

	private void assertRegistered(String meterName) {
		Meter meter = meterRegistry.find(meterName).meter();
		assertNotNull(meter, () -> "등록되지 않은 saturation meter: " + meterName + ", registered="
			+ meterRegistry.getMeters().stream().map(candidate -> candidate.getId().getName()).sorted().toList());
	}

	private double meterValue(String meterName) {
		Meter meter = meterRegistry.find(meterName).meter();
		return meter == null ? -1.0 : meter.measure().iterator().next().getValue();
	}

	private boolean await(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			if (condition.getAsBoolean()) {
				return true;
			}
			Thread.sleep(25);
		}
		return false;
	}

	private OtlpPayload awaitPayloadAfter(long sequence, Predicate<OtlpPayload> condition)
		throws InterruptedException {
		long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
		while (System.nanoTime() < deadline) {
			OtlpPayload payload = OTLP_PAYLOADS.stream()
				.filter(candidate -> candidate.sequence() > sequence)
				.filter(condition)
				.findFirst()
				.orElse(null);
			if (payload != null) {
				return payload;
			}
			Thread.sleep(25);
		}
		return null;
	}

	private boolean payloadHasGaugeValue(OtlpPayload payload, String metricName, DoublePredicate expected) {
		return parseRequest(payload).getResourceMetricsList().stream()
			.flatMap(resourceMetrics -> resourceMetrics.getScopeMetricsList().stream())
			.flatMap(scopeMetrics -> scopeMetrics.getMetricsList().stream())
			.filter(metric -> metricName.equals(metric.getName()))
			.flatMap(metric -> metric.getGauge().getDataPointsList().stream())
			.anyMatch(point -> hasDeploymentTags(point) && expected.test(gaugeValue(point)));
	}

	private Collection<MessageOrBuilder> httpRequestPoints() {
		return metricRequests().stream()
			.flatMap(request -> request.getResourceMetricsList().stream())
			.flatMap(resourceMetrics -> resourceMetrics.getScopeMetricsList().stream())
			.flatMap(scopeMetrics -> scopeMetrics.getMetricsList().stream())
			.filter(metric -> "http.server.requests".equals(metric.getName()))
			.flatMap(metric -> metric.getHistogram().getDataPointsList().stream().map(point -> (MessageOrBuilder)point))
			.toList();
	}

	private Collection<MessageOrBuilder> gaugePoints(String metricName) {
		return metricRequests().stream()
			.flatMap(request -> request.getResourceMetricsList().stream())
			.flatMap(resourceMetrics -> resourceMetrics.getScopeMetricsList().stream())
			.flatMap(scopeMetrics -> scopeMetrics.getMetricsList().stream())
			.filter(metric -> metricName.equals(metric.getName()))
			.flatMap(metric -> metric.getGauge().getDataPointsList().stream().map(point -> (MessageOrBuilder)point))
			.toList();
	}

	private Set<String> exportedMetricNames() {
		return metricRequests().stream()
			.flatMap(request -> request.getResourceMetricsList().stream())
			.flatMap(resourceMetrics -> resourceMetrics.getScopeMetricsList().stream())
			.flatMap(scopeMetrics -> scopeMetrics.getMetricsList().stream())
			.map(Metric::getName)
			.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private List<ExportMetricsServiceRequest> metricRequests() {
		return OTLP_PAYLOADS.stream().map(LatencySaturationProductionPostgresTest::parseRequest).toList();
	}

	private boolean pointHas(MessageOrBuilder point, Map<String, String> expected) {
		Map<String, String> attributes = attributes(point);
		return expected.entrySet().stream().allMatch(entry -> entry.getValue().equals(attributes.get(entry.getKey())));
	}

	private boolean hasDeploymentTags(MessageOrBuilder point) {
		return pointHas(point, DEPLOYMENT_TAGS);
	}

	private double gaugeValue(MessageOrBuilder point) {
		return ((NumberDataPoint)point).getAsDouble();
	}

	private boolean hasDeploymentResourceAttributes(ExportMetricsServiceRequest request) {
		return request.getResourceMetricsList().stream().allMatch(resourceMetrics -> {
			Map<String, String> attributes = resourceMetrics.getResource().getAttributesList().stream()
				.collect(java.util.stream.Collectors.toMap(KeyValue::getKey,
					attribute -> attribute.getValue().getStringValue(), (first, second) -> first));
			return DEPLOYMENT_TAGS.entrySet().stream()
				.allMatch(entry -> entry.getValue().equals(attributes.get(entry.getKey())));
		});
	}

	private Map<String, String> attributes(MessageOrBuilder point) {
		try {
			@SuppressWarnings("unchecked") List<KeyValue> attributes = (List<KeyValue>)point.getClass()
				.getMethod("getAttributesList").invoke(point);
			return attributes.stream().collect(java.util.stream.Collectors.toMap(KeyValue::getKey,
				attribute -> attribute.getValue().getStringValue(), (first, second) -> first));
		} catch (ReflectiveOperationException exception) {
			throw new IllegalStateException("OTLP metric point attribute를 읽지 못했습니다", exception);
		}
	}

	private static HttpServer startOtlpReceiver() {
		try {
			HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			receiver.createContext("/v1/metrics", exchange -> {
				try {
					long sequence = OTLP_RECEIVER_SEQUENCE.incrementAndGet();
					OTLP_PAYLOADS.add(new OtlpPayload(sequence, exchange.getRequestBody().readAllBytes(),
						exchange.getRequestHeaders().getFirst("Content-Encoding")));
					exchange.sendResponseHeaders(200, -1);
				} finally {
					exchange.close();
				}
			});
			receiver.start();
			return receiver;
		} catch (IOException exception) {
			throw new IllegalStateException("OTLP 수신기를 시작하지 못했습니다", exception);
		}
	}

	private static ExportMetricsServiceRequest parseRequest(OtlpPayload payload) {
		try (InputStream body = "gzip".equalsIgnoreCase(payload.contentEncoding())
			? new GZIPInputStream(new ByteArrayInputStream(payload.body()))
			: new ByteArrayInputStream(payload.body())) {
			return ExportMetricsServiceRequest.parseFrom(body.readAllBytes());
		} catch (IOException exception) {
			throw new IllegalStateException("OTLP payload을 해석하지 못했습니다", exception);
		}
	}

	private record OtlpPayload(long sequence, byte[] body, String contentEncoding) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ControlledHttpRequestConfiguration {

		@Bean
		ControlledHttpRequestController controlledHttpRequestController() {
			return new ControlledHttpRequestController();
		}
	}

	@RestController
	static class ControlledHttpRequestController {

		@GetMapping("/monitoring-contract/normal/{id}")
		Map<String, String> normal(@PathVariable
		long id) {
			return Map.of("result", "normal");
		}

		@GetMapping("/monitoring-contract/slow/{id}")
		Map<String, String> slow(@PathVariable
		long id) throws InterruptedException {
			Thread.sleep(150);
			return Map.of("result", "slow");
		}
	}
}
