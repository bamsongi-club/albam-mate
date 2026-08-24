package cloud.bamsongi.albammate.monitoring;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 알림 relay의 최종 처리 결과만 유한한 outcome dimension으로 계측한다. */
@Component
public final class NotificationRelayMetrics {

	private final Counter processedEvents;
	private final Counter retryScheduledEvents;
	private final Counter failedEvents;
	private final Timer deliveryDuration;
	private final AtomicLong oldestProcessableAgeMillis = new AtomicLong();

	public NotificationRelayMetrics(MeterRegistry meterRegistry) {
		Objects.requireNonNull(meterRegistry, "meterRegistry");
		processedEvents = Counter.builder("notification.relay.events")
			.tag("outcome", "processed")
			.register(meterRegistry);
		retryScheduledEvents = Counter.builder("notification.relay.events")
			.tag("outcome", "retry_scheduled")
			.register(meterRegistry);
		failedEvents = Counter.builder("notification.relay.events")
			.tag("outcome", "failed")
			.register(meterRegistry);
		deliveryDuration = Timer.builder("notification.relay.delivery.duration").register(meterRegistry);
		Gauge.builder("notification.relay.oldest.processable.age", oldestProcessableAgeMillis,
			ageMillis -> ageMillis.get() / 1000.0)
			.register(meterRegistry);
	}

	/** 커밋된 relay 성공만 기록해 롤백된 저장을 성공으로 집계하지 않는다. */
	public void recordProcessed(long deliveryDelayMillis) {
		processedEvents.increment();
		deliveryDuration.record(Duration.ofMillis(deliveryDelayMillis));
	}

	/** 별도 실패 기록 트랜잭션이 커밋한 재시도 예약만 기록한다. */
	public void recordRetryScheduled() {
		retryScheduledEvents.increment();
	}

	/** 별도 실패 기록 트랜잭션이 커밋한 최종 격리만 기록한다. */
	public void recordFailed() {
		failedEvents.increment();
	}

	/** batch 종료 시점의 처리 가능한 최장 적체 시간을 초 단위로 갱신한다. */
	public void recordOldestProcessableAgeMillis(Long ageMillis) {
		oldestProcessableAgeMillis.set(ageMillis == null ? 0L : ageMillis);
	}
}
