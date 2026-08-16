package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class DependencyHealthMetricsTest {

	@Test
	void T2_PostgreSQL과_Redis의_상태를_서로_독립된_유한_dimension으로_기록한다() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		Class<?> metricsType = Class.forName("cloud.bamsongi.albammate.monitoring.DependencyHealthMetrics");
		Object metrics = metricsType.getConstructor(io.micrometer.core.instrument.MeterRegistry.class)
			.newInstance(registry);

		metricsType.getMethod("recordPostgresql", boolean.class).invoke(metrics, true);
		metricsType.getMethod("recordRedis", boolean.class).invoke(metrics, false);

		assertEquals(1.0, gaugeValue(registry, "postgresql"));
		assertEquals(0.0, gaugeValue(registry, "redis"));
		metricsType.getMethod("recordPostgresql", boolean.class).invoke(metrics, false);
		assertEquals(0.0, gaugeValue(registry, "postgresql"));
		assertEquals(0.0, gaugeValue(registry, "redis"));
	}

	private double gaugeValue(SimpleMeterRegistry registry, String dependency) {
		return registry.find("albam.dependency.health")
			.tag("dependency", dependency)
			.gauge()
			.value();
	}

	@Test
	void T2_표본화는_PostgreSQL과_Redis_장애를_서로_독립적으로_표시한다() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		DataSource dataSource = mock(DataSource.class);
		RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
		Connection postgresql = mock(Connection.class);
		RedisConnection redis = mock(RedisConnection.class);
		when(dataSource.getConnection()).thenReturn(postgresql);
		when(redisConnectionFactory.getConnection()).thenReturn(redis);
		DependencyHealthSampler sampler = new DependencyHealthSampler(
			dataSource, redisConnectionFactory, registry, java.time.Duration.ofSeconds(10));

		when(postgresql.isValid(1)).thenReturn(true);
		when(redis.ping()).thenReturn("PONG");
		sampler.sample();
		assertEquals(1.0, gaugeValue(registry, "postgresql"));
		assertEquals(1.0, gaugeValue(registry, "redis"));

		when(postgresql.isValid(1)).thenReturn(true);
		when(redis.ping()).thenThrow(new IllegalStateException("redis unavailable"));
		sampler.sample();
		assertEquals(1.0, gaugeValue(registry, "postgresql"));
		assertEquals(0.0, gaugeValue(registry, "redis"));

		when(postgresql.isValid(1)).thenThrow(new java.sql.SQLException("postgresql unavailable"));
		doReturn("PONG").when(redis).ping();
		sampler.sample();
		assertEquals(0.0, gaugeValue(registry, "postgresql"));
		assertEquals(1.0, gaugeValue(registry, "redis"));
		sampler.shutdown();
	}

	@Test
	void T2_PostgreSQL_probe가_지연되어도_Redis_gauge는_제한시간_안에_갱신한다() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		DataSource dataSource = mock(DataSource.class);
		RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
		RedisConnection redis = mock(RedisConnection.class);
		when(dataSource.getConnection()).thenAnswer(invocation -> {
			Thread.sleep(1_000);
			throw new java.sql.SQLException("postgresql pool acquisition timed out");
		});
		when(redisConnectionFactory.getConnection()).thenReturn(redis);
		when(redis.ping()).thenReturn("PONG");
		DependencyHealthSampler sampler = new DependencyHealthSampler(
			dataSource, redisConnectionFactory, registry, java.time.Duration.ofSeconds(10));

		long startedAt = System.nanoTime();
		sampler.sample();

		assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500);
		assertEquals(0.0, gaugeValue(registry, "postgresql"));
		assertEquals(1.0, gaugeValue(registry, "redis"));
		sampler.shutdown();
	}

	@Test
	void T2_연속된_PostgreSQL_pool_지연도_Redis_probe_slot을_점유하지_않는다() throws Exception {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		DataSource dataSource = mock(DataSource.class);
		RedisConnectionFactory redisConnectionFactory = mock(RedisConnectionFactory.class);
		RedisConnection redis = mock(RedisConnection.class);
		java.util.concurrent.CountDownLatch releasePostgresqlProbes = new java.util.concurrent.CountDownLatch(1);
		when(dataSource.getConnection()).thenAnswer(invocation -> {
			while (releasePostgresqlProbes.getCount() > 0) {
				try {
					releasePostgresqlProbes.await(10, java.util.concurrent.TimeUnit.MILLISECONDS);
				} catch (InterruptedException ignored) {
					// Hikari 획득이 interrupt에 즉시 반응하지 않는 경계를 재현한다.
				}
			}
			throw new java.sql.SQLException("postgresql pool acquisition timed out");
		});
		when(redisConnectionFactory.getConnection()).thenReturn(redis);
		when(redis.ping()).thenReturn("PONG");
		DependencyHealthSampler sampler = new DependencyHealthSampler(
			dataSource, redisConnectionFactory, registry, java.time.Duration.ofSeconds(10));

		try {
			sampler.sample();
			long startedAt = System.nanoTime();
			sampler.sample();

			assertTrue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500);
			assertEquals(1.0, gaugeValue(registry, "redis"));
		} finally {
			releasePostgresqlProbes.countDown();
			sampler.shutdown();
		}
	}

	@Test
	void T2_두_dependency_probe_timeout의_합은_아주_짧은_poll_interval보다도_엄격히_짧다() throws Exception {
		java.lang.reflect.Method timeout = DependencyHealthSampler.class
			.getDeclaredMethod("boundedProbeTimeout", java.time.Duration.class);
		timeout.setAccessible(true);

		for (java.time.Duration pollInterval : java.util.List.of(java.time.Duration.ofMillis(1),
			java.time.Duration.ofMillis(2))) {
			java.time.Duration probeTimeout = (java.time.Duration)timeout.invoke(null, pollInterval);
			assertTrue(probeTimeout.multipliedBy(2).compareTo(pollInterval) < 0);
		}
	}

	@Test
	void T2_의존성_표본화는_업무용_기본_scheduler가_아닌_전용_단일_스레드에_등록된다() throws Exception {
		Class<?> configurationType = Class
			.forName("cloud.bamsongi.albammate.monitoring.DependencyHealthSchedulingConfiguration");
		ConditionalOnProperty conditional = configurationType.getAnnotation(ConditionalOnProperty.class);
		assertEquals("app.monitoring.dependency-health", conditional.prefix());
		assertEquals("enabled", conditional.name()[0]);
		assertEquals("true", conditional.havingValue());
		assertEquals(true, conditional.matchIfMissing());
		Object configuration = configurationType.getDeclaredConstructor().newInstance();
		ThreadPoolTaskScheduler scheduler = (ThreadPoolTaskScheduler)configurationType
			.getDeclaredMethod("dependencyHealthTaskScheduler")
			.invoke(configuration);
		scheduler.initialize();
		assertEquals(1, scheduler.getScheduledThreadPoolExecutor().getCorePoolSize());

		TaskScheduler dedicatedScheduler = mock(TaskScheduler.class);
		DependencyHealthSampler sampler = mock(DependencyHealthSampler.class);
		Object runner = configurationType
			.getDeclaredMethod("dependencyHealthSamplingRunner", DependencyHealthSampler.class, TaskScheduler.class,
				java.time.Duration.class)
			.invoke(configuration, sampler, dedicatedScheduler, java.time.Duration.ofSeconds(10));
		runner.getClass().getMethod("run", org.springframework.boot.ApplicationArguments.class)
			.invoke(runner, new Object[] {null});
		verify(dedicatedScheduler).scheduleWithFixedDelay(any(Runnable.class), eq(java.time.Duration.ofSeconds(10)));
		scheduler.shutdown();
	}
}
