package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
	"ALBAM_MATE_OTLP_METRICS_URL=http://127.0.0.1:1/v1/metrics"
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
}
