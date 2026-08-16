package cloud.bamsongi.albammate.notification.relay;

import java.time.Clock;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/** 모든 인스턴스에서 설정된 fixed delay마다 제한된 relay batch를 시작하며, 기본값은 5초다. */
@Component
@Slf4j
@RequiredArgsConstructor
public class NotificationRelayScheduler {

	@NonNull private final NotificationRelayCoordinator coordinator;
	@NonNull private final Clock clock;
	@NonNull private final NotificationRelayProperties properties;

	/** relay를 비활성화한 환경에서는 scheduler thread가 저장 상태를 바꾸지 않는다. */
	@Scheduled(fixedDelayString = "${app.notification.relay.poll-interval:5s}")
	public void relayProcessableEvents() {
		if (properties.isEnabled()) {
			try {
				coordinator.processBatch();
			} catch (RuntimeException exception) {
				log.atError().addKeyValue("event", "notification_outbox_relay_scheduler_failed")
					.addKeyValue("failureCode", "RELAY_SCHEDULER_FAILURE")
					.addKeyValue("exceptionClass", exception.getClass().getSimpleName())
					.addKeyValue("occurredAt", Instant.now(clock)).log("notification relay scheduler failed");
			}
		}
	}
}
