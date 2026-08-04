package cloud.bamsongi.albammate.chat.retention;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 보관 삭제에 필요한 제한 조회와 조건부 갱신만 담당하는 JDBC 저장소다. */
@Repository
class ChatMessageRetentionStore {

	private final JdbcTemplate jdbcTemplate;

	ChatMessageRetentionStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
	}

	List<DueChatRoom> findDueChatRooms(Instant referenceTime, int limit) {
		return jdbcTemplate.query("""
			select id, purge_after
			from chat_rooms
			where purge_after <= ?
			  and messages_purged_at is null
			order by purge_after asc, id asc
			limit ?
			""", (resultSet, rowNumber) -> new DueChatRoom(
			resultSet.getLong("id"), resultSet.getTimestamp("purge_after").toInstant()),
			Timestamp.from(referenceTime), limit);
	}

	List<Long> findNextMessageIds(long chatRoomId, int limit) {
		return jdbcTemplate.query("""
			select id
			from chat_messages
			where chat_room_id = ?
			order by id asc
			limit ?
			""", (resultSet, rowNumber) -> resultSet.getLong("id"), chatRoomId, limit);
	}

	int deleteMessageChunk(long chatRoomId, List<Long> messageIds) {
		if (messageIds.isEmpty()) {
			return 0;
		}
		String placeholders = String.join(", ", java.util.Collections.nCopies(messageIds.size(), "?"));
		List<Object> parameters = new ArrayList<>();
		parameters.add(chatRoomId);
		parameters.addAll(messageIds);
		return jdbcTemplate.update(
			"delete from chat_messages where chat_room_id = ? and id in (" + placeholders + ")",
			parameters.toArray());
	}

	boolean markMessagesPurgedIfEmpty(long chatRoomId, Instant completedAt) {
		int updated = jdbcTemplate.update("""
			update chat_rooms
			set messages_purged_at = ?, updated_at = ?
			where id = ?
			  and messages_purged_at is null
			  and not exists (
			      select 1
			      from chat_messages
			      where chat_messages.chat_room_id = chat_rooms.id
			  )
			""", Timestamp.from(completedAt), Timestamp.from(completedAt), chatRoomId);
		return updated == 1;
	}

	record DueChatRoom(long chatRoomId, Instant purgeAfter) {
	}
}
