package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.mock.env.MockEnvironment;

class NotificationOpsRunnerTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(NotificationOpsRunnerConfiguration.class);

	@Test
	void notification_ops_profile에서만_Runner_bean을_등록한다() {
		contextRunner.run(context -> assertFalse(context.containsBean("notificationOpsRunner")));
		contextRunner.withInitializer(context -> context.getEnvironment().setActiveProfiles("notification-ops"))
			.run(context -> assertTrue(context.containsBean("notificationOpsRunner")));
	}

	@Test
	void 성공_입력오류_실행실패를_각각_0_2_1로_종료하고_사유를_출력하지_않는다() {
		String reason = "operator private reason";
		NotificationOutboxRecoveryService successService = mock(NotificationOutboxRecoveryService.class);
		when(successService.execute(org.mockito.ArgumentMatchers.any())).thenReturn(
			new NotificationOutboxRecoveryResult(List.of(3L), 1, 0, List.of()));
		NotificationOpsRunner successRunner = new NotificationOpsRunner(successService,
			environment("REPROCESS", reason));

		String output = captureOutput(successRunner);
		assertEquals(0, successRunner.getExitCode());
		assertFalse(output.contains(reason));
		assertFalse(output.contains("eventType=PARTICIPATION_JOINED"));

		NotificationOpsRunner invalidRunner = new NotificationOpsRunner(mock(NotificationOutboxRecoveryService.class),
			new MockEnvironment());
		captureOutput(invalidRunner);
		assertEquals(2, invalidRunner.getExitCode());

		NotificationOutboxRecoveryService failureService = mock(NotificationOutboxRecoveryService.class);
		when(failureService.execute(org.mockito.ArgumentMatchers.any())).thenThrow(new IllegalStateException());
		NotificationOpsRunner failureRunner = new NotificationOpsRunner(failureService,
			environment("REPROCESS", reason));
		String failureOutput = captureOutput(failureRunner);
		assertEquals(1, failureRunner.getExitCode());
		assertFalse(failureOutput.contains(reason));
	}

	@Test
	void inspect는_대상별_비민감_상태만_출력한다() {
		NotificationOutboxRecoveryService recoveryService = mock(NotificationOutboxRecoveryService.class);
		when(recoveryService.preview(org.mockito.ArgumentMatchers.any())).thenReturn(
			new NotificationOutboxRecoveryResult(List.of(3L), 1, 0, List.of(item())));
		NotificationOpsRunner runner = new NotificationOpsRunner(recoveryService,
			environment("INSPECT", "private reason"));

		String output = captureOutput(runner);

		assertTrue(output.contains("eventType=PARTICIPATION_JOINED"));
		assertTrue(output.contains("reprocessable=true"));
		assertFalse(output.contains("private reason"));
	}

	private static NotificationOutboxRecoveryItem item() {
		return new NotificationOutboxRecoveryItem(3L, "FAILED", "PARTICIPATION_JOINED",
			Instant.parse("2026-08-01T00:00:00Z"), Instant.parse("2026-10-30T00:00:00Z"), 5, 5,
			"RELAY_PROCESSING_FAILURE", true, true);
	}

	private static MockEnvironment environment(String action, String reason) {
		return new MockEnvironment()
			.withProperty("app.notification.ops.action", action)
			.withProperty("app.notification.ops.event-ids", "3")
			.withProperty("app.notification.ops.dry-run", "false")
			.withProperty("app.notification.ops.reason-reference", "ISSUE-267")
			.withProperty("app.notification.ops.reason", reason)
			.withProperty("app.notification.ops.requested-by", "ops-user");
	}

	private static String captureOutput(NotificationOpsRunner runner) {
		PrintStream originalOut = System.out;
		PrintStream originalErr = System.err;
		ByteArrayOutputStream captured = new ByteArrayOutputStream();
		try {
			PrintStream stream = new PrintStream(captured, true, StandardCharsets.UTF_8);
			System.setOut(stream);
			System.setErr(stream);
			runner.run(new DefaultApplicationArguments());
		} finally {
			System.setOut(originalOut);
			System.setErr(originalErr);
		}
		return captured.toString(StandardCharsets.UTF_8);
	}

	@Configuration(proxyBeanMethods = false)
	@ComponentScan(basePackageClasses = NotificationOpsRunner.class, useDefaultFilters = false, includeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = NotificationOpsRunner.class))
	static class NotificationOpsRunnerConfiguration {

		@Bean
		NotificationOutboxRecoveryService recoveryService() {
			return mock(NotificationOutboxRecoveryService.class);
		}
	}
}
