package cloud.bamsongi.albammate.matching;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchPartyChatWriteGuardPostgresTest extends SharedPostgresIntegrationSupport {

	private static final long WAIT_SECONDS = 10;

	@Autowired
	private ApplicationContext applicationContext;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute(
			"truncate table match_chat_messages, match_chat_rooms, match_party_participants, match_parties, users restart identity cascade");
	}

	@Test
	void Guard는_ACTIVE_현재참가자만_통과시키고_호출자_트랜잭션_종료까지_Party_잠금을_유지한다() throws Exception {
		long memberId = insertUser("member");
		long formerMemberId = insertUser("former-member");
		long outsiderId = insertUser("outsider");
		long partyId = insertActiveParty();
		long preparingPartyId = insertPreparingParty();
		insertParticipant(partyId, memberId, false);
		insertParticipant(partyId, formerMemberId, true);
		insertParticipant(preparingPartyId, memberId, false);

		Class<?> guardType = Class.forName(
			"cloud.bamsongi.albammate.matching.contract.MatchPartyChatWriteGuard");
		Object guard = applicationContext.getBean(guardType);
		Method executeWithActiveAccess = guardType.getMethod(
			"executeWithActiveAccess", long.class, long.class, Supplier.class);
		assertEquals("allowed", invokeGuard(executeWithActiveAccess, guard, memberId, partyId, () -> "allowed"));
		AtomicBoolean formerSupplierExecuted = new AtomicBoolean(false);
		assertForbidden(
			() -> invokeGuard(
				executeWithActiveAccess,
				guard,
				formerMemberId,
				partyId,
				() -> {
					formerSupplierExecuted.set(true);
					return "forbidden";
				}));
		assertFalse(formerSupplierExecuted.get());
		assertForbidden(() -> invokeGuard(executeWithActiveAccess, guard, outsiderId, partyId, () -> null));
		AtomicBoolean preparingSupplierExecuted = new AtomicBoolean(false);
		assertNotActive(
			() -> invokeGuard(
				executeWithActiveAccess,
				guard,
				memberId,
				preparingPartyId,
				() -> {
					preparingSupplierExecuted.set(true);
					return "not-active";
				}));
		assertFalse(preparingSupplierExecuted.get());

		TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
		CountDownLatch guardReturned = new CountDownLatch(1);
		CountDownLatch releaseTransaction = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> guardFuture = executor.submit(
				() -> transactionTemplate.executeWithoutResult(status -> {
					invokeGuard(executeWithActiveAccess, guard, memberId, partyId, () -> {
						guardReturned.countDown();
						return null;
					});
					await(releaseTransaction);
				}));
			await(guardReturned);

			Future<?> closeFuture = executor.submit(
				() -> jdbcTemplate.update(
					"update match_parties set status = 'CLOSED', closed_at = current_timestamp, "
						+ "purge_after = current_timestamp + interval '7 days', chat_opened_at = null, closes_at = null where id = ?",
					partyId));
			assertFalse(closeFuture.isDone(), "Guard가 유지한 Party 잠금 중 상태 전환이 완료됐습니다.");
			assertThrows(TimeoutException.class, () -> closeFuture.get(1, TimeUnit.SECONDS));

			releaseTransaction.countDown();
			guardFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			closeFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
		} finally {
			releaseTransaction.countDown();
			shutdown(executor);
		}
		assertForbidden(() -> invokeGuard(executeWithActiveAccess, guard, memberId, partyId, () -> null));

		long rollbackPartyId = insertActiveParty();
		long roomId = insertChatRoom(rollbackPartyId);
		insertParticipant(rollbackPartyId, memberId, false);
		assertThrows(
			IllegalStateException.class,
			() -> transactionTemplate.executeWithoutResult(status -> invokeGuard(
				executeWithActiveAccess,
				guard,
				memberId,
				rollbackPartyId,
				() -> {
					jdbcTemplate.update(
						"insert into match_chat_messages "
							+ "(match_chat_room_id, sender_user_id, message_type, client_message_id, content, created_at) "
							+ "values (?, ?, 'USER', ?, 'rollback check', current_timestamp)",
						roomId,
						memberId,
						UUID.randomUUID().toString());
					throw new IllegalStateException("rollback check");
				})));
		assertEquals(
			0,
			jdbcTemplate.queryForObject(
				"select count(*) from match_chat_messages where match_chat_room_id = ?",
				Integer.class,
				roomId));
	}

	private Object invokeGuard(Method method, Object guard, long userId, long partyId, Supplier<?> operation) {
		try {
			return method.invoke(guard, userId, partyId, operation);
		} catch (IllegalAccessException exception) {
			throw new AssertionError(exception);
		} catch (InvocationTargetException exception) {
			Throwable cause = exception.getCause();
			if (cause instanceof RuntimeException runtimeException) {
				throw runtimeException;
			}
			throw new AssertionError(cause);
		}
	}

	private void assertForbidden(org.junit.jupiter.api.function.Executable operation) {
		BusinessException exception = assertThrows(BusinessException.class, operation);
		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	private void assertNotActive(org.junit.jupiter.api.function.Executable operation) {
		BusinessException exception = assertThrows(BusinessException.class, operation);
		assertEquals(ErrorCode.MATCH_CHAT_NOT_ACTIVE, exception.getErrorCode());
	}

	private long insertUser(String role) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, current_timestamp, current_timestamp) returning id",
			Long.class,
			"match-guard-" + role + "-" + UUID.randomUUID() + "@example.com",
			"매칭 " + role);
	}

	private long insertActiveParty() {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, chat_opened_at, closes_at, created_at, updated_at) "
				+ "values ('ACTIVE', current_timestamp, current_timestamp, current_timestamp + interval '1 day', current_timestamp, current_timestamp) returning id",
			Long.class);
	}

	private long insertPreparingParty() {
		return jdbcTemplate.queryForObject(
			"insert into match_parties (status, preparing_started_at, created_at, updated_at) "
				+ "values ('PREPARING', current_timestamp, current_timestamp, current_timestamp) returning id",
			Long.class);
	}

	private long insertChatRoom(long partyId) {
		return jdbcTemplate.queryForObject(
			"insert into match_chat_rooms (party_id, created_at, updated_at) values (?, current_timestamp, current_timestamp) returning id",
			Long.class,
			partyId);
	}

	private void insertParticipant(long partyId, long userId, boolean left) {
		jdbcTemplate.update(
			"insert into match_party_participants (party_id, user_id, participant_ref, left_at, created_at) values (?, ?, ?, ?, current_timestamp)",
			partyId,
			userId,
			UUID.randomUUID(),
			left ? java.sql.Timestamp.from(Instant.now()) : null);
	}

	private void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "동시성 동기화 지점에 도달하지 못했습니다.");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("동시성 동기화 대기 중 인터럽트되었습니다.", exception);
		}
	}

	private void shutdown(ExecutorService executor) {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
		} catch (InterruptedException exception) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
			throw new AssertionError("동시성 워커 종료 대기 중 인터럽트되었습니다.", exception);
		}
	}
}
