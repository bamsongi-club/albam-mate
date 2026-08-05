package cloud.bamsongi.albammate.chat.websocket;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.contract.ChatRealtimeSignalGateway;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.chat.dto.ChatMessageEvent;
import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/**
 * ADR-0032가 정한 CHAT-03 WebSocket은 서버 발신 전용이다.
 *
 * <p>클라이언트가 보낸 애플리케이션 메시지 프레임은 저장하지 않고 정책 위반으로 연결을 종료한다. 각 인스턴스는 자신이 보유한 연결만
 * 메모리에서 관리하며, {@link ChatRealtimeSignalGateway#onMessageCommitted(MessageCommitted)}로 받은 커밋 신호는 신호
 * payload를 그대로 보내지 않고 연결별 마지막 전달 ID 이후의 PostgreSQL 메시지를 {@code messageId} 오름차순으로 다시 조회해
 * 중복 없이 전달한다.
 */
@Component
@RequiredArgsConstructor
public class ChatWebSocketHandler implements WebSocketHandler, ChatRealtimeSignalGateway {

	static final String ROOM_ID_ATTRIBUTE = "chat.room.id";
	static final String USER_ID_ATTRIBUTE = "chat.user.id";
	static final String SESSION_ID_ATTRIBUTE = "chat.session.id";
	static final String AFTER_MESSAGE_ID_ATTRIBUTE = "chat.after.message.id";

	private static final String SCHEDULED_VALIDATION_ATTRIBUTE = "chat.access.validation";

	private final ChatAccessGuard chatAccessGuard;
	private final SessionRepository<? extends Session> sessionRepository;
	private final TaskScheduler taskScheduler;
	private final ChatWebSocketProperties properties;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final UserQuery userQuery;
	private final ChatWebSocketMetrics metrics;
	private final ObjectMapper objectMapper;
	private final Clock clock;

	private final Set<WebSocketSession> closeRequestedSessions = ConcurrentHashMap.newKeySet();
	private final Map<WebSocketSession, RoomConnection> connectionsBySession = new ConcurrentHashMap<>();
	private final Map<Long, Set<RoomConnection>> connectionsByRoomId = new ConcurrentHashMap<>();

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		closeRequestedSessions.remove(session);
		RoomConnection connection = registerConnection(session);
		if (connection == null) {
			closeForPolicyViolation(session);
			return;
		}

		Duration interval = properties.getAccessValidationInterval();
		ScheduledFuture<?> validation = taskScheduler.scheduleAtFixedRate(
			() -> validateAccess(session), interval);
		session.getAttributes().put(SCHEDULED_VALIDATION_ATTRIBUTE, validation);

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
		Object validation = session.getAttributes().remove(SCHEDULED_VALIDATION_ATTRIBUTE);
		closeRequestedSessions.remove(session);
		unregisterConnection(session);
		if (validation instanceof ScheduledFuture<?> future) {
			future.cancel(false);
		}
	}

	@Override
	public boolean supportsPartialMessages() {
		return false;
	}

	/** Redis 등 전달 신호가 알려온 방의 로컬 연결마다 PostgreSQL catch-up 전달을 다시 시도한다. */
	@Override
	public void onMessageCommitted(MessageCommitted event) {
		Set<RoomConnection> connections = connectionsByRoomId.get(event.roomId());
		if (connections == null || connections.isEmpty()) {
			return;
		}
		for (RoomConnection connection : Set.copyOf(connections)) {
			deliver(connection);
		}
	}

	private RoomConnection registerConnection(WebSocketSession session) {
		Map<String, Object> attributes = session.getAttributes();
		Long roomId = attribute(attributes, ROOM_ID_ATTRIBUTE, Long.class);
		Long userId = attribute(attributes, USER_ID_ATTRIBUTE, Long.class);
		if (roomId == null || userId == null) {
			return null;
		}
		Long chatRoomId = chatRoomRepository.findByRoomId(roomId).map(ChatRoom::getId).orElse(null);
		if (chatRoomId == null) {
			return null;
		}
		Long afterMessageId = attribute(attributes, AFTER_MESSAGE_ID_ATTRIBUTE, Long.class);
		long baseline = initialBaseline(chatRoomId, afterMessageId);
		RoomConnection connection = new RoomConnection(session, roomId, chatRoomId, userId, baseline);
		connectionsBySession.put(session, connection);
		connectionsByRoomId.computeIfAbsent(roomId, key -> ConcurrentHashMap.newKeySet()).add(connection);
		metrics.connectionOpened();
		return connection;
	}

	private void unregisterConnection(WebSocketSession session) {
		RoomConnection connection = connectionsBySession.remove(session);
		if (connection == null) {
			return;
		}
		Set<RoomConnection> connections = connectionsByRoomId.get(connection.roomId);
		if (connections != null) {
			connections.remove(connection);
			connectionsByRoomId.computeIfPresent(
				connection.roomId, (key, existing) -> existing.isEmpty() ? null : existing);
		}
		metrics.connectionClosed();
	}

	private long latestMessageId(long chatRoomId) {
		return chatMessageRepository
			.findByChatRoomIdOrderByIdDesc(chatRoomId, PageRequest.of(0, 1))
			.stream()
			.findFirst()
			.map(ChatMessage::getId)
			.orElse(0L);
	}

	/**
	 * 현재 방에 없는 cursor는 과거 메시지를 건너뛰게 하므로 이력 처음부터 복구한다.
	 *
	 * <p>현재 이력보다 큰 cursor만 최신 ID로 제한한다. 이는 아직 저장되지 않은 미래 ID가 이후 메시지 전달을 막지 않게
	 * 하되, 다른 방의 더 작은 메시지 ID를 현재 방의 기준으로 오인하지 않게 한다.
	 */
	private long initialBaseline(long chatRoomId, Long afterMessageId) {
		long latestMessageId = latestMessageId(chatRoomId);
		if (afterMessageId == null) {
			return latestMessageId;
		}
		if (afterMessageId > latestMessageId) {
			return latestMessageId;
		}
		return chatMessageRepository.existsByIdAndChatRoomId(afterMessageId, chatRoomId) ? afterMessageId : 0L;
	}

	/**
	 * 전달 직전에 접근·세션 유효성을 다시 확인하고, 연결별 마지막 전달 ID 이후의 메시지를 오름차순으로 전달한다.
	 *
	 * <p>연결마다 하나의 잠금으로 직렬화하므로, 최초 연결의 catch-up과 이후 신호가 촉진한 재조회가 겹쳐도 중복·누락 없이
	 * 합류한다.
	 */
	private void deliver(RoomConnection connection) {
		connection.lock.lock();
		try {
			if (closeRequestedSessions.contains(connection.session) || !connection.session.isOpen()) {
				return;
			}
			if (!isAccessValid(connection.session)) {
				closeForPolicyViolation(connection.session);
				return;
			}
			deliverNewMessages(connection);
		} catch (RuntimeException exception) {
			metrics.recordDeliveryFailure();
		} finally {
			connection.lock.unlock();
		}
	}

	private void deliverNewMessages(RoomConnection connection) {
		List<ChatMessage> newMessages = chatMessageRepository
			.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(connection.chatRoomId,
				connection.lastDeliveredMessageId.get());
		if (newMessages.isEmpty()) {
			return;
		}
		Map<Long, String> nicknames = userQuery.findNicknamesByIds(
			newMessages.stream().map(ChatMessage::getSenderUserId).collect(Collectors.toSet()));
		int delivered = 0;
		for (ChatMessage message : newMessages) {
			if (closeRequestedSessions.contains(connection.session) || !connection.session.isOpen()) {
				break;
			}
			ChatMessageResponse response = ChatMessageResponse.from(
				message,
				connection.roomId,
				nicknames.getOrDefault(message.getSenderUserId(), ""),
				message.getSenderUserId().equals(connection.userId));
			if (!send(connection.session, ChatMessageEvent.messageCreated(response))) {
				metrics.recordDeliveryFailure();
				closeForTransportFailure(connection.session);
				break;
			}
			connection.lastDeliveredMessageId.set(message.getId());
			metrics.recordDeliveryLatency(Duration.between(message.getCreatedAt(), clock.instant()));
			delivered++;
		}
		metrics.recordRecoveredMessages(delivered);
	}

	private boolean send(WebSocketSession session, ChatMessageEvent event) {
		try {
			session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
			return true;
		} catch (IOException | RuntimeException exception) {
			return false;
		}
	}

	private void validateAccess(WebSocketSession session) {
		RoomConnection connection = connectionsBySession.get(session);
		if (connection == null) {
			return;
		}
		connection.lock.lock();
		try {
			if (closeRequestedSessions.contains(session) || !session.isOpen()) {
				return;
			}
			if (!isAccessValid(session)) {
				closeForPolicyViolation(session);
			}
		} finally {
			connection.lock.unlock();
		}
	}

	/** 참가 취소·방 최종 상태 전이·세션 만료 신호가 유실돼도 전달 직전마다 다시 확인하는 유일한 판정이다. */
	private boolean isAccessValid(WebSocketSession session) {
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

	private void closeForPolicyViolation(WebSocketSession session) {
		closeSessionOnce(session, CloseStatus.POLICY_VIOLATION);
	}

	private void closeForTransportFailure(WebSocketSession session) {
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

	/** 이 인스턴스가 보유한 연결 하나의 방·catch-up 진행 상태다. */
	private static final class RoomConnection {

		private final WebSocketSession session;
		private final long roomId;
		private final long chatRoomId;
		private final long userId;
		private final AtomicLong lastDeliveredMessageId;
		private final ReentrantLock lock = new ReentrantLock();

		private RoomConnection(WebSocketSession session, long roomId, long chatRoomId, long userId, long baseline) {
			this.session = session;
			this.roomId = roomId;
			this.chatRoomId = chatRoomId;
			this.userId = userId;
			this.lastDeliveredMessageId = new AtomicLong(baseline);
		}
	}
}
