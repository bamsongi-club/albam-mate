package cloud.bamsongi.albammate.chat.match.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import cloud.bamsongi.albammate.chat.match.MatchChatMessageCommitted;
import cloud.bamsongi.albammate.chat.match.MatchChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.match.MatchChatMessageType;
import cloud.bamsongi.albammate.chat.match.MatchChatSender;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatMessage;
import cloud.bamsongi.albammate.chat.match.entity.MatchChatRoom;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatMessageRepository;
import cloud.bamsongi.albammate.chat.match.repository.MatchChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.matching.contract.MatchPartyChatWriteGuard;
import cloud.bamsongi.albammate.matching.contract.MatchPartyParticipantRefQuery;
import cloud.bamsongi.albammate.user.contract.UserQuery;

/** CHAT-T3 — 전송 검증, clientMessageId 멱등성, 접근 위임을 mock 협력자로 검증한다. */
class MatchChatMessageCommandServiceTest {

	private static final long PARTY_ID = 7L;
	private static final long CURRENT_USER_ID = 42L;
	private static final long CHAT_ROOM_ID = 700L;
	private static final Instant NOW = Instant.parse("2026-08-19T00:00:00Z");

	private MatchPartyChatWriteGuard matchPartyChatWriteGuard;
	private MatchChatRoomRepository matchChatRoomRepository;
	private MatchChatMessageRepository matchChatMessageRepository;
	private MatchPartyParticipantRefQuery matchPartyParticipantRefQuery;
	private UserQuery userQuery;
	private ApplicationEventPublisher eventPublisher;
	private MatchChatMessageCommandService service;

	@BeforeEach
	void setUp() {
		matchPartyChatWriteGuard = mock(MatchPartyChatWriteGuard.class);
		matchChatRoomRepository = mock(MatchChatRoomRepository.class);
		matchChatMessageRepository = mock(MatchChatMessageRepository.class);
		matchPartyParticipantRefQuery = mock(MatchPartyParticipantRefQuery.class);
		userQuery = mock(UserQuery.class);
		eventPublisher = mock(ApplicationEventPublisher.class);
		service = new MatchChatMessageCommandService(
			matchPartyChatWriteGuard,
			matchChatRoomRepository,
			matchChatMessageRepository,
			matchPartyParticipantRefQuery,
			userQuery,
			eventPublisher,
			Clock.fixed(NOW, ZoneOffset.UTC));

		when(matchPartyChatWriteGuard.executeWithActiveAccess(anyLong(), anyLong(), any()))
			.thenAnswer(invocation -> ((Supplier<?>)invocation.getArgument(2)).get());
		MatchChatRoom chatRoom = mock(MatchChatRoom.class);
		when(chatRoom.getId()).thenReturn(CHAT_ROOM_ID);
		when(matchChatRoomRepository.findByPartyId(PARTY_ID)).thenReturn(Optional.of(chatRoom));
		when(matchPartyParticipantRefQuery.findParticipantRef(PARTY_ID, CURRENT_USER_ID))
			.thenReturn(Optional.of("ref-1"));
		when(userQuery.findNicknameById(CURRENT_USER_ID)).thenReturn(Optional.of("발신자"));
		when(matchChatMessageRepository.save(any())).thenAnswer(invocation -> {
			MatchChatMessage saved = invocation.getArgument(0);
			ReflectionTestUtils.setField(saved, "id", 900L);
			return saved;
		});
	}

	@Test
	void 최초_유효_전송은_생성_결과와_참가자_정보를_반환하고_커밋_이벤트를_발행한다() {
		when(matchChatMessageRepository.findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(
			CHAT_ROOM_ID, CURRENT_USER_ID, "client-1")).thenReturn(Optional.empty());

		MatchChatMessageSendResult result = service.send(
			CURRENT_USER_ID, PARTY_ID, new MatchChatMessageSendRequest("client-1", "같이 플레이해요."));

		assertTrue(result.created());
		assertEquals(900L, result.message().messageId());
		assertEquals(PARTY_ID, result.message().partyId());
		assertEquals(MatchChatMessageType.USER, result.message().type());
		assertEquals("client-1", result.message().clientMessageId());
		assertEquals(new MatchChatSender("ref-1", "발신자"), result.message().sender());
		assertTrue(result.message().isMine());
		assertEquals("같이 플레이해요.", result.message().content());
		verify(eventPublisher).publishEvent(MatchChatMessageCommitted.messageCreated(PARTY_ID, 900L));
	}

	@Test
	void 같은_clientMessageId와_같은_정규화_본문_재요청은_동일한_데이터를_반환하고_새로_저장하거나_이벤트를_발행하지_않는다() {
		MatchChatMessage existing = MatchChatMessage.createUserMessage(
			CHAT_ROOM_ID, CURRENT_USER_ID, "client-1", "안녕하세요", NOW);
		ReflectionTestUtils.setField(existing, "id", 901L);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(
			CHAT_ROOM_ID, CURRENT_USER_ID, "client-1")).thenReturn(Optional.of(existing));

		MatchChatMessageSendResult result = service.send(
			CURRENT_USER_ID, PARTY_ID, new MatchChatMessageSendRequest("client-1", "  안녕하세요  "));

		assertTrue(!result.created());
		assertEquals(901L, result.message().messageId());
		assertEquals("안녕하세요", result.message().content());
		verify(matchChatMessageRepository, never()).save(any());
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void 같은_clientMessageId_다른_정규화_본문은_VALIDATION_ERROR이고_저장이나_이벤트를_남기지_않는다() {
		MatchChatMessage existing = MatchChatMessage.createUserMessage(
			CHAT_ROOM_ID, CURRENT_USER_ID, "client-1", "첫 본문", NOW);
		when(matchChatMessageRepository.findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(
			CHAT_ROOM_ID, CURRENT_USER_ID, "client-1")).thenReturn(Optional.of(existing));

		assertValidationError(new MatchChatMessageSendRequest("client-1", "다른 본문"));

		verify(matchChatMessageRepository, never()).save(any());
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void clientMessageId가_비었거나_100자를_넘으면_VALIDATION_ERROR다() {
		assertValidationError(new MatchChatMessageSendRequest("", "본문"));
		assertValidationError(new MatchChatMessageSendRequest(null, "본문"));
		assertValidationError(new MatchChatMessageSendRequest("i".repeat(101), "본문"));
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void content가_비었거나_500자를_넘으면_VALIDATION_ERROR다() {
		when(matchChatMessageRepository.findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(
			eq(CHAT_ROOM_ID), eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());

		assertValidationError(new MatchChatMessageSendRequest("client-empty", null));
		assertValidationError(new MatchChatMessageSendRequest("client-empty-2", ""));
		assertValidationError(new MatchChatMessageSendRequest("client-empty-3", "   "));
		assertValidationError(new MatchChatMessageSendRequest("client-too-long", "c".repeat(501)));
		verify(matchChatMessageRepository, never()).save(any());
	}

	@Test
	void CRLF는_LF로_정규화되고_LF_외_제어문자는_거절된다() {
		when(matchChatMessageRepository.findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(
			eq(CHAT_ROOM_ID), eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());

		MatchChatMessageSendResult result = service.send(
			CURRENT_USER_ID, PARTY_ID, new MatchChatMessageSendRequest("client-crlf", "첫 줄\r\n둘째 줄"));

		assertEquals("첫 줄\n둘째 줄", result.message().content());
		assertValidationError(new MatchChatMessageSendRequest("client-control", "본문" + (char)7 + "끝"));
	}

	@Test
	void LF_외의_줄바꿈_서식_문자는_거절된다() {
		when(matchChatMessageRepository.findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(
			eq(CHAT_ROOM_ID), eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());

		assertValidationError(new MatchChatMessageSendRequest("client-line-separator", "본문" + (char)0x2028 + "끝"));
		assertValidationError(
			new MatchChatMessageSendRequest("client-paragraph-separator", "본문" + (char)0x2029 + "끝"));
		assertValidationError(new MatchChatMessageSendRequest("client-rtl-override", "본문" + (char)0x202E + "끝"));
		verify(matchChatMessageRepository, never()).save(any());
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void 앞뒤_공백은_제거된다() {
		when(matchChatMessageRepository.findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(
			eq(CHAT_ROOM_ID), eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());

		MatchChatMessageSendResult result = service.send(
			CURRENT_USER_ID, PARTY_ID, new MatchChatMessageSendRequest("client-trim", "  본문  "));

		assertEquals("본문", result.message().content());
	}

	@Test
	void 정확히_경계값_100자_500자는_저장된다() {
		when(matchChatMessageRepository.findByMatchChatRoomIdAndSenderUserIdAndClientMessageId(
			eq(CHAT_ROOM_ID), eq(CURRENT_USER_ID), any())).thenReturn(Optional.empty());

		MatchChatMessageSendResult result = service.send(
			CURRENT_USER_ID, PARTY_ID,
			new MatchChatMessageSendRequest("i".repeat(100), "c".repeat(500)));

		assertTrue(result.created());
		assertEquals(500, result.message().content().length());
	}

	@Test
	void 접근이_거부되면_write_guard_예외를_그대로_전파하고_저장소나_이벤트를_건드리지_않는다() {
		org.mockito.Mockito.reset(matchPartyChatWriteGuard);
		when(matchPartyChatWriteGuard.executeWithActiveAccess(eq(CURRENT_USER_ID), eq(PARTY_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.FORBIDDEN));

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> service.send(CURRENT_USER_ID, PARTY_ID, new MatchChatMessageSendRequest("client-1", "본문")));

		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
		verifyNoInteractions(matchChatMessageRepository);
		verifyNoInteractions(eventPublisher);
	}

	@Test
	void NOT_ACTIVE_접근_거부도_그대로_전파한다() {
		org.mockito.Mockito.reset(matchPartyChatWriteGuard);
		when(matchPartyChatWriteGuard.executeWithActiveAccess(eq(CURRENT_USER_ID), eq(PARTY_ID), any()))
			.thenThrow(new BusinessException(ErrorCode.MATCH_CHAT_NOT_ACTIVE));

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> service.send(CURRENT_USER_ID, PARTY_ID, new MatchChatMessageSendRequest("client-1", "본문")));

		assertEquals(ErrorCode.MATCH_CHAT_NOT_ACTIVE, exception.getErrorCode());
		verifyNoMoreInteractions(matchChatMessageRepository);
	}

	@Test
	void 검증_실패는_write_guard_안에서_일어나지만_참가자_조회_전에_끝난다() {
		assertValidationError(new MatchChatMessageSendRequest(null, "본문"));

		verify(matchPartyParticipantRefQuery, never()).findParticipantRef(anyLong(), anyLong());
	}

	private void assertValidationError(MatchChatMessageSendRequest request) {
		BusinessException exception = assertThrows(
			BusinessException.class, () -> service.send(CURRENT_USER_ID, PARTY_ID, request));
		assertEquals(ErrorCode.VALIDATION_ERROR, exception.getErrorCode());
	}
}
