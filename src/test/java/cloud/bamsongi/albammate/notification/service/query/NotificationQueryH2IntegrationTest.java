package cloud.bamsongi.albammate.notification.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** H2 PostgreSQL 호환 모드에서 알림 조회 SQL과 본인 범위를 확인한다. */
@SpringBootTest
@Transactional
class NotificationQueryH2IntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private NotificationListQueryService notificationListQueryService;
	@Autowired
	private UnreadNotificationCountQueryService unreadNotificationCountQueryService;

	@Test
	void 본인_미만료_알림만_고정_정렬과_현재_방_제목으로_조회하고_미확인_수를_센다() {
		long userId = user("notification-query-h2@example.com");
		long otherUserId = user("notification-query-h2-other@example.com");
		long roomId = room(userId, "변경된 방 제목");
		insertNotification(userId, roomId, "PARTICIPANT_JOINED", null, "2099-01-02T00:00:00Z");
		long laterId = insertNotification(
			userId, roomId, "ROOM_CANCELED", "2099-01-02T00:00:00Z", "2099-01-02T00:00:00Z");
		insertNotification(otherUserId, roomId, "PARTICIPANT_JOINED", null, "2099-01-03T00:00:00Z");
		insertNotification(userId, roomId, "PARTICIPANT_JOINED", null, "2020-01-01T00:00:00Z");

		var page = notificationListQueryService.findPage(userId, 0, 10);

		assertEquals(2, page.totalElements());
		assertEquals(laterId, page.content().getFirst().id());
		assertEquals("변경된 방 제목", page.content().getFirst().roomTitle());
		assertEquals(1, unreadNotificationCountQueryService.countUnread(userId).unreadCount());
	}

	private long user(String email) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', '닉네임', current_timestamp, current_timestamp)",
			email);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long room(long userId, String title) {
		jdbcTemplate.update(
			"insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, active_participant_count, start_at, place, status, created_at, updated_at) values (?, 'PERSON_FOCUSED', ?, 'ALL_LEVELS', false, 2, 0, '2099-01-01T00:00:00Z', '서울', 'RECRUITING', current_timestamp, current_timestamp)",
			userId,
			title);
		return jdbcTemplate.queryForObject("select id from rooms where host_user_id = ? and title = ?", Long.class,
			userId, title);
	}

	private long insertNotification(long userId, long roomId, String type, String readAt, String createdAt) {
		long sourceEventId = jdbcTemplate
			.queryForObject("select coalesce(max(source_event_id), 0) + 1 from notifications", Long.class);
		jdbcTemplate.update(
			"insert into notifications (source_event_id, recipient_user_id, room_id, type, read_at, created_at, recorded_at, expires_at) values (?, ?, ?, ?, cast(? as timestamp with time zone), cast(? as timestamp with time zone), current_timestamp, dateadd('DAY', 90, cast(? as timestamp with time zone)))",
			sourceEventId,
			userId,
			roomId,
			type,
			readAt,
			createdAt,
			createdAt);
		return jdbcTemplate.queryForObject("select id from notifications where source_event_id = ?", Long.class,
			sourceEventId);
	}
}
