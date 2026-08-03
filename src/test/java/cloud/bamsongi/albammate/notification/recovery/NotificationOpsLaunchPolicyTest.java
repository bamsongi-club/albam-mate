package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.scheduling.annotation.EnableScheduling;

class NotificationOpsLaunchPolicyTest {

	@Test
	void 일반_profile의_운영_인자는_기동_전에_거절한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.REJECT_OPERATION_ARGUMENTS,
			NotificationOpsLaunchPolicy.decide(new String[] {"--app.notification.ops.action=INSPECT"}, new Properties(),
				Map.of()));
	}

	@Test
	void 일반_profile의_시스템속성_운영_키는_기동_전에_거절한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.REJECT_OPERATION_ARGUMENTS,
			decisionForSystemProperty("app.notification.ops.action", "INSPECT"));
	}

	@Test
	void 일반_profile의_환경변수_운영_키는_기동_전에_거절한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.REJECT_OPERATION_ARGUMENTS,
			decisionForEnvironmentVariable("APP_NOTIFICATION_OPS_ACTION", "INSPECT"));
	}

	@Test
	void 유사한_시스템속성_환경변수_운영_key는_거절하지_않는다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decisionForSystemProperty("app.notification.opsx.action", "INSPECT"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decisionForEnvironmentVariable("APP_NOTIFICATION_OPSX_ACTION", "INSPECT"));
	}

	@Test
	void notification_ops_profile은_시스템속성_환경변수_운영_키보다_먼저_선택된다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decision(new String[0], systemProperties(Map.of(
				"spring.profiles.active", "notification-ops",
				"app.notification.ops.action", "INSPECT")), Map.of()));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decision(new String[0], new Properties(), Map.of(
				"SPRING_PROFILES_ACTIVE", "notification-ops",
				"APP_NOTIFICATION_OPS_ACTION", "INSPECT")));
	}

	@Test
	void 명령행_active_include_default_profile은_ops_애플리케이션을_선택한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForArguments("--spring.profiles.active=local,notification-ops"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForArguments("--spring.profiles.include=local,notification-ops"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForArguments("--spring.profiles.default=local,notification-ops"));
	}

	@Test
	void 시스템속성_active_include_default_profile은_ops_애플리케이션을_선택한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForSystemProperty("spring.profiles.active", "local,notification-ops"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForSystemProperty("spring.profiles.include", "local,notification-ops"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForSystemProperty("spring.profiles.default", "local,notification-ops"));
	}

	@Test
	void 환경변수_active_include_default_profile은_ops_애플리케이션을_선택한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForEnvironmentVariable("SPRING_PROFILES_ACTIVE", "local,notification-ops"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForEnvironmentVariable("SPRING_PROFILES_INCLUDE", "local,notification-ops"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForEnvironmentVariable("SPRING_PROFILES_DEFAULT", "local,notification-ops"));
	}

	@Test
	void 비슷한_profile_문자열은_모든_source에서_notification_ops로_오인하지_않는다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decisionForArguments("--spring.profiles.active=not-notification-ops"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decisionForSystemProperty("spring.profiles.include", "not-notification-ops"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decisionForEnvironmentVariable("SPRING_PROFILES_DEFAULT", "not-notification-ops"));
	}

	@Test
	void 명령행_active_local은_낮은_우선순위_active_ops를_덮어쓴다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decision(new String[] {"--spring.profiles.active=local"}, systemProperty("spring.profiles.active",
				"notification-ops"), Map.of("SPRING_PROFILES_ACTIVE", "notification-ops")));
	}

	@Test
	void 시스템속성_active_local은_환경변수_active_ops를_덮어쓴다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decision(new String[0], systemProperty("spring.profiles.active", "local"),
				Map.of("SPRING_PROFILES_ACTIVE", "notification-ops")));
	}

	@Test
	void active_또는_include에_실제_profile이_있으면_default_ops를_무시한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decisionForArguments("--spring.profiles.active=local", "--spring.profiles.default=notification-ops"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decisionForArguments("--spring.profiles.include=local", "--spring.profiles.default=notification-ops"));
	}

	@Test
	void active와_include가_비어있으면_default_ops를_선택한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForArguments("--spring.profiles.default=notification-ops"));
	}

	@Test
	void active_local과_include_ops가_함께_있으면_ops를_선택한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForArguments("--spring.profiles.active=local", "--spring.profiles.include=notification-ops"));
	}

	@Test
	void 같은_명령행_profile_option은_마지막_값을_사용한다() {
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NORMAL,
			decisionForArguments("--spring.profiles.active=notification-ops", "--spring.profiles.active=local"));
		assertEquals(NotificationOpsLaunchPolicy.LaunchDecision.NOTIFICATION_OPS,
			decisionForArguments("--spring.profiles.active=local", "--spring.profiles.active=notification-ops"));
	}

	@Test
	void ops_애플리케이션은_non_web이고_일반_scheduler를_활성화하지_않는다() {
		assertEquals(WebApplicationType.NONE, NotificationOpsApplication.create().getWebApplicationType());
		assertNull(NotificationOpsApplication.class.getAnnotation(EnableScheduling.class));
	}

	private static NotificationOpsLaunchPolicy.LaunchDecision decisionForArguments(String... arguments) {
		return decision(arguments, new Properties(), Map.of());
	}

	private static NotificationOpsLaunchPolicy.LaunchDecision decisionForSystemProperty(String key, String value) {
		return decision(new String[0], systemProperty(key, value), Map.of());
	}

	private static NotificationOpsLaunchPolicy.LaunchDecision decisionForEnvironmentVariable(String key, String value) {
		return decision(new String[0], new Properties(), Map.of(key, value));
	}

	private static NotificationOpsLaunchPolicy.LaunchDecision decision(
		String[] arguments,
		Properties systemProperties,
		Map<String, String> environmentVariables) {
		return NotificationOpsLaunchPolicy.decide(arguments, systemProperties, environmentVariables);
	}

	private static Properties systemProperty(String key, String value) {
		return systemProperties(Map.of(key, value));
	}

	private static Properties systemProperties(Map<String, String> entries) {
		Properties properties = new Properties();
		entries.forEach(properties::setProperty);
		return properties;
	}
}
