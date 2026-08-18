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
import lombok.extern.slf4j.Slf4j;

/** ChatAccessGuard 접근 판정 안에서 방별 채팅 이력을 읽기 전용으로 조회한다. */
@Slf4j
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
			() -> queryHistory(currentUserId, roomId, beforeMessageId, size));
	}

	private ChatMessagePageResponse queryHistory(long currentUserId, long roomId, Long beforeMessageId, int size) {
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
		Map<Long, UserQuery.UserSummary> summariesById = userQuery.findUserSummariesByIds(
			page.stream().map(ChatMessage::getSenderUserId).collect(Collectors.toSet()));
		List<ChatMessageResponse> messages = page.stream()
			.map(message -> {
				UserQuery.UserSummary sender = senderSummaryOf(summariesById, message, roomId);
				return ChatMessageResponse.from(
					message,
					roomId,
					sender.nickname(),
					sender.profileImageUrl(),
					message.getSenderUserId() == currentUserId);
			})
			.toList();
		Long nextBeforeMessageId = hasNext ? page.get(page.size() - 1).getId() : null;
		return new ChatMessagePageResponse(messages, nextBeforeMessageId, hasNext);
	}

	/** 발신자 요약을 찾지 못하는 예기치 않은 상태만 roomId로 기록하고, 발신자 내부 사용자 ID는 남기지 않는다. */
	private UserQuery.UserSummary senderSummaryOf(
		Map<Long, UserQuery.UserSummary> summariesById, ChatMessage message, long roomId) {
		UserQuery.UserSummary sender = summariesById.get(message.getSenderUserId());
		if (sender == null) {
			log.atError().addKeyValue("event", "chat_message_sender_nickname_missing")
				.addKeyValue("roomId", roomId).log("chat message sender nickname missing");
			throw new BusinessException(ErrorCode.INTERNAL_SERVER_ERROR);
		}
		return sender;
	}
}
