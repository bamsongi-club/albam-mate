package cloud.bamsongi.albammate.monitoring;

import java.util.Arrays;

import org.apache.catalina.connector.Connector;
import org.apache.coyote.AbstractProtocol;
import org.apache.coyote.ProtocolHandler;
import org.apache.tomcat.util.threads.ThreadPoolExecutor;
import org.springframework.boot.tomcat.TomcatWebServer;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/** Spring Boot 4에서 기본 binder가 제공하지 않는 Tomcat connector thread pool을 관측한다. */
@Component
public final class TomcatThreadPoolMetrics implements ApplicationListener<WebServerInitializedEvent> {

	private final MeterRegistry meterRegistry;

	public TomcatThreadPoolMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;
	}

	@Override
	public void onApplicationEvent(WebServerInitializedEvent event) {
		if (!isProductServer(event.getApplicationContext())
			|| !(event.getWebServer() instanceof TomcatWebServer tomcatWebServer)) {
			return;
		}
		Arrays.stream(tomcatWebServer.getTomcat().getService().findConnectors())
			.forEach(this::registerIfThreadPoolExecutor);
	}

	static boolean isProductServer(WebServerApplicationContext applicationContext) {
		return applicationContext.getServerNamespace() == null;
	}

	private void registerIfThreadPoolExecutor(Connector connector) {
		ProtocolHandler handler = connector.getProtocolHandler();
		if (!(handler instanceof AbstractProtocol<?> protocol)
			|| !(protocol.getExecutor() instanceof ThreadPoolExecutor executor)) {
			return;
		}
		register(protocol.getName(), executor);
	}

	private void register(String name, ThreadPoolExecutor executor) {
		String[] tags = {"name", name};
		Gauge.builder("tomcat.threads.busy", executor, ThreadPoolExecutor::getActiveCount).tags(tags)
			.register(meterRegistry);
		Gauge.builder("tomcat.threads.current", executor, ThreadPoolExecutor::getPoolSize).tags(tags)
			.register(meterRegistry);
		Gauge.builder("tomcat.threads.config.max", executor, ThreadPoolExecutor::getMaximumPoolSize)
			.tags(tags)
			.register(meterRegistry);
	}
}
