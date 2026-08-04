package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

@SpringBootTest(classes = NotificationOpsApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.profiles.active=notification-ops")
class NotificationOpsDedicatedContextTest {

	@Autowired
	private ApplicationContext context;

	@Test
	void 전용_ops_구성은_Runner를_등록한다() {
		assertNotNull(context.getBean(NotificationOpsRunner.class));
	}
}
