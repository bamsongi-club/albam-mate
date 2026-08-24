package cloud.bamsongi.albammate.chat.retention;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 보관 삭제에 필요한 제한 조회와 조건부 갱신만 담당하는 JDBC 저장소다. */
@Repository
class ChatMessageRetentionStore {

	private final JdbcTemplate jdbcTemplate;

	/**
	 * 보관 삭제 전용 JdbcTemplate에 질의 시간 상한을 걸어, 느린 조회·삭제·완료 질의가 잠금 임대를
	 * 넘기지 않게 한다. DataSource가 같으므로 진행 중인 트랜잭션의 연결을 그대로 사용한다.
	 */
	ChatMessageRetentionStore(DataSource dataSource, ChatMessageRetentionProperties properties) {
		Objects.requireNonNull(dataSource, "dataSource");
		Objects.requireNonNull(properties, "properties");
		JdbcTemplate template = new JdbcTemplate(dataSource);
		template.setQueryTimeout(Math.toIntExact(properties.getQueryTimeout().toSeconds()));
		this.jdbcTemplate = template;
	}

	List<DueChatRoom> findDueChatRooms(
		Instant referenceTime, DueChatRoomCursor cursor, int limit) {
		String afterCursor = cursor == null
			? ""
			: """
				and (
				    purge_after > ?
				    or (purge_after = ? and id > ?)
				)
				""";
		String query = """
			select id, purge_after
			from chat_rooms
			where purge_after <= ?
			  and messages_purged_at is null
			""" + afterCursor + """
			order by purge_after asc, id asc
			limit ?
			""";
		List<Object> parameters = new ArrayList<>();
		parameters.add(Timestamp.from(referenceTime));
		if (cursor != null) {
			parameters.add(Timestamp.from(cursor.purgeAfter()));
			parameters.add(Timestamp.from(cursor.purgeAfter()));
			parameters.add(cursor.chatRoomId());
		}
		parameters.add(limit);
		return jdbcTemplate.query(query, (resultSet, rowNumber) -> new DueChatRoom(
			resultSet.getLong("id"), resultSet.getTimestamp("purge_after").toInstant()),
			parameters.toArray());
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

	record DueChatRoomCursor(Instant purgeAfter, long chatRoomId) {

		static DueChatRoomCursor after(DueChatRoom dueChatRoom) {
			return new DueChatRoomCursor(dueChatRoom.purgeAfter(), dueChatRoom.chatRoomId());
		}
	}
}
