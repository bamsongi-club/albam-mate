package cloud.bamsongi.albammate.chat.service;

import static cloud.bamsongi.albammate.fixture.StructuredLogAssertions.assertFields;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;
import cloud.bamsongi.albammate.chat.dto.ChatMessageSendRequest;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

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
		when(userQuery.findUserSummaryById(SENDER_USER_ID)).thenReturn(Optional.empty());
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
			assertFields(event, java.util.Map.of("event", "chat_message_sender_nickname_missing", "roomId", ROOM_ID));
			assertFalse(event.getFormattedMessage().contains(String.valueOf(SENDER_USER_ID)));
			assertFalse(event.getFormattedMessage().contains(SECRET_CONTENT));
		} finally {
			detachLogAppender(appender);
		}
	}

	@Test
	void T2_채팅_업무_거절은_전달_metric과_분리한_유한_outcome으로_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		ChatAccessGuard chatAccessGuard = mock(ChatAccessGuard.class);
		when(chatAccessGuard.executeWithAccess(anyLong(), anyLong(), any()))
			.thenThrow(
				new BusinessException(ErrorCode.FORBIDDEN),
				new BusinessException(ErrorCode.SERVICE_UNAVAILABLE),
				new IllegalStateException("publisher unavailable"));
		ChatMessageCommandService service = new ChatMessageCommandService(
			chatAccessGuard,
			mock(ChatRoomRepository.class),
			mock(ChatMessageRepository.class),
			mock(UserQuery.class),
			mock(ChatMessageRateLimiter.class),
			mock(ApplicationEventPublisher.class),
			Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
			new ChatMessageLimitProperties());

		try {
			assertThrows(BusinessException.class,
				() -> service.send(SENDER_USER_ID, ROOM_ID, new ChatMessageSendRequest("client-1", "내용")));
			assertThrows(BusinessException.class,
				() -> service.send(SENDER_USER_ID, ROOM_ID, new ChatMessageSendRequest("client-2", "내용")));
			assertThrows(IllegalStateException.class,
				() -> service.send(SENDER_USER_ID, ROOM_ID, new ChatMessageSendRequest("client-3", "내용")));

			assertEquals(1.0, meterRegistry.get("chat.message.operations").tag("outcome", "rejected").counter()
				.count());
			assertEquals(2.0, meterRegistry.get("chat.message.operations").tag("outcome", "failed").counter()
				.count());
			assertTrue(meterRegistry.find("chat.message.delivery.failures").meters().isEmpty());
			assertTrue(meterRegistry.find("chat.message.operations").meters().stream()
				.allMatch(meter -> meter.getId().getTags().stream()
					.allMatch(tag -> "outcome".equals(tag.getKey()))));
		} finally {
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
		}
	}

	@Test
	void T2_채팅_저장_성공은_커밋_뒤에만_업무_success로_기록한다() {
		SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
		Metrics.addRegistry(meterRegistry);
		TransactionSynchronizationManager.initSynchronization();
		ChatAccessGuard chatAccessGuard = mock(ChatAccessGuard.class);
		when(chatAccessGuard.executeWithAccess(anyLong(), anyLong(), any()))
			.thenAnswer(invocation -> ((Supplier<?>)invocation.getArgument(2)).get());
		ChatRoom chatRoom = mock(ChatRoom.class);
		when(chatRoom.getId()).thenReturn(3L);
		ChatRoomRepository chatRoomRepository = mock(ChatRoomRepository.class);
		when(chatRoomRepository.findByRoomIdForMessageAppend(ROOM_ID)).thenReturn(Optional.of(chatRoom));
		ChatMessageRepository chatMessageRepository = mock(ChatMessageRepository.class);
		when(chatMessageRepository.findByChatRoomIdAndSenderUserIdAndClientMessageId(3L, SENDER_USER_ID, "client-1"))
			.thenReturn(Optional.empty());
		ChatMessage saved = mock(ChatMessage.class);
		when(saved.getId()).thenReturn(31L);
		when(saved.getClientMessageId()).thenReturn("client-1");
		when(saved.getContent()).thenReturn("내용");
		when(saved.getCreatedAt()).thenReturn(Instant.parse("2026-08-04T00:00:00Z"));
		when(chatMessageRepository.save(any(ChatMessage.class))).thenReturn(saved);
		UserQuery userQuery = mock(UserQuery.class);
		when(userQuery.findUserSummaryById(SENDER_USER_ID))
			.thenReturn(Optional.of(new UserQuery.UserSummary("발신자", null)));
		ChatMessageRateLimiter rateLimiter = mock(ChatMessageRateLimiter.class);
		when(rateLimiter.reserve(SENDER_USER_ID, ROOM_ID))
			.thenReturn(mock(ChatMessageRateLimiter.RateLimitReservation.class));
		ChatMessageCommandService service = new ChatMessageCommandService(
			chatAccessGuard,
			chatRoomRepository,
			chatMessageRepository,
			userQuery,
			rateLimiter,
			mock(ApplicationEventPublisher.class),
			Clock.fixed(Instant.parse("2026-08-04T00:00:00Z"), ZoneOffset.UTC),
			new ChatMessageLimitProperties());

		try {
			service.send(SENDER_USER_ID, ROOM_ID, new ChatMessageSendRequest("client-1", "내용"));

			assertEquals(0.0, meterRegistry.get("chat.message.operations").tag("outcome", "accepted").counter()
				.count());
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
			assertEquals(1.0, meterRegistry.get("chat.message.operations").tag("outcome", "accepted").counter()
				.count());
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
			Metrics.removeRegistry(meterRegistry);
			meterRegistry.close();
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
