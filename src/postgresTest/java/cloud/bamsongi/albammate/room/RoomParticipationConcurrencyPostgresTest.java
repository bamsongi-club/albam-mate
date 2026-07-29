package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.concurrent.Callable;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.dto.RoomUpdateRequest;
import cloud.bamsongi.albammate.room.entity.Participation;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.service.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.RoomUpdateService;

@Testcontainers
@SpringBootTest
@Import(RoomParticipationConcurrencyPostgresTest.ConcurrencyTestConfiguration.class)
class RoomParticipationConcurrencyPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
	private static final long WAIT_SECONDS = 10;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_concurrency_test");

	@Autowired
	private RoomParticipationService roomParticipationService;

	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;

	@Autowired
	private RoomUpdateService roomUpdateService;

	@Autowired
	private RoomReadGate roomReadGate;

	@Autowired
	private ParticipationWriteFailureGate participationWriteFailureGate;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;

	@Autowired
	@Qualifier("participationRepository") private ParticipationRepository participationRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		roomReadGate.deactivate();
		participationWriteFailureGate.deactivate();
		jdbcTemplate.execute(
			"truncate table participations, rooms, users restart identity cascade");
	}

	@Test
	void 마지막_좌석_참가_두_건은_같은_버전을_읽어도_한_건만_성공하고_정원을_초과하지_않는다() throws Exception {
		long hostUserId = insertUser("last-seat-host", "방장");
		long firstParticipantId = insertUser("last-seat-first", "참가자1");
		long secondParticipantId = insertUser("last-seat-second", "참가자2");
		Room room = createRoom(hostUserId, 1);

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomParticipationService.participate(
					firstParticipantId, room.getId()),
				() -> roomParticipationService.participate(
					secondParticipantId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		assertEquals(1, results.stream().filter(CommandResult::successful).count());
		assertEquals(
			List.of(ErrorCode.CAPACITY_EXCEEDED),
			results.stream()
				.filter(result -> !result.successful())
				.map(CommandResult::errorCode)
				.toList());
		assertRoomInvariant(room.getId());
		assertEquals(
			RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
	}

	@Test
	void 참가_취소와_새_참가는_같은_버전을_읽은_뒤_재시도해_둘_다_성공한다() throws Exception {
		long hostUserId = insertUser("cancel-join-host", "방장");
		long cancelingParticipantId = insertUser("cancel-join-current", "기존참가자");
		long joiningParticipantId = insertUser("cancel-join-new", "새참가자");
		Room room = createRoom(hostUserId, 2);
		roomParticipationService.participate(cancelingParticipantId, room.getId());

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomParticipationCancelService.cancelParticipation(
					cancelingParticipantId, room.getId()),
				() -> roomParticipationService.participate(
					joiningParticipantId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		assertTrue(results.stream().allMatch(CommandResult::successful));
		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(1, storedRoom.getActiveParticipantCount());
		assertEquals(RoomStatus.RECRUITING, storedRoom.getStatus());
		assertEquals(
			ParticipationStatus.CANCELED,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), cancelingParticipantId)
				.orElseThrow()
				.getStatus());
		assertEquals(
			ParticipationStatus.ACTIVE,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), joiningParticipantId)
				.orElseThrow()
				.getStatus());
		assertRoomInvariant(room.getId());
	}

	@Test
	void 정원_축소와_새_참가는_같은_버전을_읽어도_최신_업무_규칙과_저장_불변식을_지킨다() throws Exception {
		long hostUserId = insertUser("update-join-host", "방장");
		long joiningParticipantId = insertUser("update-join-new", "새참가자");
		Room room = createRoom(hostUserId, 2);
		RoomUpdateRequest updateRequest = new RoomUpdateRequest();
		updateRequest.setRecruitmentCapacity(1);

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomUpdateService.updateRoom(
					hostUserId, room.getId(), updateRequest),
				() -> roomParticipationService.participate(
					joiningParticipantId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		CommandResult updateResult = results.get(0);
		CommandResult joinResult = results.get(1);
		assertTrue(joinResult.successful());
		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		if (updateResult.successful()) {
			assertTrue(joinResult.successful());
			assertEquals(1, storedRoom.getCapacity());
			assertEquals(1, storedRoom.getActiveParticipantCount());
			assertEquals(RoomStatus.CLOSED, storedRoom.getStatus());
		} else {
			assertEquals(
				ErrorCode.ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS,
				updateResult.errorCode());
			assertEquals(2, storedRoom.getCapacity());
			assertEquals(1, storedRoom.getActiveParticipantCount());
			assertEquals(RoomStatus.RECRUITING, storedRoom.getStatus());
		}
		assertRoomInvariant(room.getId());
	}

	@Test
	void 취소된_기존_참가와_신규_참가는_같은_버전을_읽은_뒤_재시도해_관계를_정확히_저장한다() throws Exception {
		long hostUserId = insertUser("rejoin-host", "방장");
		long activeParticipantId = insertUser("rejoin-active", "기존활성참가자");
		long rejoiningParticipantId = insertUser("rejoin-canceled", "재참가자");
		long newParticipantId = insertUser("rejoin-new", "새참가자");
		Room room = createRoom(hostUserId, 3);
		roomParticipationService.participate(activeParticipantId, room.getId());
		roomParticipationService.participate(rejoiningParticipantId, room.getId());
		roomParticipationCancelService.cancelParticipation(rejoiningParticipantId, room.getId());
		Long canceledParticipationId = participationRepository
			.findByRoomIdAndUserId(room.getId(), rejoiningParticipantId)
			.orElseThrow()
			.getId();

		roomReadGate.activate(room.getId());
		List<CommandResult> results;
		try {
			results = executeConcurrently(
				() -> roomParticipationService.participate(
					rejoiningParticipantId, room.getId()),
				() -> roomParticipationService.participate(
					newParticipantId, room.getId()));
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			roomReadGate.deactivate();
		}

		assertTrue(results.stream().allMatch(CommandResult::successful));
		Participation rejoinedParticipation = participationRepository
			.findByRoomIdAndUserId(room.getId(), rejoiningParticipantId)
			.orElseThrow();
		assertEquals(canceledParticipationId, rejoinedParticipation.getId());
		assertEquals(ParticipationStatus.ACTIVE, rejoinedParticipation.getStatus());
		assertEquals(
			ParticipationStatus.ACTIVE,
			participationRepository
				.findByRoomIdAndUserId(room.getId(), newParticipantId)
				.orElseThrow()
				.getStatus());
		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(3, storedRoom.getActiveParticipantCount());
		assertEquals(RoomStatus.CLOSED, storedRoom.getStatus());
		assertRoomInvariant(room.getId());
	}

	@Test
	void participation_저장_실패는_이미_flush된_방_변경까지_같은_트랜잭션에서_롤백한다() {
		long hostUserId = insertUser("rollback-host", "방장");
		long participantId = insertUser("rollback-participant", "참가자");
		Room room = createRoom(hostUserId, 1);

		participationWriteFailureGate.activate();
		try {
			assertThrows(
				DataIntegrityViolationException.class,
				() -> roomParticipationService.participate(participantId, room.getId()));
		} finally {
			participationWriteFailureGate.deactivate();
		}

		Room storedRoom = roomRepository.findById(room.getId()).orElseThrow();
		assertEquals(0, storedRoom.getActiveParticipantCount());
		assertEquals(RoomStatus.RECRUITING, storedRoom.getStatus());
		int activeParticipationCount = (int)participationRepository.findAll().stream()
			.filter(
				participation -> participation
					.getRoom()
					.getId()
					.equals(room.getId()))
			.filter(
				participation -> participation.getStatus() == ParticipationStatus.ACTIVE)
			.count();
		assertEquals(0, activeParticipationCount);
	}

	private List<CommandResult> executeConcurrently(Callable<?> first, Callable<?> second)
		throws Exception {
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> firstFuture = executor.submit(() -> execute(first));
			Future<CommandResult> secondFuture = executor.submit(() -> execute(second));
			CommandResult firstResult = firstFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			CommandResult secondResult = secondFuture.get(WAIT_SECONDS, TimeUnit.SECONDS);
			return List.of(firstResult, secondResult);
		} finally {
			executor.shutdown();
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
		}
	}

	private CommandResult execute(Callable<?> command) throws Exception {
		try {
			command.call();
			return CommandResult.success();
		} catch (BusinessException exception) {
			return CommandResult.failure(exception.getErrorCode());
		}
	}

	private void assertRoomInvariant(long roomId) {
		Room room = roomRepository.findById(roomId).orElseThrow();
		int activeParticipationCount = (int)participationRepository.findAll().stream()
			.filter(
				participation -> participation.getRoom().getId().equals(roomId))
			.filter(
				participation -> participation.getStatus() == ParticipationStatus.ACTIVE)
			.count();

		assertEquals(activeParticipationCount, room.getActiveParticipantCount());
		assertTrue(room.getActiveParticipantCount() >= 0);
		assertTrue(room.getActiveParticipantCount() <= room.getCapacity());
		assertEquals(
			room.getActiveParticipantCount() == room.getCapacity()
				? RoomStatus.CLOSED
				: RoomStatus.RECRUITING,
			room.getStatus());
	}

	private Room createRoom(long hostUserId, int capacity) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"동시성 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				NOW.plusSeconds(3600),
				"홍대 테스트 장소",
				capacity));
	}

	private long insertUser(String emailPrefix, String nickname) {
		String email = emailPrefix + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z', "
				+ "TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z')",
			email,
			nickname);
		Long userId = jdbcTemplate.queryForObject(
			"select id from users where email = ?", Long.class, email);
		assertNotNull(userId);
		return userId;
	}

	private record CommandResult(boolean successful, ErrorCode errorCode) {

		private static CommandResult success() {
			return new CommandResult(true, null);
		}

		private static CommandResult failure(ErrorCode errorCode) {
			return new CommandResult(false, errorCode);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ConcurrencyTestConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		RoomReadGate roomReadGate() {
			return new RoomReadGate();
		}

		@Bean
		ParticipationWriteFailureGate participationWriteFailureGate() {
			return new ParticipationWriteFailureGate();
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

		@Bean(name = "gatedParticipationRepository")
		@Primary
		ParticipationRepository gatedParticipationRepository(
			@Qualifier("participationRepository") ParticipationRepository delegate,
			ParticipationWriteFailureGate participationWriteFailureGate) {
			InvocationHandler handler = new GateAwareParticipationRepositoryInvocationHandler(
				delegate, participationWriteFailureGate);
			return (ParticipationRepository)Proxy.newProxyInstance(
				ParticipationRepository.class.getClassLoader(),
				new Class<?>[] {ParticipationRepository.class},
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

	private static final class GateAwareParticipationRepositoryInvocationHandler
		implements InvocationHandler {

		private final ParticipationRepository delegate;
		private final ParticipationWriteFailureGate participationWriteFailureGate;

		private GateAwareParticipationRepositoryInvocationHandler(
			ParticipationRepository delegate,
			ParticipationWriteFailureGate participationWriteFailureGate) {
			this.delegate = delegate;
			this.participationWriteFailureGate = participationWriteFailureGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
			try {
				Object result = method.invoke(delegate, args);
				if (method.getName().equals("save")
					&& args != null
					&& args.length == 1
					&& participationWriteFailureGate.consumeFailureWhenActive()) {
					delegate.flush();
					throw new DataIntegrityViolationException("테스트 전용 participation 저장 실패");
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

		void afterFindById(long roomId, Optional<Room> room) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || scenario.roomId != roomId) {
				return;
			}

			int readOrder = scenario.totalReadCount.getAndIncrement();
			if (readOrder >= 2) {
				return;
			}

			Long version = room.orElseThrow().getVersion();
			assertNotNull(version);
			scenario.initialReadCount.incrementAndGet();
			scenario.observedVersions.add(version);
			scenario.initialReads.countDown();
			try {
				assertTrue(scenario.initialReads.await(WAIT_SECONDS, TimeUnit.SECONDS));
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("동시 초기 findById 대기 중 인터럽트되었습니다.", exception);
			}
		}

		void assertExactlyTwoReadsOfOneVersion() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			assertEquals(2, scenario.initialReadCount.get());
			assertEquals(1, scenario.observedVersions.size());
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private static final class Scenario {

			private final long roomId;
			private final CountDownLatch initialReads = new CountDownLatch(2);
			private final AtomicInteger initialReadCount = new AtomicInteger();
			private final AtomicInteger totalReadCount = new AtomicInteger();
			private final Set<Long> observedVersions = java.util.concurrent.ConcurrentHashMap.newKeySet();

			private Scenario(long roomId) {
				this.roomId = roomId;
			}
		}
	}

	static final class ParticipationWriteFailureGate {

		private final AtomicReference<AtomicInteger> remainingFailures = new AtomicReference<>();

		void activate() {
			assertTrue(remainingFailures.compareAndSet(null, new AtomicInteger(1)));
		}

		boolean consumeFailureWhenActive() {
			AtomicInteger failures = remainingFailures.get();
			return failures != null && failures.getAndDecrement() > 0;
		}

		void deactivate() {
			remainingFailures.set(null);
		}
	}
}
