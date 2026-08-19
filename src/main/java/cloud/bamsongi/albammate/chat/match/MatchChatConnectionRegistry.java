package cloud.bamsongi.albammate.chat.match;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * Party별 MATCH WebSocket 연결의 등록·해제 상태와 종료 요청 여부를 이 인스턴스 메모리에서 관리한다.
 *
 * <p>{@link MatchChatWebSocketHandler}가 handshake 속성으로 넘긴 연결을 {@link MatchChatPartyConnection}으로
 * 등록하고, Party ID로 함께 조회하거나 세션별 종료 요청을 조율할 때 이 컴포넌트에 위임한다.
 */
@Component
@RequiredArgsConstructor
class MatchChatConnectionRegistry {

	@NonNull private final MatchChatRoomRepository matchChatRoomRepository;
	@NonNull private final MatchChatMessageRepository matchChatMessageRepository;
	@NonNull private final MatchChatWebSocketMetrics metrics;

	private final Set<WebSocketSession> closeRequestedSessions = ConcurrentHashMap.newKeySet();
	private final Map<WebSocketSession, MatchChatPartyConnection> connectionsBySession = new ConcurrentHashMap<>();
	private final Map<Long, Set<MatchChatPartyConnection>> connectionsByPartyId = new ConcurrentHashMap<>();
	private final Object connectionIndexLock = new Object();

	/** handshake 속성이 갖춰지고 그 partyId의 MatchChatRoom이 있는 세션만 등록하고, 그렇지 않으면 {@code null}을 돌려준다. */
	MatchChatPartyConnection register(WebSocketSession session) {
		closeRequestedSessions.remove(session);
		Map<String, Object> attributes = session.getAttributes();
		Long partyId = attribute(attributes, MatchChatWebSocketHandler.PARTY_ID_ATTRIBUTE, Long.class);
		Long userId = attribute(attributes, MatchChatWebSocketHandler.USER_ID_ATTRIBUTE, Long.class);
		if (partyId == null || userId == null) {
			return null;
		}
		Long matchChatRoomId = matchChatRoomRepository.findByPartyId(partyId).map(MatchChatRoom::getId).orElse(null);
		if (matchChatRoomId == null) {
			return null;
		}
		Long afterMessageId = attribute(attributes, MatchChatWebSocketHandler.AFTER_MESSAGE_ID_ATTRIBUTE, Long.class);
		long baseline = initialBaseline(matchChatRoomId, afterMessageId);
		MatchChatPartyConnection connection = new MatchChatPartyConnection(
			session, partyId, matchChatRoomId, userId, baseline);
		synchronized (connectionIndexLock) {
			connectionsBySession.put(session, connection);
			connectionsByPartyId.compute(partyId, (key, connections) -> {
				Set<MatchChatPartyConnection> updated = connections == null ? ConcurrentHashMap.newKeySet() : connections;
				updated.add(connection);
				return updated;
			});
			metrics.connectionOpened();
		}
		return connection;
	}

	void unregister(WebSocketSession session) {
		closeRequestedSessions.remove(session);
		synchronized (connectionIndexLock) {
			MatchChatPartyConnection connection = connectionsBySession.remove(session);
			if (connection == null) {
				return;
			}
			connectionsByPartyId.computeIfPresent(connection.partyId, (key, connections) -> {
				connections.remove(connection);
				return connections.isEmpty() ? null : connections;
			});
			metrics.connectionClosed();
		}
	}

	MatchChatPartyConnection find(WebSocketSession session) {
		synchronized (connectionIndexLock) {
			return connectionsBySession.get(session);
		}
	}

	Set<MatchChatPartyConnection> findByPartyId(long partyId) {
		synchronized (connectionIndexLock) {
			Set<MatchChatPartyConnection> connections = connectionsByPartyId.get(partyId);
			return connections == null ? Set.of() : Set.copyOf(connections);
		}
	}

	/** 주기 재검증이 한 번의 순회에 사용할 Party별 로컬 연결 스냅샷을 만든다. */
	Map<Long, Set<MatchChatPartyConnection>> snapshotByPartyId() {
		synchronized (connectionIndexLock) {
			Map<Long, Set<MatchChatPartyConnection>> snapshot = new ConcurrentHashMap<>();
			connectionsByPartyId.forEach((partyId, connections) -> snapshot.put(partyId, Set.copyOf(connections)));
			return Map.copyOf(snapshot);
		}
	}

	/** 이미 종료를 요청했거나 세션이 닫혀 더 이상 전달을 이어갈 필요가 없는지 본다. */
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

	private long latestMessageId(long matchChatRoomId) {
		return matchChatMessageRepository
			.findByMatchChatRoomIdOrderByIdDesc(matchChatRoomId, PageRequest.of(0, 1))
			.stream()
			.findFirst()
			.map(MatchChatMessage::getId)
			.orElse(0L);
	}

	/**
	 * 현재 Party에 없는 cursor는 과거 메시지를 건너뛰게 하므로 이력 처음부터 복구한다.
	 *
	 * <p>현재 Party에 실제로 없는 cursor는 다른 Party의 ID 또는 삭제된 이력일 수 있으므로 기준을 0으로 되돌린다. 다만
	 * 아직 저장되지 않은 미래 ID는 최신 ID로 제한해 이후 메시지 전달을 막지 않는다.
	 */
	private long initialBaseline(long matchChatRoomId, Long afterMessageId) {
		long latestMessageId = latestMessageId(matchChatRoomId);
		if (afterMessageId == null) {
			return latestMessageId;
		}
		if (matchChatMessageRepository.existsByIdAndMatchChatRoomId(afterMessageId, matchChatRoomId)) {
			return afterMessageId;
		}
		if (afterMessageId > latestMessageId && !matchChatMessageRepository.existsById(afterMessageId)) {
			return latestMessageId;
		}
		return 0L;
	}

	private <T> T attribute(Map<String, Object> attributes, String name, Class<T> type) {
		Object value = attributes.get(name);
		return type.isInstance(value) ? type.cast(value) : null;
	}
}
