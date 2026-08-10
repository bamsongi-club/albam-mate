package cloud.bamsongi.albammate.chat.websocket;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimeSignalGateway;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.room.contract.ChatWebSocketAccessChecker;
import lombok.RequiredArgsConstructor;

/**
 * ADR-0032가 정한 CHAT-03 WebSocket은 서버 발신 전용이다.
 *
 * <p>클라이언트가 보낸 애플리케이션 메시지 프레임은 저장하지 않고 정책 위반으로 연결을 종료한다. 각 인스턴스는 자신이 보유한 연결만
 * 메모리에서 관리하며, {@link ChatRealtimeSignalGateway#onMessageCommitted(MessageCommitted)}로 받은 커밋 신호는 신호
 * payload를 그대로 보내지 않고 연결별 마지막 전달 ID 이후의 PostgreSQL 메시지를 {@code messageId} 오름차순으로 다시 조회해
 * 중복 없이 전달한다.
 *
 * <p>연결 등록·해제와 방별 조회는 {@link ChatConnectionRegistry}가, catch-up 메시지 전달은
 * {@link ChatMessageDeliveryService}가 맡는다. 이 클래스는 {@link WebSocketHandler}·
 * {@link ChatRealtimeSignalGateway} 어댑터로서 두 컴포넌트에 위임하고, 세션·접근 재검증처럼 HTTP 인증 상태에 걸친
 * 판정만 직접 수행한다.
 */
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler implements WebSocketHandler, ChatRealtimeSignalGateway {

	static final String ROOM_ID_ATTRIBUTE = "chat.room.id";
	static final String USER_ID_ATTRIBUTE = "chat.user.id";
	static final String SESSION_ID_ATTRIBUTE = "chat.session.id";
	static final String AFTER_MESSAGE_ID_ATTRIBUTE = "chat.after.message.id";

	private final ChatAccessGuard chatAccessGuard;
	private final ChatWebSocketAccessChecker chatWebSocketAccessChecker;
	private final SessionRepository<? extends Session> sessionRepository;
	// Redis 프로필이 구독 재시도용 TaskScheduler를 함께 등록하므로 빈 이름과 같은 필드명으로 대상을 고정한다.
	private final TaskScheduler chatWebSocketTaskScheduler;
	private final ChatWebSocketProperties properties;
	private final ChatConnectionRegistry connectionRegistry;
	private final ChatMessageDeliveryService messageDeliveryService;
	private final ChatWebSocketMetrics metrics;
	private final AtomicBoolean accessValidationScheduled = new AtomicBoolean();

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		ChatRoomConnection connection = connectionRegistry.register(session);
		if (connection == null) {
			connectionRegistry.closeForPolicyViolation(session);
			return;
		}

		scheduleAccessValidation();
		deliver(connection);
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

	/** Redis 등 전달 신호가 알려온 방의 로컬 연결마다 PostgreSQL catch-up 전달을 다시 시도한다. */
	@Override
	public void onMessageCommitted(MessageCommitted event) {
		Set<ChatRoomConnection> connections = connectionRegistry.findByRoomId(event.roomId());
		for (ChatRoomConnection connection : connections) {
			deliver(connection);
		}
	}

	/**
	 * 전달 직전에 접근·세션 유효성을 다시 확인하고, catch-up 전달을 {@link ChatMessageDeliveryService}에 위임한다.
	 *
	 * <p>연결마다 하나의 잠금으로 직렬화하므로, 최초 연결의 catch-up과 이후 신호가 촉진한 재조회, 그리고 스케줄 접근
	 * 재검증이 겹쳐도 중복·누락 없이 합류한다.
	 */
	private void deliver(ChatRoomConnection connection) {
		connection.lock.lock();
		try {
			if (connectionRegistry.shouldStopDelivery(connection.session)) {
				return;
			}
			if (!isDeliveryAccessValid(connection.session)) {
				connectionRegistry.closeForPolicyViolation(connection.session);
				return;
			}
			messageDeliveryService.deliverNewMessages(connection);
		} catch (RuntimeException exception) {
			metrics.recordDeliveryFailure();
		} finally {
			connection.lock.unlock();
		}
	}

	private void scheduleAccessValidation() {
		if (accessValidationScheduled.compareAndSet(false, true)) {
			Duration interval = properties.getAccessValidationInterval();
			chatWebSocketTaskScheduler.scheduleAtFixedRate(this::validateAccess, interval);
		}
	}

	private void validateAccess() {
		connectionRegistry.snapshotByRoomId().forEach((roomId, connections) -> {
			try {
				chatWebSocketAccessChecker.correctRoomState(roomId);
			} catch (RuntimeException exception) {
				connections.forEach(this::closeForPolicyViolation);
				return;
			}
			connections.forEach(this::validateCurrentAccess);
		});
	}

	private void validateCurrentAccess(ChatRoomConnection connection) {
		WebSocketSession session = connection.session;
		connection.lock.lock();
		try {
			if (connectionRegistry.shouldStopDelivery(session)) {
				return;
			}
			if (!isCurrentAccessValid(session)) {
				connectionRegistry.closeForPolicyViolation(session);
			}
		} finally {
			connection.lock.unlock();
		}
	}

	private void closeForPolicyViolation(ChatRoomConnection connection) {
		connection.lock.lock();
		try {
			connectionRegistry.closeForPolicyViolation(connection.session);
		} finally {
			connection.lock.unlock();
		}
	}

	/** 참가 취소·방 최종 상태 전이·세션 만료 신호가 유실돼도 전달 직전마다 다시 확인하는 유일한 판정이다. */
	private boolean isDeliveryAccessValid(WebSocketSession session) {
		Map<String, Object> attributes = session.getAttributes();
		Long roomId = attribute(attributes, ROOM_ID_ATTRIBUTE, Long.class);
		Long userId = attribute(attributes, USER_ID_ATTRIBUTE, Long.class);
		String sessionId = attribute(attributes, SESSION_ID_ATTRIBUTE, String.class);
		try {
			if (roomId == null || userId == null || sessionId == null
				|| sessionRepository.findById(sessionId) == null) {
				return false;
			}
			chatAccessGuard.executeWithAccess(userId, roomId, () -> null);
			return true;
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private boolean isCurrentAccessValid(WebSocketSession session) {
		Map<String, Object> attributes = session.getAttributes();
		Long roomId = attribute(attributes, ROOM_ID_ATTRIBUTE, Long.class);
		Long userId = attribute(attributes, USER_ID_ATTRIBUTE, Long.class);
		String sessionId = attribute(attributes, SESSION_ID_ATTRIBUTE, String.class);
		try {
			if (roomId == null || userId == null || sessionId == null
				|| sessionRepository.findById(sessionId) == null) {
				return false;
			}
			chatWebSocketAccessChecker.verifyCurrentAccess(userId, roomId);
			return true;
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private <T> T attribute(Map<String, Object> attributes, String name, Class<T> type) {
		Object value = attributes.get(name);
		return type.isInstance(value) ? type.cast(value) : null;
	}
}
