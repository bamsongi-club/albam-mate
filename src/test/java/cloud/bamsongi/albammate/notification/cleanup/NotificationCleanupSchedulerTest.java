package cloud.bamsongi.albammate.notification.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import org.springframework.scheduling.support.SimpleTriggerContext;

class NotificationCleanupSchedulerTest {

	private static final Instant NOW = Instant.parse("2026-08-04T00:00:00Z");

	@Test
	void 이전_완료_시각에_interval과_jitter를_더해_다음_실행을_예약한다() {
		NotificationCleanupCoordinator coordinator = mock(NotificationCleanupCoordinator.class);
		NotificationCleanupProperties properties = properties();
		properties.setInterval(Duration.ofHours(1));
		properties.setJitter(Duration.ofMinutes(5));
		NotificationCleanupScheduler scheduler = new TestNotificationCleanupScheduler(coordinator, properties,
			300_000L);
		Instant completion = NOW.plus(Duration.ofMinutes(2));

		Instant nextExecution = scheduler.nextExecution(
			new SimpleTriggerContext(NOW, NOW.plusSeconds(1), completion));

		assertEquals(completion.plus(Duration.ofHours(1)).plus(Duration.ofMinutes(5)), nextExecution);
	}

	@Test
	void 첫_실행은_Trigger_Clock_현재_시각에_interval과_jitter를_더한다() {
		NotificationCleanupCoordinator coordinator = mock(NotificationCleanupCoordinator.class);
		NotificationCleanupProperties properties = properties();
		NotificationCleanupScheduler scheduler = new TestNotificationCleanupScheduler(coordinator, properties, 0L);

		assertEquals(
			NOW.plus(Duration.ofHours(1)),
			scheduler.nextExecution(new SimpleTriggerContext(Clock.fixed(NOW, ZoneOffset.UTC))));
	}

	@Test
	void 허용된_최대_jitter는_0분부터_5분_사이의_밀리초만_만든다() {
		NotificationCleanupScheduler scheduler = new NotificationCleanupScheduler(
			mock(NotificationCleanupCoordinator.class),
			properties());
		long maximumJitterMillis = Duration.ofMinutes(5).toMillis();

		for (int attempt = 0; attempt < 100; attempt++) {
			long jitterMillis = scheduler.nextJitterMillis(maximumJitterMillis);

			assertTrue(jitterMillis >= 0);
			assertTrue(jitterMillis <= maximumJitterMillis);
		}
	}

	@Test
	void scheduler는_만료_시각을_만들지_않고_cleanup_실행만_시작한다() {
		NotificationCleanupCoordinator coordinator = mock(NotificationCleanupCoordinator.class);
		NotificationCleanupScheduler scheduler = new TestNotificationCleanupScheduler(coordinator, properties(), 0L);

		scheduler.cleanupExpiredData();

		verify(coordinator).cleanupExpiredData();
	}

	@Test
	void cleanup_소유_설정이_동적_Trigger를_Spring_scheduling에_등록한다() {
		assertNotNull(NotificationCleanupScheduler.class.getAnnotation(Configuration.class));
		assertNotNull(NotificationCleanupScheduler.class.getAnnotation(EnableScheduling.class));
		NotificationCleanupCoordinator coordinator = mock(NotificationCleanupCoordinator.class);
		NotificationCleanupScheduler scheduler = new NotificationCleanupScheduler(coordinator, properties());
		ScheduledTaskRegistrar registrar = new ScheduledTaskRegistrar();

		scheduler.configureTasks(registrar);

		assertEquals(1, registrar.getTriggerTaskList().size());
		var triggerTask = registrar.getTriggerTaskList().getFirst();
		assertEquals(scheduler, triggerTask.getTrigger());
		triggerTask.getRunnable().run();
		verify(coordinator).cleanupExpiredData();
	}

	private NotificationCleanupProperties properties() {
		return new NotificationCleanupProperties();
	}

	private static final class TestNotificationCleanupScheduler extends NotificationCleanupScheduler {

		private final long jitterMillis;

		private TestNotificationCleanupScheduler(
			NotificationCleanupCoordinator coordinator,
			NotificationCleanupProperties properties,
			long jitterMillis) {
			super(coordinator, properties);
			this.jitterMillis = jitterMillis;
		}

		@Override
		long nextJitterMillis(long maximumInclusive) {
			return jitterMillis;
		}
	}
}
