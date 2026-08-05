package cloud.bamsongi.albammate.chat.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * ADR-0032가 정한 CHAT-03 WebSocket은 서버 발신 전용이다.
 *
 * <p>클라이언트가 보낸 애플리케이션 메시지 프레임은 저장하지 않고 정책 위반으로 연결을 종료한다.
 */
@Component
public class ChatWebSocketHandler implements WebSocketHandler {

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		// 연결 수립만 확인한다. 실시간 전달·재연결 복구는 이 클래스의 범위가 아니다.
	}

	@Override
	public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
		session.close(CloseStatus.POLICY_VIOLATION);
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
		if (session.isOpen()) {
			session.close(CloseStatus.SERVER_ERROR);
		}
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) {
		// 연결별 상태 관리는 이 클래스의 범위가 아니다.
	}

	@Override
	public boolean supportsPartialMessages() {
		return false;
	}
}
