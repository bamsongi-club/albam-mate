package cloud.bamsongi.albammate.chat.match.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.chat.match.MatchChatMessagePageResponse;
import cloud.bamsongi.albammate.chat.match.MatchChatMessageResponse;
import cloud.bamsongi.albammate.chat.match.MatchChatMessageType;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.contract.MatchPartyAccessQuery;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatAccess;
import cloud.bamsongi.albammate.user.contract.UserQuery;

/** CHAT-T4 — beforeMessageId 커서, size 기본값, SYSTEM 메시지 노출과 접근 판정 전달을 mock 협력자로 검증한다. */
class MatchChatMessageHistoryQueryServiceTest {

	private static final long PARTY_ID = 7L;
	private static final long CURRENT_USER_ID = 42L;
	private static final long CHAT_ROOM_ID = 700L;
	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

	private MatchPartyAccessQuery matchPartyAccessQuery;
	private MatchChatRoomRepository matchChatRoomRepository;
	private MatchChatMessageRepository matchChatMessageRepository;
	private cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery matchPartyParticipantRefQuery;
	private UserQuery userQuery;
	private MatchChatMessageHistoryQueryService service;

	@BeforeEach
	void setUp() {
		matchPartyAccessQuery = mock(MatchPartyAccessQuery.class);
		matchChatRoomRepository = mock(MatchChatRoomRepository.class);
		matchChatMessageRepository = mock(MatchChatMessageRepository.class);
		matchPartyParticipantRefQuery = mock(
			cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery.class);
		userQuery = mock(UserQuery.class);
		service = new MatchChatMessageHistoryQueryService(
			matchPartyAccessQuery,
			matchChatRoomRepository,
			matchChatMessageRepository,
			matchPartyParticipantRefQuery,
			userQuery);

		when(matchPartyAccessQuery.evaluateChatAccess(CURRENT_USER_ID, PARTY_ID))
			.thenReturn(MatchPartyChatAccess.ALLOWED);
		MatchChatRoom chatRoom = mock(MatchChatRoom.class);
		when(chatRoom.getId()).thenReturn(CHAT_ROOM_ID);
		when(matchChatRoomRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.of(chatRoom));
	}

	@Test
	void beforeMessageId가_없으면_최신_구간을_조회한다() {
		MatchChatMessage userMessage = userMessage(20L, CURRENT_USER_ID, "client-1", "본문1");
		when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(CHAT_ROOM_ID, PageRequest.of(0, 51)))
			.thenReturn(List.of(userMessage));
		when(matchPartyParticipantRefQuery.findParticipantRefs(PARTY_ID, java.util.Set.of(CURRENT_USER_ID)))
			.thenReturn(Map.of(CURRENT_USER_ID, "ref-1"));
		when(userQuery.findNicknamesByIds(java.util.Set.of(CURRENT_USER_ID)))
			.thenReturn(Map.of(CURRENT_USER_ID, "발신자"));

		MatchChatMessagePageResponse page = service.history(CURRENT_USER_ID, PARTY_ID, null, 50);

		assertEquals(1, page.messages().size());
		assertFalse(page.hasNext());
		assertNull(page.nextBeforeMessageId());
		MatchChatMessageResponse message = page.messages().get(0);
		assertEquals(20L, message.messageId());
		assertEquals("ref-1", message.sender().participantRef());
		assertEquals("발신자", message.sender().nickname());
		assertTrue(message.isMine());
		verify(matchChatMessageRepository, never())
			.findByMatchChatRoomIdAndIdLessThanOrderByIdDesc(anyLong(), anyLong(), any());
	}

	@Test
	void beforeMessageId가_있으면_그보다_작은_과거_메시지를_조회한다() {
		when(matchChatMessageRepository.findByMatchChatRoomIdAndIdLessThanOrderByIdDesc(
			CHAT_ROOM_ID, 20L, PageRequest.of(0, 11)))
			.thenReturn(List.of());

		MatchChatMessagePageResponse page = service.history(CURRENT_USER_ID, PARTY_ID, 20L, 10);

		assertTrue(page.messages().isEmpty());
		assertFalse(page.hasNext());
	}

	@Test
	void size보다_많이_조회되면_hasNext는_true이고_마지막_페이지_항목_id를_커서로_반환한다() {
		MatchChatMessage first = userMessage(30L, CURRENT_USER_ID, "client-1", "본문1");
		MatchChatMessage second = userMessage(29L, CURRENT_USER_ID, "client-2", "본문2");
		MatchChatMessage extra = userMessage(28L, CURRENT_USER_ID, "client-3", "본문3");
		when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(CHAT_ROOM_ID, PageRequest.of(0, 3)))
			.thenReturn(List.of(first, second, extra));
		when(matchPartyParticipantRefQuery.findParticipantRefs(PARTY_ID, java.util.Set.of(CURRENT_USER_ID)))
			.thenReturn(Map.of(CURRENT_USER_ID, "ref-1"));
		when(userQuery.findNicknamesByIds(java.util.Set.of(CURRENT_USER_ID)))
			.thenReturn(Map.of(CURRENT_USER_ID, "발신자"));

		MatchChatMessagePageResponse page = service.history(CURRENT_USER_ID, PARTY_ID, null, 2);

		assertEquals(2, page.messages().size());
		assertTrue(page.hasNext());
		assertEquals(29L, page.nextBeforeMessageId());
	}

	@Test
	void SYSTEM_메시지는_sender가_null이고_isMine이_false다() {
		MatchChatMessage systemMessage = MatchChatMessage.createSystemMessage(
			CHAT_ROOM_ID, cloud.bamsongi.albammate.chat.match.MatchChatSystemEventKey.CHAT_OPENED, "채팅이 열렸습니다.", NOW);
		ReflectionTestUtils.setField(systemMessage, "id", 15L);
		when(matchChatMessageRepository.findByMatchChatRoomIdOrderByIdDesc(CHAT_ROOM_ID, PageRequest.of(0, 51)))
			.thenReturn(List.of(systemMessage));

		MatchChatMessagePageResponse page = service.history(CURRENT_USER_ID, PARTY_ID, null, 50);

		MatchChatMessageResponse message = page.messages().get(0);
		assertEquals(MatchChatMessageType.SYSTEM, message.type());
		assertNull(message.sender());
		assertFalse(message.isMine());
		verifyNoInteractions(matchPartyParticipantRefQuery);
	}

	@Test
	void 접근이_NOT_ACTIVE면_MATCH_CHAT_NOT_ACTIVE이고_조회하지_않는다() {
		when(matchPartyAccessQuery.evaluateChatAccess(CURRENT_USER_ID, PARTY_ID))
			.thenReturn(MatchPartyChatAccess.NOT_ACTIVE);

		BusinessException exception = assertThrows(
			BusinessException.class, () -> service.history(CURRENT_USER_ID, PARTY_ID, null, 50));

		assertEquals(ErrorCode.MATCH_CHAT_NOT_ACTIVE, exception.getErrorCode());
		verifyNoInteractions(matchChatRoomRepository);
		verifyNoInteractions(matchChatMessageRepository);
	}

	@Test
	void 접근이_FORBIDDEN이면_FORBIDDEN이고_조회하지_않는다() {
		when(matchPartyAccessQuery.evaluateChatAccess(CURRENT_USER_ID, PARTY_ID))
			.thenReturn(MatchPartyChatAccess.FORBIDDEN);

		BusinessException exception = assertThrows(
			BusinessException.class, () -> service.history(CURRENT_USER_ID, PARTY_ID, null, 50));

		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
		verifyNoInteractions(matchChatRoomRepository);
		verifyNoInteractions(matchChatMessageRepository);
	}

	private MatchChatMessage userMessage(long id, long senderUserId, String clientMessageId, String content) {
		MatchChatMessage message = MatchChatMessage.createUserMessage(
			CHAT_ROOM_ID, senderUserId, clientMessageId, content, NOW);
		ReflectionTestUtils.setField(message, "id", id);
		return message;
	}
}
