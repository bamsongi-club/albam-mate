package cloud.bamsongi.albammate.chat.websocket;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.session.MapSession;
import org.springframework.session.MapSessionRepository;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;

class ChatWebSocketHandlerTest {

	private static final String SESSION_ID = "session-id";

	private final MapSessionRepository sessionRepository = new MapSessionRepository(new ConcurrentHashMap<>());
	private final TaskScheduler taskScheduler = mock(TaskScheduler.class);
	private final ChatAccessGuard chatAccessGuard = mock(ChatAccessGuard.class);
	private final ChatWebSocketProperties properties = new ChatWebSocketProperties();

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
		when(chatAccessGuard.executeWithAccess(eq(42L), eq(7L), org.mockito.ArgumentMatchers.any()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));
		ChatWebSocketHandler handler = handler();
		ArgumentCaptor<Runnable> validation = ArgumentCaptor.forClass(Runnable.class);
		when(taskScheduler.scheduleAtFixedRate(validation.capture(), eq(Duration.ofSeconds(1))))
			.thenReturn(mock(ScheduledFuture.class));

		handler.afterConnectionEstablished(webSocketSession);
		validation.getValue().run();

		verify(webSocketSession).close(CloseStatus.POLICY_VIOLATION);
	}

	private ChatWebSocketHandler handler() {
		return new ChatWebSocketHandler(chatAccessGuard, sessionRepository, taskScheduler, properties);
	}

	private WebSocketSession session(long userId, long roomId) throws Exception {
		MapSession savedSession = sessionRepository.createSession();
		savedSession.setId(SESSION_ID);
		sessionRepository.save(savedSession);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(ChatWebSocketHandler.SESSION_ID_ATTRIBUTE, SESSION_ID);
		attributes.put(ChatWebSocketHandler.USER_ID_ATTRIBUTE, userId);
		attributes.put(ChatWebSocketHandler.ROOM_ID_ATTRIBUTE, roomId);
		WebSocketSession webSocketSession = mock(WebSocketSession.class);
		when(webSocketSession.isOpen()).thenReturn(true);
		when(webSocketSession.getAttributes()).thenReturn(attributes);
		return webSocketSession;
	}
}
