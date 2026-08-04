package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

@Testcontainers
@SpringBootTest
@Import(RoomActionAvailabilitySnapshotPostgresTest.WaitlistHookConfiguration.class)
class RoomActionAvailabilitySnapshotPostgresTest {

	private static final Instant START_AT = Instant.parse("2099-01-01T10:00:00Z");

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("room_action_availability_snapshot_test");

	@Autowired
	private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private RoomListReadService roomListReadService;
	@Autowired
	private RoomDetailReadService roomDetailReadService;
	@Autowired
	private WaitlistReadHook waitlistReadHook;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table room_waitlists, participations, rooms, users restart identity cascade");
	}

	@Test
	void PostgreSQL_목록과_상세_ReadService_proxy는_중간_WAITING_커밋을_섞지_않고_조회_락을_사용하지_않는다() {
		long hostUserId = user("snapshot-host@example.com");
		long requesterUserId = user("snapshot-requester@example.com");
		Room room = room(hostUserId);

		waitlistReadHook.beforeListWaitingRead = () -> {
			commitWaiting(room.getId(), requesterUserId);
			assertNoRowReadLock();
		};
		RoomListReadService.RoomListReadResult listResult = roomListReadService.findPublicRooms(
			new RoomListSearchCriteria(null, null, null, null, null, null, java.util.Set.of(), false),
			PageRequest.of(0, 10), requesterUserId);
		assertEquals(java.util.Set.of(), listResult.waitingRoomIds());

		long detailRequesterUserId = user("snapshot-detail-requester@example.com");
		waitlistReadHook.beforeDetailWaitingRead = () -> {
			commitWaiting(room.getId(), detailRequesterUserId);
			assertNoRowReadLock();
		};
		RoomDetailReadService.RoomDetailReadResult detailResult = roomDetailReadService.findRoomDetail(
			room.getId(), detailRequesterUserId);
		assertFalse(detailResult.currentUserWaiting());
		assertTrue(jdbcTemplate.queryForObject(
			"select exists(select 1 from room_waitlists where room_id = ? and user_id = ? and status = 'WAITING')",
			Boolean.class,
			room.getId(), requesterUserId));
		assertTrue(jdbcTemplate.queryForObject(
			"select exists(select 1 from room_waitlists where room_id = ? and user_id = ? and status = 'WAITING')",
			Boolean.class,
			room.getId(), detailRequesterUserId));
	}

	private void assertNoRowReadLock() {
		Boolean usesRowReadLock = requiresNew().execute(status -> jdbcTemplate.queryForObject(
			"""
				select exists(
				    select 1
				    from pg_locks lock
				    join pg_class relation on relation.oid = lock.relation
				    where relation.relname = 'rooms'
				      and lock.mode in ('RowShareLock', 'ExclusiveLock', 'AccessExclusiveLock')
				)
				""",
			Boolean.class));
		assertFalse(Boolean.TRUE.equals(usesRowReadLock));
	}

	private void commitWaiting(long roomId, long userId) {
		requiresNew().executeWithoutResult(status -> jdbcTemplate.update(
			"""
				insert into room_waitlists (
				    room_id, user_id, status, queue_order, queued_at, created_at, updated_at)
				values (?, ?, 'WAITING', nextval('room_waitlist_queue_order_seq'), ?, ?, ?)
				""",
			roomId,
			userId,
			START_AT.atOffset(ZoneOffset.UTC),
			START_AT.atOffset(ZoneOffset.UTC),
			START_AT.atOffset(ZoneOffset.UTC)));
	}

	private TransactionTemplate requiresNew() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	private long user(String email) {
		return jdbcTemplate.queryForObject(
			"""
				insert into users (email, password_hash, nickname, created_at, updated_at)
				values (?, 'hash', '사용자', ?, ?) returning id
				""",
			Long.class, email, START_AT.atOffset(ZoneOffset.UTC), START_AT.atOffset(ZoneOffset.UTC));
	}

	private Room room(long hostUserId) {
		return roomRepository.saveAndFlush(Room.create(hostUserId, RoomType.PERSON_FOCUSED, "스냅샷 방", null, null,
			ExperienceLevel.ALL_LEVELS, false, START_AT, "서울", 2));
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class WaitlistHookConfiguration {

		@Bean
		WaitlistReadHook waitlistReadHook() {
			return new WaitlistReadHook();
		}

		@Bean
		@Primary
		RoomWaitlistRepository hookedRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			WaitlistReadHook hook) {
			return (RoomWaitlistRepository)java.lang.reflect.Proxy.newProxyInstance(
				RoomWaitlistRepository.class.getClassLoader(),
				new Class<?>[] {RoomWaitlistRepository.class},
				(proxy, method, arguments) -> {
					if (method.getName().equals("findWaitingRoomIdsByUserIdAndRoomIds")
						&& hook.beforeListWaitingRead != null) {
						hook.beforeListWaitingRead.run();
						hook.beforeListWaitingRead = null;
					}
					if (method.getName().equals("findStateWithPositionByRoomIdAndUserId")
						&& hook.beforeDetailWaitingRead != null) {
						hook.beforeDetailWaitingRead.run();
						hook.beforeDetailWaitingRead = null;
					}
					try {
						return method.invoke(delegate, arguments);
					} catch (java.lang.reflect.InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
		}
	}

	static class WaitlistReadHook {

		private Runnable beforeListWaitingRead;
		private Runnable beforeDetailWaitingRead;
	}
}
