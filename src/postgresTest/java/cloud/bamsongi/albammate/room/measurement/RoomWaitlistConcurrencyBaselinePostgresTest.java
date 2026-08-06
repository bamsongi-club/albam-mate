package cloud.bamsongi.albammate.room.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

@Testcontainers
@SpringBootTest
@TestPropertySource(properties = {
	"spring.datasource.hikari.maximum-pool-size=10",
	"app.notification.relay.enabled=false"})
@Import(RoomWaitlistConcurrencyBaselinePostgresTest.BaselineTestConfiguration.class)
class RoomWaitlistConcurrencyBaselinePostgresTest {

	private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
	private static final String FIXTURE_SEED = "ROOM-10A-20260806";
	private static final String FIXTURE_STATEMENT_MARKER = "room10b_fixture_marker";
	private static final String RETRY_EVENT = "room_10b_retry";
	private static final String PARTICIPATION_RETRY_EVENT = "room_participation_retry";
	private static final String PARTICIPATION_CANCEL_RETRY_EVENT = "room_participation_cancel_retry";
	private static final String WAITLIST_CANCEL_RETRY_EVENT = "room_waitlist_cancel_retry";

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
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
	private LatestBusinessStateConflictGate latestBusinessStateConflictGate;

	@Autowired
	private WaitlistTransitionGate waitlistTransitionGate;

	@Autowired
	@Qualifier("roomRepository") private RoomRepository roomRepository;

	@Autowired
	@Qualifier("roomWaitlistRepository") private RoomWaitlistRepository roomWaitlistRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@BeforeEach
	void enablePgStatStatements() {
		jdbcTemplate.execute("create extension if not exists pg_stat_statements");
	}

	@AfterEach
	void tearDown() {
		roomReadGate.deactivate();
		waitlistFirstDecisionGate.deactivate();
		latestBusinessStateConflictGate.deactivate();
		waitlistTransitionGate.deactivate();
		jdbcTemplate.execute("truncate table participations, room_waitlists, rooms, users restart identity cascade");
	}

	@Test
	void 마지막_좌석_직전_직접_참가와_대기_신청은_최신_결과로_수렴한다() throws Exception {
		LastSeatWaitlistFixture directFirstFixture = createLastSeatWaitlistFixture("direct-first");
		roomParticipationService.participate(directFirstFixture.directJoinUserId(), directFirstFixture.room().getId());
		assertTrue(roomWaitlistCommandService.register(
			directFirstFixture.waitingUserId(), directFirstFixture.room().getId()).created());

		assertEquals(RoomStatus.CLOSED, roomStatus(directFirstFixture.room().getId()));
		assertEquals(2, activeParticipantCount(directFirstFixture.room().getId()));
		assertEquals(RoomWaitlistStatus.WAITING,
			waitlistStatus(directFirstFixture.room().getId(), directFirstFixture.waitingUserId()));
		assertWaitlistRoomInvariant(directFirstFixture.room().getId());

		LastSeatWaitlistFixture concurrentFixture = createLastSeatWaitlistFixture("same-version");
		RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureLastSeatWaitlistRound(concurrentFixture);

		assertEquals(1, measurement.successCount());
		assertEquals(1, measurement.businessFailureCount());
		assertTrue(measurement.hasOnlyBusinessError(ErrorCode.WAITLIST_NOT_AVAILABLE));
		assertEquals(RoomStatus.CLOSED, roomStatus(concurrentFixture.room().getId()));
		assertEquals(2, activeParticipantCount(concurrentFixture.room().getId()));
		assertEquals(0, activeWaitingCount(concurrentFixture.room().getId()));
		assertWaitlistRoomInvariant(concurrentFixture.room().getId());
	}

	@Test
	void 복수_참가_취소는_고정_동시_수준마다_FIFO_자동_승격한다() throws Exception {
		for (int concurrencyLevel : List.of(2, 4, 8)) {
			CancellationPromotionFixture fixture = createCancellationPromotionFixture(
				"promotion-" + concurrencyLevel, concurrencyLevel);
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = measureCancellationPromotionRound(fixture);

			assertTrue(measurement.successCount() > 0);
			assertEquals(concurrencyLevel, measurement.successCount() + measurement.concurrencyFailureCount());
			assertEquals(0, measurement.businessFailureCount());
			assertEquals(0, measurement.technicalFailureCount());
			assertEquals(fixture.room().getCapacity(), activeParticipantCount(fixture.room().getId()));
			assertEquals(RoomStatus.CLOSED, roomStatus(fixture.room().getId()));
			for (int index = 0; index < fixture.leavingUserIds().size(); index++) {
				ParticipationStatus expectedStatus = measurement.requests().get(index).successful()
					? ParticipationStatus.CANCELED
					: ParticipationStatus.ACTIVE;
				assertEquals(expectedStatus,
					participationStatus(fixture.room().getId(), fixture.leavingUserIds().get(index)));
			}
			for (int index = 0; index < fixture.waitingUserIds().size(); index++) {
				if (index < measurement.successCount()) {
					assertEquals(RoomWaitlistStatus.PROMOTED,
						waitlistStatus(fixture.room().getId(), fixture.waitingUserIds().get(index)));
					assertEquals(ParticipationStatus.ACTIVE,
						participationStatus(fixture.room().getId(), fixture.waitingUserIds().get(index)));
				} else {
					assertEquals(RoomWaitlistStatus.WAITING,
						waitlistStatus(fixture.room().getId(), fixture.waitingUserIds().get(index)));
					assertEquals(0, participationCount(fixture.room().getId(), fixture.waitingUserIds().get(index)));
				}
			}
			assertEquals(concurrencyLevel - measurement.successCount() + 1, activeWaitingCount(fixture.room().getId()));
			assertWaitlistRoomInvariant(fixture.room().getId());
		}
	}

	@Test
	void 첫_WAITING_취소와_자동_승격은_확정_순서별_최신_상태를_보존한다() throws Exception {
		WaitlistCancellationFixture cancellationFirstFixture = createWaitlistCancellationFixture("cancel-first");
		RoomConcurrencyBaselineSupport.RoundMeasurement cancellationFirst = measureWaitlistCancellationRound(
			cancellationFirstFixture, TransitionOrder.CANCEL_FIRST);

		assertEquals(2, cancellationFirst.successCount());
		assertEquals(RoomWaitlistStatus.CANCELED,
			waitlistStatus(cancellationFirstFixture.room().getId(), cancellationFirstFixture.firstWaitingUserId()));
		assertEquals(RoomWaitlistStatus.PROMOTED,
			waitlistStatus(cancellationFirstFixture.room().getId(), cancellationFirstFixture.secondWaitingUserId()));
		assertEquals(ParticipationStatus.ACTIVE,
			participationStatus(cancellationFirstFixture.room().getId(),
				cancellationFirstFixture.secondWaitingUserId()));
		assertWaitlistRoomInvariant(cancellationFirstFixture.room().getId());

		WaitlistCancellationFixture promotionFirstFixture = createWaitlistCancellationFixture("promotion-first");
		RoomConcurrencyBaselineSupport.RoundMeasurement promotionFirst = measureWaitlistCancellationRound(
			promotionFirstFixture, TransitionOrder.PROMOTION_FIRST);

		assertEquals(1, promotionFirst.successCount());
		assertEquals(1, promotionFirst.businessFailureCount());
		assertTrue(promotionFirst.hasOnlyBusinessError(ErrorCode.WAITLIST_ENTRY_NOT_FOUND));
		assertEquals(RoomWaitlistStatus.PROMOTED,
			waitlistStatus(promotionFirstFixture.room().getId(), promotionFirstFixture.firstWaitingUserId()));
		assertEquals(ParticipationStatus.ACTIVE,
			participationStatus(promotionFirstFixture.room().getId(), promotionFirstFixture.firstWaitingUserId()));
		assertEquals(RoomWaitlistStatus.WAITING,
			waitlistStatus(promotionFirstFixture.room().getId(), promotionFirstFixture.secondWaitingUserId()));
		assertEquals(0, participationCount(
			promotionFirstFixture.room().getId(), promotionFirstFixture.secondWaitingUserId()));
		assertEquals(1, activeWaitingCount(promotionFirstFixture.room().getId()));
		assertWaitlistRoomInvariant(promotionFirstFixture.room().getId());
	}

	@Test
	void 대기와_자동_승격_경합_측정은_공통_raw_형식으로_기록한다() throws Exception {
		runLastSeatWaitlistPreparationRound(createLastSeatWaitlistFixture("prepare-last-seat"));
		for (int round = 1; round <= 3; round++) {
			LastSeatWaitlistFixture fixture = createLastSeatWaitlistFixture("raw-last-seat-" + round);
			assertRawMeasurement(measureLastSeatWaitlistRound(fixture),
				"last-seat-waitlist", 2, fixture.room().getId(), PARTICIPATION_RETRY_EVENT);
		}
		for (int concurrencyLevel : List.of(2, 4, 8)) {
			runCancellationPromotionPreparationRound(createCancellationPromotionFixture(
				"prepare-promotion-" + concurrencyLevel, concurrencyLevel));
			for (int round = 1; round <= 3; round++) {
				CancellationPromotionFixture fixture = createCancellationPromotionFixture(
					"raw-promotion-" + concurrencyLevel + "-" + round, concurrencyLevel);
				assertRawMeasurement(measureCancellationPromotionRound(fixture),
					"cancel-promote", concurrencyLevel, fixture.room().getId(), PARTICIPATION_CANCEL_RETRY_EVENT);
			}
		}
		for (TransitionOrder order : TransitionOrder.values()) {
			runWaitlistCancellationPreparationRound(createWaitlistCancellationFixture("prepare-" + order), order);
			for (int round = 1; round <= 3; round++) {
				WaitlistCancellationFixture fixture = createWaitlistCancellationFixture("raw-" + order + "-" + round);
				assertRawMeasurement(measureWaitlistCancellationRound(fixture, order), order.scenarioName(), 2,
					fixture.room().getId(), WAITLIST_CANCEL_RETRY_EVENT, PARTICIPATION_CANCEL_RETRY_EVENT);
			}
		}
	}

	@Test
	void 각_측정_round_뒤_PostgreSQL_대기_저장_불변식이_유지된다() throws Exception {
		LastSeatWaitlistFixture lastSeatFixture = createLastSeatWaitlistFixture("invariant-last-seat");
		measureLastSeatWaitlistRound(lastSeatFixture);
		assertWaitlistRoomInvariant(lastSeatFixture.room().getId());

		for (int concurrencyLevel : List.of(2, 4, 8)) {
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

	private RoomConcurrencyBaselineSupport.RoundMeasurement measureLastSeatWaitlistRound(
		LastSeatWaitlistFixture fixture)
		throws Exception {
		waitlistFirstDecisionGate.activate(fixture.room().getId());
		try {
			return measureRound("last-seat-waitlist", 2, fixture.room().getId(), lastSeatWaitlistCommands(fixture));
		} finally {
			waitlistFirstDecisionGate.deactivate();
		}
	}

	private List<Callable<?>> lastSeatWaitlistCommands(LastSeatWaitlistFixture fixture) {
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
			fixture.leavingUserIds().stream().<Callable<?>>map(
				userId -> () -> roomParticipationCancelService.cancelParticipation(userId, fixture.room().getId()))
				.toList());
	}

	private RoomConcurrencyBaselineSupport.RoundMeasurement measureWaitlistCancellationRound(
		WaitlistCancellationFixture fixture, TransitionOrder order) throws Exception {
		waitlistTransitionGate.activate(fixture.room().getId(), fixture.firstWaitingUserId(), order);
		try {
			return measureRound(order.scenarioName(), 2, fixture.room().getId(), List.<Callable<?>>of(
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
		String scenario, int concurrencyLevel, long roomId, List<Callable<?>> commands) throws Exception {
		roomReadGate.activate(roomId, commands.size());
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

	private void runLastSeatWaitlistPreparationRound(LastSeatWaitlistFixture fixture) throws Exception {
		roomReadGate.activate(fixture.room().getId(), 2);
		try {
			baselineSupport.runPreparationRound(List.<Callable<?>>of(
				() -> roomParticipationService.participate(fixture.directJoinUserId(), fixture.room().getId()),
				() -> runWaitlistCommandForPreparation(fixture.waitingUserId(), fixture.room().getId())));
			roomReadGate.assertInitialReadsShareOneVersion();
		} finally {
			roomReadGate.deactivate();
		}
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
		assertTrue(retryLogs.stream().allMatch(log -> log.retryAttempt() || log.exhaustedAttempt()));
		assertTrue(retryLogs.stream()
			.filter(RoomConcurrencyBaselineSupport.RetryLogRecord::exhaustedAttempt)
			.allMatch(log -> log.attempt() == 3));
	}

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
			LatestBusinessStateConflictGate latestBusinessStateConflictGate) {
			RoomRepository readGatedRepository = RoomConcurrencyBaselineSupport.gatedRoomRepository(delegate,
				roomReadGate);
			InvocationHandler handler = new GateAwareRoomRepositoryInvocationHandler(
				readGatedRepository, waitlistFirstDecisionGate, latestBusinessStateConflictGate);
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

		void afterRoomRead(Method method, Object[] arguments) {
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
		private final LatestBusinessStateConflictGate latestBusinessStateConflictGate;

		private GateAwareRoomRepositoryInvocationHandler(
			RoomRepository delegate,
			WaitlistFirstDecisionGate waitlistFirstDecisionGate,
			LatestBusinessStateConflictGate latestBusinessStateConflictGate) {
			this.delegate = delegate;
			this.waitlistFirstDecisionGate = waitlistFirstDecisionGate;
			this.latestBusinessStateConflictGate = latestBusinessStateConflictGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				latestBusinessStateConflictGate.beforeClaimVersion(method, arguments);
				Object result = method.invoke(delegate, arguments);
				waitlistFirstDecisionGate.afterRoomRead(method, arguments);
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
