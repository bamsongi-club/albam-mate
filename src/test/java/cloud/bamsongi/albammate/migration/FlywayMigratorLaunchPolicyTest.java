package cloud.bamsongi.albammate.migration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class FlywayMigratorLaunchPolicyTest {

	@Test
	void migrator_프로필은_전용_non_web_애플리케이션을_선택한다() {
		assertEquals(FlywayMigratorLaunchPolicy.LaunchDecision.MIGRATOR,
			FlywayMigratorLaunchPolicy.decide(new String[] {"--spring.profiles.active=production,migrator"},
				new Properties(), Map.of()));
	}

	@Test
	void notification_ops와_migrator_프로필은_함께_기동할_수_없다() {
		assertEquals(FlywayMigratorLaunchPolicy.LaunchDecision.REJECT_CONFLICTING_PROFILES,
			FlywayMigratorLaunchPolicy.decide(new String[] {"--spring.profiles.active=notification-ops,migrator"},
				new Properties(), Map.of()));
	}
}
