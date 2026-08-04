package cloud.bamsongi.albammate.notification.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

class NotificationCleanupPropertiesBindingTest {

	@Test
	void application_yml의_cleanup_블록은_운영_기본값을_제공한다() throws IOException {
		List<PropertySource<?>> propertySources = new YamlPropertySourceLoader().load(
			"notificationCleanupApplicationYaml", new FileSystemResource("src/main/resources/application.yml"));

		PropertySource<?> propertySource = propertySources.getFirst();
		assertEquals("1h", propertySource.getProperty("app.notification.cleanup.interval"));
		assertEquals("5m", propertySource.getProperty("app.notification.cleanup.jitter"));
		assertEquals(500, propertySource.getProperty("app.notification.cleanup.batch-size"));
		assertEquals(5, propertySource.getProperty("app.notification.cleanup.max-batches-per-target"));
	}

	@Test
	void 기본_속성은_운영_정본의_시간과_batch_상한을_가진다() {
		NotificationCleanupProperties properties = new NotificationCleanupProperties();

		assertEquals(Duration.ofHours(1), properties.getInterval());
		assertEquals(Duration.ofMinutes(5), properties.getJitter());
		assertEquals(500, properties.getBatchSize());
		assertEquals(5, properties.getMaxBatchesPerTarget());
	}

	@Test
	void CLEANUP_JITTER는_0분과_5분을_기동_설정으로_허용한다() {
		assertCleanupJitterStarts("0s", Duration.ZERO);
		assertCleanupJitterStarts("5m", Duration.ofMinutes(5));
	}

	@Test
	void CLEANUP_JITTER가_5분을_초과하면_기동에_실패한다() {
		new ApplicationContextRunner()
			.withUserConfiguration(CleanupPropertiesConfiguration.class)
			.withPropertyValues("app.notification.cleanup.jitter=5m1s")
			.run(context -> assertNotNull(context.getStartupFailure()));
	}

	private void assertCleanupJitterStarts(String configuredJitter, Duration expectedJitter) {
		new ApplicationContextRunner()
			.withUserConfiguration(CleanupPropertiesConfiguration.class)
			.withPropertyValues("app.notification.cleanup.jitter=" + configuredJitter)
			.run(context -> {
				assertNull(context.getStartupFailure());
				assertEquals(expectedJitter, context.getBean(NotificationCleanupProperties.class).getJitter());
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(NotificationCleanupProperties.class)
	static class CleanupPropertiesConfiguration {}
}
