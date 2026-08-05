package cloud.bamsongi.albammate.chat.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;

/** Redis를 사용하지 않는 개발·테스트 프로필에서 커밋 후 포트를 안전하게 닫는 기본 구현이다. */
@Component
@Profile("!local-multi & !production")
class NoOpChatRealtimePublisher implements ChatRealtimePublisher {

	@Override
	public void publish(MessageCommitted event) {
		// CHAT-03 Redis adapter가 이 포트를 대체한다.
	}
}
