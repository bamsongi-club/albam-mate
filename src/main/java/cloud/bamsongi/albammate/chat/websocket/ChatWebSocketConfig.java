package cloud.bamsongi.albammate.chat.websocket;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.socket.server.HandshakeHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

/** CHAT-03 WebSocket handshake에 필요한 속성과 컨테이너 upgrade 전략을 구성한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChatWebSocketProperties.class)
public class ChatWebSocketConfig {

	@Bean
	HandshakeHandler chatHandshakeHandler() {
		return new DefaultHandshakeHandler();
	}

	@Bean(destroyMethod = "shutdown")
	ThreadPoolTaskScheduler chatWebSocketTaskScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("chat-websocket-validation-");
		return scheduler;
	}
}
