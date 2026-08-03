package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

import cloud.bamsongi.albammate.AlbamMateApplication;

@SpringBootTest(classes = AlbamMateApplication.class, properties = {
	"spring.profiles.include=notification-ops",
	"spring.main.web-application-type=servlet",
	"app.notification.ops.action=INSPECT",
	"app.notification.ops.event-ids=1"
})
class NotificationOpsRootContextIsolationTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void 일반_앱_scan은_notification_ops_profile이어도_Runner를_등록하지_않는다() {
		assertTrue(context.getBeansOfType(NotificationOpsRunner.class).isEmpty());
	}
}
