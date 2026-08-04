package cloud.bamsongi.albammate.chat.retention;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;

@Testcontainers
@SpringBootTest(properties = "app.chat.retention.enabled=false")
class ChatMessageRetentionPostgresTest {

	private static final String MIGRATION_SCHEMA = "retention_migration_test";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("albam_mate_chat_retention_test");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private ChatMessageRetentionCoordinator coordinator;
	@Autowired
	private ChatMessageRetentionProperties properties;
	@MockitoSpyBean
	private ChatMessageRetentionStore store;
	@Autowired
	private ScheduledTaskLock scheduledTaskLock;

	@AfterEach
	void retention_테스트_데이터를_정리한다() {
		jdbcTemplate.execute("drop schema if exists " + MIGRATION_SCHEMA + " cascade");
		properties.setMaxRoomsPerRun(50);
		properties.setMaxMessagesPerRun(5_000);
		properties.setMessageChunkSize(100);
		jdbcTemplate.update(
			"delete from chat_messages where sender_user_id in (select id from users where email like 'retention-%')");
		jdbcTemplate.update(
			"delete from chat_rooms where room_id in (select id from rooms where host_user_id in (select id from users where email like 'retention-%'))");
		jdbcTemplate
			.update("delete from rooms where host_user_id in (select id from users where email like 'retention-%')");
		jdbcTemplate.update("delete from users where email like 'retention-%'");
	}

	@Test
	void V11_로컬_초기화는_기존_행을_보존하고_상태별_값을_하나의_PostgreSQL_기준_시각으로_설정한다() {
		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.schemas(MIGRATION_SCHEMA)
			.defaultSchema(MIGRATION_SCHEMA)
			.createSchemas(true)
			.locations("classpath:db/migration", "classpath:db/vendor-migration/postgresql")
			.target("10")
			.load()
			.migrate();
		long userId = migrationUser();
		long recruitingRoomId = migrationRoom(userId, "RECRUITING", "recruiting");
		long closedRoomId = migrationRoom(userId, "CLOSED", "closed");
		long canceledRoomId = migrationRoom(userId, "CANCELED", "canceled");
		long finishedRoomId = migrationRoom(userId, "FINISHED", "finished");
		Instant preservedPurgeAfter = Instant.parse("2026-01-01T00:00:00Z");
		jdbcTemplate.update(
			"""
				insert into retention_migration_test.chat_rooms (room_id, purge_after, messages_purged_at, created_at, updated_at)
				values (?, ?, null, ?, ?)
				""",
			closedRoomId, timestamp(preservedPurgeAfter), timestamp(preservedPurgeAfter),
			timestamp(preservedPurgeAfter));

		Flyway.configure()
			.dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
			.schemas(MIGRATION_SCHEMA)
			.defaultSchema(MIGRATION_SCHEMA)
			.locations("classpath:db/migration", "classpath:db/vendor-migration/postgresql")
			.load()
			.migrate();

		assertNull(migrationTimestamp(recruitingRoomId, "purge_after"));
		assertNull(migrationTimestamp(recruitingRoomId, "messages_purged_at"));
		assertEquals(preservedPurgeAfter, migrationTimestamp(closedRoomId, "purge_after"));
		assertNull(migrationTimestamp(closedRoomId, "messages_purged_at"));
		assertEquals(migrationTimestamp(canceledRoomId, "purge_after"),
			migrationTimestamp(canceledRoomId, "messages_purged_at"));
		assertEquals(migrationTimestamp(finishedRoomId, "purge_after"),
			migrationTimestamp(finishedRoomId, "messages_purged_at"));
		assertEquals(migrationTimestamp(canceledRoomId, "purge_after"),
			migrationTimestamp(finishedRoomId, "purge_after"));
		assertEquals(4,
			jdbcTemplate.queryForObject("select count(*) from retention_migration_test.chat_rooms", Integer.class));
		assertEquals(0,
			jdbcTemplate.queryForObject("select count(*) from retention_migration_test.shedlock", Integer.class));
	}

	@Test
	void 최종_상태_전환_30일_뒤_메시지는_삭제되고_완료_시각을_기록한다() {
		Fixture fixture = createFixture("one", "FINISHED", Instant.now().minusSeconds(31L * 24 * 60 * 60));
		insertMessage(fixture.chatRoomId(), fixture.userId(), "one");

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertEquals(1, summary.purgedRoomCount());
		assertEquals(1, summary.deletedMessageCount());
		assertEquals(0, countMessages(fixture.chatRoomId()));
		assertTrue(messagesPurgedAt(fixture.chatRoomId()) != null);
	}

	@Test
	void 만료_방은_purgeAfter와_chatRoomId_오름차순_keyset_page로_조회한다() {
		Instant dueTime = Instant.now().minusSeconds(31L * 24 * 60 * 60);
		Fixture first = createFixture("first", "CANCELED", dueTime.minusSeconds(1));
		Fixture second = createFixture("second", "FINISHED", dueTime);
		Fixture third = createFixture("third", "FINISHED", dueTime);

		Instant referenceTime = Instant.now();
		List<ChatMessageRetentionStore.DueChatRoom> dueChatRooms = store.findDueChatRooms(referenceTime, null, 2);
		List<ChatMessageRetentionStore.DueChatRoom> nextDueChatRooms = store.findDueChatRooms(
			referenceTime, ChatMessageRetentionStore.DueChatRoomCursor.after(dueChatRooms.getLast()), 2);

		assertEquals(List.of(first.chatRoomId(), second.chatRoomId()),
			dueChatRooms.stream().map(ChatMessageRetentionStore.DueChatRoom::chatRoomId).toList());
		assertEquals(List.of(third.chatRoomId()),
			nextDueChatRooms.stream().map(ChatMessageRetentionStore.DueChatRoom::chatRoomId).toList());
	}

	@Test
	void 두_로컬_실행은_같은_ShedLock에서_하나만_작업을_소유하고_다른_실행은_skip한다() throws Exception {
		CountDownLatch lockOwnerStarted = new CountDownLatch(1);
		CountDownLatch releaseLockOwner = new CountDownLatch(1);
		ExecutorService executorService = Executors.newFixedThreadPool(2);
		try {
			Future<ScheduledTaskLock.LockExecution> owner = executorService.submit(() -> scheduledTaskLock.tryExecute(
				"chat-message-retention-postgres-test", java.time.Duration.ofSeconds(10), () -> {
					lockOwnerStarted.countDown();
					await(releaseLockOwner);
				}));
			assertTrue(lockOwnerStarted.await(5, TimeUnit.SECONDS));

			ScheduledTaskLock.LockExecution skipped = executorService.submit(() -> scheduledTaskLock.tryExecute(
				"chat-message-retention-postgres-test", java.time.Duration.ofSeconds(10), () -> {
					throw new AssertionError("skipped execution must not run");
				})).get(5, TimeUnit.SECONDS);

			assertFalse(skipped.acquired());
			releaseLockOwner.countDown();
			assertTrue(owner.get(5, TimeUnit.SECONDS).acquired());
		} finally {
			releaseLockOwner.countDown();
			executorService.shutdownNow();
			executorService.awaitTermination(5, TimeUnit.SECONDS);
		}
	}

	@Test
	void 겹친_삭제_실행은_messagesPurgedAt_조건으로_최종_상태에_수렴한다() throws Exception {
		Fixture fixture = createFixture("overlap", "FINISHED", Instant.now().minusSeconds(31L * 24 * 60 * 60));
		for (int index = 0; index < 150; index++) {
			insertMessage(fixture.chatRoomId(), fixture.userId(), "overlap-" + index);
		}
		CountDownLatch firstReadsReady = new CountDownLatch(2);
		AtomicInteger firstReadCount = new AtomicInteger();
		doAnswer(invocation -> {
			@SuppressWarnings("unchecked") List<Long> messageIds = (List<Long>)invocation.callRealMethod();
			if (firstReadCount.getAndIncrement() < 2) {
				firstReadsReady.countDown();
				await(firstReadsReady);
			}
			return messageIds;
		}).when(store).findNextMessageIds(eq(fixture.chatRoomId()), anyInt());
		ExecutorService executorService = Executors.newFixedThreadPool(2);
		try {
			Future<ChatMessageRetentionCoordinator.RetentionRunSummary> first = executorService
				.submit(coordinator::purgeExpiredMessages);
			Future<ChatMessageRetentionCoordinator.RetentionRunSummary> second = executorService
				.submit(coordinator::purgeExpiredMessages);
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		} finally {
			executorService.shutdownNow();
			executorService.awaitTermination(5, TimeUnit.SECONDS);
		}

		assertEquals(0, countMessages(fixture.chatRoomId()));
		assertTrue(messagesPurgedAt(fixture.chatRoomId()) != null);
	}

	@Test
	void 잠금_임대가_만료돼_실행이_겹쳐도_재실행은_완료된_방을_다시_처리하지_않는다() throws Exception {
		Fixture fixture = createFixture("lease-expired", "FINISHED", Instant.now().minusSeconds(31L * 24 * 60 * 60));
		insertMessage(fixture.chatRoomId(), fixture.userId(), "lease-expired");
		CountDownLatch firstExecutionCompletedWork = new CountDownLatch(1);
		CountDownLatch releaseFirstExecution = new CountDownLatch(1);
		ExecutorService executorService = Executors.newSingleThreadExecutor();
		try {
			Future<ScheduledTaskLock.LockExecution> first = executorService.submit(() -> scheduledTaskLock.tryExecute(
				"chat-message-retention-lease-expiry-test", java.time.Duration.ofMillis(100), () -> {
					coordinator.purgeExpiredMessages();
					firstExecutionCompletedWork.countDown();
					await(releaseFirstExecution);
				}));
			assertTrue(firstExecutionCompletedWork.await(5, TimeUnit.SECONDS));
			Thread.sleep(300);

			ScheduledTaskLock.LockExecution overlapping = scheduledTaskLock.tryExecute(
				"chat-message-retention-lease-expiry-test", java.time.Duration.ofMillis(100),
				coordinator::purgeExpiredMessages);

			assertTrue(overlapping.acquired());
			releaseFirstExecution.countDown();
			assertTrue(first.get(5, TimeUnit.SECONDS).acquired());
		} finally {
			releaseFirstExecution.countDown();
			executorService.shutdownNow();
			executorService.awaitTermination(5, TimeUnit.SECONDS);
		}

		assertEquals(0, countMessages(fixture.chatRoomId()));
		assertTrue(messagesPurgedAt(fixture.chatRoomId()) != null);
	}

	@Test
	void 한_chunk_실패는_이전_방의_성공을_되돌리지_않고_다음_실행_대상으로_남긴다() {
		Instant dueTime = Instant.now().minusSeconds(31L * 24 * 60 * 60);
		Fixture successfulRoom = createFixture("successful", "FINISHED", dueTime.minusSeconds(1));
		Fixture failedRoom = createFixture("failed", "FINISHED", dueTime);
		insertMessage(successfulRoom.chatRoomId(), successfulRoom.userId(), "successful");
		insertMessage(failedRoom.chatRoomId(), failedRoom.userId(), "failed");
		jdbcTemplate.execute("""
			create function retention_fail_chunk_delete() returns trigger as $$
			begin
			    if old.chat_room_id = %d then
			        raise exception 'retention chunk failure';
			    end if;
			    return old;
			end;
			$$ language plpgsql
			""".formatted(failedRoom.chatRoomId()));
		jdbcTemplate.execute("""
			create trigger retention_fail_chunk_delete
			before delete on chat_messages
			for each row execute function retention_fail_chunk_delete()
			""");
		try {
			ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

			assertEquals(1, summary.purgedRoomCount());
			assertEquals(1, summary.failureCount());
			assertEquals(0, countMessages(successfulRoom.chatRoomId()));
			assertTrue(messagesPurgedAt(successfulRoom.chatRoomId()) != null);
			assertEquals(1, countMessages(failedRoom.chatRoomId()));
			assertNull(messagesPurgedAt(failedRoom.chatRoomId()));
		} finally {
			jdbcTemplate.execute("drop trigger if exists retention_fail_chunk_delete on chat_messages");
			jdbcTemplate.execute("drop function if exists retention_fail_chunk_delete()");
		}
	}

	@Test
	void 대표_로컬_PostgreSQL_배치는_50개_방_5000개_메시지를_처리한다() {
		Instant dueTime = Instant.now().minusSeconds(31L * 24 * 60 * 60);
		Fixture lastFixture = null;
		for (int roomIndex = 0; roomIndex < 50; roomIndex++) {
			Fixture fixture = createFixture("benchmark-" + roomIndex, "FINISHED", dueTime);
			lastFixture = fixture;
			for (int messageIndex = 0; messageIndex < 100; messageIndex++) {
				insertMessage(fixture.chatRoomId(), fixture.userId(), "benchmark-" + roomIndex + "-" + messageIndex);
			}
		}

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertEquals(50, summary.purgedRoomCount());
		assertEquals(5_000, summary.deletedMessageCount());
		assertEquals(0, summary.failureCount());
		assertTrue(messagesPurgedAt(lastFixture.chatRoomId()) != null);
	}

	@Test
	void 실행당_메시지_후보_batch는_같은_cron_주기에_남은_방까지_소진한다() {
		properties.setMaxRoomsPerRun(2);
		properties.setMaxMessagesPerRun(3);
		properties.setMessageChunkSize(2);
		Instant dueTime = Instant.now().minusSeconds(31L * 24 * 60 * 60);
		Fixture limitedRoom = createFixture("limited", "FINISHED", dueTime.minusSeconds(1));
		Fixture nextRoom = createFixture("next", "FINISHED", dueTime);
		for (int messageIndex = 0; messageIndex < 5; messageIndex++) {
			insertMessage(limitedRoom.chatRoomId(), limitedRoom.userId(), "limited-" + messageIndex);
		}
		insertMessage(nextRoom.chatRoomId(), nextRoom.userId(), "next");

		ChatMessageRetentionCoordinator.RetentionRunSummary summary = coordinator.purgeExpiredMessages();

		assertEquals(2, summary.purgedRoomCount());
		assertEquals(6, summary.deletedMessageCount());
		assertEquals(0, countMessages(limitedRoom.chatRoomId()));
		assertTrue(messagesPurgedAt(limitedRoom.chatRoomId()) != null);
		assertEquals(0, countMessages(nextRoom.chatRoomId()));
		assertTrue(messagesPurgedAt(nextRoom.chatRoomId()) != null);
	}

	private Fixture createFixture(String suffix, String status, Instant purgeAfter) {
		Instant now = Instant.now();
		long userId = jdbcTemplate.queryForObject("""
			insert into users (email, password_hash, nickname, created_at, updated_at)
			values (?, 'hash', ?, ?, ?) returning id
			""", Long.class, "retention-" + suffix + "@example.com", "retention-" + suffix, timestamp(now),
			timestamp(now));
		long roomId = jdbcTemplate.queryForObject("""
			insert into rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity,
			active_participant_count, start_at, place, status, created_at, updated_at)
			values (?, 'PERSON_FOCUSED', ?, 'ALL_LEVELS', false, '서울', 1, 0, ?, '테스트', ?, ?, ?) returning id
			""", Long.class, userId, "retention-" + suffix, timestamp(now.plusSeconds(3600)), status, timestamp(now),
			timestamp(now));
		long chatRoomId = jdbcTemplate.queryForObject("""
			insert into chat_rooms (room_id, purge_after, messages_purged_at, created_at, updated_at)
			values (?, ?, null, ?, ?) returning id
			""", Long.class, roomId, timestamp(purgeAfter), timestamp(now), timestamp(now));
		return new Fixture(userId, roomId, chatRoomId);
	}

	private long migrationUser() {
		return jdbcTemplate.queryForObject("""
			insert into retention_migration_test.users (email, password_hash, nickname, created_at, updated_at)
			values ('migration@example.com', 'hash', 'migration', current_timestamp, current_timestamp) returning id
			""", Long.class);
	}

	private long migrationRoom(long userId, String status, String suffix) {
		return jdbcTemplate.queryForObject(
			"""
				insert into retention_migration_test.rooms (host_user_id, room_type, title, experience_level, is_rulemaster_led,
				region, capacity, active_participant_count, start_at, place, status, created_at, updated_at)
				values (?, 'PERSON_FOCUSED', ?, 'ALL_LEVELS', false, '서울', 1, 0, current_timestamp, '테스트', ?,
				current_timestamp, current_timestamp) returning id
				""",
			Long.class, userId, suffix, status);
	}

	private Instant migrationTimestamp(long roomId, String column) {
		return jdbcTemplate.queryForObject(
			"select " + column + " from retention_migration_test.chat_rooms where room_id = ?", Instant.class, roomId);
	}

	private void insertMessage(long chatRoomId, long userId, String clientMessageId) {
		jdbcTemplate.update("""
			insert into chat_messages (chat_room_id, sender_user_id, client_message_id, content, created_at)
			values (?, ?, ?, 'message', ?)
			""", chatRoomId, userId, clientMessageId, timestamp(Instant.now()));
	}

	private int countMessages(long chatRoomId) {
		return jdbcTemplate.queryForObject("select count(*) from chat_messages where chat_room_id = ?", Integer.class,
			chatRoomId);
	}

	private Instant messagesPurgedAt(long chatRoomId) {
		return jdbcTemplate.queryForObject("select messages_purged_at from chat_rooms where id = ?", Instant.class,
			chatRoomId);
	}

	private Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(5, TimeUnit.SECONDS)) {
				throw new AssertionError("lock owner was not released");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("interrupted while waiting for lock owner release", exception);
		}
	}

	private record Fixture(long userId, long roomId, long chatRoomId) {
	}
}
