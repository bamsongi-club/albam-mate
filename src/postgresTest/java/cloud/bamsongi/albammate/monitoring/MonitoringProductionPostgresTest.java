package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.GZIPInputStream;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.otlp.OtlpMetricsProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import jakarta.persistence.OptimisticLockException;

@Testcontainers
@ActiveProfiles("production")
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
	"ALBAM_MATE_DB_HOST=127.0.0.1",
	"ALBAM_MATE_DB_PORT=5432",
	"ALBAM_MATE_DB_NAME=albam_mate",
	"ALBAM_MATE_DB_USER=albam_mate",
	"ALBAM_MATE_DB_PASSWORD=not-a-secret",
	"ALBAM_MATE_ENVIRONMENT=test",
	"ALBAM_MATE_STACK_ID=issue-730",
	"ALBAM_MATE_ROLE=app1",
	"ALBAM_MATE_INSTANCE_ID=postgres-test",
	"ALBAM_MATE_RELEASE=test-release",
	"logging.file.name=build/test-results/monitoring/production-structured.json"
})
class MonitoringProductionPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String REDIS_IMAGE = "redis:8.4-alpine";
	private static final int REDIS_STOP_MAX_ATTEMPTS = 50;
	private static final int REDIS_STARTUP_MAX_ATTEMPTS = 50;
	private static final Set<String> FORBIDDEN_MDC_KEYS = Set.of(
		"email", "ip", "session", "cookie", "token", "authorization", "requestbody", "responsebody",
		"querystring", "prompt", "response", "toolargs", "toolresult", "chatcontent", "notificationpayload",
		"rawsql", "userid", "actoruserid", "sourceeventids", "secret", "password", "unknownfield");
	private static final Map<String, String> ALLOWED_MDC_FIELDS = Map.of(
		"sourceEventId", "source-event-1",
		"processedCount", "3",
		"candidateLimit", "10");
	private static final String OTLP_PROBE_METRIC = "monitoring.contract.otlp.read-timeout.probe";
	private static final Map<String, String> EXPECTED_OTLP_RESOURCE_ATTRIBUTES = Map.of(
		"environment", "test",
		"stackId", "issue-730",
		"service", "albam-mate",
		"role", "app1",
		"instanceId", "postgres-test",
		"release", "test-release");
	private static final ConcurrentLinkedQueue<OtlpPayload> OTLP_PAYLOADS = new ConcurrentLinkedQueue<>();
	private static final ExecutorService OTLP_RECEIVER_EXECUTOR = Executors.newFixedThreadPool(1);
	private static final AtomicReference<CountDownLatch> OTLP_REQUEST_ENTERED = new AtomicReference<>(
		new CountDownLatch(1));
	private static final HttpServer TIMED_OUT_OTLP_RECEIVER = startTimedOutOtlpReceiver();

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("monitoring_production_test");

	@Container
	static final GenericContainer<?> APP_REDIS = redisContainer();

	@Container
	static final GenericContainer<?> PROBE_REDIS = redisContainer();

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private DependencyHealthMetrics dependencyHealthMetrics;

	@Autowired
	private OtlpMetricsProperties otlpMetricsProperties;

	@LocalServerPort
	private int applicationPort;

	@DynamicPropertySource
	static void monitoringProperties(DynamicPropertyRegistry registry) {
		registry.add("ALBAM_MATE_REDIS_HOST", APP_REDIS::getHost);
		registry.add("ALBAM_MATE_REDIS_PORT", () -> APP_REDIS.getMappedPort(6379));
		registry.add("app.monitoring.dependency-health.poll-interval", () -> "1h");
		registry.add("management.otlp.metrics.export.step", () -> "500ms");
		registry.add("management.otlp.metrics.export.connect-timeout", () -> "50ms");
		registry.add("management.otlp.metrics.export.read-timeout", () -> "50ms");
		registry.add("management.otlp.metrics.export.url",
			() -> "http://127.0.0.1:" + TIMED_OUT_OTLP_RECEIVER.getAddress().getPort() + "/v1/metrics");
	}

	@AfterAll
	static void stopTimedOutOtlpReceiver() throws InterruptedException {
		TIMED_OUT_OTLP_RECEIVER.stop(0);
		OTLP_RECEIVER_EXECUTOR.shutdownNow();
		assertTrue(OTLP_RECEIVER_EXECUTOR.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
	}

	@Test
	void T1_OTLP_read_timeout이_발생해도_대표_제품_요청은_제한_시간_안에_성공한다() throws Exception {
		assertEquals(java.time.Duration.ofMillis(50), otlpMetricsProperties.getConnectTimeout());
		assertEquals(java.time.Duration.ofMillis(50), otlpMetricsProperties.getReadTimeout());
		LoggerContext context = (LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory();
		Logger otlpLogger = context.getLogger("io.micrometer.registry.otlp.OtlpMeterRegistry");
		ListAppender<ILoggingEvent> exports = new ListAppender<>();
		exports.start();
		otlpLogger.addAppender(exports);
		OTLP_REQUEST_ENTERED.set(new CountDownLatch(1));
		meterRegistry.counter(OTLP_PROBE_METRIC).increment();
		try {
			assertTrue(OTLP_REQUEST_ENTERED.get().await(2, TimeUnit.SECONDS));
			long startedAt = System.nanoTime();
			HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + applicationPort + "/api/games?size=1"))
					.GET()
					.build(),
				HttpResponse.BodyHandlers.ofString());

			assertEquals(200, response.statusCode());
			assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 1_000);
			assertTrue(waitForProbeMetricExport());
			assertResourceAttributes(probeMetricRequest().orElseThrow());
			assertTrue(waitForReadTimeoutExport(exports));
		} finally {
			otlpLogger.detachAppender(exports);
			exports.stop();
		}
	}

	private static HttpServer startTimedOutOtlpReceiver() {
		try {
			HttpServer receiver = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			receiver.createContext("/v1/metrics", exchange -> {
				try {
					OTLP_REQUEST_ENTERED.get().countDown();
					OTLP_PAYLOADS.add(new OtlpPayload(exchange.getRequestBody().readAllBytes(),
						exchange.getRequestHeaders().getFirst("Content-Encoding")));
					Thread.sleep(200);
					exchange.sendResponseHeaders(200, -1);
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
				} catch (IOException ignored) {
					// client timeout 뒤의 late response 실패는 timeout 판정 근거로 사용하지 않는다.
				} finally {
					exchange.close();
				}
			});
			receiver.setExecutor(OTLP_RECEIVER_EXECUTOR);
			receiver.start();
			return receiver;
		} catch (IOException exception) {
			throw new IllegalStateException("OTLP read-timeout 수신기를 시작하지 못했습니다", exception);
		}
	}

	private static boolean waitForProbeMetricExport() throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
		while (System.nanoTime() < deadline) {
			if (OTLP_PAYLOADS.stream().anyMatch(MonitoringProductionPostgresTest::containsProbeMetric)) {
				return true;
			}
			Thread.sleep(20);
		}
		return false;
	}

	private static Optional<ExportMetricsServiceRequest> probeMetricRequest() {
		return OTLP_PAYLOADS.stream()
			.map(MonitoringProductionPostgresTest::parseRequest)
			.filter(MonitoringProductionPostgresTest::containsProbeMetric)
			.findFirst();
	}

	private static boolean waitForReadTimeoutExport(ListAppender<ILoggingEvent> exports) throws InterruptedException {
		long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
		while (System.nanoTime() < deadline) {
			synchronized (exports.list) {
				if (exports.list.stream().anyMatch(event -> hasCause(event.getThrowableProxy(),
					"java.net.http.HttpTimeoutException"))) {
					return true;
				}
			}
			Thread.sleep(20);
		}
		return false;
	}

	private static boolean containsProbeMetric(OtlpPayload payload) {
		return containsProbeMetric(parseRequest(payload));
	}

	private static ExportMetricsServiceRequest parseRequest(OtlpPayload payload) {
		try {
			return ExportMetricsServiceRequest.parseFrom(decode(payload));
		} catch (IOException exception) {
			throw new IllegalStateException("OTLP probe payload을 해석하지 못했습니다", exception);
		}
	}

	private static boolean containsProbeMetric(ExportMetricsServiceRequest request) {
		return request.getResourceMetricsList().stream()
			.flatMap(resourceMetrics -> resourceMetrics.getScopeMetricsList().stream())
			.flatMap(scopeMetrics -> scopeMetrics.getMetricsList().stream())
			.anyMatch(metric -> OTLP_PROBE_METRIC.equals(metric.getName()));
	}

	private static void assertResourceAttributes(ExportMetricsServiceRequest request) {
		Map<String, String> actual = request.getResourceMetricsList().stream()
			.flatMap(resourceMetrics -> resourceMetrics.getResource().getAttributesList().stream())
			.collect(java.util.stream.Collectors.toMap(
				attribute -> attribute.getKey(),
				attribute -> attribute.getValue().getStringValue(),
				(first, second) -> first));
		EXPECTED_OTLP_RESOURCE_ATTRIBUTES.forEach((key, value) -> assertEquals(value, actual.get(key)));
	}

	private static byte[] decode(OtlpPayload payload) throws IOException {
		try (InputStream body = "gzip".equalsIgnoreCase(payload.contentEncoding())
			? new GZIPInputStream(new ByteArrayInputStream(payload.body()))
			: new ByteArrayInputStream(payload.body())) {
			return body.readAllBytes();
		}
	}

	private static boolean hasCause(ch.qos.logback.classic.spi.IThrowableProxy exception, String className) {
		for (ch.qos.logback.classic.spi.IThrowableProxy current = exception; current != null; current = current
			.getCause()) {
			if (className.equals(current.getClassName())) {
				return true;
			}
		}
		return false;
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
	void T3_운영_PostgreSQL_context에서_Redis_장애와_복구는_같은_release의_meter와_JSON_log로_연결된다() throws Exception {
		LoggerContext context = (LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory();
		Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		FileAppender<ILoggingEvent> appender = (FileAppender<ILoggingEvent>)root.getAppender("FILE");
		Path logPath = Path.of(appender.getFile());
		long offset = Files.exists(logPath) ? Files.size(logPath) : 0;
		LettuceConnectionFactory probeRedisConnectionFactory = new LettuceConnectionFactory(PROBE_REDIS.getHost(),
			PROBE_REDIS.getMappedPort(6379));
		probeRedisConnectionFactory.setShareNativeConnection(false);
		probeRedisConnectionFactory.afterPropertiesSet();
		probeRedisConnectionFactory.start();
		DependencyHealthSampler probeSampler = new DependencyHealthSampler(dataSource, probeRedisConnectionFactory,
			dependencyHealthMetrics, Duration.ofHours(1));
		try {
			awaitRedisReady(probeRedisConnectionFactory);
			probeSampler.sample();
			assertEquals(1.0, meterRegistry.find("albam.dependency.health").tag("dependency", "redis").gauge().value());

			try {
				stopRedisProcess(PROBE_REDIS);
				probeSampler.sample();
				assertEquals(0.0, meterRegistry.find("albam.dependency.health")
					.tag("dependency", "redis")
					.gauge()
					.value());
			} finally {
				ensureRedisRunning(PROBE_REDIS);
				awaitRedisReady(probeRedisConnectionFactory);
			}
			probeSampler.sample();

			assertEquals(1.0, meterRegistry.find("albam.dependency.health").tag("dependency", "redis").gauge().value());
			byte[] all = Files.readAllBytes(logPath);
			String logOutput = new String(Arrays.copyOfRange(all, Math.toIntExact(offset), all.length),
				StandardCharsets.UTF_8);
			assertTrue(logOutput.contains("\"event\":\"dependency_health_changed\""));
			assertTrue(logOutput.contains("\"failureCode\":\"REDIS_UNAVAILABLE\""));
			assertTrue(logOutput.contains("\"outcome\":\"down\""));
			assertTrue(logOutput.contains("\"outcome\":\"recovered\""));
			assertTrue(logOutput.contains("\"release\":\"test-release\""));
			assertFalse(logOutput.contains("redis unavailable"));
		} finally {
			probeSampler.shutdown();
			probeRedisConnectionFactory.destroy();
		}
	}

	private static GenericContainer<?> redisContainer() {
		return new GenericContainer<>(DockerImageName.parse(REDIS_IMAGE))
			.withExposedPorts(6379)
			.withCommand("sh", "-c", "redis-server --save '' --daemonize yes && tail -f /dev/null")
			.waitingFor(Wait.forListeningPort());
	}

	private static void stopRedisProcess(GenericContainer<?> redis) throws Exception {
		assertEquals(0, redis.execInContainer("redis-cli", "shutdown", "nosave").getExitCode());
		String waitForStoppedCommand = "attempt=0; while [ \"$attempt\" -lt " + REDIS_STOP_MAX_ATTEMPTS
			+ " ]; do if ! redis-cli ping >/dev/null 2>&1; then exit 0; fi; attempt=$((attempt + 1)); sleep 0.1; done; "
			+ "echo 'Redis did not stop after " + REDIS_STOP_MAX_ATTEMPTS + " attempts.' >&2; exit 1";
		org.testcontainers.containers.Container.ExecResult result = redis.execInContainer("sh", "-c",
			waitForStoppedCommand);
		assertEquals(0, result.getExitCode(), "Redis 종료 대기 실패: " + result.getStderr());
	}

	private static void ensureRedisRunning(GenericContainer<?> redis) throws Exception {
		if (redisRespondsToPing(redis)) {
			return;
		}
		org.testcontainers.containers.Container.ExecResult start = redis.execInContainer(
			"redis-server", "--save", "", "--daemonize", "yes");
		if (start.getExitCode() != 0 && !redisRespondsToPing(redis)) {
			throw new AssertionError("Redis 재기동 실패: " + start.getStderr());
		}
		String waitForPongCommand = "attempt=0; while [ \"$attempt\" -lt " + REDIS_STARTUP_MAX_ATTEMPTS
			+ " ]; do if redis-cli ping | grep -qx PONG; then exit 0; fi; attempt=$((attempt + 1)); sleep 0.1; done; "
			+ "echo 'Redis did not return PONG after " + REDIS_STARTUP_MAX_ATTEMPTS + " attempts.' >&2; exit 1";
		org.testcontainers.containers.Container.ExecResult result = redis.execInContainer("sh", "-c",
			waitForPongCommand);
		assertEquals(0, result.getExitCode(), "Redis 시작 대기 실패: " + result.getStderr());
	}

	private static boolean redisRespondsToPing(GenericContainer<?> redis) throws Exception {
		return redis.execInContainer("redis-cli", "ping").getExitCode() == 0;
	}

	private static void awaitRedisReady(LettuceConnectionFactory redisConnectionFactory) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadline) {
			try (RedisConnection connection = redisConnectionFactory.getConnection()) {
				if ("PONG".equals(connection.ping())) {
					return;
				}
			} catch (RuntimeException ignored) {
				// 자동 재연결이 새 native connection을 준비하는 동안만 poll한다.
			}
			Thread.sleep(100);
		}
		throw new AssertionError("Redis 복구 뒤 probe Redis connection이 5초 안에 준비되지 않았습니다");
	}

	@Test
	void T3_structured_log_allowlist_excludes_forbidden_fields_from_all_sinks() throws Exception {
		LoggerContext context = (LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory();
		Logger logger = context.getLogger(getClass());
		Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		Object file = root.getAppender("FILE");
		assertEquals(true, file instanceof FileAppender<?>);
		FileAppender<ILoggingEvent> appender = (FileAppender<ILoggingEvent>)file;
		assertEquals(true, appender.isStarted());
		Path logPath = Path.of(appender.getFile());
		long offset = Files.exists(logPath) ? Files.size(logPath) : 0;
		assertFalse(productionExcludedJsonFields().contains("roomid"));
		assertFalse(productionExcludedJsonFields().contains("messageid"));
		assertFalse(productionExcludedJsonFields().contains("sourceeventid"));
		putContractMdc("monitoring_contract_test");
		try {
			logger.warn("monitoring_contract_test");
		} finally {
			org.slf4j.MDC.clear();
		}
		byte[] all = Files.readAllBytes(logPath);
		String json = new String(Arrays.copyOfRange(all, Math.toIntExact(offset), all.length), StandardCharsets.UTF_8);
		assertStructuredLogContract(json, "monitoring_contract_test");
	}

	@Test
	void T3_console_sink_uses_the_actual_structured_log_output(CapturedOutput output) {
		Logger logger = ((LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory()).getLogger(getClass());
		putContractMdc("monitoring_console_contract_test");
		try {
			logger.warn("monitoring_console_contract_test");
		} finally {
			org.slf4j.MDC.clear();
		}
		String consoleJson = (output.getOut() + output.getErr()).lines()
			.filter(line -> line.contains("\"event\":\"monitoring_console_contract_test\""))
			.reduce((first, second) -> second)
			.orElseThrow();
		assertStructuredLogContract(consoleJson, "monitoring_console_contract_test");
	}

	@Test
	void T3_실제_업무_log_호출은_stdout과_file_JSON에_event와_허용_field를_남긴다(CapturedOutput output) throws Exception {
		LoggerContext context = (LoggerContext)org.slf4j.LoggerFactory.getILoggerFactory();
		Logger root = context.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
		FileAppender<ILoggingEvent> appender = (FileAppender<ILoggingEvent>)root.getAppender("FILE");
		Path logPath = Path.of(appender.getFile());
		long offset = Files.exists(logPath) ? Files.size(logPath) : 0;

		RoomOptimisticLockRetrier retrier = new RoomOptimisticLockRetrier();
		org.junit.jupiter.api.Assertions.assertThrows(BusinessException.class,
			() -> retrier.execute(() -> {
				throw new OptimisticLockException();
			}, "room_update_retry", 17L));

		byte[] all = Files.readAllBytes(logPath);
		String fileJson = new String(Arrays.copyOfRange(all, Math.toIntExact(offset), all.length),
			StandardCharsets.UTF_8);
		assertTrue(fileJson.contains("\"event\":\"room_update_retry\""));
		assertTrue(fileJson.contains("\"roomId\":17"));
		assertTrue(fileJson.contains("\"attempt\":3"));
		assertTrue(fileJson.contains("\"useCase\":\"ROOM_UPDATE\""));
		String consoleJson = (output.getOut() + output.getErr()).lines()
			.filter(line -> line.contains("\"event\":\"room_update_retry\""))
			.reduce((first, second) -> second)
			.orElseThrow();
		assertTrue(consoleJson.contains("\"roomId\":17"));
		assertTrue(consoleJson.contains("\"attempt\":3"));
		assertTrue(consoleJson.contains("\"useCase\":\"ROOM_UPDATE\""));
		assertFalse(consoleJson.contains("\"message\""));
	}

	private static void putContractMdc(String event) {
		org.slf4j.MDC.put("event", event);
		ALLOWED_MDC_FIELDS.forEach(org.slf4j.MDC::put);
		FORBIDDEN_MDC_KEYS.forEach(key -> org.slf4j.MDC.put(key, "sentinel-" + key));
	}

	private static void assertStructuredLogContract(String json, String event) {
		assertFalse(json.isBlank());
		assertTrue(json.strip().startsWith("{"));
		assertTrue(json.contains("\"event\":\"" + event + "\""));
		assertTrue(json.contains("\"environment\":\"test\""));
		assertTrue(json.contains("\"stackId\":\"issue-730\""));
		assertTrue(json.contains("\"service\":\"albam-mate\""));
		assertTrue(json.contains("\"role\":\"app1\""));
		assertTrue(json.contains("\"instanceId\":\"postgres-test\""));
		assertTrue(json.contains("\"release\":\"test-release\""));
		ALLOWED_MDC_FIELDS.forEach((key, value) -> {
			assertTrue(json.contains("\"" + key + "\":\"" + value + "\""));
		});
		FORBIDDEN_MDC_KEYS.forEach(key -> {
			assertFalse(json.contains("\"" + key + "\""));
			assertFalse(json.contains("sentinel-" + key));
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
