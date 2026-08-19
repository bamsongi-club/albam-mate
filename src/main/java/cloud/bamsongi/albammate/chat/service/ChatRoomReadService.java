package cloud.bamsongi.albammate.chat.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.dto.ChatRoomReadStateResponse;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.entity.ChatRoomReadState;
import cloud.bamsongi.albammate.chat.entity.ChatRoomReadStateId;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomReadStateRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import lombok.RequiredArgsConstructor;

/** ROOM 접근 판정 안에서 읽음 커서를 GREATEST로만 전진시키는 멱등 명령이다. */
@Service
@RequiredArgsConstructor
public class ChatRoomReadService {

	private final ChatAccessGuard chatAccessGuard;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final ChatRoomReadStateRepository chatRoomReadStateRepository;
	private final Clock clock;

	@Transactional
	public ChatRoomReadStateResponse markRead(long currentUserId, long roomId, Long upToMessageId) {
		return chatAccessGuard.executeWithAccess(
			currentUserId,
			roomId,
			() -> advanceCursor(currentUserId, roomId, upToMessageId));
	}

	private ChatRoomReadStateResponse advanceCursor(long currentUserId, long roomId, Long upToMessageId) {
		if (upToMessageId == null || upToMessageId < 1) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		ChatRoom chatRoom = chatRoomRepository
			.findByRoomId(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		if (!chatMessageRepository.existsByIdAndChatRoomId(upToMessageId, chatRoom.getId())) {
			throw new BusinessException(ErrorCode.VALIDATION_ERROR);
		}
		chatRoomReadStateRepository.advanceCursor(currentUserId, chatRoom.getId(), upToMessageId, Instant.now(clock));
		ChatRoomReadState state = chatRoomReadStateRepository
			.findById(new ChatRoomReadStateId(currentUserId, chatRoom.getId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
		return new ChatRoomReadStateResponse(roomId, state.getLastReadMessageId(), state.getUpdatedAt());
	}
}
