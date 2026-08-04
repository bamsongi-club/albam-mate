package cloud.bamsongi.albammate.notification.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
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
}
