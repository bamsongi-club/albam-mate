package cloud.bamsongi.albammate.chat.websocket;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

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

class ChatWebSocketHandlerTest {

	private static final String SESSION_ID = "session-id";
	private static final long CHAT_ROOM_ID = 99L;

	private final MapSessionRepository sessionRepository = spy(
		new MapSessionRepository(new ConcurrentHashMap<>()));
	private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
	private final ChatAccessGuard chatAccessGuard = mock(ChatAccessGuard.class);
	private final ChatWebSocketAccessChecker chatWebSocketAccessChecker = mock(ChatWebSocketAccessChecker.class);
	private final ChatWebSocketProperties properties = new ChatWebSocketProperties();
	private final ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
	private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
	private final UserQuery userQuery = mock(UserQuery.class);
	private final ChatWebSocketMetrics metrics = new ChatWebSocketMetrics(new SimpleMeterRegistry());

	@Test
	void T1_같은_방_연결은_한_번_보정하고_각각_현재_접근을_확인한다() throws Exception {
		WebSocketSession firstSession = session("first-session", 42L, 7L);
		WebSocketSession secondSession = session("second-session", 43L, 7L);
		ChatWebSocketHandler handler = handler();
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(Duration.ofSeconds(1))))
			.thenReturn(mock(ScheduledFuture.class));

		handler.afterConnectionEstablished(firstSession);
		handler.afterConnectionEstablished(secondSession);
		validation.getValue().run();

		org.assertj.core.api.Assertions.assertThat(validation.getAllValues()).hasSize(1);
		verify(chatWebSocketAccessChecker).correctRoomState(7L);
		verify(chatWebSocketAccessChecker).verifyCurrentAccess(42L, 7L);
		verify(chatWebSocketAccessChecker).verifyCurrentAccess(43L, 7L);
	}

	@Test
	void T2_무효_연결만_POLICY_VIOLATION으로_종료하고_같은_방의_유효_연결은_유지한다() throws Exception {
		WebSocketSession validSession = session("valid-session", 42L, 7L);
		WebSocketSession canceledParticipantSession = session("canceled-participant-session", 43L, 7L);
		WebSocketSession terminalRoomSession = session("terminal-room-session", 44L, 7L);
		WebSocketSession invalidSession = session("invalid-session", 45L, 7L);
		ChatWebSocketHandler handler = handler();
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(Duration.ofSeconds(1))))
			.thenReturn(mock(ScheduledFuture.class));

		handler.afterConnectionEstablished(validSession);
		handler.afterConnectionEstablished(canceledParticipantSession);
		handler.afterConnectionEstablished(terminalRoomSession);
		handler.afterConnectionEstablished(invalidSession);
		sessionRepository.deleteById("invalid-session");
		doThrow(new BusinessException(ErrorCode.FORBIDDEN))
			.when(chatWebSocketAccessChecker).verifyCurrentAccess(43L, 7L);
		doThrow(new BusinessException(ErrorCode.FORBIDDEN))
			.when(chatWebSocketAccessChecker).verifyCurrentAccess(44L, 7L);

		org.assertj.core.api.Assertions.assertThat(validation.getAllValues()).hasSize(1);
		validation.getValue().run();

		verify(canceledParticipantSession).close(CloseStatus.POLICY_VIOLATION);
		verify(terminalRoomSession).close(CloseStatus.POLICY_VIOLATION);
		verify(invalidSession).close(CloseStatus.POLICY_VIOLATION);
		verify(validSession, never()).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void T3_전달_직전에는_기존_강한_접근_검증을_계속_사용하고_주기_재검증은_사용하지_않는다() throws Exception {
		WebSocketSession webSocketSession = session(42L, 7L);
		ChatWebSocketHandler handler = handler();
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(Duration.ofSeconds(1))))
			.thenReturn(mock(ScheduledFuture.class));

		handler.afterConnectionEstablished(webSocketSession);

		verify(chatAccessGuard).executeWithAccess(eq(42L), eq(7L), any());
		org.mockito.Mockito.clearInvocations(chatAccessGuard);
		validation.getValue().run();

		verifyNoInteractions(chatAccessGuard);
		verify(chatWebSocketAccessChecker).correctRoomState(7L);
		verify(chatWebSocketAccessChecker).verifyCurrentAccess(42L, 7L);
	}

	@Test
	void T4_모든_연결이_사라져도_장기_재검증_작업을_연결별로_취소하지_않는다() throws Exception {
		WebSocketSession webSocketSession = session(42L, 7L);
		ChatWebSocketHandler handler = handler();
		@SuppressWarnings("rawtypes") ScheduledFuture scheduledFuture = mock(ScheduledFuture.class);
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(Duration.ofSeconds(1))))
			.thenReturn(scheduledFuture);

		handler.afterConnectionEstablished(webSocketSession);
		handler.afterConnectionClosed(webSocketSession, CloseStatus.NORMAL);
		validation.getValue().run();

		verify(scheduledFuture, never()).cancel(false);
		verifyNoInteractions(chatWebSocketAccessChecker);
	}

	@Test
	void 세션이_무효화되면_기존_연결을_POLICY_VIOLATION으로_종료한다() throws Exception {
		WebSocketSession webSocketSession = session(42L, 7L);
		ChatWebSocketHandler handler = handler();
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(Duration.ofSeconds(1))))
			.thenReturn(mock(ScheduledFuture.class));

		handler.afterConnectionEstablished(webSocketSession);
		sessionRepository.deleteById(SESSION_ID);
		validation.getValue().run();

		verify(webSocketSession).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void 방_접근_권한이_사라지면_기존_연결을_POLICY_VIOLATION으로_종료한다() throws Exception {
		WebSocketSession webSocketSession = session(42L, 7L);
		doThrow(new BusinessException(ErrorCode.FORBIDDEN))
			.when(chatWebSocketAccessChecker).verifyCurrentAccess(42L, 7L);
		ChatWebSocketHandler handler = handler();
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(Duration.ofSeconds(1))))
			.thenReturn(mock(ScheduledFuture.class));

		handler.afterConnectionEstablished(webSocketSession);
		validation.getValue().run();

		verify(webSocketSession).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void 세션_저장소_조회가_실패하면_기존_연결을_POLICY_VIOLATION으로_종료한다() throws Exception {
		WebSocketSession webSocketSession = session(42L, 7L);
		doThrow(new IllegalStateException("session store unavailable"))
			.when(sessionRepository).findById(SESSION_ID);
		ChatWebSocketHandler handler = handler();
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(Duration.ofSeconds(1))))
			.thenReturn(mock(ScheduledFuture.class));

		handler.afterConnectionEstablished(webSocketSession);
		validation.getValue().run();

		verify(webSocketSession).close(CloseStatus.POLICY_VIOLATION);
	}

	@Test
	void T6_클라이언트가_보낸_메시지_프레임은_POLICY_VIOLATION으로_종료한다() throws Exception {
		WebSocketSession webSocketSession = session(42L, 7L);
		ChatWebSocketHandler handler = handler();

		handler.handleMessage(webSocketSession, new TextMessage("hello"));

		verify(webSocketSession).close(CloseStatus.POLICY_VIOLATION);
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
			Clock.fixed(Instant.parse("2026-08-05T00:00:00Z"), java.time.ZoneOffset.UTC));
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

	private WebSocketSession session(long userId, long roomId) throws Exception {
		return session(SESSION_ID, userId, roomId);
	}

	private WebSocketSession session(String sessionId, long userId, long roomId) throws Exception {
		MapSession savedSession = sessionRepository.createSession();
		savedSession.setId(sessionId);
		sessionRepository.save(savedSession);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(ChatWebSocketHandler.SESSION_ID_ATTRIBUTE, sessionId);
		attributes.put(ChatWebSocketHandler.USER_ID_ATTRIBUTE, userId);
		attributes.put(ChatWebSocketHandler.ROOM_ID_ATTRIBUTE, roomId);
		WebSocketSession webSocketSession = mock(WebSocketSession.class);
		when(webSocketSession.isOpen()).thenReturn(true);
		when(webSocketSession.getAttributes()).thenReturn(attributes);
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoom.getId()).thenReturn(CHAT_ROOM_ID);
		when(chatRoomRepository.findByRoomId(roomId)).thenReturn(Optional.of(chatRoom));
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(eq(CHAT_ROOM_ID), any()))
			.thenReturn(java.util.List.of());
		return webSocketSession;
	}
}
