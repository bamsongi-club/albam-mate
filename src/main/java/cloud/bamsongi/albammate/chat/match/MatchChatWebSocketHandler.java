package cloud.bamsongi.albammate.chat.match;

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

import cloud.bamsongi.albammate.chat.match.contract.MatchChatMessageCommitted;
import cloud.bamsongi.albammate.chat.match.contract.MatchChatRealtimeSignalGateway;
import cloud.bamsongi.albammate.chat.websocket.ChatWebSocketProperties;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;
import lombok.RequiredArgsConstructor;

/**
 * ADR-0080이 정한 MATCH 채팅 WebSocket은 서버 발신 전용이다.
 *
 * <p>클라이언트가 보낸 애플리케이션 메시지 프레임은 저장하지 않고 정책 위반으로 연결을 종료한다. 각 인스턴스는 자신이 보유한 연결만
 * 메모리에서 관리하며, {@link MatchChatRealtimeSignalGateway#onMessageCommitted(MatchChatMessageCommitted)}로 받은
 * 커밋 신호는 신호 payload를 그대로 보내지 않고 연결별 마지막 전달 ID 이후의 PostgreSQL 메시지를 {@code messageId}
 * 오름차순으로 다시 조회해 중복 없이 전달한다.
 *
 * <p>연결 등록·해제와 Party별 조회는 {@link MatchChatConnectionRegistry}가, catch-up 메시지 전달은
 * {@link MatchChatMessageDeliveryService}가 맡는다. 이 클래스는 {@link WebSocketHandler}·
 * {@link MatchChatRealtimeSignalGateway} 어댑터로서 두 컴포넌트에 위임하고, 전달 직전 접근·세션 재검증과 새 메시지 없이도
 * 접근 상실을 감지하는 주기 재검증만 직접 수행한다.
 *
 * <p>P1과 달리 접근 판정은 {@link MatchPartyAccessQuery#evaluateChatAccess(long, long)} 단일 조회로 충분하다 —
 * MATCH의 3-way 판정 자체가 이미 준비중·거부·허용을 구분하기 때문에 P1의 방 상태 보정(correctRoomState)에 대응하는 별도
 * 단계는 필요하지 않다. 다만 새 메시지가 더 이상 오지 않아도(예: Party가 CLOSED된 뒤 발신이 없는 경우) 연결이 무한정 열려
 * 있지 않도록, P1과 같은 구조로 {@link ChatWebSocketProperties#getAccessValidationInterval()} 주기마다 접근을 재확인한다.
 */
@Component
@RequiredArgsConstructor
public class MatchChatWebSocketHandler implements WebSocketHandler, MatchChatRealtimeSignalGateway {

	static final String PARTY_ID_ATTRIBUTE = "match.chat.party.id";
	static final String USER_ID_ATTRIBUTE = "match.chat.user.id";
	static final String SESSION_ID_ATTRIBUTE = "match.chat.session.id";
	static final String AFTER_MESSAGE_ID_ATTRIBUTE = "match.chat.after.message.id";

	private final MatchPartyAccessQuery matchPartyAccessQuery;
	private final SessionRepository<? extends Session> sessionRepository;
	// Redis 프로필이 구독 재시도용 TaskScheduler를 함께 등록하므로 빈 이름과 같은 필드명으로 대상을 고정한다.
	private final TaskScheduler chatWebSocketTaskScheduler;
	private final ChatWebSocketProperties properties;
	private final MatchChatConnectionRegistry connectionRegistry;
	private final MatchChatMessageDeliveryService messageDeliveryService;
	private final MatchChatWebSocketMetrics metrics;
	private final AtomicBoolean accessValidationScheduled = new AtomicBoolean();

	@Override
	public void afterConnectionEstablished(WebSocketSession session) {
		MatchChatPartyConnection connection = connectionRegistry.register(session);
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

	/** Redis 등 전달 신호가 알려온 Party의 로컬 연결마다 PostgreSQL catch-up 전달을 다시 시도한다. */
	@Override
	public void onMessageCommitted(MatchChatMessageCommitted event) {
		Set<MatchChatPartyConnection> connections = connectionRegistry.findByPartyId(event.partyId());
		for (MatchChatPartyConnection connection : connections) {
			deliver(connection);
		}
	}

	/**
	 * 전달 직전에 접근·세션 유효성을 다시 확인하고, catch-up 전달을 {@link MatchChatMessageDeliveryService}에 위임한다.
	 *
	 * <p>연결마다 하나의 잠금으로 직렬화하므로, 최초 연결의 catch-up과 이후 신호가 촉진한 재조회가 겹쳐도 중복·누락 없이
	 * 합류한다.
	 */
	private void deliver(MatchChatPartyConnection connection) {
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

	/** 새 메시지가 오지 않아도 Party별 로컬 연결마다 접근 상실을 감지해 정책 위반으로 종료한다. */
	private void validateAccess() {
		connectionRegistry.snapshotByPartyId().forEach((partyId, connections) ->
			connections.forEach(this::validateCurrentAccess));
	}

	private void validateCurrentAccess(MatchChatPartyConnection connection) {
		WebSocketSession session = connection.session;
		connection.lock.lock();
		try {
			if (connectionRegistry.shouldStopDelivery(session)) {
				return;
			}
			if (!isDeliveryAccessValid(session)) {
				connectionRegistry.closeForPolicyViolation(session);
			}
		} finally {
			connection.lock.unlock();
		}
	}

	/** 세션 만료·참가 취소·Party 최종 상태 전이가 유실돼도 전달 직전과 주기 재검증마다 다시 확인하는 유일한 판정이다. */
	private boolean isDeliveryAccessValid(WebSocketSession session) {
		Map<String, Object> attributes = session.getAttributes();
		Long partyId = attribute(attributes, PARTY_ID_ATTRIBUTE, Long.class);
		Long userId = attribute(attributes, USER_ID_ATTRIBUTE, Long.class);
		String sessionId = attribute(attributes, SESSION_ID_ATTRIBUTE, String.class);
		try {
			if (partyId == null || userId == null || sessionId == null
				|| sessionRepository.findById(sessionId) == null) {
				return false;
			}
			return matchPartyAccessQuery.evaluateChatAccess(userId, partyId) == MatchPartyChatAccess.ALLOWED;
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private <T> T attribute(Map<String, Object> attributes, String name, Class<T> type) {
		Object value = attributes.get(name);
		return type.isInstance(value) ? type.cast(value) : null;
	}
}
