package cloud.bamsongi.albammate.notification.cleanup;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.Trigger;
import org.springframework.scheduling.TriggerContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 이전 cleanup 실행 완료 뒤에 interval과 jitter를 더해 다음 실행만 예약한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@RequiredArgsConstructor
public class NotificationCleanupScheduler implements Trigger, SchedulingConfigurer {

	@NonNull private final NotificationCleanupCoordinator coordinator;
	@NonNull private final NotificationCleanupProperties properties;

	/** 만료 시각을 만들지 않고 target별 bounded cleanup 실행만 시작한다. */
	public void cleanupExpiredData() {
		coordinator.cleanupExpiredData();
	}

	/** cleanup trigger를 Spring scheduling에 등록한다. */
	@Override
	public void configureTasks(ScheduledTaskRegistrar registrar) {
		registrar.addTriggerTask(this::cleanupExpiredData, this);
	}

	@Override
	public Instant nextExecution(TriggerContext triggerContext) {
		Objects.requireNonNull(triggerContext, "triggerContext");
		Instant previousCompletion = triggerContext.lastCompletion();
		Instant scheduleAnchor = previousCompletion == null ? triggerContext.getClock().instant() : previousCompletion;
		return scheduleAnchor.plus(properties.getInterval())
			.plusMillis(nextJitterMillis(properties.getJitter().toMillis()));
	}

	long nextJitterMillis(long maximumInclusive) {
		long exclusiveUpperBound = Math.addExact(maximumInclusive, 1);
		return ThreadLocalRandom.current().nextLong(exclusiveUpperBound);
	}
}
