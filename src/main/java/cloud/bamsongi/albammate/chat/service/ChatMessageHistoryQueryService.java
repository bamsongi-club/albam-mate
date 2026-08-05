package cloud.bamsongi.albammate.chat.service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.dto.ChatMessagePageResponse;
import cloud.bamsongi.albammate.chat.dto.ChatMessageResponse;
import cloud.bamsongi.albammate.chat.entity.ChatMessage;
import cloud.bamsongi.albammate.chat.entity.ChatRoom;
import cloud.bamsongi.albammate.chat.repository.ChatMessageRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomRepository;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.user.contract.UserQuery;
import lombok.RequiredArgsConstructor;

/** ChatAccessGuard 접근 판정 안에서 방별 채팅 이력을 읽기 전용으로 조회한다. */
@Service
@RequiredArgsConstructor
public class ChatMessageHistoryQueryService {

	private final ChatAccessGuard chatAccessGuard;
	private final ChatRoomRepository chatRoomRepository;
	private final ChatMessageRepository chatMessageRepository;
	private final UserQuery userQuery;

	@Transactional
	public ChatMessagePageResponse history(long currentUserId, long roomId, Long beforeMessageId, int size) {
		return chatAccessGuard.executeWithAccess(
			currentUserId,
			roomId,
			() -> queryHistory(roomId, beforeMessageId, size));
	}

	private ChatMessagePageResponse queryHistory(long roomId, Long beforeMessageId, int size) {
		ChatRoom chatRoom = chatRoomRepository
			.findByRoomId(roomId)
			.orElseThrow(() -> new BusinessException(ErrorCode.ROOM_NOT_FOUND));
		Pageable pageable = PageRequest.of(0, size + 1);
		List<ChatMessage> fetched = beforeMessageId == null
			? chatMessageRepository.findByChatRoomIdOrderByIdDesc(chatRoom.getId(), pageable)
			: chatMessageRepository
				.findByChatRoomIdAndIdLessThanOrderByIdDesc(chatRoom.getId(), beforeMessageId, pageable);

		boolean hasNext = fetched.size() > size;
		List<ChatMessage> page = hasNext ? fetched.subList(0, size) : fetched;
		Map<Long, String> nicknamesById = userQuery.findNicknamesByIds(
			page.stream().map(ChatMessage::getSenderUserId).collect(Collectors.toSet()));
		List<ChatMessageResponse> messages = page.stream()
			.map(message -> ChatMessageResponse.from(message, roomId, nicknameOf(nicknamesById, message)))
			.toList();
		Long nextBeforeMessageId = hasNext ? page.get(page.size() - 1).getId() : null;
		return new ChatMessagePageResponse(messages, nextBeforeMessageId, hasNext);
	}

	private String nicknameOf(Map<Long, String> nicknamesById, ChatMessage message) {
		String nickname = nicknamesById.get(message.getSenderUserId());
		if (nickname == null) {
			throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		return nickname;
	}
}
