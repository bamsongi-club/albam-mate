package cloud.bamsongi.albammate.infra.redis;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

import lombok.extern.slf4j.Slf4j;

/**
 * local-multi 공용 Redis의 채팅 전달 channel 구독 container를 구성한다.
 *
 * <p>구독 시작을 {@code autoStartup}에 맡기지 않고 별도 스레드에서 시도한다. Redis가 기동 시점에 응답하지 않아도
 * 애플리케이션 시작 자체를 막지 않으며, 실패는 로그로만 남기고 다음 세션·전송 제한 확인이나 재연결의 PostgreSQL
 * catch-up으로 계속 복구된다.
 */
@Configuration(proxyBeanMethods = false)
@Profile("local-multi")
@Slf4j
class RedisChatRealtimeListenerConfiguration {

	@Bean
	RedisMessageListenerContainer chatRealtimeMessageListenerContainer(
		RedisConnectionFactory redisConnectionFactory, RedisChatRealtimeSubscriber subscriber) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(redisConnectionFactory);
		container.addMessageListener(subscriber, new ChannelTopic(RedisChatRealtimePublisher.CHANNEL));
		container.setAutoStartup(false);
		return container;
	}

	@Bean
	ApplicationListener<ApplicationReadyEvent> chatRealtimeSubscriptionStarter(
		RedisMessageListenerContainer chatRealtimeMessageListenerContainer) {
		return event -> new Thread(
			() -> startQuietly(chatRealtimeMessageListenerContainer), "chat-realtime-subscription-starter")
			.start();
	}

	private void startQuietly(RedisMessageListenerContainer container) {
		try {
			container.start();
		} catch (RuntimeException exception) {
			log.warn(
				"event=chat_realtime_subscription_start_failed exceptionType={}",
				exception.getClass().getName());
		}
	}
}
