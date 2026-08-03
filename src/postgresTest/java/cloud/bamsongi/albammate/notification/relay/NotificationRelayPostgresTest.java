package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class, properties = "app.notification.relay.enabled=false")
class NotificationRelayPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_notification_relay_test");

	@Autowired
	private NotificationRelayExecutor executor;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void PostgreSQL_선점_시각으로_수신자별_알림과_PROCESSED_전환을_함께_저장한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());
		insertRecipient(eventId, fixture.secondRecipientUserId());

		NotificationRelayExecutor.ProcessedEvent processedEvent = executor.processOne().orElseThrow();

		assertEquals(eventId, processedEvent.sourceEventId());
		assertEquals("PARTICIPATION_JOINED", processedEvent.eventType());
		assertEquals(2, processedEvent.recipientCount());
		assertEquals(
			"PROCESSED",
			jdbcTemplate.queryForObject(
				"select status from notification_outbox_events where id = ?", String.class, eventId));
		Instant processedAt = jdbcTemplate.queryForObject(
			"select processed_at from notification_outbox_events where id = ?", Instant.class, eventId);
		Instant cleanupAt = jdbcTemplate.queryForObject(
			"select cleanup_at from notification_outbox_events where id = ?", Instant.class, eventId);
		List<Instant> notificationRecordedAts = jdbcTemplate.query(
			"select recorded_at from notifications where source_event_id = ? order by recipient_user_id",
			(resultSet, rowNumber) -> resultSet.getTimestamp(1).toInstant(),
			eventId);
		assertEquals(List.of(processedAt, processedAt), notificationRecordedAts);
		assertEquals(processedAt.plusSeconds(30L * 24 * 60 * 60), cleanupAt);
		assertEquals(
			List.of("PARTICIPANT_JOINED", "PARTICIPANT_JOINED"),
			jdbcTemplate.query(
				"select type from notifications where source_event_id = ? order by recipient_user_id",
				(resultSet, rowNumber) -> resultSet.getString(1),
				eventId));
	}

	@Test
	void 미래_availableAt_이벤트는_선점하지_않는다() {
		Fixture fixture = createFixture();
		long eventId = insertRetryWaitEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());

		Optional<NotificationRelayExecutor.ProcessedEvent> processedEvent = executor.processOne();

		assertTrue(processedEvent.isEmpty());
		assertEquals(
			"RETRY_WAIT",
			jdbcTemplate.queryForObject(
				"select status from notification_outbox_events where id = ?", String.class, eventId));
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from notifications where source_event_id = ?", Integer.class, eventId));
	}

	@Test
	void 새_트랜잭션은_저장된_PENDING_이벤트를_재기동_후_처리한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());
		NotificationRelayExecutor.ProcessedEvent processedEvent = new TransactionTemplate(transactionManager)
			.execute(status -> executor.processOne().orElseThrow());

		assertEquals(eventId, processedEvent.sourceEventId());
		assertEquals("PROCESSED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from notifications where source_event_id = ?", Integer.class, eventId));
	}

	@Test
	void 두_worker는_잠긴_이벤트를_건너뛰고_서로_다른_due_이벤트를_분배한다() throws Exception {
		Fixture fixture = createFixture();
		long firstEventId = insertPendingEvent(fixture.roomId());
		long secondEventId = insertPendingEvent(fixture.roomId());
		insertRecipient(firstEventId, fixture.firstRecipientUserId());
		insertRecipient(secondEventId, fixture.secondRecipientUserId());
		CountDownLatch lockAcquired = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try {
			Future<Void> lockHolder = workers.submit(() -> {
				new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					jdbcTemplate.queryForObject(
						"select id from notification_outbox_events where id = ? for update", Long.class, firstEventId);
					lockAcquired.countDown();
					try {
						releaseLock.await(5, TimeUnit.SECONDS);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						status.setRollbackOnly();
					}
				});
				return null;
			});
			assertTrue(lockAcquired.await(5, TimeUnit.SECONDS));

			Future<Optional<NotificationRelayExecutor.ProcessedEvent>> worker = workers.submit(executor::processOne);
			NotificationRelayExecutor.ProcessedEvent processedEvent = worker.get(5, TimeUnit.SECONDS).orElseThrow();
			assertEquals(secondEventId, processedEvent.sourceEventId());
			assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from notifications where source_event_id = ?", Integer.class, secondEventId));
			assertEquals("PENDING", jdbcTemplate.queryForObject(
				"select status from notification_outbox_events where id = ?", String.class, firstEventId));
			releaseLock.countDown();
			lockHolder.get(5, TimeUnit.SECONDS);
		} finally {
			releaseLock.countDown();
			workers.shutdownNow();
		}
	}

	@Test
	void 기존_Notification이_있는_PENDING_재처리는_누락_수신자만_보충한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());
		insertRecipient(eventId, fixture.secondRecipientUserId());
		insertExistingNotification(eventId, fixture.firstRecipientUserId(), fixture.roomId());

		executor.processOne().orElseThrow();

		assertEquals(2, jdbcTemplate.queryForObject(
			"select count(*) from notifications where source_event_id = ?", Integer.class, eventId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from notifications where source_event_id = ? and recipient_user_id = ?",
			Integer.class, eventId, fixture.firstRecipientUserId()));
		assertEquals("PROCESSED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
	}

	@Test
	void 두번째_수신자_저장_실패는_첫번째_Notification과_완료_전환을_함께_롤백한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());
		insertRecipient(eventId, fixture.secondRecipientUserId());
		installSecondNotificationInsertFailureTrigger();
		try {
			Exception failure = assertThrows(Exception.class, executor::processOne);
			assertTrue(hasCauseMessage(failure, "notification relay test second insert failure"));

			assertEquals(
				0,
				jdbcTemplate.queryForObject(
					"select count(*) from notifications where source_event_id = ?", Integer.class, eventId));
			assertEquals(
				"PENDING",
				jdbcTemplate.queryForObject(
					"select status from notification_outbox_events where id = ?", String.class, eventId));
			assertNull(jdbcTemplate.queryForObject(
				"select processed_at from notification_outbox_events where id = ?", Instant.class, eventId));
			assertNull(jdbcTemplate.queryForObject(
				"select cleanup_at from notification_outbox_events where id = ?", Instant.class, eventId));
		} finally {
			jdbcTemplate.execute("drop trigger if exists notification_relay_fail_second_insert on notifications");
			jdbcTemplate.execute("drop function if exists notification_relay_fail_second_insert()");
			jdbcTemplate.update("delete from notification_outbox_events where id = ?", eventId);
		}
	}

	@Test
	void 완료_전환_실패는_이미_저장한_Notification도_함께_롤백한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());
		installProcessedTransitionFailureTrigger();
		try {
			Exception failure = assertThrows(Exception.class, executor::processOne);
			assertTrue(hasCauseMessage(failure, "notification relay test processed transition failure"));
			assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from notifications where source_event_id = ?", Integer.class, eventId));
			assertEquals("PENDING", jdbcTemplate.queryForObject(
				"select status from notification_outbox_events where id = ?", String.class, eventId));
			assertNull(jdbcTemplate.queryForObject(
				"select processed_at from notification_outbox_events where id = ?", Instant.class, eventId));
			assertNull(jdbcTemplate.queryForObject(
				"select cleanup_at from notification_outbox_events where id = ?", Instant.class, eventId));
		} finally {
			jdbcTemplate.execute(
				"drop trigger if exists notification_relay_fail_processed_transition on notification_outbox_events");
			jdbcTemplate.execute("drop function if exists notification_relay_fail_processed_transition()");
			jdbcTemplate.update("delete from notification_outbox_events where id = ?", eventId);
		}
	}

	private void installSecondNotificationInsertFailureTrigger() {
		jdbcTemplate.execute("""
			create function notification_relay_fail_second_insert() returns trigger as $$
			begin
			    if exists (select 1 from notifications where source_event_id = new.source_event_id) then
			        raise exception 'notification relay test second insert failure';
			    end if;
			    return new;
			end;
			$$ language plpgsql
			""");
		jdbcTemplate.execute("""
			create trigger notification_relay_fail_second_insert
			before insert on notifications
			for each row execute function notification_relay_fail_second_insert()
			""");
	}

	private void installProcessedTransitionFailureTrigger() {
		jdbcTemplate.execute("""
			create function notification_relay_fail_processed_transition() returns trigger as $$
			begin
			    if new.status = 'PROCESSED' then
			        raise exception 'notification relay test processed transition failure';
			    end if;
			    return new;
			end;
			$$ language plpgsql
			""");
		jdbcTemplate.execute("""
			create trigger notification_relay_fail_processed_transition
			before update on notification_outbox_events
			for each row execute function notification_relay_fail_processed_transition()
			""");
	}

	private void insertExistingNotification(long eventId, long recipientUserId, long roomId) {
		jdbcTemplate.update("""
			with operation as materialized (select clock_timestamp() as operation_time)
			insert into notifications (
				source_event_id, recipient_user_id, room_id, type, created_at, recorded_at, expires_at)
			select ?, ?, ?, 'PARTICIPANT_JOINED', operation_time - interval '1 minute', operation_time,
				operation_time - interval '1 minute' + interval '90 days'
			from operation
			""", eventId, recipientUserId, roomId);
	}

	private Fixture createFixture() {
		String token = UUID.randomUUID().toString().replace("-", "");
		long hostUserId = insertUser("relay-host-" + token + "@example.com");
		long firstRecipientUserId = insertUser("relay-first-" + token + "@example.com");
		long secondRecipientUserId = insertUser("relay-second-" + token + "@example.com");
		long roomId = jdbcTemplate.queryForObject(
			"insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, "
				+ "active_participant_count, start_at, place, status, created_at, updated_at) "
				+ "values (?, 'PERSON_FOCUSED', 'relay postgres test room', 'ALL_LEVELS', false, 2, 0, "
				+ "clock_timestamp(), 'test place', 'RECRUITING', clock_timestamp(), clock_timestamp()) returning id",
			Long.class,
			hostUserId);
		return new Fixture(roomId, firstRecipientUserId, secondRecipientUserId);
	}

	private long insertUser(String email) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'relay-postgres-test-hash', 'relay-test-user', clock_timestamp(), clock_timestamp()) returning id",
			Long.class,
			email);
	}

	private long insertPendingEvent(long roomId) {
		return jdbcTemplate.queryForObject(
			"with operation as materialized (select clock_timestamp() - interval '5 seconds' as operation_time) "
				+ "insert into notification_outbox_events (event_type, room_id, occurred_at, recorded_at, status, available_at, "
				+ "failure_count, total_failure_count, reprocess_count) "
				+ "select 'PARTICIPATION_JOINED', ?, operation_time - interval '1 minute', operation_time, 'PENDING', "
				+ "operation_time, 0, 0, 0 from operation returning id",
			Long.class,
			roomId);
	}

	private long insertRetryWaitEvent(long roomId) {
		return jdbcTemplate.queryForObject(
			"with operation as materialized (select clock_timestamp() as operation_time) "
				+ "insert into notification_outbox_events (event_type, room_id, occurred_at, recorded_at, status, available_at, "
				+ "failure_count, total_failure_count, last_failure_code, last_failed_at, last_failure_class, "
				+ "last_failure_message, reprocess_count) "
				+ "select 'PARTICIPATION_JOINED', ?, operation_time - interval '1 minute', operation_time, 'RETRY_WAIT', "
				+ "operation_time + interval '1 minute', 1, 1, 'TEST_FAILURE', operation_time, 'TestFailure', 'test failure', "
				+ "0 from operation returning id",
			Long.class,
			roomId);
	}

	private void insertRecipient(long eventId, long recipientUserId) {
		jdbcTemplate.update(
			"insert into notification_outbox_recipients (outbox_event_id, recipient_user_id) values (?, ?)",
			eventId,
			recipientUserId);
	}

	private boolean hasCauseMessage(Throwable failure, String expectedMessage) {
		Throwable current = failure;
		while (current != null) {
			if (current.getMessage() != null && current.getMessage().contains(expectedMessage)) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private record Fixture(long roomId, long firstRecipientUserId, long secondRecipientUserId) {
	}
}
