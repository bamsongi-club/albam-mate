package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.scheduling.annotation.EnableScheduling;

class NotificationOpsLaunchPolicyTest {

	@Test
	void 일반_profile의_운영_인자는_기동_전에_거절한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.REJECT_OPERATION_ARGUMENTS,
			NotificationOpsLaunchPolicy.decide(new String[] {"--app.notification.ops.action=INSPECT"}, null, null));
	}

	@Test
	void notification_ops_profile은_명령행과_환경_profile에서_선택된다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			NotificationOpsLaunchPolicy.decide(new String[] {"--spring.profiles.active=notification-ops"}, null, null));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			NotificationOpsLaunchPolicy.decide(new String[0], null, "local,notification-ops"));
	}

	@Test
	void 비슷한_profile_문자열은_notification_ops로_오인하지_않는다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			NotificationOpsLaunchPolicy.decide(new String[] {"--spring.profiles.active=not-notification-ops"}, null,
				null));
	}

	@Test
	void ops_애플리케이션은_non_web이고_일반_scheduler를_활성화하지_않는다() {
		assertEquals(WebApplicationType.NONE, NotificationOpsApplication.create().getWebApplicationType());
		assertNull(NotificationOpsApplication.class.getAnnotation(EnableScheduling.class));
	}
}
