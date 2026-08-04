package cloud.bamsongi.albammate.notification.recovery;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.support.SpringApplicationJsonEnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.SimpleCommandLinePropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

/** main 메서드가 process 종료 전에 선택하는 일반 앱·ops 앱·입력 거절 경계다. */
public final class NotificationOpsLaunchPolicy {

	private NotificationOpsLaunchPolicy() {}

	public static LaunchDecision decide(
		String[] args,
		Properties systemProperties,
		Map<String, String> environmentVariables) {
		ConfigurableEnvironment environment = createEnvironment(args, systemProperties, environmentVariables);
		String activeProfiles = environment.getProperty("spring.profiles.active");
		String includedProfiles = environment.getProperty("spring.profiles.include");
		if (containsNotificationOpsProfile(activeProfiles) || containsNotificationOpsProfile(includedProfiles)) {
			return LaunchDecision.NOTIFICATION_OPS;
		}
		if (!hasProfileToken(activeProfiles) && !hasProfileToken(includedProfiles)
			&& containsNotificationOpsProfile(environment.getProperty("spring.profiles.default"))) {
			return LaunchDecision.NOTIFICATION_OPS;
		}
		return hasNotificationOpsConfiguration(environment)
			? LaunchDecision.REJECT_OPERATION_ARGUMENTS
			: LaunchDecision.NORMAL;
	}

	private static ConfigurableEnvironment createEnvironment(
		String[] args,
		Properties systemProperties,
		Map<String, String> environmentVariables) {
		ConfigurableEnvironment environment = new StandardEnvironment();
		MutablePropertySources propertySources = environment.getPropertySources();
		propertySources.replace(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME,
			new PropertiesPropertySource(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME, systemProperties));
		Map<String, Object> systemEnvironment = new LinkedHashMap<>();
		systemEnvironment.putAll(environmentVariables);
		propertySources.replace(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
			new SystemEnvironmentPropertySource(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
				systemEnvironment));
		propertySources.addFirst(new SimpleCommandLinePropertySource(args));
		new SpringApplicationJsonEnvironmentPostProcessor().postProcessEnvironment(environment,
			new SpringApplication());
		return environment;
	}

	private static boolean containsNotificationOpsProfile(String profiles) {
		if (profiles == null) {
			return false;
		}
		for (String profile : profiles.split(",")) {
			if ("notification-ops".equals(profile.trim())) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasProfileToken(String profiles) {
		if (profiles == null) {
			return false;
		}
		for (String profile : profiles.split(",")) {
			if (!profile.trim().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasNotificationOpsConfiguration(ConfigurableEnvironment environment) {
		for (org.springframework.core.env.PropertySource<?> propertySource : environment.getPropertySources()) {
			if (propertySource instanceof EnumerablePropertySource<?> enumerablePropertySource) {
				for (String propertyName : enumerablePropertySource.getPropertyNames()) {
					if (propertyName.startsWith("app.notification.ops.")
						|| propertyName.startsWith("APP_NOTIFICATION_OPS_")) {
						return true;
					}
				}
			}
		}
		return false;
	}

	public enum LaunchDecision {
		NORMAL,
		NOTIFICATION_OPS,
		REJECT_OPERATION_ARGUMENTS
	}
}
