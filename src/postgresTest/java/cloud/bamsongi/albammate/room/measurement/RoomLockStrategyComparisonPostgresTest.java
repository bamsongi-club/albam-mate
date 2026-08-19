package cloud.bamsongi.albammate.room.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import cloud.bamsongi.albammate.global.exception.BusinessException;
import cloud.bamsongi.albammate.global.exception.ErrorCode;
import cloud.bamsongi.albammate.global.security.currentuser.CurrentUserPrincipal;
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
import jakarta.persistence.EntityManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** ROOM-LOCK-01 후보 C의 PostgreSQL lock gate와 공통 비교 계약을 검증한다. */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
@Import(RoomLockStrategyComparisonPostgresTest.RoomLockTestConfiguration.class)
@TestPropertySource(properties = {
	"spring.datasource.hikari.maximum-pool-size=10",
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false"})
class RoomLockStrategyComparisonPostgresTest {

	private static final Logger log = LoggerFactory.getLogger(RoomLockStrategyComparisonPostgresTest.class);
	private static final String CANDIDATE = "C";
	private static final String BASE_SHA = "49b960a1f7537574b39d67ff22df8890a3891ef6";
	private static final String MANIFEST_PATH = "docs/measurements/room-lock-strategy-comparison-contract.md";
	private static final String RECEIPT_ARTIFACT_PATH = "docs/measurements/results/room-lock-01-c-receipt.txt";
	private static final String RAW_ARTIFACT_PATH = "docs/measurements/results/room-lock-01-c-raw.log";
	private static final String CAPTURE_STATE_PATH = "build/room-lock-01-c-capture.state";
	private static final Set<String> IMMUTABLE_ARTIFACT_BUNDLE_PATHS = Set.of(
		MANIFEST_PATH,
		RECEIPT_ARTIFACT_PATH,
		RAW_ARTIFACT_PATH);
	private static final Set<String> POST_MEASUREMENT_TEST_CONTRACT_PATHS = Set.of(
		"src/postgresTest/java/cloud/bamsongi/albammate/room/measurement/RoomLockStrategyComparisonPostgresTest.java",
		"src/postgresTest/java/cloud/bamsongi/albammate/room/measurement/RoomConcurrencyBaselineSupport.java",
		"src/postgresTest/java/cloud/bamsongi/albammate/room/measurement/RoomWaitlistConcurrencyBaselinePostgresTest.java");
	private static final Set<String> PROVENANCE_BUNDLE_WITH_TEST_CONTRACT_PATHS = java.util.stream.Stream.concat(
		IMMUTABLE_ARTIFACT_BUNDLE_PATHS.stream(), POST_MEASUREMENT_TEST_CONTRACT_PATHS.stream())
		.collect(Collectors.toUnmodifiableSet());
	private static final Instant FIXED_TIME = RoomLockComparisonMeasurementContract.FIXED_TIME;
	private static final String FIXTURE_SEED = RoomLockComparisonMeasurementContract.FIXTURE_SEED;
	/** A/B/C가 동일하게 한 번씩 실행하는 T2 commit-order 표본 수다. */
	private static final int T2_ROUND_REPETITIONS = RoomLockComparisonMeasurementContract.T2_ROUND_REPETITIONS;
	private static final String EXPECTED_GATE = "RoomWriteGate signals after findByIdForWrite acquires the PostgreSQL row lock "
		+ "and before the first transaction continues; second request is signaled before delegate invocation"
		+ "; each T2 scenario runs both commit orders " + T2_ROUND_REPETITIONS + " times";
	private static final String EXPECTED_RETRY_BUDGET = "optimistic=3; waitlist queue-order conflict=3; technical=0";
	private static final String EXPECTED_RUNNER_PATH = "scripts/measurements/run-room-lock-comparison.ps1";
	private static final String EXPECTED_POSTGRES_TEST_COMMAND = ".\\gradlew.bat postgresTest --tests \"cloud.bamsongi.albammate.room.measurement.RoomLockStrategyComparisonPostgresTest.*\" --rerun --no-daemon --stacktrace";
	private static final List<String> REQUIRED_RAW_SCENARIOS = RoomLockComparisonMeasurementContract
		.requiredRawScenarios();
	private static final List<String> METRIC_FIELDS = RoomLockComparisonMeasurementContract.METRIC_FIELDS;
	private static final long WAIT_SECONDS = 10;

	@Container
	@ServiceConnection
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:18.4")
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
		.withDatabaseName("albam_mate_room_lock_01");

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private RoomRepository roomRepository;
	@Autowired
	private RoomParticipationService roomParticipationService;
	@Autowired
	private RoomParticipationCancelService roomParticipationCancelService;
	@Autowired
	private RoomWaitlistCommandService roomWaitlistCommandService;
	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private Environment environment;
	@Autowired
	private RoomStatusCorrectionCoordinator roomStatusCorrectionCoordinator;
	@Autowired
	private RoomWriteGate roomWriteGate;
	@Autowired
	@Qualifier("roomWaitlistRepository") private RoomWaitlistRepository roomWaitlistRepository;

	private ch.qos.logback.classic.Logger retrierLogger;
	private Level retrierLoggerLevel;
	private ch.qos.logback.classic.Logger waitlistRegistrationLogger;
	private Level waitlistRegistrationLoggerLevel;
	private ListAppender<ILoggingEvent> retryLogs;
	private ListAppender<ILoggingEvent> waitlistRegistrationLogs;
	private final ObjectMapper objectMapper = new ObjectMapper();
	private RuntimeProvenance runtimeProvenance;

	@BeforeEach
	void cleanFixture() {
		jdbcTemplate.execute("create extension if not exists pg_stat_statements");
		jdbcTemplate.execute("truncate table room_waitlists, participations, rooms, users restart identity cascade");
		retrierLogger = (ch.qos.logback.classic.Logger)LoggerFactory.getLogger(RoomOptimisticLockRetrier.class);
		retrierLoggerLevel = retrierLogger.getLevel();
		retrierLogger.setLevel(Level.DEBUG);
		retryLogs = new ListAppender<>();
		retryLogs.start();
		retrierLogger.addAppender(retryLogs);
		waitlistRegistrationLogger = (ch.qos.logback.classic.Logger)LoggerFactory
			.getLogger("cloud.bamsongi.albammate.room.service.command.RoomWaitlistRegistrationCoordinator");
		waitlistRegistrationLoggerLevel = waitlistRegistrationLogger.getLevel();
		waitlistRegistrationLogger.setLevel(Level.DEBUG);
		waitlistRegistrationLogs = new ListAppender<>();
		waitlistRegistrationLogs.start();
		waitlistRegistrationLogger.addAppender(waitlistRegistrationLogs);
	}

	@AfterEach
	void cleanConnectionState() {
		roomWriteGate.deactivate();
		retrierLogger.detachAppender(retryLogs);
		retryLogs.stop();
		retrierLogger.setLevel(retrierLoggerLevel);
		waitlistRegistrationLogger.detachAppender(waitlistRegistrationLogs);
		waitlistRegistrationLogs.stop();
		waitlistRegistrationLogger.setLevel(waitlistRegistrationLoggerLevel);
		assertEquals("0", jdbcTemplate.queryForObject("show lock_timeout", String.class));
	}

	@Test
	void T1_후보는_실제_HTTP_요청을_고정_fixture와_동일_metric_schema로_기록한다() throws Exception {
		RawMetrics metrics = runLastSeatParticipationRound("t1-last-seat");
		String rawArtifact = rawArtifact("T1", metrics);

		assertEquals("C", CANDIDATE);
		assertEquals(FIXTURE_SEED, "ROOM-LOCK-01-20260817");
		logRawArtifact("T1", metrics);
		assertEquals(METRIC_FIELDS, rawFieldNames(rawArtifact));
	}

	@Test
	void T1_raw_validator는_실제_요청이_없는_placeholder_record를_거부한다() throws Exception {
		String contract = Files.readString(Path.of(MANIFEST_PATH));
		RunManifest manifest = readManifest(contract);
		String placeholderRaw = manifest.rawArtifactContent().replaceFirst(
			"scenario=T1 [^\\n]+",
			"scenario=T1"
				+ " requestCount=0 success=0 businessFailure=0 concurrencyFailure=0 technicalFailure=0 "
				+ "conflictCount=0 retry0=0 retry1=0 retry2=0 exhausted=0 responseNanos=[] calls=0 "
				+ "totalExecMs=0.0 rows=0 sharedBlksHit=0 sharedBlksRead=0");

		assertFalse(rawArtifactMatchesContract(placeholderRaw));
	}

	@Test
	void T1_raw_validator는_결과와_PostgreSQL_비용_지표의_산식을_검증한다() throws Exception {
		RunManifest manifest = readManifest(Files.readString(Path.of(MANIFEST_PATH)));
		String t1Record = manifest.rawArtifactContent().lines()
			.filter(line -> line.contains(" scenario=T1 "))
			.findFirst()
			.orElseThrow();

		assertTrue(rawArtifactMatchesContract(manifest.rawArtifactContent()));
		assertFalse(rawArtifactMatchesContract(
			manifest.rawArtifactContent().replace(t1Record, t1Record.replace("success=1", "success=2"))));
		assertFalse(rawArtifactMatchesContract(
			manifest.rawArtifactContent().replace(t1Record, t1Record.replaceFirst("calls=\\d+", "calls=0"))));
	}

	@Test
	@DisabledIfEnvironmentVariable(named = "ROOM785_CAPTURE_RAW", matches = "true")
	void T1_기록된_provenance는_실행_runtime과_독립적으로_canonical_bundle을_검증한다() throws Exception {
		RunManifest manifest = readManifest(Files.readString(Path.of(MANIFEST_PATH)));

		ManifestValidationResult validation = validateRecordedManifest(
			manifest, Path.of("").toAbsolutePath().normalize());
		assertEquals("VALID", validation.status());
	}

	@Test
	@EnabledIfEnvironmentVariable(named = "ROOM785_ACTUAL_COMMAND", matches = ".+")
	@DisabledIfEnvironmentVariable(named = "ROOM785_CAPTURE_RAW", matches = "true")
	void T1_실행_manifest_계약은_실제_provenance와_artifact_digest를_검증한다(
		@TempDir
		Path tempDirectory)
		throws Exception {
		String contract = Files.readString(Path.of(
			"docs/measurements/room-lock-strategy-comparison-contract.md"));

		RunManifest manifest = readManifest(contract);
		Path repositoryRoot = Path.of("").toAbsolutePath().normalize();
		ManifestValidationResult recordedResult = validateManifest(
			manifest, repositoryRoot);
		assertEquals("VALID", recordedResult.status(), recordedResult.reason());

		Path artifact = tempDirectory.resolve("artifact.log");
		Path rawArtifact = tempDirectory.resolve("raw.log");
		Files.writeString(artifact, manifest.artifactContent(), StandardCharsets.UTF_8);
		Files.writeString(rawArtifact, manifest.rawArtifactContent(), StandardCharsets.UTF_8);
		RunManifest validFixture = manifest.withArtifacts(
			artifact,
			rawArtifact,
			sha256(artifact),
			sha256(rawArtifact));
		assertEquals(
			"VALID",
			validateSyntheticManifest(validFixture, repositoryRoot).status());
		assertEquals(
			"INVALID",
			validateManifest(manifest.withArtifactPaths(artifact, rawArtifact), repositoryRoot).status(),
			"실행 manifest는 canonical tracked artifact 경로만 허용해야 한다");

		String placeholderRaw = manifest.rawArtifactContent().replaceFirst(
			"scenario=T1 [^\\n]+",
			"scenario=T1"
				+ " requestCount=0 success=0 businessFailure=0 concurrencyFailure=0 technicalFailure=0 "
				+ "conflictCount=0 retry0=0 retry1=0 retry2=0 exhausted=0 responseNanos=[] calls=0 "
				+ "totalExecMs=0.0 rows=0 sharedBlksHit=0 sharedBlksRead=0");
		Files.writeString(rawArtifact, placeholderRaw, StandardCharsets.UTF_8);
		RunManifest placeholderFixture = manifest.withArtifacts(
			artifact,
			rawArtifact,
			sha256(artifact),
			sha256(rawArtifact));
		assertEquals(
			"INVALID",
			validateSyntheticManifest(placeholderFixture, repositoryRoot).status(),
			"T1은 실제 request-bearing HTTP record여야 한다");

		RunManifest invalidDigest = validFixture.withArtifactDigest("0".repeat(64));
		assertEquals(
			"INVALID",
			validateSyntheticManifest(invalidDigest, repositoryRoot).status());

		RunManifest invalidHead = validFixture.withHeadSha(BASE_SHA);
		assertEquals(
			"INVALID",
			validateSyntheticManifest(invalidHead, repositoryRoot).status());

		RunManifest missingArtifact = validFixture.withArtifactPaths(
			tempDirectory.resolve("missing-artifact.log"),
			tempDirectory.resolve("missing-raw.log"));
		assertEquals("INVALID", validateSyntheticManifest(missingArtifact, repositoryRoot).status());
	}

	@Test
	void T2_C_여러_due_ROOM은_startAt와_ID_순서로_읽힌다() {
		Room firstRoom = createRoom("ROOM-LOCK-01-first", FIXED_TIME.plusSeconds(3600));
		Room secondRoom = createRoom("ROOM-LOCK-01-second", FIXED_TIME.plusSeconds(3600));
		resetPostgresStatistics();

		List<Long> roomIds = roomRepository
			.findDueRooms(FIXED_TIME.plusSeconds(10_800), FIXED_TIME)
			.stream()
			.map(Room::getId)
			.toList();

		assertEquals(List.of(firstRoom.getId(), secondRoom.getId()), roomIds);
		logRawArtifact("T2-due-room-order", new RawMetrics(
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			0,
			List.of(),
			readPostgresCost()));
	}

	@Test
	void T2_C_마지막_좌석_직접_참가는_실제_HTTP_경로에서_첫_ROOM_lock_뒤_두번째_요청을_직렬화한다()
		throws Exception {
		logRawArtifact("T2-lock", runLastSeatParticipationRound("t2-last-seat"));
	}

	@Test
	void T2_C_직접_참가의_flush_이후_실패는_실제_HTTP_트랜잭션을_rollback한다() throws Exception {
		Room room = createRoom();
		long userId = insertUser("rollback-participant");
		StoredSnapshot before = snapshot(room.getId());
		resetPostgresStatistics();
		roomWriteGate.failAfterNextFlush(
			new DataAccessResourceFailureException("controlled T2 rollback"));
		try {
			TimedApiResult timedResult = measureApiRequest(() -> postParticipation(userId, room.getId()));

			assertApi(timedResult.result(), 500, ErrorCode.INTERNAL_SERVER_ERROR);
			assertEquals(before, snapshot(room.getId()));
			assertRoomSnapshotInvariant(before);
			roomWriteGate.assertFlushFaultCallCount(1);
			logRawArtifact("T2-rollback", new RawMetrics(
				1,
				0,
				0,
				0,
				1,
				0,
				1,
				0,
				0,
				0,
				List.of(timedResult.responseNanos()),
				readPostgresCost()));
		} finally {
			roomWriteGate.deactivate();
		}
	}

	@Test
	void T2_C_대기_신규_등록과_참가_취소는_실제_HTTP_경로에서_FIFO_자동_승격으로_수렴한다()
		throws Exception {
		for (int repetition = 1; repetition <= T2_ROUND_REPETITIONS; repetition++) {
			runWaitlistPromotionRound(true, false, repetition);
			runWaitlistPromotionRound(false, false, repetition);
		}
	}

	@Test
	void T2_C_대기_재활성_등록과_참가_취소는_기존_행을_자동_승격한다() throws Exception {
		for (int repetition = 1; repetition <= T2_ROUND_REPETITIONS; repetition++) {
			runWaitlistPromotionRound(true, true, repetition);
			runWaitlistPromotionRound(false, true, repetition);
		}
	}

	@Test
	void T2_C_시작_경계는_직접_참가_선행과_상태_보정_선행_양쪽에서_최신_snapshot으로_수렴한다()
		throws Exception {
		for (int repetition = 1; repetition <= T2_ROUND_REPETITIONS; repetition++) {
			for (boolean participationFirst : List.of(true, false)) {
				StoredSnapshot snapshot = runStartBoundaryRound(participationFirst, repetition);
				assertEquals(RoomStatus.CLOSED, snapshot.room().status());
				assertEquals(0, snapshot.room().activeParticipantCount());
				assertEquals(0, snapshot.participations().size());
				assertRoomSnapshotInvariant(snapshot);
			}
		}
	}

	@Test
	void T2_C_시작_경계는_대기_신규와_재활성_등록도_양쪽_commit_order에서_검증한다()
		throws Exception {
		for (int repetition = 1; repetition <= T2_ROUND_REPETITIONS; repetition++) {
			for (boolean registrationFirst : List.of(true, false)) {
				for (boolean reactivation : List.of(false, true)) {
					StoredSnapshot snapshot = runStartBoundaryWaitlistRound(registrationFirst, reactivation,
						repetition);
					assertEquals(RoomStatus.CLOSED, snapshot.room().status());
					assertEquals(1, snapshot.room().activeParticipantCount());
					assertEquals(0, snapshot.waitlists().stream()
						.filter(waitlist -> waitlist.status() == RoomWaitlistStatus.WAITING)
						.count());
					assertRoomSnapshotInvariant(snapshot);
				}
			}
		}
	}

	@Test
	void T2_C_시작_경계는_참가_취소_자동_승격과_대기_취소도_양쪽_commit_order에서_검증한다()
		throws Exception {
		for (int repetition = 1; repetition <= T2_ROUND_REPETITIONS; repetition++) {
			for (boolean cancellationFirst : List.of(true, false)) {
				StoredSnapshot snapshot = runStartBoundaryParticipationCancellationRound(cancellationFirst, repetition);
				assertEquals(RoomStatus.CLOSED, snapshot.room().status());
				assertEquals(1, snapshot.room().activeParticipantCount());
				assertEquals(RoomWaitlistStatus.EXPIRED, snapshot.waitlistStatusForOnlyEntry());
				assertRoomSnapshotInvariant(snapshot);
			}

			StoredSnapshot waitlistCancellationFirst = runStartBoundaryWaitlistCancellationRound(true, repetition);
			assertEquals(RoomStatus.CLOSED, waitlistCancellationFirst.room().status());
			assertEquals(RoomWaitlistStatus.CANCELED,
				waitlistCancellationFirst.waitlistStatusForOnlyEntry());
			assertRoomSnapshotInvariant(waitlistCancellationFirst);

			StoredSnapshot waitlistCorrectionFirst = runStartBoundaryWaitlistCancellationRound(false, repetition);
			assertEquals(RoomStatus.CLOSED, waitlistCorrectionFirst.room().status());
			assertEquals(RoomWaitlistStatus.EXPIRED,
				waitlistCorrectionFirst.waitlistStatusForOnlyEntry());
			assertRoomSnapshotInvariant(waitlistCorrectionFirst);
		}
	}

	@Test
	void T3_C의_lock_timeout은_현재_transaction만_실패시키고_부분_변경을_남기지_않는다() throws Exception {
		Room room = createRoom();
		long activeUserId = insertUser("timeout-active");
		long waitingUserId = insertUser("timeout-waiting");
		assertApi(postParticipation(activeUserId, room.getId()), 201, null);
		StoredSnapshot before = snapshot(room.getId());
		resetPostgresStatistics();
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch firstLockAcquired = new CountDownLatch(1);
		CountDownLatch releaseFirstRequest = new CountDownLatch(1);
		try {
			Future<?> first = executor.submit(() -> inTransaction(() -> {
				lockRoom(room.getId());
				firstLockAcquired.countDown();
				await(releaseFirstRequest, "첫 번째 lock release");
				return null;
			}));
			await(firstLockAcquired, "첫 번째 ROOM lock 획득");

			Future<ApiResult> timeout = executor.submit(() -> postWaitlist(waitingUserId, room.getId()));
			long timeoutStartedNanos = System.nanoTime();
			ApiResult timeoutResult = timeout.get(WAIT_SECONDS, TimeUnit.SECONDS);
			long timeoutResponseNanos = System.nanoTime() - timeoutStartedNanos;
			assertApi(timeoutResult, 500, ErrorCode.INTERNAL_SERVER_ERROR);
			releaseFirstRequest.countDown();
			first.get(WAIT_SECONDS, TimeUnit.SECONDS);
			assertEquals(before, snapshot(room.getId()));
			assertRetryLog("reasonCode=LOCK_TIMEOUT", "sqlState=55P03", "attempt=1");
			assertEquals(1, retryLogCount("reasonCode=LOCK_TIMEOUT"));
			assertEquals(0, retryLogCount("OPTIMISTIC_LOCK_CONFLICT"));
			PostgresCost postgresCost = readPostgresCost();
			RawMetrics metrics = new RawMetrics(
				1,
				0,
				0,
				0,
				1,
				0,
				1,
				0,
				0,
				0,
				List.of(timeoutResponseNanos),
				postgresCost);
			logRawArtifact("T3-lock-timeout", metrics);
		} finally {
			releaseFirstRequest.countDown();
			executor.shutdownNow();
		}
	}

	@Test
	void T3_낙관_충돌_세번은_실제_HTTP_경로에서_409와_snapshot_불변식을_검증한다() throws Exception {
		Room room = createRoom();
		long userId = insertUser("optimistic-user");
		StoredSnapshot before = snapshot(room.getId());
		resetPostgresStatistics();
		roomWriteGate.bumpVersionAfterWriteLock(room.getId(), 3);

		TimedApiResult timedResult = measureApiRequest(() -> postParticipation(userId, room.getId()));
		ApiResult result = timedResult.result();

		assertApi(result, 409, ErrorCode.ROOM_CONCURRENT_MODIFICATION);
		assertEquals(before, snapshot(room.getId()));
		roomWriteGate.assertOptimisticConflictProtocol(3);
		assertRetryLog("reasonCode=OPTIMISTIC_LOCK_CONFLICT", "attempt=2");
		assertRetryLog("reasonCode=OPTIMISTIC_LOCK_CONFLICT", "attempt=3");
		assertRetryLog("reasonCode=OPTIMISTIC_LOCK_EXHAUSTED", "attempt=3");
		assertEquals(2, retryLogCount("reasonCode=OPTIMISTIC_LOCK_CONFLICT"));
		assertEquals(1, retryLogCount("reasonCode=OPTIMISTIC_LOCK_EXHAUSTED"));
		logRawArtifact(
			"T3-optimistic-exhausted",
			new RawMetrics(
				1,
				0,
				0,
				1,
				0,
				3,
				0,
				0,
				1,
				1,
				List.of(timedResult.responseNanos()),
				readPostgresCost()));
	}

	@Test
	void T3_실제_두_HTTP_트랜잭션의_역순_row_lock은_deadlock으로_재시도_없이_분류된다() throws Exception {
		Room firstRoom = createRoom(FIXTURE_SEED + "-deadlock-first", FIXED_TIME.plusSeconds(3600));
		Room secondRoom = createRoom(FIXTURE_SEED + "-deadlock-second", FIXED_TIME.plusSeconds(3600));
		long firstUserId = insertUser("deadlock-first-user");
		long secondUserId = insertUser("deadlock-second-user");
		StoredSnapshot firstBefore = snapshot(firstRoom.getId());
		StoredSnapshot secondBefore = snapshot(secondRoom.getId());
		resetPostgresStatistics();
		roomWriteGate.forceDeadlock(firstRoom.getId(), secondRoom.getId());

		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<TimedApiResult> first = executor.submit(
				() -> measureApiRequest(() -> postParticipation(firstUserId, firstRoom.getId())));
			Future<TimedApiResult> second = executor.submit(
				() -> measureApiRequest(() -> postParticipation(secondUserId, secondRoom.getId())));
			TimedApiResult firstResult = first.get(WAIT_SECONDS, TimeUnit.SECONDS);
			TimedApiResult secondResult = second.get(WAIT_SECONDS, TimeUnit.SECONDS);

			List<TimedApiResult> results = List.of(firstResult, secondResult);
			assertEquals(1, results.stream().filter(result -> result.result().status() == 201).count());
			assertEquals(1, results.stream().filter(result -> result.result().status() == 500).count());
			results.stream()
				.filter(result -> result.result().status() == 201)
				.forEach(result -> assertApi(result.result(), 201, null));
			results.stream()
				.filter(result -> result.result().status() == 500)
				.forEach(result -> assertApi(result.result(), 500, ErrorCode.INTERNAL_SERVER_ERROR));
			roomWriteGate.assertDeadlockProtocol();
			assertRetryLog("reasonCode=DEADLOCK", "sqlState=40P01", "attempt=1");
			assertEquals(1, retryLogCount("reasonCode=DEADLOCK"));
			assertEquals(0, retryLogCount("reasonCode=LOCK_TIMEOUT"));
			assertEquals(0, retryLogCount("OPTIMISTIC_LOCK_CONFLICT"));
			assertDeadlockRoomResult(firstResult, firstRoom.getId(), firstBefore, firstUserId);
			assertDeadlockRoomResult(secondResult, secondRoom.getId(), secondBefore, secondUserId);
			logRawArtifact(
				"T3-deadlock",
				new RawMetrics(
					2,
					1,
					0,
					0,
					1,
					0,
					2,
					0,
					0,
					0,
					List.of(firstResult.responseNanos(), secondResult.responseNanos()),
					readPostgresCost()));
		} finally {
			roomWriteGate.deactivate();
			executor.shutdownNow();
		}
	}

	@Test
	void T3_예상_밖_저장소_오류는_실제_HTTP_경로에서_재시도_없이_500으로_분류된다() throws Exception {
		assertTechnicalFailure();
	}

	private static List<String> rawFieldNames(String rawArtifact) {
		return Arrays.stream(rawArtifact.substring(rawArtifact.indexOf(' ') + 1).split(" "))
			.map(field -> field.substring(0, field.indexOf('=')))
			.toList();
	}

	private static String rawArtifact(String scenario, RawMetrics metrics) {
		return "ROOM785_RAW candidate=" + CANDIDATE
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
			+ " calls=" + metrics.postgresCost().calls()
			+ " totalExecMs=" + metrics.postgresCost().totalExecMs()
			+ " rows=" + metrics.postgresCost().rows()
			+ " sharedBlksHit=" + metrics.postgresCost().sharedBlksHit()
			+ " sharedBlksRead=" + metrics.postgresCost().sharedBlksRead();
	}

	private void logRawArtifact(String scenario, RawMetrics metrics) {
		String canonicalScenario = canonicalRawScenario(scenario);
		if (canonicalScenario == null) {
			return;
		}
		String rawArtifact = rawArtifact(canonicalScenario, metrics);
		assertEquals(METRIC_FIELDS, rawFieldNames(rawArtifact));
		log.info(rawArtifact);
	}

	private String canonicalRawScenario(String scenario) {
		if ("T1".equals(scenario)) {
			return "T1";
		}
		if (RoomLockComparisonMeasurementContract.requiredRawScenarios().contains(scenario)) {
			return scenario;
		}
		return null;
	}

	private TimedApiResult measureApiRequest(ApiCall action) throws Exception {
		long startedNanos = System.nanoTime();
		ApiResult result = action.call();
		return new TimedApiResult(result, System.nanoTime() - startedNanos);
	}

	private long awaitCorrection(Future<?> correction) throws Exception {
		long startedNanos = System.nanoTime();
		correction.get(WAIT_SECONDS, TimeUnit.SECONDS);
		return System.nanoTime() - startedNanos;
	}

	private void resetPostgresStatistics() {
		jdbcTemplate.execute("select pg_stat_statements_reset()");
	}

	private PostgresCost readPostgresCost() {
		return jdbcTemplate.queryForObject(
			"select coalesce(sum(calls), 0), coalesce(sum(total_exec_time), 0), coalesce(sum(rows), 0), "
				+ "coalesce(sum(shared_blks_hit), 0), coalesce(sum(shared_blks_read), 0) "
				+ "from pg_stat_statements where dbid = "
				+ "(select oid from pg_database where datname = current_database()) "
				+ "and query not like '%pg_stat_statements%'",
			(rs, rowNumber) -> new PostgresCost(
				rs.getLong(1), rs.getDouble(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)));
	}

	@FunctionalInterface
	private interface ApiCall {

		ApiResult call() throws Exception;
	}

	private record TimedApiResult(ApiResult result, long responseNanos) {
	}

	private record PostgresCost(long calls, double totalExecMs, long rows, long sharedBlksHit, long sharedBlksRead) {
	}

	private record RawMetrics(
		int requestCount,
		int success,
		int businessFailure,
		int concurrencyFailure,
		int technicalFailure,
		int conflictCount,
		int retry0,
		int retry1,
		int retry2,
		int exhausted,
		List<Long> responseNanos,
		PostgresCost postgresCost) {

		private static RawMetrics empty() {
			return new RawMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, List.of(),
				new PostgresCost(0, 0, 0, 0, 0));
		}
	}

	private record CommandOutcome(boolean successful, ErrorCode errorCode, long responseNanos) {
	}

	private record RawRecord(
		String scenario,
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
		long calls,
		double totalExecMs,
		long rows,
		long sharedBlksHit,
		long sharedBlksRead) {
	}

	private Room createRoom() {
		return createRoom(FIXTURE_SEED + "-host", FIXED_TIME.plusSeconds(3600));
	}

	private Room createRoom(String userSuffix, Instant startAt) {
		return createRoom(userSuffix, startAt, 1);
	}

	private Room createRoom(String userSuffix, Instant startAt, int capacity) {
		long hostUserId = insertUser(userSuffix);
		return roomRepository.saveAndFlush(Room.create(
			hostUserId,
			RoomType.PERSON_FOCUSED,
			"ROOM-LOCK-01 fixture",
			null,
			null,
			ExperienceLevel.ALL_LEVELS,
			false,
			startAt,
			"홍대 테스트 장소",
			capacity));
	}

	private long insertUser(String suffix) {
		String email = suffix + "@example.com";
		jdbcTemplate.update(
			"insert into users (email, password_hash, nickname, created_at, updated_at) "
				+ "values (?, 'fixture-password-hash', ?, ?, ?)",
			email,
			suffix,
			Timestamp.from(FIXED_TIME),
			Timestamp.from(FIXED_TIME));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private RequestPostProcessor authenticationFor(long userId) {
		return authentication(new UsernamePasswordAuthenticationToken(
			new CurrentUserPrincipal(userId), null, AuthorityUtils.NO_AUTHORITIES));
	}

	private void lockRoom(long roomId) {
		jdbcTemplate.queryForObject("select id from rooms where id = ? for update", Long.class, roomId);
	}

	private <T> T inTransaction(Supplier<T> action) {
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
		return transaction.execute(status -> action.get());
	}

	private void await(CountDownLatch latch, String phase) {
		try {
			if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
				throw new AssertionError(phase + " 대기 시간이 초과되었습니다.");
			}
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new AssertionError(phase + " 중 인터럽트되었습니다.", exception);
		}
	}

	private ApiResult postParticipation(long userId, long roomId) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/rooms/" + roomId + "/participants")
			.with(authenticationFor(userId))
			.with(csrf()))
			.andReturn();
		return new ApiResult(result.getResponse().getStatus(), result.getResponse().getContentAsString());
	}

	private ApiResult postWaitlist(long userId, long roomId) throws Exception {
		MvcResult result = mockMvc.perform(post("/api/rooms/" + roomId + "/waitlist")
			.with(authenticationFor(userId))
			.with(csrf()))
			.andReturn();
		return new ApiResult(result.getResponse().getStatus(), result.getResponse().getContentAsString());
	}

	private ApiResult deleteParticipation(long userId, long roomId) throws Exception {
		MvcResult result = mockMvc.perform(delete("/api/rooms/" + roomId + "/participants/me")
			.with(authenticationFor(userId))
			.with(csrf()))
			.andReturn();
		return new ApiResult(result.getResponse().getStatus(), result.getResponse().getContentAsString());
	}

	private ApiResult deleteWaitlist(long userId, long roomId) throws Exception {
		MvcResult result = mockMvc.perform(delete("/api/rooms/" + roomId + "/waitlist/me")
			.with(authenticationFor(userId))
			.with(csrf()))
			.andReturn();
		return new ApiResult(result.getResponse().getStatus(), result.getResponse().getContentAsString());
	}

	private void assertApi(ApiResult result, int expectedStatus, ErrorCode expectedErrorCode) {
		assertEquals(expectedStatus, result.status());
		if (expectedErrorCode == null) {
			return;
		}
		assertTrue(result.body().contains("\"code\":\"" + expectedErrorCode.getCode() + "\""));
	}

	private RunManifest readManifest(String contract) throws Exception {
		int headingStart = contract.indexOf("## Final candidate C run manifest");
		int jsonStart = contract.indexOf("```json", headingStart) + "```json".length();
		int jsonEnd = contract.indexOf("```", jsonStart);
		JsonNode manifest = objectMapper.readTree(contract.substring(jsonStart, jsonEnd).trim());
		return new RunManifest(
			text(manifest, "resultStatus"),
			text(manifest, "candidate"),
			text(manifest, "candidateSourceSha"),
			text(manifest, "baseToHeadDiff", "baseSha"),
			text(manifest, "baseToHeadDiff", "headSha"),
			text(manifest, "baseToHeadDiff", "diffDigest"),
			text(manifest, "runArtifact", "artifactId"),
			text(manifest, "runArtifact", "artifactDigest"),
			text(manifest, "runArtifact", "content"),
			text(manifest, "metricSchema", "rawArtifactId"),
			text(manifest, "metricSchema", "rawArtifactDigest"),
			text(manifest, "metricSchema", "rawArtifactContent"),
			text(manifest, "environment", "java"),
			text(manifest, "environment", "postgres"),
			text(manifest, "environment", "postgresImage"),
			text(manifest, "environment", "os"),
			text(manifest, "environment", "cpu"),
			text(manifest, "environment", "configuration"),
			text(manifest, "fixture", "seed"),
			text(manifest, "fixture", "fixedTime"),
			text(manifest, "fixture", "concurrencyLevel"),
			text(manifest, "fixture", "gate"),
			text(manifest, "fixture", "retryBudget"),
			text(manifest, "commands", "runner"),
			text(manifest, "commands", "dockerVersion"),
			text(manifest, "commands", "exactPostgresTestCommand"),
			textArray(manifest, "metricSchema", "fieldNames"));
	}

	private String text(JsonNode node, String... path) {
		JsonNode current = node;
		for (String segment : path) {
			current = current.path(segment);
		}
		return current.asText("");
	}

	private List<String> textArray(JsonNode node, String... path) {
		JsonNode current = node;
		for (String segment : path) {
			current = current.path(segment);
		}
		if (!current.isArray()) {
			return List.of();
		}
		java.util.ArrayList<String> values = new java.util.ArrayList<>();
		for (JsonNode value : current) {
			values.add(value.asText(""));
		}
		return List.copyOf(values);
	}

	private ManifestValidationResult validateManifest(
		RunManifest manifest, Path repositoryRoot) throws Exception {
		return validateManifest(manifest, repositoryRoot, true, true);
	}

	private ManifestValidationResult validateRecordedManifest(
		RunManifest manifest, Path repositoryRoot) throws Exception {
		return validateManifest(manifest, repositoryRoot, false, true);
	}

	private ManifestValidationResult validateSyntheticManifest(
		RunManifest manifest, Path repositoryRoot) throws Exception {
		return validateManifest(manifest, repositoryRoot, false, false);
	}

	private ManifestValidationResult validateManifest(
		RunManifest manifest,
		Path repositoryRoot,
		boolean requireRuntimeMatch,
		boolean requireCanonicalArtifactPaths)
		throws Exception {
		if (Files.exists(repositoryRoot.resolve(CAPTURE_STATE_PATH))) {
			return invalid("capture in progress");
		}
		if (!"VALID".equals(manifest.resultStatus())) {
			return invalid("resultStatus");
		}
		if (!CANDIDATE.equals(manifest.candidate())) {
			return invalid("candidate");
		}
		if (!hasRequiredManifestValues(manifest)) {
			return invalid("required manifest field");
		}
		if (!isSha1(manifest.candidateSourceSha())
			|| !isSha1(manifest.baseSha())
			|| !isSha1(manifest.headSha())
			|| !isSha256(manifest.diffDigest())
			|| !isSha256(manifest.artifactDigest())
			|| !isSha256(manifest.rawArtifactDigest())) {
			return invalid("digest format");
		}
		if (!BASE_SHA.equals(manifest.baseSha())
			|| !manifest.candidateSourceSha().equals(manifest.headSha())) {
			return invalid("source and base-to-head SHA mismatch");
		}
		if (!gitCommitExists(repositoryRoot, manifest.candidateSourceSha())) {
			return invalid("candidate source commit");
		}
		if (!isCandidateCheckoutHead(repositoryRoot, manifest.candidateSourceSha())) {
			return invalid("candidate checkout HEAD");
		}
		String actualDiffDigest = gitDiffDigest(
			repositoryRoot, manifest.baseSha(), manifest.candidateSourceSha());
		if (!manifest.diffDigest().equalsIgnoreCase(actualDiffDigest)) {
			return invalid("base-to-head diff digest");
		}
		if (requireRuntimeMatch && !manifestMatchesRuntimeProvenance(manifest)) {
			return invalid("runtime provenance");
		}

		if (manifest.artifactContent().isBlank() || manifest.rawArtifactContent().isBlank()) {
			return invalid("artifact content");
		}
		if (requireCanonicalArtifactPaths
			&& (!RECEIPT_ARTIFACT_PATH.equals(manifest.artifactId())
				|| !RAW_ARTIFACT_PATH.equals(manifest.rawArtifactId()))) {
			return invalid("canonical artifact path");
		}
		Path artifact = resolveArtifact(repositoryRoot, manifest.artifactId());
		Path rawArtifact = resolveArtifact(repositoryRoot, manifest.rawArtifactId());
		if (!isRegularManifestArtifact(
			repositoryRoot, manifest.artifactId(), artifact, !requireCanonicalArtifactPaths)
			|| !isRegularManifestArtifact(
				repositoryRoot, manifest.rawArtifactId(), rawArtifact, !requireCanonicalArtifactPaths)) {
			return invalid("missing artifact");
		}
		if (!artifactReceiptMatchesManifest(manifest)
			|| !rawArtifactMatchesContract(manifest.rawArtifactContent())) {
			return invalid("artifact content");
		}
		if (!manifest.artifactDigest().equalsIgnoreCase(sha256(artifact))
			|| !manifest.rawArtifactDigest().equalsIgnoreCase(sha256(rawArtifact))
			|| !manifest.artifactDigest().equalsIgnoreCase(sha256(manifest.artifactContent()))
			|| !manifest.rawArtifactDigest().equalsIgnoreCase(sha256(manifest.rawArtifactContent()))) {
			return invalid("artifact digest");
		}
		return new ManifestValidationResult("VALID", "");
	}

	private boolean manifestMatchesRuntimeProvenance(RunManifest manifest) throws Exception {
		RuntimeProvenance actual = runtimeProvenance();
		return actual.javaVersion().equals(manifest.javaVersion())
			&& actual.postgresVersion().equals(manifest.postgresVersion())
			&& actual.postgresImage().equals(manifest.postgresImage())
			&& actual.os().equals(manifest.os())
			&& actual.cpu().equals(manifest.cpu())
			&& actual.configuration().equals(manifest.configuration())
			&& actual.dockerVersion().equals(manifest.dockerVersion())
			&& EXPECTED_RUNNER_PATH.equals(manifest.runnerCommand())
			&& EXPECTED_POSTGRES_TEST_COMMAND.equals(manifest.exactPostgresTestCommand())
			&& EXPECTED_POSTGRES_TEST_COMMAND.equals(System.getenv("ROOM785_ACTUAL_COMMAND"))
			&& EXPECTED_RUNNER_PATH.equals(System.getenv("ROOM785_RUNNER_PATH"))
			&& expectedExecutionId().equals(System.getenv("ROOM785_EXECUTION_ID"))
			&& RAW_ARTIFACT_PATH.equals(System.getenv("ROOM785_RAW_ARTIFACT_PATH"));
	}

	private String expectedExecutionId() throws Exception {
		return sha256(EXPECTED_POSTGRES_TEST_COMMAND);
	}

	private RuntimeProvenance runtimeProvenance() throws Exception {
		if (runtimeProvenance == null) {
			runtimeProvenance = new RuntimeProvenance(
				System.getProperty("java.vendor.version", "") + " LTS",
				jdbcTemplate.queryForObject("select version()", String.class),
				runtimePostgresImage(),
				"os.name=" + System.getProperty("os.name", "")
					+ "; os.version=" + System.getProperty("os.version", ""),
				"model=" + runCommand(
					"powershell.exe",
					"-NoProfile",
					"-Command",
					"(Get-CimInstance Win32_Processor | Select-Object -First 1 -ExpandProperty Name).Trim()")
					+ "; logicalProcessors=" + Runtime.getRuntime().availableProcessors(),
				runtimeConfiguration(),
				runCommand("docker", "version", "--format", "Docker Engine server {{.Server.Version}}"));
		}
		return runtimeProvenance;
	}

	private String runtimePostgresImage() throws Exception {
		String configuredImage = postgres.getDockerImageName();
		String repositoryDigest = runCommand(
			"docker",
			"image",
			"inspect",
			"--format={{index .RepoDigests 0}}",
			configuredImage);
		int digestSeparator = repositoryDigest.indexOf('@');
		if (digestSeparator < 0) {
			return "";
		}
		return configuredImage + repositoryDigest.substring(digestSeparator);
	}

	private String runtimeConfiguration() {
		String maxPoolSize = environment.getProperty("spring.datasource.hikari.maximum-pool-size", "");
		String sharedPreloadLibraries = jdbcTemplate.queryForObject(
			"show shared_preload_libraries", String.class);
		String schedulingEnabled = environment.getProperty("spring.task.scheduling.enabled", "true");
		String notificationRelayEnabled = environment.getProperty("app.notification.relay.enabled", "true");
		String chatRetentionEnabled = environment.getProperty("app.chat.retention.enabled", "true");
		return "Hikari maxPoolSize=" + maxPoolSize
			+ "; shared_preload_libraries=" + sharedPreloadLibraries
			+ "; fixed UTC command clock=" + FIXED_TIME
			+ "; PostgreSQL lock_timeout=100ms"
			+ "; scheduling=" + schedulingEnabled
			+ "; notificationRelay=" + notificationRelayEnabled
			+ "; chatRetention=" + chatRetentionEnabled;
	}

	private boolean hasRequiredManifestValues(RunManifest manifest) {
		if (!allNonBlank(
			manifest.artifactId(),
			manifest.rawArtifactId(),
			manifest.javaVersion(),
			manifest.postgresVersion(),
			manifest.postgresImage(),
			manifest.os(),
			manifest.cpu(),
			manifest.configuration(),
			manifest.fixtureSeed(),
			manifest.fixedTime(),
			manifest.concurrencyLevel(),
			manifest.gate(),
			manifest.retryBudget(),
			manifest.runnerCommand(),
			manifest.dockerVersion(),
			manifest.exactPostgresTestCommand(),
			manifest.artifactContent(),
			manifest.rawArtifactContent())) {
			return false;
		}
		return FIXTURE_SEED.equals(manifest.fixtureSeed())
			&& FIXED_TIME.toString().equals(manifest.fixedTime())
			&& "2".equals(manifest.concurrencyLevel())
			&& EXPECTED_GATE.equals(manifest.gate())
			&& EXPECTED_RETRY_BUDGET.equals(manifest.retryBudget())
			&& EXPECTED_RUNNER_PATH.equals(manifest.runnerCommand())
			&& EXPECTED_POSTGRES_TEST_COMMAND.equals(manifest.exactPostgresTestCommand())
			&& METRIC_FIELDS.equals(manifest.metricFields());
	}

	private boolean allNonBlank(String... values) {
		return Arrays.stream(values).allMatch(value -> value != null && !value.isBlank());
	}

	private boolean artifactReceiptMatchesManifest(RunManifest manifest) throws Exception {
		String[] lines = manifest.artifactContent().split("\\n", -1);
		if (lines.length != 11 || !lines[10].isEmpty()) {
			return false;
		}
		if (!("candidate=" + CANDIDATE).equals(lines[0])
			|| !("sourceSha=" + manifest.candidateSourceSha()).equals(lines[1])
			|| !("baseSha=" + manifest.baseSha()).equals(lines[2])
			|| !("headSha=" + manifest.headSha()).equals(lines[3])
			|| !("command=" + EXPECTED_POSTGRES_TEST_COMMAND).equals(lines[4])
			|| !("runner=" + EXPECTED_RUNNER_PATH).equals(lines[5])
			|| !("executionId=" + expectedExecutionId()).equals(lines[6])
			|| !"result=BUILD_SUCCESSFUL".equals(lines[7])
			|| !("rawArtifactDigest=" + manifest.rawArtifactDigest()).equals(lines[9])) {
			return false;
		}
		if (!lines[8].startsWith("testCases=")) {
			return false;
		}
		try {
			return Integer.parseInt(lines[8].substring("testCases=".length())) > 0;
		} catch (NumberFormatException exception) {
			return false;
		}
	}

	private boolean rawArtifactMatchesContract(String content) {
		List<RawRecord> records = content.lines()
			.map(this::parseRawRecord)
			.toList();
		if (records.stream().anyMatch(Objects::isNull)) {
			return false;
		}
		List<String> scenarios = records.stream()
			.map(RawRecord::scenario)
			.sorted()
			.toList();
		List<String> requiredScenarios = REQUIRED_RAW_SCENARIOS.stream().sorted().toList();
		return records.size() == requiredScenarios.size()
			&& records.stream().allMatch(this::recordHasValidMetrics)
			&& requiredScenarios.equals(scenarios);
	}

	private RawRecord parseRawRecord(String line) {
		if (!line.startsWith("ROOM785_RAW ")) {
			return null;
		}
		Map<String, String> values = new LinkedHashMap<>();
		for (String field : line.substring("ROOM785_RAW ".length()).split(" ")) {
			int separator = field.indexOf('=');
			if (separator <= 0 || values.put(field.substring(0, separator), field.substring(separator + 1)) != null) {
				return null;
			}
		}
		if (!METRIC_FIELDS.equals(List.copyOf(values.keySet())) || !CANDIDATE.equals(values.get("candidate"))) {
			return null;
		}
		try {
			return new RawRecord(
				values.get("scenario"),
				parseNonNegativeLong(values, "requestCount"),
				parseNonNegativeLong(values, "success"),
				parseNonNegativeLong(values, "businessFailure"),
				parseNonNegativeLong(values, "concurrencyFailure"),
				parseNonNegativeLong(values, "technicalFailure"),
				parseNonNegativeLong(values, "conflictCount"),
				parseNonNegativeLong(values, "retry0"),
				parseNonNegativeLong(values, "retry1"),
				parseNonNegativeLong(values, "retry2"),
				parseNonNegativeLong(values, "exhausted"),
				parseResponseNanos(values.get("responseNanos")),
				parseNonNegativeLong(values, "calls"),
				parseNonNegativeDouble(values, "totalExecMs"),
				parseNonNegativeLong(values, "rows"),
				parseNonNegativeLong(values, "sharedBlksHit"),
				parseNonNegativeLong(values, "sharedBlksRead"));
		} catch (IllegalArgumentException exception) {
			return null;
		}
	}

	private long parseNonNegativeLong(Map<String, String> values, String field) {
		long value = Long.parseLong(values.get(field));
		if (value < 0) {
			throw new IllegalArgumentException(field);
		}
		return value;
	}

	private double parseNonNegativeDouble(Map<String, String> values, String field) {
		double value = Double.parseDouble(values.get(field));
		if (!Double.isFinite(value) || value < 0) {
			throw new IllegalArgumentException(field);
		}
		return value;
	}

	private List<Long> parseResponseNanos(String value) {
		if (!value.startsWith("[") || !value.endsWith("]")) {
			throw new IllegalArgumentException("responseNanos");
		}
		String body = value.substring(1, value.length() - 1);
		if (body.isBlank()) {
			return List.of();
		}
		return Arrays.stream(body.split(","))
			.map(Long::parseLong)
			.peek(nanos -> {
				if (nanos < 0) {
					throw new IllegalArgumentException("responseNanos");
				}
			})
			.toList();
	}

	private boolean recordHasValidMetrics(RawRecord record) {
		long outcomeCount = record.success() + record.businessFailure()
			+ record.concurrencyFailure() + record.technicalFailure();
		long retryBucketCount = record.retry0() + record.retry1() + record.retry2();
		if (outcomeCount != record.requestCount()
			|| record.responseNanos().size() != record.requestCount()
			|| record.responseNanos().stream().anyMatch(nanos -> nanos <= 0)
			|| retryBucketCount != record.requestCount()
			|| record.calls() <= 0
			|| record.exhausted() > record.concurrencyFailure()
			|| (record.exhausted() > 0 && (record.retry2() < record.exhausted()
				|| record.conflictCount() < record.exhausted() * 3))) {
			return false;
		}
		return expectedScenarioOutcome(record);
	}

	private boolean expectedScenarioOutcome(RawRecord record) {
		String scenario = record.scenario();
		if ("T1".equals(scenario) || "T2-lock".equals(scenario)) {
			return record.requestCount() == 2
				&& record.success() == 1
				&& record.businessFailure() == 1
				&& record.concurrencyFailure() == 0
				&& record.technicalFailure() == 0;
		}
		if ("T2-due-room-order".equals(scenario)) {
			return record.requestCount() == 0;
		}
		if ("T2-rollback".equals(scenario)) {
			return record.requestCount() == 1
				&& record.success() == 0
				&& record.businessFailure() == 0
				&& record.concurrencyFailure() == 0
				&& record.technicalFailure() == 1;
		}
		if (scenario.startsWith("T3-lock-timeout")
			|| scenario.startsWith("T3-unexpected-technical")) {
			return record.requestCount() == 1
				&& record.success() == 0
				&& record.businessFailure() == 0
				&& record.concurrencyFailure() == 0
				&& record.technicalFailure() == 1;
		}
		if ("T3-optimistic-exhausted".equals(scenario)) {
			return record.requestCount() == 1
				&& record.success() == 0
				&& record.businessFailure() == 0
				&& record.concurrencyFailure() == 1
				&& record.technicalFailure() == 0
				&& record.conflictCount() == 3
				&& record.retry0() == 0
				&& record.retry1() == 0
				&& record.retry2() == 1
				&& record.exhausted() == 1;
		}
		if ("T3-deadlock".equals(scenario)) {
			return record.requestCount() == 2
				&& record.success() == 1
				&& record.businessFailure() == 0
				&& record.concurrencyFailure() == 0
				&& record.technicalFailure() == 1;
		}
		if (scenario.startsWith("T2-waitlist-new-")
			|| scenario.startsWith("T2-waitlist-reactivation-")) {
			return record.requestCount() == 2
				&& record.success() == 2
				&& record.businessFailure() == 0
				&& record.concurrencyFailure() == 0
				&& record.technicalFailure() == 0;
		}
		if ("T2-start-waitlist-cancel-first".equals(scenario)) {
			return record.requestCount() == 2
				&& record.success() == 2
				&& record.businessFailure() == 0
				&& record.concurrencyFailure() == 0
				&& record.technicalFailure() == 0;
		}
		if ("T2-start-waitlist-correction-first".equals(scenario)) {
			return record.requestCount() == 2
				&& record.success() == 1
				&& record.businessFailure() == 1
				&& record.concurrencyFailure() == 0
				&& record.technicalFailure() == 0;
		}
		if (scenario.startsWith("T2-start-")) {
			return record.requestCount() == 2
				&& record.success() == 1
				&& record.businessFailure() == 1
				&& record.concurrencyFailure() == 0
				&& record.technicalFailure() == 0;
		}
		return false;
	}

	private String rawScenario(String line) {
		return Arrays.stream(line.split(" "))
			.filter(field -> field.startsWith("scenario="))
			.findFirst()
			.map(field -> field.substring("scenario=".length()))
			.orElse("");
	}

	private boolean hasMetricFields(String line) {
		try {
			return METRIC_FIELDS.equals(rawFieldNames(line));
		} catch (RuntimeException exception) {
			return false;
		}
	}

	private Path resolveArtifact(Path repositoryRoot, String artifactId) {
		Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
		Path artifact = Path.of(artifactId);
		if (artifact.isAbsolute()) {
			return artifact.normalize();
		}
		Path resolved = normalizedRoot.resolve(artifact).normalize();
		return resolved.startsWith(normalizedRoot)
			? resolved
			: normalizedRoot.resolve("__invalid_room_lock_artifact__");
	}

	private boolean isRegularManifestArtifact(
		Path repositoryRoot, String artifactId, Path artifact, boolean allowExternal) throws Exception {
		Path normalizedRoot = repositoryRoot.toAbsolutePath().normalize();
		Path normalizedArtifact = artifact.toAbsolutePath().normalize();
		if (!Files.isRegularFile(artifact) || Files.isSymbolicLink(artifact)) {
			return false;
		}
		if (!normalizedArtifact.startsWith(normalizedRoot)) {
			return allowExternal;
		}
		return !runGit(repositoryRoot, "ls-files", "--error-unmatch", "--", artifactId).isBlank();
	}

	private boolean isCandidateCheckoutHead(Path repositoryRoot, String candidateSourceSha) throws Exception {
		String currentHead = gitHead(repositoryRoot);
		if (candidateSourceSha.equals(currentHead)) {
			return hasOnlyManifestWorkingTreeChange(repositoryRoot);
		}
		String firstParent = gitParent(repositoryRoot, currentHead);
		if (candidateSourceSha.equals(firstParent)) {
			return hasOnlyManifestWorkingTreeChange(repositoryRoot)
				&& hasExactArtifactBundleChanges(gitDiffNames(repositoryRoot, candidateSourceSha, currentHead));
		}
		String secondParent = gitSecondParent(repositoryRoot, currentHead);
		return !secondParent.isBlank()
			&& candidateSourceSha.equals(gitParent(repositoryRoot, secondParent))
			&& hasExactArtifactBundleChanges(gitDiffNames(repositoryRoot, candidateSourceSha, secondParent));
	}

	private boolean hasOnlyManifestWorkingTreeChange(Path repositoryRoot) throws Exception {
		return hasOnlyManifestChanges(gitWorkingTreeDiffNames(repositoryRoot))
			&& hasOnlyManifestChanges(gitStagedDiffNames(repositoryRoot))
			&& gitUntrackedFiles(repositoryRoot).isBlank();
	}

	private boolean hasOnlyManifestChanges(String changedFiles) {
		return changedFiles.isBlank()
			|| Arrays.stream(changedFiles.split("\\R"))
				.allMatch(IMMUTABLE_ARTIFACT_BUNDLE_PATHS::contains);
	}

	private boolean hasExactArtifactBundleChanges(String changedFiles) {
		if (changedFiles.isBlank()) {
			return false;
		}
		Set<String> changed = Arrays.stream(changedFiles.split("\\R"))
			.filter(file -> !file.isBlank())
			.collect(java.util.stream.Collectors.toSet());
		return changed.equals(IMMUTABLE_ARTIFACT_BUNDLE_PATHS)
			|| changed.equals(PROVENANCE_BUNDLE_WITH_TEST_CONTRACT_PATHS);
	}

	private String gitHead(Path repositoryRoot) throws Exception {
		return runGit(repositoryRoot, "rev-parse", "HEAD");
	}

	private String gitParent(Path repositoryRoot, String commitSha) throws Exception {
		if (commitSha.isBlank()) {
			return "";
		}
		return runGit(repositoryRoot, "rev-parse", commitSha + "^");
	}

	private String gitSecondParent(Path repositoryRoot, String commitSha) throws Exception {
		if (commitSha.isBlank()) {
			return "";
		}
		return runGit(repositoryRoot, "rev-parse", commitSha + "^2");
	}

	private String gitDiffNames(Path repositoryRoot, String baseSha, String headSha) throws Exception {
		return runGit(repositoryRoot, "diff", "--name-only", baseSha + ".." + headSha);
	}

	private String gitWorkingTreeDiffNames(Path repositoryRoot) throws Exception {
		return runGit(repositoryRoot, "diff", "--name-only");
	}

	private String gitStagedDiffNames(Path repositoryRoot) throws Exception {
		return runGit(repositoryRoot, "diff", "--cached", "--name-only");
	}

	private String gitUntrackedFiles(Path repositoryRoot) throws Exception {
		return runGit(repositoryRoot, "ls-files", "--others", "--exclude-standard");
	}

	private String runCommand(String... command) throws Exception {
		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		return process.waitFor() == 0 ? output : "";
	}

	private String runGit(Path repositoryRoot, String... arguments) throws Exception {
		String[] command = new String[arguments.length + 3];
		command[0] = "git";
		command[1] = "-C";
		command[2] = repositoryRoot.toString();
		System.arraycopy(arguments, 0, command, 3, arguments.length);
		Process process = new ProcessBuilder(command)
			.redirectErrorStream(true)
			.start();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		return process.waitFor() == 0 ? output : "";
	}

	private boolean gitCommitExists(Path repositoryRoot, String commitSha) throws Exception {
		Process process = new ProcessBuilder(
			"git", "-C", repositoryRoot.toString(), "cat-file", "-e", commitSha + "^{commit}")
			.redirectErrorStream(true)
			.start();
		process.getInputStream().readAllBytes();
		return process.waitFor() == 0;
	}

	private String gitDiffDigest(Path repositoryRoot, String baseSha, String headSha) throws Exception {
		Process process = new ProcessBuilder(
			"git", "-C", repositoryRoot.toString(), "diff-tree", "--no-commit-id", "--raw", "-r", "-z",
			"--no-renames", baseSha, headSha)
			.redirectErrorStream(true)
			.start();
		byte[] diffMetadata = process.getInputStream().readAllBytes();
		if (process.waitFor() != 0) {
			return "";
		}
		return sha256(diffMetadata);
	}

	private String sha256(Path path) throws Exception {
		return sha256(Files.readAllBytes(path));
	}

	private String sha256(byte[] content) throws Exception {
		return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
	}

	private String sha256(String content) throws Exception {
		return sha256(content.getBytes(StandardCharsets.UTF_8));
	}

	private boolean isSha1(String value) {
		return value.matches("[0-9a-f]{40}");
	}

	private boolean isSha256(String value) {
		return value.matches("[0-9a-f]{64}");
	}

	private record RuntimeProvenance(
		String javaVersion,
		String postgresVersion,
		String postgresImage,
		String os,
		String cpu,
		String configuration,
		String dockerVersion) {
	}

	private ManifestValidationResult invalid(String reason) {
		return new ManifestValidationResult("INVALID", reason);
	}

	private void assertTechnicalFailure() throws Exception {
		Room room = createRoom();
		long userId = insertUser("unexpected-storage-failure");
		StoredSnapshot before = snapshot(room.getId());
		installUnexpectedStorageFailureTrigger();
		try {
			TimedApiResult result = measureApiRequest(() -> postParticipation(userId, room.getId()));

			assertApi(result.result(), 500, ErrorCode.INTERNAL_SERVER_ERROR);
			assertEquals(before, snapshot(room.getId()));
			assertRetryLog("reasonCode=UNEXPECTED_TECHNICAL_FAILURE", "sqlState=XX000", "attempt=1");
			assertEquals(1, retryLogCount("reasonCode=UNEXPECTED_TECHNICAL_FAILURE"));
			assertEquals(0, retryLogCount("OPTIMISTIC_LOCK_CONFLICT"));
			logRawArtifact(
				"T3-unexpected-technical",
				new RawMetrics(
					1,
					0,
					0,
					0,
					1,
					0,
					1,
					0,
					0,
					0,
					List.of(result.responseNanos()),
					readPostgresCost()));
		} finally {
			removeUnexpectedStorageFailureTrigger();
			roomWriteGate.deactivate();
		}
	}

	private void installUnexpectedStorageFailureTrigger() {
		jdbcTemplate.execute("drop trigger if exists room785_unexpected_storage_failure on rooms");
		jdbcTemplate.execute("""
			create or replace function room785_unexpected_storage_failure()
			returns trigger
			language plpgsql
			as $$
			begin
				raise exception 'controlled storage failure' using errcode = 'XX000';
			end;
			$$
			""");
		jdbcTemplate.execute("""
			create trigger room785_unexpected_storage_failure
			before update on rooms
			for each row execute function room785_unexpected_storage_failure()
			""");
	}

	private void removeUnexpectedStorageFailureTrigger() {
		jdbcTemplate.execute("drop trigger if exists room785_unexpected_storage_failure on rooms");
		jdbcTemplate.execute("drop function if exists room785_unexpected_storage_failure()");
	}

	private void assertRetryLog(String... fragments) {
		assertTrue(containsRetryLog(retryLogs, fragments) || containsRetryLog(waitlistRegistrationLogs, fragments),
			() -> "retry log not found: " + Arrays.toString(fragments));
	}

	private String retryLogText(ILoggingEvent event) {
		if (event.getKeyValuePairs() == null || event.getKeyValuePairs().isEmpty()) {
			return event.getFormattedMessage();
		}
		return event.getKeyValuePairs().stream()
			.map(pair -> pair.key + "=" + pair.value)
			.collect(Collectors.joining(" "));
	}

	private long retryLogCount(String fragment) {
		return retryLogCount(retryLogs, fragment) + retryLogCount(waitlistRegistrationLogs, fragment);
	}

	private long retryLogCount(ListAppender<ILoggingEvent> appender, String fragment) {
		return appender.list.stream()
			.map(this::retryLogText)
			.filter(message -> message.contains(fragment))
			.count();
	}

	private boolean containsRetryLog(ListAppender<ILoggingEvent> appender, String... fragments) {
		return appender.list.stream()
			.map(this::retryLogText)
			.anyMatch(message -> Arrays.stream(fragments).allMatch(message::contains));
	}

	/** 시작 경계 fixture는 경계 이전 시각의 ROOM에서 준비한 뒤 경합 직전에 start_at을 경계로 옮긴다. */
	private void moveStartAtToBoundary(long roomId) {
		jdbcTemplate.update("update rooms set start_at = ? where id = ?", Timestamp.from(FIXED_TIME), roomId);
	}

	/** 시작 경계 이전부터 WAITING인 대기를 만든다. 경계 이후에는 production 등록이 거절되므로 직접 적재한다. */
	private void createWaitingWaitlist(long roomId, long userId) {
		long queueOrder = jdbcTemplate.queryForObject(
			"select nextval('room_waitlist_queue_order_seq')", Long.class);
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(
			roomId, userId, queueOrder, FIXED_TIME.minusSeconds(10)));
	}

	private void createCanceledWaitlist(long roomId, long userId) {
		long queueOrder = jdbcTemplate.queryForObject(
			"select nextval('room_waitlist_queue_order_seq')", Long.class);
		roomWaitlistRepository.saveAndFlush(RoomWaitlist.create(
			roomId, userId, queueOrder, FIXED_TIME.minusSeconds(10)));
		jdbcTemplate.update(
			"update room_waitlists set status = 'CANCELED', updated_at = ? where room_id = ? and user_id = ?",
			Timestamp.from(FIXED_TIME.minusSeconds(1)), roomId, userId);
	}

	private RawMetrics runLastSeatParticipationRound(String suffix) throws Exception {
		Room room = createRoom(FIXTURE_SEED + "-" + suffix, FIXED_TIME.plusSeconds(3600));
		long firstUserId = insertUser(suffix + "-first");
		long secondUserId = insertUser(suffix + "-second");
		resetPostgresStatistics();

		roomWriteGate.holdFirstWriteLock(room.getId(), true);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		CountDownLatch secondStarted = new CountDownLatch(1);
		try {
			Future<TimedApiResult> first = executor.submit(
				() -> measureApiRequest(() -> postParticipation(firstUserId, room.getId())));
			roomWriteGate.awaitFirstWriteLock();
			Future<TimedApiResult> second = executor.submit(() -> {
				secondStarted.countDown();
				return measureApiRequest(() -> postParticipation(secondUserId, room.getId()));
			});
			await(secondStarted, "두 번째 직접 참가 요청 시작");
			roomWriteGate.awaitSecondWriteLockRequest();
			roomWriteGate.releaseFirstWriteLock();

			TimedApiResult firstResult = first.get(WAIT_SECONDS, TimeUnit.SECONDS);
			roomWriteGate.releaseSecondWriteLockRequest();
			TimedApiResult secondResult = second.get(WAIT_SECONDS, TimeUnit.SECONDS);
			assertApi(firstResult.result(), 201, null);
			assertApi(secondResult.result(), 409, ErrorCode.CAPACITY_EXCEEDED);
			StoredSnapshot snapshot = snapshot(room.getId());
			assertEquals(1, snapshot.room().activeParticipantCount());
			assertEquals(RoomStatus.CLOSED, snapshot.room().status());
			assertEquals(ParticipationStatus.ACTIVE, snapshot.participationStatus(firstUserId));
			assertTrue(snapshot.participationStatus(secondUserId) == null);
			assertRoomSnapshotInvariant(snapshot);
			roomWriteGate.assertWriteLockProtocol(2);
			return new RawMetrics(
				2,
				1,
				1,
				0,
				0,
				0,
				2,
				0,
				0,
				0,
				List.of(firstResult.responseNanos(), secondResult.responseNanos()),
				readPostgresCost());
		} finally {
			roomWriteGate.deactivate();
			executor.shutdownNow();
		}
	}

	private void runWaitlistRegistrationPreconditionRound(boolean firstCommandFirst) throws Exception {
		String order = Boolean.toString(firstCommandFirst);
		Room room = createRoom(FIXTURE_SEED + "-precondition-" + order, FIXED_TIME.plusSeconds(3600));
		long directUserId = insertUser("precondition-direct-" + order);
		long waitlistUserId = insertUser("precondition-waitlist-" + order);
		resetPostgresStatistics();

		List<CommandOutcome> outcomes;
		if (firstCommandFirst) {
			outcomes = List.of(
				measureCommand(() -> roomParticipationService.participate(directUserId, room.getId())),
				measureCommand(() -> roomWaitlistCommandService.register(waitlistUserId, room.getId())));
		} else {
			outcomes = List.of(
				measureCommand(() -> roomWaitlistCommandService.register(waitlistUserId, room.getId())),
				measureCommand(() -> roomParticipationService.participate(directUserId, room.getId())));
		}
		logRawArtifact(
			"T2-waitlist-registration-precondition-boundary-" + order,
			metricsFor(outcomes));

		CommandOutcome directOutcome = firstCommandFirst ? outcomes.get(0) : outcomes.get(1);
		CommandOutcome waitlistOutcome = firstCommandFirst ? outcomes.get(1) : outcomes.get(0);
		assertTrue(directOutcome.successful());
		if (firstCommandFirst) {
			assertTrue(waitlistOutcome.successful());
			assertEquals(RoomWaitlistStatus.WAITING, snapshot(room.getId()).waitlistStatus(waitlistUserId));
		} else {
			assertEquals(ErrorCode.WAITLIST_NOT_AVAILABLE, waitlistOutcome.errorCode());
			assertTrue(roomWaitlistRepository.findById(
				new cloud.bamsongi.albammate.room.entity.RoomWaitlistId(room.getId(), waitlistUserId)).isEmpty());
		}
		assertRoomSnapshotInvariant(snapshot(room.getId()));
	}

	private void runWaitlistRegistrationCommitOrderRound(boolean firstCommandFirst) throws Exception {
		String order = Boolean.toString(firstCommandFirst);
		Room room = createRoom(FIXTURE_SEED + "-registration-order-" + order, FIXED_TIME.plusSeconds(3600));
		long activeUserId = insertUser("registration-active-" + order);
		long firstUserId = insertUser("registration-first-" + order);
		long secondUserId = insertUser("registration-second-" + order);
		assertApi(postParticipation(activeUserId, room.getId()), 201, null);

		List<CommandOutcome> outcomes = runOrderedCommands(
			"T2-waitlist-registration-commit-order-" + order,
			room.getId(),
			firstCommandFirst,
			() -> roomWaitlistCommandService.register(firstUserId, room.getId()),
			() -> roomWaitlistCommandService.register(secondUserId, room.getId()));

		assertTrue(outcomes.stream().allMatch(CommandOutcome::successful));
		long earlierUserId = firstCommandFirst ? firstUserId : secondUserId;
		long laterUserId = firstCommandFirst ? secondUserId : firstUserId;
		assertTrue(waitlistQueueOrder(room.getId(), earlierUserId) < waitlistQueueOrder(room.getId(), laterUserId));
		assertRoomSnapshotInvariant(snapshot(room.getId()));
	}

	private void runWaitlistReactivationCommitOrderRound(boolean firstCommandFirst) throws Exception {
		String order = Boolean.toString(firstCommandFirst);
		Room room = createRoom(FIXTURE_SEED + "-reactivation-order-" + order, FIXED_TIME.plusSeconds(3600));
		long activeUserId = insertUser("reactivation-active-" + order);
		long reactivatingUserId = insertUser("reactivation-existing-" + order);
		long newUserId = insertUser("reactivation-new-" + order);
		assertApi(postParticipation(activeUserId, room.getId()), 201, null);
		assertTrue(roomWaitlistCommandService.register(reactivatingUserId, room.getId()).created());
		roomWaitlistCommandService.cancel(reactivatingUserId, room.getId());

		List<CommandOutcome> outcomes = runOrderedCommands(
			"T2-waitlist-reactivation-commit-order-" + order,
			room.getId(),
			firstCommandFirst,
			() -> roomWaitlistCommandService.register(reactivatingUserId, room.getId()),
			() -> roomWaitlistCommandService.register(newUserId, room.getId()));

		assertTrue(outcomes.stream().allMatch(CommandOutcome::successful));
		long earlierUserId = firstCommandFirst ? reactivatingUserId : newUserId;
		long laterUserId = firstCommandFirst ? newUserId : reactivatingUserId;
		assertTrue(waitlistQueueOrder(room.getId(), earlierUserId) < waitlistQueueOrder(room.getId(), laterUserId));
		assertRoomSnapshotInvariant(snapshot(room.getId()));
	}

	private void runWaitlistCancellationPromotionRound(boolean cancellationFirst) throws Exception {
		String order = Boolean.toString(cancellationFirst);
		Room room = createRoom(FIXTURE_SEED + "-promotion-" + order, FIXED_TIME.plusSeconds(3600));
		long activeUserId = insertUser("promotion-active-" + order);
		long waitingUserId = insertUser("promotion-waiting-" + order);
		assertApi(postParticipation(activeUserId, room.getId()), 201, null);
		assertTrue(roomWaitlistCommandService.register(waitingUserId, room.getId()).created());

		List<CommandOutcome> outcomes = runOrderedCommands(
			"T2-waitlist-cancellation-participation-promotion-" + cancellationFirst,
			room.getId(),
			cancellationFirst,
			() -> {
				roomWaitlistCommandService.cancel(waitingUserId, room.getId());
				return null;
			},
			() -> roomParticipationCancelService.cancelParticipation(activeUserId, room.getId()));

		if (cancellationFirst) {
			assertTrue(outcomes.stream().allMatch(CommandOutcome::successful));
			assertEquals(RoomWaitlistStatus.CANCELED, snapshot(room.getId()).waitlistStatus(waitingUserId));
		} else {
			assertTrue(outcomes.get(1).successful());
			assertEquals(ErrorCode.WAITLIST_ENTRY_NOT_FOUND, outcomes.get(0).errorCode());
			assertEquals(RoomWaitlistStatus.PROMOTED, snapshot(room.getId()).waitlistStatus(waitingUserId));
			assertEquals(ParticipationStatus.ACTIVE, snapshot(room.getId()).participationStatus(waitingUserId));
		}
		assertRoomSnapshotInvariant(snapshot(room.getId()));
	}

	private void runParticipationCancellationRound(boolean firstCommandFirst) throws Exception {
		String order = Boolean.toString(firstCommandFirst);
		Room room = createRoom(FIXTURE_SEED + "-cancel-order-" + order, FIXED_TIME.plusSeconds(3600), 2);
		long firstUserId = insertUser("cancel-first-" + order);
		long secondUserId = insertUser("cancel-second-" + order);
		assertApi(postParticipation(firstUserId, room.getId()), 201, null);
		assertApi(postParticipation(secondUserId, room.getId()), 201, null);

		List<CommandOutcome> outcomes = runOrderedCommands(
			"T2-participation-cancellation-cancellation-" + firstCommandFirst,
			room.getId(),
			firstCommandFirst,
			() -> roomParticipationCancelService.cancelParticipation(firstUserId, room.getId()),
			() -> roomParticipationCancelService.cancelParticipation(secondUserId, room.getId()));

		assertTrue(outcomes.stream().allMatch(CommandOutcome::successful));
		StoredSnapshot snapshot = snapshot(room.getId());
		assertEquals(ParticipationStatus.CANCELED, snapshot.participationStatus(firstUserId));
		assertEquals(ParticipationStatus.CANCELED, snapshot.participationStatus(secondUserId));
		assertEquals(0, snapshot.room().activeParticipantCount());
		assertRoomSnapshotInvariant(snapshot);
	}

	private List<CommandOutcome> runOrderedCommands(
		String scenario,
		long roomId,
		boolean firstCommandFirst,
		Callable<?> firstCommand,
		Callable<?> secondCommand) throws Exception {
		resetPostgresStatistics();
		roomWriteGate.holdFirstWriteLock(roomId, true);
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<CommandOutcome> first;
			Future<CommandOutcome> second;
			Future<CommandOutcome> firstLock;
			Future<CommandOutcome> secondLock;
			if (firstCommandFirst) {
				first = executor.submit(() -> measureCommand(firstCommand));
				firstLock = first;
				roomWriteGate.awaitFirstWriteLock();
				second = executor.submit(() -> measureCommand(secondCommand));
				secondLock = second;
			} else {
				second = executor.submit(() -> measureCommand(secondCommand));
				firstLock = second;
				roomWriteGate.awaitFirstWriteLock();
				first = executor.submit(() -> measureCommand(firstCommand));
				secondLock = first;
			}
			roomWriteGate.awaitSecondWriteLockRequest();
			roomWriteGate.releaseFirstWriteLock();

			CommandOutcome firstLockOutcome = firstLock.get(WAIT_SECONDS, TimeUnit.SECONDS);
			roomWriteGate.releaseSecondWriteLockRequest();
			CommandOutcome secondLockOutcome = secondLock.get(WAIT_SECONDS, TimeUnit.SECONDS);
			List<CommandOutcome> outcomes = firstCommandFirst
				? List.of(firstLockOutcome, secondLockOutcome)
				: List.of(secondLockOutcome, firstLockOutcome);
			logRawArtifact(scenario, metricsFor(outcomes));
			return outcomes;
		} finally {
			roomWriteGate.deactivate();
			executor.shutdownNow();
		}
	}

	private CommandOutcome measureCommand(Callable<?> command) throws Exception {
		long startedNanos = System.nanoTime();
		try {
			command.call();
			return new CommandOutcome(true, null, System.nanoTime() - startedNanos);
		} catch (BusinessException exception) {
			return new CommandOutcome(false, exception.getErrorCode(), System.nanoTime() - startedNanos);
		}
	}

	private RawMetrics metricsFor(List<CommandOutcome> outcomes) {
		int success = (int)outcomes.stream().filter(CommandOutcome::successful).count();
		int businessFailure = outcomes.size() - success;
		return new RawMetrics(
			outcomes.size(),
			success,
			businessFailure,
			0,
			0,
			0,
			outcomes.size(),
			0,
			0,
			0,
			outcomes.stream().map(CommandOutcome::responseNanos).toList(),
			readPostgresCost());
	}

	private long waitlistQueueOrder(long roomId, long userId) {
		return jdbcTemplate.queryForObject(
			"select queue_order from room_waitlists where room_id = ? and user_id = ?",
			Long.class,
			roomId,
			userId);
	}

	private void runWaitlistPromotionRound(boolean registrationFirst, boolean reactivation, int repetition)
		throws Exception {
		String mode = reactivation ? "r" : "n";
		String order = (registrationFirst ? "r" : "c") + repetition;
		Room room = createRoom(FIXTURE_SEED + "-p-" + mode + "-" + order,
			FIXED_TIME.plusSeconds(3600));
		long activeUserId = insertUser("p-" + mode + "-a-" + order);
		long firstWaitingUserId = insertUser("p-" + mode + "-f-" + order);
		long secondWaitingUserId = insertUser("p-" + mode + "-s-" + order);
		assertApi(postParticipation(activeUserId, room.getId()), 201, null);
		assertApi(postWaitlist(secondWaitingUserId, room.getId()), 201, null);
		if (reactivation) {
			createCanceledWaitlist(room.getId(), firstWaitingUserId);
		}
		resetPostgresStatistics();

		roomWriteGate.holdFirstWriteLock(room.getId());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<TimedApiResult> registration;
			Future<TimedApiResult> cancellation;
			if (registrationFirst) {
				registration = executor.submit(
					() -> measureApiRequest(() -> postWaitlist(firstWaitingUserId, room.getId())));
				roomWriteGate.awaitFirstWriteLock();
				cancellation = executor.submit(
					() -> measureApiRequest(() -> deleteParticipation(activeUserId, room.getId())));
			} else {
				cancellation = executor.submit(
					() -> measureApiRequest(() -> deleteParticipation(activeUserId, room.getId())));
				roomWriteGate.awaitFirstWriteLock();
				registration = executor.submit(
					() -> measureApiRequest(() -> postWaitlist(firstWaitingUserId, room.getId())));
			}
			roomWriteGate.awaitSecondWriteLockRequest();
			roomWriteGate.releaseFirstWriteLock();

			TimedApiResult registrationResult = registration.get(WAIT_SECONDS, TimeUnit.SECONDS);
			TimedApiResult cancellationResult = cancellation.get(WAIT_SECONDS, TimeUnit.SECONDS);
			assertApi(registrationResult.result(), 201, null);
			assertApi(cancellationResult.result(), 200, null);
			StoredSnapshot snapshot = snapshot(room.getId());
			assertEquals(1, snapshot.room().activeParticipantCount());
			assertEquals(RoomStatus.CLOSED, snapshot.room().status());
			assertEquals(ParticipationStatus.CANCELED, snapshot.participationStatus(activeUserId));
			assertEquals(ParticipationStatus.ACTIVE, snapshot.participationStatus(secondWaitingUserId));
			assertEquals(RoomWaitlistStatus.WAITING, snapshot.waitlistStatus(firstWaitingUserId));
			assertEquals(RoomWaitlistStatus.PROMOTED, snapshot.waitlistStatus(secondWaitingUserId));
			assertRoomSnapshotInvariant(snapshot);
			roomWriteGate.assertWriteLockProtocol(2);
			String scenario = "T2-waitlist-" + (reactivation ? "reactivation" : "new") + "-"
				+ (registrationFirst ? "promotion" : "cancel-first-promotion");
			logRawArtifact(scenario, new RawMetrics(
				2,
				2,
				0,
				0,
				0,
				0,
				2,
				0,
				0,
				0,
				List.of(registrationResult.responseNanos(), cancellationResult.responseNanos()),
				readPostgresCost()));
		} finally {
			roomWriteGate.deactivate();
			executor.shutdownNow();
		}
	}

	private StoredSnapshot runStartBoundaryRound(boolean participationFirst, int repetition) throws Exception {
		Room room = createRoom(
			FIXTURE_SEED + (participationFirst ? "-boundary-participation" : "-boundary-correction") + repetition,
			FIXED_TIME.plusSeconds(3600));
		long userId = insertUser((participationFirst ? "boundary-participant" : "boundary-rejected") + repetition);
		moveStartAtToBoundary(room.getId());
		resetPostgresStatistics();
		roomWriteGate.holdFirstWriteLock(room.getId());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<TimedApiResult> participation;
			Future<?> correction;
			if (participationFirst) {
				participation = executor.submit(
					() -> measureApiRequest(() -> postParticipation(userId, room.getId())));
				roomWriteGate.awaitFirstWriteLock();
				correction = executor.submit(
					() -> roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME));
				roomWriteGate.awaitSecondWriteLockRequest();
			} else {
				correction = executor.submit(
					() -> roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME));
				roomWriteGate.awaitFirstWriteLock();
				participation = executor.submit(
					() -> measureApiRequest(() -> postParticipation(userId, room.getId())));
				roomWriteGate.awaitSecondWriteLockRequest();
			}
			roomWriteGate.releaseFirstWriteLock();

			TimedApiResult participationResult;
			long correctionResponseNanos;
			if (participationFirst) {
				participationResult = participation.get(WAIT_SECONDS, TimeUnit.SECONDS);
				correctionResponseNanos = awaitCorrection(correction);
			} else {
				correctionResponseNanos = awaitCorrection(correction);
				participationResult = participation.get(WAIT_SECONDS, TimeUnit.SECONDS);
			}
			assertApi(participationResult.result(), 409, ErrorCode.ROOM_NOT_RECRUITING);
			StoredSnapshot snapshot = snapshot(room.getId());
			roomWriteGate.assertWriteLockProtocol(2);
			logRawArtifact(
				participationFirst ? "T2-start-direct-participation-first" : "T2-start-correction-first",
				new RawMetrics(
					2,
					1,
					1,
					0,
					0,
					0,
					2,
					0,
					0,
					0,
					List.of(participationResult.responseNanos(), correctionResponseNanos),
					readPostgresCost()));
			return snapshot;
		} finally {
			roomWriteGate.deactivate();
			executor.shutdownNow();
		}
	}

	private StoredSnapshot runStartBoundaryWaitlistRound(
		boolean registrationFirst, boolean reactivation, int repetition) throws Exception {
		String order = (registrationFirst ? "r" : "c") + repetition;
		String mode = reactivation ? "reactivation" : "new";
		Room room = createRoom(
			FIXTURE_SEED + "-bwl-" + mode + "-" + order,
			FIXED_TIME.plusSeconds(3600));
		long activeUserId = insertUser("bwl-active-" + mode + "-" + order);
		long waitingUserId = insertUser("bwl-waiting-" + mode + "-" + order);
		long userId = insertUser("bwl-" + mode + "-" + order);
		assertApi(postParticipation(activeUserId, room.getId()), 201, null);
		createWaitingWaitlist(room.getId(), waitingUserId);
		if (reactivation) {
			createCanceledWaitlist(room.getId(), userId);
		}
		moveStartAtToBoundary(room.getId());
		resetPostgresStatistics();

		roomWriteGate.holdFirstWriteLock(room.getId());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<TimedApiResult> registration;
			Future<?> correction;
			if (registrationFirst) {
				registration = executor.submit(
					() -> measureApiRequest(() -> postWaitlist(userId, room.getId())));
				roomWriteGate.awaitFirstWriteLock();
				correction = executor.submit(
					() -> roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME));
				roomWriteGate.awaitSecondWriteLockRequest();
			} else {
				correction = executor.submit(
					() -> roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME));
				roomWriteGate.awaitFirstWriteLock();
				registration = executor.submit(
					() -> measureApiRequest(() -> postWaitlist(userId, room.getId())));
				roomWriteGate.awaitSecondWriteLockRequest();
			}
			roomWriteGate.releaseFirstWriteLock();

			TimedApiResult registrationResult;
			long correctionResponseNanos;
			if (registrationFirst) {
				registrationResult = registration.get(WAIT_SECONDS, TimeUnit.SECONDS);
				correctionResponseNanos = awaitCorrection(correction);
			} else {
				correctionResponseNanos = awaitCorrection(correction);
				registrationResult = registration.get(WAIT_SECONDS, TimeUnit.SECONDS);
			}
			assertApi(registrationResult.result(), 409, ErrorCode.WAITLIST_NOT_AVAILABLE);
			StoredSnapshot snapshot = snapshot(room.getId());
			assertEquals(1, snapshot.room().activeParticipantCount());
			assertEquals(1, snapshot.participations().size());
			assertEquals(ParticipationStatus.ACTIVE, snapshot.participationStatus(activeUserId));
			assertEquals(RoomWaitlistStatus.EXPIRED, snapshot.waitlistStatus(waitingUserId));
			assertEquals(reactivation ? RoomWaitlistStatus.CANCELED : null,
				snapshot.waitlistStatus(userId));
			assertEquals(0, snapshot.waitlists().stream()
				.filter(waitlist -> waitlist.status() == RoomWaitlistStatus.WAITING)
				.count());
			assertRoomSnapshotInvariant(snapshot);
			roomWriteGate.assertWriteLockProtocol(2);
			String scenario;
			if (reactivation) {
				scenario = registrationFirst
					? "T2-start-waitlist-reactivation-registration-first"
					: "T2-start-waitlist-reactivation-correction-first";
			} else {
				scenario = registrationFirst
					? "T2-start-waitlist-new-registration-first"
					: "T2-start-waitlist-new-correction-first";
			}
			logRawArtifact(scenario, new RawMetrics(
				2,
				1,
				1,
				0,
				0,
				0,
				2,
				0,
				0,
				0,
				List.of(registrationResult.responseNanos(), correctionResponseNanos),
				readPostgresCost()));
			return snapshot;
		} finally {
			roomWriteGate.deactivate();
			executor.shutdownNow();
		}
	}

	private StoredSnapshot runStartBoundaryParticipationCancellationRound(
		boolean cancellationFirst, int repetition) throws Exception {
		String order = (cancellationFirst ? "cancel" : "correction") + repetition;
		Room room = createRoom(FIXTURE_SEED + "-bpc-" + order, FIXED_TIME.plusSeconds(3600));
		long activeUserId = insertUser("bpc-active-" + order);
		long waitingUserId = insertUser("bpc-waiting-" + order);
		assertApi(postParticipation(activeUserId, room.getId()), 201, null);
		assertApi(postWaitlist(waitingUserId, room.getId()), 201, null);
		moveStartAtToBoundary(room.getId());
		resetPostgresStatistics();

		roomWriteGate.holdFirstWriteLock(room.getId());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<TimedApiResult> cancellation;
			Future<?> correction;
			if (cancellationFirst) {
				cancellation = executor.submit(
					() -> measureApiRequest(() -> deleteParticipation(activeUserId, room.getId())));
				roomWriteGate.awaitFirstWriteLock();
				correction = executor.submit(
					() -> roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME));
				roomWriteGate.awaitSecondWriteLockRequest();
			} else {
				correction = executor.submit(
					() -> roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME));
				roomWriteGate.awaitFirstWriteLock();
				cancellation = executor.submit(
					() -> measureApiRequest(() -> deleteParticipation(activeUserId, room.getId())));
				roomWriteGate.awaitSecondWriteLockRequest();
			}
			roomWriteGate.releaseFirstWriteLock();

			TimedApiResult cancellationResult;
			long correctionResponseNanos;
			if (cancellationFirst) {
				cancellationResult = cancellation.get(WAIT_SECONDS, TimeUnit.SECONDS);
				correctionResponseNanos = awaitCorrection(correction);
			} else {
				correctionResponseNanos = awaitCorrection(correction);
				cancellationResult = cancellation.get(WAIT_SECONDS, TimeUnit.SECONDS);
			}
			assertApi(cancellationResult.result(), 409, ErrorCode.INVALID_ROOM_STATUS_TRANSITION);
			StoredSnapshot snapshot = snapshot(room.getId());
			// 시작 경계 이후에는 참가 취소가 거절되므로 자동 승격도 일어나지 않는다.
			assertEquals(ParticipationStatus.ACTIVE, snapshot.participationStatus(activeUserId));
			assertTrue(snapshot.participationStatus(waitingUserId) == null);
			assertEquals(RoomWaitlistStatus.EXPIRED, snapshot.waitlistStatus(waitingUserId));
			assertRoomSnapshotInvariant(snapshot);
			roomWriteGate.assertWriteLockProtocol(2);
			logRawArtifact(
				cancellationFirst ? "T2-start-participation-cancel-first" : "T2-start-participation-correction-first",
				new RawMetrics(
					2,
					1,
					1,
					0,
					0,
					0,
					2,
					0,
					0,
					0,
					List.of(cancellationResult.responseNanos(), correctionResponseNanos),
					readPostgresCost()));
			return snapshot;
		} finally {
			roomWriteGate.deactivate();
			executor.shutdownNow();
		}
	}

	private StoredSnapshot runStartBoundaryWaitlistCancellationRound(
		boolean cancellationFirst, int repetition) throws Exception {
		String order = (cancellationFirst ? "cancel" : "correction") + repetition;
		Room room = createRoom(FIXTURE_SEED + "-bwc-" + order, FIXED_TIME.plusSeconds(3600));
		long activeUserId = insertUser("bwc-active-" + order);
		long waitingUserId = insertUser("bwc-waiting-" + order);
		assertApi(postParticipation(activeUserId, room.getId()), 201, null);
		assertApi(postWaitlist(waitingUserId, room.getId()), 201, null);
		moveStartAtToBoundary(room.getId());
		resetPostgresStatistics();

		roomWriteGate.holdFirstWriteLock(room.getId());
		ExecutorService executor = Executors.newFixedThreadPool(2);
		try {
			Future<TimedApiResult> cancellation;
			Future<?> correction;
			if (cancellationFirst) {
				cancellation = executor.submit(
					() -> measureApiRequest(() -> deleteWaitlist(waitingUserId, room.getId())));
				roomWriteGate.awaitFirstWriteLock();
				correction = executor.submit(
					() -> roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME));
				roomWriteGate.awaitSecondWriteLockRequest();
			} else {
				correction = executor.submit(
					() -> roomStatusCorrectionCoordinator.correctRoom(room.getId(), FIXED_TIME));
				roomWriteGate.awaitFirstWriteLock();
				cancellation = executor.submit(
					() -> measureApiRequest(() -> deleteWaitlist(waitingUserId, room.getId())));
				roomWriteGate.awaitSecondWriteLockRequest();
			}
			roomWriteGate.releaseFirstWriteLock();

			TimedApiResult cancellationResult;
			long correctionResponseNanos;
			if (cancellationFirst) {
				cancellationResult = cancellation.get(WAIT_SECONDS, TimeUnit.SECONDS);
				correctionResponseNanos = awaitCorrection(correction);
			} else {
				correctionResponseNanos = awaitCorrection(correction);
				cancellationResult = cancellation.get(WAIT_SECONDS, TimeUnit.SECONDS);
			}
			assertApi(
				cancellationResult.result(),
				cancellationFirst ? 200 : 404,
				cancellationFirst ? null : ErrorCode.WAITLIST_ENTRY_NOT_FOUND);
			StoredSnapshot snapshot = snapshot(room.getId());
			assertEquals(1, snapshot.room().activeParticipantCount());
			assertEquals(ParticipationStatus.ACTIVE, snapshot.participationStatus(activeUserId));
			assertRoomSnapshotInvariant(snapshot);
			roomWriteGate.assertWriteLockProtocol(2);
			logRawArtifact(
				cancellationFirst ? "T2-start-waitlist-cancel-first" : "T2-start-waitlist-correction-first",
				new RawMetrics(
					2,
					cancellationFirst ? 2 : 1,
					cancellationFirst ? 0 : 1,
					0,
					0,
					0,
					2,
					0,
					0,
					0,
					List.of(cancellationResult.responseNanos(), correctionResponseNanos),
					readPostgresCost()));
			return snapshot;
		} finally {
			roomWriteGate.deactivate();
			executor.shutdownNow();
		}
	}

	private StoredSnapshot snapshot(long roomId) {
		StoredRoom room = jdbcTemplate.queryForObject(
			"select active_participant_count, capacity, status, version from rooms where id = ?",
			(rs, rowNumber) -> new StoredRoom(
				rs.getInt("active_participant_count"),
				rs.getInt("capacity"),
				RoomStatus.valueOf(rs.getString("status")),
				rs.getLong("version")),
			roomId);
		List<StoredParticipation> participations = jdbcTemplate.query(
			"select user_id, status from participations where room_id = ? order by user_id",
			(rs, rowNumber) -> new StoredParticipation(rs.getLong("user_id"),
				ParticipationStatus.valueOf(rs.getString("status"))),
			roomId);
		List<StoredWaitlist> waitlists = jdbcTemplate.query(
			"select user_id, status, queue_order from room_waitlists where room_id = ? order by queue_order, user_id",
			(rs, rowNumber) -> new StoredWaitlist(rs.getLong("user_id"),
				RoomWaitlistStatus.valueOf(rs.getString("status")), rs.getLong("queue_order")),
			roomId);
		return new StoredSnapshot(room, participations, waitlists);
	}

	private void assertRoomSnapshotInvariant(StoredSnapshot snapshot) {
		long activeParticipationCount = snapshot.participations().stream()
			.filter(participation -> participation.status() == ParticipationStatus.ACTIVE)
			.count();
		assertEquals(activeParticipationCount, snapshot.room().activeParticipantCount());
		assertTrue(snapshot.room().activeParticipantCount() >= 0);
		assertTrue(snapshot.room().activeParticipantCount() <= snapshot.room().capacity());
		long duplicateActiveUsers = snapshot.participations().stream()
			.filter(participation -> participation.status() == ParticipationStatus.ACTIVE)
			.map(StoredParticipation::userId)
			.distinct()
			.count();
		assertEquals(activeParticipationCount, duplicateActiveUsers);
		List<Long> waitingOrders = snapshot.waitlists().stream()
			.filter(waitlist -> waitlist.status() == RoomWaitlistStatus.WAITING)
			.map(StoredWaitlist::queueOrder)
			.toList();
		assertEquals(waitingOrders.stream().sorted().toList(), waitingOrders);
	}

	private void assertDeadlockRoomResult(
		TimedApiResult result,
		long roomId,
		StoredSnapshot before,
		long userId) {
		StoredSnapshot after = snapshot(roomId);
		assertRoomSnapshotInvariant(after);
		if (result.result().status() == 500) {
			assertEquals(before, after);
			return;
		}
		assertApi(result.result(), 201, null);
		assertEquals(before.room().activeParticipantCount() + 1, after.room().activeParticipantCount());
		assertEquals(before.room().capacity(), after.room().capacity());
		assertEquals(before.participations().size() + 1, after.participations().size());
		assertEquals(ParticipationStatus.ACTIVE, after.participationStatus(userId));
		assertEquals(before.waitlists(), after.waitlists());
		assertTrue(after.room().version() > before.room().version());
	}

	private record ApiResult(int status, String body) {
	}

	private record ManifestValidationResult(String status, String reason) {
	}

	private record RunManifest(
		String resultStatus,
		String candidate,
		String candidateSourceSha,
		String baseSha,
		String headSha,
		String diffDigest,
		String artifactId,
		String artifactDigest,
		String artifactContent,
		String rawArtifactId,
		String rawArtifactDigest,
		String rawArtifactContent,
		String javaVersion,
		String postgresVersion,
		String postgresImage,
		String os,
		String cpu,
		String configuration,
		String fixtureSeed,
		String fixedTime,
		String concurrencyLevel,
		String gate,
		String retryBudget,
		String runnerCommand,
		String dockerVersion,
		String exactPostgresTestCommand,
		List<String> metricFields) {

		private RunManifest withArtifacts(
			Path artifact, Path rawArtifact, String artifactHash, String rawArtifactHash) throws Exception {
			return new RunManifest(
				resultStatus,
				candidate,
				candidateSourceSha,
				baseSha,
				headSha,
				diffDigest,
				artifact.toString(),
				artifactHash,
				Files.readString(artifact, StandardCharsets.UTF_8),
				rawArtifact.toString(),
				rawArtifactHash,
				Files.readString(rawArtifact, StandardCharsets.UTF_8),
				javaVersion,
				postgresVersion,
				postgresImage,
				os,
				cpu,
				configuration,
				fixtureSeed,
				fixedTime,
				concurrencyLevel,
				gate,
				retryBudget,
				runnerCommand,
				dockerVersion,
				exactPostgresTestCommand,
				metricFields);
		}

		private RunManifest withArtifactPaths(Path artifact, Path rawArtifact) {
			return new RunManifest(
				resultStatus,
				candidate,
				candidateSourceSha,
				baseSha,
				headSha,
				diffDigest,
				artifact.toString(),
				artifactDigest,
				artifactContent,
				rawArtifact.toString(),
				rawArtifactDigest,
				rawArtifactContent,
				javaVersion,
				postgresVersion,
				postgresImage,
				os,
				cpu,
				configuration,
				fixtureSeed,
				fixedTime,
				concurrencyLevel,
				gate,
				retryBudget,
				runnerCommand,
				dockerVersion,
				exactPostgresTestCommand,
				metricFields);
		}

		private RunManifest withArtifactDigest(String digest) {
			return new RunManifest(
				resultStatus,
				candidate,
				candidateSourceSha,
				baseSha,
				headSha,
				diffDigest,
				artifactId,
				digest,
				artifactContent,
				rawArtifactId,
				rawArtifactDigest,
				rawArtifactContent,
				javaVersion,
				postgresVersion,
				postgresImage,
				os,
				cpu,
				configuration,
				fixtureSeed,
				fixedTime,
				concurrencyLevel,
				gate,
				retryBudget,
				runnerCommand,
				dockerVersion,
				exactPostgresTestCommand,
				metricFields);
		}

		private RunManifest withHeadSha(String sha) {
			return new RunManifest(
				resultStatus,
				candidate,
				candidateSourceSha,
				baseSha,
				sha,
				diffDigest,
				artifactId,
				artifactDigest,
				artifactContent,
				rawArtifactId,
				rawArtifactDigest,
				rawArtifactContent,
				javaVersion,
				postgresVersion,
				postgresImage,
				os,
				cpu,
				configuration,
				fixtureSeed,
				fixedTime,
				concurrencyLevel,
				gate,
				retryBudget,
				runnerCommand,
				dockerVersion,
				exactPostgresTestCommand,
				metricFields);
		}
	}

	private record StoredRoom(int activeParticipantCount, int capacity, RoomStatus status, long version) {
	}

	private record StoredParticipation(long userId, ParticipationStatus status) {
	}

	private record StoredWaitlist(long userId, RoomWaitlistStatus status, long queueOrder) {
	}

	private record StoredSnapshot(
		StoredRoom room,
		List<StoredParticipation> participations,
		List<StoredWaitlist> waitlists) {

		private ParticipationStatus participationStatus(long userId) {
			return participations.stream()
				.filter(participation -> participation.userId() == userId)
				.map(StoredParticipation::status)
				.findFirst()
				.orElse(null);
		}

		private RoomWaitlistStatus waitlistStatus(long userId) {
			return waitlists.stream()
				.filter(waitlist -> waitlist.userId() == userId)
				.map(StoredWaitlist::status)
				.findFirst()
				.orElse(null);
		}

		private RoomWaitlistStatus waitlistStatusForOnlyEntry() {
			assertEquals(1, waitlists.size());
			return waitlists.get(0).status();
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class RoomLockTestConfiguration {

		@Bean
		RoomWriteGate roomWriteGate(EntityManager entityManager) {
			return new RoomWriteGate(entityManager);
		}

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(FIXED_TIME, ZoneOffset.UTC);
		}

		@Bean(name = "roomLockGatedRepository")
		@Primary
		RoomRepository roomLockGatedRepository(
			@Qualifier("roomRepository") RoomRepository delegate,
			RoomWriteGate roomWriteGate) {
			InvocationHandler handler = new GateAwareRoomRepositoryInvocationHandler(delegate, roomWriteGate);
			return (RoomRepository)Proxy.newProxyInstance(
				RoomRepository.class.getClassLoader(), new Class<?>[] {RoomRepository.class}, handler);
		}
	}

	private static final class GateAwareRoomRepositoryInvocationHandler implements InvocationHandler {

		private final RoomRepository delegate;
		private final RoomWriteGate roomWriteGate;

		private GateAwareRoomRepositoryInvocationHandler(RoomRepository delegate, RoomWriteGate roomWriteGate) {
			this.delegate = delegate;
			this.roomWriteGate = roomWriteGate;
		}

		@Override
		public Object invoke(Object proxy, Method method, Object[] arguments) throws Throwable {
			try {
				roomWriteGate.before(method, arguments);
				Object result = method.invoke(delegate, arguments);
				roomWriteGate.after(method, arguments);
				return result;
			} catch (InvocationTargetException exception) {
				throw exception.getCause();
			}
		}
	}

	static final class RoomWriteGate {

		private final EntityManager entityManager;
		private final AtomicReference<HoldScenario> holdScenario = new AtomicReference<>();
		private final AtomicReference<FlushFaultScenario> flushFaultScenario = new AtomicReference<>();
		private final AtomicReference<OptimisticConflictScenario> optimisticConflictScenario = new AtomicReference<>();
		private final AtomicReference<DeadlockScenario> deadlockScenario = new AtomicReference<>();

		RoomWriteGate(EntityManager entityManager) {
			this.entityManager = entityManager;
		}

		void holdFirstWriteLock(long roomId) {
			holdFirstWriteLock(roomId, false);
		}

		void holdFirstWriteLock(long roomId, boolean serializeSecondLock) {
			assertTrue(holdScenario.compareAndSet(null, new HoldScenario(roomId, serializeSecondLock)));
		}

		void failAfterNextFlush(RuntimeException failure) {
			assertTrue(flushFaultScenario.compareAndSet(null, new FlushFaultScenario(failure)));
		}

		void bumpVersionAfterWriteLock(long roomId, int expectedCalls) {
			assertTrue(expectedCalls > 0);
			assertTrue(optimisticConflictScenario.compareAndSet(
				null,
				new OptimisticConflictScenario(roomId, expectedCalls)));
		}

		void forceDeadlock(long firstRoomId, long secondRoomId) {
			assertTrue(firstRoomId != secondRoomId);
			assertTrue(deadlockScenario.compareAndSet(
				null,
				new DeadlockScenario(firstRoomId, secondRoomId)));
		}

		void before(Method method, Object[] arguments) {
			HoldScenario scenario = holdScenario.get();
			if (scenario == null) {
				return;
			}
			if (method.getName().equals("setLocalWriteLockTimeout")) {
				scenario.timeoutSet.set(true);
			}
			if (isWriteLock(method, arguments)
				&& arguments != null
				&& arguments.length == 1
				&& arguments[0] instanceof Long roomId
				&& scenario.roomId == roomId
				&& scenario.lockRequests.incrementAndGet() == 2) {
				scenario.secondLockRequested.countDown();
				if (scenario.serializeSecondLock) {
					await(scenario.proceedSecondLock, "두 번째 실제 ROOM write lock 진행");
				}
			}
		}

		void after(Method method, Object[] arguments) {
			HoldScenario scenario = holdScenario.get();
			if (scenario != null
				&& method.getName().equals("findByIdForWrite")
				&& arguments != null
				&& arguments.length == 1
				&& arguments[0] instanceof Long roomId
				&& scenario.roomId == roomId) {
				scenario.lockCount.incrementAndGet();
				if (!scenario.timeoutSet.get()) {
					scenario.timeoutBeforeLock.set(false);
				}
				if (scenario.firstLock.compareAndSet(false, true)) {
					scenario.firstLockAcquired.countDown();
					await(scenario.releaseFirstLock, "첫 번째 실제 ROOM write lock 해제");
				}
			}
			if (isWriteLock(method, arguments) && arguments != null && arguments[0] instanceof Long roomId) {
				OptimisticConflictScenario optimisticConflict = optimisticConflictScenario.get();
				if (optimisticConflict != null && optimisticConflict.roomId == roomId) {
					optimisticConflict.bumpVersion(entityManager);
				}
				DeadlockScenario deadlock = deadlockScenario.get();
				if (deadlock != null) {
					deadlock.lockOtherRoom(entityManager, roomId);
				}
			}
			FlushFaultScenario flushFault = flushFaultScenario.get();
			if (flushFault != null && method.getName().equals("flush")
				&& flushFault.calls.getAndIncrement() == 0) {
				throw flushFault.failure;
			}
		}

		void awaitFirstWriteLock() {
			HoldScenario scenario = holdScenario.get();
			assertNotNull(scenario);
			await(scenario.firstLockAcquired, "첫 번째 실제 ROOM write lock 획득");
		}

		void awaitSecondWriteLockRequest() {
			HoldScenario scenario = holdScenario.get();
			assertNotNull(scenario);
			await(scenario.secondLockRequested, "두 번째 실제 ROOM write lock 요청");
		}

		void releaseFirstWriteLock() {
			HoldScenario scenario = holdScenario.get();
			if (scenario != null) {
				scenario.releaseFirstLock.countDown();
			}
		}

		void releaseSecondWriteLockRequest() {
			HoldScenario scenario = holdScenario.get();
			if (scenario != null) {
				scenario.proceedSecondLock.countDown();
			}
		}

		void assertWriteLockProtocol(int expectedLockCount) {
			HoldScenario scenario = holdScenario.get();
			assertNotNull(scenario);
			assertEquals(expectedLockCount, scenario.lockCount.get());
			assertTrue(scenario.timeoutBeforeLock.get());
		}

		void assertOptimisticConflictProtocol(int expectedCalls) {
			OptimisticConflictScenario scenario = optimisticConflictScenario.get();
			assertNotNull(scenario);
			assertEquals(expectedCalls, scenario.calls.get());
			assertEquals(expectedCalls, scenario.transactionIds.size());
			assertEquals(expectedCalls, scenario.transactionIds.stream().distinct().count());
		}

		void assertDeadlockProtocol() {
			DeadlockScenario scenario = deadlockScenario.get();
			assertNotNull(scenario);
			assertEquals(2, scenario.firstLocks.get());
			assertEquals(2, scenario.transactionIds.size());
			assertEquals(2, scenario.transactionIds.stream().distinct().count());
		}

		void assertFlushFaultCallCount(int expectedCalls) {
			FlushFaultScenario scenario = flushFaultScenario.get();
			assertNotNull(scenario);
			assertEquals(expectedCalls, scenario.calls.get());
		}

		void deactivate() {
			releaseFirstWriteLock();
			releaseSecondWriteLockRequest();
			holdScenario.set(null);
			flushFaultScenario.set(null);
			optimisticConflictScenario.set(null);
			deadlockScenario.set(null);
		}

		private boolean isWriteLock(Method method, Object[] arguments) {
			return method.getName().equals("findByIdForWrite")
				&& arguments != null
				&& arguments.length == 1
				&& arguments[0] instanceof Long;
		}

		private static void await(CountDownLatch latch, String phase) {
			try {
				if (!latch.await(WAIT_SECONDS, TimeUnit.SECONDS)) {
					throw new AssertionError(phase + " 대기 시간이 초과되었습니다.");
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				throw new AssertionError(phase + " 중 인터럽트되었습니다.", exception);
			}
		}

		private static final class HoldScenario {

			private final long roomId;
			private final AtomicBoolean firstLock = new AtomicBoolean();
			private final AtomicBoolean timeoutSet = new AtomicBoolean();
			private final AtomicBoolean timeoutBeforeLock = new AtomicBoolean(true);
			private final AtomicInteger lockCount = new AtomicInteger();
			private final AtomicInteger lockRequests = new AtomicInteger();
			private final CountDownLatch firstLockAcquired = new CountDownLatch(1);
			private final CountDownLatch secondLockRequested = new CountDownLatch(1);
			private final boolean serializeSecondLock;
			private final CountDownLatch releaseFirstLock = new CountDownLatch(1);
			private final CountDownLatch proceedSecondLock = new CountDownLatch(1);

			private HoldScenario(long roomId, boolean serializeSecondLock) {
				this.roomId = roomId;
				this.serializeSecondLock = serializeSecondLock;
			}
		}

		private static final class FlushFaultScenario {

			private final RuntimeException failure;
			private final AtomicInteger calls = new AtomicInteger();

			private FlushFaultScenario(RuntimeException failure) {
				this.failure = failure;
			}
		}

		private static final class OptimisticConflictScenario {

			private final long roomId;
			private final int expectedCalls;
			private final AtomicInteger calls = new AtomicInteger();
			private final List<Long> transactionIds = new CopyOnWriteArrayList<>();

			private OptimisticConflictScenario(long roomId, int expectedCalls) {
				this.roomId = roomId;
				this.expectedCalls = expectedCalls;
			}

			private void bumpVersion(EntityManager entityManager) {
				int call = calls.incrementAndGet();
				assertTrue(call <= expectedCalls);
				transactionIds.add(((Number)entityManager
					.createNativeQuery("select txid_current()")
					.getSingleResult()).longValue());
				assertEquals(1, entityManager
					.createNativeQuery("update rooms set version = version + 1 where id = :roomId")
					.setParameter("roomId", roomId)
					.executeUpdate());
			}
		}

		private static final class DeadlockScenario {

			private final long firstRoomId;
			private final long secondRoomId;
			private final AtomicInteger firstLocks = new AtomicInteger();
			private final CountDownLatch bothFirstLocks = new CountDownLatch(2);
			private final List<Long> transactionIds = new CopyOnWriteArrayList<>();

			private DeadlockScenario(long firstRoomId, long secondRoomId) {
				this.firstRoomId = firstRoomId;
				this.secondRoomId = secondRoomId;
			}

			private void lockOtherRoom(EntityManager entityManager, long roomId) {
				if (roomId != firstRoomId && roomId != secondRoomId) {
					return;
				}
				assertTrue(firstLocks.incrementAndGet() <= 2);
				transactionIds.add(((Number)entityManager
					.createNativeQuery("select txid_current()")
					.getSingleResult()).longValue());
				bothFirstLocks.countDown();
				await(bothFirstLocks, "두 deadlock 트랜잭션의 첫 번째 ROOM lock 획득");
				entityManager.createNativeQuery("set local lock_timeout = '0'").executeUpdate();
				assertEquals(
					"0",
					entityManager.createNativeQuery("show lock_timeout").getSingleResult());
				long otherRoomId = roomId == firstRoomId ? secondRoomId : firstRoomId;
				entityManager.createNativeQuery("select id from rooms where id = :roomId for update")
					.setParameter("roomId", otherRoomId)
					.getSingleResult();
			}
		}
	}
}
