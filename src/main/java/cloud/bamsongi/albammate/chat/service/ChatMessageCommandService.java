package cloud.bamsongi.albammate.chat.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import cloud.bamsongi.albammate.chat.contract.ChatMessageRateLimiter;
import cloud.bamsongi.albammate.chat.contract.MessageCommitted;
import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
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
import lombok.extern.slf4j.Slf4j;

/** ROOM 접근 잠금 안에서 메시지를 저장하고, 신규 저장만 커밋 뒤 전달 이벤트로 등록한다. */
@Slf4j
@Service
public class ChatMessageCommandService {

	/** LF만 줄바꿈으로 허용하고, 나머지 제어문자는 공백 제거 전에 거절한다. */
	private static final Pattern DISALLOWED_CONTROL_CHARACTER_PATTERN = Pattern.compile("[\\p{Cc}&&[^\\n]]");

	private final ChatAccessGuard chatAccessGuard;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final UserQuery userQuery;
	private final ChatMessageRateLimiter chatMessageRateLimiter;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;
	private final ChatMessageLimitProperties chatMessageLimitProperties;
	private final ChatMessageMetrics metrics;

	public ChatMessageCommandService(
		ChatAccessGuard chatAccessGuard,
		ChatRoomRepository chatRoomRepository,
		ChatMessageRepository chatMessageRepository,
		UserQuery userQuery,
		ChatMessageRateLimiter chatMessageRateLimiter,
		ApplicationEventPublisher eventPublisher,
		Clock clock,
		ChatMessageLimitProperties chatMessageLimitProperties,
		ChatMessageMetrics... metrics) {
		this.chatAccessGuard = Objects.requireNonNull(chatAccessGuard, "chatAccessGuard");
		this.chatRoomRepository = Objects.requireNonNull(chatRoomRepository, "chatRoomRepository");
		this.chatMessageRepository = Objects.requireNonNull(chatMessageRepository, "chatMessageRepository");
		this.userQuery = Objects.requireNonNull(userQuery, "userQuery");
		this.chatMessageRateLimiter = Objects.requireNonNull(chatMessageRateLimiter, "chatMessageRateLimiter");
		this.eventPublisher = Objects.requireNonNull(eventPublisher, "eventPublisher");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.chatMessageLimitProperties = Objects.requireNonNull(chatMessageLimitProperties,
			"chatMessageLimitProperties");
		this.metrics = metrics.length == 0
			? new ChatMessageMetrics(Metrics.globalRegistry)
			: Objects.requireNonNull(metrics[0], "metrics");
	}

	@Transactional
	public ChatMessageSendResult send(long currentUserId, long roomId, ChatMessageSendRequest request) {
		Objects.requireNonNull(request, "request");
		try {
			ChatMessageSendResult result = chatAccessGuard.executeWithAccess(
				currentUserId,
				roomId,
				() -> appendMessage(currentUserId, roomId, request));
			registerAcceptedAfterCommit();
			return result;
		} catch (BusinessException exception) {
			recordBusinessException(exception);
			throw exception;
		} catch (RuntimeException exception) {
			metrics.recordFailed();
			throw exception;
		}
	}

	private void recordBusinessException(BusinessException exception) {
		if (exception.getErrorCode().getStatus() >= 500) {
			metrics.recordFailed();
			return;
		}
		metrics.recordRejected();
	}

	/** 새 저장과 같은 멱등 결과 모두 실제 커밋 뒤에만 업무 성공으로 기록한다. */
	private void registerAcceptedAfterCommit() {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				metrics.recordAccepted();
			}
		});
	}

	private ChatMessageSendResult appendMessage(long currentUserId, long roomId, ChatMessageSendRequest request) {
		ChatRoom chatRoom = chatRoomRepository
			.findByRoomIdForMessageAppend(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		String clientMessageId = validateClientMessageId(request.clientMessageId());
		String content = normalizeContent(request.content());
		UserQuery.UserSummary sender = requireSenderSummary(roomId, currentUserId);

		return chatMessageRepository
			.findByChatRoomIdAndSenderUserIdAndClientMessageId(chatRoom.getId(), currentUserId, clientMessageId)
			.map(existing -> existingMessage(existing, roomId, content, sender))
			.orElseGet(() -> saveNewMessage(chatRoom, currentUserId, roomId, clientMessageId, content, sender));
	}

	private ChatMessageSendResult existingMessage(
		ChatMessage existing, long roomId, String content, UserQuery.UserSummary sender) {
		if (!existing.getContent().equals(content)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return new ChatMessageSendResult(
			ChatMessageResponse.from(existing, roomId, sender.nickname(), sender.profileImageUrl(), true), false);
	}

	private ChatMessageSendResult saveNewMessage(
		ChatRoom chatRoom,
		long currentUserId,
		long roomId,
		String clientMessageId,
		String content,
		UserQuery.UserSummary sender) {
		ChatMessageRateLimiter.RateLimitReservation reservation = chatMessageRateLimiter.reserve(currentUserId, roomId);
		Runnable releaseOnce = releaseOnce(reservation);
		try {
			ChatMessage saved = chatMessageRepository.save(
				ChatMessage.create(chatRoom.getId(), currentUserId, clientMessageId, content, Instant.now(clock)));
			registerReservationReleaseOnRollback(releaseOnce);
			eventPublisher.publishEvent(MessageCommitted.messageCreated(roomId, saved.getId()));
			return new ChatMessageSendResult(
				ChatMessageResponse.from(saved, roomId, sender.nickname(), sender.profileImageUrl(), true), true);
		} catch (RuntimeException exception) {
			releaseOnce.run();
			throw exception;
		}
	}

	private Runnable releaseOnce(ChatMessageRateLimiter.RateLimitReservation reservation) {
		AtomicBoolean released = new AtomicBoolean();
		return () -> {
			if (released.compareAndSet(false, true)) {
				reservation.release();
			}
		};
	}

	private void registerReservationReleaseOnRollback(Runnable releaseOnce) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					releaseOnce.run();
				}
			}
		});
	}

	private String validateClientMessageId(String clientMessageId) {
		if (clientMessageId == null
			|| clientMessageId.isBlank()
			|| clientMessageId.length() > chatMessageLimitProperties.getMaxClientMessageIdLength()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return clientMessageId;
	}

	private String normalizeContent(String content) {
		if (content == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		String normalized = content.replace("\r\n", "\n");
		if (DISALLOWED_CONTROL_CHARACTER_PATTERN.matcher(normalized).find()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		normalized = normalized.strip();
		if (normalized.isEmpty()
			|| normalized.length() > chatMessageLimitProperties.getMaxContentLength()) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return normalized;
	}

	/** 발신자 표시 요약을 조회하며, 접근 검증을 통과했는데도 사용자가 없는 예기치 않은 상태만 roomId로 기록한다. */
	private UserQuery.UserSummary requireSenderSummary(long roomId, long currentUserId) {
		return userQuery
			.findUserSummaryById(currentUserId)
			.orElseThrow(() -> {
				log.atError().addKeyValue("event", "chat_message_sender_nickname_missing")
					.addKeyValue("roomId", roomId).log("chat message sender nickname missing");
				return new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
			});
	}
}
