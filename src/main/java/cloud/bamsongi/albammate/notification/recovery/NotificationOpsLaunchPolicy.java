package cloud.bamsongi.albammate.notification.recovery;

import java.util.Map;
import java.util.Properties;

import org.springframework.core.env.SimpleCommandLinePropertySource;

/** main 메서드가 process 종료 전에 선택하는 일반 앱·ops 앱·입력 거절 경계다. */
public final class NotificationOpsLaunchPolicy {

	private NotificationOpsLaunchPolicy() {}

	public static LaunchDecision decide(
		String[] args,
		Properties systemProperties,
		Map<String, String> environmentVariables) {
		String activeProfiles = resolveProfileValue(args, systemProperties, environmentVariables,
			"spring.profiles.active", "SPRING_PROFILES_ACTIVE");
		String includedProfiles = resolveProfileValue(args, systemProperties, environmentVariables,
			"spring.profiles.include", "SPRING_PROFILES_INCLUDE");
		if (containsNotificationOpsProfile(activeProfiles) || containsNotificationOpsProfile(includedProfiles)) {
			return LaunchDecision.NOTIFICATION_OPS;
		}
		if (!hasProfileToken(activeProfiles) && !hasProfileToken(includedProfiles)
			&& containsNotificationOpsProfile(resolveProfileValue(args, systemProperties, environmentVariables,
				"spring.profiles.default", "SPRING_PROFILES_DEFAULT"))) {
			return LaunchDecision.NOTIFICATION_OPS;
		}
		return hasNotificationOpsConfiguration(args, systemProperties, environmentVariables)
			? LaunchDecision.REJECT_OPERATION_ARGUMENTS
			: LaunchDecision.NORMAL;
	}

	private static String resolveProfileValue(
		String[] args,
		Properties systemProperties,
		Map<String, String> environmentVariables,
		String propertyKey,
		String environmentKey) {
		String commandLineValue = new SimpleCommandLinePropertySource(args).getProperty(propertyKey);
		if (commandLineValue != null) {
			return commandLineValue;
		}
		String systemPropertyValue = systemProperties.getProperty(propertyKey);
		return systemPropertyValue != null ? systemPropertyValue : environmentVariables.get(environmentKey);
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

	private static boolean hasNotificationOpsConfiguration(
		String[] args,
		Properties systemProperties,
		Map<String, String> environmentVariables) {
		return hasNotificationOpsCommandLineArgument(args)
			|| hasNotificationOpsSystemProperty(systemProperties)
			|| hasNotificationOpsEnvironmentVariable(environmentVariables);
	}

	private static boolean hasNotificationOpsCommandLineArgument(String[] args) {
		for (String argument : args) {
			if (argument.startsWith("--app.notification.ops.")) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasNotificationOpsSystemProperty(Properties systemProperties) {
		for (String key : systemProperties.stringPropertyNames()) {
			if (key.startsWith("app.notification.ops.")) {
				return true;
			}
		}
		return false;
	}

	private static boolean hasNotificationOpsEnvironmentVariable(Map<String, String> environmentVariables) {
		for (String key : environmentVariables.keySet()) {
			if (key.startsWith("APP_NOTIFICATION_OPS_")) {
				return true;
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
