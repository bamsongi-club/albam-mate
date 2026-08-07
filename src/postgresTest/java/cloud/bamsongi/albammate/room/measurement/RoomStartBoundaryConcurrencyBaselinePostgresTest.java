package cloud.bamsongi.albammate.room.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;
import jakarta.persistence.OptimisticLockException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 시작 경계 상태 보정과 ROOM 명령의 동시 실행 결과를 같은 ROOM-10 산식으로 측정한다. */
@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
	"spring.datasource.hikari.maximum-pool-size=10",
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false"})
@Import(RoomStartBoundaryConcurrencyBaselinePostgresTest.BaselineTestConfiguration.class)
class RoomStartBoundaryConcurrencyBaselinePostgresTest {

	private static final Instant NOW = Instant.parse("2026-08-07T00:00:00Z");
	private static final String FIXTURE_SEED = "ROOM-10C-20260807";
	private static final String FIXTURE_STATEMENT_MARKER = "room10c_fixture_marker";
	private static final String RETRY_EVENT = "room_10c_retry";
	private static final int MEASUREMENT_ROUNDS = 3;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
		.withDatabaseName("albam_mate_room_10c");

	@Autowired
	private RoomStatusCorrectionCoordinator roomStatusCorrectionCoordinator;

	@Autowired
	private RoomParticipationService roomParticipationService;

	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;

	@Autowired
	private RoomWaitlistCommandService roomWaitlistCommandService;

	@Autowired
	private RoomOptimisticLockRetrier roomOptimisticLockRetrier;

	@Autowired
	private RoomConcurrencyBaselineSupport baselineSupport;

	@Autowired
	private RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate;

	@Autowired
	private StartBoundaryCommitOrderGate commitOrderGate;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;

	@Autowired
	@Qualifier("roomWaitlistRepository") private RoomWaitlistRepository roomWaitlistRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private Environment springEnvironment;

	@BeforeEach
	void enablePgStatStatements() {
		jdbcTemplate.execute("create extension if not exists pg_stat_statements");
	}

	@AfterEach
	void tearDown() {
		roomReadGate.deactivate();
		commitOrderGate.deactivate();
		jdbcTemplate.execute("truncate table participations, room_waitlists, rooms, users restart identity cascade");
	}

	@Test
	void 시작_경계_상태_보정과_직접_참가는_양쪽_확정_순서에서_시작_뒤_ACTIVE를_남기지_않는다()
		throws Exception {
		for (CommitOrder order : CommitOrder.values()) {
			StartBoundaryFixture fixture = createDirectJoinFixture(order.fixtureSuffix());
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureRound(fixture, order);

			assertExpectedBusinessFailure(measurement, ErrorCode.ROOM_NOT_RECRUITING);
			assertEquals(0, activeParticipationCount(fixture.room().getId(), fixture.mutationUserId()));
			assertStartBoundaryInvariant(fixture);
		}
	}

	@Test
	void 시작_경계_상태_보정과_대기_활성화는_양쪽_확정_순서에서_WAITING을_남기지_않고_만료한다()
		throws Exception {
		for (CommitOrder order : CommitOrder.values()) {
			StartBoundaryFixture fixture = createWaitlistRegistrationFixture(order.fixtureSuffix());
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureRound(fixture, order);

			assertExpectedBusinessFailure(measurement, ErrorCode.WAITLIST_NOT_AVAILABLE);
			assertEquals(RoomWaitlistStatus.EXPIRED,
				waitlistStatus(fixture.room().getId(), fixture.waitingUserIds().get(0)));
			assertStartBoundaryInvariant(fixture);
		}
	}

	@Test
	void 시작_경계_상태_보정과_참가_취소_자동_승격은_양쪽_확정_순서에서_승격하지_않는다() throws Exception {
		for (CommitOrder order : CommitOrder.values()) {
			StartBoundaryFixture fixture = createParticipationCancellationFixture(order.fixtureSuffix());
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureRound(fixture, order);

			assertExpectedBusinessFailure(measurement, ErrorCode.INVALID_ROOM_STATUS_TRANSITION);
			assertEquals(ParticipationStatus.ACTIVE,
				participationStatus(fixture.room().getId(), fixture.mutationUserId()));
			assertEquals(RoomWaitlistStatus.EXPIRED,
				waitlistStatus(fixture.room().getId(), fixture.waitingUserIds().get(0)));
			assertStartBoundaryInvariant(fixture);
		}
	}

	@Test
	void 시작_경계_상태_보정과_대기_취소는_양쪽_확정_순서에서_확정_상태를_보존한다() throws Exception {
		for (CommitOrder order : CommitOrder.values()) {
			StartBoundaryFixture fixture = createWaitlistCancellationFixture(order.fixtureSuffix());
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureRound(fixture, order);

			if (order == CommitOrder.CORRECTION_FIRST) {
				assertExpectedBusinessFailure(measurement, ErrorCode.WAITLIST_ENTRY_NOT_FOUND);
				assertEquals(RoomWaitlistStatus.EXPIRED,
					waitlistStatus(fixture.room().getId(), fixture.mutationUserId()));
			} else {
				assertEquals(2, measurement.successCount());
				assertEquals(0, measurement.businessFailureCount());
				assertEquals(RoomWaitlistStatus.CANCELED,
					waitlistStatus(fixture.room().getId(), fixture.mutationUserId()));
			}
			assertEquals(RoomWaitlistStatus.EXPIRED,
				waitlistStatus(fixture.room().getId(), fixture.waitingUserIds().get(1)));
			assertStartBoundaryInvariant(fixture);
		}
	}

	@Test
	void 시작_경계_경합_측정_round는_공통_RAW_형식으로_결과와_비용을_기록한다() throws Exception {
		baselineSupport.clearCollectedRounds();
		for (StartBoundaryScenario scenario : StartBoundaryScenario.values()) {
			for (CommitOrder order : CommitOrder.values()) {
				runPreparationRound(
					createFixture(scenario, scenario.fixturePrefix() + "-prepare-" + order.fixtureSuffix()),
					order);
				for (int round = 1; round <= MEASUREMENT_ROUNDS; round++) {
					StartBoundaryFixture fixture = createFixture(
						scenario, scenario.fixturePrefix() + "-" + order.fixtureSuffix() + "-" + round);
					RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureRound(fixture, order);

					assertRawMeasurement(measurement, fixture.scenario().measurementName(order));
					assertStartBoundaryInvariant(fixture);
				}
			}
		}

		assertMeasurementReportPersisted();
	}

	@Test
	void 보존된_ROOM_10a와_ROOM_10b_원자료는_수준별_통합_비교_입력으로_읽힌다() throws Exception {
		JsonNode room10a = readMeasurementInput("room-10a", 9);
		JsonNode room10b = readMeasurementInput("room-10b", 21);

		assertEquals("b75903219c552628a42e071f8e2cd0ba97d8a767",
			room10a.path("environment").path("gitSha").asText());
		assertEquals(room10a.path("environment").path("gitSha").asText(),
			room10b.path("environment").path("gitSha").asText());
		assertEquals(List.of(2, 4, 8), concurrencyLevels(room10a));
		assertEquals(List.of(2, 4, 8), concurrencyLevels(room10b));
		assertTrue(room10a.path("rounds").get(0).path("rawRecord").asText().startsWith("ROOM10A_RAW "));
		assertTrue(room10b.path("rounds").get(0).path("rawRecord").asText().startsWith("ROOM10A_RAW "));
	}

	@Test
	void 각_시작_경계_측정_round_뒤_PostgreSQL_불변식이_유지된다() throws Exception {
		for (StartBoundaryScenario scenario : StartBoundaryScenario.values()) {
			for (CommitOrder order : CommitOrder.values()) {
				StartBoundaryFixture fixture = createFixture(scenario, "invariant-" + scenario.fixturePrefix()
					+ "-" + order.fixtureSuffix());
				measureRound(fixture, order);

				assertStartBoundaryInvariant(fixture);
			}
		}
	}

	@Test
	void 시작_경계_경합의_업무_실패_소진_기술_실패는_서로_분류된다() {
		RoomConcurrencyBaselineSupport.RetryMeasurement exhausted = baselineSupport.newRetryMeasurement();
		BusinessException exhaustedException = assertThrows(
			BusinessException.class,
			() -> exhausted.execute(roomOptimisticLockRetrier, RETRY_EVENT, () -> {
				throw new OptimisticLockException("deterministic conflict");
			}));
		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exhaustedException.getErrorCode());
		assertEquals(3, exhausted.attemptCount());
		assertEquals(3, exhausted.conflictCount());
		assertEquals(2, exhausted.retryCount());
		assertTrue(exhausted.exhausted());

		AtomicInteger invocation = new AtomicInteger();
		RoomConcurrencyBaselineSupport.RetryMeasurement businessFailure = baselineSupport.newRetryMeasurement();
		BusinessException businessException = assertThrows(
			BusinessException.class,
			() -> businessFailure.execute(roomOptimisticLockRetrier, RETRY_EVENT, () -> {
				if (invocation.getAndIncrement() == 0) {
					throw new OptimisticLockException("deterministic conflict");
				}
				throw new BusinessException(ErrorCode.ROOM_NOT_RECRUITING);
			}));
		assertEquals(ErrorCode.ROOM_NOT_RECRUITING, businessException.getErrorCode());
		assertEquals(2, businessFailure.attemptCount());
		assertEquals(1, businessFailure.conflictCount());
		assertEquals(1, businessFailure.businessFailureCount());
		assertFalse(businessFailure.exhausted());

		RoomConcurrencyBaselineSupport.RetryMeasurement technicalFailure = baselineSupport.newRetryMeasurement();
		assertThrows(IllegalStateException.class, () -> technicalFailure.execute(
			roomOptimisticLockRetrier, RETRY_EVENT, () -> {
				throw new IllegalStateException("deterministic technical failure");
			}));
		assertEquals(1, technicalFailure.technicalFailureCount());
	}

	private RoomConcurrencyBaselineSupport.RoundMeasurement measureRound(
		StartBoundaryFixture fixture, CommitOrder order) throws Exception {
		commitOrderGate.activate(fixture.room().getId(), order);
		roomReadGate.activate(fixture.room().getId(), 2);
		try {
			jdbcTemplate.execute("select id as room10c_fixture_marker from users limit 0");
			assertTrue(baselineSupport.statementCallsContaining(FIXTURE_STATEMENT_MARKER) > 0L);
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = baselineSupport.measureRound(
				fixture.scenario().measurementName(order), 2, roomReadGate,
				List.of(correctionCommand(fixture.room().getId()), mutationCommand(fixture)));
			assertEquals(0L, baselineSupport.statementCallsContaining(FIXTURE_STATEMENT_MARKER));
			roomReadGate.assertInitialReadsShareOneVersion();
			return measurement;
		} finally {
			roomReadGate.deactivate();
			commitOrderGate.deactivate();
		}
	}

	private void runPreparationRound(StartBoundaryFixture fixture, CommitOrder order) throws Exception {
		commitOrderGate.activate(fixture.room().getId(), order);
		roomReadGate.activate(fixture.room().getId(), 2);
		try {
			baselineSupport.runPreparationRound(List.of(
				correctionCommand(fixture.room().getId()),
				() -> {
					try {
						return mutationCommand(fixture).call();
					} catch (BusinessException exception) {
						return null;
					}
				}));
			roomReadGate.assertInitialReadsShareOneVersion();
		} finally {
			roomReadGate.deactivate();
			commitOrderGate.deactivate();
		}
	}

	private Callable<?> correctionCommand(long roomId) {
		return () -> executeOrdered(StartBoundaryCommitOrderGate.Operation.CORRECTION, () -> {
			roomStatusCorrectionCoordinator.correctRoom(roomId, NOW);
			return null;
		});
	}

	private Callable<?> mutationCommand(StartBoundaryFixture fixture) {
		return () -> executeOrdered(StartBoundaryCommitOrderGate.Operation.MUTATION, () -> {
			switch (fixture.scenario()) {
				case DIRECT_JOIN ->
					roomParticipationService.participate(fixture.mutationUserId(), fixture.room().getId());
				case WAITLIST_REGISTRATION -> roomWaitlistCommandService.register(
					fixture.mutationUserId(), fixture.room().getId());
				case PARTICIPATION_CANCELLATION -> roomParticipationCancelService.cancelParticipation(
					fixture.mutationUserId(), fixture.room().getId());
				case WAITLIST_CANCELLATION -> roomWaitlistCommandService.cancel(
					fixture.mutationUserId(), fixture.room().getId());
			}
			return null;
		});
	}

	private Object executeOrdered(StartBoundaryCommitOrderGate.Operation operation, Callable<?> command)
		throws Exception {
		commitOrderGate.mark(operation);
		try {
			return command.call();
		} finally {
			commitOrderGate.complete(operation);
			commitOrderGate.clear();
		}
	}

	private void assertExpectedBusinessFailure(
		RoomConcurrencyBaselineSupport.RoundMeasurement measurement, ErrorCode expectedErrorCode) {
		assertEquals(2, measurement.totalRequestCount());
		assertEquals(1, measurement.successCount());
		assertEquals(1, measurement.businessFailureCount());
		assertEquals(0, measurement.concurrencyFailureCount());
		assertEquals(0, measurement.technicalFailureCount());
		assertTrue(measurement.hasOnlyBusinessError(expectedErrorCode));
	}

	private void assertRawMeasurement(
		RoomConcurrencyBaselineSupport.RoundMeasurement measurement, String scenario) {
		assertEquals(2, measurement.totalRequestCount());
		assertEquals(measurement.totalRequestCount(), measurement.successCount() + measurement.businessFailureCount()
			+ measurement.concurrencyFailureCount() + measurement.technicalFailureCount());
		assertEquals(measurement.totalRequestCount(), measurement.retryCount(0) + measurement.retryCount(1)
			+ measurement.retryCount(2));
		assertEquals(measurement.totalRetryCount(), measurement.retryAttemptLogCount());
		assertEquals(measurement.concurrencyFailureCount(), measurement.exhaustedCount());
		assertTrue(measurement.requestDurationsNanos().stream().allMatch(duration -> duration > 0));
		assertTrue(measurement.postgresCost().statementCalls() > 0);
		assertTrue(measurement.rawRecord().startsWith("ROOM10A_RAW scenario=" + scenario + " concurrencyLevel=2 "));
		assertTrue(measurement.rawRecord().contains(" conflictRate="));
		assertTrue(measurement.rawRecord().contains(" responseNanos="));
		assertTrue(measurement.rawRecord().contains(" totalExecMs="));
	}

	private void assertStartBoundaryInvariant(StartBoundaryFixture fixture) {
		long roomId = fixture.room().getId();
		RoomConcurrencyBaselineSupport.RoomInvariant invariant = baselineSupport.readRoomInvariant(roomId);
		assertEquals(RoomStatus.CLOSED, invariant.status());
		assertTrue(roomVersion(roomId) >= fixture.versionBeforeMeasurement());
		assertEquals(invariant.activeParticipantCount(), invariant.activeParticipationCount());
		assertTrue(invariant.activeParticipantCount() >= 0);
		assertTrue(invariant.activeParticipantCount() <= invariant.capacity());
		assertFalse(invariant.hasDuplicatedActiveParticipation());
		assertEquals(0, activeWaitingCount(roomId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from room_waitlists where room_id = ? and status = 'PROMOTED'", Integer.class, roomId));
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*)
			from participations participation
			join room_waitlists waitlist on waitlist.room_id = participation.room_id
				and waitlist.user_id = participation.user_id
			where participation.room_id = ? and participation.status = 'ACTIVE' and waitlist.status = 'WAITING'
			""", Integer.class, roomId));
	}

	private void assertMeasurementReportPersisted() throws Exception {
		int expectedRoundCount = StartBoundaryScenario.values().length * CommitOrder.values().length
			* MEASUREMENT_ROUNDS;
		Path reportPath = baselineSupport.writeMeasurementReport(objectMapper, "room-10c", Map.ofEntries(
			Map.entry("postgresImage", postgres.getDockerImageName()),
			Map.entry("sharedPreloadLibraries",
				jdbcTemplate.queryForObject("show shared_preload_libraries", String.class)),
			Map.entry("fixtureSeed", FIXTURE_SEED),
			Map.entry("fixedClock", NOW.toString()),
			Map.entry("concurrencyLevel", "2"),
			Map.entry("schedulingEnabled", springEnvironment.getProperty("spring.task.scheduling.enabled", "true")),
			Map.entry("notificationRelayEnabled",
				springEnvironment.getProperty("app.notification.relay.enabled", "true")),
			Map.entry("chatRetentionEnabled",
				springEnvironment.getProperty("app.chat.retention.enabled", "true"))));

		assertTrue(Files.exists(reportPath));
		RoomConcurrencyBaselineSupport.MeasurementReport report = objectMapper.readValue(
			Files.readString(reportPath), RoomConcurrencyBaselineSupport.MeasurementReport.class);
		assertEquals("room-10c", report.reportName());
		assertEquals(expectedRoundCount, report.rounds().size());
		assertTrue(report.rounds().stream().allMatch(round -> round.concurrencyLevel() == 2));
		assertTrue(report.rounds().stream().allMatch(round -> round.rawRecord().startsWith("ROOM10A_RAW ")));
	}

	private JsonNode readMeasurementInput(String reportName, int expectedRoundCount) throws Exception {
		Path path = Path.of("docs", "measurements", "results", reportName, reportName + ".json");
		assertTrue(Files.exists(path));
		JsonNode report = objectMapper.readTree(Files.readString(path));
		assertEquals(reportName, report.path("reportName").asText());
		assertEquals(expectedRoundCount, report.path("rounds").size());
		return report;
	}

	private List<Integer> concurrencyLevels(JsonNode report) {
		return report.path("rounds").valueStream()
			.map(round -> round.path("concurrencyLevel").asInt())
			.distinct()
			.sorted()
			.toList();
	}

	private StartBoundaryFixture createFixture(StartBoundaryScenario scenario, String suffix) {
		return switch (scenario) {
			case DIRECT_JOIN -> createDirectJoinFixture(suffix);
			case WAITLIST_REGISTRATION -> createWaitlistRegistrationFixture(suffix);
			case PARTICIPATION_CANCELLATION -> createParticipationCancellationFixture(suffix);
			case WAITLIST_CANCELLATION -> createWaitlistCancellationFixture(suffix);
		};
	}

	private StartBoundaryFixture createDirectJoinFixture(String suffix) {
		long hostUserId = insertUser(suffix + "-host", "방장");
		long joiningUserId = insertUser(suffix + "-joining", "참가자");
		Room room = createFutureRoom(hostUserId, 2);
		moveStartAtToBoundary(room.getId());
		return fixture(StartBoundaryScenario.DIRECT_JOIN, room, joiningUserId, List.of());
	}

	private StartBoundaryFixture createWaitlistRegistrationFixture(String suffix) {
		long hostUserId = insertUser(suffix + "-host", "방장");
		long activeUserId = insertUser(suffix + "-active", "기존 참가자");
		long registeringUserId = insertUser(suffix + "-registering", "대기 신청자");
		long existingWaitingUserId = insertUser(suffix + "-waiting", "기존 대기자");
		Room room = createFutureRoom(hostUserId, 1);
		roomParticipationService.participate(activeUserId, room.getId());
		createWaiting(room.getId(), existingWaitingUserId, 10L);
		moveStartAtToBoundary(room.getId());
		return fixture(StartBoundaryScenario.WAITLIST_REGISTRATION, room, registeringUserId,
			List.of(existingWaitingUserId));
	}

	private StartBoundaryFixture createParticipationCancellationFixture(String suffix) {
		long hostUserId = insertUser(suffix + "-host", "방장");
		long activeUserId = insertUser(suffix + "-active", "취소 참가자");
		long waitingUserId = insertUser(suffix + "-waiting", "대기자");
		Room room = createFutureRoom(hostUserId, 1);
		roomParticipationService.participate(activeUserId, room.getId());
		createWaiting(room.getId(), waitingUserId, 10L);
		moveStartAtToBoundary(room.getId());
		return fixture(StartBoundaryScenario.PARTICIPATION_CANCELLATION, room, activeUserId, List.of(waitingUserId));
	}

	private StartBoundaryFixture createWaitlistCancellationFixture(String suffix) {
		long hostUserId = insertUser(suffix + "-host", "방장");
		long activeUserId = insertUser(suffix + "-active", "기존 참가자");
		long firstWaitingUserId = insertUser(suffix + "-first", "첫 대기자");
		long secondWaitingUserId = insertUser(suffix + "-second", "둘째 대기자");
		Room room = createFutureRoom(hostUserId, 1);
		roomParticipationService.participate(activeUserId, room.getId());
		createWaiting(room.getId(), firstWaitingUserId, 10L);
		createWaiting(room.getId(), secondWaitingUserId, 20L);
		moveStartAtToBoundary(room.getId());
		return fixture(StartBoundaryScenario.WAITLIST_CANCELLATION, room, firstWaitingUserId,
			List.of(firstWaitingUserId, secondWaitingUserId));
	}

	private StartBoundaryFixture fixture(
		StartBoundaryScenario scenario, Room room, long mutationUserId, List<Long> waitingUserIds) {
		return new StartBoundaryFixture(scenario, room, mutationUserId, waitingUserIds, roomVersion(room.getId()));
	}

	private Room createFutureRoom(long hostUserId, int capacity) {
		return roomRepository.saveAndFlush(Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"ROOM-10c 시작 경계 측정 방",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			NOW.plusSeconds(60),
			"홍대 테스트 장소",
			capacity));
	}

	private long insertUser(String suffix, String nickname) {
		String email = FIXTURE_SEED + "-" + suffix + "@example.com";
		jdbcTemplate.update("""
			insert into users (email, password_hash, nickname, created_at, updated_at)
			values (?, 'fixture-password-hash', ?, TIMESTAMP WITH TIME ZONE '2026-08-07T00:00:00Z',
				TIMESTAMP WITH TIME ZONE '2026-08-07T00:00:00Z')
			""", email, nickname);
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private void createWaiting(long roomId, long userId, long queueOrder) {
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(roomId, userId, queueOrder, NOW.minusSeconds(1)));
	}

	private void moveStartAtToBoundary(long roomId) {
		jdbcTemplate.update("update rooms set start_at = ? where id = ?", Timestamp.from(NOW), roomId);
	}

	private long roomVersion(long roomId) {
		return jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId);
	}

	private int activeWaitingCount(long roomId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from room_waitlists where room_id = ? and status = 'WAITING'", Integer.class, roomId);
	}

	private int activeParticipationCount(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ? and status = 'ACTIVE'",
			Integer.class, roomId, userId);
	}

	private RoomWaitlistStatus waitlistStatus(long roomId, long userId) {
		return RoomWaitlistStatus.valueOf(jdbcTemplate.queryForObject(
			"select status from room_waitlists where room_id = ? and user_id = ?", String.class, roomId, userId));
	}

	private ParticipationStatus participationStatus(long roomId, long userId) {
		return ParticipationStatus.valueOf(jdbcTemplate.queryForObject(
			"select status from participations where room_id = ? and user_id = ?", String.class, roomId, userId));
	}

	private record StartBoundaryFixture(
		StartBoundaryScenario scenario,
		Room room,
		long mutationUserId,
		List<Long> waitingUserIds,
		long versionBeforeMeasurement) {
	}

	private enum StartBoundaryScenario {
		DIRECT_JOIN("start-boundary-direct"),
		WAITLIST_REGISTRATION("start-boundary-waitlist"),
		PARTICIPATION_CANCELLATION("start-boundary-cancel-promote"),
		WAITLIST_CANCELLATION("start-boundary-waitlist-cancel");

		private final String fixturePrefix;

		StartBoundaryScenario(String fixturePrefix) {
			this.fixturePrefix = fixturePrefix;
		}

		String fixturePrefix() {
			return fixturePrefix;
		}

		String measurementName(CommitOrder order) {
			return fixturePrefix + "-" + order.fixtureSuffix();
		}
	}

	private enum CommitOrder {
		CORRECTION_FIRST("correction-first"),
		MUTATION_FIRST("mutation-first");

		private final String fixtureSuffix;

		CommitOrder(String fixtureSuffix) {
			this.fixtureSuffix = fixtureSuffix;
		}

		String fixtureSuffix() {
			return fixtureSuffix;
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class BaselineTestConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(NOW, ZoneOffset.UTC);
		}

		@Bean
		RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate() {
			return new RoomConcurrencyBaselineSupport.RoomReadGate();
		}

		@Bean
		StartBoundaryCommitOrderGate startBoundaryCommitOrderGate(
			RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate) {
			return new StartBoundaryCommitOrderGate(roomReadGate);
		}

		@Bean
		RoomConcurrencyBaselineSupport roomConcurrencyBaselineSupport(
			PlatformTransactionManager transactionManager,
			JdbcTemplate jdbcTemplate,
			@Qualifier("roomRepository") RoomRepository roomRepository) {
			return new RoomConcurrencyBaselineSupport(transactionManager, jdbcTemplate, roomRepository);
		}

		@Bean
		@Primary
		RoomOptimisticLockRetrier measuredRoomOptimisticLockRetrier(
			RoomConcurrencyBaselineSupport baselineSupport) {
			return baselineSupport.measuredRetrier();
		}

		@Bean(name = "gatedRoomRepository")
		@Primary
		RoomRepository gatedRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate,
			RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate,
			StartBoundaryCommitOrderGate commitOrderGate) {
			RoomRepository readGatedRepository = RoomConcurrencyBaselineSupport.gatedRoomRepository(delegate,
				roomReadGate);
			InvocationHandler handler = new StartBoundaryRoomRepositoryInvocationHandler(
				readGatedRepository, commitOrderGate);
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(), new Class<?>[] {RoomRepository.class}, handler);
		}
	}

	static final class StartBoundaryCommitOrderGate {

		enum Operation {
			CORRECTION,
			MUTATION
		}

		private final RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate;
		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();
		private final ThreadLocal<Operation> operation = new ThreadLocal<>();

		StartBoundaryCommitOrderGate(RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate) {
			this.roomReadGate = roomReadGate;
		}

		void activate(long roomId, CommitOrder order) {
			if (!activeScenario.compareAndSet(null, new Scenario(roomId, order))) {
				throw new IllegalStateException("시작 경계 확정 순서 gate가 이미 활성화되어 있습니다.");
			}
		}

		void mark(Operation operation) {
			this.operation.set(operation);
		}

		void clear() {
			operation.remove();
		}

		void complete(Operation operation) {
			Scenario scenario = activeScenario.get();
			if (scenario == null) {
				return;
			}
			if (operation == Operation.CORRECTION) {
				scenario.correctionCompleted.countDown();
				return;
			}
			scenario.mutationCompleted.countDown();
		}

		void afterRoomRead(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			Operation currentOperation = operation.get();
			if (scenario == null || currentOperation == null
				|| !isMatchingRoomRead(method, arguments, scenario.roomId)) {
				return;
			}
			if (currentOperation == Operation.CORRECTION
				&& scenario.order == CommitOrder.MUTATION_FIRST
				&& scenario.correctionReadBlocked.compareAndSet(false, true)) {
				awaitAndRecord(scenario.mutationCompleted, "명령 확정");
			}
			if (currentOperation == Operation.MUTATION
				&& scenario.order == CommitOrder.CORRECTION_FIRST
				&& scenario.mutationReadBlocked.compareAndSet(false, true)) {
				awaitAndRecord(scenario.correctionCompleted, "상태 보정 확정");
			}
		}

		void deactivate() {
			Scenario scenario = activeScenario.getAndSet(null);
			if (scenario != null) {
				scenario.correctionCompleted.countDown();
				scenario.mutationCompleted.countDown();
			}
		}

		private boolean isMatchingRoomRead(Method method, Object[] arguments, long roomId) {
			return method.getName().equals("findById")
				&& arguments != null
				&& arguments.length == 1
				&& arguments[0] instanceof Long candidateRoomId
				&& candidateRoomId == roomId;
		}

		private void awaitAndRecord(CountDownLatch latch, String phase) {
			long startedAt = System.nanoTime();
			try {
				if (!latch.await(10, TimeUnit.SECONDS)) {
					throw new AssertionError(phase + " 대기 시간이 초과되었습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(phase + " 대기 중 인터럽트되었습니다.", exception);
			} finally {
				roomReadGate.recordGateWaitNanos(System.nanoTime() - startedAt);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final CommitOrder order;
			private final CountDownLatch correctionCompleted = new CountDownLatch(1);
			private final CountDownLatch mutationCompleted = new CountDownLatch(1);
			private final AtomicBoolean correctionReadBlocked = new AtomicBoolean();
			private final AtomicBoolean mutationReadBlocked = new AtomicBoolean();

			private Scenario(long roomId, CommitOrder order) {
				this.roomId = roomId;
				this.order = order;
			}
		}
	}

	private static final class StartBoundaryRoomRepositoryInvocationHandler implements InvocationHandler {

		private final RoomRepository delegate;
		private final StartBoundaryCommitOrderGate commitOrderGate;

		private StartBoundaryRoomRepositoryInvocationHandler(
			RoomRepository delegate, StartBoundaryCommitOrderGate commitOrderGate) {
			this.delegate = delegate;
			this.commitOrderGate = commitOrderGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				Object result = method.invoke(delegate, arguments);
				commitOrderGate.afterRoomRead(method, arguments);
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}
}
