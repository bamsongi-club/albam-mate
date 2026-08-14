package cloud.bamsongi.albammate.room.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionProperties;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionScheduler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false",
	"app.room.status-correction.trigger-delay=24h",
	"app.room.status-correction.trigger-jitter=0s",
	"app.room.status-correction.lock-at-most-for=2m",
	"app.room.status-correction.execution-warning-threshold=30s",
	"app.room.status-correction.max-batches-per-run=1001",
	"app.notification.cleanup.interval=24h",
	"app.notification.cleanup.jitter=0s"
})
@Import(RoomStatusCorrectionCandidateMeasurementPostgresTest.CandidateMeasurementConfiguration.class)
class RoomStatusCorrectionCandidateMeasurementPostgresTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-06T00:00:00Z");
	private static final Instant FINISHED_THRESHOLD = REQUEST_TIME.minusSeconds(24 * 60 * 60);
	private static final String BASELINE_FIXTURE_SEED = "ROOM-09c-baseline-v1";
	private static final String WAITING_QUEUE_FIXTURE_SEED = "ROOM-09d-candidate-v1";
	private static final String BASELINE_HOST_EMAIL = "room-09c-baseline@example.com";
	private static final String BASELINE_HOST_NICKNAME = "ROOM-09c 기준선";
	private static final String BASELINE_ROOM_TITLE_PREFIX = "ROOM-09c 기준선";
	private static final String WAITING_QUEUE_ROOM_TITLE_PREFIX = "ROOM-09d 후보 closed_with_waiting";
	private static final int NON_DUE_CLOSED_ROOM_COUNT = 10;
	private static final String CANDIDATE_IMPLEMENTATION_SOURCE_SHA = "8416d3254a3e9e2316bc14745959a2b42dab3c26";
	/**
	 * #383이 단일 일괄 트랜잭션 전략을 고정한 SHA다. 원자료의 {@code baselineSourceSha}는 전략의 유래를 남기는
	 * 값이며 실행 커밋이 아니다. 실행 커밋은 {@code measurementStartEnvironment.gitSha}에 따로 남고, 현행과
	 * 후보 두 경로 모두 그 커밋 하나에서 실행한다. 그 뒤 두 경로에 공통으로 들어온 시작 경계 대기열 종료는
	 * 양쪽에 똑같이 실행되므로 트랜잭션 범위 비교의 변수가 아니다.
	 */
	private static final String BASELINE_IMPLEMENTATION_SOURCE_SHA = "4688316415113b4457f03628d77bdcb7f594c294";
	private static final Path REPORT_DIRECTORY = Path.of("build", "reports", "measurements");
	private static final MeasurementProfile SMALL = new MeasurementProfile("small", 100, 20);
	private static final MeasurementProfile MEDIUM = new MeasurementProfile("medium", 10_000, 2_000);
	private static final MeasurementProfile LARGE = new MeasurementProfile("large", 50_000, 10_000);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

	@Autowired
	private RoomStatusCorrectionScheduler scheduler;

	/** #383이 기준선으로 남긴 전체 Entity 단일 트랜잭션 경로를 같은 세션에서 실행하려고 직접 사용한다. */
	@Autowired
	private RoomStatusCorrectionCoordinator coordinator;

	@Autowired
	private RoomStatusCorrectionProperties properties;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private Environment springEnvironment;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("create extension if not exists pg_stat_statements");
		clearFixture();
		resetProgress();
	}

	@AfterEach
	void tearDown() {
		properties.setCandidateLimit(null);
		clearFixture();
		resetProgress();
	}

	@Test
	void small_후보는_WAITING_없는_동일_fixture를_warm_up_1회와_실측_5회로_기록한다() throws Exception {
		CandidateMeasurementReport report = measureCandidate(SMALL, FixtureType.NO_WAITING, 10,
			candidateReportPath(SMALL));

		assertEquals("SUCCESS", report.outcome());
		assertEquals(SMALL, report.fixture().profile());
		assertBaselineComparableFixture(report.fixture(), SMALL);
		assertEquals(NON_DUE_CLOSED_ROOM_COUNT, report.fixture().nonDueClosedRoomCount());
		assertEquals(10, report.candidateLimit());
		assertEquals(0, report.fixture().waitingPerClosedDueRoom());
		assertSuccessfulSamples(report);
		assertEnvironment(report.measurementStartEnvironment(),
			"small_후보는_WAITING_없는_동일_fixture를_warm_up_1회와_실측_5회로_기록한다", "false");
		assertRecordedMeasurementContract(candidateReportPath(SMALL), SMALL, FixtureType.NO_WAITING, 10,
			"small_후보는_WAITING_없는_동일_fixture를_warm_up_1회와_실측_5회로_기록한다", "false");
		assertTrue(Files.exists(candidateReportPath(SMALL)));
	}

	/**
	 * `ROOM-09d-T1`의 동일 세션 비교다. 소형은 제한 ID `10`(2배치)과 `20`(1배치)으로 나눠, 후보의 비용이 배치 분할에서
	 * 오는지 ROOM별 트랜잭션에서 오는지 갈라 볼 수 있게 한다.
	 */
	@Test
	void 소형은_현행과_후보를_같은_세션에서_각각_warm_up_1회와_실측_5회로_비교한다() throws Exception {
		for (int candidateLimit : List.of(10, 20)) {
			DirectComparisonReport report = measureDirectComparison(
				SMALL, candidateLimit, directComparisonReportPath(SMALL, candidateLimit));

			assertEquals("SUCCESS", report.outcome());
			assertEquals(BASELINE_IMPLEMENTATION_SOURCE_SHA, report.baselineSourceSha());
			assertEquals(CANDIDATE_IMPLEMENTATION_SOURCE_SHA, report.candidateSourceSha());
			assertNull(report.baseline().candidateLimit(), "현행 경로는 제한 ID를 사용하지 않습니다.");
			assertEquals(candidateLimit, report.candidate().candidateLimit());
			assertEquals(1, report.baseline().warmUpRuns().size());
			assertEquals(5, report.baseline().measuredRuns().size());
			assertEquals(1, report.candidate().warmUpRuns().size());
			assertEquals(5, report.candidate().measuredRuns().size());
			assertEquals(3, report.observedChanges().size());
			assertTrue(Files.exists(directComparisonReportPath(SMALL, candidateLimit)));
		}
	}

	@Test
	@EnabledIfSystemProperty(named = "issue390.measurement", matches = "true")
	void 승인_규모는_현행과_후보를_같은_세션에서_제한_ID별로_비교한다() throws Exception {
		for (MeasurementProfile profile : List.of(MEDIUM, LARGE)) {
			for (int candidateLimit : measuredCandidateLimits()) {
				DirectComparisonReport report = measureDirectComparison(
					profile, candidateLimit, directComparisonReportPath(profile, candidateLimit));

				assertEquals("SUCCESS", report.outcome());
				assertEquals(profile, report.fixture().profile());
				assertNull(report.baseline().candidateLimit());
				assertEquals(candidateLimit, report.candidate().candidateLimit());
				assertEquals(5, report.baseline().measuredRuns().size());
				assertEquals(5, report.candidate().measuredRuns().size());
			}
		}
	}

	@Test
	@EnabledIfSystemProperty(named = "issue390.measurement", matches = "true")
	void 승인_규모_후보는_명시적_속성에서만_10_100_1000_후보를_기록한다() throws Exception {
		for (MeasurementProfile profile : List.of(MEDIUM, LARGE)) {
			for (int candidateLimit : List.of(10, 100, 1_000)) {
				CandidateMeasurementReport report = measureCandidate(
					profile, FixtureType.NO_WAITING, candidateLimit, candidateReportPath(profile, candidateLimit));

				assertEquals("SUCCESS", report.outcome());
				assertEquals(profile, report.fixture().profile());
				assertBaselineComparableFixture(report.fixture(), profile);
				assertEquals(candidateLimit, report.candidateLimit());
				assertSuccessfulSamples(report);
				assertEnvironment(report.measurementStartEnvironment(),
					"승인_규모_후보는_명시적_속성에서만_10_100_1000_후보를_기록한다", "true");
				assertRecordedMeasurementContract(candidateReportPath(profile, candidateLimit), profile,
					FixtureType.NO_WAITING,
					candidateLimit, "승인_규모_후보는_명시적_속성에서만_10_100_1000_후보를_기록한다", "true");
			}
		}
	}

	@Test
	void CLOSED_due_ROOM마다_WAITING_10명을_둔_별도_fixture의_후보_종료_비용을_기록한다() throws Exception {
		WaitingQueueMeasurementReport report = measureWaitingQueue(SMALL, 10, waitingQueueReportPath(SMALL));

		assertEquals("SUCCESS", report.outcome());
		assertEquals(SMALL, report.fixture().profile());
		assertEquals(10, report.candidateLimit());
		assertEquals(10, report.fixture().waitingPerClosedDueRoom());
		assertEquals(NON_DUE_CLOSED_ROOM_COUNT, report.fixture().nonDueClosedRoomCount());
		assertEquals(1, report.warmUpRuns().size());
		assertEquals(5, report.measuredRuns().size());
		assertTrue(report.measuredRuns().stream().allMatch(
			run -> run.candidateCount() == SMALL.dueRoomCount() && run.successCount() == SMALL.dueRoomCount()));
		assertEnvironment(report.measurementStartEnvironment(),
			"CLOSED_due_ROOM마다_WAITING_10명을_둔_별도_fixture의_후보_종료_비용을_기록한다", "false");
		assertRecordedMeasurementContract(waitingQueueReportPath(SMALL), SMALL, FixtureType.CLOSED_WITH_WAITING, 10,
			"CLOSED_due_ROOM마다_WAITING_10명을_둔_별도_fixture의_후보_종료_비용을_기록한다", "false");
		assertTrue(Files.exists(waitingQueueReportPath(SMALL)));
	}

	private CandidateMeasurementReport measureCandidate(
		MeasurementProfile profile, FixtureType fixtureType, int candidateLimit, Path reportPath) throws Exception {
		MeasurementEnvironment environment = environment(profile, fixtureType, candidateLimit,
			ReportKind.CANDIDATE_ONLY);
		List<MeasurementRun> warmUpRuns = new ArrayList<>();
		List<MeasurementRun> measuredRuns = new ArrayList<>();
		try {
			warmUpRuns.add(executeRun(profile, fixtureType, candidateLimit, "warm-up", 1));
			for (int iteration = 1; iteration <= 5; iteration++) {
				measuredRuns.add(executeRun(profile, fixtureType, candidateLimit, "measured", iteration));
			}
			CandidateMeasurementReport report = CandidateMeasurementReport.success(
				environment, fixture(profile, fixtureType), candidateLimit, warmUpRuns, measuredRuns,
				summary(measuredRuns));
			writeReport(reportPath, report);
			return report;
		} catch (MeasurementRunFailureException exception) {
			writeReport(reportPath, CandidateMeasurementReport.runFailure(
				environment, fixture(profile, fixtureType), candidateLimit, warmUpRuns, measuredRuns,
				exception.partialRun(), exception.getCause().getClass().getName()));
			throw exception;
		} finally {
			clearFixture();
			resetProgress();
		}
	}

	/**
	 * `ROOM-09d-T1`이 요구하는 동일 세션 비교다. 같은 호스트·Java·PostgreSQL·설정에서 현행과 후보를 각각
	 * warm-up 1회와 실측 5회로 실행한다. 두 경로를 번갈아 실행해 한쪽만 특정 시간대의 호스트 부하를 받지 않게 한다.
	 */
	private DirectComparisonReport measureDirectComparison(
		MeasurementProfile profile, int candidateLimit, Path reportPath) throws Exception {
		MeasurementEnvironment environment = environment(profile, FixtureType.NO_WAITING, candidateLimit,
			ReportKind.DIRECT_COMPARISON);
		List<MeasurementRun> baselineWarmUp = new ArrayList<>();
		List<MeasurementRun> baselineMeasured = new ArrayList<>();
		List<MeasurementRun> candidateWarmUp = new ArrayList<>();
		List<MeasurementRun> candidateMeasured = new ArrayList<>();
		try {
			baselineWarmUp.add(
				executeRun(profile, FixtureType.NO_WAITING, ProcessingPath.CURRENT_BASELINE, candidateLimit,
					"warm-up", 1));
			candidateWarmUp.add(
				executeRun(profile, FixtureType.NO_WAITING, ProcessingPath.BOUNDED_CANDIDATE, candidateLimit,
					"warm-up", 1));
			for (int iteration = 1; iteration <= 5; iteration++) {
				baselineMeasured.add(
					executeRun(profile, FixtureType.NO_WAITING, ProcessingPath.CURRENT_BASELINE, candidateLimit,
						"measured", iteration));
				candidateMeasured.add(
					executeRun(profile, FixtureType.NO_WAITING, ProcessingPath.BOUNDED_CANDIDATE, candidateLimit,
						"measured", iteration));
			}
			SeriesSummary baselineSummary = summary(baselineMeasured);
			SeriesSummary candidateSummary = summary(candidateMeasured);
			DirectComparisonReport report = new DirectComparisonReport("SUCCESS", environment,
				fixture(profile, FixtureType.NO_WAITING), candidateLimit, BASELINE_IMPLEMENTATION_SOURCE_SHA,
				CANDIDATE_IMPLEMENTATION_SOURCE_SHA,
				new PathSeries("current-baseline", null, baselineWarmUp, baselineMeasured, baselineSummary),
				new PathSeries("bounded-candidate", candidateLimit, candidateWarmUp, candidateMeasured,
					candidateSummary),
				observedChanges(baselineSummary, candidateSummary));
			writeReport(reportPath, report);
			return report;
		} finally {
			clearFixture();
			resetProgress();
		}
	}

	/** 변화율은 현행 중앙값을 분모로 한 관찰값이며 합격선이 아니다. */
	private List<ObservedChange> observedChanges(SeriesSummary baseline, SeriesSummary candidate) {
		return List.of(
			new ObservedChange("medianCallElapsedNanos", baseline.medianCallElapsedNanos(),
				candidate.medianCallElapsedNanos(),
				percentChange(baseline.medianCallElapsedNanos(), candidate.medianCallElapsedNanos())),
			new ObservedChange("medianThroughputPerSecond", baseline.medianThroughputPerSecond(),
				candidate.medianThroughputPerSecond(),
				percentChange(baseline.medianThroughputPerSecond(), candidate.medianThroughputPerSecond())),
			new ObservedChange("medianDatabaseExecutionTimeMs", baseline.medianDatabaseExecutionTimeMs(),
				candidate.medianDatabaseExecutionTimeMs(),
				percentChange(baseline.medianDatabaseExecutionTimeMs(), candidate.medianDatabaseExecutionTimeMs())));
	}

	private double percentChange(double baselineValue, double candidateValue) {
		return (candidateValue - baselineValue) / baselineValue * 100;
	}

	private WaitingQueueMeasurementReport measureWaitingQueue(
		MeasurementProfile profile, int candidateLimit, Path reportPath) throws Exception {
		MeasurementEnvironment environment = environment(profile, FixtureType.CLOSED_WITH_WAITING, candidateLimit,
			ReportKind.CANDIDATE_ONLY);
		List<MeasurementRun> warmUpRuns = new ArrayList<>();
		List<MeasurementRun> measuredRuns = new ArrayList<>();
		try {
			warmUpRuns.add(executeRun(profile, FixtureType.CLOSED_WITH_WAITING, candidateLimit, "warm-up", 1));
			for (int iteration = 1; iteration <= 5; iteration++) {
				measuredRuns
					.add(executeRun(profile, FixtureType.CLOSED_WITH_WAITING, candidateLimit, "measured", iteration));
			}
			WaitingQueueMeasurementReport report = WaitingQueueMeasurementReport.success(
				environment, fixture(profile, FixtureType.CLOSED_WITH_WAITING), candidateLimit, warmUpRuns,
				measuredRuns, summary(measuredRuns));
			writeReport(reportPath, report);
			return report;
		} catch (MeasurementRunFailureException exception) {
			writeReport(reportPath, WaitingQueueMeasurementReport.runFailure(
				environment, fixture(profile, FixtureType.CLOSED_WITH_WAITING), candidateLimit, warmUpRuns,
				measuredRuns,
				exception.partialRun(), exception.getCause().getClass().getName()));
			throw exception;
		} finally {
			clearFixture();
			resetProgress();
		}
	}

	private MeasurementRun executeRun(
		MeasurementProfile profile, FixtureType fixtureType, int candidateLimit, String phase, int iteration) {
		return executeRun(profile, fixtureType, ProcessingPath.BOUNDED_CANDIDATE, candidateLimit, phase, iteration);
	}

	/**
	 * 같은 커밋에서 같은 fixture를 두 경로로 실행한다. 비교 변수는 트랜잭션 범위 하나다.
	 * {@code CURRENT_BASELINE}은 #383이 남긴 전체 Entity 단일 트랜잭션 경로이고 제한 ID를 사용하지 않는다.
	 * {@code BOUNDED_CANDIDATE}는 #382가 병합한 제한 ID 순회·ROOM별 독립 트랜잭션 경로다. 두 경로를 한 측정
	 * 세션 안에서 번갈아 실행해야 호스트 부하 차이를 구현 차이와 섞지 않는다.
	 */
	private MeasurementRun executeRun(MeasurementProfile profile, FixtureType fixtureType, ProcessingPath path,
		int candidateLimit, String phase, int iteration) {
		Long startedAtNanos = null;
		int initialDueRoomCount = profile.dueRoomCount();
		try {
			clearFixture();
			resetProgress();
			seedFixture(profile, fixtureType);
			initialDueRoomCount = remainingDueRoomCount(fixtureType);
			assertEquals(profile.dueRoomCount(), initialDueRoomCount, "seed한 due 집합이 profile과 같아야 합니다.");
			jdbcTemplate.execute("select pg_stat_statements_reset()");
			startedAtNanos = runProcessingPath(path, candidateLimit);
			long elapsedNanos = System.nanoTime() - startedAtNanos;
			// 사후 검증 SELECT가 통계에 섞이지 않도록 처리 반환 직후에 DB 비용을 먼저 확보한다.
			// 그래야 실행시간과 DB 비용이 같은 구간을 가리킨다.
			DatabaseCost databaseCost = databaseCost();
			int remainingDueRoomCount = remainingDueRoomCount(fixtureType);
			int successCount = initialDueRoomCount - remainingDueRoomCount;
			assertEquals(0, remainingDueRoomCount, "동일 초기 due 집합을 끝까지 처리해야 합니다.");
			return new MeasurementRun(phase, iteration, initialDueRoomCount, successCount, 0, elapsedNanos,
				elapsedNanos,
				throughputPerSecond(successCount, elapsedNanos), databaseCost);
		} catch (RuntimeException | AssertionError exception) {
			Long elapsedNanos = startedAtNanos == null ? null : System.nanoTime() - startedAtNanos;
			MeasurementRun partialRun = new MeasurementRun(phase, iteration, initialDueRoomCount, null, null,
				elapsedNanos, elapsedNanos, null, captureDatabaseCost());
			throw new MeasurementRunFailureException(partialRun, exception);
		}
	}

	/** 측정 구간을 최소로 유지하려고 경로 분기와 설정 주입을 끝낸 직후의 시작 시각을 돌려준다. */
	private long runProcessingPath(ProcessingPath path, int candidateLimit) {
		if (path == ProcessingPath.CURRENT_BASELINE) {
			properties.setCandidateLimit(null);
			long startedAtNanos = System.nanoTime();
			coordinator.correctDueRooms(REQUEST_TIME);
			return startedAtNanos;
		}
		properties.setCandidateLimit(candidateLimit);
		long startedAtNanos = System.nanoTime();
		scheduler.correctDueRooms();
		return startedAtNanos;
	}

	private void seedFixture(MeasurementProfile profile, FixtureType fixtureType) {
		if (fixtureType == FixtureType.NO_WAITING) {
			seedNoWaitingRooms(profile, insertBaselineHost());
			return;
		}
		long hostUserId = insertUser("host");
		for (int index = 0; index < profile.roomCount(); index++) {
			boolean due = index < profile.dueRoomCount();
			boolean closedDue = due && index % 2 == 1;
			boolean nonDueClosed = !due && index < profile.dueRoomCount() + NON_DUE_CLOSED_ROOM_COUNT;
			String title = waitingQueueRoomTitle(index);
			Instant startAt = due ? REQUEST_TIME
				: (nonDueClosed ? FINISHED_THRESHOLD.plusSeconds(1) : REQUEST_TIME.plusSeconds(24 * 60 * 60));
			insertRoom(hostUserId, title, startAt, closedDue || nonDueClosed);
			if (closedDue) {
				Long roomId = jdbcTemplate.queryForObject("select id from rooms where title = ?", Long.class, title);
				seedWaitingRows(roomId, index);
			}
		}
	}

	private void seedNoWaitingRooms(MeasurementProfile profile, long hostUserId) {
		jdbcTemplate.batchUpdate("""
			insert into rooms(host_user_id, room_type, title, experience_level, is_rulemaster_led, region,
				capacity, active_participant_count, start_at, place, status, version, created_at, updated_at)
			values (?, 'PERSON_FOCUSED', ?, 'ALL_LEVELS', false, '홍대', 4, 0, ?, '측정 장소', ?, 0, ?, ?)
			""", new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
				boolean due = index < profile.dueRoomCount();
				boolean closedDue = due && index % 2 == 1;
				boolean nonDueClosed = !due && index < profile.dueRoomCount() + NON_DUE_CLOSED_ROOM_COUNT;
				Instant startAt = due
					? (closedDue ? FINISHED_THRESHOLD : REQUEST_TIME)
					: (nonDueClosed ? FINISHED_THRESHOLD.plusSeconds(1) : REQUEST_TIME.plusSeconds(24 * 60 * 60));
				statement.setLong(1, hostUserId);
				statement.setString(2, baselineRoomTitle(index, nonDueClosed));
				statement.setTimestamp(3, Timestamp.from(startAt));
				statement.setString(4, closedDue || nonDueClosed ? "CLOSED" : "RECRUITING");
				statement.setTimestamp(5, Timestamp.from(REQUEST_TIME));
				statement.setTimestamp(6, Timestamp.from(REQUEST_TIME));
			}

			@Override
			public int getBatchSize() {
				return profile.roomCount();
			}
		});
	}

	private void insertRoom(long hostUserId, String title, Instant startAt, boolean closed) {
		jdbcTemplate.update("""
			insert into rooms(host_user_id, room_type, title, experience_level, is_rulemaster_led, region,
				capacity, active_participant_count, start_at, place, status, version, created_at, updated_at)
			values (?, 'PERSON_FOCUSED', ?, 'ALL_LEVELS', false, '홍대', 4, 0, ?, '측정 장소', ?, 0, ?, ?)
			""", hostUserId, title, Timestamp.from(startAt), closed ? "CLOSED" : "RECRUITING",
			Timestamp.from(REQUEST_TIME), Timestamp.from(REQUEST_TIME));
	}

	private void seedWaitingRows(long roomId, int roomIndex) {
		for (int waitingIndex = 0; waitingIndex < 10; waitingIndex++) {
			long userId = insertUser("waiting-" + roomIndex + "-" + waitingIndex);
			jdbcTemplate.update("""
				insert into room_waitlists(room_id, user_id, status, queue_order, queued_at, created_at, updated_at)
				values (?, ?, 'WAITING', ?, ?, ?, ?)
				""", roomId, userId, waitingIndex + 1L, Timestamp.from(REQUEST_TIME),
				Timestamp.from(REQUEST_TIME), Timestamp.from(REQUEST_TIME));
		}
	}

	private long insertBaselineHost() {
		jdbcTemplate.update(
			"insert into users(email, password_hash, nickname, created_at, updated_at) values (?, 'postgres-test-hash', ?, ?, ?)",
			BASELINE_HOST_EMAIL, BASELINE_HOST_NICKNAME, Timestamp.from(REQUEST_TIME), Timestamp.from(REQUEST_TIME));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, BASELINE_HOST_EMAIL);
	}

	private long insertUser(String suffix) {
		String email = "room-09d-" + WAITING_QUEUE_FIXTURE_SEED + "-" + suffix + "@example.com";
		jdbcTemplate.update(
			"insert into users(email, password_hash, nickname, created_at, updated_at) values (?, 'postgres-test-hash', ?, ?, ?)",
			email, "ROOM-09d 측정", Timestamp.from(REQUEST_TIME), Timestamp.from(REQUEST_TIME));
		return jdbcTemplate.queryForObject("select id from users where email = ?", Long.class, email);
	}

	private int remainingDueRoomCount(FixtureType fixtureType) {
		Integer count = jdbcTemplate.queryForObject("""
				select count(*)
				from rooms r
				where r.title like ?
				  and (
				    (r.status = 'RECRUITING' and r.start_at <= ?)
				    or (r.status = 'CLOSED' and r.start_at + interval '24 hours' <= ?)
				    or (? = 'CLOSED_WITH_WAITING' and r.status = 'CLOSED' and r.start_at <= ?
				        and exists (select 1 from room_waitlists w where w.room_id = r.id and w.status = 'WAITING'))
				  )
			""", Integer.class, fixtureTitlePrefix(fixtureType) + "%", Timestamp.from(REQUEST_TIME),
			Timestamp.from(REQUEST_TIME), fixtureType.name(),
			Timestamp.from(REQUEST_TIME));
		return count == null ? 0 : count;
	}

	private MeasurementFixture fixture(MeasurementProfile profile, FixtureType fixtureType) {
		String seed = fixtureSeed(fixtureType);
		String dataIdentifier = fixtureType == FixtureType.NO_WAITING
			? seed + ":" + profile.name() + ":" + profile.roomCount() + ":" + profile.dueRoomCount()
			: seed + ":" + profile.name() + ":" + fixtureType.name();
		return new MeasurementFixture(profile, profile.roomCount(), profile.dueRoomCount(),
			profile.roomCount() - profile.dueRoomCount(), NON_DUE_CLOSED_ROOM_COUNT,
			fixtureType == FixtureType.CLOSED_WITH_WAITING ? 10 : 0, REQUEST_TIME.toString(), seed, dataIdentifier);
	}

	private MeasurementEnvironment environment(MeasurementProfile profile, FixtureType fixtureType,
		int candidateLimit, ReportKind reportKind) {
		Runtime runtime = Runtime.getRuntime();
		return new MeasurementEnvironment(gitSha(), CANDIDATE_IMPLEMENTATION_SOURCE_SHA,
			System.getProperty("java.version"),
			jdbcTemplate.queryForObject("show server_version", String.class), System.getProperty("os.name"),
			runtime.availableProcessors(), runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(),
			Map.ofEntries(
				Map.entry("postgresImage", POSTGRES.getDockerImageName()), Map.entry("dockerVersion", dockerVersion()),
				Map.entry("sharedPreloadLibraries",
					jdbcTemplate.queryForObject("show shared_preload_libraries", String.class)),
				Map.entry("requestTime", REQUEST_TIME.toString()), Map.entry("fixtureSeed", fixtureSeed(fixtureType)),
				Map.entry("profile", profile.name()), Map.entry("fixtureType", fixtureType.name()),
				Map.entry("candidateLimit", String.valueOf(candidateLimit)),
				Map.entry("executionCommand", executionCommand(profile, fixtureType, reportKind)),
				Map.entry("measurementSystemProperty", measurementSystemProperty(profile, fixtureType, reportKind)),
				Map.entry("measuredCandidateLimits",
					measuredCandidateLimits().stream().map(String::valueOf).collect(Collectors.joining(","))),
				Map.entry("issue390.measurement", System.getProperty("issue390.measurement", "false")),
				Map.entry("springTaskSchedulingEnabled",
					springEnvironment.getProperty("spring.task.scheduling.enabled", "true")),
				Map.entry("notificationRelayEnabled",
					springEnvironment.getProperty("app.notification.relay.enabled", "true")),
				Map.entry("chatRetentionEnabled", springEnvironment.getProperty("app.chat.retention.enabled", "true")),
				Map.entry("roomStatusCorrectionTriggerDelay",
					springEnvironment.getProperty("app.room.status-correction.trigger-delay", "15m")),
				Map.entry("roomStatusCorrectionTriggerJitter",
					springEnvironment.getProperty("app.room.status-correction.trigger-jitter", "3m")),
				Map.entry("roomStatusCorrectionLockAtMostFor",
					springEnvironment.getProperty("app.room.status-correction.lock-at-most-for", "2m")),
				Map.entry("roomStatusCorrectionExecutionWarningThreshold",
					springEnvironment.getProperty("app.room.status-correction.execution-warning-threshold", "30s")),
				Map.entry("roomStatusCorrectionMaxBatchesPerRun",
					springEnvironment.getProperty("app.room.status-correction.max-batches-per-run", "1001")),
				Map.entry("notificationCleanupInterval",
					springEnvironment.getProperty("app.notification.cleanup.interval", "1h")),
				Map.entry("notificationCleanupJitter",
					springEnvironment.getProperty("app.notification.cleanup.jitter", "5m"))));
	}

	private void assertSuccessfulSamples(CandidateMeasurementReport report) {
		assertEquals(1, report.warmUpRuns().size());
		assertEquals(5, report.measuredRuns().size());
		assertTrue(report.measuredRuns().stream()
			.allMatch(run -> run.candidateCount() == report.fixture().dueRoomCount()
				&& run.successCount() == report.fixture().dueRoomCount() && run.failureCount() == 0
				&& run.databaseCost().totalExecutionTimeMs() > 0));
	}

	private void assertBaselineComparableFixture(MeasurementFixture fixture, MeasurementProfile profile) {
		assertEquals(BASELINE_FIXTURE_SEED, fixture.seed());
		assertEquals(BASELINE_FIXTURE_SEED + ":" + profile.name() + ":" + profile.roomCount() + ":"
			+ profile.dueRoomCount(), fixture.dataIdentifier());
	}

	private void assertEnvironment(
		MeasurementEnvironment environment, String testMethodName, String measurementProperty) {
		assertNotEquals("UNAVAILABLE", environment.configuration().get("dockerVersion"));
		assertTrue(environment.configuration().get("executionCommand").contains(testMethodName));
		assertEquals(measurementProperty, environment.configuration().get("issue390.measurement"));
		assertEquals("false", environment.configuration().get("springTaskSchedulingEnabled"));
		assertEquals("false", environment.configuration().get("notificationRelayEnabled"));
		assertEquals("false", environment.configuration().get("chatRetentionEnabled"));
		assertEquals("24h", environment.configuration().get("roomStatusCorrectionTriggerDelay"));
		assertEquals("0s", environment.configuration().get("roomStatusCorrectionTriggerJitter"));
		assertEquals("2m", environment.configuration().get("roomStatusCorrectionLockAtMostFor"));
		assertEquals("30s", environment.configuration().get("roomStatusCorrectionExecutionWarningThreshold"));
		assertEquals("1001", environment.configuration().get("roomStatusCorrectionMaxBatchesPerRun"));
		assertEquals("24h", environment.configuration().get("notificationCleanupInterval"));
		assertEquals("0s", environment.configuration().get("notificationCleanupJitter"));
		assertTrue(environment.startUsedHeapBytes() >= 0);
		assertTrue(environment.maxHeapBytes() > 0);
	}

	private void assertRecordedMeasurementContract(
		Path reportPath, MeasurementProfile profile, FixtureType fixtureType, int candidateLimit,
		String testMethodName, String measurementProperty) throws Exception {
		JsonNode report = objectMapper.readTree(Files.readString(reportPath));
		assertEquals(fixtureSeed(fixtureType), report.path("fixture").path("seed").asText(), "보고서 fixture seed");
		assertEquals(fixture(profile, fixtureType).dataIdentifier(),
			report.path("fixture").path("dataIdentifier").asText(),
			"보고서 fixture dataIdentifier");
		assertEquals(candidateLimit, report.path("candidateLimit").asInt(), "보고서 candidate limit");
		JsonNode configuration = report.path("measurementStartEnvironment").path("configuration");
		assertEquals(executionCommand(profile, fixtureType, ReportKind.CANDIDATE_ONLY),
			configuration.path("executionCommand").asText(),
			"보고서 실행 명령");
		assertTrue(configuration.path("executionCommand").asText().contains(testMethodName));
		assertEquals(String.valueOf(candidateLimit), configuration.path("candidateLimit").asText(),
			"보고서 설정 candidate limit");
		assertEquals(measurementProperty, configuration.path("issue390.measurement").asText());
	}

	private SeriesSummary summary(List<MeasurementRun> measuredRuns) {
		List<Long> callElapsed = measuredRuns.stream().map(MeasurementRun::callElapsedNanos).sorted().toList();
		List<Long> wholeTurnElapsed = measuredRuns.stream().map(MeasurementRun::wholeTurnElapsedNanos).sorted()
			.toList();
		List<Double> throughput = measuredRuns.stream().map(MeasurementRun::throughputPerSecond).sorted().toList();
		List<Double> databaseExecution = measuredRuns.stream().map(run -> run.databaseCost().totalExecutionTimeMs())
			.sorted().toList();
		return new SeriesSummary(callElapsed.getFirst(), callElapsed.get(2), callElapsed.getLast(),
			wholeTurnElapsed.getFirst(), wholeTurnElapsed.get(2), wholeTurnElapsed.getLast(),
			throughput.getFirst(), throughput.get(2), throughput.getLast(),
			databaseExecution.getFirst(), databaseExecution.get(2), databaseExecution.getLast());
	}

	private double throughputPerSecond(int successCount, long elapsedNanos) {
		return successCount * 1_000_000_000d / elapsedNanos;
	}

	private DatabaseCost databaseCost() {
		return jdbcTemplate.queryForObject("""
			select coalesce(sum(calls), 0), coalesce(sum(total_exec_time), 0), coalesce(sum(rows), 0),
			       coalesce(sum(shared_blks_hit), 0), coalesce(sum(shared_blks_read), 0)
			from pg_stat_statements
			where dbid = (select oid from pg_database where datname = current_database())
			  and query not like 'select pg_stat_statements_reset%'
			""", (resultSet, rowNum) -> new DatabaseCost(resultSet.getLong(1), resultSet.getDouble(2),
			resultSet.getLong(3), resultSet.getLong(4), resultSet.getLong(5)));
	}

	private DatabaseCost captureDatabaseCost() {
		try {
			return databaseCost();
		} catch (RuntimeException ignored) {
			return new DatabaseCost(0, 0, 0, 0, 0);
		}
	}

	private String dockerVersion() {
		try {
			Process process = new ProcessBuilder("docker", "version", "--format", "{{.Server.Version}}")
				.redirectErrorStream(true).start();
			String version = new String(process.getInputStream().readAllBytes()).trim();
			return process.waitFor() == 0 && !version.isBlank() ? version : "UNAVAILABLE";
		} catch (Exception ignored) {
			return "UNAVAILABLE";
		}
	}

	private String gitSha() {
		try {
			Process process = new ProcessBuilder("git", "rev-parse", "HEAD").redirectErrorStream(true).start();
			return process.waitFor() == 0 ? new String(process.getInputStream().readAllBytes()).trim() : "UNAVAILABLE";
		} catch (Exception ignored) {
			return "UNAVAILABLE";
		}
	}

	private void clearFixture() {
		jdbcTemplate.update("""
			delete from room_waitlists
			where room_id in (
				select id from rooms where title like ? or title like ?
			)
			""", BASELINE_ROOM_TITLE_PREFIX + "%", WAITING_QUEUE_ROOM_TITLE_PREFIX + "%");
		jdbcTemplate.update("delete from rooms where title like ? or title like ?", BASELINE_ROOM_TITLE_PREFIX + "%",
			WAITING_QUEUE_ROOM_TITLE_PREFIX + "%");
		jdbcTemplate.update("delete from users where email = ?", BASELINE_HOST_EMAIL);
		jdbcTemplate.update("delete from users where email like ?",
			"room-09d-" + WAITING_QUEUE_FIXTURE_SEED + "-%@example.com");
	}

	private void resetProgress() {
		jdbcTemplate.update("""
			update room_status_correction_progress
			set turn_cutoff = null, cursor_due_at = null, cursor_room_id = null,
			    progress_version = 0, execution_generation = 0
			where job_name = 'room-status-correction'
			""");
	}

	private String baselineRoomTitle(int index, boolean nonDueClosed) {
		return nonDueClosed
			? BASELINE_ROOM_TITLE_PREFIX + " non-due-closed " + index
			: BASELINE_ROOM_TITLE_PREFIX + " " + index;
	}

	private String waitingQueueRoomTitle(int index) {
		return WAITING_QUEUE_ROOM_TITLE_PREFIX + " " + index;
	}

	private String fixtureTitlePrefix(FixtureType fixtureType) {
		return fixtureType == FixtureType.NO_WAITING ? BASELINE_ROOM_TITLE_PREFIX : WAITING_QUEUE_ROOM_TITLE_PREFIX;
	}

	private String fixtureSeed(FixtureType fixtureType) {
		return fixtureType == FixtureType.NO_WAITING ? BASELINE_FIXTURE_SEED : WAITING_QUEUE_FIXTURE_SEED;
	}

	/**
	 * 보고서를 만든 환경에서 그대로 붙여 넣을 수 있도록 실행 OS의 wrapper와 셸 문법으로 기록한다. 측정 gate 속성은
	 * Gradle `-D` 인자로는 포크된 테스트 JVM에 닿지 않으므로 {@code JAVA_TOOL_OPTIONS}로 전달한다. 셸에 의존하지 않는
	 * 소비자는 이 문자열 대신 {@code measurementSystemProperty} 필드를 읽는다.
	 */
	private String executionCommand(MeasurementProfile profile, FixtureType fixtureType, ReportKind reportKind) {
		String selector = measurementSelector(profile, fixtureType, reportKind);
		boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
		String wrapper = windows ? ".\\gradlew.bat" : "./gradlew";
		String gradleCommand = wrapper + " postgresTest --no-daemon --tests \"" + selector + "\" --rerun --fail-fast";
		String systemProperty = measurementSystemProperty(profile, fixtureType, reportKind);
		if (systemProperty.isEmpty()) {
			return gradleCommand;
		}
		return windows
			? "$env:JAVA_TOOL_OPTIONS = '-D" + systemProperty + "'; " + gradleCommand
			: "JAVA_TOOL_OPTIONS='-D" + systemProperty + "' " + gradleCommand;
	}

	/** 셸 문법 없이 재현 조건만 읽을 수 있도록 gate 속성을 별도로 남긴다. 필요 없으면 빈 문자열이다. */
	private String measurementSystemProperty(MeasurementProfile profile, FixtureType fixtureType,
		ReportKind reportKind) {
		return profile == SMALL || fixtureType == FixtureType.CLOSED_WITH_WAITING ? "" : "issue390.measurement=true";
	}

	private String measurementSelector(MeasurementProfile profile, FixtureType fixtureType, ReportKind reportKind) {
		String className = "cloud.bamsongi.albammate.room.measurement."
			+ "RoomStatusCorrectionCandidateMeasurementPostgresTest.";
		if (fixtureType == FixtureType.CLOSED_WITH_WAITING) {
			return className + "CLOSED_due_ROOM마다_WAITING_10명을_둔_별도_fixture의_후보_종료_비용을_기록한다";
		}
		if (reportKind == ReportKind.DIRECT_COMPARISON) {
			return profile == SMALL
				? className + "소형은_현행과_후보를_같은_세션에서_각각_warm_up_1회와_실측_5회로_비교한다"
				: className + "승인_규모는_현행과_후보를_같은_세션에서_제한_ID별로_비교한다";
		}
		if (profile == SMALL) {
			return className + "small_후보는_WAITING_없는_동일_fixture를_warm_up_1회와_실측_5회로_기록한다";
		}
		return className + "승인_규모_후보는_명시적_속성에서만_10_100_1000_후보를_기록한다";
	}

	private Path candidateReportPath(MeasurementProfile profile) {
		return candidateReportPath(profile, 10);
	}

	private Path candidateReportPath(MeasurementProfile profile, int candidateLimit) {
		return REPORT_DIRECTORY.resolve("room-09d-candidate-" + profile.name() + "-limit-" + candidateLimit + ".json");
	}

	/**
	 * 승인 규모의 동일 세션 비교는 한 제한 ID마다 두 경로를 12회 실행하므로 전체 후보를 한 번에 재면 오래 걸린다.
	 * 기본값은 승인된 후보 전체이고, 부분 재측정이 필요할 때만 {@code issue390.candidateLimits}로 좁힌다.
	 * 실제로 실행한 목록은 환경 메타데이터의 {@code measuredCandidateLimits}에 남는다. 부분 실행 산출물이
	 * 최종 보존분이 되는 것은 {@code scripts/measurements/room09-measurement-report.mjs}의 조합 manifest가 막는다.
	 */
	private List<Integer> measuredCandidateLimits() {
		String configured = System.getProperty("issue390.candidateLimits", "").trim();
		if (configured.isEmpty()) {
			return List.of(10, 100, 1_000);
		}
		return Stream.of(configured.split(","))
			.map(String::trim)
			.filter(value -> !value.isEmpty())
			.map(Integer::parseInt)
			.toList();
	}

	private Path directComparisonReportPath(MeasurementProfile profile, int candidateLimit) {
		return REPORT_DIRECTORY
			.resolve("room-09d-direct-comparison-" + profile.name() + "-limit-" + candidateLimit + ".json");
	}

	private Path waitingQueueReportPath(MeasurementProfile profile) {
		return REPORT_DIRECTORY.resolve("room-09d-waiting-queue-" + profile.name() + "-limit-10.json");
	}

	private void writeReport(Path reportPath, Object report) throws Exception {
		Files.createDirectories(reportPath.getParent());
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
	}

	private enum FixtureType {
		NO_WAITING,
		CLOSED_WITH_WAITING
	}

	/** #383 기준선 경로와 #382 후보 경로를 같은 측정 세션에서 구분해 실행하기 위한 식별자다. */
	private enum ProcessingPath {
		CURRENT_BASELINE,
		BOUNDED_CANDIDATE
	}

	/** 같은 fixture라도 어떤 테스트가 만든 보고서인지에 따라 재현 selector가 다르다. */
	private enum ReportKind {
		CANDIDATE_ONLY,
		DIRECT_COMPARISON
	}

	private static final class MeasurementRunFailureException extends RuntimeException {

		private final MeasurementRun partialRun;

		private MeasurementRunFailureException(MeasurementRun partialRun, Throwable cause) {
			super(cause);
			this.partialRun = partialRun;
		}

		private MeasurementRun partialRun() {
			return partialRun;
		}
	}

	private record MeasurementProfile(String name, int roomCount, int dueRoomCount) {
	}

	private record MeasurementFixture(
		MeasurementProfile profile,
		int roomCount,
		int dueRoomCount,
		int nonDueRoomCount,
		int nonDueClosedRoomCount,
		int waitingPerClosedDueRoom,
		String requestTime,
		String seed,
		String dataIdentifier) {
	}

	private record CandidateMeasurementReport(
		String outcome,
		MeasurementEnvironment measurementStartEnvironment,
		MeasurementFixture fixture,
		int candidateLimit,
		List<MeasurementRun> warmUpRuns,
		List<MeasurementRun> measuredRuns,
		List<MeasurementRun> partialRuns,
		String runFailureExceptionType,
		SeriesSummary summary) {

		private static CandidateMeasurementReport success(
			MeasurementEnvironment environment, MeasurementFixture fixture, int candidateLimit,
			List<MeasurementRun> warmUpRuns, List<MeasurementRun> measuredRuns, SeriesSummary summary) {
			return new CandidateMeasurementReport("SUCCESS", environment, fixture, candidateLimit, warmUpRuns,
				measuredRuns,
				List.of(), null, summary);
		}

		private static CandidateMeasurementReport runFailure(
			MeasurementEnvironment environment, MeasurementFixture fixture, int candidateLimit,
			List<MeasurementRun> warmUpRuns, List<MeasurementRun> measuredRuns, MeasurementRun partialRun,
			String exceptionType) {
			return new CandidateMeasurementReport("RUN_FAILURE", environment, fixture, candidateLimit, warmUpRuns,
				measuredRuns, List.of(partialRun), exceptionType, null);
		}
	}

	private record DirectComparisonReport(
		String outcome,
		MeasurementEnvironment measurementStartEnvironment,
		MeasurementFixture fixture,
		int candidateLimit,
		String baselineSourceSha,
		String candidateSourceSha,
		PathSeries baseline,
		PathSeries candidate,
		List<ObservedChange> observedChanges) {
	}

	private record PathSeries(
		String path,
		Integer candidateLimit,
		List<MeasurementRun> warmUpRuns,
		List<MeasurementRun> measuredRuns,
		SeriesSummary summary) {
	}

	private record ObservedChange(
		String metric,
		double baselineValue,
		double candidateValue,
		double percentChange) {
	}

	private record WaitingQueueMeasurementReport(
		String outcome,
		MeasurementEnvironment measurementStartEnvironment,
		MeasurementFixture fixture,
		int candidateLimit,
		List<MeasurementRun> warmUpRuns,
		List<MeasurementRun> measuredRuns,
		List<MeasurementRun> partialRuns,
		String runFailureExceptionType,
		SeriesSummary summary) {

		private static WaitingQueueMeasurementReport success(
			MeasurementEnvironment environment, MeasurementFixture fixture, int candidateLimit,
			List<MeasurementRun> warmUpRuns, List<MeasurementRun> measuredRuns, SeriesSummary summary) {
			return new WaitingQueueMeasurementReport("SUCCESS", environment, fixture, candidateLimit, warmUpRuns,
				measuredRuns, List.of(), null, summary);
		}

		private static WaitingQueueMeasurementReport runFailure(
			MeasurementEnvironment environment, MeasurementFixture fixture, int candidateLimit,
			List<MeasurementRun> warmUpRuns, List<MeasurementRun> measuredRuns, MeasurementRun partialRun,
			String exceptionType) {
			return new WaitingQueueMeasurementReport("RUN_FAILURE", environment, fixture, candidateLimit, warmUpRuns,
				measuredRuns, List.of(partialRun), exceptionType, null);
		}
	}

	private record MeasurementRun(
		String phase,
		int iteration,
		int candidateCount,
		Integer successCount,
		Integer failureCount,
		Long callElapsedNanos,
		Long wholeTurnElapsedNanos,
		Double throughputPerSecond,
		DatabaseCost databaseCost) {
	}

	private record SeriesSummary(
		long minCallElapsedNanos,
		long medianCallElapsedNanos,
		long maxCallElapsedNanos,
		long minWholeTurnElapsedNanos,
		long medianWholeTurnElapsedNanos,
		long maxWholeTurnElapsedNanos,
		double minThroughputPerSecond,
		double medianThroughputPerSecond,
		double maxThroughputPerSecond,
		double minDatabaseExecutionTimeMs,
		double medianDatabaseExecutionTimeMs,
		double maxDatabaseExecutionTimeMs) {
	}

	private record DatabaseCost(
		long calls,
		double totalExecutionTimeMs,
		long rows,
		long sharedBufferHits,
		long sharedBufferReads) {
	}

	private record MeasurementEnvironment(
		String gitSha,
		String candidateImplementationSourceSha,
		String javaVersion,
		String postgresqlVersion,
		String operatingSystem,
		int cpuCount,
		long startUsedHeapBytes,
		long maxHeapBytes,
		Map<String, String> configuration) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class CandidateMeasurementConfiguration {

		@Bean
		@Primary
		Clock fixedClock() {
			return Clock.fixed(REQUEST_TIME, ZoneOffset.UTC);
		}
	}
}
