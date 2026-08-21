package cloud.bamsongi.albammate.room.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.repository.RoomWaitlistRepository;
import cloud.bamsongi.albammate.room.service.RoomOptimisticLockRetrier;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationCancelService;
import cloud.bamsongi.albammate.room.service.command.RoomParticipationService;
import cloud.bamsongi.albammate.room.service.command.RoomWaitlistCommandService;
import jakarta.persistence.OptimisticLockException;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
	"spring.datasource.hikari.maximum-pool-size=10",
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false"})
@Import(RoomWaitlistConcurrencyBaselinePostgresTest.BaselineTestConfiguration.class)
class RoomWaitlistConcurrencyBaselinePostgresTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
	private static final String FIXTURE_SEED = "ROOM-10A-20260806";
	private static final String FIXTURE_STATEMENT_MARKER = "room10b_fixture_marker";
	private static final String RETRY_EVENT = "room_10b_retry";
	private static final String PARTICIPATION_RETRY_EVENT = "room_participation_retry";
	private static final String PARTICIPATION_CANCEL_RETRY_EVENT = "room_participation_cancel_retry";
	private static final String WAITLIST_CANCEL_RETRY_EVENT = "room_waitlist_cancel_retry";
	private static final List<Integer> CONCURRENCY_LEVELS = List.of(2, 4, 8);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer(
		cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages.postgres18())
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
		.withDatabaseName("albam_mate_room_10b");

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
	private WaitlistFirstDecisionGate waitlistFirstDecisionGate;

	@Autowired
	private DirectParticipationFirstGate directParticipationFirstGate;

	@Autowired
	private LatestBusinessStateConflictGate latestBusinessStateConflictGate;

	@Autowired
	private WaitlistTransitionGate waitlistTransitionGate;

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
		waitlistFirstDecisionGate.deactivate();
		directParticipationFirstGate.deactivate();
		latestBusinessStateConflictGate.deactivate();
		waitlistTransitionGate.deactivate();
		jdbcTemplate.execute("truncate table participations, room_waitlists, rooms, users restart identity cascade");
	}

	@Test
	void 마지막_좌석_직전_직접_참가와_대기_신청은_최신_결과로_수렴한다() throws Exception {
		LastSeatWaitlistFixture directFirstFixture = createLastSeatWaitlistFixture("direct-first");
		RoomConcurrencyBaselineSupport.RoundMeasurement directFirst = measureDirectFirstLastSeatWaitlistRound(
			directFirstFixture);

		assertEquals(2, directFirst.successCount());
		assertEquals(0, directFirst.businessFailureCount());
		assertEquals(RoomStatus.CLOSED, roomStatus(directFirstFixture.room().getId()));
		assertEquals(2, activeParticipantCount(directFirstFixture.room().getId()));
		assertEquals(RoomWaitlistStatus.WAITING,
			waitlistStatus(directFirstFixture.room().getId(), directFirstFixture.waitingUserId()));
		assertWaitlistRoomInvariant(directFirstFixture.room().getId());

		LastSeatWaitlistFixture waitlistFirstFixture = createLastSeatWaitlistFixture("waitlist-first");
		RoomConcurrencyBaselineSupport.RoundMeasurement waitlistFirst = measureWaitlistFirstLastSeatWaitlistRound(
			waitlistFirstFixture);

		assertEquals(1, waitlistFirst.successCount());
		assertEquals(1, waitlistFirst.businessFailureCount());
		assertTrue(waitlistFirst.hasOnlyBusinessError(ErrorCode.WAITLIST_NOT_AVAILABLE));
		assertEquals(RoomStatus.CLOSED, roomStatus(waitlistFirstFixture.room().getId()));
		assertEquals(2, activeParticipantCount(waitlistFirstFixture.room().getId()));
		assertEquals(0, activeWaitingCount(waitlistFirstFixture.room().getId()));
		assertEquals(ParticipationStatus.ACTIVE,
			participationStatus(waitlistFirstFixture.room().getId(), waitlistFirstFixture.directJoinUserId()));
		assertEquals(0, participationCount(
			waitlistFirstFixture.room().getId(), waitlistFirstFixture.waitingUserId()));
		assertEquals(0, waitlistEntryCount(
			waitlistFirstFixture.room().getId(), waitlistFirstFixture.waitingUserId()));
		assertWaitlistRoomInvariant(waitlistFirstFixture.room().getId());
	}

	@Test
	void 복수_참가_취소는_고정_동시_수준마다_FIFO_자동_승격한다() throws Exception {
		for (int concurrencyLevel : CONCURRENCY_LEVELS) {
			CancellationPromotionFixture fixture = createCancellationPromotionFixture(
				"promotion-" + concurrencyLevel, concurrencyLevel);
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureCancellationPromotionRound(fixture);

			assertTrue(measurement.successCount() > 0);
			assertEquals(concurrencyLevel, measurement.successCount() + measurement.concurrencyFailureCount());
			assertEquals(0, measurement.businessFailureCount());
			assertEquals(0, measurement.technicalFailureCount());
			assertCancellationPromotionOutcome(fixture, measurement);
			assertWaitlistRoomInvariant(fixture.room().getId());
		}
	}

	@Test
	void 첫_WAITING_취소와_자동_승격은_확정_순서별_최신_상태를_보존한다() throws Exception {
		WaitlistCancellationFixture cancellationFirstFixture = createWaitlistCancellationFixture("cancel-first");
		RoomConcurrencyBaselineSupport.RoundMeasurement cancellationFirst = measureWaitlistCancellationRound(
			cancellationFirstFixture, TransitionOrder.CANCEL_FIRST);

		assertEquals(2, cancellationFirst.successCount());
		assertWaitlistCancellationOutcome(cancellationFirstFixture, TransitionOrder.CANCEL_FIRST);
		assertWaitlistRoomInvariant(cancellationFirstFixture.room().getId());

		WaitlistCancellationFixture promotionFirstFixture = createWaitlistCancellationFixture("promotion-first");
		RoomConcurrencyBaselineSupport.RoundMeasurement promotionFirst = measureWaitlistCancellationRound(
			promotionFirstFixture, TransitionOrder.PROMOTION_FIRST);

		assertEquals(1, promotionFirst.successCount());
		assertEquals(1, promotionFirst.businessFailureCount());
		assertTrue(promotionFirst.hasOnlyBusinessError(ErrorCode.WAITLIST_ENTRY_NOT_FOUND));
		assertWaitlistCancellationOutcome(promotionFirstFixture, TransitionOrder.PROMOTION_FIRST);
		assertWaitlistRoomInvariant(promotionFirstFixture.room().getId());
	}

	@Test
	@Tag("measurement")
	void 대기와_자동_승격_경합_측정은_공통_raw_형식으로_기록한다() throws Exception {
		baselineSupport.clearCollectedRounds();
		runDirectFirstLastSeatWaitlistPreparationRound(createLastSeatWaitlistFixture("prepare-direct-first"));
		for (int round = 1; round <= 3; round++) {
			LastSeatWaitlistFixture fixture = createLastSeatWaitlistFixture("raw-direct-first-" + round);
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureDirectFirstLastSeatWaitlistRound(
				fixture);
			assertWaitlistRoomInvariant(fixture.room().getId());
			assertRawMeasurement(measurement,
				"last-seat-direct-first", 2, fixture.room().getId(), PARTICIPATION_RETRY_EVENT);
		}

		runWaitlistFirstLastSeatWaitlistPreparationRound(createLastSeatWaitlistFixture("prepare-waitlist-first"));
		for (int round = 1; round <= 3; round++) {
			LastSeatWaitlistFixture fixture = createLastSeatWaitlistFixture("raw-waitlist-first-" + round);
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureWaitlistFirstLastSeatWaitlistRound(
				fixture);
			assertWaitlistRoomInvariant(fixture.room().getId());
			assertRawMeasurement(measurement,
				"last-seat-waitlist", 2, fixture.room().getId(), PARTICIPATION_RETRY_EVENT);
		}
		for (int concurrencyLevel : CONCURRENCY_LEVELS) {
			runCancellationPromotionPreparationRound(createCancellationPromotionFixture(
				"prepare-promotion-" + concurrencyLevel, concurrencyLevel));
			for (int round = 1; round <= 3; round++) {
				CancellationPromotionFixture fixture = createCancellationPromotionFixture(
					"raw-promotion-" + concurrencyLevel + "-" + round, concurrencyLevel);
				RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureCancellationPromotionRound(
					fixture);
				assertCancellationPromotionOutcome(fixture, measurement);
				assertWaitlistRoomInvariant(fixture.room().getId());
				assertRawMeasurement(measurement,
					"cancel-promote", concurrencyLevel, fixture.room().getId(), PARTICIPATION_CANCEL_RETRY_EVENT);
			}
		}
		for (TransitionOrder order : TransitionOrder.values()) {
			runWaitlistCancellationPreparationRound(createWaitlistCancellationFixture("prepare-" + order), order);
			for (int round = 1; round <= 3; round++) {
				WaitlistCancellationFixture fixture = createWaitlistCancellationFixture("raw-" + order + "-" + round);
				RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureWaitlistCancellationRound(fixture,
					order);
				assertWaitlistCancellationOutcome(fixture, order);
				assertWaitlistRoomInvariant(fixture.room().getId());
				assertRawMeasurement(measurement, order.scenarioName(), 2,
					fixture.room().getId(), WAITLIST_CANCEL_RETRY_EVENT, PARTICIPATION_CANCEL_RETRY_EVENT);
			}
		}

		assertMeasurementReportPersisted();
	}

	/**
	 * 로그 출력만으로는 후속 통합이 쓸 입력이 남지 않으므로 수집한 round를 JSON 원자료로 보존한다. 보존 형식은
	 * ROOM-09c 기준선과 같고, 버전 관리 사본은 이 파일을 `docs/measurements/results/room-10b/`로 복사해 남긴다.
	 */
	private void assertMeasurementReportPersisted() throws Exception {
		int expectedRoundCount = 3 + 3 + 3 * CONCURRENCY_LEVELS.size() + 3 * TransitionOrder.values().length;
		assertEquals(expectedRoundCount, baselineSupport.collectedRoundCount());

		Path reportPath = baselineSupport.writeMeasurementReport(objectMapper, "room-10b", Map.ofEntries(
			Map.entry("postgresImage", postgres.getDockerImageName()),
			Map.entry("sharedPreloadLibraries",
				jdbcTemplate.queryForObject("show shared_preload_libraries", String.class)),
			Map.entry("fixtureSeed", FIXTURE_SEED),
			Map.entry("fixedClock", NOW.toString()),
			Map.entry("concurrencyLevels", CONCURRENCY_LEVELS.toString()),
			Map.entry("schedulingEnabled", springEnvironment.getProperty("spring.task.scheduling.enabled", "true")),
			Map.entry("notificationRelayEnabled",
				springEnvironment.getProperty("app.notification.relay.enabled", "true")),
			Map.entry("chatRetentionEnabled",
				springEnvironment.getProperty("app.chat.retention.enabled", "true"))));

		assertTrue(Files.exists(reportPath));
		RoomConcurrencyBaselineSupport.MeasurementReport report = objectMapper.readValue(
			Files.readString(reportPath), RoomConcurrencyBaselineSupport.MeasurementReport.class);
		assertEquals("room-10b", report.reportName());
		assertEquals(expectedRoundCount, report.rounds().size());
		assertNotEquals("UNAVAILABLE", report.environment().gitSha());
		assertTrue(report.environment().cpuCount() > 0);
		assertTrue(report.rounds().stream().allMatch(round -> round.totalRequestCount() > 0));
		assertTrue(report.rounds().stream().allMatch(round -> round.rawRecord().startsWith("ROOM10A_RAW")));
		assertTrue(report.rounds().stream().allMatch(round -> round.postgresCost().statementCalls() > 0));
		assertEquals(
			CONCURRENCY_LEVELS,
			report.rounds().stream()
				.filter(round -> "cancel-promote".equals(round.scenario()))
				.map(RoomConcurrencyBaselineSupport.RoundReport::concurrencyLevel)
				.distinct()
				.sorted()
				.toList());
	}

	@Test
	void 각_측정_round_뒤_PostgreSQL_대기_저장_불변식이_유지된다() throws Exception {
		LastSeatWaitlistFixture directFirstFixture = createLastSeatWaitlistFixture("invariant-direct-first");
		measureDirectFirstLastSeatWaitlistRound(directFirstFixture);
		assertWaitlistRoomInvariant(directFirstFixture.room().getId());

		LastSeatWaitlistFixture waitlistFirstFixture = createLastSeatWaitlistFixture("invariant-waitlist-first");
		measureWaitlistFirstLastSeatWaitlistRound(waitlistFirstFixture);
		assertWaitlistRoomInvariant(waitlistFirstFixture.room().getId());

		for (int concurrencyLevel : CONCURRENCY_LEVELS) {
			CancellationPromotionFixture fixture = createCancellationPromotionFixture(
				"invariant-promotion-" + concurrencyLevel, concurrencyLevel);
			measureCancellationPromotionRound(fixture);
			assertWaitlistRoomInvariant(fixture.room().getId());
		}

		for (TransitionOrder order : TransitionOrder.values()) {
			WaitlistCancellationFixture fixture = createWaitlistCancellationFixture("invariant-" + order);
			measureWaitlistCancellationRound(fixture, order);
			assertWaitlistRoomInvariant(fixture.room().getId());
		}
	}

	/**
	 * FIFO 불변식이 실제로 역순 승격을 걸러내는지 확인한다. 승격한 마지막 항목과 남은 첫 대기자의 `queue_order`만 맞바꾸면 인원·상태·중복
	 * 조건은 모두 그대로이고 잔여 WAITING 순서도 증가하므로, 이 상태를 통과시키는 불변식은 FIFO를 보장하지 않는다.
	 */
	@Test
	void 대기_저장_불변식은_선두를_건너뛴_승격을_통과시키지_않는다() throws Exception {
		CancellationPromotionFixture fixture = createCancellationPromotionFixture(
			"fifo-guard", CONCURRENCY_LEVELS.get(0));
		RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureCancellationPromotionRound(fixture);
		long roomId = fixture.room().getId();
		assertTrue(measurement.successCount() > 0);
		assertCancellationPromotionOutcome(fixture, measurement);
		assertWaitlistRoomInvariant(roomId);

		int promotedCount = (int)measurement.successCount();
		swapQueueOrder(roomId,
			fixture.waitingUserIds().get(promotedCount - 1),
			fixture.waitingUserIds().get(promotedCount));

		assertThrows(AssertionError.class, () -> assertWaitlistRoomInvariant(roomId));
	}

	private void swapQueueOrder(long roomId, long promotedUserId, long waitingUserId) {
		Long promotedOrder = queueOrder(roomId, promotedUserId);
		Long waitingOrder = queueOrder(roomId, waitingUserId);
		jdbcTemplate.update("update room_waitlists set queue_order = ? where room_id = ? and user_id = ?",
			waitingOrder, roomId, promotedUserId);
		jdbcTemplate.update("update room_waitlists set queue_order = ? where room_id = ? and user_id = ?",
			promotedOrder, roomId, waitingUserId);
	}

	private Long queueOrder(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select queue_order from room_waitlists where room_id = ? and user_id = ?", Long.class, roomId, userId);
	}

	@Test
	void 재시도_소진_업무_실패와_기술_실패는_서로_분류된다() throws Exception {
		RoomConcurrencyBaselineSupport.RetryMeasurement exhausted = baselineSupport.newRetryMeasurement();
		BusinessException exhaustedException = assertThrows(BusinessException.class,
			() -> exhausted.execute(roomOptimisticLockRetrier, RETRY_EVENT, () -> {
				throw new OptimisticLockException("deterministic conflict");
			}));
		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exhaustedException.getErrorCode());
		assertEquals(3, exhausted.attemptCount());
		assertEquals(3, exhausted.conflictCount());
		assertEquals(2, exhausted.retryCount());
		assertTrue(exhausted.exhausted());

		RoomConcurrencyBaselineSupport.RetryMeasurement businessFailure = baselineSupport.newRetryMeasurement();
		BusinessException businessException = assertThrows(BusinessException.class,
			() -> businessFailure.execute(roomOptimisticLockRetrier, RETRY_EVENT,
				new CallableAfterConflict(ErrorCode.CAPACITY_EXCEEDED)::execute));
		assertEquals(ErrorCode.CAPACITY_EXCEEDED, businessException.getErrorCode());
		assertEquals(2, businessFailure.attemptCount());
		assertEquals(1, businessFailure.conflictCount());
		assertEquals(1, businessFailure.businessFailureCount());
		assertFalse(businessFailure.exhausted());

		long hostUserId = baselineSupport.insertUser(FIXTURE_SEED + "-latest-state-host", "방장");
		long existingUserId = baselineSupport.insertUser(FIXTURE_SEED + "-latest-state-existing", "기존 참가자");
		long waitingUserId = baselineSupport.insertUser(FIXTURE_SEED + "-latest-state-waiting", "대기 신청자");
		Room room = baselineSupport.createRoom(hostUserId, 1, NOW);
		roomParticipationService.participate(existingUserId, room.getId());
		latestBusinessStateConflictGate.activate(room.getId());
		try {
			BusinessException latestStateException = assertThrows(
				BusinessException.class,
				() -> roomWaitlistCommandService.register(waitingUserId, room.getId()));

			assertEquals(ErrorCode.WAITLIST_NOT_AVAILABLE, latestStateException.getErrorCode());
			assertEquals(1, latestBusinessStateConflictGate.forcedConflictCount());
			assertEquals(2, latestBusinessStateConflictGate.roomReadCount());
			assertEquals(1, latestBusinessStateConflictGate.canceledRoomReadCount());
			assertEquals(1, latestBusinessStateConflictGate.claimVersionAttemptCount());
			assertEquals(RoomStatus.CANCELED, roomStatus(room.getId()));
			assertEquals(0, activeWaitingCount(room.getId()));
		} finally {
			latestBusinessStateConflictGate.deactivate();
		}

		RoomConcurrencyBaselineSupport.RoundMeasurement technicalFailure = baselineSupport.measureRound(
			"technical-failure", 1, roomReadGate, List.of(() -> {
				throw new IllegalStateException("deterministic technical failure");
			}));
		assertEquals(0, technicalFailure.successCount());
		assertEquals(0, technicalFailure.businessFailureCount());
		assertEquals(0, technicalFailure.concurrencyFailureCount());
		assertEquals(1, technicalFailure.technicalFailureCount());
	}

	private RoomConcurrencyBaselineSupport.RoundMeasurement measureDirectFirstLastSeatWaitlistRound(
		LastSeatWaitlistFixture fixture) throws Exception {
		directParticipationFirstGate.activate(fixture.room().getId());
		try {
			return measureRound("last-seat-direct-first", 2, fixture.room().getId(), 1,
				directFirstLastSeatWaitlistCommands(fixture));
		} finally {
			directParticipationFirstGate.deactivate();
		}
	}

	private List<Callable<?>> directFirstLastSeatWaitlistCommands(LastSeatWaitlistFixture fixture) {
		return List.of(
			() -> {
				roomParticipationService.participate(fixture.directJoinUserId(), fixture.room().getId());
				directParticipationFirstGate.markDirectParticipationCommitted(fixture.room().getId());
				return null;
			},
			() -> {
				directParticipationFirstGate.markWaitlistRequest();
				try {
					return roomWaitlistCommandService.register(fixture.waitingUserId(), fixture.room().getId());
				} finally {
					directParticipationFirstGate.clearWaitlistRequest();
				}
			});
	}

	private RoomConcurrencyBaselineSupport.RoundMeasurement measureWaitlistFirstLastSeatWaitlistRound(
		LastSeatWaitlistFixture fixture) throws Exception {
		waitlistFirstDecisionGate.activate(fixture.room().getId());
		try {
			return measureRound("last-seat-waitlist", 2, fixture.room().getId(), 1,
				waitlistFirstLastSeatWaitlistCommands(fixture));
		} finally {
			waitlistFirstDecisionGate.deactivate();
		}
	}

	private List<Callable<?>> waitlistFirstLastSeatWaitlistCommands(LastSeatWaitlistFixture fixture) {
		return List.of(
			() -> {
				waitlistFirstDecisionGate.markDirectRequest();
				try {
					return roomParticipationService.participate(fixture.directJoinUserId(), fixture.room().getId());
				} finally {
					waitlistFirstDecisionGate.clearDirectRequest();
				}
			},
			() -> {
				try {
					return roomWaitlistCommandService.register(fixture.waitingUserId(), fixture.room().getId());
				} finally {
					waitlistFirstDecisionGate.markWaitlistDecisionCompleted(fixture.room().getId());
				}
			});
	}

	private RoomConcurrencyBaselineSupport.RoundMeasurement measureCancellationPromotionRound(
		CancellationPromotionFixture fixture) throws Exception {
		return measureRound("cancel-promote", fixture.leavingUserIds().size(), fixture.room().getId(),
			fixture.leavingUserIds().size(),
			fixture.leavingUserIds().stream().<Callable<?>>map(
				userId -> () -> roomParticipationCancelService.cancelParticipation(userId, fixture.room().getId()))
				.toList());
	}

	private RoomConcurrencyBaselineSupport.RoundMeasurement measureWaitlistCancellationRound(
		WaitlistCancellationFixture fixture, TransitionOrder order) throws Exception {
		waitlistTransitionGate.activate(fixture.room().getId(), fixture.firstWaitingUserId(), order);
		try {
			return measureRound(order.scenarioName(), 2, fixture.room().getId(), 2, List.<Callable<?>>of(
				() -> {
					roomWaitlistCommandService.cancel(fixture.firstWaitingUserId(), fixture.room().getId());
					return null;
				},
				() -> roomParticipationCancelService.cancelParticipation(fixture.leavingUserId(),
					fixture.room().getId())));
		} finally {
			waitlistTransitionGate.deactivate();
		}
	}

	private RoomConcurrencyBaselineSupport.RoundMeasurement measureRound(
		String scenario, int concurrencyLevel, long roomId, int expectedInitialRoomReaders, List<Callable<?>> commands)
		throws Exception {
		roomReadGate.activate(roomId, expectedInitialRoomReaders);
		Logger rawLogger = (Logger)LoggerFactory.getLogger(RoomConcurrencyBaselineSupport.class);
		ListAppender<ILoggingEvent> rawLogAppender = new ListAppender<>();
		rawLogAppender.start();
		rawLogger.addAppender(rawLogAppender);
		try {
			jdbcTemplate.execute("select id as room10b_fixture_marker from users limit 0");
			assertTrue(baselineSupport.statementCallsContaining(FIXTURE_STATEMENT_MARKER) > 0L);
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = baselineSupport.measureRound(
				scenario, concurrencyLevel, roomReadGate, commands);
			List<String> rawRecords = rawLogAppender.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.filter(message -> message.startsWith("ROOM10A_RAW "))
				.toList();
			assertEquals(List.of(measurement.rawRecord()), rawRecords);
			assertEquals(0L, baselineSupport.statementCallsContaining(FIXTURE_STATEMENT_MARKER));
			roomReadGate.assertInitialReadsShareOneVersion();
			return measurement;
		} finally {
			rawLogger.detachAppender(rawLogAppender);
			rawLogAppender.stop();
			roomReadGate.deactivate();
		}
	}

	private void runCancellationPromotionPreparationRound(CancellationPromotionFixture fixture) throws Exception {
		roomReadGate.activate(fixture.room().getId(), fixture.leavingUserIds().size());
		try {
			baselineSupport.runPreparationRound(fixture.leavingUserIds().stream().<Callable<?>>map(
				userId -> () -> runCancellationCommandForPreparation(userId, fixture.room().getId())).toList());
			roomReadGate.assertInitialReadsShareOneVersion();
		} finally {
			roomReadGate.deactivate();
		}
	}

	private void runDirectFirstLastSeatWaitlistPreparationRound(LastSeatWaitlistFixture fixture) throws Exception {
		directParticipationFirstGate.activate(fixture.room().getId());
		roomReadGate.activate(fixture.room().getId(), 1);
		try {
			baselineSupport.runPreparationRound(directFirstLastSeatWaitlistCommands(fixture));
			roomReadGate.assertInitialReadsShareOneVersion();
		} finally {
			roomReadGate.deactivate();
			directParticipationFirstGate.deactivate();
		}
	}

	private void runWaitlistFirstLastSeatWaitlistPreparationRound(LastSeatWaitlistFixture fixture) throws Exception {
		waitlistFirstDecisionGate.activate(fixture.room().getId());
		roomReadGate.activate(fixture.room().getId(), 1);
		try {
			baselineSupport.runPreparationRound(waitlistFirstLastSeatWaitlistPreparationCommands(fixture));
			roomReadGate.assertInitialReadsShareOneVersion();
		} finally {
			roomReadGate.deactivate();
			waitlistFirstDecisionGate.deactivate();
		}
	}

	private List<Callable<?>> waitlistFirstLastSeatWaitlistPreparationCommands(LastSeatWaitlistFixture fixture) {
		return List.of(
			() -> {
				waitlistFirstDecisionGate.markDirectRequest();
				try {
					return roomParticipationService.participate(fixture.directJoinUserId(), fixture.room().getId());
				} finally {
					waitlistFirstDecisionGate.clearDirectRequest();
				}
			},
			() -> {
				try {
					return runWaitlistCommandForPreparation(fixture.waitingUserId(), fixture.room().getId());
				} finally {
					waitlistFirstDecisionGate.markWaitlistDecisionCompleted(fixture.room().getId());
				}
			});
	}

	private void runWaitlistCancellationPreparationRound(
		WaitlistCancellationFixture fixture, TransitionOrder order) throws Exception {
		waitlistTransitionGate.activate(fixture.room().getId(), fixture.firstWaitingUserId(), order);
		roomReadGate.activate(fixture.room().getId(), 2);
		try {
			baselineSupport.runPreparationRound(List.<Callable<?>>of(
				() -> runWaitlistCancelForPreparation(fixture.firstWaitingUserId(), fixture.room().getId()),
				() -> roomParticipationCancelService.cancelParticipation(fixture.leavingUserId(),
					fixture.room().getId())));
			roomReadGate.assertInitialReadsShareOneVersion();
		} finally {
			roomReadGate.deactivate();
			waitlistTransitionGate.deactivate();
		}
	}

	private Object runCancellationCommandForPreparation(long userId, long roomId) {
		try {
			return roomParticipationCancelService.cancelParticipation(userId, roomId);
		} catch (BusinessException exception) {
			if (exception.getErrorCode() == ErrorCode.ROOM_CONCURRENT_MODIFICATION) {
				return null;
			}
			throw exception;
		}
	}

	private Object runWaitlistCommandForPreparation(long userId, long roomId) {
		try {
			return roomWaitlistCommandService.register(userId, roomId);
		} catch (BusinessException exception) {
			if (exception.getErrorCode() == ErrorCode.WAITLIST_NOT_AVAILABLE) {
				return null;
			}
			throw exception;
		}
	}

	private Object runWaitlistCancelForPreparation(long userId, long roomId) {
		try {
			roomWaitlistCommandService.cancel(userId, roomId);
			return null;
		} catch (BusinessException exception) {
			if (exception.getErrorCode() == ErrorCode.WAITLIST_ENTRY_NOT_FOUND) {
				return null;
			}
			throw exception;
		}
	}

	private void assertRawMeasurement(RoomConcurrencyBaselineSupport.RoundMeasurement measurement,
		String scenario, int concurrencyLevel, long roomId, String... expectedEvents) {
		assertEquals(measurement.totalRequestCount(), measurement.successCount() + measurement.businessFailureCount()
			+ measurement.concurrencyFailureCount() + measurement.technicalFailureCount());
		assertEquals(measurement.totalRequestCount(), measurement.retryCount(0) + measurement.retryCount(1)
			+ measurement.retryCount(2));
		assertEquals(measurement.totalRetryCount(), measurement.retryAttemptLogCount());
		assertEquals(measurement.concurrencyFailureCount(), measurement.exhaustedCount());
		assertTrue(measurement.requestDurationsNanos().stream().allMatch(duration -> duration > 0));
		assertTrue(measurement.postgresCost().statementCalls() > 0);
		assertRetryLogFormat(measurement, roomId, expectedEvents);

		long conflictCount = measurement.requests().stream()
			.mapToLong(RoomConcurrencyBaselineSupport.RequestMeasurement::conflictCount)
			.sum();
		double conflictRate = (double)conflictCount / measurement.totalRequestCount();
		String expectedRawRecord = "ROOM10A_RAW scenario=" + scenario
			+ " concurrencyLevel=" + concurrencyLevel
			+ " requestCount=" + measurement.totalRequestCount()
			+ " success=" + measurement.successCount()
			+ " businessFailure=" + measurement.businessFailureCount()
			+ " concurrencyFailure=" + measurement.concurrencyFailureCount()
			+ " technicalFailure=" + measurement.technicalFailureCount()
			+ " conflictCount=" + conflictCount
			+ " conflictRate=" + conflictRate
			+ " retry0=" + measurement.retryCount(0)
			+ " retry1=" + measurement.retryCount(1)
			+ " retry2=" + measurement.retryCount(2)
			+ " exhausted=" + measurement.exhaustedCount()
			+ " responseNanos=" + measurement.requestDurationsNanos()
			+ " calls=" + measurement.postgresCost().statementCalls()
			+ " totalExecMs=" + measurement.postgresCost().totalExecutionMillis()
			+ " rows=" + measurement.postgresCost().rows()
			+ " sharedBlksHit=" + measurement.postgresCost().sharedBlockHits()
			+ " sharedBlksRead=" + measurement.postgresCost().sharedBlockReads();
		assertEquals(expectedRawRecord, measurement.rawRecord());
	}

	private void assertRetryLogFormat(
		RoomConcurrencyBaselineSupport.RoundMeasurement measurement, long roomId, String... expectedEvents) {
		List<RoomConcurrencyBaselineSupport.RetryLogRecord> retryLogs = measurement.retryLogRecords();
		assertEquals(measurement.totalRetryCount() + measurement.concurrencyFailureCount(), retryLogs.size());
		assertTrue(retryLogs.stream().allMatch(log -> List.of(expectedEvents).contains(log.event())));
		assertTrue(retryLogs.stream().allMatch(log -> Long.valueOf(roomId).equals(log.roomId())));
		assertTrue(retryLogs.stream().allMatch(log -> log.attempt() >= 2 && log.attempt() <= 3));
		assertTrue(retryLogs.stream().allMatch(log -> expectedUseCase(log.event()).equals(log.useCase())));
		assertTrue(retryLogs.stream().allMatch(log -> expectedReasonCode(log).equals(log.reasonCode())));
		assertTrue(retryLogs.stream().allMatch(log -> log.retryAttempt() || log.exhaustedAttempt()));
		assertTrue(retryLogs.stream()
			.filter(RoomConcurrencyBaselineSupport.RetryLogRecord::exhaustedAttempt)
			.allMatch(log -> log.attempt() == 3));
	}

	private String expectedUseCase(String event) {
		return switch (event) {
			case PARTICIPATION_RETRY_EVENT -> "ROOM_PARTICIPATION";
			case PARTICIPATION_CANCEL_RETRY_EVENT -> "ROOM_PARTICIPATION_CANCEL";
			case WAITLIST_CANCEL_RETRY_EVENT -> "ROOM_WAITLIST_CANCEL";
			default -> throw new AssertionError("예상하지 않은 재시도 event: " + event);
		};
	}

	private String expectedReasonCode(RoomConcurrencyBaselineSupport.RetryLogRecord retryLog) {
		if (retryLog.retryAttempt()) {
			return "OPTIMISTIC_LOCK_CONFLICT";
		}
		return "OPTIMISTIC_LOCK_EXHAUSTED";
	}

	/**
	 * 취소 성공 수만큼 FIFO 선두 대기자만 승격했는지 확인한다. 원자료를 남기는 round와 시나리오 전용 round가 같은 기준으로 저장 결과를
	 * 확인해야 특정 round의 FIFO 위반이 원자료에 섞이지 않는다.
	 */
	private void assertCancellationPromotionOutcome(
		CancellationPromotionFixture fixture, RoomConcurrencyBaselineSupport.RoundMeasurement measurement) {
		long roomId = fixture.room().getId();
		assertEquals(fixture.room().getCapacity(), activeParticipantCount(roomId));
		assertEquals(RoomStatus.CLOSED, roomStatus(roomId));
		for (int index = 0; index < fixture.leavingUserIds().size(); index++) {
			ParticipationStatus expectedStatus = measurement.requests().get(index).successful()
				? ParticipationStatus.CANCELED
				: ParticipationStatus.ACTIVE;
			assertEquals(expectedStatus, participationStatus(roomId, fixture.leavingUserIds().get(index)));
		}
		for (int index = 0; index < fixture.waitingUserIds().size(); index++) {
			if (index < measurement.successCount()) {
				assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(roomId, fixture.waitingUserIds().get(index)));
				assertEquals(ParticipationStatus.ACTIVE,
					participationStatus(roomId, fixture.waitingUserIds().get(index)));
			} else {
				assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(roomId, fixture.waitingUserIds().get(index)));
				assertEquals(0, participationCount(roomId, fixture.waitingUserIds().get(index)));
			}
		}
		assertEquals(fixture.waitingUserIds().size() - measurement.successCount(), activeWaitingCount(roomId));
	}

	/**
	 * 확정 순서별로 승격 대상과 남는 대기자가 달라지므로 순서를 입력으로 받아 저장 결과를 확인한다.
	 */
	private void assertWaitlistCancellationOutcome(WaitlistCancellationFixture fixture, TransitionOrder order) {
		long roomId = fixture.room().getId();
		if (order == TransitionOrder.CANCEL_FIRST) {
			assertEquals(RoomWaitlistStatus.CANCELED, waitlistStatus(roomId, fixture.firstWaitingUserId()));
			assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(roomId, fixture.secondWaitingUserId()));
			assertEquals(ParticipationStatus.ACTIVE, participationStatus(roomId, fixture.secondWaitingUserId()));
			assertEquals(0, activeWaitingCount(roomId));
			return;
		}
		assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(roomId, fixture.firstWaitingUserId()));
		assertEquals(ParticipationStatus.ACTIVE, participationStatus(roomId, fixture.firstWaitingUserId()));
		assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(roomId, fixture.secondWaitingUserId()));
		assertEquals(0, participationCount(roomId, fixture.secondWaitingUserId()));
		assertEquals(1, activeWaitingCount(roomId));
	}

	/**
	 * 잔여 WAITING의 `queue_order` 증가만으로는 FIFO 승격이 보장되지 않는다. 선두 대기자를 남기고 뒤 대기자를 승격한 결과도 잔여 순서는
	 * 증가하므로, 승격한 항목의 `queue_order`가 남은 WAITING보다 앞서는지와 승격이 실제 ACTIVE 참가로 확정됐는지를 함께 확인한다.
	 */
	private void assertWaitlistRoomInvariant(long roomId) {
		RoomConcurrencyBaselineSupport.RoomInvariant invariant = baselineSupport.readRoomInvariant(roomId);
		assertEquals(invariant.activeParticipantCount(), invariant.activeParticipationCount());
		assertTrue(invariant.activeParticipantCount() >= 0);
		assertTrue(invariant.activeParticipantCount() <= invariant.capacity());
		assertEquals(
			invariant.activeParticipantCount() == invariant.capacity() ? RoomStatus.CLOSED : RoomStatus.RECRUITING,
			invariant.status());
		assertFalse(invariant.hasDuplicatedActiveParticipation());
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*)
			from participations participation
			join room_waitlists waitlist on waitlist.room_id = participation.room_id
				and waitlist.user_id = participation.user_id
			where participation.room_id = ? and participation.status = 'ACTIVE' and waitlist.status = 'WAITING'
			""", Integer.class, roomId));
		List<Long> queueOrders = jdbcTemplate.queryForList(
			"select queue_order from room_waitlists where room_id = ? and status = 'WAITING' order by queue_order",
			Long.class,
			roomId);
		for (int index = 1; index < queueOrders.size(); index++) {
			assertTrue(queueOrders.get(index - 1) < queueOrders.get(index));
		}
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*)
			from room_waitlists waiting
			where waiting.room_id = ? and waiting.status = 'WAITING'
				and exists (
					select 1
					from room_waitlists promoted
					where promoted.room_id = waiting.room_id and promoted.status = 'PROMOTED'
						and promoted.queue_order > waiting.queue_order)
			""", Integer.class, roomId));
		assertEquals(0, jdbcTemplate.queryForObject("""
			select count(*)
			from room_waitlists promoted
			where promoted.room_id = ? and promoted.status = 'PROMOTED'
				and not exists (
					select 1
					from participations participation
					where participation.room_id = promoted.room_id and participation.user_id = promoted.user_id
						and participation.status = 'ACTIVE')
			""", Integer.class, roomId));
	}

	private LastSeatWaitlistFixture createLastSeatWaitlistFixture(String suffix) {
		long hostUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-host", "방장");
		long existingUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-existing", "기존 참가자");
		long directJoinUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-direct", "직접 참가자");
		long waitingUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-waiting", "대기 신청자");
		Room room = baselineSupport.createRoom(hostUserId, 2, NOW);
		roomParticipationService.participate(existingUserId, room.getId());
		return new LastSeatWaitlistFixture(room, directJoinUserId, waitingUserId);
	}

	private CancellationPromotionFixture createCancellationPromotionFixture(String suffix, int concurrencyLevel) {
		long hostUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-host", "방장");
		Room room = baselineSupport.createRoom(hostUserId, concurrencyLevel, NOW);
		List<Long> leavingUserIds = new ArrayList<>();
		List<Long> waitingUserIds = new ArrayList<>();
		for (int index = 0; index < concurrencyLevel; index++) {
			long leavingUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-leaving-" + index,
				"취소자" + index);
			leavingUserIds.add(leavingUserId);
			roomParticipationService.participate(leavingUserId, room.getId());
		}
		for (int index = 0; index <= concurrencyLevel; index++) {
			long waitingUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-waiting-" + index,
				"대기자" + index);
			waitingUserIds.add(waitingUserId);
			roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), waitingUserId, 10L + index, NOW));
		}
		return new CancellationPromotionFixture(room, leavingUserIds, waitingUserIds);
	}

	private WaitlistCancellationFixture createWaitlistCancellationFixture(String suffix) {
		long hostUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-host", "방장");
		long leavingUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-leaving", "취소자");
		long firstWaitingUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-first", "첫 대기자");
		long secondWaitingUserId = baselineSupport.insertUser(FIXTURE_SEED + "-" + suffix + "-second", "둘째 대기자");
		Room room = baselineSupport.createRoom(hostUserId, 1, NOW);
		roomParticipationService.participate(leavingUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), firstWaitingUserId, 10L, NOW));
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(room.getId(), secondWaitingUserId, 20L, NOW));
		return new WaitlistCancellationFixture(room, leavingUserId, firstWaitingUserId, secondWaitingUserId);
	}

	private RoomStatus roomStatus(long roomId) {
		return RoomStatus
			.valueOf(jdbcTemplate.queryForObject("select status from rooms where id = ?", String.class, roomId));
	}

	private int activeParticipantCount(long roomId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and status = 'ACTIVE'",
			Integer.class, roomId);
	}

	private int activeWaitingCount(long roomId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from room_waitlists where room_id = ? and status = 'WAITING'",
			Integer.class, roomId);
	}

	private RoomWaitlistStatus waitlistStatus(long roomId, long userId) {
		return RoomWaitlistStatus.valueOf(jdbcTemplate.queryForObject(
			"select status from room_waitlists where room_id = ? and user_id = ?", String.class, roomId, userId));
	}

	private ParticipationStatus participationStatus(long roomId, long userId) {
		return ParticipationStatus.valueOf(jdbcTemplate.queryForObject(
			"select status from participations where room_id = ? and user_id = ?", String.class, roomId, userId));
	}

	private int participationCount(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ?",
			Integer.class,
			roomId,
			userId);
	}

	private int waitlistEntryCount(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from room_waitlists where room_id = ? and user_id = ?",
			Integer.class,
			roomId,
			userId);
	}

	private record LastSeatWaitlistFixture(Room room, long directJoinUserId, long waitingUserId) {
	}

	private record CancellationPromotionFixture(Room room, List<Long> leavingUserIds, List<Long> waitingUserIds) {
	}

	private record WaitlistCancellationFixture(
		Room room, long leavingUserId, long firstWaitingUserId, long secondWaitingUserId) {
	}

	private static final class CallableAfterConflict {

		private final ErrorCode errorCode;
		private boolean firstInvocation = true;

		private CallableAfterConflict(ErrorCode errorCode) {
			this.errorCode = errorCode;
		}

		private Object execute() {
			if (firstInvocation) {
				firstInvocation = false;
				throw new OptimisticLockException("deterministic conflict");
			}
			throw new BusinessException(errorCode);
		}
	}

	enum TransitionOrder {
		CANCEL_FIRST("waitlist-cancel-first"),
		PROMOTION_FIRST("promotion-first");

		private final String scenarioName;

		TransitionOrder(String scenarioName) {
			this.scenarioName = scenarioName;
		}

		String scenarioName() {
			return scenarioName;
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
		WaitlistTransitionGate waitlistTransitionGate() {
			return new WaitlistTransitionGate();
		}

		@Bean
		WaitlistFirstDecisionGate waitlistFirstDecisionGate(
			RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate) {
			return new WaitlistFirstDecisionGate(roomReadGate);
		}

		@Bean
		DirectParticipationFirstGate directParticipationFirstGate(
			RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate) {
			return new DirectParticipationFirstGate(roomReadGate);
		}

		@Bean
		LatestBusinessStateConflictGate latestBusinessStateConflictGate(
			PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate) {
			return new LatestBusinessStateConflictGate(transactionManager, jdbcTemplate);
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
		RoomOptimisticLockRetrier measuredRoomOptimisticLockRetrier(RoomConcurrencyBaselineSupport baselineSupport) {
			return baselineSupport.measuredRetrier();
		}

		@Bean(name = "gatedRoomRepository")
		@Primary
		RoomRepository gatedRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate,
			RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate,
			WaitlistFirstDecisionGate waitlistFirstDecisionGate,
			DirectParticipationFirstGate directParticipationFirstGate,
			LatestBusinessStateConflictGate latestBusinessStateConflictGate) {
			RoomRepository readGatedRepository = RoomConcurrencyBaselineSupport.gatedRoomRepository(delegate,
				roomReadGate);
			InvocationHandler handler = new GateAwareRoomRepositoryInvocationHandler(
				readGatedRepository, waitlistFirstDecisionGate, directParticipationFirstGate,
				latestBusinessStateConflictGate);
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(), new Class<?>[] {RoomRepository.class}, handler);
		}

		@Bean(name = "gatedRoomWaitlistRepository")
		@Primary
		RoomWaitlistRepository gatedRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			WaitlistTransitionGate waitlistTransitionGate) {
			InvocationHandler handler = new GateAwareRoomWaitlistRepositoryInvocationHandler(
				delegate, waitlistTransitionGate);
			return (RoomWaitlistRepository)Proxy.newProxyInstance(
				RoomWaitlistRepository.class.getClassLoader(), new Class<?>[] {RoomWaitlistRepository.class}, handler);
		}
	}

	static final class WaitlistFirstDecisionGate {

		private final RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate;
		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();
		private final ThreadLocal<Boolean> directRequest = new ThreadLocal<>();

		WaitlistFirstDecisionGate(RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate) {
			this.roomReadGate = roomReadGate;
		}

		void activate(long roomId) {
			if (!activeScenario.compareAndSet(null, new Scenario(roomId))) {
				throw new IllegalStateException("대기 우선 결정 gate가 이미 활성화되어 있습니다.");
			}
		}

		void markDirectRequest() {
			directRequest.set(true);
		}

		void clearDirectRequest() {
			directRequest.remove();
		}

		void markWaitlistDecisionCompleted(long roomId) {
			Scenario scenario = activeScenario.get();
			if (scenario != null && scenario.roomId == roomId) {
				scenario.waitlistDecisionCompleted.countDown();
			}
		}

		void beforeRoomRead(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || !Boolean.TRUE.equals(directRequest.get())
				|| !isRoomRead(method, arguments)
				|| !scenario.directReadBlocked.compareAndSet(false, true)) {
				return;
			}
			long gateWaitStartedAt = System.nanoTime();
			await(scenario.waitlistDecisionCompleted, "대기 신청 업무 판단");
			roomReadGate.recordGateWaitNanos(System.nanoTime() - gateWaitStartedAt);
		}

		void deactivate() {
			Scenario scenario = activeScenario.getAndSet(null);
			if (scenario != null) {
				scenario.waitlistDecisionCompleted.countDown();
			}
		}

		private boolean isRoomRead(Method method, Object[] arguments) {
			return method.getName().equals("findById")
				&& arguments != null
				&& arguments.length == 1
				&& arguments[0] instanceof Long roomId
				&& activeScenario.get().roomId == roomId;
		}

		private void await(CountDownLatch latch, String phase) {
			try {
				if (!latch.await(10, TimeUnit.SECONDS)) {
					throw new AssertionError(phase + " 대기 시간이 초과되었습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(phase + " 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final CountDownLatch waitlistDecisionCompleted = new CountDownLatch(1);
			private final AtomicBoolean directReadBlocked = new AtomicBoolean();

			private Scenario(long roomId) {
				this.roomId = roomId;
			}
		}
	}

	static final class DirectParticipationFirstGate {

		private final RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate;
		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();
		private final ThreadLocal<Boolean> waitlistRequest = new ThreadLocal<>();

		DirectParticipationFirstGate(RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate) {
			this.roomReadGate = roomReadGate;
		}

		void activate(long roomId) {
			if (!activeScenario.compareAndSet(null, new Scenario(roomId))) {
				throw new IllegalStateException("직접 참가 우선 gate가 이미 활성화되어 있습니다.");
			}
		}

		void markWaitlistRequest() {
			waitlistRequest.set(true);
		}

		void clearWaitlistRequest() {
			waitlistRequest.remove();
		}

		void markDirectParticipationCommitted(long roomId) {
			Scenario scenario = activeScenario.get();
			if (scenario != null && scenario.roomId == roomId) {
				scenario.directParticipationCommitted.countDown();
			}
		}

		void beforeRoomRead(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || !Boolean.TRUE.equals(waitlistRequest.get())
				|| !isRoomRead(method, arguments, scenario)
				|| !scenario.waitlistReadBlocked.compareAndSet(false, true)) {
				return;
			}
			long gateWaitStartedAt = System.nanoTime();
			await(scenario.directParticipationCommitted, "직접 참가 커밋");
			roomReadGate.recordGateWaitNanos(System.nanoTime() - gateWaitStartedAt);
		}

		void deactivate() {
			Scenario scenario = activeScenario.getAndSet(null);
			if (scenario != null) {
				scenario.directParticipationCommitted.countDown();
			}
		}

		private boolean isRoomRead(Method method, Object[] arguments, Scenario scenario) {
			return method.getName().equals("findById")
				&& arguments != null
				&& arguments.length == 1
				&& arguments[0] instanceof Long roomId
				&& scenario.roomId == roomId;
		}

		private void await(CountDownLatch latch, String phase) {
			try {
				if (!latch.await(10, TimeUnit.SECONDS)) {
					throw new AssertionError(phase + " 대기 시간이 초과되었습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(phase + " 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final CountDownLatch directParticipationCommitted = new CountDownLatch(1);
			private final AtomicBoolean waitlistReadBlocked = new AtomicBoolean();

			private Scenario(long roomId) {
				this.roomId = roomId;
			}
		}
	}

	static final class LatestBusinessStateConflictGate {

		private final JdbcTemplate jdbcTemplate;
		private final TransactionTemplate requiresNewTransaction;
		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		LatestBusinessStateConflictGate(
			PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
			requiresNewTransaction = new TransactionTemplate(transactionManager);
			requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		}

		void activate(long roomId) {
			if (!activeScenario.compareAndSet(null, new Scenario(roomId))) {
				throw new IllegalStateException("최신 업무 상태 conflict gate가 이미 활성화되어 있습니다.");
			}
		}

		int forcedConflictCount() {
			Scenario scenario = activeScenario.get();
			return scenario != null && scenario.conflictForced.get() ? 1 : 0;
		}

		int roomReadCount() {
			return activeScenario.get().roomReadCount.get();
		}

		int canceledRoomReadCount() {
			return activeScenario.get().canceledRoomReadCount.get();
		}

		int claimVersionAttemptCount() {
			return activeScenario.get().claimVersionAttemptCount.get();
		}

		void afterRoomRead(Method method, Object[] arguments, Object result) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || !isRoomRead(method, arguments)) {
				return;
			}
			scenario.roomReadCount.incrementAndGet();
			if (result instanceof Optional<?> optional
				&& optional.isPresent()
				&& optional.get() instanceof Room room
				&& room.getStatus() == RoomStatus.CANCELED) {
				scenario.canceledRoomReadCount.incrementAndGet();
			}
		}

		void beforeClaimVersion(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || !method.getName().equals("claimVersion")
				|| arguments == null
				|| arguments.length != 2
				|| !(arguments[0] instanceof Long roomId)
				|| scenario.roomId != roomId) {
				return;
			}
			scenario.claimVersionAttemptCount.incrementAndGet();
			if (!scenario.conflictForced.compareAndSet(false, true)) {
				return;
			}
			requiresNewTransaction.executeWithoutResult(status -> jdbcTemplate.update("""
				update rooms
				set status = 'CANCELED', version = version + 1,
					updated_at = TIMESTAMP WITH TIME ZONE '2026-07-28T00:00:00Z'
				where id = ?
				""", roomId));
			throw new ObjectOptimisticLockingFailureException(Room.class, roomId);
		}

		private boolean isRoomRead(Method method, Object[] arguments) {
			return method.getName().equals("findById")
				&& arguments != null
				&& arguments.length == 1
				&& arguments[0] instanceof Long roomId
				&& activeScenario.get().roomId == roomId;
		}

		void deactivate() {
			activeScenario.set(null);
		}

		private static final class Scenario {

			private final long roomId;
			private final AtomicBoolean conflictForced = new AtomicBoolean();
			private final AtomicInteger roomReadCount = new AtomicInteger();
			private final AtomicInteger canceledRoomReadCount = new AtomicInteger();
			private final AtomicInteger claimVersionAttemptCount = new AtomicInteger();

			private Scenario(long roomId) {
				this.roomId = roomId;
			}
		}
	}

	static final class WaitlistTransitionGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		void activate(long roomId, long firstWaitingUserId, TransitionOrder order) {
			if (!activeScenario.compareAndSet(null, new Scenario(roomId, firstWaitingUserId, order))) {
				throw new IllegalStateException("대기 전이 gate가 이미 활성화되어 있습니다.");
			}
		}

		void before(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || arguments == null || arguments.length < 2
				|| !(arguments[0] instanceof Long roomId) || !(arguments[1] instanceof Long userId)
				|| scenario.roomId != roomId || scenario.firstWaitingUserId != userId) {
				return;
			}
			if (method.getName().equals("promoteWaiting") && scenario.order == TransitionOrder.CANCEL_FIRST) {
				await(scenario.cancellationCompleted, "첫 WAITING 취소 확정");
			}
			if (method.getName().equals("cancelWaiting") && scenario.order == TransitionOrder.PROMOTION_FIRST) {
				await(scenario.promotionCompleted, "첫 WAITING 승격 확정");
			}
		}

		void after(Method method, Object[] arguments) {
			Scenario scenario = activeScenario.get();
			if (scenario == null || arguments == null || arguments.length < 2
				|| !(arguments[0] instanceof Long roomId) || !(arguments[1] instanceof Long userId)
				|| scenario.roomId != roomId || scenario.firstWaitingUserId != userId) {
				return;
			}
			if (method.getName().equals("cancelWaiting") && scenario.order == TransitionOrder.CANCEL_FIRST) {
				scenario.cancellationCompleted.countDown();
			}
			if (method.getName().equals("promoteWaiting") && scenario.order == TransitionOrder.PROMOTION_FIRST) {
				scenario.promotionCompleted.countDown();
			}
		}

		void deactivate() {
			Scenario scenario = activeScenario.getAndSet(null);
			if (scenario != null) {
				scenario.cancellationCompleted.countDown();
				scenario.promotionCompleted.countDown();
			}
		}

		private void await(CountDownLatch latch, String phase) {
			try {
				if (!latch.await(10, TimeUnit.SECONDS)) {
					throw new AssertionError(phase + " 대기 시간이 초과되었습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(phase + " 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final long firstWaitingUserId;
			private final TransitionOrder order;
			private final CountDownLatch cancellationCompleted = new CountDownLatch(1);
			private final CountDownLatch promotionCompleted = new CountDownLatch(1);

			private Scenario(long roomId, long firstWaitingUserId, TransitionOrder order) {
				this.roomId = roomId;
				this.firstWaitingUserId = firstWaitingUserId;
				this.order = order;
			}
		}
	}

	private static final class GateAwareRoomRepositoryInvocationHandler implements InvocationHandler {

		private final RoomRepository delegate;
		private final WaitlistFirstDecisionGate waitlistFirstDecisionGate;
		private final DirectParticipationFirstGate directParticipationFirstGate;
		private final LatestBusinessStateConflictGate latestBusinessStateConflictGate;

		private GateAwareRoomRepositoryInvocationHandler(
			RoomRepository delegate,
			WaitlistFirstDecisionGate waitlistFirstDecisionGate,
			DirectParticipationFirstGate directParticipationFirstGate,
			LatestBusinessStateConflictGate latestBusinessStateConflictGate) {
			this.delegate = delegate;
			this.waitlistFirstDecisionGate = waitlistFirstDecisionGate;
			this.directParticipationFirstGate = directParticipationFirstGate;
			this.latestBusinessStateConflictGate = latestBusinessStateConflictGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				latestBusinessStateConflictGate.beforeClaimVersion(method, arguments);
				waitlistFirstDecisionGate.beforeRoomRead(method, arguments);
				directParticipationFirstGate.beforeRoomRead(method, arguments);
				Object result = method.invoke(delegate, arguments);
				latestBusinessStateConflictGate.afterRoomRead(method, arguments, result);
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	private static final class GateAwareRoomWaitlistRepositoryInvocationHandler implements InvocationHandler {

		private final RoomWaitlistRepository delegate;
		private final WaitlistTransitionGate waitlistTransitionGate;

		private GateAwareRoomWaitlistRepositoryInvocationHandler(
			RoomWaitlistRepository delegate, WaitlistTransitionGate waitlistTransitionGate) {
			this.delegate = delegate;
			this.waitlistTransitionGate = waitlistTransitionGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				waitlistTransitionGate.before(method, arguments);
				Object result = method.invoke(delegate, arguments);
				waitlistTransitionGate.after(method, arguments);
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}
}
