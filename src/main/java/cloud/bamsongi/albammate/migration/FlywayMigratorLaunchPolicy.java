package cloud.bamsongi.albammate.migration;

import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

/** main 메서드가 일반 앱과 one-shot migrator를 분리하고 상충 profile을 fail-close한다. */
public final class FlywayMigratorLaunchPolicy {

	private FlywayMigratorLaunchPolicy() {}

	public static LaunchDecision decide(
		String[] args,
		Properties systemProperties,
		Map<String, String> environmentVariables) {
		boolean migrator = hasProfile("migrator", args, systemProperties, environmentVariables);
		boolean notificationOps = hasProfile("notification-ops", args, systemProperties, environmentVariables);
		if (migrator && notificationOps) {
			return LaunchDecision.REJECT_CONFLICTING_PROFILES;
		}
		return migrator ? LaunchDecision.MIGRATOR : LaunchDecision.NORMAL;
	}

	private static boolean hasProfile(
		String expectedProfile,
		String[] args,
		Properties systemProperties,
		Map<String, String> environmentVariables) {
		return Arrays.stream(args).anyMatch(argument -> profileArgumentContains(argument, expectedProfile))
			|| profilesContain(systemProperties.getProperty("spring.profiles.active"), expectedProfile)
			|| profilesContain(systemProperties.getProperty("spring.profiles.include"), expectedProfile)
			|| profilesContain(environmentVariables.get("SPRING_PROFILES_ACTIVE"), expectedProfile)
			|| profilesContain(environmentVariables.get("SPRING_PROFILES_INCLUDE"), expectedProfile);
	}

	private static boolean profileArgumentContains(String argument, String expectedProfile) {
		return argument.startsWith("--spring.profiles.active=")
			&& profilesContain(argument.substring("--spring.profiles.active=".length()), expectedProfile)
			|| argument.startsWith("--spring.profiles.include=")
				&& profilesContain(argument.substring("--spring.profiles.include=".length()), expectedProfile);
	}

	private static boolean profilesContain(String profiles, String expectedProfile) {
		return profiles != null && Arrays.stream(profiles.split(","))
			.map(String::trim)
			.anyMatch(expectedProfile::equals);
	}

	public enum LaunchDecision {
		NORMAL,
		MIGRATOR,
		REJECT_CONFLICTING_PROFILES
	}
}
