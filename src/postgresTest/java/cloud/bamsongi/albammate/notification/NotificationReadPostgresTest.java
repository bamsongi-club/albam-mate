package cloud.bamsongi.albammate.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.notification.dto.NotificationBulkReadResponse;
import cloud.bamsongi.albammate.notification.dto.NotificationListItem;
import cloud.bamsongi.albammate.notification.service.command.NotificationReadCommandService;

/** PostgreSQL clock_timestamp와 읽음 SQL 문장 스냅샷의 회귀 경계다. */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class NotificationReadPostgresTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("albam_mate_notification_read_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private DataSource dataSource;
	@Autowired
	private NotificationReadCommandService notificationReadCommandService;

	@Test
	void 단건_읽음은_PostgreSQL_시각을_최초_readAt에_기록하고_반복해도_보존한다() {
		long ownerId = user("single-owner@example.com");
		long roomId = room(ownerId, "이전 제목");
		long notificationId = notification(ownerId, roomId, null, "transaction_timestamp() + interval '1 day'");
		jdbcTemplate.update("update rooms set title = '현재 제목' where id = ?", roomId);

		NotificationListItem first = notificationReadCommandService.readOne(ownerId, notificationId);
		NotificationListItem repeated = notificationReadCommandService.readOne(ownerId, notificationId);

		assertEquals("현재 제목", first.roomTitle());
		assertEquals(first.readAt(), repeated.readAt());
		assertEquals(first.readAt(), notificationReadAt(notificationId));
		assertTrue(first.readAt().isAfter(Instant.parse("2020-01-01T00:00:00Z")));
	}

	@Test
	void 미존재_타인_만료_단건_읽음은_같은_NOT_FOUND이고_상태를_바꾸지_않는다() {
		long ownerId = user("hidden-owner@example.com");
		long otherUserId = user("hidden-other@example.com");
		long roomId = room(ownerId, "은닉 방");
		long foreignNotificationId = notification(otherUserId, roomId, null,
			"transaction_timestamp() + interval '1 day'");
		long expiredNotificationId = notification(ownerId, roomId, null,
			"transaction_timestamp() - interval '1 second'");

		assertNotFound(() -> notificationReadCommandService.readOne(ownerId, Long.MAX_VALUE));
		assertNotFound(() -> notificationReadCommandService.readOne(ownerId, foreignNotificationId));
		assertNotFound(() -> notificationReadCommandService.readOne(ownerId, expiredNotificationId));

		assertNull(notificationReadAt(foreignNotificationId));
		assertNull(notificationReadAt(expiredNotificationId));
	}

	@Test
	void 일괄_읽음은_경계_이하_본인_unread만_같은_시각으로_바꾸고_반복에서_수렴한다() {
		long ownerId = user("bulk-owner@example.com");
		long otherUserId = user("bulk-other@example.com");
		long roomId = room(ownerId, "일괄 방");
		long firstUnreadId = notification(ownerId, roomId, null, "transaction_timestamp() + interval '1 day'");
		long existingReadId = notification(ownerId, roomId, "transaction_timestamp()",
			"transaction_timestamp() + interval '1 day'");
		long secondUnreadId = notification(ownerId, roomId, null, "transaction_timestamp() + interval '1 day'");
		long expiredNotificationId = notification(ownerId, roomId, null,
			"transaction_timestamp() - interval '1 second'");
		long foreignNotificationId = notification(otherUserId, roomId, null,
			"transaction_timestamp() + interval '1 day'");
		Instant existingReadAt = notificationReadAt(existingReadId);

		NotificationBulkReadResponse first = notificationReadCommandService.readAll(ownerId);
		NotificationBulkReadResponse repeated = notificationReadCommandService.readAll(ownerId);

		assertEquals(2, first.updatedCount());
		assertEquals(secondUnreadId, first.boundaryNotificationId());
		assertEquals(first.readAt(), notificationReadAt(firstUnreadId));
		assertEquals(first.readAt(), notificationReadAt(secondUnreadId));
		assertEquals(existingReadAt, notificationReadAt(existingReadId));
		assertNull(notificationReadAt(expiredNotificationId));
		assertNull(notificationReadAt(foreignNotificationId));
		assertEquals(0, repeated.updatedCount());
		assertEquals(secondUnreadId, repeated.boundaryNotificationId());
		assertTrue(!repeated.readAt().isBefore(first.readAt()));
	}

	@Test
	void 일괄_읽음은_빈_집합에서_null_경계와_non_null_readAt을_반환한다() {
		long ownerId = user("empty-owner@example.com");

		NotificationBulkReadResponse response = notificationReadCommandService.readAll(ownerId);

		assertEquals(0, response.updatedCount());
		assertNull(response.boundaryNotificationId());
		assertTrue(response.readAt() != null);
	}

	@Test
	void 문장_스냅샷_뒤_커밋된_더_낮은_ID는_일괄_읽음_대상이_아니다() throws Exception {
		long ownerId = user("snapshot-owner@example.com");
		long roomId = room(ownerId, "스냅샷 방");
		installOneSecondUpdateDelay();
		try {
			try (Connection lateInsertConnection = dataSource.getConnection()) {
				lateInsertConnection.setAutoCommit(false);
				long lateNotificationId = insertNotification(lateInsertConnection, ownerId, roomId);
				long visibleNotificationId = notification(
					ownerId, roomId, null, "transaction_timestamp() + interval '1 day'");
				try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
					Future<NotificationBulkReadResponse> bulkRead = executor.submit(
						() -> notificationReadCommandService.readAll(ownerId));
					Thread.sleep(200);
					lateInsertConnection.commit();

					NotificationBulkReadResponse response = bulkRead.get();
					assertEquals(visibleNotificationId, response.boundaryNotificationId());
					assertEquals(response.readAt(), notificationReadAt(visibleNotificationId));
					assertNull(notificationReadAt(lateNotificationId));
				}
			}
		} finally {
			removeUpdateDelay();
		}
	}

	private void assertNotFound(org.junit.jupiter.api.function.Executable action) {
		BusinessException exception = assertThrows(BusinessException.class, action);
		assertEquals(ErrorCode.NOTIFICATION_NOT_FOUND, exception.getErrorCode());
	}

	private Instant notificationReadAt(long notificationId) {
		OffsetDateTime value = jdbcTemplate.queryForObject(
			"select read_at from notifications where id = ?", OffsetDateTime.class, notificationId);
		return value == null ? null : value.toInstant();
	}

	private long user(String email) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', '닉네임', transaction_timestamp(), transaction_timestamp()) returning id",
			Long.class, email);
	}

	private long room(long userId, String title) {
		return jdbcTemplate.queryForObject(
			"insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, active_participant_count, start_at, place, status, created_at, updated_at) values (?, 'PERSON_FOCUSED', ?, 'ALL_LEVELS', false, 2, 0, transaction_timestamp() + interval '1 day', '서울', 'RECRUITING', transaction_timestamp(), transaction_timestamp()) returning id",
			Long.class, userId, title);
	}

	private long notification(long userId, long roomId, String readAt, String expiresAt) {
		Long sourceEventId = jdbcTemplate.queryForObject("select nextval('notification_outbox_events_id_seq')",
			Long.class);
		return jdbcTemplate.queryForObject(
			"insert into notifications (source_event_id, recipient_user_id, room_id, type, read_at, created_at, recorded_at, expires_at) values (?, ?, ?, 'PARTICIPANT_JOINED', "
				+ readAt + ", (" + expiresAt + ") - interval '90 days', transaction_timestamp(), " + expiresAt
				+ ") returning id",
			Long.class, sourceEventId, userId, roomId);
	}

	private long insertNotification(Connection connection, long userId, long roomId) throws Exception {
		try (PreparedStatement statement = connection.prepareStatement(
			"insert into notifications (source_event_id, recipient_user_id, room_id, type, read_at, created_at, recorded_at, expires_at) "
				+ "values (nextval('notification_outbox_events_id_seq'), ?, ?, 'PARTICIPANT_JOINED', null, "
				+ "transaction_timestamp() - interval '89 days', transaction_timestamp(), transaction_timestamp() + interval '1 day') returning id")) {
			statement.setLong(1, userId);
			statement.setLong(2, roomId);
			try (ResultSet resultSet = statement.executeQuery()) {
				resultSet.next();
				return resultSet.getLong(1);
			}
		}
	}

	private void installOneSecondUpdateDelay() {
		jdbcTemplate.execute("""
			create function notification_read_update_delay() returns trigger language plpgsql as $$
			begin
				perform pg_sleep(1);
				return new;
			end;
			$$
			""");
		jdbcTemplate.execute("create trigger notification_read_update_delay before update of read_at on notifications "
			+ "for each row when (old.read_at is null and new.read_at is not null) "
			+ "execute function notification_read_update_delay()");
	}

	private void removeUpdateDelay() {
		jdbcTemplate.execute("drop trigger if exists notification_read_update_delay on notifications");
		jdbcTemplate.execute("drop function if exists notification_read_update_delay()");
	}
}
