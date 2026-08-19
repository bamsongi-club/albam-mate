package cloud.bamsongi.albammate.monitoring;

import java.time.Duration;
import java.util.Objects;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/** 알림 relay의 최종 처리 결과만 유한한 outcome dimension으로 계측한다. */
@Component
public final class NotificationRelayMetrics {

	private final Counter processedEvents;
	private final Timer deliveryDuration;

	public NotificationRelayMetrics(MeterRegistry meterRegistry) {
		Objects.requireNonNull(meterRegistry, "meterRegistry");
		processedEvents = Counter.builder("notification.relay.events")
			.tag("outcome", "processed")
			.register(meterRegistry);
		deliveryDuration = Timer.builder("notification.relay.delivery.duration").register(meterRegistry);
	}

	/** 커밋된 relay 성공만 기록해 롤백된 저장을 성공으로 집계하지 않는다. */
	public void recordProcessed(long deliveryDelayMillis) {
		processedEvents.increment();
		deliveryDuration.record(Duration.ofMillis(deliveryDelayMillis));
	}
}
