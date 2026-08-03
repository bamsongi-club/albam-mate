package cloud.bamsongi.albammate.notification.relay;

import java.util.Objects;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 모든 인스턴스에서 5초마다 제한된 relay batch를 시작한다. */
@Component
public class NotificationRelayScheduler {

	private final NotificationRelayCoordinator coordinator;
	private final NotificationRelayProperties properties;

	public NotificationRelayScheduler(
		NotificationRelayCoordinator coordinator, NotificationRelayProperties properties) {
		this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
		this.properties = Objects.requireNonNull(properties, "properties");
	}

	/** relay를 비활성화한 환경에서는 scheduler thread가 저장 상태를 바꾸지 않는다. */
	@Scheduled(fixedDelayString = "${app.notification.relay.poll-interval:5s}")
	public void relayProcessableEvents() {
		if (properties.isEnabled()) {
			coordinator.processBatch();
		}
	}
}
