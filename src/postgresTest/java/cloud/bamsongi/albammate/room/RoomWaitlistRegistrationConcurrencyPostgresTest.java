package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.room.dto.MyRoomWaitlistResponse;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;

@Testcontainers
@SpringBootTest
@Import(RoomWaitlistRegistrationConcurrencyPostgresTest.WaitlistRegistrationConcurrencyConfiguration.class)
class RoomWaitlistRegistrationConcurrencyPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant REQUEST_TIME = Instant.parse("2026-08-10T00:00:00Z");
	private static final long WAIT_SECONDS = 10;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_waitlist_registration_concurrency_test");

	@Autowired
	private RoomParticipationService roomParticipationService;

	@Autowired
	private RoomWaitlistCommandService roomWaitlistCommandService;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private RoomVersionRaceGate roomVersionRaceGate;

	@AfterEach
	void tearDown() {
		roomVersionRaceGate.deactivate();
		jdbcTemplate.execute(
			"truncate table chat_rooms, room_waitlists, participations, rooms, users restart identity cascade");
	}

	@Test
	void T1_복수_사용자_동시_대기_등록은_재시도_뒤_FIFO_순번과_불변식으로_수렴한다() throws Exception {
		long hostUserId = insertUser("waitlist-race-host", "방장");
		long existingFirstParticipantId = insertUser("waitlist-race-existing-first", "기존참가자1");
		long existingSecondParticipantId = insertUser("waitlist-race-existing-second", "기존참가자2");
		long firstApplicantId = insertUser("waitlist-race-applicant-first", "신청자1");
		long secondApplicantId = insertUser("waitlist-race-applicant-second", "신청자2");
		Room room = createRoom(hostUserId, 2);

		roomParticipationService.participate(existingFirstParticipantId, room.getId());
		roomParticipationService.participate(existingSecondParticipantId, room.getId());
		Room fullRoom = roomRepository.findById(room.getId()).orElseThrow();
		int activeParticipantCountBeforeRegistration = fullRoom.getActiveParticipantCount();

		assertEquals(RoomStatus.CLOSED, fullRoom.getStatus());
		assertEquals(fullRoom.getCapacity(), activeParticipantCountBeforeRegistration);

		roomVersionRaceGate.activate(room.getId());
		RoomWaitlistCommandService.RegistrationResult firstResult;
		RoomWaitlistCommandService.RegistrationResult secondResult;
		try {
			List<RoomWaitlistCommandService.RegistrationResult> registrationResults = registerConcurrently(
				firstApplicantId, secondApplicantId, room.getId());
			firstResult = registrationResults.get(0);
			secondResult = registrationResults.get(1);
			roomVersionRaceGate.assertExactlyTwoInitialReadsOfOneVersion();
			roomVersionRaceGate.assertOneVersionClaimConflictAndRetry();
		} finally {
			roomVersionRaceGate.deactivate();
		}

		assertWaitingRegistration(firstResult);
		assertWaitingRegistration(secondResult);

		List<StoredWaitlist> storedWaitlists = findWaitingWaitlists(room.getId());
		assertEquals(2, storedWaitlists.size());
		assertDifferentPositiveQueueOrders(storedWaitlists);
		assertResponsePositionsMatchStoredFifo(
			storedWaitlists, firstApplicantId, firstResult, secondApplicantId, secondResult);

		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(RoomStatus.CLOSED, storedRoom.getStatus());
		assertEquals(activeParticipantCountBeforeRegistration, storedRoom.getActiveParticipantCount());
		assertEquals(activeParticipantCountBeforeRegistration, activeParticipationCount(room.getId()));
		assertEquals(0, activeApplicantParticipationCount(room.getId(), firstApplicantId, secondApplicantId));
		assertEquals(1, waitingRowCount(room.getId(), firstApplicantId));
		assertEquals(1, waitingRowCount(room.getId(), secondApplicantId));
	}

	private List<RoomWaitlistCommandService.RegistrationResult> registerConcurrently(
		long firstApplicantId, long secondApplicantId, long roomId) throws Exception {
		CountDownLatch workersReady = new CountDownLatch(2);
		CountDownLatch registrationMayStart = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<RoomWaitlistCommandService.RegistrationResult> firstFuture = executor.submit(
				() -> registerAfterStart(firstApplicantId, roomId, workersReady, registrationMayStart));
			Future<RoomWaitlistCommandService.RegistrationResult> secondFuture = executor.submit(
				() -> registerAfterStart(secondApplicantId, roomId, workersReady, registrationMayStart));

			await(workersReady, "두 대기 등록 worker가 시작 준비를 완료하지 못했습니다.");
			registrationMayStart.countDown();

			RoomWaitlistCommandService.RegistrationResult firstResult = firstFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			RoomWaitlistCommandService.RegistrationResult secondResult = secondFuture.get(WAIT_SECONDS,
				TimeUnit.SECONDS);
			return List.of(firstResult, secondResult);
		} finally {
			executor.shutdown();
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
		}
	}

	private RoomWaitlistCommandService.RegistrationResult registerAfterStart(
		long applicantId, long roomId, CountDownLatch workersReady, CountDownLatch registrationMayStart) {
		workersReady.countDown();
		await(registrationMayStart, "동시 대기 등록 시작 신호를 받지 못했습니다.");
		return roomWaitlistCommandService.register(applicantId, roomId);
	}

	private void assertWaitingRegistration(RoomWaitlistCommandService.RegistrationResult result) {
		assertTrue(result.created());
		MyRoomWaitlistResponse response = result.response();
		assertEquals(RoomWaitlistStatus.WAITING, response.waitlistStatus());
		assertNotNull(response.position());
	}

	private List<StoredWaitlist> findWaitingWaitlists(long roomId) {
		return jdbcTemplate.query(
			"""
				select user_id, queue_order
				from room_waitlists
				where room_id = ? and status = 'WAITING'
				order by queue_order asc
				""",
			(resultSet, rowNumber) -> new StoredWaitlist(resultSet.getLong("user_id"),
				resultSet.getLong("queue_order")),
			roomId);
	}

	private void assertDifferentPositiveQueueOrders(List<StoredWaitlist> storedWaitlists) {
		StoredWaitlist firstWaitlist = storedWaitlists.get(0);
		StoredWaitlist secondWaitlist = storedWaitlists.get(1);

		assertTrue(firstWaitlist.queueOrder() > 0);
		assertTrue(secondWaitlist.queueOrder() > 0);
		assertTrue(firstWaitlist.queueOrder() < secondWaitlist.queueOrder());
	}

	private void assertResponsePositionsMatchStoredFifo(
		List<StoredWaitlist> storedWaitlists,
		long firstApplicantId,
		RoomWaitlistCommandService.RegistrationResult firstResult,
		long secondApplicantId,
		RoomWaitlistCommandService.RegistrationResult secondResult) {
		StoredWaitlist firstInFifo = storedWaitlists.get(0);
		StoredWaitlist secondInFifo = storedWaitlists.get(1);

		assertEquals(1L, responsePositionFor(firstInFifo.userId(), firstApplicantId, firstResult,
			secondApplicantId, secondResult));
		assertEquals(2L, responsePositionFor(secondInFifo.userId(), firstApplicantId, firstResult,
			secondApplicantId, secondResult));
	}

	private long responsePositionFor(
		long userId,
		long firstApplicantId,
		RoomWaitlistCommandService.RegistrationResult firstResult,
		long secondApplicantId,
		RoomWaitlistCommandService.RegistrationResult secondResult) {
		if (userId == firstApplicantId) {
			return firstResult.response().position();
		}
		assertEquals(secondApplicantId, userId);
		return secondResult.response().position();
	}

	private int activeParticipationCount(long roomId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and status = 'ACTIVE'",
			Integer.class,
			roomId);
	}

	private int activeApplicantParticipationCount(long roomId, long firstApplicantId, long secondApplicantId) {
		return jdbcTemplate.queryForObject(
			"""
				select count(*)
				from participations
				where room_id = ? and user_id in (?, ?) and status = 'ACTIVE'
				""",
			Integer.class,
			roomId,
			firstApplicantId,
			secondApplicantId);
	}

	private int waitingRowCount(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"""
				select count(*)
				from room_waitlists
				where room_id = ? and user_id = ? and status = 'WAITING'
				""",
			Integer.class,
			roomId,
			userId);
	}

	private Room createRoom(long hostUserId, int capacity) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"대기 등록 경합 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				REQUEST_TIME.plusSeconds(3600),
				"홍대 테스트 장소",
				capacity));
	}

	private long insertUser(String emailPrefix, String nickname) {
		String email = emailPrefix + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-08-10T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-08-10T00:00:00Z')",
			email,
			nickname);
		Long userId = jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
		assertNotNull(userId);
		return userId;
	}

	private void await(CountDownLatch latch, String timeoutMessage) {
		try {
			assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), timeoutMessage);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("동시성 동기화 대기 중 인터럽트되었습니다.", exception);
		}
	}

	private record StoredWaitlist(long userId, long queueOrder) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class WaitlistRegistrationConcurrencyConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(REQUEST_TIME, ZoneOffset.UTC);
		}

		@Bean
		RoomVersionRaceGate roomVersionRaceGate() {
			return new RoomVersionRaceGate();
		}

		@Bean(name = "gatedRoomRepository")
		@Primary
		RoomRepository gatedRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate, RoomVersionRaceGate roomVersionRaceGate) {
			InvocationHandler handler = new GateAwareRoomRepositoryInvocationHandler(delegate, roomVersionRaceGate);
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(), new Class<?>[] {RoomRepository.class}, handler);
		}
	}

	private static final class GateAwareRoomRepositoryInvocationHandler implements InvocationHandler {

		private final RoomRepository delegate;
		private final RoomVersionRaceGate roomVersionRaceGate;

		private GateAwareRoomRepositoryInvocationHandler(
			RoomRepository delegate, RoomVersionRaceGate roomVersionRaceGate) {
			this.delegate = delegate;
			this.roomVersionRaceGate = roomVersionRaceGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				Object result = method.invoke(delegate, arguments);
				afterRoomRepositoryMethod(method, arguments, result);
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}

		private void afterRoomRepositoryMethod(Method method, Object[] arguments, Object result) {
			if (method.getName().equals("findById")
				&& arguments != null
				&& arguments.length == 1
				&& arguments[0] instanceof Long roomId
				&& result instanceof Optional<?> optional) {
				roomVersionRaceGate.afterInitialRoomRead(roomId, optional.map(Room.class::cast));
			}
			if (method.getName().equals("claimVersion")
				&& arguments != null
				&& arguments.length == 2
				&& arguments[0] instanceof Long roomId
				&& result instanceof Integer updatedRows) {
				roomVersionRaceGate.afterVersionClaim(roomId, updatedRows);
			}
		}
	}

	static final class RoomVersionRaceGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		void activate(long roomId) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId)));
		}

		void afterInitialRoomRead(long roomId, Optional<Room> room) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || scenario.roomId != roomId) {
				return;
			}

			int readOrder = scenario.totalReadCount.getAndIncrement();
			if (readOrder >= 2) {
				return;
			}

			Long roomVersion = room.orElseThrow().getVersion();
			assertNotNull(roomVersion);
			scenario.initialReadCount.incrementAndGet();
			scenario.observedVersions.add(roomVersion);
			scenario.initialReads.countDown();
			await(scenario.initialReads, "두 요청이 최초 ROOM version을 읽지 못했습니다.");
		}

		void afterVersionClaim(long roomId, int updatedRows) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || scenario.roomId != roomId) {
				return;
			}

			scenario.versionClaimAttemptCount.incrementAndGet();
			if (updatedRows == 0) {
				scenario.versionClaimConflictCount.incrementAndGet();
			}
		}

		void assertExactlyTwoInitialReadsOfOneVersion() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			assertEquals(2, scenario.initialReadCount.get());
			assertEquals(1, scenario.observedVersions.size());
		}

		void assertOneVersionClaimConflictAndRetry() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			assertEquals(1, scenario.versionClaimConflictCount.get());
			assertEquals(3, scenario.versionClaimAttemptCount.get());
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private void await(CountDownLatch latch, String timeoutMessage) {
			try {
				assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), timeoutMessage);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("ROOM version 동기화 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final CountDownLatch initialReads = new CountDownLatch(2);
			private final AtomicInteger initialReadCount = new AtomicInteger();
			private final AtomicInteger totalReadCount = new AtomicInteger();
			private final Set<Long> observedVersions = java.util.concurrent.ConcurrentHashMap.newKeySet();
			private final AtomicInteger versionClaimAttemptCount = new AtomicInteger();
			private final AtomicInteger versionClaimConflictCount = new AtomicInteger();

			private Scenario(long roomId) {
				this.roomId = roomId;
			}
		}
	}
}
