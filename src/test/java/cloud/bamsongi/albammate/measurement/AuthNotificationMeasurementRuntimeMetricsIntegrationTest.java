package cloud.bamsongi.albammate.measurement;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import io.micrometer.core.instrument.MeterRegistry;

@SpringBootTest(properties = "app.measurement.auth-notification.enabled=true")
class AuthNotificationMeasurementRuntimeMetricsIntegrationTest {

	@Autowired
	private DataSource dataSource;

	@Autowired
	private MeterRegistry meterRegistry;

	@Autowired
	private AuthNotificationMeasurementRecorder measurementRecorder;

	@Test
	void 계측을_활성화해도_JVM_process_system_Hikari_metric과_recorder를_모두_등록한다() throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			assertTrue(connection.isValid(1));
		}

		assertNotNull(measurementRecorder);
		assertGaugeRegistered("jvm.memory.used");
		assertGaugeRegistered("process.uptime");
		assertGaugeRegistered("system.cpu.usage");
		assertGaugeRegistered("hikaricp.connections.active");
	}

	private void assertGaugeRegistered(String metricName) {
		assertNotNull(meterRegistry.find(metricName).gauge(), () -> "missing runtime metric: " + metricName);
	}
}
