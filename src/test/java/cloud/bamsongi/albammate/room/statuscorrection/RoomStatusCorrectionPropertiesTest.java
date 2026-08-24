package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

class RoomStatusCorrectionPropertiesTest {

	private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
		.withUserConfiguration(PropertiesConfiguration.class)
		.withPropertyValues(
			"app.room.status-correction.lock-name=room-status-correction",
			"app.room.status-correction.trigger-delay=15m",
			"app.room.status-correction.trigger-jitter=3m",
			"app.room.status-correction.max-batches-per-run=100");

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

	@Test
	void lockName은_room_status_correction만_허용한다() {
		contextRunner.withPropertyValues(
			"app.room.status-correction.lock-at-most-for=2m",
			"app.room.status-correction.execution-warning-threshold=30s",
			"app.room.status-correction.lock-name=another-room-job")
			.run(context -> assertTrue(context.getStartupFailure() != null));
	}

	@Test
	void 후보_제한은_운영_기본값_없이_양수_명시값만_허용한다() {
		contextRunner.withPropertyValues(
			"app.room.status-correction.lock-at-most-for=2m",
			"app.room.status-correction.execution-warning-threshold=30s")
			.run(context -> assertFalse(context.getStartupFailure() != null));
		contextRunner.withPropertyValues(
			"app.room.status-correction.lock-at-most-for=2m",
			"app.room.status-correction.execution-warning-threshold=30s",
			"app.room.status-correction.candidate-limit=0")
			.run(context -> assertTrue(context.getStartupFailure() != null));
		contextRunner.withPropertyValues(
			"app.room.status-correction.lock-at-most-for=2m",
			"app.room.status-correction.execution-warning-threshold=30s",
			"app.room.status-correction.candidate-limit=10")
			.run(context -> assertFalse(context.getStartupFailure() != null));
	}

	@Test
	void 실행당_최대_배치_수는_누락과_0_이하를_거부하고_양수만_허용한다() {
		new ApplicationContextRunner()
			.withUserConfiguration(PropertiesConfiguration.class)
			.withPropertyValues(
				"app.room.status-correction.lock-name=room-status-correction",
				"app.room.status-correction.trigger-delay=15m",
				"app.room.status-correction.trigger-jitter=3m",
				"app.room.status-correction.lock-at-most-for=2m",
				"app.room.status-correction.execution-warning-threshold=30s")
			.run(context -> assertTrue(context.getStartupFailure() != null));
		contextRunner.withPropertyValues(
			"app.room.status-correction.lock-at-most-for=2m",
			"app.room.status-correction.execution-warning-threshold=30s",
			"app.room.status-correction.max-batches-per-run=0")
			.run(context -> assertTrue(context.getStartupFailure() != null));
		contextRunner.withPropertyValues(
			"app.room.status-correction.lock-at-most-for=2m",
			"app.room.status-correction.execution-warning-threshold=30s",
			"app.room.status-correction.max-batches-per-run=-1")
			.run(context -> assertTrue(context.getStartupFailure() != null));
		contextRunner.withPropertyValues(
			"app.room.status-correction.lock-at-most-for=2m",
			"app.room.status-correction.execution-warning-threshold=30s",
			"app.room.status-correction.max-batches-per-run=100")
			.run(context -> assertFalse(context.getStartupFailure() != null));
	}

	/**
	 * ROOM-09d 후보 측정으로 확정한 초기 운영값이 생산 {@code application.yml}에 그대로 있고 같은 값으로 바인딩되는지
	 * 확인한다. 테스트가 값을 직접 주입하면 설정과 어긋나도 통과하고, classpath의 {@code application.yml}은
	 * {@code src/test/resources} 사본이 가리므로 생산 설정 파일을 경로로 직접 읽는다.
	 */
	@Test
	void 확정한_초기_운영값이_생산_application_yml과_설정_바인딩에_동일하게_적용된다() {
		RoomStatusCorrectionProperties properties = bindProductionProperties();

		assertEquals(100, properties.getCandidateLimit());
		assertEquals(100, properties.getMaxBatchesPerRun());
		assertEquals(Duration.ofSeconds(180), properties.getExecutionWarningThreshold());
		assertEquals(Duration.ofMinutes(10), properties.getLockAtMostFor());
		assertEquals(Duration.ofMinutes(15), properties.getTriggerDelay());
		assertEquals(Duration.ofMinutes(3), properties.getTriggerJitter());
	}

	/**
	 * `lockAtMostFor`는 정상 전체 실행이 끝나기 전에 만료되면 안 되고, 실행 주기를 넘겨 다른 인스턴스의 인계를
	 * 불필요하게 늦춰서도 안 된다. 확정값 사이의 이 관계를 고정한다.
	 */
	@Test
	void lockAtMostFor는_실행시간_경고_기준보다_길고_실행_주기를_넘지_않는다() {
		RoomStatusCorrectionProperties properties = bindProductionProperties();

		assertTrue(properties.getLockAtMostFor().compareTo(properties.getExecutionWarningThreshold()) > 0);
		assertTrue(properties.getLockAtMostFor().compareTo(properties.getTriggerDelay()) <= 0);
	}

	private RoomStatusCorrectionProperties bindProductionProperties() {
		Path applicationYml = Path.of("src", "main", "resources", "application.yml");
		assertTrue(Files.exists(applicationYml), "생산 설정 파일을 찾지 못했습니다: " + applicationYml.toAbsolutePath());

		List<PropertySource<?>> sources;
		try {
			sources = new YamlPropertySourceLoader().load("application.yml", new FileSystemResource(applicationYml));
		} catch (IOException exception) {
			throw new IllegalStateException("생산 설정 파일을 읽지 못했습니다: " + applicationYml.toAbsolutePath(), exception);
		}

		MutablePropertySources propertySources = new MutablePropertySources();
		sources.forEach(propertySources::addLast);
		return new Binder(ConfigurationPropertySources.from(propertySources))
			.bind("app.room.status-correction", RoomStatusCorrectionProperties.class)
			.orElseThrow(() -> new IllegalStateException("app.room.status-correction 바인딩에 실패했습니다"));
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(RoomStatusCorrectionProperties.class)
	static class PropertiesConfiguration {}
}
