package cloud.bamsongi.albammate.notification.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import cloud.bamsongi.albammate.notification.dto.NotificationBulkReadResponse;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.enums.NotificationType;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/** PostgreSQL 시계와 문장 스냅샷을 사용하는 알림 읽음 SQL adapter다. */
@Repository
@RequiredArgsConstructor
public class NotificationReadRepository {

	@NonNull private final JdbcTemplate jdbcTemplate;

	/**
	 * 대상 행을 잠가 반복·동시 요청이 같은 최신 readAt을 확인하게 한다. readAt이 없는 경우에만 operationTime으로
	 * 갱신하고, coalesce로 이번 요청의 갱신 여부와 관계없이 보존된 최초 readAt을 반환한다.
	 */
	public Optional<NotificationListItem> markReadAndFindCurrentItem(long recipientUserId, long notificationId) {
		return jdbcTemplate.query(
			"""
				with operation as materialized (
					select clock_timestamp() as operation_time
				),
				locked_notification as materialized (
					select notification.id, notification.type, notification.room_id, room.title, notification.read_at,
						notification.created_at, operation.operation_time
					from notifications notification
					join rooms room on room.id = notification.room_id
					cross join operation
					where notification.id = ?
					  and notification.recipient_user_id = ?
					  and notification.expires_at > operation.operation_time
					for update of notification
				),
				updated as (
					update notifications notification
					set read_at = locked_notification.operation_time
					from locked_notification
					where notification.id = locked_notification.id
					  and notification.read_at is null
					returning notification.id, notification.read_at
				)
				select locked_notification.id, locked_notification.type, locked_notification.room_id, locked_notification.title,
					coalesce(updated.read_at, locked_notification.read_at) as read_at, locked_notification.created_at
				from locked_notification
				left join updated on updated.id = locked_notification.id
				""",
			this::mapNotificationListItem,
			notificationId,
			recipientUserId).stream().findFirst();
	}

	public NotificationBulkReadResponse markAllUnread(long recipientUserId) {
		return jdbcTemplate.queryForObject(
			"""
				with operation as materialized (
					select clock_timestamp() as operation_time
				),
				boundary as materialized (
					select max(id) as notification_id
					from notifications
					cross join operation
					where recipient_user_id = ?
					  and expires_at > operation.operation_time
				),
				updated as (
					update notifications notification
					set read_at = operation.operation_time
					from boundary
					cross join operation
					where notification.recipient_user_id = ?
					  and notification.expires_at > operation.operation_time
					  and notification.read_at is null
					  and notification.id <= boundary.notification_id
					returning notification.id
				)
				select count(updated.id) as updated_count,
					boundary.notification_id as boundary_notification_id,
					operation.operation_time as read_at
				from boundary
				cross join operation
				left join updated on true
				group by boundary.notification_id, operation.operation_time
				""",
			(resultSet, rowNumber) -> new NotificationBulkReadResponse(
				resultSet.getLong("updated_count"),
				longOrNull(resultSet, "boundary_notification_id"),
				instant(resultSet, "read_at")),
			recipientUserId,
			recipientUserId);
	}

	private NotificationListItem mapNotificationListItem(ResultSet resultSet, int rowNumber) throws SQLException {
		return new NotificationListItem(
			resultSet.getLong("id"),
			NotificationType.valueOf(resultSet.getString("type")),
			resultSet.getLong("room_id"),
			resultSet.getString("title"),
			instant(resultSet, "read_at"),
			instant(resultSet, "created_at"));
	}

	private Long longOrNull(ResultSet resultSet, String columnLabel) throws SQLException {
		long value = resultSet.getLong(columnLabel);
		return resultSet.wasNull() ? null : value;
	}

	private Instant instant(ResultSet resultSet, String columnLabel) throws SQLException {
		return resultSet.getObject(columnLabel, OffsetDateTime.class).toInstant();
	}
}
