package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

class NotificationRelayPropertiesBindingTest {

	@Test
	void application_yml의_relay_블록은_운영_기본값을_제공한다() throws IOException {
		List<PropertySource<?>> propertySources = new YamlPropertySourceLoader().load(
			"notificationRelayApplicationYaml", new FileSystemResource("src/main/resources/application.yml"));

		assertEquals(1, propertySources.size());
		PropertySource<?> propertySource = propertySources.getFirst();
		assertTrue((Boolean)propertySource.getProperty("app.notification.relay.enabled"));
		assertEquals("5s", propertySource.getProperty("app.notification.relay.poll-interval"));
		assertEquals(50, propertySource.getProperty("app.notification.relay.max-events-per-run"));
		assertNull(propertySource.getProperty("app.notification.relay.max-automatic-attempts"));
		assertNull(propertySource.getProperty("app.notification.relay.first-retry-delay"));
	}

	@Test
	void 기본_속성은_운영_relay_기본값을_가진다() {
		NotificationRelayProperties properties = new NotificationRelayProperties();

		assertTrue(properties.isEnabled());
		assertEquals(Duration.ofSeconds(5), properties.getPollInterval());
		assertEquals(50, properties.getMaxEventsPerRun());
	}

	@Test
	void 전용_relay_속성은_최소_Binder_context에_바인딩된다() {
		new ApplicationContextRunner()
			.withUserConfiguration(NotificationRelayPropertiesConfiguration.class)
			.withPropertyValues(
				"app.notification.relay.enabled=true",
				"app.notification.relay.poll-interval=10ms",
				"app.notification.relay.max-events-per-run=3")
			.run(context -> {
				assertNull(context.getStartupFailure());
				NotificationRelayProperties properties = context.getBean(NotificationRelayProperties.class);
				assertTrue(properties.isEnabled());
				assertEquals(Duration.ofMillis(10), properties.getPollInterval());
				assertEquals(3, properties.getMaxEventsPerRun());
			});
	}

	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(NotificationRelayProperties.class)
	static class NotificationRelayPropertiesConfiguration {}
}
