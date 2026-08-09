package cloud.bamsongi.albammate.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** T4: catch-up 전달 컴포넌트가 마지막 전달 ID 이후 메시지를 ASC로 전달하고 실패를 종료·계측하는 동작을 직접 검증한다. */
class ChatMessageDeliveryServiceTest {

	private static final long ROOM_ID = 7L;
	private static final long CHAT_ROOM_ID = 99L;
	private static final long USER_ID = 42L;
	private static final Instant CREATED_AT = Instant.parse("2026-08-05T00:00:00Z");

	private final ChatConnectionRegistry connectionRegistry = mock(ChatConnectionRegistry.class);
	private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
	private final UserQuery userQuery = mock(UserQuery.class);
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final ChatWebSocketMetrics metrics = new ChatWebSocketMetrics(meterRegistry);
	private final ChatMessageDeliveryService deliveryService = new ChatMessageDeliveryService(
		connectionRegistry, chatMessageRepository, userQuery, metrics,
		JsonMapper.builder().build(), Clock.fixed(CREATED_AT.plusSeconds(1), ZoneOffset.UTC));

	@Test
	void T4_마지막_전달_ID_이후_메시지만_ASC로_전달하고_기준을_갱신해_중복_전달하지_않는다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false);
		ChatRoomConnection connection = new ChatRoomConnection(session, ROOM_ID, CHAT_ROOM_ID, USER_ID, 0L);
		ChatMessage message1 = chatMessage(1L);
		ChatMessage message2 = chatMessage(2L);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message1, message2));
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of(USER_ID, "발신자"));

		deliveryService.deliverNewMessages(connection);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(2)).sendMessage(captor.capture());
		assertTrue(captor.getAllValues().get(0).getPayload().contains("\"eventId\":1"));
		assertTrue(captor.getAllValues().get(1).getPayload().contains("\"eventId\":2"));
		assertEquals(2L, connection.lastDeliveredMessageId.get());

		clearInvocations(session);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 2L))
			.thenReturn(List.of());

		deliveryService.deliverNewMessages(connection);

		verify(session, never()).sendMessage(any());
	}

	@Test
	void T4_전송이_실패하면_그_메시지에서_멈추고_SERVER_ERROR로_종료하며_실패를_계측한다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false);
		ChatRoomConnection connection = new ChatRoomConnection(session, ROOM_ID, CHAT_ROOM_ID, USER_ID, 0L);
		ChatMessage message1 = chatMessage(1L);
		ChatMessage message2 = chatMessage(2L);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message1, message2));
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of(USER_ID, "발신자"));
		doThrow(new IOException("boom")).when(session).sendMessage(any());

		deliveryService.deliverNewMessages(connection);

		verify(session, times(1)).sendMessage(any());
		assertEquals(0L, connection.lastDeliveredMessageId.get());
		verify(connectionRegistry).closeForTransportFailure(session);
		assertEquals(1.0, meterRegistry.get("chat.websocket.delivery.failures").counter().count());
	}

	@Test
	void T4_shouldStopDelivery가_true면_그_메시지에서_조용히_멈추고_이후_메시지를_전달하지_않는다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false, true);
		ChatRoomConnection connection = new ChatRoomConnection(session, ROOM_ID, CHAT_ROOM_ID, USER_ID, 0L);
		ChatMessage message1 = chatMessage(1L);
		ChatMessage message2 = chatMessage(2L);
		when(chatMessageRepository.findByChatRoomIdAndIdGreaterThanOrderByIdAsc(CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message1, message2));
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of(USER_ID, "발신자"));

		deliveryService.deliverNewMessages(connection);

		verify(session, times(1)).sendMessage(any());
		assertEquals(1L, connection.lastDeliveredMessageId.get());
		verify(connectionRegistry, never()).closeForTransportFailure(any());
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
