package cloud.bamsongi.albammate.chat.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;

/** local-multi 외 프로필은 Redis 전송 제한의 적용 대상이 아니므로 예약을 만들지 않는다. */
@Configuration(proxyBeanMethods = false)
class ChatMessageRateLimitConfiguration {

	@Bean
	@Profile("!local-multi")
	ChatMessageRateLimiter nonLocalMultiChatMessageRateLimiter() {
		return (userId, roomId) -> () -> {};
	}
}
