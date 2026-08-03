package cloud.bamsongi.albammate.notification.relay;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** 모든 인스턴스에서 설정된 fixed delay마다 제한된 relay batch를 시작하며, 기본값은 5초다. */
@Component
@RequiredArgsConstructor
public class NotificationRelayScheduler {

	@NonNull private final NotificationRelayCoordinator coordinator;
	@NonNull private final NotificationRelayProperties properties;

	/** relay를 비활성화한 환경에서는 scheduler thread가 저장 상태를 바꾸지 않는다. */
	@Scheduled(fixedDelayString = "${app.notification.relay.poll-interval:5s}")
	public void relayProcessableEvents() {
		if (properties.isEnabled()) {
			coordinator.processBatch();
		}
	}
}
