package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.entity.RoomWaitlistId;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;

@Testcontainers
@SpringBootTest
@Import(RoomStatusCorrectionPostgresTest.StatusCorrectionTestConfiguration.class)
class RoomStatusCorrectionPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant START_AT = Instant.parse("2026-07-28T00:00:00Z");
	private static final OffsetDateTime START_AT_UTC = START_AT.atOffset(ZoneOffset.UTC);
	private static final Instant FINISH_AT = START_AT.plus(Room.AUTOMATIC_FINISH_AFTER_START);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("room_state_test");

	@Autowired
	private RoomStatusCorrectionCoordinator coordinator;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;
	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;

	@Autowired
	private RoomReadGate roomReadGate;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;

	private final List<Long> roomIds = new ArrayList<>();
	private final List<RoomWaitlistId> waitlistIds = new ArrayList<>();
	private Long hostUserId;

	@BeforeEach
	void setUp() {
		hostUserId = insertHostUser();
	}

	@AfterEach
	void tearDown() {
		waitlistIds.forEach(waitlistId -> roomWaitlistRepository.deleteById(waitlistId));
		roomIds.forEach(roomId -> jdbcTemplate.update("delete from rooms where id = ?", roomId));
		jdbcTemplate.update("delete from users where id = ?", hostUserId);
	}

	@Test
	void 시작_경계_직전에는_보정하지_않고_정확한_UTC_시각에는_닫힌다() {
		Room room = saveRoom(START_AT);
		Long versionBefore = currentRoom(room.getId()).getVersion();

		assertStoredAsUtcTimestamptz(room.getId(), START_AT);

		coordinator.correctRoom(room.getId(), START_AT.minusNanos(1_000));

		Room beforeBoundary = currentRoom(room.getId());
		assertEquals(RoomStatus.RECRUITING, beforeBoundary.getStatus());
		assertEquals(versionBefore, beforeBoundary.getVersion());

		coordinator.correctRoom(room.getId(), START_AT);

		Room atBoundary = currentRoom(room.getId());
		assertEquals(RoomStatus.CLOSED, atBoundary.getStatus());
		assertEquals(versionBefore + 1, atBoundary.getVersion());
	}

	@Test
	void 자동_종료_경계_직전에는_보정하지_않고_정확한_UTC_시각에는_종료된다() {
		Room room = saveRoom(START_AT);
		coordinator.correctRoom(room.getId(), START_AT);
		Long closedVersion = currentRoom(room.getId()).getVersion();

		coordinator.correctRoom(room.getId(), FINISH_AT.minusNanos(1_000));

		Room beforeBoundary = currentRoom(room.getId());
		assertEquals(RoomStatus.CLOSED, beforeBoundary.getStatus());
		assertEquals(closedVersion, beforeBoundary.getVersion());

		coordinator.correctRoom(room.getId(), FINISH_AT);

		Room atBoundary = currentRoom(room.getId());
		assertEquals(RoomStatus.FINISHED, atBoundary.getStatus());
		assertEquals(closedVersion + 1, atBoundary.getVersion());
	}

	@Test
	void 전체_보정은_시작_경계에서_모집중_ROOM을_닫고_기존_닫힌_ROOM의_대기열까지_만료한다() throws ReflectiveOperationException {
		Room recruitingRoom = saveRoom(START_AT.minusSeconds(1));
		RoomWaitlist recruitingWaiting = saveWaiting(recruitingRoom.getId());
		Room closedRoom = saveRoom(START_AT.minusSeconds(1));
		setStatus(closedRoom, RoomStatus.CLOSED);
		roomRepository.saveAndFlush(closedRoom);
		RoomWaitlist closedWaiting = saveWaiting(closedRoom.getId());

		int changedCount = coordinator.correctDueRooms(START_AT);

		assertEquals(2, changedCount);
		assertEquals(RoomStatus.CLOSED, currentRoom(recruitingRoom.getId()).getStatus());
		assertEquals(
			RoomWaitlistStatus.EXPIRED,
			roomWaitlistRepository.findById(recruitingWaiting.getId()).orElseThrow().getStatus());
		assertEquals(RoomStatus.CLOSED, currentRoom(closedRoom.getId()).getStatus());
		assertEquals(
			RoomWaitlistStatus.EXPIRED,
			roomWaitlistRepository.findById(closedWaiting.getId()).orElseThrow().getStatus());
	}

	@Test
	void 낙관락_충돌_후_coordinator는_최신_종료_상태를_독립_트랜잭션에서_다시_읽는다() throws Exception {
		Room room = saveRoom(START_AT);
		Long versionBefore = currentRoom(room.getId()).getVersion();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		roomReadGate.activate(room.getId());

		try {
			Future<?> reconciliation = executor.submit(() -> coordinator.correctRoom(room.getId(), START_AT));

			roomReadGate.awaitFirstRead();

			transactionTemplate()
				.executeWithoutResult(
					status -> {
						Room latestRoom = currentRoom(room.getId());
						assertTrue(latestRoom.reconcileStateAt(FINISH_AT));
					});
			roomReadGate.releaseFirstAttempt();
			reconciliation.get(5, TimeUnit.SECONDS);
			roomReadGate.assertFirstAttemptThenRetry();

			Room finalRoom = currentRoom(room.getId());
			assertEquals(RoomStatus.FINISHED, finalRoom.getStatus());
			assertEquals(versionBefore + 1, finalRoom.getVersion());
		} finally {
			try {
				roomReadGate.releaseFirstAttempt();
			} finally {
				try {
					executor.shutdown();
					awaitTermination(executor);
				} finally {
					roomReadGate.deactivate();
				}
			}
		}
	}

	@Test
	void coordinator가_세_번_충돌하면_동시_수정_오류로_매핑한다() throws Exception {
		Room room = saveRoom(START_AT);
		Long versionBefore = currentRoom(room.getId()).getVersion();
		ExecutorService executor = Executors.newSingleThreadExecutor();
		roomReadGate.activateEveryAttempt(room.getId(), 3);

		try {
			Future<?> reconciliation = executor.submit(() -> coordinator.correctRoom(room.getId(), START_AT));

			for (int attempt = 1; attempt <= 3; attempt++) {
				roomReadGate.awaitRead(attempt);
				updateRoomTitleInSeparateTransaction(room.getId(), attempt);
				roomReadGate.releaseAttempt(attempt);
			}

			ExecutionException executionException = assertThrows(
				ExecutionException.class,
				() -> reconciliation.get(5, TimeUnit.SECONDS));
			BusinessException businessException = assertInstanceOf(BusinessException.class,
				executionException.getCause());
			assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, businessException.getErrorCode());
			roomReadGate.assertEveryAttemptConflicted();

			Room finalRoom = currentRoom(room.getId());
			assertEquals(RoomStatus.RECRUITING, finalRoom.getStatus());
			assertEquals(versionBefore + 3, finalRoom.getVersion());
		} finally {
			try {
				roomReadGate.releaseAllAttempts();
			} finally {
				try {
					executor.shutdown();
					awaitTermination(executor);
				} finally {
					roomReadGate.deactivate();
				}
			}
		}
	}

	private TransactionTemplate transactionTemplate() {
		return new TransactionTemplate(transactionManager);
	}

	private void awaitTermination(ExecutorService executor) {
		try {
			if (executor.awaitTermination(5, TimeUnit.SECONDS)) {
				return;
			}

			executor.shutdownNow();
			assertTrue(
				executor.awaitTermination(5, TimeUnit.SECONDS), "워커를 강제 종료한 뒤에도 종료되지 않았습니다.");
		} catch (InterruptedException exception) {
			executor.shutdownNow();
			Thread.currentThread().interrupt();
			throw new AssertionError("워커 종료 대기 중 인터럽트되었습니다.", exception);
		}
	}

	private Room saveRoom(Instant startAt) {
		Room room = Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"PostgreSQL 상태 보정 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startAt,
			"홍대 카페",
			3);
		Room saved = roomRepository.saveAndFlush(room);
		roomIds.add(saved.getId());
		return saved;
	}

	private Room currentRoom(Long roomId) {
		return roomRepository.findById(roomId).orElseThrow();
	}

	private RoomWaitlist saveWaiting(Long roomId) {
		RoomWaitlist waitlist = roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(roomId, hostUserId, roomId, START_AT.minusSeconds(2)));
		waitlistIds.add(waitlist.getId());
		return waitlist;
	}

	private void setStatus(Room room, RoomStatus status) throws ReflectiveOperationException {
		Field field = Room.class.getDeclaredField("status");
		field.setAccessible(true);
		field.set(room, status);
	}

	private void updateRoomTitleInSeparateTransaction(Long roomId, int attempt) {
		transactionTemplate()
			.executeWithoutResult(
				status -> {
					Room room = currentRoom(roomId);
					assertEquals(RoomStatus.RECRUITING, room.getStatus());
					room.update(
						"PostgreSQL 상태 보정 방 " + attempt,
						room.getDescription(),
						room.getGameId(),
						room.getExperienceLevel(),
						room.isRulemasterLed(),
						room.getStartAt(),
						room.getPlace(),
						room.getCapacity());
					roomRepository.saveAndFlush(room);
				});
	}

	private void assertStoredAsUtcTimestamptz(Long roomId, Instant expectedStartAt) {
		assertEquals(
			"timestamp with time zone",
			jdbcTemplate.queryForObject(
				"select pg_typeof(start_at)::text from rooms where id = ?",
				String.class,
				roomId));
		OffsetDateTime storedStartAt = jdbcTemplate.queryForObject(
			"select start_at from rooms where id = ?", OffsetDateTime.class, roomId);
		assertEquals(expectedStartAt, storedStartAt.toInstant());
	}

	private Long insertHostUser() {
		String email = "room-state-postgres-" + UUID.randomUUID() + "@example.com";
		assertEquals(START_AT, START_AT_UTC.toInstant());
		jdbcTemplate.update(
			"insert into users "
				+ "(email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'postgres-test-hash', '상태 보정 테스트', ?, ?)",
			email,
			START_AT_UTC,
			START_AT_UTC);
		return jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class StatusCorrectionTestConfiguration {

		@Bean
		RoomReadGate roomReadGate() {
			return new RoomReadGate();
		}

		@Bean(name = "gatedRoomRepository")
		@Primary
		RoomRepository gatedRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate, RoomReadGate roomReadGate) {
			InvocationHandler handler = new GateAwareRoomRepositoryInvocationHandler(delegate, roomReadGate);
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(),
				new Class<?>[] {RoomRepository.class},
				handler);
		}
	}

	private static final class GateAwareRoomRepositoryInvocationHandler
		implements InvocationHandler {

		private final RoomRepository delegate;
		private final RoomReadGate roomReadGate;

		private GateAwareRoomRepositoryInvocationHandler(
			RoomRepository delegate, RoomReadGate roomReadGate) {
			this.delegate = delegate;
			this.roomReadGate = roomReadGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			try {
				Object result = method.invoke(delegate, args);
				if (method.getName().equals("findById")
					&& args != null
					&& args.length == 1
					&& args[0] instanceof Long roomId
					&& result instanceof Optional<?> optional) {
					roomReadGate.afterFindById(roomId, optional.map(Room.class::cast));
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	static final class RoomReadGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		void activate(long roomId) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId)));
		}

		void activateEveryAttempt(long roomId, int attemptCount) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId, attemptCount)));
		}

		void afterFindById(long roomId, Optional<Room> room) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || scenario.roomId != roomId) {
				return;
			}

			int readOrder = scenario.readCount.incrementAndGet();
			scenario.observedStatuses.add(room.orElseThrow().getStatus());
			if (readOrder <= scenario.gatedAttemptCount) {
				scenario.readGates.get(readOrder - 1).read.countDown();
				await(scenario.readGates.get(readOrder - 1).release);
			}
		}

		void awaitFirstRead() {
			Scenario scenario = activeScenario.get();
			assertTrue(scenario != null, "활성화된 읽기 게이트가 없습니다.");
			awaitRead(1);
		}

		void releaseFirstAttempt() {
			releaseAttempt(1);
		}

		void awaitRead(int attempt) {
			Scenario scenario = activeScenario.get();
			assertTrue(scenario != null, "활성화된 읽기 게이트가 없습니다.");
			await(scenario.readGates.get(attempt - 1).read);
		}

		void releaseAttempt(int attempt) {
			Scenario scenario = activeScenario.get();
			if (scenario != null && attempt <= scenario.gatedAttemptCount) {
				scenario.readGates.get(attempt - 1).release.countDown();
			}
		}

		void releaseAllAttempts() {
			Scenario scenario = activeScenario.get();
			if (scenario != null) {
				scenario.readGates.forEach(readGate -> readGate.release.countDown());
			}
		}

		void assertFirstAttemptThenRetry() {
			Scenario scenario = activeScenario.get();
			assertTrue(scenario != null, "활성화된 읽기 게이트가 없습니다.");
			assertEquals(2, scenario.readCount.get());
			assertEquals(
				List.of(RoomStatus.RECRUITING, RoomStatus.FINISHED), scenario.observedStatuses);
		}

		void assertEveryAttemptConflicted() {
			Scenario scenario = activeScenario.get();
			assertTrue(scenario != null, "활성화된 읽기 게이트가 없습니다.");
			assertEquals(3, scenario.readCount.get());
			assertEquals(
				List.of(RoomStatus.RECRUITING, RoomStatus.RECRUITING, RoomStatus.RECRUITING),
				scenario.observedStatuses);
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private void await(CountDownLatch latch) {
			try {
				assertTrue(latch.await(5, TimeUnit.SECONDS), "동기화 지점에 도달하지 못했습니다.");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("동기화 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final int gatedAttemptCount;
			private final List<ReadGate> readGates;
			private final AtomicInteger readCount = new AtomicInteger();
			private final List<RoomStatus> observedStatuses = new java.util.concurrent.CopyOnWriteArrayList<>();

			private Scenario(long roomId) {
				this(roomId, 1);
			}

			private Scenario(long roomId, int gatedAttemptCount) {
				this.roomId = roomId;
				this.gatedAttemptCount = gatedAttemptCount;
				this.readGates = new ArrayList<>(gatedAttemptCount);
				for (int attempt = 0; attempt < gatedAttemptCount; attempt++) {
					readGates.add(new ReadGate());
				}
			}
		}

		private static final class ReadGate {

			private final CountDownLatch read = new CountDownLatch(1);
			private final CountDownLatch release = new CountDownLatch(1);
		}
	}
}
