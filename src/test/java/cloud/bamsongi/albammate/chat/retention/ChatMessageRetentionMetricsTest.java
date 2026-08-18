package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class ChatMessageRetentionMetricsTest {

	@Test
	void T2_단발과_반복_실패뒤_다음_성공은_한번의_복구로_기록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		ChatMessageRetentionMetrics metrics = new ChatMessageRetentionMetrics(registry);

		metrics.recordExecutionFailure();
		metrics.recordExecutionFailure();
		metrics.recordCompleted(new ChatMessageRetentionCoordinator.RetentionRunSummary(0, 0, 0, 0, 10, false));
		metrics.recordCompleted(new ChatMessageRetentionCoordinator.RetentionRunSummary(0, 0, 0, 0, 10, false));

		assertEquals(2.0, registry.find("chat.message.retention.failures").counter().count());
		assertEquals(1.0, registry.find("chat.message.retention.recoveries").counter().count());
	}
}
