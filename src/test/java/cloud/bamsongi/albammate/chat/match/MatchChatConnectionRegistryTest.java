package cloud.bamsongi.albammate.chat.match;

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

import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** T1: 연결 레지스트리의 등록·해제, Party별 조회와 재연결 기준 계산을 직접 검증한다. */
class MatchChatConnectionRegistryTest {

	private static final long PARTY_ID = 7L;
	private static final long OTHER_PARTY_ID = 8L;
	private static final long MATCH_CHAT_ROOM_ID = 99L;
	private static final long USER_ID = 42L;

	private final MatchChatRoomRepository matchChatRoomRepository = mock(MatchChatRoomRepository.class);
	private final MatchChatMessageRepository matchChatMessageRepository = mock(MatchChatMessageRepository.class);
	private final MatchChatWebSocketMetrics metrics = new MatchChatWebSocketMetrics(new SimpleMeterRegistry());
	private final MatchChatConnectionRegistry registry = new MatchChatConnectionRegistry(
		matchChatRoomRepository, matchChatMessageRepository, metrics);

	@Test
	void T1_partyId나_userId_속성이_없으면_등록하지_않고_활성_연결_수도_증가하지_않는다() {
		WebSocketSession session = session(new HashMap<>());

		MatchChatPartyConnection connection = registry.register(session);

		assertNull(connection);
		assertEquals(0, metrics.activeConnectionCount());
	}

	@Test
	void T1_partyId의_MatchChatRoom이_없으면_등록하지_않는다() {
		when(matchChatRoomRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.empty());
		WebSocketSession session = session(attributes(PARTY_ID, USER_ID, null));

		MatchChatPartyConnection connection = registry.register(session);

		assertNull(connection);
		assertEquals(0, metrics.activeConnectionCount());
	}

	@Test
	void T1_속성이_갖춰지면_등록하고_해제하면_활성_연결_수가_증감한다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession session = session(attributes(PARTY_ID, USER_ID, null));

		MatchChatPartyConnection connection = registry.register(session);

		assertNotNull(connection);
		assertEquals(1, metrics.activeConnectionCount());

		registry.unregister(session);

		assertEquals(0, metrics.activeConnectionCount());
	}

	@Test
	void T1_같은_Party의_여러_연결을_Party_ID로_함께_조회하고_다른_Party_ID로는_조회하지_않는다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession sessionA = session(attributes(PARTY_ID, USER_ID, null));
		WebSocketSession sessionB = session(attributes(PARTY_ID, USER_ID + 1, null));

		MatchChatPartyConnection connectionA = registry.register(sessionA);
		MatchChatPartyConnection connectionB = registry.register(sessionB);

		Set<MatchChatPartyConnection> found = registry.findByPartyId(PARTY_ID);
		assertEquals(Set.of(connectionA, connectionB), found);
		assertTrue(registry.findByPartyId(OTHER_PARTY_ID).isEmpty());
	}

	@Test
	void T1_주기_재검증이_쓸_Party별_연결_스냅샷을_만든다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession session = session(attributes(PARTY_ID, USER_ID, null));

		MatchChatPartyConnection connection = registry.register(session);

		assertEquals(Map.of(PARTY_ID, Set.of(connection)), registry.snapshotByPartyId());
	}

	@Test
	void T1_Party의_마지막_연결이_해제되면_Party_항목을_남기지_않는다() {
		stubChatRoom();
		stubLatestMessageId(0L);
		WebSocketSession sessionA = session(attributes(PARTY_ID, USER_ID, null));
		WebSocketSession sessionB = session(attributes(PARTY_ID, USER_ID + 1, null));
		registry.register(sessionA);
		registry.register(sessionB);

		registry.unregister(sessionA);
		assertEquals(1, registry.findByPartyId(PARTY_ID).size());

		registry.unregister(sessionB);
		assertTrue(registry.findByPartyId(PARTY_ID).isEmpty());
	}

	@Test
	void T3_현재_Party에_존재하는_afterMessageId면_그_값을_기준으로_삼는다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(matchChatMessageRepository.existsByIdAndMatchChatRoomId(5L, MATCH_CHAT_ROOM_ID)).thenReturn(true);
		WebSocketSession session = session(attributes(PARTY_ID, USER_ID, 5L));

		MatchChatPartyConnection connection = registry.register(session);

		assertEquals(5L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_현재_Party에_없는_afterMessageId는_0으로_되돌린다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(matchChatMessageRepository.existsByIdAndMatchChatRoomId(3L, MATCH_CHAT_ROOM_ID)).thenReturn(false);
		WebSocketSession session = session(attributes(PARTY_ID, USER_ID, 3L));

		MatchChatPartyConnection connection = registry.register(session);

		assertEquals(0L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_다른_Party에_존재하지만_현재_Party_최신_ID보다_큰_afterMessageId도_0으로_되돌린다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(matchChatMessageRepository.existsByIdAndMatchChatRoomId(20L, MATCH_CHAT_ROOM_ID)).thenReturn(false);
		when(matchChatMessageRepository.existsById(20L)).thenReturn(true);
		WebSocketSession session = session(attributes(PARTY_ID, USER_ID, 20L));

		MatchChatPartyConnection connection = registry.register(session);

		assertEquals(0L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_아직_저장되지_않은_미래_afterMessageId는_현재_최신_ID로_제한한다() {
		stubChatRoom();
		stubLatestMessageId(7L);
		when(matchChatMessageRepository.existsByIdAndMatchChatRoomId(20L, MATCH_CHAT_ROOM_ID)).thenReturn(false);
		when(matchChatMessageRepository.existsById(20L)).thenReturn(false);
		WebSocketSession session = session(attributes(PARTY_ID, USER_ID, 20L));

		MatchChatPartyConnection connection = registry.register(session);

		assertEquals(7L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T3_afterMessageId가_없으면_현재_최신_ID를_기준으로_삼는다() {
		stubChatRoom();
		stubLatestMessageId(9L);
		WebSocketSession session = session(attributes(PARTY_ID, USER_ID, null));

		MatchChatPartyConnection connection = registry.register(session);

		assertEquals(9L, connection.lastDeliveredMessageId.get());
	}

	private void stubChatRoom() {
		MatchChatRoom chatRoom = mock(MatchChatRoom.class);
		when(chatRoom.getId()).thenReturn(MATCH_CHAT_ROOM_ID);
		when(matchChatRoomRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.of(chatRoom));
	}

	private void stubLatestMessageId(long latestMessageId) {
		if (latestMessageId == 0L) {
			when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(eq(MATCH_CHAT_ROOM_ID), any()))
				.thenReturn(List.of());
			return;
		}
		MatchChatMessage latest = mock(MatchChatMessage.class);
		when(latest.getId()).thenReturn(latestMessageId);
		when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(MATCH_CHAT_ROOM_ID, PageRequest.of(0, 1)))
			.thenReturn(List.of(latest));
	}

	private Map<String, Object> attributes(Long partyId, Long userId, Long afterMessageId) {
		Map<String, Object> attributes = new HashMap<>();
		if (partyId != null) {
			attributes.put(MatchChatWebSocketHandler.PARTY_ID_ATTRIBUTE, partyId);
		}
		if (userId != null) {
			attributes.put(MatchChatWebSocketHandler.USER_ID_ATTRIBUTE, userId);
		}
		if (afterMessageId != null) {
			attributes.put(MatchChatWebSocketHandler.AFTER_MESSAGE_ID_ATTRIBUTE, afterMessageId);
		}
		return attributes;
	}

	private WebSocketSession session(Map<String, Object> attributes) {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.getAttributes()).thenReturn(attributes);
		return session;
	}
}
