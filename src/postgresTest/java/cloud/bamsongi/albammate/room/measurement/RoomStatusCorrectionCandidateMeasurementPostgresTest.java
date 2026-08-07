package cloud.bamsongi.albammate.room.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.Map;

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
		CandidateMeasurementReport report = measureCandidate(SMALL, FixtureType.NO_WAITING, 10, candidateReportPath(SMALL));

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
				assertRecordedMeasurementContract(candidateReportPath(profile, candidateLimit), profile, FixtureType.NO_WAITING,
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
		assertTrue(report.measuredRuns().stream().allMatch(run ->
			run.candidateCount() == SMALL.dueRoomCount() && run.successCount() == SMALL.dueRoomCount()));
		assertEnvironment(report.measurementStartEnvironment(),
			"CLOSED_due_ROOM마다_WAITING_10명을_둔_별도_fixture의_후보_종료_비용을_기록한다", "false");
		assertRecordedMeasurementContract(waitingQueueReportPath(SMALL), SMALL, FixtureType.CLOSED_WITH_WAITING, 10,
			"CLOSED_due_ROOM마다_WAITING_10명을_둔_별도_fixture의_후보_종료_비용을_기록한다", "false");
		assertTrue(Files.exists(waitingQueueReportPath(SMALL)));
	}

	private CandidateMeasurementReport measureCandidate(
		MeasurementProfile profile, FixtureType fixtureType, int candidateLimit, Path reportPath) throws Exception {
		MeasurementEnvironment environment = environment(profile, fixtureType, candidateLimit);
		List<MeasurementRun> warmUpRuns = new ArrayList<>();
		List<MeasurementRun> measuredRuns = new ArrayList<>();
		try {
			warmUpRuns.add(executeRun(profile, fixtureType, candidateLimit, "warm-up", 1));
			for (int iteration = 1; iteration <= 5; iteration++) {
				measuredRuns.add(executeRun(profile, fixtureType, candidateLimit, "measured", iteration));
			}
			CandidateMeasurementReport report = CandidateMeasurementReport.success(
				environment, fixture(profile, fixtureType), candidateLimit, warmUpRuns, measuredRuns, summary(measuredRuns));
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

	private WaitingQueueMeasurementReport measureWaitingQueue(
		MeasurementProfile profile, int candidateLimit, Path reportPath) throws Exception {
		MeasurementEnvironment environment = environment(profile, FixtureType.CLOSED_WITH_WAITING, candidateLimit);
		List<MeasurementRun> warmUpRuns = new ArrayList<>();
		List<MeasurementRun> measuredRuns = new ArrayList<>();
		try {
			warmUpRuns.add(executeRun(profile, FixtureType.CLOSED_WITH_WAITING, candidateLimit, "warm-up", 1));
			for (int iteration = 1; iteration <= 5; iteration++) {
				measuredRuns.add(executeRun(profile, FixtureType.CLOSED_WITH_WAITING, candidateLimit, "measured", iteration));
			}
			WaitingQueueMeasurementReport report = WaitingQueueMeasurementReport.success(
				environment, fixture(profile, FixtureType.CLOSED_WITH_WAITING), candidateLimit, warmUpRuns,
				measuredRuns, summary(measuredRuns));
			writeReport(reportPath, report);
			return report;
		} catch (MeasurementRunFailureException exception) {
			writeReport(reportPath, WaitingQueueMeasurementReport.runFailure(
				environment, fixture(profile, FixtureType.CLOSED_WITH_WAITING), candidateLimit, warmUpRuns, measuredRuns,
				exception.partialRun(), exception.getCause().getClass().getName()));
			throw exception;
		} finally {
			clearFixture();
			resetProgress();
		}
	}

	private MeasurementRun executeRun(
		MeasurementProfile profile, FixtureType fixtureType, int candidateLimit, String phase, int iteration) {
		Long startedAtNanos = null;
		int initialDueRoomCount = profile.dueRoomCount();
		try {
			clearFixture();
			resetProgress();
			seedFixture(profile, fixtureType);
			initialDueRoomCount = remainingDueRoomCount(fixtureType);
			assertEquals(profile.dueRoomCount(), initialDueRoomCount, "seed한 due 집합이 profile과 같아야 합니다.");
			jdbcTemplate.execute("select pg_stat_statements_reset()");
			properties.setCandidateLimit(candidateLimit);
			startedAtNanos = System.nanoTime();
			scheduler.correctDueRooms();
			long elapsedNanos = System.nanoTime() - startedAtNanos;
			int remainingDueRoomCount = remainingDueRoomCount(fixtureType);
			int successCount = initialDueRoomCount - remainingDueRoomCount;
			assertEquals(0, remainingDueRoomCount, "동일 초기 due 집합을 끝까지 처리해야 합니다.");
			return new MeasurementRun(phase, iteration, initialDueRoomCount, successCount, 0, elapsedNanos, elapsedNanos,
				throughputPerSecond(successCount, elapsedNanos), databaseCost());
		} catch (RuntimeException | AssertionError exception) {
			Long elapsedNanos = startedAtNanos == null ? null : System.nanoTime() - startedAtNanos;
			MeasurementRun partialRun = new MeasurementRun(phase, iteration, initialDueRoomCount, null, null,
				elapsedNanos, elapsedNanos, null, captureDatabaseCost());
			throw new MeasurementRunFailureException(partialRun, exception);
		}
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

	private MeasurementEnvironment environment(MeasurementProfile profile, FixtureType fixtureType, int candidateLimit) {
		Runtime runtime = Runtime.getRuntime();
		return new MeasurementEnvironment(gitSha(), CANDIDATE_IMPLEMENTATION_SOURCE_SHA, System.getProperty("java.version"),
			jdbcTemplate.queryForObject("show server_version", String.class), System.getProperty("os.name"),
			runtime.availableProcessors(), runtime.totalMemory() - runtime.freeMemory(), runtime.maxMemory(), Map.ofEntries(
				Map.entry("postgresImage", POSTGRES.getDockerImageName()), Map.entry("dockerVersion", dockerVersion()),
				Map.entry("sharedPreloadLibraries",
					jdbcTemplate.queryForObject("show shared_preload_libraries", String.class)),
				Map.entry("requestTime", REQUEST_TIME.toString()), Map.entry("fixtureSeed", fixtureSeed(fixtureType)),
				Map.entry("profile", profile.name()), Map.entry("fixtureType", fixtureType.name()),
				Map.entry("candidateLimit", String.valueOf(candidateLimit)),
				Map.entry("executionCommand", executionCommand(profile, fixtureType)),
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
				Map.entry("notificationCleanupInterval",
					springEnvironment.getProperty("app.notification.cleanup.interval", "1h")),
				Map.entry("notificationCleanupJitter",
					springEnvironment.getProperty("app.notification.cleanup.jitter", "5m"))));
	}

	private void assertSuccessfulSamples(CandidateMeasurementReport report) {
		assertEquals(1, report.warmUpRuns().size());
		assertEquals(5, report.measuredRuns().size());
		assertTrue(report.measuredRuns().stream().allMatch(run ->
			run.candidateCount() == report.fixture().dueRoomCount()
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
		assertEquals(fixture(profile, fixtureType).dataIdentifier(), report.path("fixture").path("dataIdentifier").asText(),
			"보고서 fixture dataIdentifier");
		assertEquals(candidateLimit, report.path("candidateLimit").asInt(), "보고서 candidate limit");
		JsonNode configuration = report.path("measurementStartEnvironment").path("configuration");
		assertEquals(executionCommand(profile, fixtureType), configuration.path("executionCommand").asText(),
			"보고서 실행 명령");
		assertTrue(configuration.path("executionCommand").asText().contains(testMethodName));
		assertEquals(String.valueOf(candidateLimit), configuration.path("candidateLimit").asText(), "보고서 설정 candidate limit");
		assertEquals(measurementProperty, configuration.path("issue390.measurement").asText());
	}

	private SeriesSummary summary(List<MeasurementRun> measuredRuns) {
		List<Long> callElapsed = measuredRuns.stream().map(MeasurementRun::callElapsedNanos).sorted().toList();
		List<Long> wholeTurnElapsed = measuredRuns.stream().map(MeasurementRun::wholeTurnElapsedNanos).sorted().toList();
		List<Double> throughput = measuredRuns.stream().map(MeasurementRun::throughputPerSecond).sorted().toList();
		List<Double> databaseExecution = measuredRuns.stream().map(run -> run.databaseCost().totalExecutionTimeMs()).sorted().toList();
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

	private String executionCommand(MeasurementProfile profile, FixtureType fixtureType) {
		String selector = measurementSelector(profile, fixtureType);
		if (profile == SMALL || fixtureType == FixtureType.CLOSED_WITH_WAITING) {
			return ".\\gradlew.bat postgresTest --no-daemon --tests \"" + selector + "\" --rerun --fail-fast";
		}
		return "$env:JAVA_TOOL_OPTIONS = '-Dissue390.measurement=true'; .\\gradlew.bat postgresTest --no-daemon --tests \""
			+ selector + "\" --rerun --fail-fast";
	}

	private String measurementSelector(MeasurementProfile profile, FixtureType fixtureType) {
		String className = "cloud.bamsongi.albammate.room.measurement."
			+ "RoomStatusCorrectionCandidateMeasurementPostgresTest.";
		if (fixtureType == FixtureType.CLOSED_WITH_WAITING) {
			return className + "CLOSED_due_ROOM마다_WAITING_10명을_둔_별도_fixture의_후보_종료_비용을_기록한다";
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
			return new CandidateMeasurementReport("SUCCESS", environment, fixture, candidateLimit, warmUpRuns, measuredRuns,
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
