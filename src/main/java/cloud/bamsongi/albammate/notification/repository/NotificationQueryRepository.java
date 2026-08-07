package cloud.bamsongi.albammate.notification.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.enums.NotificationType;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * 알림 조회에 필요한 최소 컬럼과 현재 ROOMS.title만 SQL로 투영한다.
 * PostgreSQL의 CURRENT_TIMESTAMP는 transaction_timestamp()와 같아 한 읽기 트랜잭션의 content와 count가
 * 동일한 만료 기준을 사용한다. 문장 실행 시각이 필요한 읽음·relay·cleanup은 clock_timestamp()를 사용한다.
 */
@Repository
@RequiredArgsConstructor
public class NotificationQueryRepository {

	@NonNull private final JdbcTemplate jdbcTemplate;

	public List<NotificationListItem> findPage(long recipientUserId, int page, int size) {
		return jdbcTemplate.query(
			"""
				select n.id, n.type, n.room_id, r.title, n.read_at, n.created_at
				from notifications n
				join rooms r on r.id = n.room_id
				where n.recipient_user_id = ?
				  and n.expires_at > CURRENT_TIMESTAMP
				order by n.created_at desc, n.id desc
				limit ? offset ?
				""",
			this::mapListItem,
			recipientUserId,
			size,
			(long)page * size);
	}

	public long countUnexpired(long recipientUserId) {
		Long count = jdbcTemplate.queryForObject(
			"""
				select count(*)
				from notifications n
				where n.recipient_user_id = ?
				  and n.expires_at > CURRENT_TIMESTAMP
				""",
			Long.class,
			recipientUserId);
		return count == null ? 0 : count;
	}

	public long countUnreadUnexpired(long recipientUserId) {
		Long count = jdbcTemplate.queryForObject(
			"""
				select count(*)
				from notifications n
				where n.recipient_user_id = ?
				  and n.read_at is null
				  and n.expires_at > CURRENT_TIMESTAMP
				""",
			Long.class,
			recipientUserId);
		return count == null ? 0 : count;
	}

	private NotificationListItem mapListItem(ResultSet resultSet, int rowNumber) throws SQLException {
		return new NotificationListItem(
			resultSet.getLong("id"),
			NotificationType.valueOf(resultSet.getString("type")),
			resultSet.getLong("room_id"),
			resultSet.getString("title"),
			instantOrNull(resultSet, "read_at"),
			instantOrNull(resultSet, "created_at"));
	}

	private Instant instantOrNull(ResultSet resultSet, String columnLabel) throws SQLException {
		OffsetDateTime value = resultSet.getObject(columnLabel, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}
}
