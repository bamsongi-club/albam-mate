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
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomStatusChangeService;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

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
	private RoomStatusCorrectionCoordinator roomStatusCorrectionCoordinator;
	@Autowired
	private RoomStatusChangeService roomStatusChangeService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private MeterRegistry meterRegistry;
	@Autowired
	private QueueOrderConflictGate queueOrderConflictGate;
	@Autowired
	private ConditionalTransitionGate conditionalTransitionGate;

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
		conditionalTransitionGate.deactivate();
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
	void T3_대기열_진입_취소_FIFO_승격은_PostgreSQL_커밋_뒤_유한_metric과_불변식으로_수렴한다() {
		jdbcTemplate.update("update rooms set active_participant_count = capacity where id = ?", roomId);
		double joinsBefore = operationCount("join", "accepted");
		double cancelsBefore = operationCount("cancel", "accepted");
		double promotionsBefore = operationCount("promote", "accepted");

		roomWaitlistCommandService.register(secondUserId, roomId);
		roomWaitlistCommandService.cancel(secondUserId, roomId);
		roomWaitlistCommandService.register(thirdUserId, roomId);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbcTemplate.update(
			"insert into participations (room_id, user_id, status, joined_at, created_at, updated_at) values (?, ?, "
				+ "'ACTIVE', ?, ?, ?)",
			roomId,
			firstUserId,
			Timestamp.from(REQUEST_TIME),
			Timestamp.from(REQUEST_TIME),
			Timestamp.from(REQUEST_TIME)));

		roomParticipationCancelService.cancelParticipation(firstUserId, roomId);

		assertEquals(joinsBefore + 2.0, operationCount("join", "accepted"));
		assertEquals(cancelsBefore + 1.0, operationCount("cancel", "accepted"));
		assertEquals(promotionsBefore + 1.0, operationCount("promote", "accepted"));
		assertEquals("PROMOTED", waitlistStatus(thirdUserId));
		assertEquals(1, jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ? and status = 'ACTIVE'",
			Integer.class,
			roomId,
			thirdUserId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ? and status = 'ACTIVE'",
			Integer.class,
			roomId,
			firstUserId));
		assertTrue(meterRegistry.find("room.waitlist.operations").meters().stream()
			.allMatch(meter -> meter.getId().getTags().stream()
				.allMatch(tag -> "operation".equals(tag.getKey()) || "outcome".equals(tag.getKey()))));
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
				status -> roomWaitlistRepository.cancelWaiting(roomId, firstUserId, queueOrder,
					REQUEST_TIME.plusSeconds(60))),
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
	void T2_이전_순번의_취소와_승격은_별도_커밋된_재신청_WAITING을_전이하지_못한다() throws Exception {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, firstUserId, 10L, REQUEST_TIME));
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, secondUserId, 20L, REQUEST_TIME));
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, thirdUserId, 40L, REQUEST_TIME));
		});
		assertStaleCancelRejectedAfterPromotionAndReapply(firstUserId, 10L, 30L);
		assertStalePromotionRejectedAfterCancelAndReapply(thirdUserId, 40L, 50L);

		assertEquals("WAITING", waitlistStatus(firstUserId));
		assertEquals(30L, waitlistQueueOrder(firstUserId));
		assertEquals("WAITING", waitlistStatus(thirdUserId));
		assertEquals(50L, waitlistQueueOrder(thirdUserId));
		assertEquals(secondUserId, roomWaitlistRepository.findFirstWaitingByRoomId(roomId).orElseThrow().getUserId());
		assertEquals(3, jdbcTemplate.queryForObject(
			"select count(distinct queue_order) from room_waitlists where room_id = ? and status = 'WAITING'",
			Integer.class, roomId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id in (?, ?) and status = 'ACTIVE'",
			Integer.class, roomId, firstUserId, thirdUserId));
	}

	@Test
	void T3_시작_보정과_ROOM_취소가_만든_종료_대기는_재신청과_승격을_거절한다() {
		new TransactionTemplate(transactionManager).executeWithoutResult(
			status -> roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, firstUserId, 10L, REQUEST_TIME)));
		roomStatusCorrectionCoordinator.correctRoom(roomId, REQUEST_TIME.plusSeconds(3600));

		assertEquals("EXPIRED", waitlistStatus(firstUserId));
		assertWaitlistActivationAndPromotionRejected(roomId, firstUserId, 10L);

		long canceledRoomId = insertRoom(insertUser("concurrency-waitlist-canceled-host@example.com"));
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> roomWaitlistRepository
			.saveAndFlush(RoomWaitlist.create(canceledRoomId, secondUserId, 20L, REQUEST_TIME)));
		roomStatusChangeService.cancelRoom(
			jdbcTemplate.queryForObject("select host_user_id from rooms where id = ?", Long.class, canceledRoomId),
			canceledRoomId);

		assertEquals("ROOM_CANCELED", waitlistStatus(canceledRoomId, secondUserId));
		assertWaitlistActivationAndPromotionRejected(canceledRoomId, secondUserId, 20L);
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
				status -> roomWaitlistRepository.cancelWaiting(roomId, firstUserId, 10L,
					REQUEST_TIME.plusSeconds(60))),
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

	private void assertStaleCancelRejectedAfterPromotionAndReapply(
		long userId, long previousQueueOrder, long reapplyQueueOrder) throws Exception {
		conditionalTransitionGate.block("cancelWaiting", roomId, userId, previousQueueOrder);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Integer> staleCancel = executor.submit(() -> readThenCancel(userId));
			conditionalTransitionGate.awaitBlocked();
			Integer promoted = new TransactionTemplate(transactionManager).execute(status -> roomWaitlistRepository
				.promoteWaiting(roomId, userId, previousQueueOrder, REQUEST_TIME.plusSeconds(60)));
			assertEquals(1, promoted);
			Integer reactivated = new TransactionTemplate(transactionManager).execute(status -> roomWaitlistRepository
				.reactivateWaiting(roomId, userId, reapplyQueueOrder, REQUEST_TIME.plusSeconds(120)));
			assertEquals(1, reactivated);
			conditionalTransitionGate.release();
			assertEquals(0, staleCancel.get(WAIT_SECONDS, TimeUnit.SECONDS));
		} finally {
			conditionalTransitionGate.release();
			conditionalTransitionGate.deactivate();
			shutdown(executor);
		}
	}

	private void assertStalePromotionRejectedAfterCancelAndReapply(
		long userId, long previousQueueOrder, long reapplyQueueOrder) throws Exception {
		conditionalTransitionGate.block("promoteWaiting", roomId, userId, previousQueueOrder);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		try {
			Future<Integer> stalePromotion = executor.submit(() -> readThenPromote(userId));
			conditionalTransitionGate.awaitBlocked();
			Integer canceled = new TransactionTemplate(transactionManager).execute(status -> roomWaitlistRepository
				.cancelWaiting(roomId, userId, previousQueueOrder, REQUEST_TIME.plusSeconds(60)));
			assertEquals(1, canceled);
			Integer reactivated = new TransactionTemplate(transactionManager).execute(status -> roomWaitlistRepository
				.reactivateWaiting(roomId, userId, reapplyQueueOrder, REQUEST_TIME.plusSeconds(120)));
			assertEquals(1, reactivated);
			conditionalTransitionGate.release();
			assertEquals(0, stalePromotion.get(WAIT_SECONDS, TimeUnit.SECONDS));
		} finally {
			conditionalTransitionGate.release();
			conditionalTransitionGate.deactivate();
			shutdown(executor);
		}
	}

	private int readThenCancel(long userId) {
		return new TransactionTemplate(transactionManager).execute(status -> roomWaitlistRepository
			.findStateWithPositionByRoomIdAndUserId(roomId, userId)
			.map(waiting -> roomWaitlistRepository.cancelWaiting(
				roomId, userId, waiting.getQueueOrder(), REQUEST_TIME.plusSeconds(180)))
			.orElseThrow());
	}

	private int readThenPromote(long userId) {
		return new TransactionTemplate(transactionManager).execute(status -> roomWaitlistRepository
			.findStateWithPositionByRoomIdAndUserId(roomId, userId)
			.map(waiting -> roomWaitlistRepository.promoteWaiting(
				roomId, userId, waiting.getQueueOrder(), REQUEST_TIME.plusSeconds(180)))
			.orElseThrow());
	}

	private String waitlistStatus(long userId) {
		return waitlistStatus(roomId, userId);
	}

	private String waitlistStatus(long targetRoomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select status from room_waitlists where room_id = ? and user_id = ?", String.class, targetRoomId, userId);
	}

	private long waitlistQueueOrder(long userId) {
		return jdbcTemplate.queryForObject(
			"select queue_order from room_waitlists where room_id = ? and user_id = ?", Long.class, roomId, userId);
	}

	private double operationCount(String operation, String outcome) {
		Counter counter = meterRegistry.find("room.waitlist.operations")
			.tags("operation", operation, "outcome", outcome)
			.counter();
		return counter == null ? 0.0 : counter.count();
	}

	private void assertWaitlistActivationAndPromotionRejected(long targetRoomId, long userId, long queueOrder) {
		BusinessException registrationException = org.junit.jupiter.api.Assertions.assertThrows(
			BusinessException.class,
			() -> roomWaitlistCommandService.register(userId, targetRoomId));
		assertEquals(ErrorCode.WAITLIST_NOT_AVAILABLE, registrationException.getErrorCode());
		int promoted = new TransactionTemplate(transactionManager)
			.execute(status -> roomWaitlistRepository.promoteWaiting(
				targetRoomId, userId, queueOrder, REQUEST_TIME.plusSeconds(120)));
		assertEquals(0, promoted);
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

	private void shutdown(ExecutorService executor) throws InterruptedException {
		executor.shutdownNow();
		assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
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

		@Bean
		ConditionalTransitionGate conditionalTransitionGate() {
			return new ConditionalTransitionGate();
		}

		@Bean(name = "queueOrderConflictRoomWaitlistRepository")
		@Primary
		RoomWaitlistRepository queueOrderConflictRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			QueueOrderConflictGate queueOrderConflictGate,
			ConditionalTransitionGate conditionalTransitionGate) {
			InvocationHandler handler = new QueueOrderConflictRepositoryInvocationHandler(delegate,
				queueOrderConflictGate, conditionalTransitionGate);
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

	static final class ConditionalTransitionGate {

		private Scenario scenario;

		synchronized void block(String methodName, long roomId, long userId, long expectedQueueOrder) {
			if (scenario != null) {
				throw new IllegalStateException("조건부 전이 gate가 이미 활성화되어 있습니다.");
			}
			scenario = new Scenario(methodName, roomId, userId, expectedQueueOrder);
		}

		void beforeConditionalTransition(Method method, Object[] arguments) {
			Scenario activeScenario = currentScenario(method, arguments);
			if (activeScenario == null) {
				return;
			}
			activeScenario.blocked.countDown();
			await(activeScenario.release, "이전 순번 조건부 전이를 다시 시작하지 못했습니다.");
		}

		void awaitBlocked() {
			Scenario activeScenario;
			synchronized (this) {
				activeScenario = scenario;
			}
			if (activeScenario == null) {
				throw new IllegalStateException("대기할 조건부 전이 gate가 없습니다.");
			}
			await(activeScenario.blocked, "이전 순번 조건부 전이가 UPDATE 직전에 멈추지 않았습니다.");
		}

		synchronized void release() {
			if (scenario != null) {
				scenario.release.countDown();
			}
		}

		synchronized void deactivate() {
			release();
			scenario = null;
		}

		private synchronized Scenario currentScenario(Method method, Object[] arguments) {
			if (scenario == null || arguments == null || arguments.length < 3
				|| !scenario.methodName.equals(method.getName())
				|| !Long.valueOf(scenario.roomId).equals(arguments[0])
				|| !Long.valueOf(scenario.userId).equals(arguments[1])
				|| !Long.valueOf(scenario.expectedQueueOrder).equals(arguments[2])) {
				return null;
			}
			return scenario;
		}

		private void await(CountDownLatch latch, String timeoutMessage) {
			try {
				assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), timeoutMessage);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("조건부 전이 동기화 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final String methodName;
			private final long roomId;
			private final long userId;
			private final long expectedQueueOrder;
			private final CountDownLatch blocked = new CountDownLatch(1);
			private final CountDownLatch release = new CountDownLatch(1);

			private Scenario(String methodName, long roomId, long userId, long expectedQueueOrder) {
				this.methodName = methodName;
				this.roomId = roomId;
				this.userId = userId;
				this.expectedQueueOrder = expectedQueueOrder;
			}
		}
	}

	private static final class QueueOrderConflictRepositoryInvocationHandler implements InvocationHandler {

		private final RoomWaitlistRepository delegate;
		private final QueueOrderConflictGate queueOrderConflictGate;
		private final ConditionalTransitionGate conditionalTransitionGate;

		private QueueOrderConflictRepositoryInvocationHandler(
			RoomWaitlistRepository delegate,
			QueueOrderConflictGate queueOrderConflictGate,
			ConditionalTransitionGate conditionalTransitionGate) {
			this.delegate = delegate;
			this.queueOrderConflictGate = queueOrderConflictGate;
			this.conditionalTransitionGate = conditionalTransitionGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				conditionalTransitionGate.beforeConditionalTransition(method, arguments);
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
