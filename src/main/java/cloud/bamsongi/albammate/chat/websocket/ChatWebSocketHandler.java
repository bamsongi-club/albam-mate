package cloud.bamsongi.albammate.chat.websocket;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import lombok.RequiredArgsConstructor;

/**
 * ADR-0032가 정한 CHAT-03 WebSocket은 서버 발신 전용이다.
 *
 * <p>클라이언트가 보낸 애플리케이션 메시지 프레임은 저장하지 않고 정책 위반으로 연결을 종료한다.
 */
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler implements WebSocketHandler {

	static final String ROOM_ID_ATTRIBUTE = "chat.room.id";
	static final String USER_ID_ATTRIBUTE = "chat.user.id";
	static final String SESSION_ID_ATTRIBUTE = "chat.session.id";

	private final ChatAccessGuard chatAccessGuard;
	private final SessionRepository<? extends Session> sessionRepository;
	private final TaskScheduler taskScheduler;
	private final ChatWebSocketProperties properties;
	private Set<WebSocketSession> closeRequestedSessions = ConcurrentHashMap.newKeySet();

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		closeRequestedSessions.remove(session);
		Duration interval = properties.getAccessValidationInterval();
		ScheduledFuture<?> validation = taskScheduler.scheduleAtFixedRate(
			() -> validateAccess(session), interval);
		session.getAttributes().put(SCHEDULED_VALIDATION_ATTRIBUTE, validation);
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
		Object validation = session.getAttributes().remove(SCHEDULED_VALIDATION_ATTRIBUTE);
		closeRequestedSessions.remove(session);
		if (validation instanceof ScheduledFuture<?> future) {
			future.cancel(false);
		}
	}

	@Override
	public boolean supportsPartialMessages() {
		return false;
	}

	private static final String SCHEDULED_VALIDATION_ATTRIBUTE = "chat.access.validation";

	private void validateAccess(WebSocketSession session) {
		if (!session.isOpen()) {
			return;
		}
		Map<String, Object> attributes = session.getAttributes();
		Long roomId = attribute(attributes, ROOM_ID_ATTRIBUTE, Long.class);
		Long userId = attribute(attributes, USER_ID_ATTRIBUTE, Long.class);
		String sessionId = attribute(attributes, SESSION_ID_ATTRIBUTE, String.class);
		try {
			if (roomId == null || userId == null || sessionId == null
				|| sessionRepository.findById(sessionId) == null) {
				closeForPolicyViolation(session);
				return;
			}
			chatAccessGuard.executeWithAccess(userId, roomId, () -> null);
		} catch (RuntimeException exception) {
			closeForPolicyViolation(session);
		}
	}

	private void closeForPolicyViolation(WebSocketSession session) {
		if (session.isOpen() && closeRequestedSessions.add(session)) {
			try {
				session.close(CloseStatus.POLICY_VIOLATION);
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
