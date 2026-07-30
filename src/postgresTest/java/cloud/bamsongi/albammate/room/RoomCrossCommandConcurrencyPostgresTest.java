package cloud.bamsongi.albammate.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
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
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomStatusChangeService;
import cloud.bamsongi.albammate.room.service.command.RoomUpdateService;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;

@Testcontainers
@SpringBootTest
@Import(RoomCrossCommandConcurrencyPostgresTest.CrossCommandConcurrencyTestConfiguration.class)
class RoomCrossCommandConcurrencyPostgresTest {

	private static final String POSTGRES_IMAGE = "postgres:18.4";
	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
	private static final long WAIT_SECONDS = 10;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(POSTGRES_IMAGE)
		.withDatabaseName("albam_mate_cross_command_test");

	@Autowired
	private RoomParticipationService roomParticipationService;

	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;

	@Autowired
	private RoomStatusChangeService roomStatusChangeService;

	@Autowired
	private RoomStatusCorrectionCoordinator roomStatusCorrectionCoordinator;

	@Autowired
	private RoomUpdateService roomUpdateService;

	@Autowired
	private RoomReadGate roomReadGate;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;

	@Autowired
	@Qualifier("participationRepository") private ParticipationRepository participationRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		roomReadGate.deactivate();
		jdbcTemplate.execute("truncate table participations, rooms, users restart identity cascade");
	}

	@Test
	void 방_취소가_먼저_커밋되면_동시_참가는_재시도에서_모집중_아님_오류가_된다() throws Exception {
		long hostUserId = insertUser("cancel-first-host", "방장");
		long joiningUserId = insertUser("cancel-first-join", "참가자");
		Room room = createRoom(hostUserId, NOW.plusSeconds(3600), 2);

		roomReadGate.activate(room.getId(), Command.CANCEL_ROOM, Command.PARTICIPATE);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> cancel = submit(
				executor,
				Command.CANCEL_ROOM,
				() -> roomStatusChangeService.cancelRoom(hostUserId, room.getId()));
			Future<CommandResult> participate = submit(
				executor,
				Command.PARTICIPATE,
				() -> roomParticipationService.participate(joiningUserId, room.getId()));

			roomReadGate.awaitInitialReads();
			roomReadGate.release(Command.CANCEL_ROOM);
			assertTrue(await(cancel).successful());
			roomReadGate.release(Command.PARTICIPATE);

			CommandResult participateResult = await(participate);
			assertEquals(ErrorCode.ROOM_NOT_RECRUITING, participateResult.errorCode());
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			releaseAndStop(executor);
		}

		Room storedRoom = currentRoom(room.getId());
		assertEquals(RoomStatus.CANCELED, storedRoom.getStatus());
		assertEquals(0, storedRoom.getActiveParticipantCount());
		assertEquals(0, participationCount(room.getId(), joiningUserId));
		assertRoomStorageInvariant(room.getId());
	}

	@Test
	void 활성_참가자_취소가_먼저_커밋돼도_최초_읽기의_수정_조건은_보존된다() throws Exception {
		long hostUserId = insertUser("cancel-update-host", "방장");
		long participantUserId = insertUser("cancel-update-participant", "참가자");
		Room room = createRoom(hostUserId, NOW.plusSeconds(3600), 2);
		roomParticipationService.participate(participantUserId, room.getId());
		String originalTitle = currentRoom(room.getId()).getTitle();
		RoomUpdateRequest request = new RoomUpdateRequest();
		request.setTitle("바뀌면 안 되는 제목");

		roomReadGate.activate(room.getId(), Command.CANCEL_PARTICIPATION, Command.UPDATE);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> cancel = submit(
				executor,
				Command.CANCEL_PARTICIPATION,
				() -> roomParticipationCancelService.cancelParticipation(participantUserId, room.getId()));
			Future<CommandResult> update = submit(
				executor,
				Command.UPDATE,
				() -> roomUpdateService.updateRoom(hostUserId, room.getId(), request));

			roomReadGate.awaitInitialReads();
			roomReadGate.release(Command.CANCEL_PARTICIPATION);
			assertTrue(await(cancel).successful());
			roomReadGate.release(Command.UPDATE);

			CommandResult updateResult = await(update);
			assertEquals(
				ErrorCode.ROOM_UPDATE_NOT_ALLOWED_WITH_ACTIVE_PARTICIPANTS, updateResult.errorCode());
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			releaseAndStop(executor);
		}

		Room storedRoom = currentRoom(room.getId());
		assertEquals(originalTitle, storedRoom.getTitle());
		assertEquals(0, storedRoom.getActiveParticipantCount());
		assertParticipationState(room.getId(), participantUserId, ParticipationStatus.CANCELED, true);
		assertRoomStorageInvariant(room.getId());
	}

	@Test
	void 자동_종료가_먼저_커밋되면_동시_수동_종료는_재시도에서_멱등_성공한다() throws Exception {
		long hostUserId = insertUser("finish-host", "방장");
		Room room = createRoom(hostUserId, NOW.minus(Room.AUTOMATIC_FINISH_AFTER_START), 2);
		closeRoomBeforeAutomaticFinish(room.getId());
		Long versionBefore = currentRoom(room.getId()).getVersion();

		roomReadGate.activate(room.getId(), Command.RECONCILE, Command.FINISH);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> reconcile = submit(
				executor,
				Command.RECONCILE,
				() -> {
					roomStatusCorrectionCoordinator.correctRoom(room.getId(), NOW);
					return null;
				});
			Future<CommandResult> finish = submit(
				executor,
				Command.FINISH,
				() -> roomStatusChangeService.finishRoom(hostUserId, room.getId()));

			roomReadGate.awaitInitialReads();
			roomReadGate.release(Command.RECONCILE);
			assertTrue(await(reconcile).successful());
			roomReadGate.release(Command.FINISH);
			assertTrue(await(finish).successful());
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			releaseAndStop(executor);
		}

		Room storedRoom = currentRoom(room.getId());
		assertEquals(RoomStatus.FINISHED, storedRoom.getStatus());
		assertEquals(versionBefore + 1, storedRoom.getVersion());
		assertRoomStorageInvariant(room.getId());
	}

	@Test
	void 동일_사용자의_동시_참가는_한_건만_성공하고_관계는_한_행이다() throws Exception {
		long hostUserId = insertUser("same-join-host", "방장");
		long participantUserId = insertUser("same-join-participant", "참가자");
		Room room = createRoom(hostUserId, NOW.plusSeconds(3600), 2);

		roomReadGate.activate(room.getId(), Command.FIRST, Command.SECOND);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> first = submit(
				executor,
				Command.FIRST,
				() -> roomParticipationService.participate(participantUserId, room.getId()));
			Future<CommandResult> second = submit(
				executor,
				Command.SECOND,
				() -> roomParticipationService.participate(participantUserId, room.getId()));

			roomReadGate.awaitInitialReads();
			roomReadGate.releaseAll();
			List<CommandResult> results = List.of(await(first), await(second));
			assertEquals(1, results.stream().filter(CommandResult::successful).count());
			assertEquals(
				List.of(ErrorCode.ALREADY_PARTICIPATING),
				results.stream().filter(result -> !result.successful()).map(CommandResult::errorCode).toList());
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			releaseAndStop(executor);
		}

		Room storedRoom = currentRoom(room.getId());
		assertEquals(1, storedRoom.getActiveParticipantCount());
		assertEquals(1, participationCount(room.getId(), participantUserId));
		assertParticipationState(room.getId(), participantUserId, ParticipationStatus.ACTIVE, false);
		assertRoomStorageInvariant(room.getId());
	}

	@Test
	void 동일_사용자의_동시_참가_취소는_한_건만_성공하고_관계_ID를_유지한다() throws Exception {
		long hostUserId = insertUser("same-cancel-host", "방장");
		long participantUserId = insertUser("same-cancel-participant", "참가자");
		Room room = createRoom(hostUserId, NOW.plusSeconds(3600), 2);
		roomParticipationService.participate(participantUserId, room.getId());
		Long participationId = participationRepository
			.findByRoomIdAndUserId(room.getId(), participantUserId)
			.orElseThrow()
			.getId();

		roomReadGate.activate(room.getId(), Command.FIRST, Command.SECOND);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandResult> first = submit(
				executor,
				Command.FIRST,
				() -> roomParticipationCancelService.cancelParticipation(participantUserId, room.getId()));
			Future<CommandResult> second = submit(
				executor,
				Command.SECOND,
				() -> roomParticipationCancelService.cancelParticipation(participantUserId, room.getId()));

			roomReadGate.awaitInitialReads();
			roomReadGate.releaseAll();
			List<CommandResult> results = List.of(await(first), await(second));
			assertEquals(1, results.stream().filter(CommandResult::successful).count());
			assertEquals(
				List.of(ErrorCode.PARTICIPATION_NOT_FOUND),
				results.stream().filter(result -> !result.successful()).map(CommandResult::errorCode).toList());
			roomReadGate.assertExactlyTwoReadsOfOneVersion();
		} finally {
			releaseAndStop(executor);
		}

		Participation storedParticipation = participationRepository
			.findByRoomIdAndUserId(room.getId(), participantUserId)
			.orElseThrow();
		assertEquals(participationId, storedParticipation.getId());
		assertEquals(0, currentRoom(room.getId()).getActiveParticipantCount());
		assertEquals(1, participationCount(room.getId(), participantUserId));
		assertParticipationState(room.getId(), participantUserId, ParticipationStatus.CANCELED, true);
		assertRoomStorageInvariant(room.getId());
	}

	private Future<CommandResult> submit(
		ExecutorService executor, Command command, Callable<?> operation) {
		return executor.submit(
			() -> {
				roomReadGate.bind(command);
				try {
					operation.call();
					return CommandResult.success();
				} catch (BusinessException exception) {
					return CommandResult.failure(exception.getErrorCode());
				} finally {
					roomReadGate.unbind();
				}
			});
	}

	private CommandResult await(Future<CommandResult> future) throws Exception {
		return future.get(WAIT_SECONDS, TimeUnit.SECONDS);
	}

	private void releaseAndStop(ExecutorService executor) throws Exception {
		try {
			roomReadGate.releaseAll();
		} finally {
			executor.shutdown();
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				executor.shutdownNow();
				assertTrue(executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS));
			}
			roomReadGate.deactivate();
		}
	}

	private void closeRoomBeforeAutomaticFinish(long roomId) {
		Room room = currentRoom(roomId);
		assertTrue(room.reconcileStateAt(NOW.minusSeconds(1)));
		roomRepository.saveAndFlush(room);
		assertEquals(RoomStatus.CLOSED, currentRoom(roomId).getStatus());
	}

	private Room createRoom(long hostUserId, Instant startsAt, int capacity) {
		return roomRepository.saveAndFlush(
			Room.create(
				hostUserId,
				RoomType.PERSON_FOCUSED,
				"교차 명령 동시성 테스트 방",
				null,
				null,
				ExperienceLevel.ALL_LEVELS,
				false,
				startsAt,
				"홍대 테스트 장소",
				capacity));
	}

	private Room currentRoom(long roomId) {
		return roomRepository.findById(roomId).orElseThrow();
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

	private int participationCount(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ?",
			Integer.class,
			roomId,
			userId);
	}

	private void assertParticipationState(
		long roomId, long userId, ParticipationStatus expectedStatus, boolean canceledAtRequired) {
		Participation participation = participationRepository
			.findByRoomIdAndUserId(roomId, userId)
			.orElseThrow();
		assertEquals(expectedStatus, participation.getStatus());
		if (canceledAtRequired) {
			assertNotNull(participation.getCanceledAt());
		} else {
			assertNull(participation.getCanceledAt());
		}
	}

	private void assertRoomStorageInvariant(long roomId) {
		Room room = currentRoom(roomId);
		Integer activeParticipationCount = jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and status = 'ACTIVE'",
			Integer.class,
			roomId);
		Integer malformedParticipationCount = jdbcTemplate.queryForObject(
			"select count(*) from participations "
				+ "where room_id = ? and ((status = 'ACTIVE' and canceled_at is not null) "
				+ "or (status = 'CANCELED' and canceled_at is null))",
			Integer.class,
			roomId);

		assertEquals(activeParticipationCount.intValue(), room.getActiveParticipantCount());
		assertTrue(room.getActiveParticipantCount() >= 0);
		assertTrue(room.getActiveParticipantCount() <= room.getCapacity());
		assertEquals(0, malformedParticipationCount.intValue());
	}

	private record CommandResult(boolean successful, ErrorCode errorCode) {

		private static CommandResult success() {
			return new CommandResult(true, null);
		}

		private static CommandResult failure(ErrorCode errorCode) {
			return new CommandResult(false, errorCode);
		}
	}

	private enum Command {
		CANCEL_ROOM,
		PARTICIPATE,
		CANCEL_PARTICIPATION,
		UPDATE,
		RECONCILE,
		FINISH,
		FIRST,
		SECOND
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CrossCommandConcurrencyTestConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

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

	private static final class GateAwareRoomRepositoryInvocationHandler implements InvocationHandler {

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
		private final ThreadLocal<Command> boundCommand = new ThreadLocal<>();

		void activate(long roomId, Command first, Command second) {
			assertTrue(activeScenario.compareAndSet(null, new Scenario(roomId, Set.of(first, second))));
		}

		void bind(Command command) {
			boundCommand.set(command);
		}

		void unbind() {
			boundCommand.remove();
		}

		void afterFindById(long roomId, Optional<Room> room) {
			Scenario scenario = activeScenario.get();
			Command command = boundCommand.get();
			if (scenario == null || scenario.roomId != roomId || command == null) {
				return;
			}

			if (!scenario.initialReadCommands.remove(command)) {
				return;
			}

			Long version = room.orElseThrow().getVersion();
			assertNotNull(version);
			scenario.initialReadCount.incrementAndGet();
			scenario.observedVersions.add(version);
			scenario.initialReads.countDown();
			await(scenario.releaseSignals.get(command));
		}

		void awaitInitialReads() {
			Scenario scenario = requireScenario();
			await(scenario.initialReads);
		}

		void release(Command command) {
			requireScenario().releaseSignals.get(command).countDown();
		}

		void releaseAll() {
			Scenario scenario = activeScenario.get();
			if (scenario != null) {
				scenario.releaseSignals.values().forEach(signal -> signal.countDown());
			}
		}

		void assertExactlyTwoReadsOfOneVersion() {
			Scenario scenario = requireScenario();
			assertEquals(2, scenario.initialReadCount.get());
			assertEquals(1, scenario.observedVersions.size());
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private Scenario requireScenario() {
			Scenario scenario = activeScenario.get();
			assertNotNull(scenario);
			return scenario;
		}

		private void await(CountDownLatch latch) {
			try {
				assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "동기화 지점에 도달하지 못했습니다.");
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("동시 명령 동기화 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final CountDownLatch initialReads = new CountDownLatch(2);
			private final Set<Command> initialReadCommands;
			private final Set<Long> observedVersions = java.util.concurrent.ConcurrentHashMap.newKeySet();
			private final AtomicInteger initialReadCount = new AtomicInteger();
			private final EnumMap<Command, CountDownLatch> releaseSignals = new EnumMap<>(Command.class);

			private Scenario(long roomId, Set<Command> commands) {
				this.roomId = roomId;
				this.initialReadCommands = java.util.concurrent.ConcurrentHashMap.newKeySet();
				this.initialReadCommands.addAll(commands);
				for (Command command : commands) {
					releaseSignals.put(command, new CountDownLatch(1));
				}
			}
		}
	}
}
