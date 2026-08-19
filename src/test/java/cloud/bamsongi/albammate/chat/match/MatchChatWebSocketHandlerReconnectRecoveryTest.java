package cloud.bamsongi.albammate.chat.match;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import org.springframework.data.domain.PageRequest;
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

/** T3(CHAT-T7): afterMessageId 재연결의 누락분 catch-up과 복구 중 도착한 이벤트의 중복 없는 합류를 검증한다. */
class MatchChatWebSocketHandlerReconnectRecoveryTest {

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
	private final MatchChatWebSocketMetrics metrics = new MatchChatWebSocketMetrics(new SimpleMeterRegistry());

	@Test
	void afterMessageId_재연결은_누락분을_ASC로_먼저_전달한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		MatchChatMessage message6 = chatMessage(6L);
		MatchChatMessage message7 = chatMessage(7L);
		when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(MATCH_CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(message7));
		when(matchChatMessageRepository.existsByIdAndMatchChatRoomId(5L, MATCH_CHAT_ROOM_ID)).thenReturn(true);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 5L))
			.thenReturn(List.of(message6, message7));
		stubSender(USER_ID);
		WebSocketSession session = session(5L);
		MatchChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(2)).sendMessage(captor.capture());
		assertTrue(captor.getAllValues().get(0).getPayload().contains("\"eventId\":6"));
		assertTrue(captor.getAllValues().get(1).getPayload().contains("\"eventId\":7"));
	}

	@Test
	void afterMessageId가_현재_이력보다_크면_현재_최신값으로_제한해_새_메시지를_전달한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		MatchChatMessage currentLatest = chatMessage(5L);
		MatchChatMessage laterMessage = chatMessage(6L);
		when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(MATCH_CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(currentLatest));
		when(matchChatMessageRepository.existsByIdAndMatchChatRoomId(9L, MATCH_CHAT_ROOM_ID)).thenReturn(false);
		when(matchChatMessageRepository.existsById(9L)).thenReturn(false);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 5L))
			.thenReturn(List.of(), List.of(laterMessage));
		stubSender(USER_ID);
		WebSocketSession session = session(9L);
		MatchChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);
		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID, laterMessage.getId()));

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session).sendMessage(captor.capture());
		assertTrue(captor.getValue().getPayload().contains("\"eventId\":6"));
	}

	@Test
	void 다른_Party_afterMessageId는_현재_Party_이력을_누락시키지_않는다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		MatchChatMessage currentPartyFirstMessage = chatMessage(10L);
		MatchChatMessage currentPartyLatestMessage = chatMessage(20L);
		when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(MATCH_CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(currentPartyLatestMessage));
		when(matchChatMessageRepository.existsByIdAndMatchChatRoomId(15L, MATCH_CHAT_ROOM_ID)).thenReturn(false);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(currentPartyFirstMessage, currentPartyLatestMessage));
		stubSender(USER_ID);
		WebSocketSession session = session(15L);
		MatchChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(2)).sendMessage(captor.capture());
		assertTrue(captor.getAllValues().get(0).getPayload().contains("\"eventId\":10"));
		assertTrue(captor.getAllValues().get(1).getPayload().contains("\"eventId\":20"));
	}

	private void stubSender(long senderUserId) {
		when(matchPartyParticipantRefQuery.findParticipantRefs(eq(PARTY_ID), eq(Set.of(senderUserId))))
			.thenReturn(Map.of(senderUserId, "ref-" + senderUserId));
		when(userQuery.findNicknamesByIds(eq(Set.of(senderUserId)))).thenReturn(Map.of(senderUserId, "발신자"));
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

	private WebSocketSession session(Long afterMessageId) {
		MapSession savedSession = sessionRepository.createSession();
		savedSession.setId(SESSION_ID);
		sessionRepository.save(savedSession);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(MatchChatWebSocketHandler.SESSION_ID_ATTRIBUTE, SESSION_ID);
		attributes.put(MatchChatWebSocketHandler.USER_ID_ATTRIBUTE, USER_ID);
		attributes.put(MatchChatWebSocketHandler.PARTY_ID_ATTRIBUTE, PARTY_ID);
		if (afterMessageId != null) {
			attributes.put(MatchChatWebSocketHandler.AFTER_MESSAGE_ID_ATTRIBUTE, afterMessageId);
		}
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(session.getAttributes()).thenReturn(attributes);
		MatchChatRoom chatRoom = mock(MatchChatRoom.class);
		when(chatRoom.getId()).thenReturn(MATCH_CHAT_ROOM_ID);
		when(matchChatRoomRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.of(chatRoom));
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
