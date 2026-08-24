package cloud.bamsongi.albammate.chat.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.match.contract.MatchChatMessageCommitted;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;
import cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** T2(CHAT-T6): 커밋 신호를 받은 뒤 PostgreSQL catch-up으로 재조회해 전달하는 로직을 직접 검증한다. */
class MatchChatWebSocketHandlerRealtimeDeliveryTest {

	private static final long PARTY_ID = 7L;
	private static final long MATCH_CHAT_ROOM_ID = 99L;
	private static final long USER_ID = 42L;
	private static final String SESSION_ID = "session-id";
	private static final Instant CREATED_AT = Instant.parse("2026-08-05T00:00:00Z");

	private final MapSessionRepository sessionRepository = new MapSessionRepository(new HashMap<>());
	private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
	private final MatchPartyAccessQuery matchPartyAccessQuery = mock(MatchPartyAccessQuery.class);
	private final MatchChatRoomRepository matchChatRoomRepository = mock(MatchChatRoomRepository.class);
	private final MatchChatMessageRepository matchChatMessageRepository = mock(MatchChatMessageRepository.class);
	private final MatchPartyParticipantRefQuery matchPartyParticipantRefQuery = mock(
		MatchPartyParticipantRefQuery.class);
	private final UserQuery userQuery = mock(UserQuery.class);
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final MatchChatWebSocketMetrics metrics = new MatchChatWebSocketMetrics(meterRegistry);

	@Test
	void 커밋된_메시지는_연결된_관계자에게_전달되고_실제로_커밋되지_않은_신호는_전달되지_않는다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		MatchChatMessage message10 = chatMessage(10L);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message10));
		stubSender(USER_ID);

		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID, 10L));

		verify(session, times(1)).sendMessage(any());
		assertEventIdsSent(session, 10L);
	}

	@Test
	void 다른_Party의_신호는_전달을_시도하지_않는다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session, matchChatMessageRepository);

		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID + 1, 999L));

		verify(session, never()).sendMessage(any());
		verify(matchChatMessageRepository, never())
			.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(anyLong(), anyLong());
	}

	@Test
	void 같은_messageId_신호가_중복_도착해도_연결당_한_번만_전달된다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		MatchChatMessage message10 = chatMessage(10L);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message10));
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 10L))
			.thenReturn(List.of());
		stubSender(USER_ID);

		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID, 10L));
		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID, 10L));

		verify(session, times(1)).sendMessage(any());
	}

	@Test
	void 신호가_역순으로_도착해도_전달_순서는_messageId_ASC를_유지한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		MatchChatMessage message5 = chatMessage(5L);
		MatchChatMessage message6 = chatMessage(6L);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message5, message6));
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 6L))
			.thenReturn(List.of());
		stubSender(USER_ID);

		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID, 6L));
		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID, 5L));

		verify(session, times(2)).sendMessage(any());
		assertEventIdsSent(session, 5L, 6L);
	}

	@Test
	void 전달_직전_접근_재확인이_실패하면_전달하지_않고_연결을_종료한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session, matchChatMessageRepository);
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.FORBIDDEN);

		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID, 10L));

		verify(session, never()).sendMessage(any());
		verify(session).close(org.springframework.web.socket.CloseStatus.POLICY_VIOLATION);
		verify(matchChatMessageRepository, never())
			.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(anyLong(), anyLong());
	}

	@Test
	void 연결_수는_연결_시작과_종료에_따라_증감하고_전달_지연은_식별자_없이_기록된다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		assertEquals(1, metrics.activeConnectionCount());
		MatchChatMessage message10 = chatMessage(10L);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message10));
		stubSender(USER_ID);

		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID, 10L));

		assertEquals(1, meterRegistry.get("match.chat.websocket.delivery.latency").timer().count());
		assertTrue(meterRegistry.get("match.chat.websocket.delivery.latency").timer().getId().getTags().isEmpty());
		assertEquals(1.0, meterRegistry.get("match.chat.websocket.recovery.messages").counter().count());

		handler.afterConnectionClosed(session, org.springframework.web.socket.CloseStatus.NORMAL);
		assertEquals(0, metrics.activeConnectionCount());
	}

	private void stubSender(long senderUserId) {
		when(matchPartyParticipantRefQuery.findParticipantRefs(eq(PARTY_ID), eq(Set.of(senderUserId))))
			.thenReturn(Map.of(senderUserId, "ref-" + senderUserId));
		when(userQuery.findNicknamesByIds(eq(Set.of(senderUserId)))).thenReturn(Map.of(senderUserId, "발신자"));
	}

	private void assertEventIdsSent(WebSocketSession session, long... expectedEventIds) throws Exception {
		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(expectedEventIds.length)).sendMessage(captor.capture());
		List<TextMessage> sent = captor.getAllValues();
		for (int index = 0; index < expectedEventIds.length; index++) {
			String payload = sent.get(index).getPayload();
			assertTrue(payload.contains("\"eventId\":" + expectedEventIds[index]), payload);
			assertTrue(payload.contains("\"type\":\"MESSAGE_CREATED\""), payload);
		}
	}

	private MatchChatWebSocketHandler handler() {
		MatchChatConnectionRegistry connectionRegistry = new MatchChatConnectionRegistry(
			matchChatRoomRepository, matchChatMessageRepository, metrics);
		MatchChatMessageDeliveryService deliveryService = new MatchChatMessageDeliveryService(
			connectionRegistry,
			matchChatMessageRepository,
			matchPartyParticipantRefQuery,
			userQuery,
			metrics,
			JsonMapper.builder().build(),
			Clock.fixed(CREATED_AT.plusSeconds(1), ZoneOffset.UTC));
		return new MatchChatWebSocketHandler(
			matchPartyAccessQuery,
			sessionRepository,
			taskScheduler,
			new cloud.bamsongi.albammate.chat.websocket.ChatWebSocketProperties(),
			connectionRegistry,
			deliveryService,
			metrics);
	}

	private WebSocketSession connectedSession() {
		MapSession savedSession = sessionRepository.createSession();
		savedSession.setId(SESSION_ID);
		sessionRepository.save(savedSession);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(MatchChatWebSocketHandler.SESSION_ID_ATTRIBUTE, SESSION_ID);
		attributes.put(MatchChatWebSocketHandler.USER_ID_ATTRIBUTE, USER_ID);
		attributes.put(MatchChatWebSocketHandler.PARTY_ID_ATTRIBUTE, PARTY_ID);
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(session.getAttributes()).thenReturn(attributes);
		MatchChatRoom chatRoom = mock(MatchChatRoom.class);
		when(chatRoom.getId()).thenReturn(MATCH_CHAT_ROOM_ID);
		when(matchChatRoomRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.of(chatRoom));
		when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(eq(MATCH_CHAT_ROOM_ID), any()))
			.thenReturn(List.of());
		return session;
	}

	private MatchChatMessage chatMessage(long messageId) {
		MatchChatMessage message = mock(MatchChatMessage.class);
		when(message.getId()).thenReturn(messageId);
		when(message.getMessageType()).thenReturn(MatchChatMessageType.USER);
		when(message.getSenderUserId()).thenReturn(USER_ID);
		when(message.getClientMessageId()).thenReturn("client-" + messageId);
		when(message.getContent()).thenReturn("내용 " + messageId);
		when(message.getCreatedAt()).thenReturn(CREATED_AT);
		return message;
	}
}
