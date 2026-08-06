package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.global.scheduling.ScheduledTaskLock;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.RoomRepository;

@Testcontainers
class RoomStatusCorrectionProgressLocalMultiPostgresTest {

	private static final String LOCK_NAME = "room-status-correction";
	private static final Duration LOCK_AT_MOST_FOR = Duration.ofSeconds(30);
	private static final Instant REQUEST_TIME = Instant.parse("2026-08-07T00:00:00Z");
	private static final long ADVISORY_LOCK_KEY = 382009L;

	@Container
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
		.withDatabaseName("room_status_correction_local_multi_test");

	@Test
	void T5_local_프로필은_두_인스턴스_스케줄의_PostgreSQL_소유_경계를_설정한다() throws Exception {
		String localProfile = Files.readString(Path.of("src/main/resources/application-local.yml"));

		assertTrue(localProfile.contains("lock-at-most-for: 2m"));
		assertTrue(localProfile.contains("execution-warning-threshold: 30s"));

		try (ConfigurableApplicationContext firstContext = applicationContext();
			ConfigurableApplicationContext secondContext = applicationContext()) {
			resetProgressState(firstContext.getBean(JdbcTemplate.class));
			assertTrue(firstContext.containsBean("roomStatusCorrectionSchedulingConfiguration"));
			assertTrue(secondContext.containsBean("roomStatusCorrectionSchedulingConfiguration"));

			ScheduledTaskLock firstLock = firstContext.getBean(ScheduledTaskLock.class);
			RoomStatusCorrectionScheduler secondScheduler = secondContext
				.getBean(RoomStatusCorrectionScheduler.class);
			CountDownLatch ownerStarted = new CountDownLatch(1);
			CountDownLatch releaseOwner = new CountDownLatch(1);
			ExecutorService executorService = Executors.newFixedThreadPool(2);
			try {
				var owner = executorService.submit(() -> firstLock.tryExecute(
					"room-status-correction", Duration.ofSeconds(10), () -> {
						ownerStarted.countDown();
						await(releaseOwner);
					}));
				assertTrue(ownerStarted.await(5, TimeUnit.SECONDS));

				var skipped = executorService.submit(secondScheduler::correctDueRooms);
				skipped.get(5, TimeUnit.SECONDS);

				releaseOwner.countDown();
				assertTrue(owner.get(5, TimeUnit.SECONDS).acquired());
			} finally {
				releaseOwner.countDown();
				executorService.shutdownNow();
				executorService.awaitTermination(5, TimeUnit.SECONDS);
			}

			secondScheduler.correctDueRooms();
			assertEquals(1L, secondContext.getBean(RoomStatusCorrectionProgressStore.class)
				.current().executionGeneration());
		}
	}

	@Test
	void 새_generation이_진척을_확정한_뒤_이전_실행의_cursor_전진과_wrap을_거절한다() {
		String previousUrl = System.getProperty("spring.datasource.url");
		String previousUsername = System.getProperty("spring.datasource.username");
		String previousPassword = System.getProperty("spring.datasource.password");
		System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
		System.setProperty("spring.datasource.username", POSTGRES.getUsername());
		System.setProperty("spring.datasource.password", POSTGRES.getPassword());
		try {
			try (ConfigurableApplicationContext firstContext = applicationContext();
				ConfigurableApplicationContext secondContext = applicationContext()) {
				JdbcTemplate jdbcTemplate = secondContext.getBean(JdbcTemplate.class);
				resetProgress(jdbcTemplate);
				deleteLock(jdbcTemplate);
				RoomStatusCorrectionProgressStore first = firstContext.getBean(RoomStatusCorrectionProgressStore.class);
				RoomStatusCorrectionProgressStore second = secondContext
					.getBean(RoomStatusCorrectionProgressStore.class);
				resetProgressState(firstContext.getBean(JdbcTemplate.class));
				var staleExecution = first.claimExecution(Instant.parse("2026-08-05T00:01:00Z"));
				var currentExecution = second.claimExecution(Instant.parse("2026-08-05T00:01:00Z"));
				assertTrue(second.advanceCursor(
					currentExecution, Instant.parse("2026-08-05T00:00:30Z"), 20L).isPresent());
				resetProgressVersion(jdbcTemplate, staleExecution.progressVersion());

				assertFalse(first.advanceCursor(
					staleExecution, Instant.parse("2026-08-05T00:00:45Z"), 21L).isPresent());
				resetProgressVersion(jdbcTemplate, staleExecution.progressVersion());
				assertFalse(first.wrap(staleExecution, Instant.parse("2026-08-05T00:02:00Z")).isPresent());

				var persisted = second.current();
				assertEquals(currentExecution.executionGeneration(), persisted.executionGeneration());
				assertEquals(20L, persisted.cursorRoomId());
				assertEquals(staleExecution.progressVersion(), persisted.progressVersion());
			}
		} finally {
			restoreSystemProperty("spring.datasource.url", previousUrl);
			restoreSystemProperty("spring.datasource.username", previousUsername);
			restoreSystemProperty("spring.datasource.password", previousPassword);
		}
	}

	@Test
	void lock_holder_인스턴스_종료와_lease_만료_뒤_다른_인스턴스가_due_ROOM을_처리한다() throws Exception {
		List<Long> roomIds = new ArrayList<>();
		List<Long> userIds = new ArrayList<>();
		ExecutorService holderExecutor = Executors.newSingleThreadExecutor();
		CountDownLatch holderClaimed = new CountDownLatch(1);
		CountDownLatch releaseHolder = new CountDownLatch(1);
		Future<?> holderTask = null;

		try (ConfigurableApplicationContext firstContext = applicationContext();
			ConfigurableApplicationContext secondContext = applicationContext()) {
			JdbcTemplate secondJdbcTemplate = secondContext.getBean(JdbcTemplate.class);
			RoomRepository secondRoomRepository = secondContext.getBean(RoomRepository.class);
			try {
				Long hostUserId = insertUser(secondJdbcTemplate, "takeover");
				userIds.add(hostUserId);
				roomIds.add(saveRoom(secondRoomRepository, hostUserId, REQUEST_TIME.minusSeconds(2)).getId());
				roomIds.add(saveRoom(secondRoomRepository, hostUserId, REQUEST_TIME.minusSeconds(1)).getId());
				resetProgress(secondJdbcTemplate);
				deleteLock(secondJdbcTemplate);

				RoomStatusCorrectionProgressStore firstProgress = firstContext
					.getBean(RoomStatusCorrectionProgressStore.class);
				ScheduledTaskLock firstLock = firstContext.getBean(ScheduledTaskLock.class);
				holderTask = holderExecutor.submit(() -> firstLock.tryExecute(
					LOCK_NAME,
					LOCK_AT_MOST_FOR,
					() -> {
						firstProgress.claimExecution(REQUEST_TIME);
						setLockOwner(firstContext.getBean(JdbcTemplate.class), "room-382-first-instance");
						holderClaimed.countDown();
						awaitLatch(releaseHolder);
					}));

				awaitLatch(holderClaimed);
				firstContext.close();
				expireLock(secondJdbcTemplate);

				RoomStatusCorrectionProgressStore secondProgress = secondContext
					.getBean(RoomStatusCorrectionProgressStore.class);
				RoomStatusCorrectionCoordinator secondCoordinator = secondContext
					.getBean(RoomStatusCorrectionCoordinator.class);
				ScheduledTaskLock secondLock = secondContext.getBean(ScheduledTaskLock.class);
				ScheduledTaskLock.LockExecution takeover = secondLock.tryExecute(
					LOCK_NAME,
					LOCK_AT_MOST_FOR,
					() -> {
						RoomStatusCorrectionProgressStore.ProgressSnapshot claimed = secondProgress
							.claimExecution(REQUEST_TIME);
						setLockOwner(secondJdbcTemplate, "room-382-second-instance");
						secondCoordinator.correctBoundedDueRooms(REQUEST_TIME, claimed, 10);
					});

				assertTrue(takeover.acquired());
				roomIds.forEach(roomId -> assertEquals(
					RoomStatus.CLOSED,
					secondRoomRepository.findById(roomId).orElseThrow().getStatus()));
				assertEquals(2L, secondProgress.current().executionGeneration());
				assertNullCursor(secondProgress.current());
			} finally {
				releaseHolder.countDown();
				stopWorkerIgnoringFailure(holderTask, holderExecutor);
				cleanup(secondJdbcTemplate, roomIds, userIds);
			}
		}
	}

	@Test
	void lease_만료_중첩_실행에서_stale_cursor는_다음_ROOM을_건너뛰고_새_인스턴스가_모든_due_ROOM을_처리한다()
		throws Exception {
		List<Long> roomIds = new ArrayList<>();
		List<Long> userIds = new ArrayList<>();
		ExecutorService firstExecutor = Executors.newSingleThreadExecutor();
		ExecutorService secondExecutor = Executors.newSingleThreadExecutor();
		CountDownLatch firstClaimed = new CountDownLatch(1);
		CountDownLatch secondClaimed = new CountDownLatch(1);
		CountDownLatch allowSecond = new CountDownLatch(1);
		Future<?> firstTask = null;
		Future<?> secondTask = null;
		Connection advisoryLockConnection = null;

		try (ConfigurableApplicationContext firstContext = applicationContext();
			ConfigurableApplicationContext secondContext = applicationContext()) {
			JdbcTemplate firstJdbcTemplate = firstContext.getBean(JdbcTemplate.class);
			JdbcTemplate secondJdbcTemplate = secondContext.getBean(JdbcTemplate.class);
			RoomRepository secondRoomRepository = secondContext.getBean(RoomRepository.class);
			try {
				Long hostUserId = insertUser(secondJdbcTemplate, "overlap");
				userIds.add(hostUserId);
				roomIds.add(saveRoom(secondRoomRepository, hostUserId, REQUEST_TIME.minusSeconds(3)).getId());
				roomIds.add(saveRoom(secondRoomRepository, hostUserId, REQUEST_TIME.minusSeconds(2)).getId());
				roomIds.add(saveRoom(secondRoomRepository, hostUserId, REQUEST_TIME.minusSeconds(1)).getId());
				resetProgress(secondJdbcTemplate);
				deleteLock(secondJdbcTemplate);

				advisoryLockConnection = acquireAdvisoryLock(
					firstContext.getBean(DataSource.class));
				dropBlockingRoomTrigger(secondJdbcTemplate);
				installBlockingRoomTrigger(secondJdbcTemplate, roomIds.getFirst());

				RoomStatusCorrectionProgressStore firstProgress = firstContext
					.getBean(RoomStatusCorrectionProgressStore.class);
				RoomStatusCorrectionCoordinator firstCoordinator = firstContext
					.getBean(RoomStatusCorrectionCoordinator.class);
				ScheduledTaskLock firstLock = firstContext.getBean(ScheduledTaskLock.class);
				firstTask = firstExecutor.submit(() -> firstLock.tryExecute(
					LOCK_NAME,
					LOCK_AT_MOST_FOR,
					() -> {
						RoomStatusCorrectionProgressStore.ProgressSnapshot stale = firstProgress
							.claimExecution(REQUEST_TIME);
						setLockOwner(firstJdbcTemplate, "room-382-first-instance");
						firstClaimed.countDown();
						firstCoordinator.correctBoundedDueRooms(REQUEST_TIME, stale, 10);
					}));

				awaitLatch(firstClaimed);
				expireLock(secondJdbcTemplate);

				RoomStatusCorrectionProgressStore secondProgress = secondContext
					.getBean(RoomStatusCorrectionProgressStore.class);
				RoomStatusCorrectionCoordinator secondCoordinator = secondContext
					.getBean(RoomStatusCorrectionCoordinator.class);
				ScheduledTaskLock secondLock = secondContext.getBean(ScheduledTaskLock.class);
				secondTask = secondExecutor.submit(() -> secondLock.tryExecute(
					LOCK_NAME,
					LOCK_AT_MOST_FOR,
					() -> {
						RoomStatusCorrectionProgressStore.ProgressSnapshot current = secondProgress
							.claimExecution(REQUEST_TIME);
						setLockOwner(secondJdbcTemplate, "room-382-second-instance");
						secondClaimed.countDown();
						awaitLatch(allowSecond);
						secondCoordinator.correctBoundedDueRooms(REQUEST_TIME, current, 10);
					}));

				awaitLatch(secondClaimed);
				releaseAdvisoryLock(advisoryLockConnection);
				advisoryLockConnection = null;
				awaitWorker(firstTask, firstExecutor);

				assertEquals(RoomStatus.CLOSED,
					secondRoomRepository.findById(roomIds.getFirst()).orElseThrow().getStatus());
				assertEquals(RoomStatus.RECRUITING,
					secondRoomRepository.findById(roomIds.get(1)).orElseThrow().getStatus());
				assertEquals(RoomStatus.RECRUITING,
					secondRoomRepository.findById(roomIds.get(2)).orElseThrow().getStatus());
				assertEquals(2L, secondProgress.current().executionGeneration());
				assertNullCursor(secondProgress.current());

				allowSecond.countDown();
				awaitWorker(secondTask, secondExecutor);

				roomIds.forEach(roomId -> assertEquals(
					RoomStatus.CLOSED,
					secondRoomRepository.findById(roomId).orElseThrow().getStatus()));
				assertEquals(REQUEST_TIME.plusNanos(1_000), secondProgress.current().turnCutoff());
				assertNullCursor(secondProgress.current());
			} finally {
				allowSecond.countDown();
				if (advisoryLockConnection != null) {
					releaseAdvisoryLock(advisoryLockConnection);
				}
				stopWorkerIgnoringFailure(firstTask, firstExecutor);
				stopWorkerIgnoringFailure(secondTask, secondExecutor);
				dropBlockingRoomTrigger(secondJdbcTemplate);
				cleanup(secondJdbcTemplate, roomIds, userIds);
			}
		}
	}

	private void resetProgressVersion(JdbcTemplate jdbcTemplate, long progressVersion) {
		jdbcTemplate.update("""
			update room_status_correction_progress
			set progress_version = ?
			where job_name = 'room-status-correction'
			""", progressVersion);
	}

	private void resetProgressState(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.update("""
			update room_status_correction_progress
			set turn_cutoff = null,
			    cursor_due_at = null,
			    cursor_room_id = null,
			    progress_version = 0,
			    execution_generation = 0
			where job_name = 'room-status-correction'
			""");
	}

	private ConfigurableApplicationContext applicationContext() {
		String previousUrl = System.getProperty("spring.datasource.url");
		String previousUsername = System.getProperty("spring.datasource.username");
		String previousPassword = System.getProperty("spring.datasource.password");
		System.setProperty("spring.datasource.url", POSTGRES.getJdbcUrl());
		System.setProperty("spring.datasource.username", POSTGRES.getUsername());
		System.setProperty("spring.datasource.password", POSTGRES.getPassword());
		try {
			return new SpringApplicationBuilder(AlbamMateApplication.class)
				.properties(Map.of(
					"spring.profiles.active", "local",
					"server.port", "0",
					"spring.task.scheduling.enabled", "false",
					"spring.datasource.url", POSTGRES.getJdbcUrl(),
					"spring.datasource.username", POSTGRES.getUsername(),
					"spring.datasource.password", POSTGRES.getPassword(),
					"app.room.status-correction.lock-name", "room-status-correction",
					"app.room.status-correction.trigger-delay", "15m",
					"app.room.status-correction.trigger-jitter", "3m",
					"app.room.status-correction.lock-at-most-for", "2m",
					"app.room.status-correction.execution-warning-threshold", "30s"))
				.run();
		} finally {
			restoreSystemProperty("spring.datasource.url", previousUrl);
			restoreSystemProperty("spring.datasource.username", previousUsername);
			restoreSystemProperty("spring.datasource.password", previousPassword);
		}
	}

	private void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS));
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("ShedLock 소유 실행 대기 중 인터럽트가 발생했습니다", exception);
		}
	}
		}
	}

	private void restoreSystemProperty(String name, String value) {
		if (value == null) {
			System.clearProperty(name);
			return;
		}
		System.setProperty(name, value);
	}
}
