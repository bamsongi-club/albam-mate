package cloud.bamsongi.albammate.monitoring;

import java.sql.Connection;
import java.sql.SQLException;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;

/** 업무 transaction 밖에서 의존성 연결 상태만 제한된 주기로 표본화한다. */
@Component
@ConditionalOnProperty(prefix = "app.monitoring.dependency-health", name = "enabled", havingValue = "true", matchIfMissing = true)
class DependencyHealthSampler {

	private final DataSource dataSource;
	private final RedisConnectionFactory redisConnectionFactory;
	private final DependencyHealthMetrics metrics;

	DependencyHealthSampler(DataSource dataSource, RedisConnectionFactory redisConnectionFactory,
		MeterRegistry meterRegistry) {
		this.dataSource = dataSource;
		this.redisConnectionFactory = redisConnectionFactory;
		metrics = new DependencyHealthMetrics(meterRegistry);
	}

	void sample() {
		metrics.recordPostgresql(postgresqlIsUp());
		metrics.recordRedis(redisIsUp());
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
}
