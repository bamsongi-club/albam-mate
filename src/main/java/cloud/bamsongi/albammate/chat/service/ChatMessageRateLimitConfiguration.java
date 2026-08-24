package cloud.bamsongi.albammate.chat.service;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;

/** local과 production은 infra의 {@code RedisChatMessageRateLimiter}가 자체 등록해 Redis 전송 제한을 적용하고,
 * 그 외 프로필만 이 설정이 예약을 만들지 않는 no-op을 등록한다. */
@Configuration(proxyBeanMethods = false)
class ChatMessageRateLimitConfiguration {

	@Bean
	@Profile("!local & !production")
	ChatMessageRateLimiter nonLocalChatMessageRateLimiter() {
		return (userId, roomId) -> () -> {};
	}
}
