package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RoomStatusCorrectionPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration.class)
		.withPropertyValues(
			"app.room.status-correction.lock-name=room-status-correction",
			"app.room.status-correction.trigger-delay=15m",
			"app.room.status-correction.trigger-jitter=3m");

	@Test
	void lockAtMostFor와_실행시간_경고_기준은_각각_없어도_기동에_실패하고_둘이_있으면_기동한다() {
		contextRunner.run(context -> assertTrue(context.getStartupFailure() != null));
		contextRunner.withPropertyValues("app.room.status-correction.lock-at-most-for=2m")
			.run(context -> assertTrue(context.getStartupFailure() != null));
		contextRunner.withPropertyValues("app.room.status-correction.execution-warning-threshold=30s")
			.run(context -> assertTrue(context.getStartupFailure() != null));
		contextRunner.withPropertyValues(
			"app.room.status-correction.lock-at-most-for=2m",
			"app.room.status-correction.execution-warning-threshold=30s")
			.run(context -> assertFalse(context.getStartupFailure() != null));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RoomStatusCorrectionProperties.class)
	static class PropertiesConfiguration {}
}
