package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;

@Testcontainers
@SpringBootTest
@Import(RoomWaitlistConcurrencyPostgresTest.WaitlistQueueConflictConfiguration.class)
class RoomWaitlistConcurrencyPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant REQUEST_TIME = Instant.parse("2026-08-04T00:00:00Z");
	private static final long WAIT_SECONDS = 10;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_waitlist_concurrency_test");

	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private DataSource dataSource;
	@Autowired
	private RoomWaitlistCommandService roomWaitlistCommandService;
	@Autowired
	private QueueOrderConflictGate queueOrderConflictGate;

	private long roomId;
	private long firstUserId;
	private long secondUserId;
	private long thirdUserId;

	@BeforeEach
	void setUp() {
		long hostUserId = insertUser("concurrency-waitlist-host@example.com");
		firstUserId = insertUser("concurrency-waitlist-first@example.com");
		secondUserId = insertUser("concurrency-waitlist-second@example.com");
		thirdUserId = insertUser("concurrency-waitlist-third@example.com");
		roomId = insertRoom(hostUserId);
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table room_waitlists, rooms, users restart identity cascade");
	}

	@Test
	void 동시_신규_저장과_조건부_승격은_한_행과_FIFO_단일_전이로_수렴한다() throws Exception {
		CountDownLatch claimStarted = new CountDownLatch(2);
		List<Boolean> saved = executeConcurrently(
			() -> saveAfterRoomClaim(firstUserId, claimStarted),
			() -> saveAfterRoomClaim(secondUserId, claimStarted));

		assertEquals(1, saved.stream().filter(Boolean::booleanValue).count());
		int promotedCount = new TransactionTemplate(transactionManager).execute(status -> {
			long queueOrder = jdbcTemplate.queryForObject(
				"select queue_order from room_waitlists where room_id = ?", Long.class, roomId);
			return roomWaitlistRepository.promoteWaiting(roomId, firstUserId, queueOrder, REQUEST_TIME.plusSeconds(60))
				+ roomWaitlistRepository.promoteWaiting(roomId, secondUserId, queueOrder, REQUEST_TIME.plusSeconds(60));
		});
		assertEquals(1, promotedCount);
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from room_waitlists where room_id = ?", Integer.class, roomId));
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from room_waitlists where room_id = ? and status = 'WAITING'",
				Integer.class,
				roomId));
	}

	@Test
	void 동시_취소_재신청_승격은_조건부_전이_하나만_남긴다() throws Exception {
		long queueOrder = new TransactionTemplate(transactionManager).execute(status -> {
			long nextQueueOrder = roomWaitlistRepository.getNextQueueOrder();
			roomWaitlistRepository.saveAndFlush(
				RoomWaitlist.create(roomId, firstUserId, nextQueueOrder, REQUEST_TIME));
			return nextQueueOrder;
		});

		List<Integer> firstTransitionResults = executeConcurrentlyWhileWaitlistLocked(
			() -> new TransactionTemplate(transactionManager).execute(
				status -> roomWaitlistRepository.cancelWaiting(roomId, firstUserId, REQUEST_TIME.plusSeconds(60))),
			() -> new TransactionTemplate(transactionManager).execute(status -> roomWaitlistRepository.promoteWaiting(
				roomId,
				firstUserId,
				queueOrder,
				REQUEST_TIME.plusSeconds(60))));
		assertEquals(1, firstTransitionResults.stream().mapToInt(Integer::intValue).sum());

		List<Integer> reapplyResults = executeConcurrentlyWhileWaitlistLocked(
			() -> new TransactionTemplate(transactionManager).execute(status -> {
				long nextQueueOrder = roomWaitlistRepository.getNextQueueOrder();
				return roomWaitlistRepository.reactivateWaiting(roomId, firstUserId, nextQueueOrder,
					REQUEST_TIME.plusSeconds(120));
			}),
			() -> new TransactionTemplate(transactionManager).execute(status -> {
				long nextQueueOrder = roomWaitlistRepository.getNextQueueOrder();
				return roomWaitlistRepository.reactivateWaiting(roomId, firstUserId, nextQueueOrder,
					REQUEST_TIME.plusSeconds(120));
			}));
		assertEquals(1, reapplyResults.stream().mapToInt(Integer::intValue).sum());
		assertEquals(
			"WAITING",
			jdbcTemplate.queryForObject(
				"select status from room_waitlists where room_id = ? and user_id = ?",
				String.class,
				roomId,
				firstUserId));
		assertEquals(
			1,
			jdbcTemplate.queryForObject(
				"select count(*) from room_waitlists where room_id = ? and user_id = ?",
				Integer.class,
				roomId,
				firstUserId));
	}

	@Test
	void 첫_대기자_취소와_승격_경쟁뒤에는_다음_FIFO_후보만_승격한다() throws Exception {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, firstUserId, 10L, REQUEST_TIME));
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, secondUserId, 20L, REQUEST_TIME));
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, thirdUserId, 30L, REQUEST_TIME));
		});

		executeConcurrentlyWhileWaitlistLocked(
			() -> new TransactionTemplate(transactionManager).execute(
				status -> roomWaitlistRepository.cancelWaiting(roomId, firstUserId, REQUEST_TIME.plusSeconds(60))),
			() -> new TransactionTemplate(transactionManager).execute(status -> roomWaitlistRepository
				.promoteWaiting(roomId, firstUserId, 10L, REQUEST_TIME.plusSeconds(60))));
		int promoted = new TransactionTemplate(transactionManager).execute(status -> roomWaitlistRepository
			.findFirstWaitingByRoomId(roomId)
			.map(candidate -> roomWaitlistRepository.promoteWaiting(roomId, candidate.getUserId(),
				candidate.getQueueOrder(), REQUEST_TIME.plusSeconds(120)))
			.orElse(0));

		assertEquals(1, promoted);
		assertEquals(20L, jdbcTemplate.queryForObject(
			"select queue_order from room_waitlists where room_id = ? and user_id = ?", Long.class, roomId,
			secondUserId));
		assertEquals("PROMOTED", jdbcTemplate.queryForObject(
			"select status from room_waitlists where room_id = ? and user_id = ?", String.class, roomId, secondUserId));
		assertEquals("WAITING", jdbcTemplate.queryForObject(
			"select status from room_waitlists where room_id = ? and user_id = ?", String.class, roomId, thirdUserId));
	}

	@Test
	void claim과_순번_발급_뒤_실패하면_방과_대기행은_롤백되고_순번만_공백으로_남는다() {
		long consumedQueueOrder = new TransactionTemplate(transactionManager).execute(status -> {
			assertEquals(1, roomRepository.claimVersion(roomId, 0L));
			long queueOrder = roomWaitlistRepository.getNextQueueOrder();
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, firstUserId, queueOrder, REQUEST_TIME));
			status.setRollbackOnly();
			return queueOrder;
		});

		assertEquals(0L, jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId));
		assertEquals(0, jdbcTemplate.queryForObject("select count(*) from room_waitlists where room_id = ?",
			Integer.class, roomId));
		assertTrue(roomWaitlistRepository.getNextQueueOrder() > consumedQueueOrder);
	}

	@Test
	void T5_실제_WAITING_순번_UNIQUE_충돌은_독립_세번_시도마다_롤백한다() {
		jdbcTemplate.update("update rooms set active_participant_count = capacity, status = 'CLOSED' where id = ?",
			roomId);
		new TransactionTemplate(transactionManager).executeWithoutResult(
			status -> roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, firstUserId, 10L, REQUEST_TIME)));
		queueOrderConflictGate.returnDuplicateQueueOrder(10L);

		BusinessException exception = new TransactionTemplate(transactionManager).execute(status -> {
			BusinessException thrown = org.junit.jupiter.api.Assertions.assertThrows(
				BusinessException.class,
				() -> roomWaitlistCommandService.register(secondUserId, roomId));
			assertTrue(!status.isRollbackOnly());
			return thrown;
		});

		assertEquals(ErrorCode.INTERNAL_SERVER_ERROR, exception.getErrorCode());
		assertEquals(3, queueOrderConflictGate.getIssuedDuplicateQueueOrderCount());
		assertEquals(0L, jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from room_waitlists where room_id = ? and user_id = ?", Integer.class, roomId,
			secondUserId));
	}

	private boolean saveAfterRoomClaim(long userId, CountDownLatch claimStarted) throws Exception {
		return new TransactionTemplate(transactionManager).execute(status -> {
			Long version = jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId);
			claimStarted.countDown();
			awaitClaims(claimStarted);
			if (roomRepository.claimVersion(roomId, version) == 0) {
				return false;
			}
			long queueOrder = roomWaitlistRepository.getNextQueueOrder();
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, userId, queueOrder, REQUEST_TIME));
			return true;
		});
	}

	private <T> List<T> executeConcurrently(Callable<T> first, Callable<T> second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<T> firstFuture = executor.submit(first);
			Future<T> secondFuture = executor.submit(second);
			return List.of(firstFuture.get(WAIT_SECONDS, TimeUnit.SECONDS),
				secondFuture.get(WAIT_SECONDS, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
		}
	}

	private <T> List<T> executeConcurrentlyWhileWaitlistLocked(Callable<T> first, Callable<T> second) throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try (Connection lockHolder = dataSource.getConnection()) {
			lockHolder.setAutoCommit(false);
			lockWaitlist(lockHolder);
			Future<T> firstFuture = executor.submit(first);
			Future<T> secondFuture = executor.submit(second);
			awaitWorkersWaitingForWaitlistLock(2);
			lockHolder.commit();
			return List.of(firstFuture.get(WAIT_SECONDS, TimeUnit.SECONDS),
				secondFuture.get(WAIT_SECONDS, TimeUnit.SECONDS));
		} finally {
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
		}
	}

	private void lockWaitlist(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(
			"select room_id from room_waitlists where room_id = ? and user_id = ? for update")) {
			statement.setLong(1, roomId);
			statement.setLong(2, firstUserId);
			statement.executeQuery();
		}
	}

	private void awaitWorkersWaitingForWaitlistLock(int expectedWorkerCount) {
		long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
		while (System.nanoTime() < deadlineNanos) {
			Integer waitingWorkerCount = jdbcTemplate.queryForObject("""
				select count(*)
				from pg_stat_activity
				where datname = current_database()
				  and wait_event_type = 'Lock'
				  and query like '%update room_waitlists%'
				""", Integer.class);
			if (waitingWorkerCount != null && waitingWorkerCount >= expectedWorkerCount) {
				return;
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("두 waitlist UPDATE worker가 행 잠금을 기다리지 않았습니다.");
	}

	private void awaitClaims(CountDownLatch claimStarted) {
		try {
			assertTrue(claimStarted.await(WAIT_SECONDS, TimeUnit.SECONDS));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("동시 시작 대기 중 인터럽트되었습니다.", exception);
		}
	}

	private long insertUser(String email) {
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			email,
			Timestamp.from(REQUEST_TIME),
			Timestamp.from(REQUEST_TIME));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private long insertRoom(long hostUserId) {
		jdbcTemplate.update(
			"""
				insert into rooms (
				    host_user_id, room_type, title, experience_level, is_rulemaster_led, region, capacity,
				    active_participant_count, start_at, place, status, version, created_at, updated_at)
				values (?, 'PERSON_FOCUSED', '동시성 대기열 방', 'ALL_LEVELS', false, '홍대', 4, 0, ?, '테스트 장소',
				        'RECRUITING', 0, ?, ?)
				""",
			hostUserId,
			Timestamp.from(REQUEST_TIME.plusSeconds(3600)),
			Timestamp.from(REQUEST_TIME),
			Timestamp.from(REQUEST_TIME));
		return jdbcTemplate.queryForObject("select id from rooms where host_user_id = ?", Long.class, hostUserId);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class WaitlistQueueConflictConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(REQUEST_TIME, ZoneOffset.UTC);
		}

		@Bean
		QueueOrderConflictGate queueOrderConflictGate() {
			return new QueueOrderConflictGate();
		}

		@Bean(name = "queueOrderConflictRoomWaitlistRepository")
		@Primary
		RoomWaitlistRepository queueOrderConflictRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			QueueOrderConflictGate queueOrderConflictGate) {
			InvocationHandler handler = new QueueOrderConflictRepositoryInvocationHandler(delegate,
				queueOrderConflictGate);
			return (RoomWaitlistRepository)Proxy.newProxyInstance(
				RoomWaitlistRepository.class.getClassLoader(),
				new Class<?>[] {RoomWaitlistRepository.class},
				handler);
		}
	}

	static final class QueueOrderConflictGate {

		private Long duplicateQueueOrder;
		private int issuedDuplicateQueueOrderCount;

		void returnDuplicateQueueOrder(long queueOrder) {
			duplicateQueueOrder = queueOrder;
			issuedDuplicateQueueOrderCount = 0;
		}

		long replaceIssuedQueueOrder(long issuedQueueOrder) {
			if (duplicateQueueOrder == null) {
				return issuedQueueOrder;
			}
			issuedDuplicateQueueOrderCount++;
			return duplicateQueueOrder;
		}

		int getIssuedDuplicateQueueOrderCount() {
			return issuedDuplicateQueueOrderCount;
		}
	}

	private static final class QueueOrderConflictRepositoryInvocationHandler implements InvocationHandler {

		private final RoomWaitlistRepository delegate;
		private final QueueOrderConflictGate queueOrderConflictGate;

		private QueueOrderConflictRepositoryInvocationHandler(
			RoomWaitlistRepository delegate, QueueOrderConflictGate queueOrderConflictGate) {
			this.delegate = delegate;
			this.queueOrderConflictGate = queueOrderConflictGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				Object result = method.invoke(delegate, arguments);
				if (method.getName().equals("getNextQueueOrder")) {
					return queueOrderConflictGate.replaceIssuedQueueOrder((long)result);
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}
}
