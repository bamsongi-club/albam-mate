package cloud.bamsongi.albammate;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.AutoConfigurationExcludeFilter;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.TypeExcludeFilter;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

import cloud.bamsongi.albammate.global.time.UtcTimeZone;
import cloud.bamsongi.albammate.notification.recovery.NotificationOpsApplication;
import cloud.bamsongi.albammate.notification.recovery.NotificationOpsLaunchPolicy;

@SpringBootApplication
@ComponentScan(excludeFilters = {
	@ComponentScan.Filter(type = FilterType.CUSTOM, classes = TypeExcludeFilter.class),
	@ComponentScan.Filter(type = FilterType.CUSTOM, classes = AutoConfigurationExcludeFilter.class),
	@ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = NotificationOpsApplication.class)
})
public class AlbamMateApplication {

	public static void main(String[] args) {
		UtcTimeZone.configure();
		NotificationOpsLaunchPolicy.LaunchDecision launchDecision = NotificationOpsLaunchPolicy.decide(
			args, System.getProperties(), System.getenv());
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
