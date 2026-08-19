package cloud.bamsongi.albammate.monitoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.catalina.Service;
import org.apache.catalina.connector.Connector;
import org.apache.catalina.startup.Tomcat;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TomcatThreadPoolMetricsTest {

	@Test
	void T2_management_namespace_WebServer는_Tomcat_thread_pool_meter_등록에서_제외한다() {
		ServletWebServerApplicationContext management = new ServletWebServerApplicationContext();
		management.setServerNamespace("management");

		assertFalse(TomcatThreadPoolMetrics.isProductServer(management));
		assertTrue(TomcatThreadPoolMetrics.isProductServer(new ServletWebServerApplicationContext()));
	}

	@Test
	void T2_management과_non_Tomcat과_executor_없는_connector는_meter를_등록하지_않는다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		TomcatThreadPoolMetrics metrics = new TomcatThreadPoolMetrics(registry);
		TomcatWebServer tomcat = mock(TomcatWebServer.class);
		WebServerInitializedEvent managementEvent = event(managementContext(), tomcat);

		metrics.onApplicationEvent(managementEvent);
		assertTrue(registry.getMeters().isEmpty());
		verifyNoInteractions(tomcat);

		metrics.onApplicationEvent(event(productContext(), mock(WebServer.class)));
		assertTrue(registry.getMeters().isEmpty());

		metrics.onApplicationEvent(event(productContext(), tomcatServer(connectorWithoutExecutor())));
		assertTrue(registry.getMeters().isEmpty());
	}

	@Test
	void T2_제품_Tomcat의_모든_지원_connector를_name별_gauge로_등록한다() {
		SimpleMeterRegistry registry = new SimpleMeterRegistry();
		TomcatThreadPoolMetrics metrics = new TomcatThreadPoolMetrics(registry);

		metrics.onApplicationEvent(event(productContext(), tomcatServer(
			connector("app-http-8080", 2, 7, 9), connector("app-https-8443", 4, 8, 12))));

		assertGauges(registry, "app-http-8080", 2.0, 7.0, 9.0);
		assertGauges(registry, "app-https-8443", 4.0, 8.0, 12.0);
	}

	private WebServerInitializedEvent event(WebServerApplicationContext context, WebServer webServer) {
		WebServerInitializedEvent event = mock(WebServerInitializedEvent.class);
		when(event.getApplicationContext()).thenReturn(context);
		when(event.getWebServer()).thenReturn(webServer);
		return event;
	}

	private ServletWebServerApplicationContext managementContext() {
		ServletWebServerApplicationContext context = new ServletWebServerApplicationContext();
		context.setServerNamespace("management");
		return context;
	}

	private ServletWebServerApplicationContext productContext() {
		return new ServletWebServerApplicationContext();
	}

	private TomcatWebServer tomcatServer(Connector... connectors) {
		Tomcat tomcat = mock(Tomcat.class);
		Service service = mock(Service.class);
		when(tomcat.getService()).thenReturn(service);
		when(service.findConnectors()).thenReturn(connectors);
		TomcatWebServer server = mock(TomcatWebServer.class);
		when(server.getTomcat()).thenReturn(tomcat);
		return server;
	}

	private void assertGauges(SimpleMeterRegistry registry, String name, double busy, double current, double maximum) {
		assertEquals(busy, registry.get("tomcat.threads.busy").tag("name", name).gauge().value());
		assertEquals(current, registry.get("tomcat.threads.current").tag("name", name).gauge().value());
		assertEquals(maximum, registry.get("tomcat.threads.config.max").tag("name", name).gauge().value());
	}

	private Connector connector(String name, int busy, int current, int maximum) {
		ThreadPoolExecutor executor = mock(ThreadPoolExecutor.class);
		when(executor.getActiveCount()).thenReturn(busy);
		when(executor.getPoolSize()).thenReturn(current);
		when(executor.getMaximumPoolSize()).thenReturn(maximum);
		AbstractProtocol<?> protocol = mock(AbstractProtocol.class);
		when(protocol.getExecutor()).thenReturn(executor);
		when(protocol.getName()).thenReturn(name);
		Connector connector = mock(Connector.class);
		when(connector.getProtocolHandler()).thenReturn(protocol);
		return connector;
	}

	private Connector connectorWithoutExecutor() {
		ProtocolHandler protocol = mock(ProtocolHandler.class);
		Connector connector = mock(Connector.class);
		when(connector.getProtocolHandler()).thenReturn(protocol);
		return connector;
	}
}
