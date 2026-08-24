package cloud.bamsongi.albammate.chat.websocket;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.web.socket.WebSocketSession;

/**
 * 이 인스턴스가 보유한 연결 하나의 방·catch-up 진행 상태다.
 *
 * <p>{@link ChatConnectionRegistry}가 handshake 속성을 검증해 만들고, {@link ChatMessageDeliveryService}가
 * {@link #lastDeliveredMessageId}를 갱신한다. {@link #lock}은 전달과 스케줄 접근 재검증을 같은 연결에 대해
 * 하나로 직렬화하는 데 쓴다.
 */
final class ChatRoomConnection {

	final WebSocketSession session;
	final long roomId;
	final long chatRoomId;
	final long userId;
	final AtomicLong lastDeliveredMessageId;
	final ReentrantLock lock = new ReentrantLock();

	ChatRoomConnection(WebSocketSession session, long roomId, long chatRoomId, long userId, long baseline) {
		this.session = session;
		this.roomId = roomId;
		this.chatRoomId = chatRoomId;
		this.userId = userId;
		this.lastDeliveredMessageId = new AtomicLong(baseline);
	}
}
