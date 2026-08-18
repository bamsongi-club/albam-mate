package cloud.bamsongi.albammate.monitoring;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/** 업무 transaction 밖에서 의존성 연결 상태만 제한된 주기로 표본화한다. */
@Component
@ConditionalOnProperty(prefix = "app.monitoring.dependency-health", name = "enabled", havingValue = "true", matchIfMissing = true)
class DependencyHealthSampler {

	private final DataSource dataSource;
	private final RedisConnectionFactory redisConnectionFactory;
	private final DependencyHealthMetrics metrics;
	private final Duration probeTimeout;
	private final java.util.concurrent.ExecutorService postgresqlProbeExecutor = Executors
		.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "dependency-health-postgresql-probe");
			thread.setDaemon(true);
			return thread;
		});
	private final java.util.concurrent.ExecutorService redisProbeExecutor = Executors
		.newSingleThreadExecutor(runnable -> {
			Thread thread = new Thread(runnable, "dependency-health-redis-probe");
			thread.setDaemon(true);
			return thread;
		});

	DependencyHealthSampler(DataSource dataSource, RedisConnectionFactory redisConnectionFactory,
		DependencyHealthMetrics metrics, @Value("${app.monitoring.dependency-health.poll-interval:10s}")
		Duration pollInterval) {
		this.dataSource = dataSource;
		this.redisConnectionFactory = redisConnectionFactory;
		this.metrics = metrics;
		probeTimeout = boundedProbeTimeout(pollInterval);
	}

	void sample() {
		Future<ProbeResult> postgresqlProbe = postgresqlProbeExecutor.submit(
			() -> ProbeResult.from(postgresqlIsUp()));
		Future<ProbeResult> redisProbe = redisProbeExecutor.submit(() -> ProbeResult.from(redisIsUp()));
		recordKnown(awaitProbe(redisProbe), metrics::recordRedis);
		recordKnown(awaitProbe(postgresqlProbe), metrics::recordPostgresql);
	}

	@PreDestroy
	void shutdown() {
		postgresqlProbeExecutor.shutdownNow();
		redisProbeExecutor.shutdownNow();
	}

	private void recordKnown(ProbeResult result, Consumer<Boolean> recorder) {
		if (result == ProbeResult.UP) {
			recorder.accept(true);
		} else if (result == ProbeResult.DOWN) {
			recorder.accept(false);
		}
	}

	private ProbeResult awaitProbe(Future<ProbeResult> probe) {
		try {
			return probe.get(probeTimeout.toNanos(), TimeUnit.NANOSECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			probe.cancel(true);
			return ProbeResult.UNKNOWN;
		} catch (java.util.concurrent.TimeoutException | java.util.concurrent.ExecutionException exception) {
			probe.cancel(true);
			return ProbeResult.UNKNOWN;
		}
	}

	private boolean postgresqlIsUp() {
		try (Connection connection = dataSource.getConnection()) {
			return connection.isValid(1);
		} catch (SQLException exception) {
			return false;
		}
	}

	private boolean redisIsUp() {
		try (RedisConnection connection = redisConnectionFactory.getConnection()) {
			return "PONG".equals(connection.ping());
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private static Duration boundedProbeTimeout(Duration pollInterval) {
		if (pollInterval.isNegative() || pollInterval.isZero()) {
			throw new IllegalArgumentException("의존성 상태 표본화 주기는 양수여야 합니다.");
		}
		long pollIntervalNanos = pollInterval.toNanos();
		if (pollIntervalNanos < 3) {
			throw new IllegalArgumentException("의존성 상태 표본화 주기는 두 probe timeout보다 길어야 합니다.");
		}
		long timeoutNanos = Math.min(Duration.ofMillis(250).toNanos(), (pollIntervalNanos - 1) / 2);
		return Duration.ofNanos(timeoutNanos);
	}

	private enum ProbeResult {
		UP,
		DOWN,
		UNKNOWN;

		private static ProbeResult from(boolean up) {
			return up ? UP : DOWN;
		}
	}
}
