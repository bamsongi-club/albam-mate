package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ChatMessageRetentionMetricsTest {

	@Test
	void T2_서로_다른_인스턴스와_재시작_경계에서_앱이_복구를_합성하지_않는다() {
		SimpleMeterRegistry beforeRestartRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metricsBeforeRestart = new ChatMessageRetentionMetrics(beforeRestartRegistry);
		SimpleMeterRegistry afterRestartRegistry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metricsAfterRestart = new ChatMessageRetentionMetrics(afterRestartRegistry);

		metricsBeforeRestart.recordExecutionFailure();
		metricsAfterRestart.recordCompleted(
			new ChatMessageRetentionCoordinator.RetentionRunSummary(0, 0, 0, 0, 10, false));

		assertEquals(1.0, beforeRestartRegistry.find("chat.message.retention.failures").counter().count());
		assertNull(beforeRestartRegistry.find("chat.message.retention.recoveries").counter());
		assertNull(afterRestartRegistry.find("chat.message.retention.recoveries").counter());
	}
}
