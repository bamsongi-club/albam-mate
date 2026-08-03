package cloud.bamsongi.albammate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ConfigurableApplicationContext;

import cloud.bamsongi.albammate.global.time.UtcTimeZone;
import cloud.bamsongi.albammate.notification.recovery.NotificationOpsApplication;
import cloud.bamsongi.albammate.notification.recovery.NotificationOpsLaunchPolicy;

@SpringBootApplication
public class AlbamMateApplication {

	public static void main(String[] args) {
		UtcTimeZone.configure();
		NotificationOpsLaunchPolicy.LaunchDecision launchDecision = NotificationOpsLaunchPolicy.decide(
			args, System.getProperty("spring.profiles.active"), System.getenv("SPRING_PROFILES_ACTIVE"));
		if (launchDecision == NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS) {
			SpringApplication application = NotificationOpsApplication.create();
			ConfigurableApplicationContext context = application.run(args);
			System.exit(SpringApplication.exit(context));
		}
		if (launchDecision == NotificationOpsLaunchPolicy.LaunchDecision.REJECT_OPERATION_ARGUMENTS) {
			System.err.println("notification outbox operation requires notification-ops profile");
			System.exit(2);
		}
		SpringApplication.run(AlbamMateApplication.class, args);
	}

}
