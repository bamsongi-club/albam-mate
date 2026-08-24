package cloud.bamsongi.albammate.chat.websocket;

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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.room.contract.ChatWebSocketAccessChecker;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** T5: afterMessageId 재연결의 누락분 catch-up과 복구 중 도착한 이벤트의 중복 없는 합류를 검증한다. */
class ChatWebSocketHandlerReconnectRecoveryTest {

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
	private final ChatWebSocketMetrics metrics = new ChatWebSocketMetrics(new SimpleMeterRegistry());

	@Test
	void afterMessageId_재연결은_누락분을_ASC로_먼저_전달한다() throws Exception {
		ChatMessage message6 = chatMessage(6L);
		ChatMessage message7 = chatMessage(7L);
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(message7));
		when(chatMessageRepository.existsByIdAndChatRoomId(5L, CHAT_ROOM_ID)).thenReturn(true);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 5L))
			.thenReturn(List.of(message6, message7));
		when(userQuery.findUserSummariesByIds(any()))
			.thenReturn(Map.of(USER_ID, new UserQuery.UserSummary("발신자", null)));
		WebSocketSession session = session(5L);
		ChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(2)).sendMessage(captor.capture());
		assertTrue(captor.getAllValues().get(0).getPayload().contains("\"eventId\":6"));
		assertTrue(captor.getAllValues().get(1).getPayload().contains("\"eventId\":7"));
	}

	@Test
	void afterMessageId가_현재_이력보다_크면_현재_최신값으로_제한해_새_메시지를_전달한다() throws Exception {
		ChatMessage currentLatest = chatMessage(5L);
		ChatMessage laterMessage = chatMessage(6L);
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(currentLatest));
		when(chatMessageRepository.existsByIdAndChatRoomId(9L, CHAT_ROOM_ID)).thenReturn(false);
		when(chatMessageRepository.existsById(9L)).thenReturn(false);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 5L))
			.thenReturn(List.of(), List.of(laterMessage));
		when(userQuery.findUserSummariesByIds(any()))
			.thenReturn(Map.of(USER_ID, new UserQuery.UserSummary("발신자", null)));
		WebSocketSession session = session(9L);
		ChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);
		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, laterMessage.getId()));

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session).sendMessage(captor.capture());
		assertTrue(captor.getValue().getPayload().contains("\"eventId\":6"));
	}

	@Test
	void 다른_방_afterMessageId는_현재_방_이력을_누락시키지_않는다() throws Exception {
		ChatMessage currentRoomFirstMessage = chatMessage(10L);
		ChatMessage currentRoomLatestMessage = chatMessage(20L);
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(currentRoomLatestMessage));
		when(chatMessageRepository.existsByIdAndChatRoomId(15L, CHAT_ROOM_ID)).thenReturn(false);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(currentRoomFirstMessage, currentRoomLatestMessage));
		when(userQuery.findUserSummariesByIds(any()))
			.thenReturn(Map.of(USER_ID, new UserQuery.UserSummary("발신자", null)));
		WebSocketSession session = session(15L);
		ChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(2)).sendMessage(captor.capture());
		assertTrue(captor.getAllValues().get(0).getPayload().contains("\"eventId\":10"));
		assertTrue(captor.getAllValues().get(1).getPayload().contains("\"eventId\":20"));
	}

	@Test
	void 다른_방의_현재_방_최신값보다_큰_afterMessageId도_이력을_누락시키지_않는다() throws Exception {
		ChatMessage currentRoomFirstMessage = chatMessage(10L);
		ChatMessage currentRoomLatestMessage = chatMessage(20L);
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(currentRoomLatestMessage));
		when(chatMessageRepository.existsByIdAndChatRoomId(21L, CHAT_ROOM_ID)).thenReturn(false);
		when(chatMessageRepository.existsById(21L)).thenReturn(true);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(currentRoomFirstMessage, currentRoomLatestMessage));
		when(userQuery.findUserSummariesByIds(any()))
			.thenReturn(Map.of(USER_ID, new UserQuery.UserSummary("발신자", null)));
		WebSocketSession session = session(21L);
		ChatWebSocketHandler handler = handler();

		handler.afterConnectionEstablished(session);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(2)).sendMessage(captor.capture());
		assertTrue(captor.getAllValues().get(0).getPayload().contains("\"eventId\":10"));
		assertTrue(captor.getAllValues().get(1).getPayload().contains("\"eventId\":20"));
	}

	@Test
	void 복구_중_도착한_신호는_중복_전달_없이_이어서_전달된다() throws Exception {
		ChatMessage message1 = chatMessage(1L);
		ChatMessage message2 = chatMessage(2L);
		CountDownLatch catchupStarted = new CountDownLatch(1);
		CountDownLatch releaseCatchup = new CountDownLatch(1);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(eq(CHAT_ROOM_ID), eq(0L)))
			.thenAnswer(invocation -> {
				catchupStarted.countDown();
				assertTrue(releaseCatchup.await(5, TimeUnit.SECONDS), "release timed out");
				return List.of(message1);
			});
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(eq(CHAT_ROOM_ID), eq(1L)))
			.thenReturn(List.of(message2));
		when(userQuery.findUserSummariesByIds(any()))
			.thenReturn(Map.of(USER_ID, new UserQuery.UserSummary("발신자", null)));
		WebSocketSession session = session(null);
		ChatWebSocketHandler handler = handler();

		Thread connectThread = new Thread(() -> handler.afterConnectionEstablished(session));
		connectThread.start();
		assertTrue(catchupStarted.await(5, TimeUnit.SECONDS), "catch-up did not start in time");

		Thread signalThread = new Thread(
			() -> handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, 2L)));
		signalThread.start();
		releaseCatchup.countDown();
		connectThread.join(5_000);
		signalThread.join(5_000);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(2)).sendMessage(captor.capture());
		assertTrue(captor.getAllValues().get(0).getPayload().contains("\"eventId\":1"));
		assertTrue(captor.getAllValues().get(1).getPayload().contains("\"eventId\":2"));
	}

	private ChatWebSocketHandler handler() {
		ChatConnectionRegistry connectionRegistry = new ChatConnectionRegistry(chatRoomRepository,
			chatMessageRepository, metrics);
		ChatMessageDeliveryService deliveryService = new ChatMessageDeliveryService(
			connectionRegistry,
			chatMessageRepository,
			userQuery,
			new cloud.bamsongi.albammate.chat.system.ChatMessageResponseAssembler(),
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

	private WebSocketSession session(Long afterMessageId) {
		MapSession savedSession = sessionRepository.createSession();
		savedSession.setId(SESSION_ID);
		sessionRepository.save(savedSession);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(ChatWebSocketHandler.SESSION_ID_ATTRIBUTE, SESSION_ID);
		attributes.put(ChatWebSocketHandler.USER_ID_ATTRIBUTE, USER_ID);
		attributes.put(ChatWebSocketHandler.ROOM_ID_ATTRIBUTE, ROOM_ID);
		if (afterMessageId != null) {
			attributes.put(ChatWebSocketHandler.AFTER_MESSAGE_ID_ATTRIBUTE, afterMessageId);
		}
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(session.getAttributes()).thenReturn(attributes);
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoom.getId()).thenReturn(CHAT_ROOM_ID);
		when(chatRoomRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(chatRoom));
		if (afterMessageId == null) {
			when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(eq(CHAT_ROOM_ID), any()))
				.thenReturn(List.of());
		}
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
