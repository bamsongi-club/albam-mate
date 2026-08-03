package cloud.bamsongi.albammate.notification.relay;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import org.junit.jupiter.api.Test;

class NotificationRelaySchedulerTest {

	@Test
	void 활성화된_relay는_batch_처리를_시작한다() {
		NotificationRelayCoordinator coordinator = mock(NotificationRelayCoordinator.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		NotificationRelayScheduler scheduler = new NotificationRelayScheduler(coordinator, properties);

		scheduler.relayProcessableEvents();

		verify(coordinator).processBatch();
	}

	@Test
	void 비활성화된_relay는_저장_상태를_바꾸지_않는다() {
		NotificationRelayCoordinator coordinator = mock(NotificationRelayCoordinator.class);
		NotificationRelayProperties properties = new NotificationRelayProperties();
		properties.setEnabled(false);
		NotificationRelayScheduler scheduler = new NotificationRelayScheduler(coordinator, properties);

		scheduler.relayProcessableEvents();

		verifyNoInteractions(coordinator);
	}
}
