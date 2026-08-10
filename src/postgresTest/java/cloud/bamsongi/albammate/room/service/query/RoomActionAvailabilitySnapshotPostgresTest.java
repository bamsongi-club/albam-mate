package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CompletableFuture;

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
import cloud.bamsongi.albammate.room.enums.MyRoomRole;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionScheduler;

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
	private MyRoomReadService myRoomReadService;
	@Autowired
	private RoomStatusCorrectionScheduler roomStatusCorrectionScheduler;
	@Autowired
	private WaitlistReadHook waitlistReadHook;
	@Autowired
	private ParticipationReadHook participationReadHook;
	@Autowired
	private RoomRepositoryReadHook roomRepositoryReadHook;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table room_waitlists, participations, rooms, users restart identity cascade");
	}

	@Test
	void ACTIVE_요청자_관계_뒤_커밋도_조건부_ACTIVE_목록_스냅샷에_섞지_않고_마지막_조회_뒤_락을_사용하지_않는다() {
		long hostUserId = user("snapshot-active-host@example.com");
		long requesterUserId = user("snapshot-active-requester@example.com");
		long laterParticipantUserId = user("snapshot-active-later@example.com");
		Room room = room(hostUserId);
		commitActiveParticipation(room.getId(), requesterUserId);

		participationReadHook.beforeDetailActiveListRead = () -> {
			assertReadOnlyRepeatableRead();
			commitActiveParticipation(room.getId(), laterParticipantUserId);
		};
		participationReadHook.afterDetailActiveListRead = this::assertNoRowReadLock;
		RoomDetailReadService.RoomDetailReadResult result = readCommitted()
			.execute(status -> roomDetailReadService.findRoomDetail(room.getId(), requesterUserId));

		assertEquals(1, result.activeParticipations().size());
		assertEquals(requesterUserId, result.activeParticipations().getFirst().getUserId());
		assertTrue(jdbcTemplate.queryForObject(
			"select exists(select 1 from participations where room_id = ? and user_id = ? and status = 'ACTIVE')",
			Boolean.class, room.getId(), laterParticipantUserId));
	}

	@Test
	void 외부_READ_COMMITTED_호출자도_목록과_상세_ReadService_proxy의_READ_ONLY_REPEATABLE_READ_스냅샷에_중간_WAITING_커밋을_섞지_않고_조회_락을_사용하지_않는다() {
		long hostUserId = user("snapshot-host@example.com");
		long requesterUserId = user("snapshot-requester@example.com");
		Room room = room(hostUserId);

		waitlistReadHook.beforeListWaitingRead = () -> {
			assertReadOnlyRepeatableRead();
			commitWaiting(room.getId(), requesterUserId);
		};
		waitlistReadHook.afterListWaitingRead = this::assertNoRowReadLock;
		RoomListReadService.RoomListReadResult listResult = readCommitted().execute(status -> {
			assertEquals("read committed", transactionIsolation());
			return roomListReadService.findPublicRoomsAt(
				new RoomListSearchCriteria(null, null, null, null, null, null, null, java.util.Set.of(), false),
				PageRequest.of(0, 10), requesterUserId, START_AT);
		});
		assertEquals(java.util.Set.of(), listResult.waitingRoomIds());

		long detailRequesterUserId = user("snapshot-detail-requester@example.com");
		waitlistReadHook.beforeDetailWaitingRead = () -> {
			assertReadOnlyRepeatableRead();
			commitWaiting(room.getId(), detailRequesterUserId);
		};
		waitlistReadHook.afterDetailWaitingRead = this::assertNoRowReadLock;
		RoomDetailReadService.RoomDetailReadResult detailResult = readCommitted().execute(status -> {
			assertEquals("read committed", transactionIsolation());
			return roomDetailReadService.findRoomDetail(room.getId(), detailRequesterUserId);
		});
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

	@Test
	void Scheduler_커밋과_동시에_목록과_내_모임은_각각_전후_하나의_REPEATABLE_READ_snapshot만_반환하고_조회_락을_사용하지_않는다() {
		long hostUserId = user("scheduler-snapshot-host@example.com");
		long waitingUserId = user("scheduler-snapshot-waiting@example.com");
		Room room = room(hostUserId);
		commitWaiting(room.getId(), waitingUserId);

		roomRepositoryReadHook.beforeActiveParticipationRead = () -> {
			assertReadOnlyRepeatableRead();
			runScheduler();
		};
		roomRepositoryReadHook.afterActiveParticipationRead = this::assertNoRowReadLock;
		RoomListReadService.RoomListReadResult publicResult = roomListReadService.findPublicRoomsAt(
			new RoomListSearchCriteria(null, null, null, null, null, null, null, java.util.Set.of(), false),
			PageRequest.of(0, 10), waitingUserId, START_AT);

		assertEquals(RoomStatus.RECRUITING, publicResult.rooms().getContent().getFirst().getStatus());
		assertEquals(RoomStatus.CLOSED, publicResult.effectiveStatusFor(publicResult.rooms().getContent().getFirst()));
		assertEquals(java.util.Set.of(room.getId()), publicResult.waitingRoomIds());
		assertEquals(RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals("EXPIRED", jdbcTemplate.queryForObject(
			"select status from room_waitlists where room_id = ? and user_id = ?", String.class, room.getId(),
			waitingUserId));

		roomRepositoryReadHook.beforeMyRoomPageRead = () -> {
			assertReadOnlyRepeatableRead();
			runScheduler();
		};
		roomRepositoryReadHook.afterMyRoomPageRead = this::assertNoRowReadLock;
		MyRoomReadService.MyRoomReadResult myResult = myRoomReadService.findMyRoomsAt(
			hostUserId, MyRoomRole.HOSTED, PageRequest.of(0, 10), START_AT);

		assertEquals(RoomStatus.CLOSED, myResult.rooms().getContent().getFirst().getStatus());
		assertEquals(RoomStatus.CLOSED, myResult.effectiveStatusFor(myResult.rooms().getContent().getFirst()));
	}

	private void runScheduler() {
		CompletableFuture.runAsync(roomStatusCorrectionScheduler::correctDueRooms).join();
	}

	private void assertNoRowReadLock() {
		Boolean usesRowReadLock = requiresNew().execute(status -> jdbcTemplate.queryForObject(
			"""
				select exists(
				    select 1
				    from pg_locks lock
				    join pg_class relation on relation.oid = lock.relation
				    where relation.relname in ('rooms', 'participations', 'room_waitlists')
				      and lock.mode in ('RowShareLock', 'ExclusiveLock', 'AccessExclusiveLock')
				)
				""",
			Boolean.class));
		assertFalse(Boolean.TRUE.equals(usesRowReadLock));
	}

	private void assertReadOnlyRepeatableRead() {
		assertEquals("repeatable read", transactionIsolation());
		assertEquals("on", jdbcTemplate.queryForObject("show transaction_read_only", String.class));
	}

	private String transactionIsolation() {
		return jdbcTemplate.queryForObject("show transaction_isolation", String.class);
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

	private void commitActiveParticipation(long roomId, long userId) {
		requiresNew().executeWithoutResult(status -> jdbcTemplate.update(
			"""
				insert into participations (room_id, user_id, status, joined_at, canceled_at, created_at, updated_at)
				values (?, ?, 'ACTIVE', ?, null, ?, ?)
				""",
			roomId, userId, START_AT.atOffset(ZoneOffset.UTC), START_AT.atOffset(ZoneOffset.UTC),
			START_AT.atOffset(ZoneOffset.UTC)));
	}

	private TransactionTemplate requiresNew() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return template;
	}

	private TransactionTemplate readCommitted() {
		TransactionTemplate template = new TransactionTemplate(transactionManager);
		template.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
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
		@Primary
		Clock fixedClock() {
			return Clock.fixed(START_AT, ZoneOffset.UTC);
		}

		@Bean
		WaitlistReadHook waitlistReadHook() {
			return new WaitlistReadHook();
		}

		@Bean
		ParticipationReadHook participationReadHook() {
			return new ParticipationReadHook();
		}

		@Bean
		RoomRepositoryReadHook roomRepositoryReadHook() {
			return new RoomRepositoryReadHook();
		}

		@Bean
		@Primary
		RoomRepository hookedRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate,
			RoomRepositoryReadHook hook) {
			return (RoomRepository)java.lang.reflect.Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(), new Class<?>[] {RoomRepository.class},
				(proxy, method, arguments) -> {
					if (method.getName().equals("findActiveParticipationRoomIds")
						&& hook.beforeActiveParticipationRead != null) {
						hook.beforeActiveParticipationRead.run();
						hook.beforeActiveParticipationRead = null;
					}
					if (method.getName().equals("findMyRoomsAt") && hook.beforeMyRoomPageRead != null) {
						hook.beforeMyRoomPageRead.run();
						hook.beforeMyRoomPageRead = null;
					}
					try {
						Object result = method.invoke(delegate, arguments);
						if (method.getName().equals("findActiveParticipationRoomIds")
							&& hook.afterActiveParticipationRead != null) {
							hook.afterActiveParticipationRead.run();
							hook.afterActiveParticipationRead = null;
						}
						if (method.getName().equals("findMyRoomsAt") && hook.afterMyRoomPageRead != null) {
							hook.afterMyRoomPageRead.run();
							hook.afterMyRoomPageRead = null;
						}
						return result;
					} catch (java.lang.reflect.InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
		}

		@Bean
		@Primary
		ParticipationRepository hookedParticipationRepository(
			@Qualifier("participationRepository") ParticipationRepository delegate,
			ParticipationReadHook hook) {
			return (ParticipationRepository)java.lang.reflect.Proxy.newProxyInstance(
				ParticipationRepository.class.getClassLoader(), new Class<?>[] {ParticipationRepository.class},
				(proxy, method, arguments) -> {
					try {
						Object result = method.invoke(delegate, arguments);
						if (method.getName().equals("findByRoomIdAndUserId")
							&& hook.beforeDetailActiveListRead != null) {
							hook.beforeDetailActiveListRead.run();
							hook.beforeDetailActiveListRead = null;
						}
						if (method.getName().equals("findByRoomIdAndStatusOrderByJoinedAtAscIdAsc")
							&& hook.afterDetailActiveListRead != null) {
							hook.afterDetailActiveListRead.run();
							hook.afterDetailActiveListRead = null;
						}
						return result;
					} catch (java.lang.reflect.InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
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
					if (method.getName().equals("findWaitingRoomIdsByUserIdAndRoomIds")) {
						if (hook.beforeListWaitingRead != null) {
							hook.beforeListWaitingRead.run();
							hook.beforeListWaitingRead = null;
						}
						if (hook.beforeDetailWaitingRead != null) {
							hook.beforeDetailWaitingRead.run();
							hook.beforeDetailWaitingRead = null;
						}
					}
					try {
						Object result = method.invoke(delegate, arguments);
						if (method.getName().equals("findWaitingRoomIdsByUserIdAndRoomIds")) {
							if (hook.afterListWaitingRead != null) {
								hook.afterListWaitingRead.run();
								hook.afterListWaitingRead = null;
							}
							if (hook.afterDetailWaitingRead != null) {
								hook.afterDetailWaitingRead.run();
								hook.afterDetailWaitingRead = null;
							}
						}
						return result;
					} catch (java.lang.reflect.InvocationTargetException exception) {
						throw exception.getCause();
					}
				});
		}
	}

	static class WaitlistReadHook {

		private Runnable beforeListWaitingRead;
		private Runnable beforeDetailWaitingRead;
		private Runnable afterListWaitingRead;
		private Runnable afterDetailWaitingRead;
	}

	static class RoomRepositoryReadHook {

		private Runnable beforeActiveParticipationRead;
		private Runnable afterActiveParticipationRead;
		private Runnable beforeMyRoomPageRead;
		private Runnable afterMyRoomPageRead;
	}

	static class ParticipationReadHook {

		private Runnable beforeDetailActiveListRead;
		private Runnable afterDetailActiveListRead;
	}
}
