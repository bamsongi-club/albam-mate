package cloud.bamsongi.albammate.chat.match;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.springframework.web.socket.WebSocketSession;

/**
 * 이 인스턴스가 보유한 연결 하나의 Party·catch-up 진행 상태다.
 *
 * <p>{@link MatchChatConnectionRegistry}가 handshake 속성을 검증해 만들고, {@link MatchChatMessageDeliveryService}가
 * {@link #lastDeliveredMessageId}를 갱신한다. {@link #lock}은 전달을 같은 연결에 대해 하나로 직렬화하는 데 쓴다.
 */
final class MatchChatPartyConnection {

	final WebSocketSession session;
	final long partyId;
	final long matchChatRoomId;
	final long userId;
	final AtomicLong lastDeliveredMessageId;
	final ReentrantLock lock = new ReentrantLock();

	MatchChatPartyConnection(WebSocketSession session, long partyId, long matchChatRoomId, long userId, long baseline) {
		this.session = session;
		this.partyId = partyId;
		this.matchChatRoomId = matchChatRoomId;
		this.userId = userId;
		this.lastDeliveredMessageId = new AtomicLong(baseline);
	}
}
