package cloud.bamsongi.albammate.monitoring;

import java.util.concurrent.atomic.AtomicInteger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** PostgreSQL과 Redis의 연결 상태를 유한한 dimension의 별도 gauge로 기록한다. */
public final class DependencyHealthMetrics {

	private final AtomicInteger postgresqlUp = new AtomicInteger();
	private final AtomicInteger redisUp = new AtomicInteger();

	public DependencyHealthMetrics(MeterRegistry meterRegistry) {
		Gauge.builder("albam.dependency.health", postgresqlUp, AtomicInteger::get)
			.tag("dependency", "postgresql")
			.register(meterRegistry);
		Gauge.builder("albam.dependency.health", redisUp, AtomicInteger::get)
			.tag("dependency", "redis")
			.register(meterRegistry);
	}

	public void recordPostgresql(boolean up) {
		postgresqlUp.set(up ? 1 : 0);
	}

	public void recordRedis(boolean up) {
		redisUp.set(up ? 1 : 0);
	}
}
