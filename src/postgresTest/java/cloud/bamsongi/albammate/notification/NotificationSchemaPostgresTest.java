package cloud.bamsongi.albammate.notification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.Map;
import java.util.Set;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.notification.entity.Notification;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxEvent;
import cloud.bamsongi.albammate.notification.entity.NotificationOutboxRecipient;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class NotificationSchemaPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final String OPERATION_TIME = "TIMESTAMPTZ '2026-08-02T00:00:00Z'";
	private static final String OCCURRED_AT = "TIMESTAMPTZ '2026-08-01T12:00:00Z'";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_notification_test");

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private EntityManager entityManager;

	@Test
	void 빈_PostgreSQL에_V4_V5_알림_테이블_제약과_인덱스가_생성된다() {
		flyway.validate();

		Set<String> tables = Set.copyOf(jdbcTemplate.query(
			"select table_name from information_schema.tables where table_schema = current_schema()",
			(resultSet, rowNumber) -> resultSet.getString("table_name").toLowerCase()));
		assertTrue(tables.containsAll(Set.of(
			"notification_outbox_events", "notification_outbox_recipients", "notifications")));
		assertTrue(appliedVersions().containsAll(Set.of("4", "5")));

		Map<String, String> indexDefinitions = notificationIndexDefinitions();
		assertEquals(
			Set.of(
				"idx_notification_outbox_events_relay",
				"idx_notification_outbox_events_failed",
				"idx_notification_outbox_events_cleanup",
				"idx_notifications_recipient_created",
				"idx_notifications_recipient_unread",
				"idx_notifications_expiry"),
			indexDefinitions.keySet());
		assertIndexDefinition(
			indexDefinitions,
			"idx_notification_outbox_events_relay",
			"(available_at, id)",
			"WHERE",
			"status",
			"PENDING",
			"RETRY_WAIT");
		assertIndexDefinition(
			indexDefinitions,
			"idx_notification_outbox_events_failed",
			"(id)",
			"WHERE",
			"status",
			"FAILED");
		assertIndexDefinition(
			indexDefinitions,
			"idx_notification_outbox_events_cleanup",
			"(cleanup_at, id)",
			"WHERE",
			"cleanup_at",
			"IS NOT NULL");
		assertIndexDefinition(
			indexDefinitions,
			"idx_notifications_recipient_created",
			"(recipient_user_id, created_at DESC, id DESC)");
		assertIndexDefinition(
			indexDefinitions,
			"idx_notifications_recipient_unread",
			"(recipient_user_id, id)",
			"WHERE",
			"read_at",
			"IS NULL");
		assertIndexDefinition(indexDefinitions, "idx_notifications_expiry", "(expires_at, id)");
	}

	@Test
	void 이벤트_유형_상태_실패_재처리와_보존_CHECK가_잘못된_조합을_거절한다() {
		Fixture fixture = createFixture();
		String valid = validOutboxValues(fixture);

		assertConstraintViolation(
			"ck_notification_outbox_events_event_type",
			() -> insertOutbox(fixture, valid.replace("'PARTICIPATION_JOINED'", "'UNSUPPORTED'")));
		assertConstraintViolation(
			"ck_notification_outbox_events_status",
			() -> insertOutbox(fixture, valid.replace("'PENDING', " + OPERATION_TIME, "'PROCESSING', NULL")));
		assertConstraintViolation(
			"ck_notification_outbox_events_failure_counts",
			() -> insertOutbox(fixture, valid.replace("0, 0, NULL, NULL, NULL, NULL", "6, 6, NULL, NULL, NULL, NULL")));
		assertConstraintViolation(
			"ck_notification_outbox_events_available_at_status",
			() -> insertOutbox(fixture, valid.replace("'PENDING', " + OPERATION_TIME, "'PENDING', NULL")));
		assertConstraintViolation(
			"ck_notification_outbox_events_failure_details",
			() -> insertOutbox(fixture,
				valid.replace("0, 0, NULL, NULL, NULL, NULL", "0, 0, 'CODE', NULL, NULL, NULL")));
		assertConstraintViolation(
			"ck_notification_outbox_events_reprocess_details",
			() -> insertOutbox(fixture, valid.replace("'PENDING'", "'RETRY_WAIT'").replace(
				"NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL",
				"NULL, NULL, NULL, NULL, 1, NULL, NULL, NULL, NULL, NULL, NULL")));
		assertConstraintViolation(
			"ck_notification_outbox_events_pending",
			() -> insertOutbox(fixture, valid.replace("'PENDING', " + OPERATION_TIME, "'PENDING', " + OCCURRED_AT)));
		assertConstraintViolation(
			"ck_notification_outbox_events_retry_wait",
			() -> insertOutbox(fixture, valid.replace("'PENDING'", "'RETRY_WAIT'")));
		assertConstraintViolation(
			"ck_notification_outbox_events_failed_or_discarded_failure",
			() -> insertOutbox(fixture,
				valid.replace("'PENDING'", "'FAILED'").replace(", " + OPERATION_TIME + ", 0, 0", ", NULL, 0, 0")));
		assertConstraintViolation(
			"ck_notification_outbox_events_processed",
			() -> insertOutbox(fixture, processedOutboxValues(fixture, "NULL")));
		assertConstraintViolation(
			"ck_notification_outbox_events_discarded",
			() -> insertOutbox(fixture, discardedOutboxValues(fixture, "'   '")));
		assertConstraintViolation(
			"ck_notification_outbox_events_incomplete",
			() -> insertOutbox(fixture, valid.replace(
				", 0, NULL, NULL, NULL, NULL, NULL, NULL",
				", 0, NULL, NULL, " + OPERATION_TIME + ", NULL, NULL, NULL")));
	}

	@Test
	void Outbox와_Notification의_필수_FK가_잘못된_식별자를_거절한다() {
		Fixture fixture = createFixture();

		assertSqlConstraintViolation(
			"23503",
			"fk_notification_outbox_events_room",
			() -> insertOutbox(new Fixture(Long.MAX_VALUE, fixture.recipientUserId()), validOutboxValues(
				new Fixture(Long.MAX_VALUE, fixture.recipientUserId()))));

		long sourceEventId = insertOutbox(fixture, validOutboxValues(fixture));
		assertSqlConstraintViolation(
			"23503",
			"fk_notifications_recipient_user",
			() -> insertNotification(
				new Fixture(fixture.roomId(), Long.MAX_VALUE),
				sourceEventId,
				"PARTICIPANT_JOINED",
				"NULL",
				OCCURRED_AT + " + INTERVAL '90 days'"));
		assertSqlConstraintViolation(
			"23503",
			"fk_notifications_room",
			() -> insertNotification(
				new Fixture(Long.MAX_VALUE, fixture.recipientUserId()),
				sourceEventId,
				"PARTICIPANT_JOINED",
				"NULL",
				OCCURRED_AT + " + INTERVAL '90 days'"));
	}

	@Test
	void Notification_유형_읽음_보존과_멱등성_CHECK가_위반을_거절한다() {
		Fixture fixture = createFixture();
		long sourceEventId = insertOutbox(fixture, validOutboxValues(fixture));

		assertConstraintViolation(
			"ck_notifications_type",
			() -> insertNotification(
				fixture,
				sourceEventId + 1,
				"UNSUPPORTED",
				"NULL",
				OCCURRED_AT + " + INTERVAL '90 days'"));
		assertConstraintViolation(
			"ck_notifications_read_at",
			() -> insertNotification(
				fixture,
				sourceEventId + 2,
				"PARTICIPANT_JOINED",
				OCCURRED_AT,
				OCCURRED_AT + " + INTERVAL '90 days'"));
		assertConstraintViolation(
			"ck_notifications_expires_at",
			() -> insertNotification(fixture, sourceEventId + 3, "PARTICIPANT_JOINED", "NULL",
				OPERATION_TIME + " + INTERVAL '89 days'"));

		insertNotification(fixture, sourceEventId, "PARTICIPANT_JOINED", "NULL", OCCURRED_AT + " + INTERVAL '90 days'");
		assertSqlConstraintViolation(
			"23505",
			"uq_notifications_source_event_recipient",
			() -> insertNotification(fixture, sourceEventId, "PARTICIPANT_JOINED", "NULL",
				OCCURRED_AT + " + INTERVAL '90 days'"));
	}

	@Test
	void 수신자_복합키_FK와_Outbox_삭제_CASCADE를_적용한다() {
		Fixture fixture = createFixture();
		long outboxEventId = insertOutbox(fixture, validOutboxValues(fixture));
		jdbcTemplate.update(
			"insert into notification_outbox_recipients (outbox_event_id, recipient_user_id) values (?, ?)",
			outboxEventId,
			fixture.recipientUserId());

		assertSqlConstraintViolation(
			"23505",
			"notification_outbox_recipients_pkey",
			() -> jdbcTemplate.update(
				"insert into notification_outbox_recipients (outbox_event_id, recipient_user_id) values (?, ?)",
				outboxEventId,
				fixture.recipientUserId()));
		assertSqlConstraintViolation(
			"23503",
			"fk_notification_outbox_recipients_outbox_event",
			() -> jdbcTemplate.update(
				"insert into notification_outbox_recipients (outbox_event_id, recipient_user_id) values (?, ?)",
				Long.MAX_VALUE,
				fixture.recipientUserId()));
		assertSqlConstraintViolation(
			"23503",
			"fk_notification_outbox_recipients_user",
			() -> jdbcTemplate.update(
				"insert into notification_outbox_recipients (outbox_event_id, recipient_user_id) values (?, ?)",
				outboxEventId,
				Long.MAX_VALUE));

		jdbcTemplate.update("delete from notification_outbox_events where id = ?", outboxEventId);
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from notification_outbox_recipients where outbox_event_id = ?",
				Integer.class,
				outboxEventId));
	}

	@Test
	void Notification은_Outbox_FK없이_식별자만_저장하고_Entity_매핑을_검증한다() {
		assertFalse(
			jdbcTemplate.queryForObject(
				"select exists (select 1 from information_schema.key_column_usage kcu "
					+ "join information_schema.referential_constraints rc "
					+ "on rc.constraint_catalog = kcu.constraint_catalog "
					+ "and rc.constraint_schema = kcu.constraint_schema "
					+ "and rc.constraint_name = kcu.constraint_name "
					+ "where kcu.table_schema = current_schema() and kcu.table_name = 'notifications' "
					+ "and kcu.column_name = 'source_event_id')",
				Boolean.class));

		assertEntityAttributes(NotificationOutboxEvent.class, Set.of(
			"id", "eventType", "roomId", "occurredAt", "recordedAt", "status", "availableAt", "failureCount",
			"totalFailureCount", "lastFailureCode", "lastFailedAt", "lastFailureClass", "lastFailureMessage",
			"reprocessCount", "lastReprocessedAt", "lastReprocessReason", "processedAt", "discardedAt",
			"discardReason", "cleanupAt"));
		assertEntityAttributes(NotificationOutboxRecipient.class, Set.of("id"));
		assertEntityAttributes(Notification.class, Set.of(
			"id", "sourceEventId", "recipientUserId", "roomId", "type", "readAt", "createdAt", "recordedAt",
			"expiresAt"));
	}

	private Set<String> appliedVersions() {
		return Set.copyOf(jdbcTemplate.query(
			"select version from flyway_schema_history where success = true",
			(resultSet, rowNumber) -> resultSet.getString("version")));
	}

	private Map<String, String> notificationIndexDefinitions() {
		return jdbcTemplate.query(
			"select indexname, indexdef from pg_indexes where schemaname = current_schema() "
				+ "and indexname like 'idx_notification_%'",
			resultSet -> {
				Map<String, String> result = new java.util.HashMap<>();
				while (resultSet.next()) {
					result.put(resultSet.getString("indexname"), resultSet.getString("indexdef"));
				}
				return result;
			});
	}

	private void assertIndexDefinition(
		Map<String, String> indexDefinitions, String indexName, String columns, String... predicateTokens) {
		String indexDefinition = indexDefinitions.get(indexName);
		assertTrue(indexDefinition.contains(columns), () -> "Expected index columns: " + indexDefinition);
		for (String predicateToken : predicateTokens) {
			assertTrue(indexDefinition.contains(predicateToken), () -> "Expected index predicate: " + indexDefinition);
		}
	}

	private void assertEntityAttributes(Class<?> entityClass, Set<String> expectedAttributes) {
		EntityType<?> entityType = entityManager.getMetamodel().entity(entityClass);
		assertEquals(
			expectedAttributes,
			entityType.getAttributes().stream().map(attribute -> attribute.getName())
				.collect(java.util.stream.Collectors.toSet()));
	}

	private Fixture createFixture() {
		long hostUserId = jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (concat('host-', nextval('users_id_seq'), '@example.com'), 'hash', '주최자', now(), now()) returning id",
			Long.class);
		long recipientUserId = jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (concat('recipient-', nextval('users_id_seq'), '@example.com'), 'hash', '수신자', now(), now()) returning id",
			Long.class);
		long roomId = jdbcTemplate.queryForObject(
			"insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity, "
				+ "active_participant_count, start_at, place, status, created_at, updated_at) "
				+ "values (?, 'PERSON_FOCUSED', '알림 스키마 검증 방', 'ALL_LEVELS', false, 1, 0, now(), '홍대', "
				+ "'RECRUITING', now(), now()) returning id",
			Long.class,
			hostUserId);
		return new Fixture(roomId, recipientUserId);
	}

	private String validOutboxValues(Fixture fixture) {
		return "'PARTICIPATION_JOINED', " + fixture.roomId() + ", " + OCCURRED_AT + ", " + OPERATION_TIME
			+ ", 'PENDING', " + OPERATION_TIME
			+ ", 0, 0, NULL, NULL, NULL, NULL, 0, NULL, NULL, NULL, NULL, NULL, NULL";
	}

	private String processedOutboxValues(Fixture fixture, String cleanupAt) {
		return "'PARTICIPATION_JOINED', " + fixture.roomId() + ", " + OCCURRED_AT + ", " + OPERATION_TIME
			+ ", 'PROCESSED', NULL, 0, 0, NULL, NULL, NULL, NULL, 0, NULL, NULL, " + OPERATION_TIME
			+ ", NULL, NULL, " + cleanupAt;
	}

	private String discardedOutboxValues(Fixture fixture, String discardReason) {
		return "'PARTICIPATION_JOINED', " + fixture.roomId() + ", " + OCCURRED_AT + ", " + OPERATION_TIME
			+ ", 'DISCARDED', NULL, 0, 1, 'CODE', " + OPERATION_TIME
			+ ", 'Failure', 'message', 0, NULL, NULL, NULL, " + OPERATION_TIME + ", " + discardReason
			+ ", " + OPERATION_TIME + " + INTERVAL '30 days'";
	}

	private long insertOutbox(Fixture fixture, String values) {
		return jdbcTemplate.queryForObject(
			"insert into notification_outbox_events (event_type, room_id, occurred_at, recorded_at, status, available_at, "
				+ "failure_count, total_failure_count, last_failure_code, last_failed_at, last_failure_class, last_failure_message, "
				+ "reprocess_count, last_reprocessed_at, last_reprocess_reason, processed_at, discarded_at, discard_reason, cleanup_at) "
				+ "values (" + values + ") returning id",
			Long.class);
	}

	private void insertNotification(
		Fixture fixture, long sourceEventId, String type, String readAt, String expiresAt) {
		jdbcTemplate.update(
			"insert into notifications (source_event_id, recipient_user_id, room_id, type, read_at, created_at, recorded_at, expires_at) "
				+ "values (?, ?, ?, ?, " + readAt + ", " + OCCURRED_AT + ", " + OPERATION_TIME + ", " + expiresAt + ")",
			sourceEventId,
			fixture.recipientUserId(),
			fixture.roomId(),
			type);
	}

	private void assertConstraintViolation(String expectedConstraint,
		org.junit.jupiter.api.function.Executable operation) {
		assertSqlConstraintViolation("23514", expectedConstraint, operation);
	}

	private void assertSqlConstraintViolation(
		String expectedSqlState, String expectedConstraint, org.junit.jupiter.api.function.Executable operation) {
		DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation);
		SQLException sqlException = findSqlException(exception);
		assertEquals(expectedSqlState, sqlException.getSQLState());
		assertTrue(
			containsMessage(exception, expectedConstraint),
			() -> "Expected PostgreSQL constraint in exception: " + expectedConstraint);
	}

	private SQLException findSqlException(Throwable throwable) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current instanceof SQLException sqlException) {
				return sqlException;
			}
		}
		throw new AssertionError("Expected a PostgreSQL SQLException cause", throwable);
	}

	private boolean containsMessage(Throwable throwable, String expectedText) {
		for (Throwable current = throwable; current != null; current = current.getCause()) {
			if (current.getMessage() != null && current.getMessage().contains(expectedText)) {
				return true;
			}
		}
		return false;
	}

	private record Fixture(long roomId, long recipientUserId) {
	}
}
