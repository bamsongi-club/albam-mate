package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

import cloud.bamsongi.albammate.AlbamMateApplication;

@SpringBootTest(classes = AlbamMateApplication.class, properties = {
	"app.notification.relay.enabled=true",
	"app.notification.relay.poll-interval=10ms",
	"app.notification.relay.max-events-per-run=3"
})
@Import(NotificationRelayPropertiesBindingTest.SchedulingConfiguration.class)
class NotificationRelayPropertiesBindingTest {

	@Autowired
	private NotificationRelayProperties properties;

	@Autowired
	private NotificationRelayCoordinator coordinator;

	@Autowired
	private ConfigurableEnvironment environment;

	@Test
	void application_yml의_relay_블록은_YAML로_파싱되어_운영_기본값을_제공한다() throws IOException {
		List<PropertySource<?>> propertySources = new YamlPropertySourceLoader().load(
			"notificationRelayApplicationYaml", new FileSystemResource("src/main/resources/application.yml"));

		assertEquals(1, propertySources.size());
		PropertySource<?> propertySource = propertySources.getFirst();
		assertEquals(true, propertySource.getProperty("app.notification.relay.enabled"));
		assertEquals("5s", propertySource.getProperty("app.notification.relay.poll-interval"));
		assertEquals(50, propertySource.getProperty("app.notification.relay.max-events-per-run"));
	}

	@Test
	void 실제_Environment의_relay_키에_전용_값을_주입하면_Binder가_반영한다() {
		assertTrue(environment.getPropertySources().stream()
			.anyMatch(source -> source.getName().contains("application.yml")),
			() -> environment.getPropertySources().stream().map(source -> source.getName()).toList().toString());
		assertTrue(environment.containsProperty("app.notification.relay.enabled"));
		assertTrue(environment.containsProperty("app.notification.relay.poll-interval"));
		assertTrue(environment.containsProperty("app.notification.relay.max-events-per-run"));
		assertTrue(properties.isEnabled());
		assertEquals(Duration.ofMillis(10), properties.getPollInterval());
		assertEquals(3, properties.getMaxEventsPerRun());
	}

	@Test
	void 활성화된_Spring_scheduling은_짧은_relay_주기로_coordinator를_자동_호출한다() {
		reset(coordinator);

		verify(coordinator, timeout(2_000).atLeastOnce()).processBatch();
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SchedulingConfiguration {

		@Bean
		@Primary
		NotificationRelayCoordinator testNotificationRelayCoordinator() {
			return mock(NotificationRelayCoordinator.class);
		}
	}
}
