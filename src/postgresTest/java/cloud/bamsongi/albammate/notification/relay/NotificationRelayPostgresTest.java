package cloud.bamsongi.albammate.notification.relay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.notification.repository.NotificationOutboxEventRepository;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class, properties = "app.notification.relay.enabled=false")
class NotificationRelayPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final int RELAY_TEST_ADVISORY_LOCK_CLASS = 314;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_notification_relay_test");

	@Autowired
	private NotificationRelayExecutor executor;

	@Autowired
	private NotificationRelayFailureRecorder failureRecorder;

	@Autowired
	private NotificationRelayFailureClassifier failureClassifier;

	@Autowired
	private NotificationRelayCoordinator coordinator;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Autowired
	private NotificationOutboxEventRepository eventRepository;

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
	void WAITLIST_PROMOTED는_기존_알림이_있는_PENDING_재처리에도_수신자별_한건으로_수렴한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId(), "WAITLIST_PROMOTED");
		insertRecipient(eventId, fixture.firstRecipientUserId());
		insertExistingNotification(
			eventId, fixture.firstRecipientUserId(), fixture.roomId(), "WAITLIST_PROMOTED");

		executor.processOne().orElseThrow();

		assertEquals("WAITLIST_PROMOTED", jdbcTemplate.queryForObject(
			"select type from notifications where source_event_id = ?", String.class, eventId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from notifications where source_event_id = ? and recipient_user_id = ?",
			Integer.class, eventId, fixture.firstRecipientUserId()));
		assertEquals("PROCESSED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
	}

	@Test
	void 성공_구조화_로그는_PostgreSQL_트랜잭션_커밋_뒤_한번만_남긴다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());
		ListAppender<ILoggingEvent> appender = attachExecutorLogAppender();
		try {
			executor.processOne().orElseThrow();

			assertEquals(1, appender.list.size());
			assertEquals(Level.INFO, appender.list.getFirst().getLevel());
			assertTrue(appender.list.getFirst().getFormattedMessage()
				.contains("event=notification_outbox_relay_event_processed sourceEventId=" + eventId));
		} finally {
			detachExecutorLogAppender(appender);
		}
	}

	@Test
	void 커밋_시점에_롤백되면_성공_구조화_로그를_남기지_않는다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());
		installDeferredProcessedCommitFailureTrigger(eventId);
		ListAppender<ILoggingEvent> appender = attachExecutorLogAppender();
		try {
			Exception failure = assertThrows(Exception.class, executor::processOne);

			assertTrue(hasCauseMessage(failure, "notification relay test deferred commit failure"));
			assertTrue(appender.list.isEmpty());
			assertEquals("PENDING", jdbcTemplate.queryForObject(
				"select status from notification_outbox_events where id = ?", String.class, eventId));
			assertEquals(0, jdbcTemplate.queryForObject(
				"select count(*) from notifications where source_event_id = ?", Integer.class, eventId));
		} finally {
			detachExecutorLogAppender(appender);
			jdbcTemplate.execute(
				"drop trigger if exists notification_relay_fail_deferred_commit on notification_outbox_events");
			jdbcTemplate.execute("drop function if exists notification_relay_fail_deferred_commit()");
			jdbcTemplate.update("delete from notification_outbox_events where id = ?", eventId);
		}
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
	void 처리_가능한_적체의_가장_오래된_나이를_반환하고_적체가_없으면_null을_반환한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());

		Long oldestProcessableAgeMillis = eventRepository.findOldestProcessableAgeMillis();

		assertTrue(oldestProcessableAgeMillis >= 5_000L);
		executor.processOne().orElseThrow();
		assertNull(eventRepository.findOldestProcessableAgeMillis());
	}

	@Test
	void relay_컨텍스트_재기동_후_저장된_PENDING_이벤트를_처리한다() {
		Fixture fixture = createFixture();
		long eventId;
		try (ConfigurableApplicationContext existingContext = createRelayContext()) {
			assertFalse(existingContext.getBean(NotificationRelayProperties.class).isEnabled());
			eventId = insertPendingEvent(fixture.roomId());
			insertRecipient(eventId, fixture.firstRecipientUserId());
		}

		try (ConfigurableApplicationContext restartedContext = createRelayContext()) {
			assertFalse(restartedContext.getBean(NotificationRelayProperties.class).isEnabled());
			NotificationRelayExecutor restartedExecutor = restartedContext.getBean(NotificationRelayExecutor.class);
			NotificationRelayExecutor.ProcessedEvent processedEvent = restartedExecutor.processOne().orElseThrow();

			assertEquals(eventId, processedEvent.sourceEventId());
			assertEquals("PROCESSED", jdbcTemplate.queryForObject(
				"select status from notification_outbox_events where id = ?", String.class, eventId));
			assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from notifications where source_event_id = ?", Integer.class, eventId));
		}
	}

	@Test
	void 두_relay_worker는_선점_중인_due_이벤트를_건너뛰고_서로_다른_이벤트를_처리한다() throws Exception {
		Fixture fixture = createFixture();
		long firstEventId = insertPendingEvent(fixture.roomId());
		long secondEventId = insertPendingEvent(fixture.roomId());
		insertRecipient(firstEventId, fixture.firstRecipientUserId());
		insertRecipient(secondEventId, fixture.secondRecipientUserId());
		int advisoryLockKey = Math.toIntExact(firstEventId);
		ExecutorService workers = Executors.newFixedThreadPool(2);
		try (Connection advisoryLockConnection = dataSource.getConnection()) {
			acquireAdvisoryLock(advisoryLockConnection, advisoryLockKey);
			installFirstWorkerNotificationInsertGate(firstEventId, advisoryLockKey);
			Future<Optional<NotificationRelayExecutor.ProcessedEvent>> firstWorker = workers
				.submit(executor::processOne);
			awaitWorkerWaitingForAdvisoryLock(advisoryLockKey);

			Future<Optional<NotificationRelayExecutor.ProcessedEvent>> secondWorker = workers
				.submit(executor::processOne);
			NotificationRelayExecutor.ProcessedEvent secondProcessedEvent = secondWorker.get(5, TimeUnit.SECONDS)
				.orElseThrow();
			assertEquals(secondEventId, secondProcessedEvent.sourceEventId());

			releaseAdvisoryLock(advisoryLockConnection, advisoryLockKey);
			NotificationRelayExecutor.ProcessedEvent firstProcessedEvent = firstWorker.get(5, TimeUnit.SECONDS)
				.orElseThrow();
			assertEquals(firstEventId, firstProcessedEvent.sourceEventId());

			assertEquals("PROCESSED", jdbcTemplate.queryForObject(
				"select status from notification_outbox_events where id = ?", String.class, firstEventId));
			assertEquals("PROCESSED", jdbcTemplate.queryForObject(
				"select status from notification_outbox_events where id = ?", String.class, secondEventId));
			assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from notifications where source_event_id = ?", Integer.class, firstEventId));
			assertEquals(1, jdbcTemplate.queryForObject(
				"select count(*) from notifications where source_event_id = ?", Integer.class, secondEventId));
		} finally {
			workers.shutdownNow();
			workers.awaitTermination(5, TimeUnit.SECONDS);
			jdbcTemplate.execute("drop trigger if exists notification_relay_hold_first_worker on notifications");
			jdbcTemplate.execute("drop function if exists notification_relay_hold_first_worker()");
		}
	}

	private ConfigurableApplicationContext createRelayContext() {
		return new SpringApplicationBuilder(AlbamMateApplication.class)
			.run(
				"--server.port=0",
				"--app.notification.relay.enabled=false",
				"--spring.datasource.url=" + postgres.getJdbcUrl(),
				"--spring.datasource.username=" + postgres.getUsername(),
				"--spring.datasource.password=" + postgres.getPassword());
	}

	private void installFirstWorkerNotificationInsertGate(long firstEventId, int advisoryLockKey) {
		jdbcTemplate.execute("""
			create function notification_relay_hold_first_worker() returns trigger as $$
			begin
			    if new.source_event_id = %d then
			        perform pg_advisory_xact_lock(%d, %d);
			    end if;
			    return new;
			end;
			$$ language plpgsql
			""".formatted(firstEventId, RELAY_TEST_ADVISORY_LOCK_CLASS, advisoryLockKey));
		jdbcTemplate.execute("""
			create trigger notification_relay_hold_first_worker
			before insert on notifications
			for each row execute function notification_relay_hold_first_worker()
			""");
	}

	private void acquireAdvisoryLock(Connection connection, int advisoryLockKey) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_lock(?, ?)")) {
			statement.setInt(1, RELAY_TEST_ADVISORY_LOCK_CLASS);
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
				""", Boolean.class, RELAY_TEST_ADVISORY_LOCK_CLASS, advisoryLockKey);
			if (Boolean.TRUE.equals(waiting)) {
				return;
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("first relay worker did not reach the PostgreSQL advisory lock gate");
	}

	private void releaseAdvisoryLock(Connection connection, int advisoryLockKey) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("select pg_advisory_unlock(?, ?)")) {
			statement.setInt(1, RELAY_TEST_ADVISORY_LOCK_CLASS);
			statement.setInt(2, advisoryLockKey);
			statement.execute();
		}
	}

	@Test
	void 기존_Notification이_있는_PENDING_재처리는_누락_수신자만_보충한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());
		insertRecipient(eventId, fixture.secondRecipientUserId());
		insertExistingNotification(
			eventId, fixture.firstRecipientUserId(), fixture.roomId(), "PARTICIPANT_JOINED");

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
	void 일시_실패는_PostgreSQL_기록_시각에서_재시도하고_다섯번째_실패에_격리한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		NotificationRelayProcessingException failure = NotificationRelayProcessingException.failed(
			eventId, new IllegalStateException("temporary database failure"));

		for (int attempt = 1; attempt <= 5; attempt++) {
			NotificationRelayFailureRecorder.RecordedFailure recordedFailure = failureRecorder.record(failure)
				.orElseThrow();
			assertEquals(attempt, recordedFailure.failureCount());
			assertEquals(attempt, recordedFailure.totalFailureCount());
			if (attempt < 5) {
				assertTrue(recordedFailure.retryScheduled());
				Instant failedAt = jdbcTemplate.queryForObject(
					"select last_failed_at from notification_outbox_events where id = ?", Instant.class, eventId);
				Instant availableAt = jdbcTemplate.queryForObject(
					"select available_at from notification_outbox_events where id = ?", Instant.class, eventId);
				assertEquals(retryDelay(attempt), java.time.Duration.between(failedAt, availableAt));
				jdbcTemplate.update(
					"update notification_outbox_events set available_at = clock_timestamp() where id = ?", eventId);
			} else {
				assertFalse(recordedFailure.retryScheduled());
				assertFalse(recordedFailure.deterministicFailure());
			}
		}

		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
		assertNull(jdbcTemplate.queryForObject(
			"select available_at from notification_outbox_events where id = ?", Instant.class, eventId));
	}

	@Test
	void 결정적_데이터_오류는_첫_실패에_FAILED로_격리한다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());

		NotificationRelayFailureRecorder.RecordedFailure recordedFailure = failureRecorder.record(
			NotificationRelayProcessingException.failed(eventId,
				new org.springframework.dao.DataIntegrityViolationException("sensitive constraint detail")))
			.orElseThrow();

		assertEquals("DATA_CONSTRAINT_VIOLATION", recordedFailure.failureCode());
		assertTrue(recordedFailure.deterministicFailure());
		assertFalse(recordedFailure.retryScheduled());
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
	}

	@Test
	void 만료된_PENDING의_일시_실패는_최신_DB_시각으로_canonical_만료_실패로_덮어쓴다() {
		Fixture fixture = createFixture();
		long eventId = insertLongExpiredPendingEvent(fixture.roomId());

		NotificationRelayFailureRecorder.RecordedFailure recordedFailure = failureRecorder.record(
			NotificationRelayProcessingException.failed(eventId, new IllegalStateException("temporary failure")))
			.orElseThrow();
		NotificationRelayFailureClassifier.FailureClassification expired = failureClassifier.expiredClassification();

		assertEquals(expired.failureCode(), recordedFailure.failureCode());
		assertEquals(expired.failureClass(), recordedFailure.failureClass());
		assertTrue(recordedFailure.deterministicFailure());
		assertFalse(recordedFailure.retryScheduled());
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
		assertNull(jdbcTemplate.queryForObject(
			"select available_at from notification_outbox_events where id = ?", Instant.class, eventId));
		assertEquals(expired.sanitizedMessage(), jdbcTemplate.queryForObject(
			"select last_failure_message from notification_outbox_events where id = ?", String.class, eventId));
	}

	@Test
	void 존재하지_않는_이벤트의_실패는_변경과_afterCommit_로그를_남기지_않는다() {
		ListAppender<ILoggingEvent> appender = attachFailureLogAppender();
		try {
			Optional<NotificationRelayFailureRecorder.RecordedFailure> result = failureRecorder.record(
				NotificationRelayProcessingException.failed(Long.MAX_VALUE,
					new IllegalStateException("missing event")));

			assertTrue(result.isEmpty());
			assertTrue(appender.list.isEmpty());
		} finally {
			detachFailureLogAppender(appender);
		}
	}

	@Test
	void 만료_이벤트는_알림이나_PROCESSED를_만들지_않고_NOTIFICATION_EXPIRED로_격리한다() {
		Fixture fixture = createFixture();
		long eventId = insertExpiredPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());

		NotificationRelayProcessingException exception = assertThrows(
			NotificationRelayProcessingException.class, executor::processOne);
		NotificationRelayFailureRecorder.RecordedFailure recordedFailure = failureRecorder.record(exception)
			.orElseThrow();

		assertEquals(NotificationRelayProcessingException.FailureReason.EXPIRED, exception.getFailureReason());
		assertEquals(failureClassifier.expiredClassification().failureCode(), recordedFailure.failureCode());
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from notifications where source_event_id = ?", Integer.class, eventId));
		assertNull(jdbcTemplate.queryForObject(
			"select processed_at from notification_outbox_events where id = ?", Instant.class, eventId));
	}

	@Test
	void 늦은_실패_기록은_이미_완료된_PROCESSED_이벤트를_덮어쓰지_않는다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		insertRecipient(eventId, fixture.firstRecipientUserId());
		executor.processOne().orElseThrow();

		assertTrue(failureRecorder.record(NotificationRelayProcessingException.failed(
			eventId, new IllegalStateException("late failure"))).isEmpty());
		assertEquals("PROCESSED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, eventId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select failure_count from notification_outbox_events where id = ?", Integer.class, eventId));
	}

	@Test
	void poison_이벤트는_같은_batch의_뒤_정상_이벤트를_막지_않는다() {
		Fixture fixture = createFixture();
		long poisonEventId = insertPendingEvent(fixture.roomId());
		long successfulEventId = insertPendingEvent(fixture.roomId());
		insertRecipient(successfulEventId, fixture.firstRecipientUserId());

		NotificationRelayCoordinator.RelayBatchSummary summary = coordinator.processBatch();

		assertTrue(summary.failedCount() >= 1);
		assertTrue(summary.processedCount() >= 1);
		assertEquals("FAILED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, poisonEventId));
		assertEquals("PROCESSED", jdbcTemplate.queryForObject(
			"select status from notification_outbox_events where id = ?", String.class, successfulEventId));
	}

	@Test
	void 실패_저장과_로그는_원본_SQL과_민감_예외_메시지를_남기지_않는다() {
		Fixture fixture = createFixture();
		long eventId = insertPendingEvent(fixture.roomId());
		String sensitiveMessage = "select * from notifications where recipient=987654321 payload=relay-payload-sensitive";
		ListAppender<ILoggingEvent> appender = attachFailureLogAppender();
		try {
			failureRecorder.record(NotificationRelayProcessingException.failed(
				eventId, new org.springframework.dao.DataIntegrityViolationException(sensitiveMessage))).orElseThrow();

			String storedMessage = jdbcTemplate.queryForObject(
				"select last_failure_message from notification_outbox_events where id = ?", String.class, eventId);
			assertFalse(storedMessage.contains("select *"));
			assertFalse(storedMessage.contains("987654321"));
			assertEquals(1, appender.list.size());
			String loggedMessage = appender.list.getFirst().getFormattedMessage();
			assertFalse(loggedMessage.contains("select *"));
			assertFalse(loggedMessage.contains("987654321"));
			assertFalse(loggedMessage.contains("relay-payload-sensitive"));
		} finally {
			detachFailureLogAppender(appender);
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

	private void installDeferredProcessedCommitFailureTrigger(long eventId) {
		jdbcTemplate.execute("""
			create function notification_relay_fail_deferred_commit() returns trigger as $$
			begin
			    if new.id = %d and new.status = 'PROCESSED' then
			        raise exception 'notification relay test deferred commit failure';
			    end if;
			    return new;
			end;
			$$ language plpgsql
			""".formatted(eventId));
		jdbcTemplate.execute("""
			create constraint trigger notification_relay_fail_deferred_commit
			after update on notification_outbox_events
			deferrable initially deferred
			for each row execute function notification_relay_fail_deferred_commit()
			""");
	}

	private void insertExistingNotification(long eventId, long recipientUserId, long roomId, String notificationType) {
		jdbcTemplate.update("""
			with operation as materialized (select clock_timestamp() as operation_time)
			insert into notifications (
				source_event_id, recipient_user_id, room_id, type, created_at, recorded_at, expires_at)
			select ?, ?, ?, ?, operation_time - interval '1 minute', operation_time,
				operation_time - interval '1 minute' + interval '90 days'
			from operation
			""", eventId, recipientUserId, roomId, notificationType);
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
		return insertPendingEvent(roomId, "PARTICIPATION_JOINED");
	}

	private long insertPendingEvent(long roomId, String eventType) {
		return jdbcTemplate.queryForObject(
			"with operation as materialized (select clock_timestamp() - interval '5 seconds' as operation_time) "
				+ "insert into notification_outbox_events (event_type, room_id, occurred_at, recorded_at, status, available_at, "
				+ "failure_count, total_failure_count, reprocess_count) "
				+ "select ?, ?, operation_time - interval '1 minute', operation_time, 'PENDING', "
				+ "operation_time, 0, 0, 0 from operation returning id",
			Long.class, eventType, roomId);
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

	private long insertExpiredPendingEvent(long roomId) {
		return jdbcTemplate.queryForObject(
			"with operation as materialized (select clock_timestamp() as operation_time) "
				+ "insert into notification_outbox_events (event_type, room_id, occurred_at, recorded_at, status, available_at, "
				+ "failure_count, total_failure_count, reprocess_count) "
				+ "select 'PARTICIPATION_JOINED', ?, operation_time - interval '90 days', operation_time, 'PENDING', "
				+ "operation_time, 0, 0, 0 from operation returning id",
			Long.class,
			roomId);
	}

	private long insertLongExpiredPendingEvent(long roomId) {
		return jdbcTemplate.queryForObject(
			"with operation as materialized (select clock_timestamp() as operation_time) "
				+ "insert into notification_outbox_events (event_type, room_id, occurred_at, recorded_at, status, available_at, "
				+ "failure_count, total_failure_count, reprocess_count) "
				+ "select 'PARTICIPATION_JOINED', ?, operation_time - interval '91 days', operation_time, 'PENDING', "
				+ "operation_time, 0, 0, 0 from operation returning id",
			Long.class,
			roomId);
	}

	private java.time.Duration retryDelay(int failureCount) {
		return switch (failureCount) {
			case 1 -> java.time.Duration.ofSeconds(10);
			case 2 -> java.time.Duration.ofSeconds(30);
			case 3 -> java.time.Duration.ofMinutes(2);
			case 4 -> java.time.Duration.ofMinutes(10);
			default -> throw new IllegalArgumentException("retry delay exists only for first through fourth failure");
		};
	}

	private ListAppender<ILoggingEvent> attachFailureLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayFailureRecorder.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private ListAppender<ILoggingEvent> attachExecutorLogAppender() {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayExecutor.class);
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		return appender;
	}

	private void detachExecutorLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayExecutor.class);
		logger.detachAppender(appender);
		appender.stop();
	}

	private void detachFailureLogAppender(ListAppender<ILoggingEvent> appender) {
		Logger logger = (Logger)org.slf4j.LoggerFactory.getLogger(NotificationRelayFailureRecorder.class);
		logger.detachAppender(appender);
		appender.stop();
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
