package cloud.bamsongi.albammate.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.user.contract.UserRowLockPort;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class UserRowLockPortPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final long WAIT_SECONDS = 10;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_user_row_lock_test");

	@Autowired
	private UserRowLockPort userRowLockPort;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table users restart identity cascade");
	}

	@Test
	void 입력_순서와_없는_ID에_관계없이_존재한_사용자만_같은_순서로_잠가_직렬화한다() throws Exception {
		long firstUserId = insertUser("first");
		long secondUserId = insertUser("second");
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		CountDownLatch transactionsReady = new CountDownLatch(2);
		CountDownLatch startTransactions = new CountDownLatch(1);
		CountDownLatch anyTransactionLocked = new CountDownLatch(1);
		CountDownLatch allTransactionsLocked = new CountDownLatch(2);
		CountDownLatch releaseTransactions = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);

		try {
			Future<Set<Long>> firstFuture = executor.submit(
				() -> transactionTemplate.execute(status -> {
					transactionsReady.countDown();
					await(startTransactions);
					Set<Long> lockedUserIds = userRowLockPort.lockExistingUsersInAscendingOrder(
						List.of(secondUserId, 999_999L, firstUserId));
					anyTransactionLocked.countDown();
					allTransactionsLocked.countDown();
					await(releaseTransactions);
					return lockedUserIds;
				}));

			Future<Set<Long>> secondFuture = executor.submit(
				() -> transactionTemplate.execute(
					status -> {
						transactionsReady.countDown();
						await(startTransactions);
						Set<Long> lockedUserIds = userRowLockPort.lockExistingUsersInAscendingOrder(
							List.of(firstUserId, secondUserId));
						anyTransactionLocked.countDown();
						allTransactionsLocked.countDown();
						await(releaseTransactions);
						return lockedUserIds;
					}));

			assertTrue(
				transactionsReady.await(WAIT_SECONDS, TimeUnit.SECONDS),
				"두 트랜잭션이 동시성 시작 지점에 도달하지 못했습니다.");
			startTransactions.countDown();
			assertTrue(
				anyTransactionLocked.await(WAIT_SECONDS, TimeUnit.SECONDS),
				"두 트랜잭션 중 어느 것도 사용자 행 잠금을 획득하지 못했습니다.");
			assertFalse(
				allTransactionsLocked.await(1, TimeUnit.SECONDS),
				"두 번째 트랜잭션이 첫 번째 트랜잭션의 잠금 해제 전에 완료됐습니다.");
			releaseTransactions.countDown();
			assertEquals(
				List.of(firstUserId, secondUserId),
				List.copyOf(firstFuture.get(WAIT_SECONDS, TimeUnit.SECONDS)));
			assertEquals(
				List.of(firstUserId, secondUserId),
				List.copyOf(secondFuture.get(WAIT_SECONDS, TimeUnit.SECONDS)));
		} finally {
			startTransactions.countDown();
			releaseTransactions.countDown();
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
		}
	}

	@Test
	void 낮은_ID_행부터_실제로_잠가_높은_ID_경합_중에도_잠금_순서를_보장한다() throws Exception {
		long lowUserId = insertUser("low");
		long highUserId = insertUser("high");
		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		CountDownLatch highRowLocked = new CountDownLatch(1);
		CountDownLatch releaseHighRow = new CountDownLatch(1);
		CountDownLatch targetStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<Void> blockerFuture = executor.submit(
			() -> transactionTemplate.execute(status -> {
				jdbcTemplate.queryForObject(
					"select id from users where id = ? for update", Long.class, highUserId);
				highRowLocked.countDown();
				await(releaseHighRow);
				return null;
			}));
		Future<Set<Long>> targetFuture = null;

		try {
			assertTrue(
				highRowLocked.await(WAIT_SECONDS, TimeUnit.SECONDS),
				"높은 ID 사용자 행을 선행 잠그지 못했습니다.");
			targetFuture = executor.submit(
				() -> transactionTemplate.execute(status -> {
					targetStarted.countDown();
					return userRowLockPort.lockExistingUsersInAscendingOrder(
						List.of(highUserId, lowUserId));
				}));
			assertTrue(
				targetStarted.await(WAIT_SECONDS, TimeUnit.SECONDS),
				"대상 잠금 트랜잭션이 시작되지 않았습니다.");
			assertTrue(
				observeRowLockedByAnotherTransaction(lowUserId),
				"높은 ID 행에서 경합 중인 동안 낮은 ID 행이 먼저 잠기지 않았습니다.");

			releaseHighRow.countDown();
			assertEquals(
				List.of(lowUserId, highUserId),
				List.copyOf(targetFuture.get(WAIT_SECONDS, TimeUnit.SECONDS)));
			blockerFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
		} finally {
			releaseHighRow.countDown();
			if (targetFuture != null) {
				targetFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			}
			blockerFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			executor.shutdownNow();
			assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
		}
	}

	private long insertUser(String role) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			"row-lock-" + role + "-" + UUID.randomUUID() + "@example.com",
			"잠금 " + role);
	}

	private void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "동시성 동기화 지점에 도달하지 못했습니다.");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("동시성 동기화 대기 중 인터럽트되었습니다.", exception);
		}
	}

	private boolean observeRowLockedByAnotherTransaction(long userId) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
		while (System.nanoTime() < deadline) {
			if (!tryLockRowNowait(userId)) {
				return true;
			}
			try {
				Thread.sleep(10);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("행 잠금 관찰 중 인터럽트되었습니다.", exception);
			}
		}
		return false;
	}

	private boolean tryLockRowNowait(long userId) {
		try {
			jdbcTemplate.queryForObject(
				"select id from users where id = ? for update nowait", Long.class, userId);
			return true;
		} catch (DataAccessException exception) {
			if (isLockNotAvailable(exception)) {
				return false;
			}
			throw exception;
		}
	}

	private boolean isLockNotAvailable(DataAccessException exception) {
		Throwable cause = exception;
		while (cause != null) {
			if (cause instanceof SQLException sqlException && "55P03".equals(sqlException.getSQLState())) {
				return true;
			}
			cause = cause.getCause();
		}
		return false;
	}
}
