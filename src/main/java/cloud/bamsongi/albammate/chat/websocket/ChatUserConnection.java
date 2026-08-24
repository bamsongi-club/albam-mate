package cloud.bamsongi.albammate.chat.websocket;

import java.util.concurrent.locks.ReentrantLock;

import org.springframework.web.socket.WebSocketSession;

/**
 * 이 인스턴스가 보유한 사용자 단위 연결 하나다.
 *
 * <p>{@link ChatUserConnectionRegistry}가 handshake 속성을 검증해 만든다. {@link #lock}은 같은 연결에 대한
 * 동시 전송을 직렬화해, 서로 다른 방의 커밋 신호가 겹쳐 도착해도 같은 세션에 대한 전송이 뒤섞이지 않게 한다.
 */
final class ChatUserConnection {

	final WebSocketSession session;
	final long userId;
	final ReentrantLock lock = new ReentrantLock();

	ChatUserConnection(WebSocketSession session, long userId) {
		this.session = session;
		this.userId = userId;
	}
}
