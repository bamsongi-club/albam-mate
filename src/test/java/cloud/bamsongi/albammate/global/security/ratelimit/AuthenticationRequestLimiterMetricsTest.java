package cloud.bamsongi.albammate.global.security.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class AuthenticationRequestLimiterMetricsTest {

	@Test
	void T10_새_관측이_없는_한_window_뒤_gauge는_0으로_복귀한다() {
		AtomicLong ticker = new AtomicLong();
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		AuthenticationRequestLimiterMetrics metrics = new AuthenticationRequestLimiterMetrics(
			meterRegistry,
			ticker::get);
		metrics.recordUsage("ip", 1, 10, Duration.ofSeconds(10));

		assertEquals(0.1, meterRegistry.get("auth.request.limiter.capacity.utilization")
			.tag("family", "ip").gauge().value());
		ticker.set(Duration.ofSeconds(10).toNanos());

		assertEquals(0.0, meterRegistry.get("auth.request.limiter.capacity.utilization")
			.tag("family", "ip").gauge().value());
	}
}
