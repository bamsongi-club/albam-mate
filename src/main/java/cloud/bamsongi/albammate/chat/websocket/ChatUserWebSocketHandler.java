package cloud.bamsongi.albammate.chat.websocket;

import java.io.IOException;
import java.util.List;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.contract.ChatRoomUpdatedSignalGateway;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.room.contract.ChatRoomParticipantsQuery;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0082가 정한 CHAT-08 사용자 단위 WebSocket은 서버 발신 전용이다.
 *
 * <p>클라이언트가 보낸 애플리케이션 메시지 프레임은 저장하지 않고 정책 위반으로 연결을 종료한다. 기존 방 단위
 * {@link ChatWebSocketHandler}와 별개로, 커밋 신호를 받으면 {@link ChatRoomParticipantsQuery}로 그 방의 현재
 * 참가자 user id를 조회해 이 인스턴스에 연결된 참가자에게 최소 이벤트만 전송한다.
 */
@Component
@RequiredArgsConstructor
public class ChatUserWebSocketHandler implements WebSocketHandler, ChatRoomUpdatedSignalGateway {

	static final String USER_ID_ATTRIBUTE = "chat.user.ws.user.id";

	private final ChatUserConnectionRegistry connectionRegistry;
	private final ChatRoomParticipantsQuery chatRoomParticipantsQuery;
	private final ObjectMapper objectMapper;

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		ChatUserConnection connection = connectionRegistry.register(session);
		if (connection == null) {
			connectionRegistry.closeForPolicyViolation(session);
		}
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
		connectionRegistry.unregister(session);
	}

	@Override
	public boolean supportsPartialMessages() {
		return false;
	}

	/**
	 * 참가자 조회·전송 실패가 원인 커밋이나 다른 참가자 전달에 번지지 않는 best-effort 팬아웃이다.
	 *
	 * <p>참가자 조회 실패는 전체 팬아웃을 건너뛰고, 한 연결에 대한 전송 실패는 그 연결만 종료할 뿐 나머지 참가자
	 * 전달을 막지 않는다.
	 */
	@Override
	public void onMessageCommitted(MessageCommitted event) {
		List<Long> participantUserIds;
		try {
			participantUserIds = chatRoomParticipantsQuery.findCurrentParticipantUserIds(event.roomId());
		} catch (RuntimeException exception) {
			return;
		}
		String payload = serialize(event);
		if (payload == null) {
			return;
		}
		for (Long userId : participantUserIds) {
			for (ChatUserConnection connection : connectionRegistry.findByUserId(userId)) {
				send(connection, payload);
			}
		}
	}

	private String serialize(MessageCommitted event) {
		try {
			return objectMapper.writeValueAsString(new ChatRoomUpdatedEvent(event.roomId(), event.messageId()));
		} catch (RuntimeException exception) {
			return null;
		}
	}

	private void send(ChatUserConnection connection, String payload) {
		connection.lock.lock();
		try {
			if (connectionRegistry.shouldStopDelivery(connection.session)) {
				return;
			}
			connection.session.sendMessage(new TextMessage(payload));
		} catch (IOException | RuntimeException exception) {
			connectionRegistry.closeForTransportFailure(connection.session);
		} finally {
			connection.lock.unlock();
		}
	}
}
