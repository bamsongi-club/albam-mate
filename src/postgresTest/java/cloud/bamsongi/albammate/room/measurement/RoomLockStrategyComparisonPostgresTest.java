package cloud.bamsongi.albammate.room.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.regex.Pattern;

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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.exception.GlobalExceptionHandler;
import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.entity.RoomWaitlist;
import cloud.bamsongi.albammate.room.entity.RoomWaitlistId;
import cloud.bamsongi.albammate.room.enums.ExperienceLevel;
import cloud.bamsongi.albammate.room.enums.ParticipationStatus;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.enums.RoomType;
import cloud.bamsongi.albammate.room.enums.RoomWaitlistStatus;
import cloud.bamsongi.albammate.room.repository.ParticipationRepository;
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

/** ROOM-LOCK-01 후보 A의 실제 업무 경로·실패 경계·원자료 보존을 검증한다. */
@Testcontainers
@SpringBootTest
@Import(RoomLockStrategyComparisonPostgresTest.MeasurementTestConfiguration.class)
@TestPropertySource(properties = {
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false"})
class RoomLockStrategyComparisonPostgresTest {

	private static final org.slf4j.Logger log = LoggerFactory.getLogger(RoomLockStrategyComparisonPostgresTest.class);
	private static final String CANDIDATE = "A";
	private static final Instant FIXED_TIME = RoomLockComparisonMeasurementContract.FIXED_TIME;
	private static final String FIXTURE_SEED = RoomLockComparisonMeasurementContract.FIXTURE_SEED;
	private static final long WAIT_SECONDS = 10;
	private static final List<String> METRIC_FIELDS = RoomLockComparisonMeasurementContract.METRIC_FIELDS;
	private static final String EXPECTED_ARTIFACT_SHA256 = "83FA5600C39FA7A140CAEF84A7D9E533EC8BA453C2496F687D3CFFB32894FD57";
	private static final Path RAW_ARTIFACT = Path.of(
		"docs", "measurements", "results", "room-785-a", "room-785-a.json");
	private int t2Repetition;
	private RawMetrics latestMeasuredRawMetrics;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
		.withDatabaseName("albam_mate_room_lock_01_a");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private ParticipationRepository participationRepository;
	@Autowired
	private RoomWaitlistRepository roomWaitlistRepository;
	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomWaitlistCommandService roomWaitlistCommandService;
	@Autowired
	private RoomStatusCorrectionCoordinator roomStatusCorrectionCoordinator;
	@Autowired
	private GlobalExceptionHandler globalExceptionHandler;
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate;
	@Autowired
	private RoomConcurrencyBaselineSupport baselineSupport;
	@Autowired
	private FailureGate failureGate;
	@Autowired
	private CommitOrderGate commitOrderGate;
	@Autowired
	private RoomRowLockHolder roomRowLockHolder;
	@Autowired
	private DeadlockGate deadlockGate;

	@BeforeEach
	void cleanFixture() {
		jdbcTemplate.execute("create extension if not exists pg_stat_statements");
		jdbcTemplate.execute("truncate table room_waitlists, participations, rooms, users restart identity cascade");
		jdbcTemplate.execute("select pg_stat_statements_reset()");
		failureGate.deactivate();
		commitOrderGate.deactivate();
		deadlockGate.deactivate();
		roomRowLockHolder.resetAttemptCount();
	}

	@AfterEach
	void cleanConnectionState() {
		roomReadGate.deactivate();
		failureGate.deactivate();
		commitOrderGate.deactivate();
		deadlockGate.deactivate();
		assertEquals("0", jdbcTemplate.queryForObject("show lock_timeout", String.class));
	}

	@Test
	void T1_실제_참가_대기_취소_승격_경로는_마지막_좌석_불변식을_유지한다() throws Exception {
		List<RawMetrics> commitOrderMetrics = new ArrayList<>();
		for (boolean firstParticipantFirst : List.of(true, false)) {
			commitOrderMetrics.add(assertLastSeatCommitOrder(firstParticipantFirst));
		}
		RawMetrics combinedMetrics = combineRawMetrics(commitOrderMetrics);
		assertEquals(4, combinedMetrics.requestCount());
		assertEquals(combinedMetrics.requestCount(), combinedMetrics.responseNanos().size());
		logRawArtifact("T1", combinedMetrics);
	}

	private RawMetrics assertLastSeatCommitOrder(boolean firstParticipantFirst) {
		long hostUserId = insertUser("t1-host-" + firstParticipantFirst);
		long firstUserId = insertUser("t1-first-" + firstParticipantFirst);
		long secondUserId = insertUser("t1-second-" + firstParticipantFirst);
		long thirdUserId = insertUser("t1-third-" + firstParticipantFirst);
		Room room = createRoom(hostUserId, 1);

		List<CommandResult> results = runCommitOrdered(
			"T1-last-seat-" + firstParticipantFirst,
			room.getId(),
			firstParticipantFirst,
			() -> roomParticipationService.participate(firstUserId, room.getId()),
			() -> roomParticipationService.participate(secondUserId, room.getId()),
			WriteExpectation.BOTH);

		assertEquals(1, results.stream().filter(CommandResult::successful).count());
		assertEquals(ErrorCode.CAPACITY_EXCEEDED,
			results.stream().filter(result -> !result.successful()).findFirst().orElseThrow().errorCode());
		long participantUserId = results.get(0).successful() ? firstUserId : secondUserId;
		long waitingUserId = participantUserId == firstUserId ? secondUserId : firstUserId;
		assertTrue(roomWaitlistCommandService.register(waitingUserId, room.getId()).created());
		assertTrue(roomWaitlistCommandService.register(thirdUserId, room.getId()).created());
		assertTrue(waitlistQueueOrder(room.getId(), waitingUserId) < waitlistQueueOrder(room.getId(), thirdUserId));
		roomParticipationCancelService.cancelParticipation(participantUserId, room.getId());

		assertRoomInvariant(room.getId());
		assertEquals(ParticipationStatus.CANCELED, participationStatus(room.getId(), participantUserId));
		assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), waitingUserId));
		assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(room.getId(), waitingUserId));
		assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(room.getId(), thirdUserId));
		assertEquals(0, activeParticipationCount(room.getId(), thirdUserId));
		return latestMeasuredRawMetrics;
	}

	@Test
	void T2_실제_대기_재활성_취소승격과_시작경계_양쪽순서는_부분변경없이_수렴한다() throws Exception {
		for (int repetition = 1; repetition <= RoomLockComparisonMeasurementContract.T2_ROUND_REPETITIONS; repetition++) {
			t2Repetition = repetition;
			assertWaitlistCancellationAndPromotionCommitOrders();
			assertWaitlistReactivationCommitOrders();
			assertStartBoundaryConvergence(true);
			assertStartBoundaryConvergence(false);
			assertStartBoundaryWaitlistRegistrationConvergence(true);
			assertStartBoundaryWaitlistRegistrationConvergence(false);
			assertStartBoundaryWaitlistReactivationConvergence(true);
			assertStartBoundaryWaitlistReactivationConvergence(false);
			assertStartBoundaryWaitlistCancellationConvergence(true);
			assertStartBoundaryWaitlistCancellationConvergence(false);
			assertStartBoundaryParticipationCancellationConvergence(true);
			assertStartBoundaryParticipationCancellationConvergence(false);
		}
		t2Repetition = 0;
		assertWaitlistRegistrationPreconditionBoundary();
		assertWaitlistRegistrationCommitOrders();
		assertParticipationCancellationCommitOrders();
		assertWorkflowAssertions("T2", List.of(
			"waitlist registration precondition boundary, rejected before write in both orders",
			"new waitlist registrations contend in both commit orders, FIFO follows commit order",
			"waitlist reactivation contends with another registration in both commit orders",
			"waitlist cancellation and promotion both orders",
			"two participation cancellations both orders",
			"start correction and direct participation both orders",
			"start correction and waitlist registration both orders",
			"start correction and waitlist cancellation both orders",
			"start correction and participation cancellation both orders"));
		jdbcTemplate.execute("truncate table room_waitlists, participations, rooms, users restart identity cascade");
		assertDueRoomOrderScenario();
		assertLastSeatLockScenario();
		assertRollbackScenario();
		assertArtifactMetadata();
	}

	@Test
	void T3_optimistic_충돌만_재시도하고_409_500_경계와_rollback을_남긴다() {
		long hostUserId = insertUser("t3-host");
		long retryingUserId = insertUser("t3-retrying");
		Room room = createRoom(hostUserId, 1);
		long optimisticRoomVersionBefore = roomVersion(room.getId());
		T3Measurement optimisticMeasurement = new T3Measurement();
		failureGate.activateOptimisticFailures(3);

		BusinessException exhausted = optimisticMeasurement.measureRequest(() -> assertThrows(BusinessException.class,
			() -> roomParticipationService.participate(retryingUserId, room.getId())));

		assertEquals(ErrorCode.ROOM_CONCURRENT_MODIFICATION, exhausted.getErrorCode());
		assertEquals(HttpStatus.CONFLICT, globalExceptionHandler.handleBusinessException(exhausted).getStatusCode());
		assertEquals(3, failureGate.failureCount());
		assertEquals(0, activeParticipationCount(room.getId(), retryingUserId));
		assertEquals(0, participationCount(room.getId(), retryingUserId));
		assertTrue(roomWaitlistRepository.findById(new RoomWaitlistId(room.getId(), retryingUserId)).isEmpty());
		assertEquals(optimisticRoomVersionBefore, roomVersion(room.getId()));
		assertRoomInvariant(room.getId());
		optimisticMeasurement.recordFailure(exhausted, failureGate.failureCount(), failureGate.failureCount(), true);
		logT3Raw("T3-optimistic-exhausted", optimisticMeasurement);

		long businessUserId = insertUser("t3-business");
		roomParticipationService.participate(businessUserId, room.getId());
		long rejectedUserId = insertUser("t3-rejected");
		BusinessException business = assertThrows(BusinessException.class,
			() -> roomParticipationService.participate(rejectedUserId, room.getId()));
		assertEquals(ErrorCode.CAPACITY_EXCEEDED, business.getErrorCode());
		assertEquals(HttpStatus.CONFLICT, globalExceptionHandler.handleBusinessException(business).getStatusCode());
		assertEquals(3, failureGate.failureCount());
		assertEquals(0, activeParticipationCount(room.getId(), rejectedUserId));

		long technicalHostUserId = insertUser("t3-technical-host");
		long technicalUserId = insertUser("t3-technical-user");
		Room technicalRoom = createRoom(technicalHostUserId, 1);
		long technicalRoomVersionBefore = roomVersion(technicalRoom.getId());
		T3Measurement technicalMeasurement = new T3Measurement();
		failureGate.activateTechnicalFailure();
		RuntimeException technical = technicalMeasurement.measureRequest(() -> assertThrows(RuntimeException.class,
			() -> roomParticipationService.participate(technicalUserId, technicalRoom.getId())));
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
			globalExceptionHandler.handleUnhandledException(technical).getStatusCode());
		assertEquals(1, failureGate.failureCount());
		assertEquals(0, activeParticipationCount(technicalRoom.getId(), technicalUserId));
		assertEquals(0, participationCount(technicalRoom.getId(), technicalUserId));
		assertTrue(
			roomWaitlistRepository.findById(new RoomWaitlistId(technicalRoom.getId(), technicalUserId)).isEmpty());
		assertEquals(technicalRoomVersionBefore, roomVersion(technicalRoom.getId()));
		assertRoomInvariant(technicalRoom.getId());
		technicalMeasurement.recordFailure(technical, failureGate.failureCount(), 0, false);
		logT3Raw("T3-unexpected-technical", technicalMeasurement);
		failureGate.deactivate();

		long timeoutHostUserId = insertUser("t3-timeout-host");
		long timeoutUserId = insertUser("t3-timeout-user");
		Room timeoutRoom = createRoom(timeoutHostUserId, 1);
		long roomVersionBeforeTimeout = roomVersion(timeoutRoom.getId());
		T3Measurement timeoutMeasurement = new T3Measurement();
		RuntimeException lockTimeout = participateWhileRoomRowLocked(
			timeoutUserId, timeoutRoom.getId(), timeoutMeasurement);
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
			globalExceptionHandler.handleUnhandledException(lockTimeout).getStatusCode());
		assertTrue(hasSqlState(lockTimeout, "55P03"));
		assertEquals(1, roomRowLockHolder.lockTimeoutAttemptCount());
		assertEquals(0, activeParticipationCount(timeoutRoom.getId(), timeoutUserId));
		assertEquals(0, jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ?",
			Integer.class,
			timeoutRoom.getId(),
			timeoutUserId));
		assertTrue(roomWaitlistRepository.findById(new RoomWaitlistId(timeoutRoom.getId(), timeoutUserId)).isEmpty());
		assertEquals(roomVersionBeforeTimeout, roomVersion(timeoutRoom.getId()));
		assertRoomInvariant(timeoutRoom.getId());
		timeoutMeasurement.recordFailure(lockTimeout, roomRowLockHolder.lockTimeoutAttemptCount(), 0, false);
		logT3Raw("T3-lock-timeout", timeoutMeasurement);

		T3Measurement deadlockMeasurement = new T3Measurement();
		assertDeadlockIsTechnicalFailureWithoutPartialState(deadlockMeasurement);
		logT3Raw("T3-deadlock", deadlockMeasurement);
		assertArchivedArtifact();
		assertWorkflowAssertions("T3", List.of(
			"optimistic retry exhaustion",
			"business 409 without retry",
			"controlled technical 500 with rollback",
			"PostgreSQL row-lock timeout 55P03",
			"PostgreSQL deadlock 40P01 with one survivor"));
	}

	private RuntimeException participateWhileRoomRowLocked(long userId, long roomId, T3Measurement measurement) {
		CountDownLatch rowLocked = new CountDownLatch(1);
		CountDownLatch releaseLock = new CountDownLatch(1);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<?> lockHolder = executor.submit(() -> roomRowLockHolder.hold(roomId, rowLocked, releaseLock));
		Future<RuntimeException> participation = null;
		try {
			assertTrue(rowLocked.await(WAIT_SECONDS, TimeUnit.SECONDS));
			assertTrue(roomRowLockHolder.awaitLockTimeoutActivation());
			measurement.beginRequestWindow();
			long startedNanos = System.nanoTime();
			participation = executor.submit(
				() -> assertThrows(RuntimeException.class, () -> roomParticipationService.participate(userId, roomId)));
			RuntimeException lockTimeout = participation.get(WAIT_SECONDS, TimeUnit.SECONDS);
			measurement.endRequestWindow(System.nanoTime() - startedNanos);
			return lockTimeout;
		} catch (Exception exception) {
			throw new AssertionError("실제 PostgreSQL row lock timeout을 관찰하지 못했습니다.", exception);
		} finally {
			releaseLock.countDown();
			awaitLockHolderCompletion(lockHolder);
			awaitParticipationCompletion(participation);
			executor.shutdownNow();
			awaitExecutorCompletion(executor);
		}
	}

	private void awaitLockHolderCompletion(Future<?> lockHolder) {
		try {
			lockHolder.get(WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (Exception exception) {
			throw new AssertionError("row lock holder를 정리하지 못했습니다.", exception);
		}
	}

	private void awaitParticipationCompletion(Future<?> participation) {
		if (participation == null) {
			return;
		}
		try {
			participation.get(WAIT_SECONDS, TimeUnit.SECONDS);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			participation.cancel(true);
			throw new AssertionError("참가 요청 스레드 정리 중 인터럽트되었습니다.", exception);
		} catch (TimeoutException exception) {
			participation.cancel(true);
			throw new AssertionError("참가 요청 스레드가 종료되지 않았습니다.", exception);
		} catch (ExecutionException ignored) {
			// 원래의 참가 요청 실패가 이미 테스트 assertion으로 보고된 뒤의 정리 단계다.
		}
	}

	private void awaitExecutorCompletion(ExecutorService executor) {
		try {
			if (!executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS)) {
				throw new AssertionError("lock timeout fixture executor가 종료되지 않았습니다.");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError("lock timeout fixture executor 정리 중 인터럽트되었습니다.", exception);
		}
	}

	private void assertDeadlockIsTechnicalFailureWithoutPartialState(T3Measurement measurement) {
		long firstHostUserId = insertUser("t3-deadlock-first-host");
		long firstUserId = insertUser("t3-deadlock-first-user");
		long secondHostUserId = insertUser("t3-deadlock-second-host");
		long secondUserId = insertUser("t3-deadlock-second-user");
		Room firstRoom = createRoom(firstHostUserId, 1);
		Room secondRoom = createRoom(secondHostUserId, 1);
		long firstRoomVersionBefore = roomVersion(firstRoom.getId());
		long secondRoomVersionBefore = roomVersion(secondRoom.getId());
		deadlockGate.activate(firstRoom.getId(), secondRoom.getId());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		Future<TimedOutcome> first = null;
		Future<TimedOutcome> second = null;
		RetryTraceCapture retryTrace = RetryTraceCapture.attach();
		try {
			measurement.beginRequestWindow();
			first = executor.submit(() -> captureFailure(
				() -> roomParticipationService.participate(firstUserId, firstRoom.getId())));
			second = executor.submit(() -> captureFailure(
				() -> roomParticipationService.participate(secondUserId, secondRoom.getId())));
			TimedOutcome firstOutcome = first.get(WAIT_SECONDS, TimeUnit.SECONDS);
			TimedOutcome secondOutcome = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
			measurement.endRequestWindow(firstOutcome.elapsedNanos(), secondOutcome.elapsedNanos());
			assertEquals(0, retryTrace.retryCount());
			assertEquals(2, retryTrace.totalAttemptCount(2));
			measurement.recordOutcome(firstOutcome.failure(), 1);
			measurement.recordOutcome(secondOutcome.failure(), 1);
			List<Exception> failures = java.util.stream.Stream.of(firstOutcome.failure(), secondOutcome.failure())
				.filter(Objects::nonNull)
				.toList();

			assertEquals(1, failures.size());
			Exception deadlock = failures.get(0);
			assertTrue(hasSqlState(deadlock, "40P01"));
			assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
				globalExceptionHandler.handleUnhandledException(deadlock).getStatusCode());
			if (firstOutcome.failure() != null) {
				assertDeadlockVictimRolledBack(firstRoom.getId(), firstUserId, firstRoomVersionBefore);
				assertDeadlockSurvivorSucceeded(secondRoom.getId(), secondUserId);
			} else {
				assertDeadlockVictimRolledBack(secondRoom.getId(), secondUserId, secondRoomVersionBefore);
				assertDeadlockSurvivorSucceeded(firstRoom.getId(), firstUserId);
			}
			assertRoomInvariant(firstRoom.getId());
			assertRoomInvariant(secondRoom.getId());
		} catch (Exception exception) {
			throw new AssertionError("실제 PostgreSQL deadlock 경로를 관찰하지 못했습니다.", exception);
		} finally {
			deadlockGate.deactivate();
			retryTrace.detach();
			executor.shutdownNow();
			awaitParticipationCompletion(first);
			awaitParticipationCompletion(second);
			awaitExecutorCompletion(executor);
		}
	}

	private TimedOutcome captureFailure(Callable<?> command) {
		long startedNanos = System.nanoTime();
		try {
			command.call();
			return new TimedOutcome(System.nanoTime() - startedNanos, null);
		} catch (Exception exception) {
			return new TimedOutcome(System.nanoTime() - startedNanos, exception);
		}
	}

	private record TimedOutcome(long elapsedNanos, Exception failure) {
	}

	private void assertDueRoomOrderScenario() {
		long firstHostUserId = insertUser("t2-due-first-host");
		long secondHostUserId = insertUser("t2-due-second-host");
		Room firstRoom = createRoom(firstHostUserId, 1);
		Room secondRoom = createRoom(secondHostUserId, 1);
		jdbcTemplate.execute("select pg_stat_statements_reset()");

		List<Long> dueRoomIds = roomRepository.findDueRooms(FIXED_TIME.plusSeconds(10_800), FIXED_TIME)
			.stream()
			.map(Room::getId)
			.toList();
		assertEquals(List.of(firstRoom.getId(), secondRoom.getId()), dueRoomIds);
		logRawArtifact("T2-due-room-order", new RawMetrics(
			0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(), readPostgresCost()));
	}

	private void assertLastSeatLockScenario() throws Exception {
		long hostUserId = insertUser("t2-lock-host");
		long firstUserId = insertUser("t2-lock-first");
		long secondUserId = insertUser("t2-lock-second");
		Room room = createRoom(hostUserId, 1);
		List<CommandResult> results = runConcurrently(
			"T2-lock",
			() -> roomParticipationService.participate(firstUserId, room.getId()),
			() -> roomParticipationService.participate(secondUserId, room.getId()));
		assertEquals(1, results.stream().filter(CommandResult::successful).count());
		assertEquals(ErrorCode.CAPACITY_EXCEEDED,
			results.stream().filter(result -> !result.successful()).findFirst().orElseThrow().errorCode());
		assertRoomInvariant(room.getId());
	}

	private void assertRollbackScenario() {
		long hostUserId = insertUser("t2-rollback-host");
		long userId = insertUser("t2-rollback-user");
		Room room = createRoom(hostUserId, 1);
		T3Measurement measurement = new T3Measurement();
		failureGate.activateTechnicalFailure();
		RuntimeException technical = measurement.measureRequest(() -> assertThrows(
			RuntimeException.class,
			() -> roomParticipationService.participate(userId, room.getId())));
		assertEquals(HttpStatus.INTERNAL_SERVER_ERROR,
			globalExceptionHandler.handleUnhandledException(technical).getStatusCode());
		assertEquals(0, activeParticipationCount(room.getId(), userId));
		assertRoomInvariant(room.getId());
		logRawArtifact("T2-rollback", new RawMetrics(
			1, 0, 0, 0, 1, 0, 1, 0, 0, 0, measurement.responseNanos(), measurement.requestCost()));
		failureGate.deactivate();
	}

	private void assertStartBoundaryConvergence(boolean correctionFirst) {
		long hostUserId = insertUser("t2-boundary-host-" + correctionFirst);
		long userId = insertUser("t2-boundary-user-" + correctionFirst);
		Room room = createRoom(hostUserId, 2);
		jdbcTemplate.update("update rooms set start_at = ? where id = ?", Timestamp.from(FIXED_TIME), room.getId());
		List<CommandResult> results = runCommitOrdered("T2-start-boundary-direct-" + correctionFirst,
			room.getId(), correctionFirst,
			() -> {
				roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME);
				return null;
			},
			() -> roomParticipationService.participate(userId, room.getId()));
		assertEquals(1, results.stream().filter(CommandResult::successful).count());
		assertEquals(ErrorCode.ROOM_NOT_RECRUITING,
			results.stream().filter(result -> !result.successful()).findFirst().orElseThrow().errorCode());
		assertEquals(RoomStatus.CLOSED, roomRepository.findById(room.getId()).orElseThrow().getStatus());
		assertEquals(0, activeParticipationCount(room.getId(), userId));
		assertRoomInvariant(room.getId());
	}

	private void assertStartBoundaryWaitlistRegistrationConvergence(boolean correctionFirst) {
		long hostUserId = insertUser("t2-boundary-registration-host-" + correctionFirst);
		long activeUserId = insertUser("t2-boundary-registration-active-" + correctionFirst);
		long registeringUserId = insertUser("t2-boundary-registration-new-" + correctionFirst);
		long existingWaitingUserId = insertUser("t2-boundary-registration-existing-" + correctionFirst);
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(activeUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(room.getId(), existingWaitingUserId, 10L, FIXED_TIME));
		jdbcTemplate.update("update rooms set start_at = ? where id = ?", Timestamp.from(FIXED_TIME), room.getId());

		List<CommandResult> results = runCommitOrdered("T2-start-boundary-waitlist-registration-" + correctionFirst,
			room.getId(), correctionFirst,
			() -> {
				roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME);
				return null;
			},
			() -> roomWaitlistCommandService.register(registeringUserId, room.getId()));

		assertEquals(ErrorCode.WAITLIST_NOT_AVAILABLE,
			results.stream().filter(result -> !result.successful()).findFirst().orElseThrow().errorCode());
		assertClosedRoomWithActiveParticipantCount(room.getId(), 1);
		assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), existingWaitingUserId));
		assertTrue(roomWaitlistRepository.findById(new RoomWaitlistId(room.getId(), registeringUserId)).isEmpty());
		assertRoomInvariant(room.getId());
	}

	private void assertStartBoundaryWaitlistReactivationConvergence(boolean correctionFirst) {
		long hostUserId = insertUser("t2-boundary-reactivation-host-" + correctionFirst);
		long activeUserId = insertUser("t2-boundary-reactivation-active-" + correctionFirst);
		long reactivatingUserId = insertUser("t2-boundary-reactivation-user-" + correctionFirst);
		long existingWaitingUserId = insertUser("t2-boundary-reactivation-existing-" + correctionFirst);
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(activeUserId, room.getId());
		roomWaitlistCommandService.register(reactivatingUserId, room.getId());
		roomWaitlistCommandService.cancel(reactivatingUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(room.getId(), existingWaitingUserId, 10L, FIXED_TIME));
		jdbcTemplate.update("update rooms set start_at = ? where id = ?", Timestamp.from(FIXED_TIME), room.getId());

		List<CommandResult> results = runCommitOrdered(
			"T2-start-boundary-waitlist-reactivation-" + correctionFirst,
			room.getId(), correctionFirst,
			() -> {
				roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME);
				return null;
			},
			() -> roomWaitlistCommandService.register(reactivatingUserId, room.getId()));

		assertEquals(ErrorCode.WAITLIST_NOT_AVAILABLE,
			results.stream().filter(result -> !result.successful()).findFirst().orElseThrow().errorCode());
		assertClosedRoomWithActiveParticipantCount(room.getId(), 1);
		assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), existingWaitingUserId));
		assertEquals(RoomWaitlistStatus.CANCELED, waitlistStatus(room.getId(), reactivatingUserId));
		assertRoomInvariant(room.getId());
	}

	private void assertStartBoundaryWaitlistCancellationConvergence(boolean correctionFirst) {
		long hostUserId = insertUser("t2-boundary-cancel-host-" + correctionFirst);
		long activeUserId = insertUser("t2-boundary-cancel-active-" + correctionFirst);
		long firstWaitingUserId = insertUser("t2-boundary-cancel-first-" + correctionFirst);
		long secondWaitingUserId = insertUser("t2-boundary-cancel-second-" + correctionFirst);
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(activeUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(room.getId(), firstWaitingUserId, 10L, FIXED_TIME));
		roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(room.getId(), secondWaitingUserId, 20L, FIXED_TIME));
		jdbcTemplate.update("update rooms set start_at = ? where id = ?", Timestamp.from(FIXED_TIME), room.getId());

		List<CommandResult> results = runCommitOrdered("T2-start-boundary-waitlist-cancellation-" + correctionFirst,
			room.getId(), correctionFirst,
			() -> {
				roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME);
				return null;
			},
			() -> {
				roomWaitlistCommandService.cancel(firstWaitingUserId, room.getId());
				return null;
			});

		if (correctionFirst) {
			assertEquals(ErrorCode.WAITLIST_ENTRY_NOT_FOUND,
				results.stream().filter(result -> !result.successful()).findFirst().orElseThrow().errorCode());
			assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), firstWaitingUserId));
		} else {
			assertEquals(2, results.stream().filter(CommandResult::successful).count(), results::toString);
			assertEquals(RoomWaitlistStatus.CANCELED, waitlistStatus(room.getId(), firstWaitingUserId));
		}
		assertClosedRoomWithActiveParticipantCount(room.getId(), 1);
		assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), secondWaitingUserId));
		assertRoomInvariant(room.getId());
	}

	private void assertStartBoundaryParticipationCancellationConvergence(boolean correctionFirst) {
		long hostUserId = insertUser("t2-boundary-participation-cancel-host-" + correctionFirst);
		long activeUserId = insertUser("t2-boundary-participation-cancel-active-" + correctionFirst);
		long firstWaitingUserId = insertUser("t2-boundary-participation-cancel-first-" + correctionFirst);
		long secondWaitingUserId = insertUser("t2-boundary-participation-cancel-second-" + correctionFirst);
		Room room = createRoom(hostUserId, 1);
		roomParticipationService.participate(activeUserId, room.getId());
		roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(room.getId(), firstWaitingUserId, 10L, FIXED_TIME));
		roomWaitlistRepository.saveAndFlush(
			RoomWaitlist.create(room.getId(), secondWaitingUserId, 20L, FIXED_TIME));
		jdbcTemplate.update("update rooms set start_at = ? where id = ?", Timestamp.from(FIXED_TIME), room.getId());

		List<CommandResult> results = runCommitOrdered(
			"T2-start-boundary-participation-cancellation-" + correctionFirst,
			room.getId(), correctionFirst,
			() -> {
				roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME);
				return null;
			},
			() -> roomParticipationCancelService.cancelParticipation(activeUserId, room.getId()),
			correctionFirst
				? WriteExpectation.FIRST_COMMAND_ONLY_WITH_OUTCOME
				: WriteExpectation.OUTCOME_ONLY);

		assertClosedRoomWithActiveParticipantCount(room.getId(), 1);
		if (correctionFirst) {
			assertEquals(1, results.stream().filter(CommandResult::successful).count());
			assertEquals(ErrorCode.INVALID_ROOM_STATUS_TRANSITION,
				results.stream().filter(result -> !result.successful()).findFirst().orElseThrow().errorCode());
			assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), activeUserId));
			assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), firstWaitingUserId));
			assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), secondWaitingUserId));
		} else {
			assertEquals(1, results.stream().filter(CommandResult::successful).count(), results::toString);
			assertEquals(ErrorCode.INVALID_ROOM_STATUS_TRANSITION,
				results.stream().filter(result -> !result.successful()).findFirst().orElseThrow().errorCode());
			assertEquals(ParticipationStatus.ACTIVE, participationStatus(room.getId(), activeUserId));
			assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), firstWaitingUserId));
			assertEquals(RoomWaitlistStatus.EXPIRED, waitlistStatus(room.getId(), secondWaitingUserId));
		}
		assertRoomInvariant(room.getId());
	}

	/**
	 * 좌석이 남은 ROOM에서 대기 등록은 같은 snapshot의 업무 사전조건으로 write 앞에서 거절된다.
	 * 이 결과는 commit order와 무관하므로 순서 비교가 아니라 precondition 경계로 분리해 검증한다.
	 */
	private void assertWaitlistRegistrationPreconditionBoundary() {
		for (boolean directFirst : List.of(true, false)) {
			long hostUserId = insertUser("t2-precondition-host-" + directFirst);
			long directUserId = insertUser("t2-precondition-direct-" + directFirst);
			long waitlistUserId = insertUser("t2-precondition-waitlist-" + directFirst);
			Room room = createRoom(hostUserId, 1);
			List<CommandResult> results = runCommitOrdered(
				"T2-waitlist-registration-precondition-boundary-" + directFirst,
				room.getId(), directFirst,
				() -> roomParticipationService.participate(directUserId, room.getId()),
				() -> roomWaitlistCommandService.register(waitlistUserId, room.getId()),
				WriteExpectation.FIRST_COMMAND_ONLY);

			assertTrue(results.get(0).successful());
			assertEquals(ErrorCode.WAITLIST_NOT_AVAILABLE, results.get(1).errorCode());
			assertEquals(1, results.stream().filter(CommandResult::successful).count());
			assertTrue(roomWaitlistRepository.findById(new RoomWaitlistId(room.getId(), waitlistUserId)).isEmpty());
			assertRoomInvariant(room.getId());
		}
	}

	/**
	 * full ROOM의 신규 대기 등록 두 건은 양 순서 모두 write에 도달하고, FIFO 순서가 commit order를 따른다.
	 * 두 순서의 결과가 실제로 달라지므로 승인 T2의 확정 순서 비교가 된다.
	 */
	private void assertWaitlistRegistrationCommitOrders() {
		for (boolean earlierFirst : List.of(true, false)) {
			long hostUserId = insertUser("t2-registration-order-host-" + earlierFirst);
			long activeUserId = insertUser("t2-registration-order-active-" + earlierFirst);
			long earlierUserId = insertUser("t2-registration-order-earlier-" + earlierFirst);
			long laterUserId = insertUser("t2-registration-order-later-" + earlierFirst);
			Room room = createRoom(hostUserId, 1);
			roomParticipationService.participate(activeUserId, room.getId());

			List<CommandResult> results = runCommitOrdered(
				"T2-waitlist-registration-commit-order-" + earlierFirst,
				room.getId(), earlierFirst,
				() -> roomWaitlistCommandService.register(earlierUserId, room.getId()),
				() -> roomWaitlistCommandService.register(laterUserId, room.getId()),
				WriteExpectation.BOTH);

			assertWaitlistFifoFollowsCommitOrder(room.getId(), results, earlierFirst, earlierUserId, laterUserId);
		}
	}

	/**
	 * CANCELED 대기의 재활성 등록도 다른 신규 등록 entrypoint와 양 commit 순서로 경합시킨다.
	 * 순차 호출이 아니라 같은 ROOM version을 읽은 뒤 write 순서만 확정한다.
	 */
	private void assertWaitlistReactivationCommitOrders() {
		for (boolean reactivationFirst : List.of(true, false)) {
			long hostUserId = insertUser("t2-reactivation-host-" + reactivationFirst);
			long activeUserId = insertUser("t2-reactivation-active-" + reactivationFirst);
			long reactivatingUserId = insertUser("t2-reactivation-user-" + reactivationFirst);
			long newUserId = insertUser("t2-reactivation-new-" + reactivationFirst);
			Room room = createRoom(hostUserId, 1);
			roomParticipationService.participate(activeUserId, room.getId());
			assertTrue(roomWaitlistCommandService.register(reactivatingUserId, room.getId()).created());
			roomWaitlistCommandService.cancel(reactivatingUserId, room.getId());
			assertEquals(RoomWaitlistStatus.CANCELED, waitlistStatus(room.getId(), reactivatingUserId));

			List<CommandResult> results = runCommitOrdered(
				"T2-waitlist-reactivation-commit-order-" + reactivationFirst,
				room.getId(), reactivationFirst,
				() -> roomWaitlistCommandService.register(reactivatingUserId, room.getId()),
				() -> roomWaitlistCommandService.register(newUserId, room.getId()),
				WriteExpectation.BOTH);

			assertWaitlistFifoFollowsCommitOrder(
				room.getId(), results, reactivationFirst, reactivatingUserId, newUserId);
		}
	}

	private void assertWaitlistFifoFollowsCommitOrder(
		long roomId,
		List<CommandResult> results,
		boolean firstCommandFirst,
		long firstCommandUserId,
		long secondCommandUserId) {
		assertEquals(2, results.stream().filter(CommandResult::successful).count());
		assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(roomId, firstCommandUserId));
		assertEquals(RoomWaitlistStatus.WAITING, waitlistStatus(roomId, secondCommandUserId));
		long priorUserId = firstCommandFirst ? firstCommandUserId : secondCommandUserId;
		long laterUserId = firstCommandFirst ? secondCommandUserId : firstCommandUserId;
		assertTrue(waitlistQueueOrder(roomId, priorUserId) < waitlistQueueOrder(roomId, laterUserId),
			"먼저 commit한 대기 등록이 더 앞선 FIFO 순서를 가져야 합니다.");
		assertRoomInvariant(roomId);
	}

	private void assertWaitlistCancellationAndPromotionCommitOrders() {
		for (boolean cancellationFirst : List.of(true, false)) {
			long hostUserId = insertUser("t2-promotion-host-" + cancellationFirst);
			long activeUserId = insertUser("t2-promotion-active-" + cancellationFirst);
			long waitingUserId = insertUser("t2-promotion-waiting-" + cancellationFirst);
			Room room = createRoom(hostUserId, 1);
			roomParticipationService.participate(activeUserId, room.getId());
			roomWaitlistCommandService.register(waitingUserId, room.getId());
			List<CommandResult> results = runCommitOrdered(
				"T2-waitlist-cancellation-participation-promotion-" + cancellationFirst,
				room.getId(), cancellationFirst,
				() -> {
					roomWaitlistCommandService.cancel(waitingUserId, room.getId());
					return null;
				},
				() -> roomParticipationCancelService.cancelParticipation(activeUserId, room.getId()));
			if (cancellationFirst) {
				assertEquals(2, results.stream().filter(CommandResult::successful).count());
				assertEquals(RoomWaitlistStatus.CANCELED, waitlistStatus(room.getId(), waitingUserId));
				assertEquals(0, activeParticipationCount(room.getId(), waitingUserId));
			} else {
				assertEquals(1, results.stream().filter(CommandResult::successful).count());
				assertEquals(ErrorCode.WAITLIST_ENTRY_NOT_FOUND,
					results.stream().filter(result -> !result.successful()).findFirst().orElseThrow().errorCode());
				assertEquals(RoomWaitlistStatus.PROMOTED, waitlistStatus(room.getId(), waitingUserId));
				assertEquals(1, activeParticipationCount(room.getId(), waitingUserId));
			}
			assertRoomInvariant(room.getId());
		}
	}

	private void assertParticipationCancellationCommitOrders() {
		for (boolean firstCancellationFirst : List.of(true, false)) {
			long hostUserId = insertUser("t2-cancel-order-host-" + firstCancellationFirst);
			long firstUserId = insertUser("t2-cancel-order-first-" + firstCancellationFirst);
			long secondUserId = insertUser("t2-cancel-order-second-" + firstCancellationFirst);
			Room room = createRoom(hostUserId, 2);
			roomParticipationService.participate(firstUserId, room.getId());
			roomParticipationService.participate(secondUserId, room.getId());

			List<CommandResult> results = runCommitOrdered(
				"T2-participation-cancellation-cancellation-" + firstCancellationFirst,
				room.getId(),
				firstCancellationFirst,
				() -> roomParticipationCancelService.cancelParticipation(firstUserId, room.getId()),
				() -> roomParticipationCancelService.cancelParticipation(secondUserId, room.getId()),
				WriteExpectation.BOTH);

			assertEquals(2, results.stream().filter(CommandResult::successful).count());
			assertEquals(ParticipationStatus.CANCELED, participationStatus(room.getId(), firstUserId));
			assertEquals(ParticipationStatus.CANCELED, participationStatus(room.getId(), secondUserId));
			assertRoomInvariant(room.getId());
			assertEquals(0, jdbcTemplate.queryForObject(
				"select active_participant_count from rooms where id = ?", Integer.class, room.getId()));
		}
	}

	/** commit-order 경합에서 각 command가 write 지점에 도달해야 하는지에 대한 기대다. */
	private enum WriteExpectation {
		/** write 도달 여부를 검증하지 않는다. */
		UNCHECKED,
		/** 두 command가 모두 write 지점에 도달해야 한다. */
		BOTH,
		/** 첫번째 command만 write에 도달하고 두번째는 업무 사전조건으로 그 앞에서 끝나야 한다. */
		FIRST_COMMAND_ONLY,
		/** 첫번째 command만 write에 도달하고 두 command의 transaction outcome을 관찰한다. */
		FIRST_COMMAND_ONLY_WITH_OUTCOME,
		/** write 도달 여부와 무관하게 두 command의 transaction outcome을 관찰한다. */
		OUTCOME_ONLY
	}

	private List<CommandResult> runCommitOrdered(
		String scenario,
		long roomId,
		boolean firstCommandFirst,
		Callable<?> firstCommand,
		Callable<?> secondCommand) {
		return runCommitOrdered(
			scenario, roomId, firstCommandFirst, firstCommand, secondCommand, WriteExpectation.UNCHECKED);
	}

	private List<CommandResult> runCommitOrdered(
		String scenario,
		long roomId,
		boolean firstCommandFirst,
		Callable<?> firstCommand,
		Callable<?> secondCommand,
		WriteExpectation writeExpectation) {
		commitOrderGate.activate(roomId, firstCommandFirst);
		roomReadGate.activate(roomId, 2);
		try {
			RoomConcurrencyBaselineSupport.RoundMeasurement measurement = baselineSupport.measureRound(
				scenario,
				2,
				roomReadGate,
				List.of(
					() -> commitOrderGate.execute(roomId, true, firstCommand),
					() -> commitOrderGate.execute(roomId, false, secondCommand)));
			roomReadGate.assertInitialReadsShareOneVersion();
			commitOrderGate.assertBothCommandsCompleted();
			switch (writeExpectation) {
				case BOTH -> {
					commitOrderGate.assertBothCommandsReachedWrite(scenario);
					commitOrderGate.assertBothCommandsReachedTransactionOutcome();
				}
				case FIRST_COMMAND_ONLY -> commitOrderGate.assertOnlyFirstCommandReachedWrite();
				case FIRST_COMMAND_ONLY_WITH_OUTCOME -> {
					commitOrderGate.assertOnlyFirstCommandReachedWrite();
					commitOrderGate.assertBothCommandsReachedTransactionOutcome();
				}
				case OUTCOME_ONLY -> commitOrderGate.assertBothCommandsReachedTransactionOutcome();
				case UNCHECKED -> {}
			}
			logMeasuredRaw(scenario, measurement);
			return toCommandResults(measurement);
		} catch (Exception exception) {
			throw new AssertionError("결정적 commit-order 경합 실행에 실패했습니다.", exception);
		} finally {
			roomReadGate.deactivate();
			commitOrderGate.deactivate();
		}
	}

	private void assertArchivedArtifact() {
		if (capturingRaw()) {
			return;
		}
		assertTrue(Files.isRegularFile(RAW_ARTIFACT));
		try {
			String artifactText = Files.readString(RAW_ARTIFACT, StandardCharsets.UTF_8);
			JsonNode artifact = objectMapper.readTree(artifactText);
			assertArtifactMetadata(artifact);
			assertEquals(RoomLockComparisonMeasurementContract.scenarioSetDigest(),
				artifact.path("scenarioSetDigest").asText());
			assertEquals(RoomLockComparisonMeasurementContract.T2_ROUND_REPETITIONS,
				artifact.path("t2RoundRepetitions").asInt());
			List<String> actualScenarios = new ArrayList<>();
			for (String scenarioGroup : List.of("T1", "T2", "T3")) {
				JsonNode rounds = artifact.path("scenarios").path(scenarioGroup);
				assertTrue(rounds.isArray());
				for (JsonNode round : rounds) {
					actualScenarios.add(round.path("scenario").asText());
					assertRawRecordMatchesStructuredFields(round);
				}
			}
			RoomLockComparisonMeasurementContract.assertScenarioSet(actualScenarios);
			assertEquals(artifact.path("artifactSha256").asText(), sha256(RAW_ARTIFACT));
			assertEquals(EXPECTED_ARTIFACT_SHA256, artifact.path("artifactSha256").asText());
		} catch (Exception exception) {
			throw new AssertionError("ROOM785 원자료 checksum을 확인하지 못했습니다.", exception);
		}
	}

	private void assertArtifactMetadata(JsonNode artifact) {
		if (capturingRaw()) {
			return;
		}
		assertEquals(CANDIDATE, artifact.path("candidate").asText());
		assertEquals(RoomLockComparisonMeasurementContract.BASE_SHA, artifact.path("lockBaseSha").asText());
		assertTrue(artifact.path("artifactSha256").asText().matches("[0-9A-F]{64}"));
		assertTrue(artifact.path("candidateImplementationSourceSha").asText().matches("[0-9a-f]{40}"));
		assertTrue(artifact.path("measurementExecutionGitHead").asText().matches("[0-9a-f]{40}"));
		List<String> metricFields = new ArrayList<>();
		artifact.path("metricFields").forEach(field -> metricFields.add(field.asText()));
		assertEquals(RoomLockComparisonMeasurementContract.METRIC_FIELDS, metricFields);
	}

	private void assertArtifactMetadata() {
		if (capturingRaw()) {
			return;
		}
		try {
			assertArtifactMetadata(objectMapper.readTree(Files.readString(RAW_ARTIFACT, StandardCharsets.UTF_8)));
		} catch (Exception exception) {
			throw new AssertionError("ROOM785 artifact metadata를 확인하지 못했습니다.", exception);
		}
	}

	private void assertWorkflowAssertions(String testId, List<String> expectedAssertions) {
		if (capturingRaw()) {
			return;
		}
		try {
			JsonNode artifact = objectMapper.readTree(Files.readString(RAW_ARTIFACT, StandardCharsets.UTF_8));
			List<String> actualAssertions = new ArrayList<>();
			artifact.path("workflowAssertions").path(testId)
				.forEach(assertion -> actualAssertions.add(assertion.asText()));
			assertEquals(expectedAssertions, actualAssertions);
		} catch (Exception exception) {
			throw new AssertionError("ROOM785 workflow assertion을 확인하지 못했습니다.", exception);
		}
	}

	private void assertWorkflowAssertionHasRawScenarios(
		String testId, String workflowAssertion, List<String> expectedScenarios) {
		if (capturingRaw()) {
			return;
		}
		try {
			JsonNode artifact = objectMapper.readTree(Files.readString(RAW_ARTIFACT, StandardCharsets.UTF_8));
			List<String> workflowAssertions = new ArrayList<>();
			artifact.path("workflowAssertions").path(testId)
				.forEach(assertion -> workflowAssertions.add(assertion.asText()));
			assertTrue(workflowAssertions.contains(workflowAssertion));

			List<String> actualScenarios = new ArrayList<>();
			artifact.path("scenarios").path(testId)
				.forEach(round -> actualScenarios.add(round.path("scenario").asText()));
			assertTrue(actualScenarios.containsAll(expectedScenarios));
		} catch (Exception exception) {
			throw new AssertionError("ROOM785 workflow assertion의 raw scenario를 확인하지 못했습니다.", exception);
		}
	}

	private void assertRawRecordMatchesStructuredFields(JsonNode round) {
		String rawRecord = round.path("rawRecord").asText();
		String paddedRawRecord = " " + rawRecord + " ";
		for (String field : METRIC_FIELDS) {
			String value = round.path(field).isArray()
				? round.path(field).toString().replace(" ", "")
				: round.path(field).asText();
			assertTrue(paddedRawRecord.contains(" " + field + "=" + value + " "),
				() -> "artifact structured/raw field가 다릅니다: " + field + " in " + rawRecord);
		}
	}

	private String sha256(Path path) throws Exception {
		try {
			String canonicalArtifact = Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
			canonicalArtifact = canonicalArtifact.replaceAll(
				"(?m)^\\s*\"artifactSha256\"\\s*:\\s*\"[^\"]*\",\\n", "");
			return java.util.HexFormat.of().withUpperCase().formatHex(
				MessageDigest.getInstance("SHA-256").digest(canonicalArtifact.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
		}
	}

	private boolean capturingRaw() {
		return "true".equals(System.getenv("ROOM785_CAPTURE_RAW"));
	}

	private List<CommandResult> runConcurrently(
		String scenario,
		Callable<?> first,
		Callable<?> second) throws Exception {
		RoomConcurrencyBaselineSupport.RoundMeasurement measurement = baselineSupport.measureRound(
			scenario,
			RoomLockComparisonMeasurementContract.CONCURRENCY_LEVEL,
			roomReadGate,
			List.of(first, second));
		logMeasuredRaw(scenario, measurement);
		return toCommandResults(measurement);
	}

	private List<CommandResult> toCommandResults(RoomConcurrencyBaselineSupport.RoundMeasurement measurement) {
		return measurement.requests().stream()
			.map(request -> new CommandResult(request.successful(), request.errorCode()))
			.toList();
	}

	private void logMeasuredRaw(String scenario, RoomConcurrencyBaselineSupport.RoundMeasurement measurement) {
		RawMetrics metrics = new RawMetrics(
			measurement.totalRequestCount(),
			measurement.successCount(),
			measurement.businessFailureCount(),
			measurement.concurrencyFailureCount(),
			measurement.technicalFailureCount(),
			measurement.requests().stream()
				.mapToLong(RoomConcurrencyBaselineSupport.RequestMeasurement::conflictCount)
				.sum(),
			measurement.retryCount(0),
			measurement.retryCount(1),
			measurement.retryCount(2),
			measurement.exhaustedCount(),
			measurement.requestDurationsNanos(),
			measurement.postgresCost());
		latestMeasuredRawMetrics = metrics;
		String comparisonScenario = comparisonScenario(scenario);
		if (comparisonScenario != null) {
			logRawArtifact(comparisonScenario, metrics);
		}
	}

	private String comparisonScenario(String scenario) {
		return switch (scenario) {
			case "T1", "T2-lock" -> scenario;
			case "T2-waitlist-cancellation-participation-promotion-true" -> "T2-waitlist-new-promotion";
			case "T2-waitlist-cancellation-participation-promotion-false" -> "T2-waitlist-new-cancel-first-promotion";
			case "T2-waitlist-reactivation-commit-order-true" -> "T2-waitlist-reactivation-promotion";
			case "T2-waitlist-reactivation-commit-order-false" -> "T2-waitlist-reactivation-cancel-first-promotion";
			case "T2-start-boundary-direct-true" -> "T2-start-direct-participation-first";
			case "T2-start-boundary-direct-false" -> "T2-start-correction-first";
			case "T2-start-boundary-waitlist-registration-true" -> "T2-start-waitlist-new-registration-first";
			case "T2-start-boundary-waitlist-registration-false" -> "T2-start-waitlist-new-correction-first";
			case "T2-start-boundary-waitlist-reactivation-true" -> "T2-start-waitlist-reactivation-registration-first";
			case "T2-start-boundary-waitlist-reactivation-false" -> "T2-start-waitlist-reactivation-correction-first";
			case "T2-start-boundary-waitlist-cancellation-true" -> "T2-start-waitlist-cancel-first";
			case "T2-start-boundary-waitlist-cancellation-false" -> "T2-start-waitlist-correction-first";
			case "T2-start-boundary-participation-cancellation-true" -> "T2-start-participation-cancel-first";
			case "T2-start-boundary-participation-cancellation-false" -> "T2-start-participation-correction-first";
			default -> null;
		};
	}

	private void logT3Raw(String scenario, T3Measurement measurement) {
		logRawArtifact(scenario, measurement.actualRawMetrics());
	}

	/**
	 * T3의 요청 구간만 응답시간과 PostgreSQL 비용으로 남긴다. fixture 준비, lock/deadlock 준비와 검증 SQL은
	 * 요청 구간 밖에서 실행되므로 후보 비교 metric에 섞이지 않는다.
	 */
	private final class T3Measurement {

		private final List<Long> responseNanos = new ArrayList<>();
		private final List<T3RequestObservation> requestObservations = new ArrayList<>();
		private long statementCalls;
		private double totalExecutionMillis;
		private long rows;
		private long sharedBlockHits;
		private long sharedBlockReads;

		private <T> T measureRequest(Supplier<T> request) {
			beginRequestWindow();
			long startedNanos = System.nanoTime();
			try {
				return request.get();
			} finally {
				endRequestWindow(System.nanoTime() - startedNanos);
			}
		}

		private void beginRequestWindow() {
			jdbcTemplate.execute("select pg_stat_statements_reset()");
		}

		private void endRequestWindow(long... requestNanos) {
			for (long elapsedNanos : requestNanos) {
				responseNanos.add(elapsedNanos);
			}
			RoomConcurrencyBaselineSupport.PostgresCost cost = readPostgresCost();
			statementCalls += cost.statementCalls();
			totalExecutionMillis += cost.totalExecutionMillis();
			rows += cost.rows();
			sharedBlockHits += cost.sharedBlockHits();
			sharedBlockReads += cost.sharedBlockReads();
		}

		private List<Long> responseNanos() {
			return List.copyOf(responseNanos);
		}

		private void recordFailure(Throwable failure, int attemptCount, int conflictCount, boolean exhausted) {
			requestObservations.add(T3RequestObservation.failure(failure, attemptCount, conflictCount, exhausted));
		}

		private void recordOutcome(Exception failure, int attemptCount) {
			requestObservations.add(T3RequestObservation.outcome(failure, attemptCount));
		}

		private RawMetrics actualRawMetrics() {
			assertEquals(responseNanos.size(), requestObservations.size(),
				"T3 요청별 responseNanos와 실행 outcome 수가 다릅니다.");
			long success = requestObservations.stream().filter(T3RequestObservation::successful).count();
			long businessFailure = requestObservations.stream().filter(T3RequestObservation::businessFailure).count();
			long concurrencyFailure = requestObservations.stream()
				.filter(T3RequestObservation::concurrencyFailure)
				.count();
			long technicalFailure = requestObservations.stream().filter(T3RequestObservation::technicalFailure).count();
			assertEquals(requestObservations.size(), success + businessFailure + concurrencyFailure + technicalFailure,
				"T3 실제 outcome 분류 합계가 requestCount와 다릅니다.");
			return new RawMetrics(
				requestObservations.size(),
				success,
				businessFailure,
				concurrencyFailure,
				technicalFailure,
				requestObservations.stream().mapToLong(T3RequestObservation::conflictCount).sum(),
				requestObservations.stream().filter(observation -> observation.retryCount() == 0).count(),
				requestObservations.stream().filter(observation -> observation.retryCount() == 1).count(),
				requestObservations.stream().filter(observation -> observation.retryCount() == 2).count(),
				requestObservations.stream().filter(T3RequestObservation::exhausted).count(),
				responseNanos(),
				requestCost());
		}

		private RoomConcurrencyBaselineSupport.PostgresCost requestCost() {
			return new RoomConcurrencyBaselineSupport.PostgresCost(
				statementCalls, totalExecutionMillis, rows, sharedBlockHits, sharedBlockReads);
		}
	}

	private void logRawArtifact(String scenario, RawMetrics metrics) {
		String rawArtifact = "ROOM785_RAW"
			+ " candidate=" + CANDIDATE
			+ " scenario=" + scenario
			+ " requestCount=" + metrics.requestCount()
			+ " success=" + metrics.success()
			+ " businessFailure=" + metrics.businessFailure()
			+ " concurrencyFailure=" + metrics.concurrencyFailure()
			+ " technicalFailure=" + metrics.technicalFailure()
			+ " conflictCount=" + metrics.conflictCount()
			+ " retry0=" + metrics.retry0()
			+ " retry1=" + metrics.retry1()
			+ " retry2=" + metrics.retry2()
			+ " exhausted=" + metrics.exhausted()
			+ " responseNanos=" + metrics.responseNanos().toString().replace(" ", "")
			+ " calls=" + metrics.postgresCost().statementCalls()
			+ " totalExecMs=" + metrics.postgresCost().totalExecutionMillis()
			+ " rows=" + metrics.postgresCost().rows()
			+ " sharedBlksHit=" + metrics.postgresCost().sharedBlockHits()
			+ " sharedBlksRead=" + metrics.postgresCost().sharedBlockReads();
		log.info(rawArtifact);
		assertEquals(METRIC_FIELDS, rawFieldNames(rawArtifact));
	}

	private RawMetrics combineRawMetrics(List<RawMetrics> metrics) {
		List<Long> responseNanos = metrics.stream()
			.flatMap(metric -> metric.responseNanos().stream())
			.toList();
		return new RawMetrics(
			metrics.stream().mapToLong(RawMetrics::requestCount).sum(),
			metrics.stream().mapToLong(RawMetrics::success).sum(),
			metrics.stream().mapToLong(RawMetrics::businessFailure).sum(),
			metrics.stream().mapToLong(RawMetrics::concurrencyFailure).sum(),
			metrics.stream().mapToLong(RawMetrics::technicalFailure).sum(),
			metrics.stream().mapToLong(RawMetrics::conflictCount).sum(),
			metrics.stream().mapToLong(RawMetrics::retry0).sum(),
			metrics.stream().mapToLong(RawMetrics::retry1).sum(),
			metrics.stream().mapToLong(RawMetrics::retry2).sum(),
			metrics.stream().mapToLong(RawMetrics::exhausted).sum(),
			responseNanos,
			new RoomConcurrencyBaselineSupport.PostgresCost(
				metrics.stream().mapToLong(metric -> metric.postgresCost().statementCalls()).sum(),
				metrics.stream().mapToDouble(metric -> metric.postgresCost().totalExecutionMillis()).sum(),
				metrics.stream().mapToLong(metric -> metric.postgresCost().rows()).sum(),
				metrics.stream().mapToLong(metric -> metric.postgresCost().sharedBlockHits()).sum(),
				metrics.stream().mapToLong(metric -> metric.postgresCost().sharedBlockReads()).sum()));
	}

	private List<String> rawFieldNames(String rawArtifact) {
		return Arrays.stream(rawArtifact.substring(rawArtifact.indexOf(' ') + 1).split(" "))
			.map(field -> field.substring(0, field.indexOf('=')))
			.toList();
	}

	private RoomConcurrencyBaselineSupport.PostgresCost readPostgresCost() {
		return jdbcTemplate.queryForObject(
			"select coalesce(sum(calls), 0), coalesce(sum(total_exec_time), 0), coalesce(sum(rows), 0), "
				+ "coalesce(sum(shared_blks_hit), 0), coalesce(sum(shared_blks_read), 0) "
				+ "from pg_stat_statements where dbid = "
				+ "(select oid from pg_database where datname = current_database()) "
				+ "and query not like '%pg_stat_statements%'",
			(rs, rowNumber) -> new RoomConcurrencyBaselineSupport.PostgresCost(
				rs.getLong(1), rs.getDouble(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
	}

	private record RawMetrics(
		long requestCount,
		long success,
		long businessFailure,
		long concurrencyFailure,
		long technicalFailure,
		long conflictCount,
		long retry0,
		long retry1,
		long retry2,
		long exhausted,
		List<Long> responseNanos,
		RoomConcurrencyBaselineSupport.PostgresCost postgresCost) {
	}

	private record T3RequestObservation(
		boolean successful,
		boolean businessFailure,
		boolean concurrencyFailure,
		boolean technicalFailure,
		int attemptCount,
		int conflictCount,
		boolean exhausted) {

		private static T3RequestObservation failure(
			Throwable failure, int attemptCount, int conflictCount, boolean exhausted) {
			if (failure instanceof BusinessException businessException
				&& businessException.getErrorCode() == ErrorCode.ROOM_CONCURRENT_MODIFICATION) {
				return new T3RequestObservation(false, false, true, false, attemptCount, conflictCount, exhausted);
			}
			if (failure instanceof BusinessException) {
				return new T3RequestObservation(false, true, false, false, attemptCount, conflictCount, exhausted);
			}
			return new T3RequestObservation(false, false, false, true, attemptCount, conflictCount, exhausted);
		}

		private static T3RequestObservation outcome(Exception failure, int attemptCount) {
			if (failure == null) {
				return new T3RequestObservation(true, false, false, false, attemptCount, 0, false);
			}
			return failure(failure, attemptCount, 0, false);
		}

		private int retryCount() {
			return attemptCount - 1;
		}
	}

	private static final class RetryTraceCapture {

		private static final Pattern RETRY_LOG_PATTERN = Pattern.compile(
			"^event=room_participation_retry(?: roomId=\\d+)? attempt=\\d+ useCase=ROOM_PARTICIPATION "
				+ "reasonCode=OPTIMISTIC_LOCK_CONFLICT$");

		private final ch.qos.logback.classic.Logger logger;
		private final Level previousLevel;
		private final ListAppender<ILoggingEvent> appender;

		private RetryTraceCapture(
			ch.qos.logback.classic.Logger logger,
			Level previousLevel,
			ListAppender<ILoggingEvent> appender) {
			this.logger = logger;
			this.previousLevel = previousLevel;
			this.appender = appender;
		}

		private static RetryTraceCapture attach() {
			ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)LoggerFactory
				.getLogger(RoomOptimisticLockRetrier.class);
			Level previousLevel = logger.getLevel();
			logger.setLevel(Level.DEBUG);
			ListAppender<ILoggingEvent> appender = new ListAppender<>();
			appender.start();
			logger.addAppender(appender);
			return new RetryTraceCapture(logger, previousLevel, appender);
		}

		private long retryCount() {
			return appender.list.stream()
				.map(ILoggingEvent::getFormattedMessage)
				.filter(message -> RETRY_LOG_PATTERN.matcher(message).matches())
				.count();
		}

		private long totalAttemptCount(int initialAttemptCount) {
			return initialAttemptCount + retryCount();
		}

		private void detach() {
			logger.detachAppender(appender);
			logger.setLevel(previousLevel);
			appender.stop();
		}
	}

	private void assertRoomInvariant(long roomId) {
		int activeParticipantCount = jdbcTemplate.queryForObject(
			"select active_participant_count from rooms where id = ?", Integer.class, roomId);
		int capacity = jdbcTemplate.queryForObject("select capacity from rooms where id = ?", Integer.class, roomId);
		int activeParticipationCount = jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and status = 'ACTIVE'", Integer.class, roomId);
		int duplicateActiveParticipationCount = jdbcTemplate.queryForObject("""
			select count(*) from (
				select user_id from participations where room_id = ? and status = 'ACTIVE'
				group by user_id having count(*) > 1
			) duplicated
			""", Integer.class, roomId);
		int activeAndWaitingOverlap = jdbcTemplate.queryForObject(
			"""
				select count(*) from participations participation
				join room_waitlists waitlist on waitlist.room_id = participation.room_id and waitlist.user_id = participation.user_id
				where participation.room_id = ? and participation.status = 'ACTIVE' and waitlist.status = 'WAITING'
				""",
			Integer.class, roomId);
		int duplicateWaitingOrderCount = jdbcTemplate.queryForObject("""
			select count(*) from (
				select queue_order from room_waitlists where room_id = ? and status = 'WAITING'
				group by queue_order having count(*) > 1
			) duplicated
			""", Integer.class, roomId);
		assertEquals(activeParticipantCount, activeParticipationCount);
		assertTrue(activeParticipantCount >= 0 && activeParticipantCount <= capacity);
		assertEquals(0, duplicateActiveParticipationCount);
		assertEquals(0, activeAndWaitingOverlap);
		assertEquals(0, duplicateWaitingOrderCount);
	}

	private int activeParticipationCount(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ? and status = 'ACTIVE'",
			Integer.class, roomId, userId);
	}

	private int participationCount(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select count(*) from participations where room_id = ? and user_id = ?",
			Integer.class, roomId, userId);
	}

	private void assertClosedRoomWithActiveParticipantCount(long roomId, int expectedActiveParticipantCount) {
		Room room = roomRepository.findById(roomId).orElseThrow();
		assertEquals(RoomStatus.CLOSED, room.getStatus());
		assertEquals(expectedActiveParticipantCount, room.getActiveParticipantCount());
	}

	private void assertDeadlockVictimRolledBack(long roomId, long userId, long roomVersionBefore) {
		assertEquals(0, participationCount(roomId, userId));
		assertTrue(roomWaitlistRepository.findById(new RoomWaitlistId(roomId, userId)).isEmpty());
		assertEquals(roomVersionBefore, roomVersion(roomId));
	}

	private void assertDeadlockSurvivorSucceeded(long roomId, long userId) {
		assertEquals(1, activeParticipationCount(roomId, userId));
		assertEquals(1, participationCount(roomId, userId));
		assertTrue(roomWaitlistRepository.findById(new RoomWaitlistId(roomId, userId)).isEmpty());
	}

	private long roomVersion(long roomId) {
		return jdbcTemplate.queryForObject("select version from rooms where id = ?", Long.class, roomId);
	}

	private boolean hasSqlState(Throwable exception, String expectedSqlState) {
		Throwable current = exception;
		while (current != null) {
			if (current instanceof SQLException sqlException && expectedSqlState.equals(sqlException.getSQLState())) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private ParticipationStatus participationStatus(long roomId, long userId) {
		return participationRepository.findByRoomIdAndUserId(roomId, userId).orElseThrow().getStatus();
	}

	private RoomWaitlistStatus waitlistStatus(long roomId, long userId) {
		return roomWaitlistRepository.findById(new RoomWaitlistId(roomId, userId)).orElseThrow().getStatus();
	}

	private long waitlistQueueOrder(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select queue_order from room_waitlists where room_id = ? and user_id = ?", Long.class, roomId, userId);
	}

	private Room createRoom(long hostUserId, int capacity) {
		return roomRepository.saveAndFlush(Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"ROOM-LOCK-01 fixture",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			FIXED_TIME.plusSeconds(3600),
			"홍대 테스트 장소",
			capacity));
	}

	private long insertUser(String suffix) {
		String repeatedSuffix = t2Repetition == 0 ? suffix : suffix + "-r" + t2Repetition;
		String email = FIXTURE_SEED + "-" + repeatedSuffix + "@example.com";
		jdbcTemplate.update("""
			insert into users (email, password_hash, nickname, created_at, updated_at)
			values (?, 'fixture-password-hash', ?, ?, ?)
			""", email, repeatedSuffix, Timestamp.from(FIXED_TIME), Timestamp.from(FIXED_TIME));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private record CommandResult(boolean successful, ErrorCode errorCode) {

		private static CommandResult success() {
			return new CommandResult(true, null);
		}

		private static CommandResult businessFailure(ErrorCode errorCode) {
			return new CommandResult(false, errorCode);
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class MeasurementTestConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
		}

		@Bean
		RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate() {
			return new RoomConcurrencyBaselineSupport.RoomReadGate();
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

		@Bean
		FailureGate failureGate() {
			return new FailureGate();
		}

		@Bean
		CommitOrderGate commitOrderGate() {
			return new CommitOrderGate();
		}

		@Bean
		RoomRowLockHolder roomRowLockHolder(
			PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate) {
			return new RoomRowLockHolder(transactionManager, jdbcTemplate);
		}

		@Bean
		DeadlockGate deadlockGate(JdbcTemplate jdbcTemplate) {
			return new DeadlockGate(jdbcTemplate);
		}

		@Bean(name = "gatedRoomRepository")
		@Primary
		RoomRepository gatedRoomRepository(
			@Qualifier("roomRepository") RoomRepository delegate,
			RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate,
			FailureGate failureGate,
			CommitOrderGate commitOrderGate,
			RoomRowLockHolder roomRowLockHolder,
			DeadlockGate deadlockGate) {
			InvocationHandler handler = new GateAwareRoomRepositoryInvocationHandler(
				delegate, roomReadGate, failureGate, commitOrderGate, roomRowLockHolder, deadlockGate);
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(), new Class<?>[] {RoomRepository.class}, handler);
		}

		@Bean(name = "gatedRoomWaitlistRepository")
		@Primary
		RoomWaitlistRepository gatedRoomWaitlistRepository(
			@Qualifier("roomWaitlistRepository") RoomWaitlistRepository delegate,
			CommitOrderGate commitOrderGate) {
			InvocationHandler handler = new GateAwareRoomWaitlistRepositoryInvocationHandler(delegate, commitOrderGate);
			return (RoomWaitlistRepository)Proxy.newProxyInstance(
				RoomWaitlistRepository.class.getClassLoader(), new Class<?>[] {RoomWaitlistRepository.class}, handler);
		}
	}

	static final class RoomRowLockHolder {

		private final TransactionTemplate requiresNewTransaction;
		private final JdbcTemplate jdbcTemplate;
		private final AtomicBoolean lockTimeoutActive = new AtomicBoolean();
		private final AtomicInteger lockTimeoutAttemptCount = new AtomicInteger();

		RoomRowLockHolder(PlatformTransactionManager transactionManager, JdbcTemplate jdbcTemplate) {
			requiresNewTransaction = new TransactionTemplate(transactionManager);
			requiresNewTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
			this.jdbcTemplate = jdbcTemplate;
		}

		void hold(long roomId, CountDownLatch rowLocked, CountDownLatch releaseLock) {
			try {
				requiresNewTransaction.executeWithoutResult(status -> {
					jdbcTemplate.queryForObject("select id from rooms where id = ? for update", Long.class, roomId);
					rowLocked.countDown();
					lockTimeoutActive.set(true);
					awaitRelease(releaseLock);
				});
			} finally {
				lockTimeoutActive.set(false);
			}
		}

		void setCurrentTransactionLockTimeoutIfActive() {
			if (lockTimeoutActive.get()) {
				lockTimeoutAttemptCount.incrementAndGet();
				jdbcTemplate.execute("set local lock_timeout = '100ms'");
			}
		}

		int lockTimeoutAttemptCount() {
			return lockTimeoutAttemptCount.get();
		}

		void resetAttemptCount() {
			lockTimeoutAttemptCount.set(0);
		}

		boolean awaitLockTimeoutActivation() {
			long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
			while (!lockTimeoutActive.get() && System.nanoTime() < deadline) {
				Thread.onSpinWait();
			}
			return lockTimeoutActive.get();
		}

		private void awaitRelease(CountDownLatch releaseLock) {
			try {
				if (!releaseLock.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
					throw new AssertionError("row lock 해제 신호가 시간 안에 도착하지 않았습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("row lock holder가 인터럽트되었습니다.", exception);
			}
		}
	}

	static final class DeadlockGate {

		private final JdbcTemplate jdbcTemplate;
		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();

		DeadlockGate(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		void activate(long firstRoomId, long secondRoomId) {
			if (!activeScenario.compareAndSet(null, new Scenario(firstRoomId, secondRoomId))) {
				throw new IllegalStateException("deadlock gate가 이미 활성화되어 있습니다.");
			}
		}

		void afterRoomRead(long roomId) {
			Scenario scenario = activeScenario.get();
			if (scenario == null) {
				return;
			}
			if (!scenario.markRead(roomId)) {
				return;
			}
			jdbcTemplate.queryForObject(
				"select id from rooms where id = ? for update", Long.class, roomId);
			scenario.primaryRowsHeld.countDown();
			awaitBothPrimaryRows(scenario.primaryRowsHeld);
			long secondaryRoomId = scenario.secondaryRoomId(roomId);
			jdbcTemplate.queryForObject(
				"select id from rooms where id = ? for update", Long.class, secondaryRoomId);
		}

		void deactivate() {
			Scenario scenario = activeScenario.getAndSet(null);
			if (scenario != null) {
				scenario.primaryRowsHeld.countDown();
			}
		}

		private void awaitBothPrimaryRows(CountDownLatch primaryRowsHeld) {
			try {
				if (!primaryRowsHeld.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
					throw new AssertionError("deadlock fixture가 두 primary ROOM 행을 모두 잠그지 못했습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("deadlock fixture 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long firstRoomId;
			private final long secondRoomId;
			private final CountDownLatch primaryRowsHeld = new CountDownLatch(2);
			private final AtomicBoolean firstRead = new AtomicBoolean();
			private final AtomicBoolean secondRead = new AtomicBoolean();

			private Scenario(long firstRoomId, long secondRoomId) {
				this.firstRoomId = firstRoomId;
				this.secondRoomId = secondRoomId;
			}

			private boolean markRead(long roomId) {
				if (roomId == firstRoomId) {
					return firstRead.compareAndSet(false, true);
				}
				if (roomId == secondRoomId) {
					return secondRead.compareAndSet(false, true);
				}
				return false;
			}

			private long secondaryRoomId(long roomId) {
				if (roomId == firstRoomId) {
					return secondRoomId;
				}
				if (roomId == secondRoomId) {
					return firstRoomId;
				}
				throw new AssertionError("deadlock gate에 등록되지 않은 ROOM입니다: " + roomId);
			}
		}
	}

	static final class FailureGate {

		private final AtomicInteger remainingOptimisticFailures = new AtomicInteger();
		private final AtomicInteger remainingTechnicalFailures = new AtomicInteger();
		private final AtomicInteger failureCount = new AtomicInteger();

		void activateOptimisticFailures(int count) {
			remainingOptimisticFailures.set(count);
			remainingTechnicalFailures.set(0);
			failureCount.set(0);
		}

		void activateTechnicalFailure() {
			remainingOptimisticFailures.set(0);
			remainingTechnicalFailures.set(1);
			failureCount.set(0);
		}

		void failAfterRoomFlushIfConfigured() {
			if (remainingOptimisticFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
				failureCount.incrementAndGet();
				throw new OptimisticLockException("controlled optimistic conflict after PostgreSQL flush");
			}
			if (remainingTechnicalFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
				failureCount.incrementAndGet();
				throw new CannotAcquireLockException("controlled PostgreSQL lock failure after flush");
			}
		}

		int failureCount() {
			return failureCount.get();
		}

		void deactivate() {
			remainingOptimisticFailures.set(0);
			remainingTechnicalFailures.set(0);
		}
	}

	private static final class GateAwareRoomRepositoryInvocationHandler implements InvocationHandler {

		private final RoomRepository delegate;
		private final RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate;
		private final FailureGate failureGate;
		private final CommitOrderGate commitOrderGate;
		private final RoomRowLockHolder roomRowLockHolder;
		private final DeadlockGate deadlockGate;

		private GateAwareRoomRepositoryInvocationHandler(
			RoomRepository delegate,
			RoomConcurrencyBaselineSupport.RoomReadGate roomReadGate,
			FailureGate failureGate,
			CommitOrderGate commitOrderGate,
			RoomRowLockHolder roomRowLockHolder,
			DeadlockGate deadlockGate) {
			this.delegate = delegate;
			this.roomReadGate = roomReadGate;
			this.failureGate = failureGate;
			this.commitOrderGate = commitOrderGate;
			this.roomRowLockHolder = roomRowLockHolder;
			this.deadlockGate = deadlockGate;
		}

		@Override
		public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
			try {
				commitOrderGate.beforeRoomWrite(method.getName());
				if (method.getName().equals("flush")) {
					roomRowLockHolder.setCurrentTransactionLockTimeoutIfActive();
				}
				Object result = method.invoke(delegate, arguments);
				if (method.getName().equals("findById")
					&& arguments != null
					&& arguments.length == 1
					&& arguments[0] instanceof Long roomId
					&& result instanceof Optional<?> optional) {
					roomReadGate.afterFindById(roomId, optional.map(Room.class::cast));
					commitOrderGate.afterRoomRead();
					deadlockGate.afterRoomRead(roomId);
				}
				if (method.getName().equals("flush")) {
					failureGate.failAfterRoomFlushIfConfigured();
				}
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	private static final class GateAwareRoomWaitlistRepositoryInvocationHandler implements InvocationHandler {

		private final RoomWaitlistRepository delegate;
		private final CommitOrderGate commitOrderGate;

		private GateAwareRoomWaitlistRepositoryInvocationHandler(
			RoomWaitlistRepository delegate, CommitOrderGate commitOrderGate) {
			this.delegate = delegate;
			this.commitOrderGate = commitOrderGate;
		}

		@Override
		public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
			try {
				commitOrderGate.beforeWaitlistWrite(method.getName());
				return method.invoke(delegate, arguments);
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	static final class CommitOrderGate {

		private final AtomicReference<Scenario> activeScenario = new AtomicReference<>();
		private final ThreadLocal<CommandContext> commandContext = new ThreadLocal<>();
		private final ThreadLocal<Boolean> transactionCompletionRegistered = new ThreadLocal<>();

		void activate(long roomId, boolean firstCommandFirst) {
			if (!activeScenario.compareAndSet(null, new Scenario(roomId, firstCommandFirst))) {
				throw new IllegalStateException("commit-order gate가 이미 활성화되어 있습니다.");
			}
		}

		Object execute(long roomId, boolean first, Callable<?> command) throws Exception {
			commandContext.set(new CommandContext(roomId, first));
			try {
				return command.call();
			} finally {
				commandCompleted(first);
				commandContext.remove();
				transactionCompletionRegistered.remove();
			}
		}

		void beforeRoomWrite(String methodName) {
			if (methodName.equals("flush") || methodName.equals("claimVersion") || methodName.equals("saveAndFlush")) {
				beforeWrite();
			}
		}

		void beforeWaitlistWrite(String methodName) {
			if (methodName.equals("cancelWaiting") || methodName.equals("reactivateWaiting")
				|| methodName.equals("promoteWaiting") || methodName.equals("expireAllWaiting")
				|| methodName.equals("cancelAllWaiting") || methodName.equals("saveAndFlush")) {
				beforeWrite();
			}
		}

		void afterRoomRead() {
			Scenario scenario = activeScenario.get();
			CommandContext current = commandContext.get();
			if (scenario == null || current == null) {
				return;
			}
			registerTransactionCompletion(scenario, current);
			if (current.first == scenario.firstCommandFirst) {
				return;
			}
			awaitFirstCompletion(scenario);
		}

		private void beforeWrite() {
			Scenario scenario = activeScenario.get();
			CommandContext current = commandContext.get();
			if (scenario == null || current == null || current.roomId != scenario.roomId) {
				return;
			}
			if (current.first) {
				scenario.firstCommandWriteCount.incrementAndGet();
			} else {
				scenario.secondCommandWriteCount.incrementAndGet();
			}
			registerTransactionCompletion(scenario, current);
			if (current.first == scenario.firstCommandFirst) {
				scenario.firstWrite.compareAndSet(false, true);
				return;
			}
			awaitFirstCompletion(scenario);
		}

		private void registerTransactionCompletion(Scenario scenario, CommandContext current) {
			if (Boolean.TRUE.equals(transactionCompletionRegistered.get())
				|| !TransactionSynchronizationManager.isSynchronizationActive()) {
				return;
			}
			transactionCompletionRegistered.set(true);
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCompletion(int status) {
					scenario.recordTransactionCompletion(current.first, status);
					transactionCompletionRegistered.remove();
					if (current.first == scenario.firstCommandFirst) {
						scenario.firstCompleted.countDown();
					}
				}
			});
		}

		void assertBothCommandsReachedWrite(String scenarioName) {
			Scenario scenario = activeScenario.get();
			if (scenario == null
				|| scenario.firstCommandWriteCount.get() == 0
				|| scenario.secondCommandWriteCount.get() == 0) {
				throw new AssertionError("두 command가 모두 transaction write 지점에 도달하지 않았습니다. scenario="
					+ scenarioName
					+ " firstWrite=" + (scenario == null ? 0 : scenario.firstCommandWriteCount.get())
					+ " secondWrite=" + (scenario == null ? 0 : scenario.secondCommandWriteCount.get()));
			}
		}

		void assertBothCommandsReachedTransactionOutcome() {
			Scenario scenario = activeScenario.get();
			if (scenario == null) {
				throw new AssertionError("commit-order gate가 활성화되어 있지 않습니다.");
			}
			awaitTransactionOutcome(scenario.firstTransactionCompleted, "첫번째");
			awaitTransactionOutcome(scenario.secondTransactionCompleted, "두번째");
			if (scenario.firstTransactionCompletionCount.get() == 0
				|| scenario.secondTransactionCompletionCount.get() == 0) {
				throw new AssertionError("두 command의 실제 PostgreSQL transaction commit 또는 rollback을 관찰하지 못했습니다.");
			}
		}

		private void awaitTransactionOutcome(CountDownLatch completion, String commandName) {
			try {
				if (!completion.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
					throw new AssertionError(commandName + " command의 transaction commit 또는 rollback 대기 시간이 초과되었습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(commandName + " command의 transaction outcome 대기 중 인터럽트되었습니다.", exception);
			}
		}

		/** 업무 사전조건으로 거절되는 두번째 command가 write 지점 앞에서 끝났는지 확인한다. */
		void assertOnlyFirstCommandReachedWrite() {
			Scenario scenario = activeScenario.get();
			if (scenario == null || scenario.firstCommandWriteCount.get() == 0) {
				throw new AssertionError("첫번째 command가 transaction write 지점에 도달하지 않았습니다.");
			}
			if (scenario.secondCommandWriteCount.get() != 0) {
				throw new AssertionError("사전조건으로 거절되어야 할 command가 write 지점에 도달했습니다.");
			}
		}

		void assertBothCommandsCompleted() {
			Scenario scenario = activeScenario.get();
			if (scenario == null || scenario.completedCommandCount.get() != 2) {
				throw new AssertionError("두 command가 모두 read 이후 decision을 완료하지 않았습니다.");
			}
		}

		void deactivate() {
			Scenario scenario = activeScenario.getAndSet(null);
			if (scenario != null) {
				scenario.firstCompleted.countDown();
			}
		}

		private void commandCompleted(boolean first) {
			Scenario scenario = activeScenario.get();
			if (scenario == null) {
				return;
			}
			scenario.completedCommandCount.incrementAndGet();
			if (first == scenario.firstCommandFirst) {
				scenario.firstCompleted.countDown();
			}
		}

		private void awaitFirstCompletion(Scenario scenario) {
			try {
				if (!scenario.firstCompleted.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
					throw new AssertionError("우선 command transaction 완료 대기 시간이 초과되었습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError("우선 command transaction 완료 대기 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class Scenario {

			private final long roomId;
			private final boolean firstCommandFirst;
			private final CountDownLatch firstCompleted = new CountDownLatch(1);
			private final AtomicBoolean firstWrite = new AtomicBoolean();
			private final AtomicInteger firstCommandWriteCount = new AtomicInteger();
			private final AtomicInteger secondCommandWriteCount = new AtomicInteger();
			private final AtomicInteger completedCommandCount = new AtomicInteger();
			private final AtomicInteger firstTransactionCompletionCount = new AtomicInteger();
			private final AtomicInteger secondTransactionCompletionCount = new AtomicInteger();
			private final CountDownLatch firstTransactionCompleted = new CountDownLatch(1);
			private final CountDownLatch secondTransactionCompleted = new CountDownLatch(1);

			private Scenario(long roomId, boolean firstCommandFirst) {
				this.roomId = roomId;
				this.firstCommandFirst = firstCommandFirst;
			}

			private void recordTransactionCompletion(boolean first, int status) {
				if (first) {
					firstTransactionCompletionCount.incrementAndGet();
					firstTransactionCompleted.countDown();
				} else {
					secondTransactionCompletionCount.incrementAndGet();
					secondTransactionCompleted.countDown();
				}
				if (status != TransactionSynchronization.STATUS_COMMITTED
					&& status != TransactionSynchronization.STATUS_ROLLED_BACK) {
					throw new AssertionError("ROOM command transaction이 commit 또는 rollback으로 끝나지 않았습니다.");
				}
			}
		}

		private record CommandContext(long roomId, boolean first) {
		}
	}
}
