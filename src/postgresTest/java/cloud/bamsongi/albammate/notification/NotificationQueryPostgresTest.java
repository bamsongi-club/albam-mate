package cloud.bamsongi.albammate.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.notification.repository.NotificationQueryRepository;
import cloud.bamsongi.albammate.notification.service.query.NotificationListQueryService;
import cloud.bamsongi.albammate.notification.service.query.UnreadNotificationCountQueryService;

/** PostgreSQL transaction_timestamp 만료 경계와 현재 방 제목 투영을 검증한다. */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class NotificationQueryPostgresTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("albam_mate_notification_query_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private NotificationListQueryService notificationListQueryService;
	@Autowired
	private UnreadNotificationCountQueryService unreadNotificationCountQueryService;
	@MockitoSpyBean
	private NotificationQueryRepository notificationQueryRepository;

	@Test
	void PostgreSQL_같은_읽기_트랜잭션에서_본인_미만료_목록과_count가_같은_경계를_쓴다() {
		long userId = user("query-owner@example.com");
		long otherUserId = user("query-other@example.com");
		long roomId = room(userId, "이전 제목");
		long firstId = notification(userId, roomId, "PARTICIPANT_JOINED", "null", "TIMESTAMPTZ '2099-01-01T00:00:00Z'");
		long secondId = notification(userId, roomId, "ROOM_CANCELED", "transaction_timestamp()",
			"TIMESTAMPTZ '2099-01-01T00:00:00Z'");
		notification(otherUserId, roomId, "PARTICIPANT_JOINED", "null", "transaction_timestamp() - interval '1 day'");
		notification(userId, roomId, "PARTICIPANT_JOINED", "null", "transaction_timestamp() - interval '91 days'");
		jdbcTemplate.update("update rooms set title = '현재 제목' where id = ?", roomId);

		var page = notificationListQueryService.findPage(userId, 0, 10);

		assertEquals(2, page.totalElements());
		assertEquals(secondId, page.content().getFirst().id());
		assertEquals(firstId, page.content().get(1).id());
		assertEquals(secondId, notificationListQueryService.findPage(userId, 0, 1).content().getFirst().id());
		assertEquals(firstId, notificationListQueryService.findPage(userId, 1, 1).content().getFirst().id());
		assertEquals(2, notificationListQueryService.findPage(userId, 2, 1).totalElements());
		assertTrue(notificationListQueryService.findPage(userId, 2, 1).content().isEmpty());
		assertTrue(page.content().stream().allMatch(item -> item.roomTitle().equals("현재 제목")));
		assertEquals(1, unreadNotificationCountQueryService.countUnread(userId).unreadCount());
		jdbcTemplate.update("update notifications set read_at = transaction_timestamp() where recipient_user_id = ?",
			userId);
		assertEquals(0, unreadNotificationCountQueryService.countUnread(userId).unreadCount());
		assertEquals(0, notificationListQueryService.findPage(otherUserId + 10_000_000L, 0, 10).totalElements());
	}

	@Test
	void 목록_SQL_뒤_만료가_지나도_같은_transaction_timestamp로_content와_count가_일치한다() {
		long userId = user("query-boundary-owner@example.com");
		long roomId = room(userId, "경계 방");
		notification(userId, roomId, "PARTICIPANT_JOINED", "null",
			"transaction_timestamp() - interval '90 days' + interval '2 seconds'");
		doAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			jdbcTemplate.queryForObject("select pg_sleep(3)", Object.class);
			return result;
		}).when(notificationQueryRepository).findPage(userId, 0, 10);

		var page = notificationListQueryService.findPage(userId, 0, 10);

		assertEquals(1, page.content().size());
		assertEquals(1, page.totalElements());
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

	private long notification(long userId, long roomId, String type, String readAt, String createdAt) {
		Long sourceEventId = jdbcTemplate
			.queryForObject("select coalesce(max(source_event_id), 0) + 1 from notifications", Long.class);
		return jdbcTemplate.queryForObject(
			"insert into notifications (source_event_id, recipient_user_id, room_id, type, read_at, created_at, recorded_at, expires_at) values (?, ?, ?, ?, "
				+ readAt + ", " + createdAt + ", transaction_timestamp(), " + createdAt
				+ " + interval '90 days') returning id",
			Long.class, sourceEventId, userId, roomId, type);
	}
}
