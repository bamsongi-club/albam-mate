package cloud.bamsongi.albammate.monitoring;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;

/** PostgreSQL과 Redis의 연결 상태를 유한한 dimension의 별도 gauge로 기록한다. */
@Slf4j
@Component
public final class DependencyHealthMetrics {

	private final AtomicInteger postgresqlUp = new AtomicInteger(-1);
	private final AtomicInteger redisUp = new AtomicInteger(-1);
	private final AtomicInteger previousPostgresqlUp = new AtomicInteger(-1);
	private final AtomicInteger previousRedisUp = new AtomicInteger(-1);

	public DependencyHealthMetrics(MeterRegistry meterRegistry) {
		Gauge.builder("albam.dependency.health", postgresqlUp, DependencyHealthMetrics::gaugeValue)
			.tag("dependency", "postgresql")
			.register(meterRegistry);
		Gauge.builder("albam.dependency.health", redisUp, DependencyHealthMetrics::gaugeValue)
			.tag("dependency", "redis")
			.register(meterRegistry);
	}

	public void recordPostgresql(boolean up) {
		record("postgresql", up, postgresqlUp, previousPostgresqlUp, "POSTGRESQL_UNAVAILABLE");
	}

	public void recordRedis(boolean up) {
		record("redis", up, redisUp, previousRedisUp, "REDIS_UNAVAILABLE");
	}

	private void record(
		String dependency,
		boolean up,
		AtomicInteger currentState,
		AtomicInteger previousState,
		String unavailableFailureCode) {
		int nextState = up ? 1 : 0;
		currentState.set(nextState);
		int previous = previousState.getAndSet(nextState);
		if (previous == nextState || previous == -1 && up) {
			return;
		}
		if (up) {
			log.atInfo().addKeyValue("event", "dependency_health_changed")
				.addKeyValue("dependency", dependency).addKeyValue("outcome", "recovered")
				.log("dependency health recovered");
			return;
		}
		log.atWarn().addKeyValue("event", "dependency_health_changed")
			.addKeyValue("dependency", dependency).addKeyValue("outcome", "down")
			.addKeyValue("failureCode", unavailableFailureCode).log("dependency health changed");
	}

	private static double gaugeValue(AtomicInteger state) {
		return state.get() < 0 ? Double.NaN : state.get();
	}
}
