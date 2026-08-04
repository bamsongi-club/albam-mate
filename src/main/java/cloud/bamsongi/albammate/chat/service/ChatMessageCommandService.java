package cloud.bamsongi.albammate.chat.service;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import lombok.RequiredArgsConstructor;

/** ROOM 접근 잠금 안에서 메시지를 저장하고, 신규 저장만 커밋 뒤 전달 이벤트로 등록한다. */
@Service
@RequiredArgsConstructor
public class ChatMessageCommandService {

	private static final int MAX_CLIENT_MESSAGE_ID_LENGTH = 100;
	private static final int MAX_CONTENT_LENGTH = 500;

	private final ChatAccessGuard chatAccessGuard;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final UserQuery userQuery;
	private final ChatMessageRateLimiter chatMessageRateLimiter;
	private final ApplicationEventPublisher eventPublisher;
	private final Clock clock;

	@Transactional
	public ChatMessageSendResult send(long currentUserId, long roomId, ChatMessageSendRequest request) {
		Objects.requireNonNull(request, "request");
		return chatAccessGuard.executeWithAccess(
			currentUserId,
			roomId,
			() -> appendMessage(currentUserId, roomId, request));
	}

	private ChatMessageSendResult appendMessage(long currentUserId, long roomId, ChatMessageSendRequest request) {
		ChatRoom chatRoom = chatRoomRepository
			.findByRoomIdForMessageAppend(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		String clientMessageId = validateClientMessageId(request.clientMessageId());
		String content = normalizeContent(request.content());
		String nickname = userQuery
			.findNicknameById(currentUserId)
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));

		return chatMessageRepository
			.findByChatRoomIdAndSenderUserIdAndClientMessageId(chatRoom.getId(), currentUserId, clientMessageId)
			.map(existing -> existingMessage(existing, roomId, content, nickname))
			.orElseGet(() -> saveNewMessage(chatRoom, currentUserId, roomId, clientMessageId, content, nickname));
	}

	private ChatMessageSendResult existingMessage(
		ChatMessage existing, long roomId, String content, String nickname) {
		if (!existing.getContent().equals(content)) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return new ChatMessageSendResult(ChatMessageResponse.from(existing, roomId, nickname), false);
	}

	private ChatMessageSendResult saveNewMessage(
		ChatRoom chatRoom,
		long currentUserId,
		long roomId,
		String clientMessageId,
		String content,
		String nickname) {
		ChatMessageRateLimiter.RateLimitReservation reservation = chatMessageRateLimiter.reserve(currentUserId, roomId);
		Runnable releaseOnce = releaseOnce(reservation);
		try {
			ChatMessage saved = chatMessageRepository.save(
				ChatMessage.create(chatRoom.getId(), currentUserId, clientMessageId, content, Instant.now(clock)));
			registerReservationReleaseOnRollback(releaseOnce);
			eventPublisher.publishEvent(MessageCommitted.messageCreated(roomId, saved.getId()));
			return new ChatMessageSendResult(ChatMessageResponse.from(saved, roomId, nickname), true);
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
			|| clientMessageId.length() > MAX_CLIENT_MESSAGE_ID_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return clientMessageId;
	}

	private String normalizeContent(String content) {
		if (content == null) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		String normalized = content.strip();
		if (normalized.isEmpty() || normalized.length() > MAX_CONTENT_LENGTH) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		return normalized;
	}
}
