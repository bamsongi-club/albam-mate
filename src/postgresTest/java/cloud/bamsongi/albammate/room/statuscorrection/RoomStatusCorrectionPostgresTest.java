package cloud.bamsongi.albammate.room.statuscorrection;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.entity.RoomWaitlistId;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.testsupport.SharedPostgresIntegrationSupport;

@Testcontainers
@SpringBootTest
@Import(RoomStatusCorrectionPostgresTest.DueRoomTransactionBoundaryTestConfiguration.class)
class RoomStatusCorrectionPostgresTest extends SharedPostgresIntegrationSupport {

	private static final Instant START_AT = Instant.parse("2026-07-28T00:00:00Z");
	private static final OffsetDateTime START_AT_UTC = START_AT.atOffset(ZoneOffset.UTC);
	private static final Instant FINISH_AT = START_AT.plus(Room.AUTOMATIC_FINISH_AFTER_START);

	@Autowired
	private RoomStatusCorrectionCoordinator coordinator;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;
	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private DueRoomTransactionGate dueRoomTransactionGate;

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
	void 비관락_충돌은_상태_보정을_실패시키고_ROOM을_부분변경하지_않는다() throws Exception {
		Room room = saveRoom(START_AT);
		Long versionBefore = currentRoom(room.getId()).getVersion();
		CountDownLatch roomLocked = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<?> lockHolder = executor.submit(() -> holdRoomLock(room.getId(), roomLocked, releaseLock));

		try {
			await(roomLocked);
			assertThrows(CannotAcquireLockException.class, () -> coordinator.correctRoom(room.getId(), START_AT));
		} finally {
			releaseLock.countDown();
			lockHolder.get(5, TimeUnit.SECONDS);
			shutdown(executor);
		}

		Room finalRoom = currentRoom(room.getId());
		assertEquals(RoomStatus.RECRUITING, finalRoom.getStatus());
		assertEquals(versionBefore, finalRoom.getVersion());
	}

	@Test
	void 비관락_실패_뒤_호출자가_재요청하면_최신_ROOM을_한_번만_보정한다() throws Exception {
		Room room = saveRoom(START_AT);
		Long versionBefore = currentRoom(room.getId()).getVersion();
		CountDownLatch roomLocked = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<?> lockHolder = executor.submit(() -> holdRoomLock(room.getId(), roomLocked, releaseLock));

		try {
			await(roomLocked);
			assertThrows(CannotAcquireLockException.class, () -> coordinator.correctRoom(room.getId(), START_AT));
		} finally {
			releaseLock.countDown();
			lockHolder.get(5, TimeUnit.SECONDS);
			shutdown(executor);
		}

		coordinator.correctRoom(room.getId(), START_AT);

		Room finalRoom = currentRoom(room.getId());
		assertEquals(RoomStatus.CLOSED, finalRoom.getStatus());
		assertEquals(versionBefore + 1, finalRoom.getVersion());
	}

	@Test
	void T1_ROOM_쓰기_잠금은_실제_PostgreSQL_transaction_local_100ms_timeout을_적용하고_트랜잭션_종료_뒤_초기화된다()
		throws Exception {
		Room room = saveRoom(START_AT);
		CountDownLatch roomLocked = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<?> lockHolder = executor.submit(() -> holdRoomLock(room.getId(), roomLocked, releaseLock));

		try {
			await(roomLocked);
			assertThrows(CannotAcquireLockException.class, () -> acquireRoomWriteLock(room.getId()));
		} finally {
			releaseLock.countDown();
			lockHolder.get(5, TimeUnit.SECONDS);
			shutdown(executor);
		}

		assertEquals("0", jdbcTemplate.queryForObject("show lock_timeout", String.class));
	}

	@Test
	void T2_due_ROOM별_독립_트랜잭션은_다음_ROOM_대기_중_이전_ROOM_잠금을_보유하지_않는다()
		throws Exception {
		Room firstRoom = saveRoom(START_AT.minusSeconds(2));
		Room secondRoom = saveRoom(START_AT.minusSeconds(1));
		dueRoomTransactionGate.holdBetweenRooms(firstRoom.getId(), secondRoom.getId());
		ExecutorService executor = Executors.newSingleThreadExecutor();
		Future<Integer> correction = executor.submit(() -> coordinator.correctDueRooms(START_AT));

		try {
			dueRoomTransactionGate.awaitFirstRoomLock();
			dueRoomTransactionGate.releaseFirstRoom();
			dueRoomTransactionGate.awaitSecondRoomLockRequest();

			assertDoesNotThrow(() -> acquireRoomWriteLock(firstRoom.getId()));

			dueRoomTransactionGate.releaseSecondRoom();
			assertEquals(2, correction.get(5, TimeUnit.SECONDS));
		} finally {
			dueRoomTransactionGate.releaseAll();
			shutdown(executor);
		}
	}

	private TransactionTemplate transactionTemplate() {
		return new TransactionTemplate(transactionManager);
	}

	private void holdRoomLock(long roomId, CountDownLatch roomLocked, CountDownLatch releaseLock) {
		transactionTemplate().executeWithoutResult(status -> {
			roomRepository.findByIdForWrite(roomId).orElseThrow();
			roomLocked.countDown();
			await(releaseLock);
		});
	}

	private void acquireRoomWriteLock(long roomId) {
		transactionTemplate().executeWithoutResult(status -> {
			roomRepository.setLocalWriteLockTimeout();
			roomRepository.findByIdForWrite(roomId).orElseThrow();
		});
	}

	private void await(CountDownLatch latch) {
		try {
			assertTrue(latch.await(5, TimeUnit.SECONDS), "동기화 지점에 도달하지 못했습니다.");
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("동기화 지점 대기 중 인터럽트되었습니다.", exception);
		}
	}

	private void shutdown(ExecutorService executor) {
		executor.shutdown();
		try {
			if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "워커가 종료되지 않았습니다.");
			}
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
	static class DueRoomTransactionBoundaryTestConfiguration {

		@Bean
		DueRoomTransactionGate dueRoomTransactionGate() {
			return new DueRoomTransactionGate();
		}

		@Bean
		@Primary
		RoomRepository dueRoomTransactionBoundaryRepository(
			@Qualifier("roomRepository") RoomRepository delegate,
			DueRoomTransactionGate dueRoomTransactionGate) {
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(),
				new Class<?>[] {RoomRepository.class},
				(proxy, method, arguments) -> invokeRepository(
					delegate, dueRoomTransactionGate, method, arguments));
		}

		private Object invokeRepository(
			RoomRepository delegate,
			DueRoomTransactionGate dueRoomTransactionGate,
			Method method,
			Object[] arguments) throws Throwable {
			Long roomId = findWriteLockRoomId(method, arguments);
			if (roomId != null) {
				dueRoomTransactionGate.beforeWriteLock(roomId);
			}
			try {
				Object result = method.invoke(delegate, arguments);
				if (roomId != null) {
					dueRoomTransactionGate.afterWriteLock(roomId);
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}

		private Long findWriteLockRoomId(Method method, Object[] arguments) {
			if (!method.getName().equals("findByIdForWrite")
				|| arguments == null
				|| arguments.length != 1
				|| !(arguments[0] instanceof Long roomId)) {
				return null;
			}
			return roomId;
		}
	}

	static final class DueRoomTransactionGate {

		private final AtomicBoolean active = new AtomicBoolean();
		private CountDownLatch firstRoomLocked;
		private CountDownLatch releaseFirstRoom;
		private CountDownLatch secondRoomLockRequested;
		private CountDownLatch releaseSecondRoom;
		private long firstRoomId;
		private long secondRoomId;

		void holdBetweenRooms(long firstRoomId, long secondRoomId) {
			this.firstRoomId = firstRoomId;
			this.secondRoomId = secondRoomId;
			this.firstRoomLocked = new CountDownLatch(1);
			this.releaseFirstRoom = new CountDownLatch(1);
			this.secondRoomLockRequested = new CountDownLatch(1);
			this.releaseSecondRoom = new CountDownLatch(1);
			assertTrue(active.compareAndSet(false, true));
		}

		void beforeWriteLock(long roomId) {
			if (active.get() && roomId == secondRoomId) {
				secondRoomLockRequested.countDown();
				await(releaseSecondRoom);
			}
		}

		void afterWriteLock(long roomId) {
			if (active.get() && roomId == firstRoomId) {
				firstRoomLocked.countDown();
				await(releaseFirstRoom);
			}
		}

		void awaitFirstRoomLock() {
			await(firstRoomLocked);
		}

		void releaseFirstRoom() {
			releaseFirstRoom.countDown();
		}

		void awaitSecondRoomLockRequest() {
			await(secondRoomLockRequested);
		}

		void releaseSecondRoom() {
			releaseSecondRoom.countDown();
		}

		void releaseAll() {
			if (!active.compareAndSet(true, false)) {
				return;
			}
			releaseFirstRoom.countDown();
			releaseSecondRoom.countDown();
		}

		private void await(CountDownLatch latch) {
			try {
				assertTrue(latch.await(5, TimeUnit.SECONDS), "상태 보정 ROOM 잠금 동기화 지점에 도달하지 못했습니다.");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("상태 보정 ROOM 잠금 동기화 대기 중 인터럽트되었습니다.", exception);
			}
		}
	}

}
