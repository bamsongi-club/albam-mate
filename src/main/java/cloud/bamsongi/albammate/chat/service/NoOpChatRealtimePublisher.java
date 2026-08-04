package cloud.bamsongi.albammate.chat.service;

import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimePublisher;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;

/** Redis 전달 어댑터가 도입되기 전, 커밋 후 포트를 안전하게 닫는 기본 구현이다. */
@Component
class NoOpChatRealtimePublisher implements ChatRealtimePublisher {

	@Override
	public void publish(MessageCommitted event) {
		// CHAT-03 Redis adapter가 이 포트를 대체한다.
	}
}
