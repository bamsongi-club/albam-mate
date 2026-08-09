package cloud.bamsongi.albammate.chat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.user.contract.UserQuery;

class ChatMessageCommandServiceTest {

	private static final long ROOM_ID = 9L;
	private static final long SENDER_USER_ID = 314159L;
	private static final String SECRET_CONTENT = "로그에_남으면_안되는_본문_마커";

	@Test
	void 발신자_닉네임을_찾을_수_없으면_로그에_roomId만_남고_사용자_ID와_본문은_남지_않는다() {
		ChatAccessGuard chatAccessGuard = mock(ChatAccessGuard.class);
		when(chatAccessGuard.executeWithAccess(anyLong(), anyLong(), any()))
			.thenAnswer(invocation -> ((Supplier<?>)invocation.getArgument(2)).get());
		ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoomRepository.findByRoomIdForMessageAppend(ROOM_ID)).thenReturn(Optional.of(chatRoom));
		UserQuery userQuery = mock(UserQuery.class);
		when(userQuery.findNicknameById(SENDER_USER_ID)).thenReturn(Optional.empty());
		ChatMessageCommandService service = new ChatMessageCommandService(
			chatAccessGuard,
			chatRoomRepository,
			mock(ChatMessageRepository.class),
			userQuery,
			mock(ChatMessageRateLimiter.class),
			mock(ApplicationEventPublisher.class),
			Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
			new ChatMessageLimitProperties());
		ListAppender<ILoggingEvent> appender = attachLogAppender();

		try {
			BusinessException exception = assertThrows(
				BusinessException.class,
				() -> service.send(
					SENDER_USER_ID, ROOM_ID, new ChatMessageSendRequest("client-1", SECRET_CONTENT)));

			assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
			assertEquals(1, appender.list.size());
			ILoggingEvent event = appender.list.getFirst();
			assertEquals(Level.ERROR, event.getLevel());
			assertEquals("event=chat_message_sender_nickname_missing roomId=9", event.getFormattedMessage());
			assertFalse(event.getFormattedMessage().contains(String.valueOf(SENDER_USER_ID)));
			assertFalse(event.getFormattedMessage().contains(SECRET_CONTENT));
		} finally {
			detachLogAppender(appender);
		}
	}

	private ListAppender<ILoggingEvent> attachLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(ChatMessageCommandService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(ChatMessageCommandService.class);
		logger.detachAppender(appender);
		appender.stop();
	}
}
