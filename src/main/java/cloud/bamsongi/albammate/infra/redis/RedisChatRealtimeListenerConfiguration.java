package cloud.bamsongi.albammate.infra.redis;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import lombok.extern.slf4j.Slf4j;

/**
 * Redis 운영 프로필의 채팅 전달 channel 구독 container를 구성한다.
 *
 * <p>구독 시작을 {@code autoStartup}에 맡기지 않고 별도 스레드에서 시도한다. Redis가 기동 시점에 응답하지 않아도
 * 애플리케이션 시작 자체를 막지 않으며, 실패한 구독은 Spring Data Redis 기본 간격으로 다시 시도한다. 구독이
 * 복구되기 전 누락분은 다음 세션·전송 제한 확인이나 재연결의 PostgreSQL catch-up으로 계속 복구된다.
 */
@Configuration(proxyBeanMethods = false)
@Profile({"local", "production"})
@Slf4j
class RedisChatRealtimeListenerConfiguration {

	private static final Duration INITIAL_SUBSCRIPTION_RETRY_DELAY = Duration.ofMillis(
		RedisMessageListenerContainer.DEFAULT_RECOVERY_INTERVAL);

	@Bean
	RedisMessageListenerContainer chatRealtimeMessageListenerContainer(
		RedisConnectionFactory redisConnectionFactory, RedisChatRealtimeSubscriber subscriber,
		Environment environment) {
		RedisMessageListenerContainer container = new RedisMessageListenerContainer();
		container.setConnectionFactory(redisConnectionFactory);
		container.addMessageListener(subscriber, new ChannelTopic(RedisChatRealtimePublisher.channelFor(environment)));
		container.setAutoStartup(false);
		return container;
	}

	@Bean(destroyMethod = "shutdown")
	ThreadPoolTaskScheduler chatRealtimeSubscriptionRetryScheduler() {
		ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
		scheduler.setPoolSize(1);
		scheduler.setThreadNamePrefix("chat-realtime-subscription-retry-");
		scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
		return scheduler;
	}

	@Bean
	ApplicationListener<ApplicationReadyEvent> chatRealtimeSubscriptionStarter(
		RedisMessageListenerContainer chatRealtimeMessageListenerContainer,
		@Qualifier("chatRealtimeSubscriptionRetryScheduler") ThreadPoolTaskScheduler retryScheduler) {
		return event -> new Thread(
			() -> startQuietly(chatRealtimeMessageListenerContainer, retryScheduler),
			"chat-realtime-subscription-starter")
			.start();
	}

	void startQuietly(RedisMessageListenerContainer container, ThreadPoolTaskScheduler retryScheduler) {
		if (!retryScheduler.isRunning()) {
			return;
		}

		try {
			container.start();
		} catch (RuntimeException exception) {
			container.stop();
			log.atWarn().addKeyValue("event", "chat_realtime_subscription_start_failed")
				.addKeyValue("retryDelayMillis", INITIAL_SUBSCRIPTION_RETRY_DELAY.toMillis())
				.addKeyValue("exceptionType", exception.getClass().getName())
				.log("chat realtime subscription start failed");
			try {
				retryScheduler.schedule(
					() -> startQuietly(container, retryScheduler),
					Instant.now().plus(INITIAL_SUBSCRIPTION_RETRY_DELAY));
			} catch (RuntimeException retryException) {
				log.atWarn().addKeyValue("event", "chat_realtime_subscription_retry_schedule_failed")
					.addKeyValue("exceptionType", retryException.getClass().getName())
					.log("chat realtime subscription retry schedule failed");
			}
		}
	}
}
