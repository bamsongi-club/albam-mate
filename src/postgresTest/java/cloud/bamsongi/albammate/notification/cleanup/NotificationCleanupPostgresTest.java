package cloud.bamsongi.albammate.notification.cleanup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
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

	private final List<Fixture> createdFixtures = new ArrayList<>();

	@AfterEach
	void 생성한_fixture를_삭제한다() {
		for (Fixture fixture : createdFixtures) {
			jdbcTemplate.update(
				"delete from notifications where room_id = ? or recipient_user_id = ?",
				fixture.roomId(), fixture.recipientUserId());
			jdbcTemplate.update(
				"delete from notification_outbox_events where room_id = ?",
				fixture.roomId());
			jdbcTemplate.update("delete from rooms where id = ?", fixture.roomId());
			jdbcTemplate.update(
				"delete from users where id in (?, ?)",
				fixture.hostUserId(), fixture.recipientUserId());
		}
		createdFixtures.clear();
	}

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
	void 미래_cleanupAt의_완료와_폐기_Outbox는_삭제하지_않는다() {
		Fixture fixture = createFixture();
		long futureProcessedEventId = insertProcessedOutbox(fixture, "clock_timestamp() - interval '29 days'");
		long futureDiscardedEventId = insertDiscardedOutbox(fixture, "clock_timestamp() - interval '29 days'");

		executor.cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 500);

		assertTrue(outboxExists(futureProcessedEventId));
		assertTrue(outboxExists(futureDiscardedEventId));
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
		assertEquals("CASCADE", jdbcTemplate.queryForObject("""
			select delete_rule
			from information_schema.referential_constraints
			where constraint_schema = 'public'
			  and constraint_name = 'fk_notification_outbox_recipients_outbox_event'
			""", String.class));

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
	void 두_cleanup_인스턴스는_잠긴_가장_이른_due_Outbox를_건너뛰고_중복_삭제하지_않는다() throws Exception {
		Fixture fixture = createFixture();
		long firstOutboxEventId = insertProcessedOutbox(fixture, "clock_timestamp() - interval '31 days'");
		long secondOutboxEventId = insertProcessedOutbox(fixture, "clock_timestamp() - interval '31 days'");
		int advisoryLockKey = Math.toIntExact(firstOutboxEventId);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try (
			ConfigurableApplicationContext firstContext = createCleanupContext();
			ConfigurableApplicationContext secondContext = createCleanupContext();
			Connection advisoryLockConnection = dataSource.getConnection()) {
			NotificationCleanupExecutor firstExecutor = firstContext.getBean(NotificationCleanupExecutor.class);
			NotificationCleanupExecutor secondExecutor = secondContext.getBean(NotificationCleanupExecutor.class);
			acquireAdvisoryLock(advisoryLockConnection, advisoryLockKey);
			installFirstOutboxWorkerDeleteGate(firstOutboxEventId, advisoryLockKey);
			Future<NotificationCleanupExecutor.CleanupBatchResult> firstWorker = workers.submit(
				() -> firstExecutor.cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 1));
			awaitWorkerWaitingForAdvisoryLock(advisoryLockKey);

			NotificationCleanupExecutor.CleanupBatchResult secondResult = workers.submit(
				() -> secondExecutor.cleanupOneBatch(NotificationCleanupTarget.OUTBOX, 1))
				.get(5, TimeUnit.SECONDS);
			assertEquals(1, secondResult.deletedCount());
			assertTrue(outboxExists(firstOutboxEventId));
			assertFalse(outboxExists(secondOutboxEventId));

			releaseAdvisoryLock(advisoryLockConnection, advisoryLockKey);
			NotificationCleanupExecutor.CleanupBatchResult firstResult = firstWorker.get(5, TimeUnit.SECONDS);
			assertEquals(1, firstResult.deletedCount());
			assertFalse(outboxExists(firstOutboxEventId));
		} finally {
			workers.shutdownNow();
			workers.awaitTermination(5, TimeUnit.SECONDS);
			jdbcTemplate.execute(
				"drop trigger if exists notification_outbox_cleanup_hold_first_worker on notification_outbox_events");
			jdbcTemplate.execute("drop function if exists notification_outbox_cleanup_hold_first_worker()");
		}
	}

	@Test
	void Outbox_cleanup_인덱스가_Flyway_스키마에_정의되고_실제_삭제_쿼리_계획에서_사용된다() throws SQLException {
		assertOutboxCleanupIndexDefinition();
		assertOutboxCleanupDeletePlanUsesIndex();
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

	@Test
	void commit_시점_실패도_고정한_measurementTime을_남기고_batch를_롤백한다() {
		Fixture fixture = createFixture();
		long notificationId = insertNotification(fixture, "clock_timestamp() - interval '91 days'");
		installNotificationDeleteCommitFailure();
		try {
			NotificationCleanupExecutor.CleanupBatchFailedException exception = assertThrows(
				NotificationCleanupExecutor.CleanupBatchFailedException.class,
				() -> executor.cleanupOneBatch(NotificationCleanupTarget.NOTIFICATION, 1));

			assertTrue(exception.getMeasurementTime() != null);
			assertFalse(exception.getOriginalExceptionClass().isBlank());
			assertTrue(notificationExists(notificationId));
		} finally {
			jdbcTemplate.execute("drop trigger if exists notification_cleanup_fail_at_commit on notifications");
			jdbcTemplate.execute("drop function if exists notification_cleanup_fail_at_commit()");
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
		Fixture fixture = new Fixture(hostUserId, recipientUserId, roomId);
		createdFixtures.add(fixture);
		return fixture;
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

	private void assertOutboxCleanupIndexDefinition() {
		String indexDefinition = jdbcTemplate.queryForObject("""
			select pg_get_indexdef('idx_notification_outbox_events_cleanup'::regclass)
			""", String.class);

		assertTrue(indexDefinition.contains(
			"ON public.notification_outbox_events USING btree (cleanup_at, id) WHERE (cleanup_at IS NOT NULL)"),
			indexDefinition);
	}

	private void assertOutboxCleanupDeletePlanUsesIndex() throws SQLException {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
			connection.setAutoCommit(false);
			try {
				statement.execute("set local enable_seqscan = off");
				Instant measurementTime = selectPostgreSqlMeasurementTime(statement);
				String executionPlan = explainOutboxCleanupDelete(connection, measurementTime);

				assertTrue(executionPlan.contains("idx_notification_outbox_events_cleanup"), executionPlan);
			} finally {
				connection.rollback();
			}
		}
	}

	private Instant selectPostgreSqlMeasurementTime(Statement statement) throws SQLException {
		try (ResultSet resultSet = statement.executeQuery("select clock_timestamp()")) {
			resultSet.next();
			return resultSet.getObject(1, OffsetDateTime.class).toInstant();
		}
	}

	private String explainOutboxCleanupDelete(Connection connection, Instant measurementTime) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			explain (costs off)
			with due_events as (
			    select event.id
			    from notification_outbox_events event
			    where event.status in ('PROCESSED', 'DISCARDED')
			      and event.cleanup_at <= ?
			    order by event.cleanup_at asc, event.id asc
			    limit 500
			    for update of event skip locked
			), deleted_events as (
			    delete from notification_outbox_events event
			    using due_events
			    where event.id = due_events.id
			    returning event.id
			)
			select count(*) from deleted_events
			""")) {
			statement.setObject(1, OffsetDateTime.ofInstant(measurementTime, ZoneOffset.UTC));
			try (ResultSet resultSet = statement.executeQuery()) {
				StringBuilder executionPlan = new StringBuilder();
				while (resultSet.next()) {
					executionPlan.append(resultSet.getString(1)).append('\n');
				}
				return executionPlan.toString();
			}
		}
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

	private void installNotificationDeleteCommitFailure() {
		jdbcTemplate.execute("""
			create function notification_cleanup_fail_at_commit() returns trigger as $$
			begin
			    raise exception 'notification cleanup test sensitive payload';
			end;
			$$ language plpgsql
			""");
		jdbcTemplate.execute("""
			create constraint trigger notification_cleanup_fail_at_commit
			after delete on notifications
			deferrable initially deferred
			for each row execute function notification_cleanup_fail_at_commit()
			""");
	}

	private void installFirstOutboxWorkerDeleteGate(long outboxEventId, int advisoryLockKey) {
		jdbcTemplate.execute("""
			create function notification_outbox_cleanup_hold_first_worker() returns trigger as $$
			begin
			    if old.id = %d then
			        perform pg_advisory_xact_lock(%d, %d);
			    end if;
			    return old;
			end;
			$$ language plpgsql
			""".formatted(outboxEventId, CLEANUP_TEST_ADVISORY_LOCK_CLASS, advisoryLockKey));
		jdbcTemplate.execute("""
			create trigger notification_outbox_cleanup_hold_first_worker
			before delete on notification_outbox_events
			for each row execute function notification_outbox_cleanup_hold_first_worker()
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

	private record Fixture(long hostUserId, long recipientUserId, long roomId) {
	}
}
