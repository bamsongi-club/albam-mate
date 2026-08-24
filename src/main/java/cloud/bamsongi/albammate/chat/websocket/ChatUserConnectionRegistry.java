package cloud.bamsongi.albammate.chat.websocket;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * CHAT-08 사용자 단위 WebSocket 연결의 등록·해제 상태를 이 인스턴스 메모리에서 관리한다.
 *
 * <p>기존 방 단위 {@link ChatConnectionRegistry}(roomId 키)와는 별개의 자료구조이며, userId를 키로 연결을
 * 관리한다. 한 사용자가 여러 탭·기기에서 동시에 연결하면 그 userId 아래 여러 연결이 함께 등록된다.
 */
@Component
class ChatUserConnectionRegistry {

	private final Set<WebSocketSession> closeRequestedSessions = ConcurrentHashMap.newKeySet();
	private final Map<WebSocketSession, ChatUserConnection> connectionsBySession = new ConcurrentHashMap<>();
	private final Map<Long, Set<ChatUserConnection>> connectionsByUserId = new ConcurrentHashMap<>();
	private final Object connectionIndexLock = new Object();

	/** handshake 속성에 사용자 ID가 있는 세션만 등록하고, 그렇지 않으면 {@code null}을 돌려준다. */
	ChatUserConnection register(WebSocketSession session) {
		closeRequestedSessions.remove(session);
		Long userId = attribute(session.getAttributes(), ChatUserWebSocketHandler.USER_ID_ATTRIBUTE, Long.class);
		if (userId == null) {
			return null;
		}
		ChatUserConnection connection = new ChatUserConnection(session, userId);
		synchronized (connectionIndexLock) {
			connectionsBySession.put(session, connection);
			connectionsByUserId.compute(userId, (key, connections) -> {
				Set<ChatUserConnection> updated = connections == null ? ConcurrentHashMap.newKeySet() : connections;
				updated.add(connection);
				return updated;
			});
		}
		return connection;
	}

	void unregister(WebSocketSession session) {
		closeRequestedSessions.remove(session);
		synchronized (connectionIndexLock) {
			ChatUserConnection connection = connectionsBySession.remove(session);
			if (connection == null) {
				return;
			}
			connectionsByUserId.computeIfPresent(connection.userId, (key, connections) -> {
				connections.remove(connection);
				return connections.isEmpty() ? null : connections;
			});
		}
	}

	Set<ChatUserConnection> findByUserId(long userId) {
		synchronized (connectionIndexLock) {
			Set<ChatUserConnection> connections = connectionsByUserId.get(userId);
			return connections == null ? Set.of() : Set.copyOf(connections);
		}
	}

	boolean shouldStopDelivery(WebSocketSession session) {
		return closeRequestedSessions.contains(session) || !session.isOpen();
	}

	void closeForPolicyViolation(WebSocketSession session) {
		closeSessionOnce(session, CloseStatus.POLICY_VIOLATION);
	}

	void closeForTransportFailure(WebSocketSession session) {
		closeSessionOnce(session, CloseStatus.SERVER_ERROR);
	}

	private void closeSessionOnce(WebSocketSession session, CloseStatus closeStatus) {
		if (session.isOpen() && closeRequestedSessions.add(session)) {
			try {
				session.close(closeStatus);
			} catch (Exception ignored) {
				// 연결 종료 경합은 이미 닫힌 연결과 동일하게 처리한다.
			}
		}
	}

	private <T> T attribute(Map<String, Object> attributes, String name, Class<T> type) {
		Object value = attributes.get(name);
		return type.isInstance(value) ? type.cast(value) : null;
	}
}
