package cloud.bamsongi.albammate.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.notification.enums.NotificationType;
import cloud.bamsongi.albammate.notification.repository.NotificationQueryRepository;
import cloud.bamsongi.albammate.notification.service.query.NotificationQueryService;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

/** PostgreSQL transaction_timestamp 만료 경계와 현재 방 제목 투영을 검증한다. */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class NotificationQueryPostgresTest extends SharedPostgresIntegrationSupport {

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private NotificationQueryService notificationQueryService;
	@Autowired
	private PlatformTransactionManager transactionManager;
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

		var page = notificationQueryService.findPage(userId, 0, 10);

		assertEquals(2, page.totalElements());
		assertEquals(secondId, page.content().getFirst().id());
		assertEquals(firstId, page.content().get(1).id());
		assertEquals(secondId, notificationQueryService.findPage(userId, 0, 1).content().getFirst().id());
		assertEquals(firstId, notificationQueryService.findPage(userId, 1, 1).content().getFirst().id());
		assertEquals(2, notificationQueryService.findPage(userId, 2, 1).totalElements());
		assertTrue(notificationQueryService.findPage(userId, 2, 1).content().isEmpty());
		assertTrue(page.content().stream().allMatch(item -> item.roomTitle().equals("현재 제목")));
		assertEquals(1, notificationQueryService.countUnread(userId).unreadCount());
		jdbcTemplate.update("update notifications set read_at = transaction_timestamp() where recipient_user_id = ?",
			userId);
		assertEquals(0, notificationQueryService.countUnread(userId).unreadCount());
		assertEquals(0, notificationQueryService.findPage(otherUserId + 10_000_000L, 0, 10).totalElements());
	}

	@Test
	void WAITLIST_PROMOTED도_본인_미만료_목록과_미확인_개수에_포함하고_타인에게는_숨긴다() {
		long ownerId = user("promotion-query-owner@example.com");
		long otherUserId = user("promotion-query-other@example.com");
		long roomId = room(ownerId, "승격 알림 방");
		long notificationId = notification(ownerId, roomId, "WAITLIST_PROMOTED", "null",
			"TIMESTAMPTZ '2099-01-01T00:00:00Z'");

		var page = notificationQueryService.findPage(ownerId, 0, 10);

		assertEquals(notificationId, page.content().getFirst().id());
		assertEquals(NotificationType.WAITLIST_PROMOTED, page.content().getFirst().type());
		assertEquals(1, notificationQueryService.countUnread(ownerId).unreadCount());
		assertEquals(0, notificationQueryService.findPage(otherUserId, 0, 10).totalElements());
	}

	@Test
	void 목록_SQL_뒤_만료가_지나도_같은_transaction_timestamp로_content와_count가_일치한다() {
		long userId = user("query-boundary-owner@example.com");
		long roomId = room(userId, "경계 방");
		long notificationId = notification(userId, roomId, "PARTICIPANT_JOINED", "null",
			"transaction_timestamp()");
		doAnswer(invocation -> {
			Object result = invocation.callRealMethod();
			moveExpiryPastClockWhileKeepingItAfterQueryTime(notificationId);
			return result;
		}).when(notificationQueryRepository).findPage(userId, 0, 10);

		var page = notificationQueryService.findPage(userId, 0, 10);

		assertEquals(1, page.content().size());
		assertEquals(1, page.totalElements());
	}

	private void moveExpiryPastClockWhileKeepingItAfterQueryTime(long notificationId) {
		OffsetDateTime expiresAt = jdbcTemplate.queryForObject(
			"select transaction_timestamp() + interval '1 second'",
			OffsetDateTime.class);
		TransactionTemplate requiresNew = new TransactionTemplate(transactionManager);
		requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		requiresNew.executeWithoutResult(status -> jdbcTemplate.update(
			"""
				update notifications
				set created_at = ? - interval '90 days', expires_at = ?
				where id = ?
				""",
			expiresAt,
			expiresAt,
			notificationId));
		jdbcTemplate.queryForObject(
			"""
				select pg_sleep((
					greatest(0, extract(epoch from (expires_at - clock_timestamp()))) + 0.1
				)::double precision)
				from notifications
				where id = ?
				""",
			Object.class,
			notificationId);
		assertTrue(Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"""
				select transaction_timestamp() < expires_at
					and clock_timestamp() > expires_at
				from notifications
				where id = ?
				""",
			Boolean.class,
			notificationId)));
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
