package cloud.bamsongi.albammate.chat.websocket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** T1·T2·T3: 연결 레지스트리의 등록·해제, 방별 조회와 재연결 기준 계산을 직접 검증한다. */
class ChatConnectionRegistryTest {

	private static final long ROOM_ID = 7L;
	private static final long OTHER_ROOM_ID = 8L;
	private static final long CHAT_ROOM_ID = 99L;
	private static final long USER_ID = 42L;

	private final ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
	private final ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
	private final ChatWebSocketMetrics metrics = new ChatWebSocketMetrics(new SimpleMeterRegistry());
	private final ChatConnectionRegistry registry = new ChatConnectionRegistry(chatRoomRepository,
		chatMessageRepository, metrics);

	@Test
	void T1_roomId나_userId_속성이_없으면_등록하지_않고_활성_연결_수도_증가하지_않는다() {
		WebSocketSession session = session(new HashMap<>());

		ChatRoomConnection connection = registry.register(session);

		assertNull(connection);
		assertEquals(0, metrics.activeConnectionCount());
	}

	@Test
	void T1_roomId의_ChatRoom이_없으면_등록하지_않는다() {
		when(chatRoomRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.empty());
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, null));

		ChatRoomConnection connection = registry.register(session);

		assertNull(connection);
		assertEquals(0, metrics.activeConnectionCount());
	}

	@Test
	void T1_속성이_갖춰지면_등록하고_해제하면_활성_연결_수가_증감한다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, null));

		ChatRoomConnection connection = registry.register(session);

		assertNotNull(connection);
		assertEquals(1, metrics.activeConnectionCount());

		registry.unregister(session);

		assertEquals(0, metrics.activeConnectionCount());
	}

	@Test
	void T2_같은_방의_여러_연결을_방_ID로_함께_조회하고_다른_방_ID로는_조회하지_않는다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession sessionA = session(attributes(ROOM_ID, USER_ID, null));
		WebSocketSession sessionB = session(attributes(ROOM_ID, USER_ID + 1, null));

		ChatRoomConnection connectionA = registry.register(sessionA);
		ChatRoomConnection connectionB = registry.register(sessionB);

		Set<ChatRoomConnection> found = registry.findByRoomId(ROOM_ID);
		assertEquals(Set.of(connectionA, connectionB), found);
		assertTrue(registry.findByRoomId(OTHER_ROOM_ID).isEmpty());
	}

	@Test
	void T2_방의_마지막_연결이_해제되면_방_항목을_남기지_않는다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession sessionA = session(attributes(ROOM_ID, USER_ID, null));
		WebSocketSession sessionB = session(attributes(ROOM_ID, USER_ID + 1, null));
		registry.register(sessionA);
		registry.register(sessionB);

		registry.unregister(sessionA);
		assertEquals(1, registry.findByRoomId(ROOM_ID).size());

		registry.unregister(sessionB);
		assertTrue(registry.findByRoomId(ROOM_ID).isEmpty());
	}

	@Test
	void T3_현재_방에_존재하는_afterMessageId면_그_값을_기준으로_삼는다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(chatMessageRepository.existsByIdAndChatRoomId(5L, CHAT_ROOM_ID)).thenReturn(true);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, 5L));

		ChatRoomConnection connection = registry.register(session);

		assertEquals(5L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_현재_방에_없는_afterMessageId는_0으로_되돌린다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(chatMessageRepository.existsByIdAndChatRoomId(3L, CHAT_ROOM_ID)).thenReturn(false);
		when(chatMessageRepository.existsById(3L)).thenReturn(true);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, 3L));

		ChatRoomConnection connection = registry.register(session);

		assertEquals(0L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_아직_저장되지_않은_미래_afterMessageId는_현재_최신_ID로_제한한다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(chatMessageRepository.existsByIdAndChatRoomId(20L, CHAT_ROOM_ID)).thenReturn(false);
		when(chatMessageRepository.existsById(20L)).thenReturn(false);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, 20L));

		ChatRoomConnection connection = registry.register(session);

		assertEquals(7L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_afterMessageId가_없으면_현재_최신_ID를_기준으로_삼는다() {
		stubChatRoom();
		stubLatestMessageId(9L);
		WebSocketSession session = session(attributes(ROOM_ID, USER_ID, null));

		ChatRoomConnection connection = registry.register(session);

		assertEquals(9L, connection.lastDeliveredMessageId.get());
	}

	private void stubChatRoom() {
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoom.getId()).thenReturn(CHAT_ROOM_ID);
		when(chatRoomRepository.findByRoomId(ROOM_ID)).thenReturn(Optional.of(chatRoom));
	}

	private void stubLatestMessageId(long latestMessageId) {
		if (latestMessageId == 0L) {
			when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(eq(CHAT_ROOM_ID), any()))
				.thenReturn(List.of());
			return;
		}
		ChatMessage latest = mock(ChatMessage.class);
		when(latest.getId()).thenReturn(latestMessageId);
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(latest));
	}

	private Map<String, Object> attributes(Long roomId, Long userId, Long afterMessageId) {
		Map<String, Object> attributes = new HashMap<>();
		if (roomId != null) {
			attributes.put(ChatWebSocketHandler.ROOM_ID_ATTRIBUTE, roomId);
		}
		if (userId != null) {
			attributes.put(ChatWebSocketHandler.USER_ID_ATTRIBUTE, userId);
		}
		if (afterMessageId != null) {
			attributes.put(ChatWebSocketHandler.AFTER_MESSAGE_ID_ATTRIBUTE, afterMessageId);
		}
		return attributes;
	}

	private WebSocketSession session(Map<String, Object> attributes) {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getAttributes()).thenReturn(attributes);
		return session;
	}
}
