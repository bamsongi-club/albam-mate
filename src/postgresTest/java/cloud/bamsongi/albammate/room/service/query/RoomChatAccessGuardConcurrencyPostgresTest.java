package cloud.bamsongi.albammate.room.service.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.contract.ChatAccessGuard;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomStatusChangeService;

@Testcontainers
@SpringBootTest
@Import(RoomChatAccessGuardConcurrencyPostgresTest.FixedClockConfiguration.class)
class RoomChatAccessGuardConcurrencyPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
	private static final long WAIT_SECONDS = 10;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_chat_access_concurrency_test");

	@Autowired
	private ChatAccessGuard chatAccessGuard;
	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomStatusChangeService roomStatusChangeService;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("truncate table chat_rooms, participations, rooms, users restart identity cascade");
	}

	@Test
	void guard_트랜잭션은_참가_취소_커밋을_지연시키고_해제_뒤_최신_접근을_거절한다() throws Exception {
		long hostUserId = insertUser("host");
		long participantUserId = insertUser("participant");
		Room room = createRoom(hostUserId);
		roomParticipationService.participate(participantUserId, room.getId());
		insertChatRoom(room.getId());

		assertGuardTransactionBlocksCommand(
			participantUserId,
			room.getId(),
			() -> roomParticipationCancelService.cancelParticipation(participantUserId, room.getId()));

		assertForbidden(participantUserId, room.getId());
	}

	@Test
	void guard_트랜잭션은_방_최종_상태_전환_커밋을_지연시키고_해제_뒤_최신_접근을_거절한다() throws Exception {
		long hostUserId = insertUser("host");
		Room room = createRoom(hostUserId);
		insertChatRoom(room.getId());

		assertGuardTransactionBlocksCommand(
			hostUserId,
			room.getId(),
			() -> roomStatusChangeService.cancelRoom(hostUserId, room.getId()));

		assertForbidden(hostUserId, room.getId());
	}

	@Test
	void guard_트랜잭션은_FINISHED_전환_커밋을_지연시키고_해제_뒤_최신_접근을_거절한다() throws Exception {
		long hostUserId = insertUser("finish-host");
		long participantUserId = insertUser("finish-participant");
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(participantUserId, room.getId());
		jdbcTemplate.update("update rooms set start_at = ? where id = ?", timestamp(NOW.minusSeconds(1)), room.getId());
		insertChatRoom(room.getId());

		assertGuardTransactionBlocksCommand(
			hostUserId,
			room.getId(),
			() -> roomStatusChangeService.finishRoom(hostUserId, room.getId()));

		assertForbidden(hostUserId, room.getId());
	}

	private void assertGuardTransactionBlocksCommand(
		long accessUserId, long roomId, Runnable command) throws Exception {
		CountDownLatch guardAccessed = new CountDownLatch(1);
		CountDownLatch releaseGuard = new CountDownLatch(1);
		CountDownLatch commandStarted = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<?> guardFuture = executor.submit(
				() -> chatAccessGuard.executeWithAccess(
					accessUserId,
					roomId,
					() -> {
						guardAccessed.countDown();
						await(releaseGuard);
						return null;
					}));
			await(guardAccessed);

			Future<?> commandFuture = executor.submit(
				() -> {
					commandStarted.countDown();
					command.run();
				});
			await(commandStarted);
			assertCommandIsBlocked(commandFuture);

			releaseGuard.countDown();
			guardFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			commandFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
		} finally {
			releaseGuard.countDown();
			shutdown(executor);
		}
	}

	private void assertCommandIsBlocked(Future<?> commandFuture) {
		assertFalse(commandFuture.isDone(), "ROOM 공유 잠금이 유지되는 동안 상태 변경이 완료됐습니다.");
		assertThrows(TimeoutException.class, () -> commandFuture.get(1, TimeUnit.SECONDS));
	}

	private void assertForbidden(long userId, long roomId) {
		BusinessException exception = assertThrows(
			BusinessException.class, () -> chatAccessGuard.executeWithAccess(userId, roomId, () -> null));
		assertEquals(ErrorCode.FORBIDDEN, exception.getErrorCode());
	}

	private Room createRoom(long hostUserId) {
		return createRoom(hostUserId, 2);
	}

	private Room createRoom(long hostUserId, int capacity) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"채팅 접근 경합 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대 테스트 장소",
				capacity));
	}

	private void insertChatRoom(long roomId) {
		jdbcTemplate.update(
			"insert into chat_rooms (room_id, created_at, updated_at) values (?, ?, ?)",
			roomId,
			timestamp(NOW),
			timestamp(NOW));
	}

	private long insertUser(String role) {
		String email = "chat-access-" + role + "-" + UUID.randomUUID() + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?)",
			email,
			"채팅 " + role,
			timestamp(NOW),
			timestamp(NOW));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private Timestamp timestamp(Instant instant) {
		return Timestamp.from(instant);
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

	@TestConfiguration(proxyBeanMethods = false)
	static class FixedClockConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}
	}
}
