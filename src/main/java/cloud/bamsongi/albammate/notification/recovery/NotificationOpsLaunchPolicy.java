package cloud.bamsongi.albammate.notification.recovery;

/** main 메서드가 process 종료 전에 선택하는 일반 앱·ops 앱·입력 거절 경계다. */
public final class NotificationOpsLaunchPolicy {

	private NotificationOpsLaunchPolicy() {}

	public static LaunchDecision decide(String[] args, String systemProfiles, String environmentProfiles) {
		boolean notificationOpsProfile = containsNotificationOpsProfile(args)
			|| containsNotificationOpsProfile(systemProfiles)
			|| containsNotificationOpsProfile(environmentProfiles);
		if (notificationOpsProfile) {
			return LaunchDecision.NOTIFICATION_OPS;
		}
		return hasNotificationOpsArguments(args) ? LaunchDecision.REJECT_OPERATION_ARGUMENTS : LaunchDecision.NORMAL;
	}

	private static boolean containsNotificationOpsProfile(String[] args) {
		for (String argument : args) {
			if ((argument.startsWith("--spring.profiles.active=") || argument.startsWith("--spring.profiles.include="))
				&& containsNotificationOpsProfile(profileValue(argument))) {
				return true;
			}
		}
		return false;
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

	private static String profileValue(String argument) {
		return argument.substring(argument.indexOf('=') + 1);
	}

	private static boolean hasNotificationOpsArguments(String[] args) {
		for (String argument : args) {
			if (argument.startsWith("--app.notification.ops.")) {
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
