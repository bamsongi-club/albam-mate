package cloud.bamsongi.albammate.chat.service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cloud.bamsongi.albammate.chat.contract.ChatRoomPreviewQuery;
import cloud.bamsongi.albammate.chat.repository.ChatRoomLastMessageRow;
import cloud.bamsongi.albammate.chat.repository.ChatRoomPreviewRepository;
import cloud.bamsongi.albammate.chat.repository.ChatRoomUnreadCountRow;
import lombok.RequiredArgsConstructor;

/**
 * 채팅 목록의 마지막 메시지·미읍음 개수를 요청한 roomId 집합 전체에 대해 상수 회수(2회)의 배치 질의로 계산한다.
 * 저장된 counter가 아니라 조회 시점 파생 계산이므로, 같은 메시지 상태에서 반복 호출해도 항상 같은 값을 반환한다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomPreviewQueryService implements ChatRoomPreviewQuery {

	private final ChatRoomPreviewRepository chatRoomPreviewRepository;

	@Override
	@Transactional(readOnly = true)
	public Map<Long, ChatRoomPreview> findPreviews(long currentUserId, Set<Long> roomIds) {
		if (roomIds == null || roomIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, ChatRoomLastMessageRow> lastMessagesByRoomId = indexLastMessages(
			chatRoomPreviewRepository.findLastMessages(roomIds));
		Map<Long, Long> unreadCountsByRoomId = indexUnreadCounts(
			chatRoomPreviewRepository.findUnreadCounts(currentUserId, roomIds));

		Map<Long, ChatRoomPreview> previews = new HashMap<>();
		for (Long roomId : roomIds) {
			ChatRoomLastMessageRow lastMessage = lastMessagesByRoomId.get(roomId);
			if (lastMessage == null) {
				continue;
			}
			previews.put(
				roomId,
				new ChatRoomPreview(
					lastMessage.getContent(),
					Instant.ofEpochMilli(lastMessage.getCreatedAtEpochMilli()),
					unreadCountsByRoomId.getOrDefault(roomId, 0L).intValue()));
		}
		return previews;
	}

	private Map<Long, ChatRoomLastMessageRow> indexLastMessages(List<ChatRoomLastMessageRow> rows) {
		Map<Long, ChatRoomLastMessageRow> index = new HashMap<>();
		rows.forEach(row -> index.put(row.getRoomId(), row));
		return index;
	}

	private Map<Long, Long> indexUnreadCounts(List<ChatRoomUnreadCountRow> rows) {
		Map<Long, Long> index = new HashMap<>();
		rows.forEach(row -> index.put(row.getRoomId(), row.getUnreadCount()));
		return index;
	}
}
