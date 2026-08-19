package cloud.bamsongi.albammate.chat.service;

import java.time.Clock;
import java.time.Instant;

import org.springframework.dao.DataIntegrityViolationException;
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
		upsertCursorWithRetry(currentUserId, chatRoom.getId(), upToMessageId);
		ChatRoomReadState state = chatRoomReadStateRepository
			.findById(new ChatRoomReadStateId(currentUserId, chatRoom.getId()))
			.orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR));
		return new ChatRoomReadStateResponse(roomId, state.getLastReadMessageId(), state.getUpdatedAt());
	}

	/**
	 * 같은 (user_id, chat_room_id)에 행이 아직 없는 상태로 두 요청이 동시에 최초 삽입을 시도하면, 독립
	 * 트랜잭션(REQUIRES_NEW)으로 실행되는 native MERGE 중 한쪽이 primary key 위반으로 실패할 수 있다
	 * (ADR-0079). 이 좁은 경합에서만 한 번 재시도하면 상대 트랜잭션이 이미 커밋한 행을 MATCHED로 만나
	 * GREATEST UPSERT가 안전하게 성공한다. 재시도도 실패하면 예외를 그대로 전파한다.
	 */
	private void upsertCursorWithRetry(long userId, long chatRoomId, long upToMessageId) {
		try {
			chatRoomReadStateRepository.advanceCursor(userId, chatRoomId, upToMessageId, Instant.now(clock));
		} catch (DataIntegrityViolationException firstAttemptFailure) {
			chatRoomReadStateRepository.advanceCursor(userId, chatRoomId, upToMessageId, Instant.now(clock));
		}
	}
}
