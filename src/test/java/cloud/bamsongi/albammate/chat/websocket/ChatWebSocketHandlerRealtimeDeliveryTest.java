package cloud.bamsongi.albammate.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.room.contract.ChatWebSocketAccessChecker;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** T1·T2·T3·T4·T10·T12: 커밋 신호에 반응하는 catch-up 전달 로직 하나를 직접 검증한다. */
class ChatWebSocketHandlerRealtimeDeliveryTest {

	private static final long ROOM_ID = 7L;
	private static final long CHAT_ROOM_ID = 99L;
	private static final long USER_ID = 42L;
	private static final String SESSION_ID = "session-id";
	private static final Instant CREATED_AT = Instant.parse("2026-08-05T00:00:00Z");

	private final MapSessionRepository sessionRepository = new MapSessionRepository(new HashMap<>());
	private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
	private final ChatAccessGuard chatAccessGuard = mock(ChatAccessGuard.class);
	private final ChatWebSocketAccessChecker chatWebSocketAccessChecker = mock(ChatWebSocketAccessChecker.class);
	private final ChatWebSocketProperties properties = new ChatWebSocketProperties();
	private final ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
	private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
	private final UserQuery userQuery = mock(UserQuery.class);
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final ChatWebSocketMetrics metrics = new ChatWebSocketMetrics(meterRegistry);

	@Test
	void T1_커밋된_메시지는_연결된_관계자에게_전달되고_실제로_커밋되지_않은_신호는_전달되지_않는다() throws Exception {
		WebSocketSession session = connectedSession();
		ChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		ChatMessage message10 = chatMessage(10L);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message10));
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of(USER_ID, "발신자"));

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 10L));

		verify(session, times(1)).sendMessage(any());
		assertEventIdsSent(session, 10L);
	}

	@Test
	void T1_다른_방의_신호는_전달을_시도하지_않는다() throws Exception {
		WebSocketSession session = connectedSession();
		ChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session, chatMessageRepository);

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID + 1, 999L));

		verify(session, never()).sendMessage(any());
		verify(chatMessageRepository, never())
			.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(anyLong(), anyLong());
	}

	@Test
	void T2_같은_messageId_신호가_중복_도착해도_연결당_한_번만_전달된다() throws Exception {
		WebSocketSession session = connectedSession();
		ChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		ChatMessage message10 = chatMessage(10L);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message10));
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 10L))
			.thenReturn(List.of());
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of(USER_ID, "발신자"));

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 10L));
		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 10L));

		verify(session, times(1)).sendMessage(any());
	}

	@Test
	void T3_신호가_유실돼도_다음_신호가_누락_messageId를_ASC로_함께_복구한다() throws Exception {
		WebSocketSession session = connectedSession();
		ChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		ChatMessage message1 = chatMessage(1L);
		ChatMessage message2 = chatMessage(2L);
		ChatMessage message3 = chatMessage(3L);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message1, message2, message3));
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of(USER_ID, "발신자"));

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 3L));

		verify(session, times(3)).sendMessage(any());
		assertEventIdsSent(session, 1L, 2L, 3L);
	}

	@Test
	void T4_신호가_역순으로_도착해도_전달_순서는_messageId_ASC를_유지한다() throws Exception {
		WebSocketSession session = connectedSession();
		ChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		ChatMessage message5 = chatMessage(5L);
		ChatMessage message6 = chatMessage(6L);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message5, message6));
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 6L))
			.thenReturn(List.of());
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of(USER_ID, "발신자"));

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 6L));
		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 5L));

		verify(session, times(2)).sendMessage(any());
		assertEventIdsSent(session, 5L, 6L);
	}

	@Test
	void T10_전달_직전_접근_재확인이_실패하면_전달하지_않고_연결을_종료한다() throws Exception {
		WebSocketSession session = connectedSession();
		ChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session, chatMessageRepository);
		when(chatAccessGuard.executeWithAccess(eq(USER_ID), eq(ROOM_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 10L));

		verify(session, never()).sendMessage(any());
		verify(session).close(CloseStatus.POLICY_VIOLATION);
		verify(chatMessageRepository, never())
			.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(anyLong(), anyLong());
	}

	@Test
	void T10_전달_직전_세션_저장소_확인이_실패하면_전달하지_않고_연결을_종료한다() throws Exception {
		WebSocketSession session = connectedSession();
		ChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		sessionRepository.deleteById(SESSION_ID);

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 10L));

		verify(session, never()).sendMessage(any());
		verify(session).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void T10_스케줄러_접근_실패와_동시_전달도_직렬화해_전달하지_않는다() throws Exception {
		WebSocketSession session = connectedSession();
		ChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		verify(taskScheduler).scheduleAtFixedRate(validation.capture(), any());
		clearInvocations(session, chatMessageRepository, chatAccessGuard, chatWebSocketAccessChecker);

		ChatMessage message10 = chatMessage(10L);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message10));
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of(USER_ID, "발신자"));
		CountDownLatch validationAccessChecked = new CountDownLatch(1);
		CountDownLatch allowValidationClose = new CountDownLatch(1);
		doAnswer(invocation -> {
			if ("chat-access-validation".equals(Thread.currentThread().getName())) {
				validationAccessChecked.countDown();
				assertTrue(allowValidationClose.await(5, TimeUnit.SECONDS), "validation release timed out");
				throw new BusinessException(ErrorCode.FORBIDDEN);
			}
			return null;
		}).when(chatWebSocketAccessChecker).verifyCurrentAccess(USER_ID, ROOM_ID);

		Thread validationThread = new Thread(validation.getValue(), "chat-access-validation");
		Thread deliveryThread = new Thread(
			() -> handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, message10.getId())),
			"chat-message-delivery");
		validationThread.start();
		assertTrue(validationAccessChecked.await(5, TimeUnit.SECONDS), "scheduled validation did not start in time");
		deliveryThread.start();
		allowValidationClose.countDown();
		validationThread.join(5_000);
		deliveryThread.join(5_000);

		assertTrue(!validationThread.isAlive(), "scheduled validation did not finish in time");
		assertTrue(!deliveryThread.isAlive(), "concurrent delivery did not finish in time");
		verify(session, never()).sendMessage(any());
		verify(session).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void T12_연결_수는_연결_시작과_종료에_따라_증감하고_전달_지연은_식별자_없이_기록된다() throws Exception {
		WebSocketSession session = connectedSession();
		ChatWebSocketHandler handler = handler();
		handler.afterConnectionEstablished(session);
		clearInvocations(session);
		assertEquals(1, metrics.activeConnectionCount());
		ChatMessage message10 = chatMessage(10L);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message10));
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of(USER_ID, "발신자"));

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 10L));

		assertEquals(1, meterRegistry.get("chat.websocket.delivery.latency").timer().count());
		assertTrue(meterRegistry.get("chat.websocket.delivery.latency").timer().getId().getTags().isEmpty());
		assertEquals(1.0, meterRegistry.get("chat.websocket.recovery.messages").counter().count());
		assertTrue(meterRegistry.get("chat.websocket.recovery.messages").counter().getId().getTags().isEmpty());

		handler.afterConnectionClosed(session, CloseStatus.NORMAL);
		assertEquals(0, metrics.activeConnectionCount());
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

	private ChatWebSocketHandler handler() {
		ChatConnectionRegistry connectionRegistry = new ChatConnectionRegistry(chatRoomRepository,
			chatMessageRepository, metrics);
		ChatMessageDeliveryService deliveryService = new ChatMessageDeliveryService(
			connectionRegistry,
			chatMessageRepository,
			userQuery,
			metrics,
			JsonMapper.builder().build(),
			Clock.fixed(CREATED_AT.plusSeconds(1), ZoneOffset.UTC));
		return new ChatWebSocketHandler(
			chatAccessGuard,
			chatWebSocketAccessChecker,
			sessionRepository,
			taskScheduler,
			properties,
			connectionRegistry,
			deliveryService,
			metrics);
	}

	private WebSocketSession connectedSession() {
		MapSession savedSession = sessionRepository.createSession();
		savedSession.setId(SESSION_ID);
		sessionRepository.save(savedSession);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(ChatWebSocketHandler.SESSION_ID_ATTRIBUTE, SESSION_ID);
		attributes.put(ChatWebSocketHandler.USER_ID_ATTRIBUTE, USER_ID);
		attributes.put(ChatWebSocketHandler.ROOM_ID_ATTRIBUTE, ROOM_ID);
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(session.getAttributes()).thenReturn(attributes);
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoom.getId()).thenReturn(CHAT_ROOM_ID);
		when(chatRoomRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(chatRoom));
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(eq(CHAT_ROOM_ID), any())).thenReturn(List.of());
		return session;
	}

	private ChatMessage chatMessage(long messageId) {
		ChatMessage message = mock(ChatMessage.class);
		when(message.getId()).thenReturn(messageId);
		when(message.getSenderUserId()).thenReturn(USER_ID);
		when(message.getClientMessageId()).thenReturn("client-" + messageId);
		when(message.getContent()).thenReturn("내용 " + messageId);
		when(message.getCreatedAt()).thenReturn(CREATED_AT);
		return message;
	}
}
