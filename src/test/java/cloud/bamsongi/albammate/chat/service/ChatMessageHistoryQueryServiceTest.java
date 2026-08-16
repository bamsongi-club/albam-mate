package cloud.bamsongi.albammate.chat.service;

import static cloud.bamsongi.albammate.fixture.StructuredLogAssertions.assertFields;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.user.contract.UserQuery;

@ExtendWith(MockitoExtension.class)
class ChatMessageHistoryQueryServiceTest {

	@Mock
	private ChatAccessGuard chatAccessGuard;
	@Mock
	private ChatRoomRepository chatRoomRepository;
	@Mock
	private ChatMessageRepository chatMessageRepository;
	@Mock
	private UserQuery userQuery;

	private ChatMessageHistoryQueryService chatMessageHistoryQueryService;

	@BeforeEach
	void setUp() {
		chatMessageHistoryQueryService = new ChatMessageHistoryQueryService(
			chatAccessGuard, chatRoomRepository, chatMessageRepository, userQuery);
		when(chatAccessGuard.executeWithAccess(anyLong(), anyLong(), any()))
			.thenAnswer(invocation -> ((Supplier<?>)invocation.getArgument(2)).get());
	}

	@Test
	void 메시지_발신자_닉네임을_찾을_수_없으면_내부_오류다() {
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoom.getId()).thenReturn(99L);
		when(chatRoomRepository.findByRoomId(7L)).thenReturn(Optional.of(chatRoom));
		ChatMessage chatMessage = mock(ChatMessage.class);
		when(chatMessage.getSenderUserId()).thenReturn(77L);
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(eq(99L), any()))
			.thenReturn(List.of(chatMessage));
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of());

		BusinessException exception = assertThrows(
			BusinessException.class,
			() -> chatMessageHistoryQueryService.history(42L, 7L, null, 50));

		assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
	}

	@Test
	void 메시지_발신자_닉네임을_찾을_수_없으면_로그에_roomId만_남고_사용자_ID는_남지_않는다() {
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoom.getId()).thenReturn(99L);
		when(chatRoomRepository.findByRoomId(7L)).thenReturn(Optional.of(chatRoom));
		ChatMessage chatMessage = mock(ChatMessage.class);
		when(chatMessage.getSenderUserId()).thenReturn(77L);
		when(chatMessageRepository.findByChatRoomIdOrderByIdDesc(eq(99L), any()))
			.thenReturn(List.of(chatMessage));
		when(userQuery.findNicknamesByIds(any())).thenReturn(Map.of());
		ListAppender<ILoggingEvent> appender = attachLogAppender();

		try {
			assertThrows(
				BusinessException.class,
				() -> chatMessageHistoryQueryService.history(42L, 7L, null, 50));

			assertEquals(1, appender.list.size());
			ILoggingEvent event = appender.list.getFirst();
			assertEquals(Level.ERROR, event.getLevel());
			assertFields(event, Map.of("event", "chat_message_sender_nickname_missing", "roomId", 7L));
			assertFalse(event.getFormattedMessage().contains("42"));
			assertFalse(event.getFormattedMessage().contains("77"));
		} finally {
			detachLogAppender(appender);
		}
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(ChatMessageHistoryQueryService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(ChatMessageHistoryQueryService.class);
		logger.detachAppender(appender);
		appender.stop();
	}
}
