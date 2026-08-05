package cloud.bamsongi.albammate.infra.redis;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimeSignalGateway;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import lombok.RequiredArgsConstructor;

/** Redis 운영 프로필의 채널을 구독해 커밋 신호를 방별 로컬 WebSocket 연결 게이트웨이로 넘긴다. */
@Component
@Profile({"local-multi", "production"})
@RequiredArgsConstructor
class RedisChatRealtimeSubscriber implements MessageListener {

	private final ChatRealtimeSignalGateway signalGateway;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		decode(new String(message.getBody(), StandardCharsets.UTF_8)).ifPresent(signalGateway::onMessageCommitted);
	}

	static Optional<MessageCommitted> decode(String payload) {
		String[] parts = payload.split(":", 3);
		if (parts.length != 3) {
			return Optional.empty();
		}
		try {
			return Optional.of(new MessageCommitted(parts[0], Long.parseLong(parts[1]), Long.parseLong(parts[2])));
		} catch (RuntimeException exception) {
			// 형식이 어긋난 신호는 무시한다. 다음 정상 신호나 재연결의 PostgreSQL catch-up이 누락분을 복구한다.
			return Optional.empty();
		}
	}
}
