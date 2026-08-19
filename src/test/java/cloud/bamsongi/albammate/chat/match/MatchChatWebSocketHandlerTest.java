package cloud.bamsongi.albammate.chat.match;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.match.contract.MatchChatMessageCommitted;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.chat.websocket.ChatWebSocketProperties;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;
import cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** T1(CHAT-T2): 인증된 ACTIVE 참가자만 연결을 유지하고, 클라이언트 프레임과 세션·접근 상실은 정책 위반으로 종료한다. */
class MatchChatWebSocketHandlerTest {

	private static final String SESSION_ID = "session-id";
	private static final long PARTY_ID = 7L;
	private static final long MATCH_CHAT_ROOM_ID = 99L;
	private static final long USER_ID = 42L;

	private final MapSessionRepository sessionRepository = new MapSessionRepository(new ConcurrentHashMap<>());
	private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
	private final MatchPartyAccessQuery matchPartyAccessQuery = mock(MatchPartyAccessQuery.class);
	private final MatchChatRoomRepository matchChatRoomRepository = mock(MatchChatRoomRepository.class);
	private final MatchChatMessageRepository matchChatMessageRepository = mock(MatchChatMessageRepository.class);
	private final MatchPartyParticipantRefQuery matchPartyParticipantRefQuery = mock(
		MatchPartyParticipantRefQuery.class);
	private final UserQuery userQuery = mock(UserQuery.class);
	private final MatchChatWebSocketMetrics metrics = new MatchChatWebSocketMetrics(new SimpleMeterRegistry());
	private final ChatWebSocketProperties properties = new ChatWebSocketProperties();

	@Test
	void ALLOWED_접근은_연결을_유지하고_최신_기준으로_등록한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);

		verify(session, Mockito.never()).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void 세션_저장소에_없는_세션은_등록_직후_POLICY_VIOLATION으로_종료한다() throws Exception {
		WebSocketSession session = session(SESSION_ID, PARTY_ID, USER_ID, null);
		sessionRepository.deleteById(SESSION_ID);
		MatchChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);

		verify(session).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void NOT_ACTIVE_접근은_전달_직전_재확인에서_POLICY_VIOLATION으로_종료한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID))
			.thenThrow(new BusinessException(ErrorCode.MATCH_CHAT_NOT_ACTIVE));
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);

		verify(session).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void FORBIDDEN_접근은_전달_직전_재확인에서_POLICY_VIOLATION으로_종료한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.FORBIDDEN);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);

		verify(session).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void 클라이언트가_보낸_메시지_프레임은_POLICY_VIOLATION으로_종료한다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		MatchChatWebSocketHandler handler = handler();

		handler.handleMessage(session, new TextMessage("hello"));

		verify(session).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void T1_새_메시지_없이도_주기_재검증이_접근_상실을_감지해_POLICY_VIOLATION으로_종료한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(properties.getAccessValidationInterval())))
			.thenReturn(mock(ScheduledFuture.class));

		handler.afterConnectionEstablished(session);
		Mockito.clearInvocations(session);
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.FORBIDDEN);
		validation.getValue().run();

		verify(session).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void T1_같은_Party의_여러_연결도_재검증_스케줄은_한_번만_등록한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession firstSession = connectedSession();
		WebSocketSession secondSession = session(SESSION_ID + "-2", PARTY_ID, USER_ID, null);
		MatchChatWebSocketHandler handler = handler();
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(properties.getAccessValidationInterval())))
			.thenReturn(mock(ScheduledFuture.class));

		handler.afterConnectionEstablished(firstSession);
		handler.afterConnectionEstablished(secondSession);

		org.assertj.core.api.Assertions.assertThat(validation.getAllValues()).hasSize(1);
	}

	@Test
	void 세션이_무효화되면_전달_촉진_시점에_기존_연결을_POLICY_VIOLATION으로_종료한다() throws Exception {
		when(matchPartyAccessQuery.evaluateChatAccess(USER_ID, PARTY_ID)).thenReturn(MatchPartyChatAccess.ALLOWED);
		WebSocketSession session = connectedSession();
		MatchChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		Mockito.clearInvocations(session);
		sessionRepository.deleteById(SESSION_ID);

		handler.onMessageCommitted(MatchChatMessageCommitted.messageCreated(PARTY_ID, 999L));

		verify(session).close(CloseStatus.POLICY_VIOLATION);
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
			java.time.Clock.fixed(java.time.Instant.parse("2026-08-05T00:00:00Z"), java.time.ZoneOffset.UTC));
		return new MatchChatWebSocketHandler(
			matchPartyAccessQuery,
			sessionRepository,
			taskScheduler,
			properties,
			connectionRegistry,
			deliveryService,
			metrics);
	}

	private MatchChatRoom chatRoom() {
		MatchChatRoom chatRoom = mock(MatchChatRoom.class);
		when(chatRoom.getId()).thenReturn(MATCH_CHAT_ROOM_ID);
		return chatRoom;
	}

	private WebSocketSession connectedSession() {
		return session(SESSION_ID, PARTY_ID, USER_ID, null);
	}

	private WebSocketSession session(String sessionId, long partyId, long userId, Long afterMessageId) {
		MapSession savedSession = sessionRepository.createSession();
		savedSession.setId(sessionId);
		sessionRepository.save(savedSession);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(MatchChatWebSocketHandler.SESSION_ID_ATTRIBUTE, sessionId);
		attributes.put(MatchChatWebSocketHandler.USER_ID_ATTRIBUTE, userId);
		attributes.put(MatchChatWebSocketHandler.PARTY_ID_ATTRIBUTE, partyId);
		if (afterMessageId != null) {
			attributes.put(MatchChatWebSocketHandler.AFTER_MESSAGE_ID_ATTRIBUTE, afterMessageId);
		}
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(session.getAttributes()).thenReturn(attributes);
		MatchChatRoom chatRoom = chatRoom();
		when(matchChatRoomRepository.findByPartyId(partyId)).thenReturn(Optional.of(chatRoom));
		when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(
			org.mockito.ArgumentMatchers.eq(MATCH_CHAT_ROOM_ID), org.mockito.ArgumentMatchers.any()))
			.thenReturn(List.of());
		return session;
	}
}
