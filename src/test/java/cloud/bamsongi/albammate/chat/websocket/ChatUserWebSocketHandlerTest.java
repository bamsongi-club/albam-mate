package cloud.bamsongi.albammate.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.room.contract.ChatRoomParticipantsQuery;
import tools.jackson.databind.json.JsonMapper;

/**
 * CHAT-08 T1·T2·T3·T5: 사용자 단위 팬아웃이 참가자에게만 최소 이벤트를 전달하고, 참가자 조회·전송 실패가
 * 다른 참가자 전달을 막지 않는지 직접 검증한다.
 */
class ChatUserWebSocketHandlerTest {

	private static final long ROOM_ID = 7L;
	private static final long MESSAGE_ID = 100L;

	private final ChatRoomParticipantsQuery chatRoomParticipantsQuery = mock(ChatRoomParticipantsQuery.class);
	private final ChatUserConnectionRegistry connectionRegistry = new ChatUserConnectionRegistry();
	private final ChatUserWebSocketHandler handler = new ChatUserWebSocketHandler(
		connectionRegistry, chatRoomParticipantsQuery, JsonMapper.builder().build());

	@Test
	void T1_방_참가자_전원이_최소_이벤트를_수신하고_메시지_본문과_발신자_정보를_담지_않는다() throws Exception {
		WebSocketSession hostSession = session(1L);
		WebSocketSession participantSession = session(2L);
		handler.afterConnectionEstablished(hostSession);
		handler.afterConnectionEstablished(participantSession);
		when(chatRoomParticipantsQuery.findCurrentParticipantUserIds(ROOM_ID)).thenReturn(List.of(1L, 2L));

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, MESSAGE_ID));

		assertMinimalPayloadSent(hostSession);
		assertMinimalPayloadSent(participantSession);
	}

	@Test
	void T2_발신자_본인의_다른_연결도_같은_신호를_수신한다() throws Exception {
		WebSocketSession firstTab = session(1L);
		WebSocketSession secondTab = session(1L);
		handler.afterConnectionEstablished(firstTab);
		handler.afterConnectionEstablished(secondTab);
		when(chatRoomParticipantsQuery.findCurrentParticipantUserIds(ROOM_ID)).thenReturn(List.of(1L));

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, MESSAGE_ID));

		assertMinimalPayloadSent(firstTab);
		assertMinimalPayloadSent(secondTab);
	}

	@Test
	void T3_참가자가_아닌_사용자는_신호를_받지_않는다() throws Exception {
		WebSocketSession strangerSession = session(99L);
		handler.afterConnectionEstablished(strangerSession);
		when(chatRoomParticipantsQuery.findCurrentParticipantUserIds(ROOM_ID)).thenReturn(List.of(1L, 2L));

		handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, MESSAGE_ID));

		verify(strangerSession, never()).sendMessage(any());
	}

	@Test
	void T5_참가자_조회가_실패해도_예외를_전파하지_않는다() {
		when(chatRoomParticipantsQuery.findCurrentParticipantUserIds(ROOM_ID))
			.thenThrow(new RuntimeException("participant lookup failed"));

		assertDoesNotThrow(() -> handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, MESSAGE_ID)));
	}

	@Test
	void T5_한_참가자의_전송_실패가_다른_참가자_전달을_막지_않는다() throws Exception {
		WebSocketSession failingSession = session(1L);
		WebSocketSession okSession = session(2L);
		doThrow(new IOException("transport failure")).when(failingSession).sendMessage(any());
		handler.afterConnectionEstablished(failingSession);
		handler.afterConnectionEstablished(okSession);
		when(chatRoomParticipantsQuery.findCurrentParticipantUserIds(ROOM_ID)).thenReturn(List.of(1L, 2L));

		assertDoesNotThrow(() -> handler.onMessageCommitted(MessageCommitted.messageCreated(ROOM_ID, MESSAGE_ID)));

		assertMinimalPayloadSent(okSession);
		verify(failingSession).close(CloseStatus.SERVER_ERROR);
	}

	private WebSocketSession session(long userId) {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		Map<String, Object> attributes = new HashMap<>();
		attributes.put(ChatUserWebSocketHandler.USER_ID_ATTRIBUTE, userId);
		when(session.getAttributes()).thenReturn(attributes);
		return session;
	}

	private void assertMinimalPayloadSent(WebSocketSession session) throws Exception {
		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(1)).sendMessage(captor.capture());
		String payload = captor.getValue().getPayload();
		assertTrue(payload.contains("\"roomId\":" + ROOM_ID), payload);
		assertTrue(payload.contains("\"messageId\":" + MESSAGE_ID), payload);
		assertFalse(payload.contains("content"), payload);
		assertFalse(payload.contains("sender"), payload);
	}
}
