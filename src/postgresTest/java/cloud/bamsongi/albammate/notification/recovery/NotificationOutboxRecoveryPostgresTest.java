package cloud.bamsongi.albammate.notification.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.AlbamMateApplication;

/** 실제 PostgreSQL의 operationTime, FOR UPDATE 및 수신자 삭제 원자성을 검증한다. */
@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class, properties = "app.notification.relay.enabled=false")
class NotificationOutboxRecoveryPostgresTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("albam_mate_notification_recovery_test");

	@Autowired
	private NotificationOutboxRecoveryService recoveryService;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Test
	void PostgreSQL_시각_창_안의_FAILED만_RETRY_WAIT로_원자적으로_복구한다() {
		Fixture fixture = createFixture();
		long eventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		insertRecipient(eventId, fixture.recipientUserId());

		NotificationOutboxRecoveryResult result = recoveryService.execute(reprocess(List.of(eventId)));

		assertEquals(1, result.changedCount());
		assertEquals("RETRY_WAIT", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select failure_count from notification_outbox_events where id = ?", Integer.class, eventId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select reprocess_count from notification_outbox_events where id = ?", Integer.class, eventId));
		assertEquals(5, jdbcTemplate.queryForObject(
			"select total_failure_count from notification_outbox_events where id = ?", Integer.class, eventId));
		assertEquals("RELAY_PROCESSING_FAILURE", jdbcTemplate.queryForObject(
			"select last_failure_code from notification_outbox_events where id = ?", String.class, eventId));
		assertTrue(jdbcTemplate.queryForObject(
			"select available_at = last_reprocessed_at from notification_outbox_events where id = ?", Boolean.class,
			eventId));
	}

	@Test
	void 만료_경계는_재처리하지_않고_확인된_폐기만_수신자를_함께_삭제한다() {
		Fixture fixture = createFixture();
		long eventId = insertFailedEvent(fixture.roomId(), "89 days", "NOTIFICATION_EXPIRED");
		insertRecipient(eventId, fixture.recipientUserId());

		assertThrows(NotificationOutboxRecoveryInputException.class,
			() -> recoveryService.execute(reprocess(List.of(eventId))));
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));

		recoveryService.execute(discard(List.of(eventId)));
		assertEquals("DISCARDED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from notification_outbox_recipients where outbox_event_id = ?", Integer.class, eventId));
		assertTrue(jdbcTemplate.queryForObject(
			"select cleanup_at = discarded_at + interval '30 days' from notification_outbox_events where id = ?",
			Boolean.class,
			eventId));
	}

	@Test
	void 없는_대상이_섞이면_기존_FAILED도_변경하지_않는다() {
		Fixture fixture = createFixture();
		long eventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		insertRecipient(eventId, fixture.recipientUserId());

		assertThrows(NotificationOutboxRecoveryInputException.class,
			() -> recoveryService.execute(reprocess(List.of(eventId, eventId + 100000L))));
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
	}

	@Test
	void 상태가_부적격인_대상이_섞이면_다른_FAILED도_변경하지_않는다() {
		Fixture fixture = createFixture();
		long eligibleEventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		long ineligibleEventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		insertRecipient(eligibleEventId, fixture.recipientUserId());
		insertRecipient(ineligibleEventId, fixture.recipientUserId());
		jdbcTemplate.update(
			"update notification_outbox_events set status = 'RETRY_WAIT', available_at = clock_timestamp() where id = ?",
			ineligibleEventId);

		assertThrows(NotificationOutboxRecoveryInputException.class,
			() -> recoveryService.execute(reprocess(List.of(eligibleEventId, ineligibleEventId))));
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eligibleEventId));
	}

	@Test
	void DISCARD에_부적격_대상이_섞이면_모든_이벤트와_수신자를_보존한다() {
		Fixture fixture = createFixture();
		long eligibleEventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		long ineligibleEventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		insertRecipient(eligibleEventId, fixture.recipientUserId());
		insertRecipient(ineligibleEventId, fixture.recipientUserId());
		jdbcTemplate.update(
			"update notification_outbox_events set status = 'RETRY_WAIT', available_at = clock_timestamp() where id = ?",
			ineligibleEventId);

		assertThrows(NotificationOutboxRecoveryInputException.class,
			() -> recoveryService.execute(discard(List.of(eligibleEventId, ineligibleEventId))));

		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eligibleEventId));
		assertEquals("RETRY_WAIT", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, ineligibleEventId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from notification_outbox_recipients where outbox_event_id = ?", Integer.class,
			eligibleEventId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from notification_outbox_recipients where outbox_event_id = ?", Integer.class,
			ineligibleEventId));
	}

	@Test
	void 잠금_대기_뒤_89일_경계에_도달한_이벤트는_재처리하지_않는다() throws Exception {
		Fixture fixture = createFixture();
		long eventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		insertRecipient(eventId, fixture.recipientUserId());
		ExecutorService worker = Executors.newSingleThreadExecutor();
		try (Connection lockHolder = dataSource.getConnection()) {
			lockHolder.setAutoCommit(false);
			lockEvent(lockHolder, eventId);
			Future<Boolean> reprocessResult = worker.submit(() -> executeReprocess(List.of(eventId)));
			awaitRecoveryWorkersWaitingForEventLock(1);
			moveOccurredAtToReprocessBoundary(lockHolder, eventId);
			lockHolder.commit();

			assertFalse(reprocessResult.get(5, TimeUnit.SECONDS));
		} finally {
			shutdownWorkers(worker);
		}
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
	}

	@Test
	void 겹치는_역순_ID_명령은_교착없이_한_명령_전체_성공과_다른_명령_전체_부적격으로_수렴한다() throws Exception {
		Fixture fixture = createFixture();
		long firstEventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		long secondEventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		insertRecipient(firstEventId, fixture.recipientUserId());
		insertRecipient(secondEventId, fixture.recipientUserId());
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try (Connection lockHolder = dataSource.getConnection()) {
			lockHolder.setAutoCommit(false);
			lockEvent(lockHolder, firstEventId);
			Future<Boolean> reverseIds = workers.submit(() -> executeReprocess(List.of(secondEventId, firstEventId)));
			Future<Boolean> orderedIds = workers.submit(() -> executeReprocess(List.of(firstEventId, secondEventId)));
			awaitRecoveryWorkersWaitingForEventLock(2);
			lockHolder.commit();
			assertEquals(1,
				(reverseIds.get(5, TimeUnit.SECONDS) ? 1 : 0) + (orderedIds.get(5, TimeUnit.SECONDS) ? 1 : 0));
		} finally {
			shutdownWorkers(workers);
		}
		assertEquals(List.of("RETRY_WAIT", "RETRY_WAIT"), jdbcTemplate.query(
			"select status from notification_outbox_events where id in (?, ?) order by id",
			(resultSet, rowNumber) -> resultSet.getString(1), firstEventId, secondEventId));
	}

	@Test
	void reason은_실제_변경에만_DB로_저장되고_로그에_reasonReference만_남는다() {
		String privateReason = "operator private reason must not leak";
		Fixture fixture = createFixture();
		long eventId = insertFailedEvent(fixture.roomId(), "1 day", "RELAY_PROCESSING_FAILURE");
		insertRecipient(eventId, fixture.recipientUserId());
		ListAppender<ILoggingEvent> appender = attachRecoveryLogAppender();
		try {
			recoveryService.preview(new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.REPROCESS,
				List.of(eventId), true, "ISSUE-267", privateReason, "ops-user", null));
			assertNull(jdbcTemplate.queryForObject(
				"select last_reprocess_reason from notification_outbox_events where id = ?", String.class, eventId));
			recoveryService.execute(new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.REPROCESS,
				List.of(eventId), false, "ISSUE-267", privateReason, "ops-user", null));
			assertEquals(privateReason, jdbcTemplate.queryForObject(
				"select last_reprocess_reason from notification_outbox_events where id = ?", String.class, eventId));
			String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
			assertFalse(logs.contains(privateReason));
			assertTrue(logs.contains("ISSUE-267"));
		} finally {
			detachRecoveryLogAppender(appender);
		}
	}

	private boolean executeReprocess(List<Long> eventIds) {
		try {
			recoveryService.execute(reprocess(eventIds));
			return true;
		} catch (NotificationOutboxRecoveryInputException exception) {
			return false;
		}
	}

	private void lockEvent(Connection connection, long eventId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"select id from notification_outbox_events where id = ? for update")) {
			statement.setLong(1, eventId);
			statement.executeQuery();
		}
	}

	private void moveOccurredAtToReprocessBoundary(Connection connection, long eventId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"update notification_outbox_events set occurred_at = clock_timestamp() - interval '89 days' where id = ?")) {
			statement.setLong(1, eventId);
			statement.executeUpdate();
		}
	}

	private void awaitRecoveryWorkersWaitingForEventLock(int expectedWorkerCount) {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
		while (System.nanoTime() < deadlineNanos) {
			Integer waitingWorkerCount = jdbcTemplate.queryForObject("""
				select count(*)
				from pg_stat_activity
				where wait_event_type = 'Lock'
				  and query like '%notification_outbox_events%'
				  and query like '%for update%'
				""", Integer.class);
			if (waitingWorkerCount != null && waitingWorkerCount >= expectedWorkerCount) {
				return;
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("recovery workers did not wait for the notification outbox event lock");
	}

	private static void shutdownWorkers(ExecutorService workers) throws InterruptedException {
		workers.shutdownNow();
		assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS), "recovery workers did not terminate");
	}

	private NotificationOutboxRecoveryRequest reprocess(List<Long> eventIds) {
		return new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.REPROCESS, eventIds, false,
			"ISSUE-267", "recover after incident", "ops-user", null);
	}

	private NotificationOutboxRecoveryRequest discard(List<Long> eventIds) {
		return new NotificationOutboxRecoveryRequest(NotificationRecoveryAction.DISCARD, eventIds, false,
			"ISSUE-267", "discard after incident", "ops-user", "DISCARD");
	}

	private Fixture createFixture() {
		String token = UUID.randomUUID().toString().replace("-", "");
		long hostUserId = insertUser("recovery-host-" + token + "@example.com");
		long recipientUserId = insertUser("recovery-recipient-" + token + "@example.com");
		long roomId = jdbcTemplate.queryForObject(
			"insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, "
				+ "active_participant_count, start_at, place, status, created_at, updated_at) "
				+ "values (?, 'PERSON_FOCUSED', 'recovery test room', 'ALL_LEVELS', false, 2, 0, "
				+ "clock_timestamp(), 'test place', 'RECRUITING', clock_timestamp(), clock_timestamp()) returning id",
			Long.class, hostUserId);
		return new Fixture(roomId, recipientUserId);
	}

	private long insertUser(String email) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'recovery-test-hash', 'recovery-test-user', clock_timestamp(), clock_timestamp()) returning id",
			Long.class, email);
	}

	private long insertFailedEvent(long roomId, String occurredAgo, String failureCode) {
		return jdbcTemplate.queryForObject(
			"with operation as materialized (select clock_timestamp() as operation_time) "
				+ "insert into notification_outbox_events (event_type, room_id, occurred_at, recorded_at, status, "
				+ "failure_count, total_failure_count, last_failure_code, last_failed_at, last_failure_class, "
				+ "last_failure_message, reprocess_count) select 'PARTICIPATION_JOINED', ?, "
				+ "operation_time - cast(? as interval), operation_time, 'FAILED', 5, 5, ?, operation_time, "
				+ "'TestFailure', 'test failure', 0 from operation returning id",
			Long.class, roomId, occurredAgo, failureCode);
	}

	private void insertRecipient(long eventId, long recipientUserId) {
		jdbcTemplate.update(
			"insert into notification_outbox_recipients (outbox_event_id, recipient_user_id) values (?, ?)",
			eventId, recipientUserId);
	}

	private ListAppender<ILoggingEvent> attachRecoveryLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationOutboxRecoveryService.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachRecoveryLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationOutboxRecoveryService.class);
		logger.detachAppender(appender);
		appender.stop();
	}

	private record Fixture(long roomId, long recipientUserId) {
	}
}
