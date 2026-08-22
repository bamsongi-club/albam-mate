package cloud.bamsongi.albammate.global.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

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
		assertEquals("127.0.0.1", springEnvironment(app1).get("MANAGEMENT_SERVER_ADDRESS"));
		assertEquals("127.0.0.1", springEnvironment(app2).get("MANAGEMENT_SERVER_ADDRESS"));
		assertTrue(app1.contains("http://127.0.0.1:9090/actuator/health"));
		assertTrue(app2.contains("http://127.0.0.1:9090/actuator/health"));
	}

	@Test
	void T1_배포_예시는_각_App의_관측_식별자와_UID_10001_로그_디렉터리_준비를_명시한다() {
		String environmentExample = read(".env.production.example");
		String deploymentGuide = read("docs/guides/AWS_MULTI_INSTANCE_INFRASTRUCTURE.md");
		String verifier = read("scripts/verify-docker-deployment.mjs");
		int appTableStart = deploymentGuide.indexOf("| App1 |");
		String appEnvironmentTable = deploymentGuide.substring(
			appTableStart, deploymentGuide.indexOf("| PostgreSQL |", appTableStart));

		assertTrue(
			environmentExample.contains("ALBAM_MATE_OTLP_METRICS_URL=http://host.docker.internal:4318/v1/metrics"));
		assertTrue(environmentExample.contains("ALBAM_MATE_ENVIRONMENT=production"));
		assertTrue(environmentExample.contains("ALBAM_MATE_STACK_ID=albam-mate-production"));
		assertTrue(environmentExample.contains("ALBAM_MATE_INSTANCE_ID=app1"));
		assertTrue(environmentExample.contains("ALBAM_MATE_INSTANCE_ID=app2"));
		assertTrue(environmentExample.contains("ALBAM_MATE_OBSERVABILITY_LOG_PATH=/var/log/albam-mate"));
		for (String required : new String[] {
			"ALBAM_MATE_ENVIRONMENT", "ALBAM_MATE_STACK_ID", "ALBAM_MATE_INSTANCE_ID",
			"ALBAM_MATE_OBSERVABILITY_LOG_PATH"
		}) {
			assertTrue(appEnvironmentTable.contains(required));
		}
		assertTrue(deploymentGuide.contains("install -d -o 10001 -g 10001 -m 0750 /var/log/albam-mate"));
		assertTrue(verifier.contains("assertSpringObservabilityEnvironment"));
		assertTrue(verifier.contains("'app1', 'app1'"));
		assertTrue(verifier.contains("assertApp2ProductionConfig"));
		assertTrue(verifier.contains("'app2', 'app2'"));
		assertTrue(verifier.contains("assertObservabilityLogBindMount"));
		assertTrue(verifier.contains("assertObservabilityLogBindWritableByRuntimeUser"));
	}

	@Test
	void T2_테스트_기본값은_probe를_끄고_production은_app_redis_정책을_사용한다() {
		String production = read("src/main/resources/application-production.yml");
		String h2 = read("src/test/resources/application.yml");
		String postgres = read("src/postgresTest/resources/application.yml");
		Map<String, Object> productionRoot = new Yaml().load(production);
		Map<String, Object> redis = map(map(map(productionRoot.get("spring")).get("data")).get("redis"));
		Map<String, Object> productionDependencyHealth = map(
			map(map(productionRoot.get("app")).get("monitoring")).get("dependency-health"));
		Map<String, Object> h2Root = new Yaml().load(h2);
		Map<String, Object> h2DependencyHealth = map(
			map(map(h2Root.get("app")).get("monitoring")).get("dependency-health"));
		Map<String, Object> postgresRoot = new Yaml().load(postgres);
		Map<String, Object> postgresDependencyHealth = map(
			map(map(postgresRoot.get("app")).get("monitoring")).get("dependency-health"));

		assertFalse(redis.containsKey("connect-timeout"));
		assertFalse(redis.containsKey("timeout"));
		assertEquals(true, productionDependencyHealth.get("enabled"));
		assertEquals(false, h2DependencyHealth.get("enabled"));
		assertEquals(false, postgresDependencyHealth.get("enabled"));
	}

	@Test
	void T3_배포_식별자와_구조화_로그_sink에는_허용된_고정값만_쓴다() {
		String production = read("src/main/resources/application-production.yml");
		String app1 = read("compose.production.yml");
		String app2 = read("compose.app2.yml");
		String monitoringOperations = read("docs/guides/MONITORING_OPERATIONS.md");
		String customizer = read(
			"src/main/java/cloud/bamsongi/albammate/monitoring/MonitoringStructuredLoggingCustomizer.java");
		String recorder = read(
			"src/main/java/cloud/bamsongi/albammate/notification/relay/NotificationRelayFailureRecorder.java");
		String logging = production.substring(production.indexOf("logging:"), production.indexOf("app:"));
		Map<String, Object> productionRoot = new Yaml().load(production);
		Map<String, Object> management = map(productionRoot.get("management"));
		Map<String, Object> metrics = map(management.get("metrics"));
		Map<String, Object> export = map(map(map(management.get("otlp")).get("metrics")).get("export"));
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
		assertTrue(logging.contains("total-size-cap: 40MB"));
		assertTrue(app1.contains("/var/log/albam-mate"));
		assertTrue(app2.contains("/var/log/albam-mate"));
		assertEquals(expectedResourceAttributes(), openTelemetryResourceAttributes);
		assertFalse(metrics.containsKey("tags"));
		assertFalse(export.containsKey("resource-attributes"));
		assertEquals("${ALBAM_MATE_OTLP_METRICS_STEP:5m}", export.get("step"));
		assertEquals("${ALBAM_MATE_ROLE:-app1}", springEnvironment(app1).get("ALBAM_MATE_ROLE"));
		assertEquals("${ALBAM_MATE_ROLE:-app2}", springEnvironment(app2).get("ALBAM_MATE_ROLE"));
		assertTrue(springEnvironment(app1).containsKey("ALBAM_MATE_RELEASE"));
		assertTrue(springEnvironment(app2).containsKey("ALBAM_MATE_RELEASE"));
		assertFalse(logging.contains("requestId: ${"));
		assertFalse(logging.contains("userId: ${"));
		assertFalse(logging.contains("roomId: ${"));
		assertFalse(logging.contains("messageId: ${"));
		assertTrue(monitoringOperations
			.contains("`notification_outbox_relay_event_failed` 전용 boolean `deterministicFailure`"));
		assertTrue(monitoringOperations.contains("결정적 또는 보존 기간 만료"));
		assertTrue(customizer.contains("\"deterministicfailure\""));
		assertTrue(recorder.contains("addKeyValue(\"deterministicFailure\""));
	}

	@Test
	void T4_OPS04_정상_OTLP_허용목록은_비용_gate의_17개_meter만_전송한다() {
		Map<String, Object> productionRoot = new Yaml().load(read("src/main/resources/application-production.yml"));
		Map<String, Object> enabled = map(map(productionRoot.get("management")).get("metrics"))
			.get("enable") instanceof Map<?, ?> values ? map(values) : Map.of();
		String monitoringOperations = read("docs/guides/MONITORING_OPERATIONS.md");

		assertEquals(Set.of(
			"http.server.requests", "jvm.memory.used", "jvm.memory.max", "tomcat.threads.busy",
			"tomcat.threads.config.max", "hikaricp.connections.pending", "hikaricp.connections.max",
			"albam.dependency.health",
			"notification.relay.events", "notification.relay.delivery.duration",
			"notification.relay.oldest.processable.age", "chat.message.operations", "room.waitlist.operations",
			"assistant.usage.events", "assistant.usage.tokens", "assistant.usage.latency",
			"assistant.cost.warning.events"),
			enabled.keySet().stream().filter(key -> !key.equals("all")).collect(java.util.stream.Collectors.toSet()));
		assertTrue(monitoringOperations.contains("정상 production OTLP 허용 목록은 아래 17개 meter만"));
	}

	@Test
	void T5_OPS04_앱_입력은_두_instance에서_AI_18_series로_재현된다() {
		String monitoringOperations = read("docs/guides/MONITORING_OPERATIONS.md");

		assertTrue(monitoringOperations.contains("두 App instance에서 AI meter는 최대 18개 series"));
		assertTrue(monitoringOperations.contains("기존 계정 기준선과 P2 증분 정적 상한"));
		assertTrue(monitoringOperations.contains("`NO_OBSERVATION`"));
	}

	@Test
	void T6_OPS04_상태는_배포전_비용_gate까지만_기록하고_실측을_대신하지_않는다() {
		String readme = read("docs/p2/README.md");
		String monitoringOperations = read("docs/guides/MONITORING_OPERATIONS.md");

		assertTrue(readme.contains("앱 normal allowlist 17개와 두 App AI 18 series"));
		assertTrue(readme.contains("미배포 | 미측정"));
		assertTrue(monitoringOperations.contains("#823 앱 배포·#824 AI 운영 실측·#872"));
	}

	@Test
	void T3_OPS01_상태는_구현과_자동검증과_AWS실측완료로_표시한다() {
		String readme = read("docs/p2/README.md");

		assertTrue(readme.contains("OPS-01·OPS-02 구현·자동 검증·임시 AWS 실측·철거 완료"));
		assertTrue(readme.contains("`AC1`~`AC7` 실측 완료"));
		assertFalse(readme.contains("OPS-01 부분 구현·부분 검증"));
	}

	@Test
	void T3_OPS02는_앱과_인프라_구현과_자동_검증과_AWS실측완료로_표시한다() {
		String readme = read("docs/p2/README.md");
		String monitoringOperations = read("docs/guides/MONITORING_OPERATIONS.md");
		String ops02Row = readme.lines()
			.filter(line -> line.startsWith("| 지연·포화 |"))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("OPS-02 상태 행을 찾지 못했습니다"));
		String gcPauseRow = monitoringOperations.lines()
			.filter(line -> line.startsWith("| `jvm.gc.pause` |"))
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("jvm.gc.pause 상태 행을 찾지 못했습니다"));

		assertTrue(readme.contains(
			"OPS-01·OPS-02 구현·자동 검증·임시 AWS 실측·철거 완료, OPS-02 외부 API·AI는 해당 기능 미배포로 조건부 제외"));
		assertTrue(readme.contains(
			"| 서비스 생존·연결 | [`OPS-01`](monitoring.md#ops-01-서비스-생존과-연결-상태) | 계약 준비 완료 | 구현 완료 | 자동 검증 완료 | 임시 AWS 검증 배포·철거 완료 | `AC1`~`AC7` 실측 완료 |"));
		assertTrue(ops02Row.contains("앱·인프라 구현 완료, 미배포 외부 API·AI 조건부 제외"));
		assertTrue(ops02Row.contains("앱 CI·인프라 수집·query·주입·복구·teardown 자동 검증 완료"));
		assertTrue(ops02Row.contains("고정 SHA 임시 AWS 검증 배포·철거 완료"));
		assertTrue(ops02Row.contains("`AC1`~`AC4`, `AC6` 실측 완료, `AC5` 조건부 제외"));
		assertTrue(monitoringOperations.contains(
			"production histogram 설정·OTLP export 자동 검증 완료, CloudWatch 배포·실측 필요"));
		assertTrue(monitoringOperations.contains(
			"production 설정·OTLP export 자동 검증 완료, CloudWatch 배포·실측 필요"));
		assertEquals(
			"| `jvm.gc.pause` | timer·Micrometer JVM binder | `action`, `cause`의 라이브러리 유한값 | 5분 count·p95 | meter 기반 있음·OTLP export 검증 필요, CloudWatch 배포·실측 필요 |",
			gcPauseRow);
		assertFalse(readme.contains("OPS-01 공개 앱 범위와 OPS-02 앱 HTTP·JVM·Tomcat·Hikari·Nginx timing 원천 범위 부분 구현·부분 검증"));
		assertFalse(
			readme.contains("| 지연·포화 | [`OPS-02`](monitoring.md#ops-02-지연과-포화) | 계약 준비 완료 | 구현 완료 | 자동 검증 완료 |"));
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

	private Map<String, Object> expectedResourceAttributes() {
		return Map.of(
			"environment", "${ALBAM_MATE_ENVIRONMENT}",
			"stackId", "${ALBAM_MATE_STACK_ID}",
			"service", "albam-mate",
			"role", "${ALBAM_MATE_ROLE}",
			"instanceId", "${ALBAM_MATE_INSTANCE_ID}",
			"release", "${ALBAM_MATE_RELEASE}");
	}
}
