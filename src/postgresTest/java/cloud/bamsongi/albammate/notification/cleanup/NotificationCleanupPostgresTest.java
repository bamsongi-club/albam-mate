package cloud.bamsongi.albammate.notification.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class, properties = {
	"app.notification.cleanup.batch-size=500",
	"app.notification.cleanup.interval=1h",
	"app.notification.cleanup.jitter=0s",
	"app.notification.relay.enabled=false"
})
@Import(NotificationCleanupPostgresTest.AheadApplicationClockConfiguration.class)
class NotificationCleanupPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final int CLEANUP_TEST_ADVISORY_LOCK_CLASS = 268;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_notification_cleanup_test");

	@Autowired
	private NotificationCleanupExecutor executor;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private Clock applicationClock;

	@Test
	void 두_cleanup_인스턴스는_잠긴_가장_이른_due_Notification을_건너뛰고_중복_삭제하지_않는다() throws Exception {
		Fixture fixture = createFixture();
		long firstNotificationId = insertNotification(fixture, "clock_timestamp() - interval '91 days'");
		long secondNotificationId = insertNotification(fixture, "clock_timestamp() - interval '91 days'");
		int advisoryLockKey = Math.toIntExact(firstNotificationId);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try (
			ConfigurableApplicationContext firstContext = createCleanupContext();
			ConfigurableApplicationContext secondContext = createCleanupContext();
			Connection advisoryLockConnection = dataSource.getConnection()) {
			NotificationCleanupExecutor firstExecutor = firstContext.getBean(NotificationCleanupExecutor.class);
			NotificationCleanupExecutor secondExecutor = secondContext.getBean(NotificationCleanupExecutor.class);
			acquireAdvisoryLock(advisoryLockConnection, advisoryLockKey);
			installFirstWorkerDeleteGate(firstNotificationId, advisoryLockKey);
			Future<NotificationCleanupExecutor.CleanupBatchResult> firstWorker = workers.submit(
				() -> firstExecutor.cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 1));
			awaitWorkerWaitingForAdvisoryLock(advisoryLockKey);

			NotificationCleanupExecutor.CleanupBatchResult secondResult = workers.submit(
				() -> secondExecutor.cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 1))
				.get(5, TimeUnit.SECONDS);
			assertEquals(1, secondResult.deletedCount());
			assertTrue(notificationExists(firstNotificationId));
			assertFalse(notificationExists(secondNotificationId));

			releaseAdvisoryLock(advisoryLockConnection, advisoryLockKey);
			NotificationCleanupExecutor.CleanupBatchResult firstResult = firstWorker.get(5, TimeUnit.SECONDS);
			assertEquals(1, firstResult.deletedCount());
			assertFalse(notificationExists(firstNotificationId));
		} finally {
			workers.shutdownNow();
			workers.awaitTermination(5, TimeUnit.SECONDS);
			jdbcTemplate.execute("drop trigger if exists notification_cleanup_hold_first_worker on notifications");
			jdbcTemplate.execute("drop function if exists notification_cleanup_hold_first_worker()");
		}
	}

	@Test
	void 앱_Clock이_앞서도_PostgreSQL_measurementTime_이전의_Notification은_삭제하지_않는다() {
		Fixture fixture = createFixture();
		long futureNotificationId = insertNotification(fixture, "clock_timestamp() - interval '89 days'");

		NotificationCleanupExecutor.CleanupBatchResult result = executor.cleanupOneBatch(
			NotificationCleanupTarget.NOTIFICATION, 500);

		assertEquals(Instant.parse("2030-01-01T00:00:00Z"), applicationClock.instant());
		assertEquals(0, result.deletedCount());
		assertTrue(notificationExists(futureNotificationId));
	}

	@Test
	void 만료_Notification과_정리_기한이_지난_완료_폐기_Outbox만_삭제한다() {
		Fixture fixture = createFixture();
		long expiredNotificationId = insertNotification(fixture, "clock_timestamp() - interval '91 days'");
		long availableNotificationId = insertNotification(fixture, "clock_timestamp() - interval '89 days'");
		long processedEventId = insertProcessedOutbox(fixture, "clock_timestamp() - interval '31 days'");
		long discardedEventId = insertDiscardedOutbox(fixture, "clock_timestamp() - interval '31 days'");
		long pendingEventId = insertPendingOutbox(fixture);
		long retryWaitEventId = insertRetryWaitOutbox(fixture);
		long failedEventId = insertFailedOutbox(fixture);

		executor.cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 500);
		executor.cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 500);

		assertFalse(notificationExists(expiredNotificationId));
		assertTrue(notificationExists(availableNotificationId));
		assertFalse(outboxExists(processedEventId));
		assertFalse(outboxExists(discardedEventId));
		assertTrue(outboxExists(pendingEventId));
		assertTrue(outboxExists(retryWaitEventId));
		assertTrue(outboxExists(failedEventId));
	}

	@Test
	void Outbox_삭제는_FK_CASCADE로_남은_수신자도_함께_삭제한다() {
		Fixture fixture = createFixture();
		long eventId = insertProcessedOutbox(fixture, "clock_timestamp() - interval '31 days'");
		jdbcTemplate.update(
			"insert into notification_outbox_recipients (outbox_event_id, recipient_user_id) values (?, ?)",
			eventId, fixture.recipientUserId());

		executor.cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 500);

		assertFalse(outboxExists(eventId));
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from notification_outbox_recipients where outbox_event_id = ?",
				Integer.class,
				eventId));
	}

	@Test
	void 두번째_삭제가_실패하면_같은_batch의_첫번째_삭제도_함께_롤백한다() {
		Fixture fixture = createFixture();
		long firstNotificationId = insertNotification(fixture, "clock_timestamp() - interval '91 days'");
		long secondNotificationId = insertNotification(fixture, "clock_timestamp() - interval '91 days'");
		installSecondNotificationDeleteFailure(secondNotificationId);
		try {
			NotificationCleanupExecutor.CleanupBatchFailedException exception = assertThrows(
				NotificationCleanupExecutor.CleanupBatchFailedException.class,
				() -> executor.cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 2));

			assertTrue(exception.getMeasurementTime() != null);
			assertFalse(exception.getOriginalExceptionClass().isBlank());
			assertTrue(notificationExists(firstNotificationId));
			assertTrue(notificationExists(secondNotificationId));
		} finally {
			jdbcTemplate.execute("drop trigger if exists notification_cleanup_fail_second_delete on notifications");
			jdbcTemplate.execute("drop function if exists notification_cleanup_fail_second_delete()");
		}
	}

	private ConfigurableApplicationContext createCleanupContext() {
		return new SpringApplicationBuilder(AlbamMateApplication.class)
			.run(
				"--server.port=0",
				"--app.notification.cleanup.batch-size=1",
				"--app.notification.cleanup.interval=1h",
				"--app.notification.cleanup.jitter=0s",
				"--app.notification.relay.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword());
	}

	private Fixture createFixture() {
		long hostUserId = jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (concat('cleanup-host-', nextval('users_id_seq'), '@example.com'), 'hash', '주최자', now(), now()) "
				+ "returning id",
			Long.class);
		long recipientUserId = jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (concat('cleanup-recipient-', nextval('users_id_seq'), '@example.com'), 'hash', '수신자', now(), now()) "
				+ "returning id",
			Long.class);
		long roomId = jdbcTemplate.queryForObject(
			"insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, "
				+ "active_participant_count, start_at, place, status, created_at, updated_at) "
				+ "values (?, 'PERSON_FOCUSED', 'cleanup 검증 방', 'ALL_LEVELS', false, 1, 0, now(), '홍대', "
				+ "'RECRUITING', now(), now()) returning id",
			Long.class,
			hostUserId);
		return new Fixture(roomId, recipientUserId);
	}

	private long insertNotification(Fixture fixture, String createdAtExpression) {
		return jdbcTemplate.queryForObject("""
			with notification_time as materialized (
			    select %s as created_at
			)
			insert into notifications (
			    source_event_id, recipient_user_id, room_id, type, read_at, created_at, recorded_at, expires_at
			)
			select nextval('notification_outbox_events_id_seq'), ?, ?, 'PARTICIPANT_JOINED', null,
			    notification_time.created_at, clock_timestamp(), notification_time.created_at + interval '90 days'
			from notification_time
			returning id
			""".formatted(createdAtExpression), Long.class, fixture.recipientUserId(), fixture.roomId());
	}

	private long insertProcessedOutbox(Fixture fixture, String processedAtExpression) {
		return jdbcTemplate.queryForObject("""
			with event_time as materialized (
			    select %s as processed_at
			)
			insert into notification_outbox_events (
			    event_type, room_id, occurred_at, recorded_at, status, available_at, failure_count, total_failure_count,
			    last_failure_code, last_failed_at, last_failure_class, last_failure_message, reprocess_count,
			    last_reprocessed_at, last_reprocess_reason, processed_at, discarded_at, discard_reason, cleanup_at
			)
			select 'PARTICIPATION_JOINED', ?, event_time.processed_at - interval '1 day', event_time.processed_at,
			    'PROCESSED', null, 0, 0, null, null, null, null, 0, null, null, event_time.processed_at, null, null,
			    event_time.processed_at + interval '30 days'
			from event_time
			returning id
			""".formatted(processedAtExpression), Long.class, fixture.roomId());
	}

	private long insertDiscardedOutbox(Fixture fixture, String discardedAtExpression) {
		return jdbcTemplate.queryForObject(
			"""
				with event_time as materialized (
				    select %s as discarded_at
				)
				insert into notification_outbox_events (
				    event_type, room_id, occurred_at, recorded_at, status, available_at, failure_count, total_failure_count,
				    last_failure_code, last_failed_at, last_failure_class, last_failure_message, reprocess_count,
				    last_reprocessed_at, last_reprocess_reason, processed_at, discarded_at, discard_reason, cleanup_at
				)
				select 'PARTICIPATION_JOINED', ?, event_time.discarded_at - interval '1 day', event_time.discarded_at,
				    'DISCARDED', null, 0, 1, 'CLEANUP_TEST', event_time.discarded_at, 'CleanupTestFailure', 'sanitized',
				    0, null, null, null, event_time.discarded_at, 'cleanup test', event_time.discarded_at + interval '30 days'
				from event_time
				returning id
				"""
				.formatted(discardedAtExpression),
			Long.class, fixture.roomId());
	}

	private long insertPendingOutbox(Fixture fixture) {
		return jdbcTemplate.queryForObject("""
			with event_time as materialized (
			    select clock_timestamp() - interval '31 days' as recorded_at
			)
			insert into notification_outbox_events (
			    event_type, room_id, occurred_at, recorded_at, status, available_at, failure_count, total_failure_count,
			    last_failure_code, last_failed_at, last_failure_class, last_failure_message, reprocess_count,
			    last_reprocessed_at, last_reprocess_reason, processed_at, discarded_at, discard_reason, cleanup_at
			)
			select 'PARTICIPATION_JOINED', ?, event_time.recorded_at, event_time.recorded_at, 'PENDING',
			    event_time.recorded_at, 0, 0, null, null, null, null, 0, null, null, null, null, null, null
			from event_time
			returning id
			""", Long.class, fixture.roomId());
	}

	private long insertRetryWaitOutbox(Fixture fixture) {
		return jdbcTemplate.queryForObject("""
			with event_time as materialized (
			    select clock_timestamp() - interval '31 days' as recorded_at
			)
			insert into notification_outbox_events (
			    event_type, room_id, occurred_at, recorded_at, status, available_at, failure_count, total_failure_count,
			    last_failure_code, last_failed_at, last_failure_class, last_failure_message, reprocess_count,
			    last_reprocessed_at, last_reprocess_reason, processed_at, discarded_at, discard_reason, cleanup_at
			)
			select 'PARTICIPATION_JOINED', ?, event_time.recorded_at, event_time.recorded_at, 'RETRY_WAIT',
			    event_time.recorded_at, 1, 1, 'CLEANUP_TEST', event_time.recorded_at, 'CleanupTestFailure', 'sanitized',
			    0, null, null, null, null, null, null
			from event_time
			returning id
			""", Long.class, fixture.roomId());
	}

	private long insertFailedOutbox(Fixture fixture) {
		return jdbcTemplate.queryForObject("""
			with event_time as materialized (
			    select clock_timestamp() - interval '31 days' as recorded_at
			)
			insert into notification_outbox_events (
			    event_type, room_id, occurred_at, recorded_at, status, available_at, failure_count, total_failure_count,
			    last_failure_code, last_failed_at, last_failure_class, last_failure_message, reprocess_count,
			    last_reprocessed_at, last_reprocess_reason, processed_at, discarded_at, discard_reason, cleanup_at
			)
			select 'PARTICIPATION_JOINED', ?, event_time.recorded_at, event_time.recorded_at, 'FAILED',
			    null, 1, 1, 'CLEANUP_TEST', event_time.recorded_at, 'CleanupTestFailure', 'sanitized',
			    0, null, null, null, null, null, null
			from event_time
			returning id
			""", Long.class, fixture.roomId());
	}

	private boolean notificationExists(long notificationId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"select exists (select 1 from notifications where id = ?)", Boolean.class, notificationId));
	}

	private boolean outboxExists(long outboxEventId) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
			"select exists (select 1 from notification_outbox_events where id = ?)", Boolean.class, outboxEventId));
	}

	private void installFirstWorkerDeleteGate(long notificationId, int advisoryLockKey) {
		jdbcTemplate.execute("""
			create function notification_cleanup_hold_first_worker() returns trigger as $$
			begin
			    if old.id = %d then
			        perform pg_advisory_xact_lock(%d, %d);
			    end if;
			    return old;
			end;
			$$ language plpgsql
			""".formatted(notificationId, CLEANUP_TEST_ADVISORY_LOCK_CLASS, advisoryLockKey));
		jdbcTemplate.execute("""
			create trigger notification_cleanup_hold_first_worker
			before delete on notifications
			for each row execute function notification_cleanup_hold_first_worker()
			""");
	}

	private void installSecondNotificationDeleteFailure(long notificationId) {
		jdbcTemplate.execute("""
			create function notification_cleanup_fail_second_delete() returns trigger as $$
			begin
			    if old.id = %d then
			        raise exception 'notification cleanup test sensitive payload';
			    end if;
			    return old;
			end;
			$$ language plpgsql
			""".formatted(notificationId));
		jdbcTemplate.execute("""
			create trigger notification_cleanup_fail_second_delete
			before delete on notifications
			for each row execute function notification_cleanup_fail_second_delete()
			""");
	}

	private void acquireAdvisoryLock(Connection connection, int advisoryLockKey) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_lock(?, ?)")) {
			statement.setInt(1, CLEANUP_TEST_ADVISORY_LOCK_CLASS);
			statement.setInt(2, advisoryLockKey);
			statement.execute();
		}
	}

	private void awaitWorkerWaitingForAdvisoryLock(int advisoryLockKey) {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadlineNanos) {
			Boolean waiting = jdbcTemplate.queryForObject("""
				select exists (
				    select 1
				    from pg_locks
				    where locktype = 'advisory'
				      and classid = ?
				      and objid = ?
				      and granted = false
				)
				""", Boolean.class, CLEANUP_TEST_ADVISORY_LOCK_CLASS, advisoryLockKey);
			if (Boolean.TRUE.equals(waiting)) {
				return;
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("first cleanup worker did not reach the PostgreSQL advisory lock gate");
	}

	private void releaseAdvisoryLock(Connection connection, int advisoryLockKey) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?, ?)")) {
			statement.setInt(1, CLEANUP_TEST_ADVISORY_LOCK_CLASS);
			statement.setInt(2, advisoryLockKey);
			statement.execute();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class AheadApplicationClockConfiguration {

		@Bean
		@Primary
		Clock cleanupTestApplicationClock() {
			return Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
		}
	}

	private record Fixture(long roomId, long recipientUserId) {
	}
}
