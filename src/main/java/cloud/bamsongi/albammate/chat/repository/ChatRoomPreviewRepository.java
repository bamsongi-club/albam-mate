package cloud.bamsongi.albammate.chat.repository;

import java.util.List;
import java.util.Set;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import cloud.bamsongi.albammate.chat.entity.ChatRoom;

/**
 * 채팅 목록의 마지막 메시지·미읽음 개수를 방 개수와 무관하게 상수 회수의 배치 질의로 계산한다.
 *
 * <p>{@code chat_rooms.room_id}(ROOM 공개 ID)로 직접 조인해, room 모듈에는 chat 내부 PK를 노출하지 않는다.
 */
public interface ChatRoomPreviewRepository extends Repository<ChatRoom, Long> {

	@Query(value = """
		SELECT cr.room_id AS roomId, cm.content AS content,
		       CAST(EXTRACT(EPOCH FROM cm.created_at) * 1000 AS BIGINT) AS createdAtEpochMilli
		FROM chat_rooms cr
		JOIN chat_messages cm ON cm.chat_room_id = cr.id
		WHERE cr.room_id IN (:roomIds)
		  AND cm.id = (SELECT MAX(cm2.id) FROM chat_messages cm2 WHERE cm2.chat_room_id = cr.id)
		""", nativeQuery = true)
	List<ChatRoomLastMessageRow> findLastMessages(@Param("roomIds")
	Set<Long> roomIds);

	/**
	 * 본인이 보낸 메시지는 제외한다({@code cm.sender_user_id <> :userId}). CHAT-06이 {@code message_type}·
	 * {@code subject_user_id} 컬럼을 추가하면, 본인이 대상인 SYSTEM 메시지를 제외하는 조건을 이 AND 절에
	 * 그대로 추가한다.
	 */
	@Query(value = """
		SELECT cr.room_id AS roomId, COUNT(*) AS unreadCount
		FROM chat_rooms cr
		JOIN chat_messages cm ON cm.chat_room_id = cr.id
		LEFT JOIN chat_room_read_states rs ON rs.chat_room_id = cr.id AND rs.user_id = :userId
		WHERE cr.room_id IN (:roomIds)
		  AND cm.id > COALESCE(rs.last_read_message_id, 0)
		  AND cm.sender_user_id <> :userId
		GROUP BY cr.room_id
		""", nativeQuery = true)
	List<ChatRoomUnreadCountRow> findUnreadCounts(@Param("userId")
	long userId, @Param("roomIds")
	Set<Long> roomIds);
}
