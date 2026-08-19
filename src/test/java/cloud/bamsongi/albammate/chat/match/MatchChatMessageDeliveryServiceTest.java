package cloud.bamsongi.albammate.chat.match;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.json.JsonMapper;

/** T4: catch-up 전달 컴포넌트가 마지막 전달 ID 이후 메시지를 ASC로 전달하고 실패를 종료·계측하는 동작을 직접 검증한다. */
class MatchChatMessageDeliveryServiceTest {

	private static final long PARTY_ID = 7L;
	private static final long MATCH_CHAT_ROOM_ID = 99L;
	private static final long USER_ID = 42L;
	private static final Instant CREATED_AT = Instant.parse("2026-08-05T00:00:00Z");

	private final MatchChatConnectionRegistry connectionRegistry = mock(MatchChatConnectionRegistry.class);
	private final MatchChatMessageRepository matchChatMessageRepository = mock(MatchChatMessageRepository.class);
	private final MatchPartyParticipantRefQuery matchPartyParticipantRefQuery = mock(
		MatchPartyParticipantRefQuery.class);
	private final UserQuery userQuery = mock(UserQuery.class);
	private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
	private final MatchChatWebSocketMetrics metrics = new MatchChatWebSocketMetrics(meterRegistry);
	private final MatchChatMessageDeliveryService deliveryService = new MatchChatMessageDeliveryService(
		connectionRegistry, matchChatMessageRepository, matchPartyParticipantRefQuery, userQuery, metrics,
		JsonMapper.builder().build(), Clock.fixed(CREATED_AT.plusSeconds(1), ZoneOffset.UTC));

	@Test
	void T1_발신자_participantRef나_닉네임이_누락되면_전송하지_않고_기준을_유지한_채_종료한다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false);
		MatchChatPartyConnection connection = new MatchChatPartyConnection(
			session, PARTY_ID, MATCH_CHAT_ROOM_ID, USER_ID, 0L);
		MatchChatMessage message = userMessage(1L, 77L);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message));
		when(matchPartyParticipantRefQuery.findParticipantRefs(PARTY_ID, Set.of(77L))).thenReturn(Map.of());
		when(userQuery.findNicknamesByIds(Set.of(77L))).thenReturn(Map.of());

		deliveryService.deliverNewMessages(connection);

		verify(session, never()).sendMessage(any());
		assertEquals(0L, connection.lastDeliveredMessageId.get());
		verify(connectionRegistry).closeForTransportFailure(session);
		assertEquals(1.0, meterRegistry.get("match.chat.websocket.delivery.failures").counter().count());
	}

	@Test
	void T2_SYSTEM_메시지는_sender가_null로_전달된다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false);
		MatchChatPartyConnection connection = new MatchChatPartyConnection(
			session, PARTY_ID, MATCH_CHAT_ROOM_ID, USER_ID, 0L);
		MatchChatMessage message = systemMessage(1L);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message));

		deliveryService.deliverNewMessages(connection);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session).sendMessage(captor.capture());
		assertTrue(captor.getValue().getPayload().contains("\"sender\":null"));
		assertEquals(1L, connection.lastDeliveredMessageId.get());
	}

	@Test
	void T4_마지막_전달_ID_이후_메시지만_ASC로_전달하고_기준을_갱신해_중복_전달하지_않는다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false);
		MatchChatPartyConnection connection = new MatchChatPartyConnection(
			session, PARTY_ID, MATCH_CHAT_ROOM_ID, USER_ID, 0L);
		MatchChatMessage message1 = userMessage(1L, USER_ID);
		MatchChatMessage message2 = userMessage(2L, USER_ID);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message1, message2));
		stubSender(USER_ID);

		deliveryService.deliverNewMessages(connection);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session, times(2)).sendMessage(captor.capture());
		assertTrue(captor.getAllValues().get(0).getPayload().contains("\"eventId\":1"));
		assertTrue(captor.getAllValues().get(1).getPayload().contains("\"eventId\":2"));
		assertEquals(2L, connection.lastDeliveredMessageId.get());

		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 2L))
			.thenReturn(List.of());
		org.mockito.Mockito.clearInvocations(session);

		deliveryService.deliverNewMessages(connection);

		verify(session, never()).sendMessage(any());
	}

	@Test
	void T4_전송이_실패하면_그_메시지에서_멈추고_SERVER_ERROR로_종료하며_실패를_계측한다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false);
		MatchChatPartyConnection connection = new MatchChatPartyConnection(
			session, PARTY_ID, MATCH_CHAT_ROOM_ID, USER_ID, 0L);
		MatchChatMessage message1 = userMessage(1L, USER_ID);
		MatchChatMessage message2 = userMessage(2L, USER_ID);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message1, message2));
		stubSender(USER_ID);
		doThrow(new IOException("boom")).when(session).sendMessage(any());

		deliveryService.deliverNewMessages(connection);

		verify(session, times(1)).sendMessage(any());
		assertEquals(0L, connection.lastDeliveredMessageId.get());
		verify(connectionRegistry).closeForTransportFailure(session);
		assertEquals(1.0, meterRegistry.get("match.chat.websocket.delivery.failures").counter().count());
	}

	@Test
	void T4_shouldStopDelivery가_true면_그_메시지에서_조용히_멈추고_이후_메시지를_전달하지_않는다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false, true);
		MatchChatPartyConnection connection = new MatchChatPartyConnection(
			session, PARTY_ID, MATCH_CHAT_ROOM_ID, USER_ID, 0L);
		MatchChatMessage message1 = userMessage(1L, USER_ID);
		MatchChatMessage message2 = userMessage(2L, USER_ID);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message1, message2));
		stubSender(USER_ID);

		deliveryService.deliverNewMessages(connection);

		verify(session, times(1)).sendMessage(any());
		assertEquals(1L, connection.lastDeliveredMessageId.get());
		verify(connectionRegistry, never()).closeForTransportFailure(any());
	}

	@Test
	void 상대_메시지는_isMine이_false다() throws Exception {
		WebSocketSession session = mock(WebSocketSession.class);
		when(session.isOpen()).thenReturn(true);
		when(connectionRegistry.shouldStopDelivery(session)).thenReturn(false);
		MatchChatPartyConnection connection = new MatchChatPartyConnection(
			session, PARTY_ID, MATCH_CHAT_ROOM_ID, USER_ID, 0L);
		MatchChatMessage message = userMessage(1L, 999L);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdGreaterThanOrderByIdAsc(MATCH_CHAT_ROOM_ID, 0L))
			.thenReturn(List.of(message));
		stubSender(999L);

		deliveryService.deliverNewMessages(connection);

		ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
		verify(session).sendMessage(captor.capture());
		assertTrue(captor.getValue().getPayload().contains("\"isMine\":false"));
	}

	private void stubSender(long senderUserId) {
		when(matchPartyParticipantRefQuery.findParticipantRefs(PARTY_ID, Set.of(senderUserId)))
			.thenReturn(Map.of(senderUserId, "ref-" + senderUserId));
		when(userQuery.findNicknamesByIds(Set.of(senderUserId))).thenReturn(Map.of(senderUserId, "발신자"));
	}

	private MatchChatMessage userMessage(long messageId, long senderUserId) {
		MatchChatMessage message = mock(MatchChatMessage.class);
		when(message.getId()).thenReturn(messageId);
		when(message.getMessageType()).thenReturn(MatchChatMessageType.USER);
		when(message.getSenderUserId()).thenReturn(senderUserId);
		when(message.getClientMessageId()).thenReturn("client-" + messageId);
		when(message.getContent()).thenReturn("내용 " + messageId);
		when(message.getCreatedAt()).thenReturn(CREATED_AT);
		return message;
	}

	private MatchChatMessage systemMessage(long messageId) {
		MatchChatMessage message = mock(MatchChatMessage.class);
		when(message.getId()).thenReturn(messageId);
		when(message.getMessageType()).thenReturn(MatchChatMessageType.SYSTEM);
		when(message.getSenderUserId()).thenReturn(null);
		when(message.getContent()).thenReturn("입장했습니다.");
		when(message.getCreatedAt()).thenReturn(CREATED_AT);
		return message;
	}
}
