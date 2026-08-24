package cloud.bamsongi.albammate.chat.service;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
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
 *
 * <p>마지막 메시지 배치 조회는 {@link ChatRoomLastMessageRow}에 적은 근거에 따라 Spring Data native
 * projection 대신 {@code NamedParameterJdbcTemplate}으로 직접 매핑해, 다이얼렉트별 타입 변환 실패와
 * epoch milliseconds 변환의 하위 밀리초 정밀도 손실을 함께 피한다.
 */
@Service
@RequiredArgsConstructor
public class ChatRoomPreviewQueryService implements ChatRoomPreviewQuery {

	private static final String FIND_LAST_MESSAGES_SQL = """
		SELECT cr.room_id AS room_id, cm.content AS content, cm.created_at AS created_at
		FROM chat_rooms cr
		JOIN chat_messages cm ON cm.chat_room_id = cr.id
		WHERE cr.room_id IN (:roomIds)
		  AND cm.id = (SELECT MAX(cm2.id) FROM chat_messages cm2 WHERE cm2.chat_room_id = cr.id)
		""";

	private final ChatRoomPreviewRepository chatRoomPreviewRepository;
	private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

	@Override
	@Transactional(readOnly = true)
	public Map<Long, ChatRoomPreview> findPreviews(long currentUserId, Set<Long> roomIds) {
		if (roomIds == null || roomIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, ChatRoomLastMessageRow> lastMessagesByRoomId = indexLastMessages(findLastMessages(roomIds));
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
					lastMessage.content(),
					lastMessage.createdAt(),
					unreadCountsByRoomId.getOrDefault(roomId, 0L).intValue()));
		}
		return previews;
	}

	private List<ChatRoomLastMessageRow> findLastMessages(Set<Long> roomIds) {
		return namedParameterJdbcTemplate.query(
			FIND_LAST_MESSAGES_SQL,
			new MapSqlParameterSource("roomIds", roomIds),
			this::mapLastMessageRow);
	}

	private ChatRoomLastMessageRow mapLastMessageRow(ResultSet resultSet, int rowNumber) throws SQLException {
		return new ChatRoomLastMessageRow(
			resultSet.getLong("room_id"),
			resultSet.getString("content"),
			resultSet.getObject("created_at", OffsetDateTime.class).toInstant());
	}

	private Map<Long, ChatRoomLastMessageRow> indexLastMessages(List<ChatRoomLastMessageRow> rows) {
		Map<Long, ChatRoomLastMessageRow> index = new HashMap<>();
		rows.forEach(row -> index.put(row.roomId(), row));
		return index;
	}

	private Map<Long, Long> indexUnreadCounts(List<ChatRoomUnreadCountRow> rows) {
		Map<Long, Long> index = new HashMap<>();
		rows.forEach(row -> index.put(row.getRoomId(), row.getUnreadCount()));
		return index;
	}
}
