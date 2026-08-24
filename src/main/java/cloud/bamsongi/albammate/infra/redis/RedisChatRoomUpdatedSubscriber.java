package cloud.bamsongi.albammate.infra.redis;

import java.nio.charset.StandardCharsets;

import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import cloud.bamsongi.albammate.chat.contract.ChatRoomUpdatedSignalGateway;
import lombok.RequiredArgsConstructor;

/**
 * CHAT-08 사용자 단위 팬아웃 전용 구독자다.
 *
 * <p>{@link RedisChatRealtimeSubscriber}와 같은 Redis 채널을 함께 구독하는 두 번째 리스너다. 방 단위 팬아웃
 * ({@link RedisChatRealtimeSubscriber})과는 완전히 독립된 경로이며, 같은 신호를 받아 그 방의 현재 참가자에게
 * 최소 이벤트를 전달하도록 {@link ChatRoomUpdatedSignalGateway}에 위임한다.
 */
@Component
@Profile({"local", "production"})
@RequiredArgsConstructor
class RedisChatRoomUpdatedSubscriber implements MessageListener {

	private final ChatRoomUpdatedSignalGateway signalGateway;

	@Override
	public void onMessage(Message message, byte[] pattern) {
		RedisChatRealtimeSubscriber.decode(new String(message.getBody(), StandardCharsets.UTF_8))
			.ifPresent(signalGateway::onMessageCommitted);
	}
}
