package cloud.bamsongi.albammate.room.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.room.entity.Room;
import cloud.bamsongi.albammate.room.enums.RoomStatus;
import cloud.bamsongi.albammate.room.repository.RoomRepository;
import cloud.bamsongi.albammate.room.statuscorrection.RoomStatusCorrectionCoordinator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest
class RoomStatusCorrectionBaselineMeasurementPostgresTest {

	private static final Instant REQUEST_TIME = Instant.parse("2026-08-06T00:00:00Z");
	private static final Instant FINISHED_THRESHOLD = REQUEST_TIME.minusSeconds(24 * 60 * 60);
	private static final int NON_DUE_CLOSED_ROOM_COUNT = 10;
	private static final Path REPORT_DIRECTORY = Path.of("build", "reports", "measurements");
	private static final String FIXTURE_SEED = "ROOM-09c-baseline-v1";
	private static final String THROUGHPUT_FORMULA = "throughputPerSecond = changedCount * 1_000_000_000 / elapsedNanos";
	private static final MeasurementProfile SMALL = new MeasurementProfile("small", 100, 20);
	private static final MeasurementProfile MEDIUM = new MeasurementProfile("medium", 10_000, 2_000);
	private static final MeasurementProfile LARGE = new MeasurementProfile("large", 50_000, 10_000);

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

	@Autowired
	private RoomStatusCorrectionCoordinator coordinator;

	@Autowired
	private RoomRepository roomRepository;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private ObjectMapper objectMapper;

	@BeforeEach
	void setUp() {
		jdbcTemplate.execute("create extension if not exists pg_stat_statements");
		jdbcTemplate.execute("select pg_stat_statements_reset()");
	}

	@AfterEach
	void tearDown() {
		jdbcTemplate.update("delete from rooms where title like 'ROOM-09c 기준선 %'");
		jdbcTemplate.update("delete from users where email = 'room-09c-baseline@example.com'");
	}

	@Test
	void 작은_fixture는_현행_전체_Entity_단일_트랜잭션_경로의_입력과_결과를_기록한다() throws Exception {
		MeasurementReport report = measure(SMALL);

		assertEquals("SUCCESS", report.outcome());
		assertEquals(SMALL.roomCount(), report.fixture().roomCount());
		assertEquals(SMALL.dueRoomCount(), report.fixture().dueRoomCount());
		assertEquals(NON_DUE_CLOSED_ROOM_COUNT, report.fixture().nonDueClosedRoomCount());
		assertEquals(0, report.fixture().waitingRoomCount());
		assertEquals(1, report.warmUpRuns().size());
		assertEquals(5, report.measuredRuns().size());
		assertTrue(report.measuredRuns().stream().allMatch(run -> run.candidateCount() == 20));
		assertTrue(report.measuredRuns().stream().allMatch(run -> run.changedCount() == 20));
		assertTrue(report.measuredRuns().stream().allMatch(run -> !run.pgStatStatements().isEmpty()));
		assertTrue(report.summary().minThroughputPerSecond() > 0);
		assertTrue(report.summary().medianThroughputPerSecond() > 0);
		assertTrue(report.summary().maxThroughputPerSecond() > 0);
		JsonNode rawReport = objectMapper.readTree(Files.readString(reportPath(SMALL)));
		JsonNode firstMeasuredRun = rawReport.path("measuredRuns").get(0);
		assertTrue(firstMeasuredRun.path("throughputPerSecond").asDouble() > 0);
		assertEquals(
			firstMeasuredRun.path("changedCount").asInt() * 1_000_000_000d
				/ firstMeasuredRun.path("elapsedNanos").asLong(),
			firstMeasuredRun.path("throughputPerSecond").asDouble(), 0.000_001d);
		assertTrue(firstMeasuredRun.hasNonNull("runStartEnvironment"));
		assertTrue(rawReport.hasNonNull("measurementStartEnvironment"));
		assertTrue(Files.exists(reportPath(SMALL)));
	}

	@Test
	void run_level_실패는_ROOM별_실패와_분리해_원자료를_기록한다() throws Exception {
		installRunFailureTrigger();
		try {
			MeasurementRunFailureException failure = assertThrows(
				MeasurementRunFailureException.class, () -> measure(SMALL));

			assertTrue(failure.getCause().getClass().getName().contains("JpaSystemException"));
			JsonNode rawReport = objectMapper.readTree(Files.readString(failureReportPath(SMALL)));
			assertEquals("RUN_FAILURE", rawReport.path("outcome").asText());
			assertEquals(0, rawReport.path("roomFailures").size());
			assertTrue(rawReport.path("runFailure").path("exceptionType").asText()
				.contains("JpaSystemException"));
			assertEquals("현행 일괄 트랜잭션 실패", rawReport.path("runFailure").path("category").asText());
			assertEquals(1, rawReport.path("partialRuns").size());
			assertEquals(20, rawReport.path("partialRuns").get(0).path("candidateCount").asInt());
			assertTrue(rawReport.path("partialRuns").get(0).has("throughputPerSecond"));
			assertTrue(rawReport.path("partialRuns").get(0).path("throughputPerSecond").isNull());
			assertTrue(rawReport.path("partialRuns").get(0).hasNonNull("runStartEnvironment"));
			assertTrue(rawReport.hasNonNull("measurementStartEnvironment"));
			assertTrue(Files.exists(failureReportPath(SMALL)));
		} finally {
			jdbcTemplate.execute("drop trigger if exists room_09c_measurement_failure_trigger on rooms");
			jdbcTemplate.execute("drop function if exists room_09c_measurement_failure()");
			clearFixture();
		}
	}

	@Test
	void 기본_postgresTest는_소형_fixture만_실행하고_승인_규모는_명시적_속성으로만_연다() throws Exception {
		MeasurementGate gate = writeMeasurementGate();

		assertEquals(List.of(SMALL), gate.defaultProfiles());
		assertEquals(List.of(MEDIUM, LARGE), gate.explicitProfiles());
		assertTrue(gate.command().contains("-Dissue383.measurement=true"));
		assertTrue(gate.command().contains("승인_규모_기준선을_측정한다"));
		assertTrue(Files.readString(REPORT_DIRECTORY.resolve("room-09c-measurement-gate.json"))
			.contains("throughputPerSecond = changedCount * 1_000_000_000 / elapsedNanos"));
		assertTrue(Files.exists(REPORT_DIRECTORY.resolve("room-09c-measurement-gate.json")));
	}

	@Test
	void 후보_수_불일치도_RUN_FAILURE로_원자료를_기록한다() throws Exception {
		MeasurementRunFailureException failure = assertThrows(
			MeasurementRunFailureException.class, () -> measure(SMALL, SMALL.dueRoomCount() + 1));

		assertTrue(failure.getCause() instanceof AssertionError);
		JsonNode rawReport = objectMapper.readTree(Files.readString(failureReportPath(SMALL)));
		assertEquals("RUN_FAILURE", rawReport.path("outcome").asText());
		assertEquals(0, rawReport.path("roomFailures").size());
		assertEquals("후보 수 사전 검증 실패", rawReport.path("runFailure").path("category").asText());
		assertEquals(20, rawReport.path("partialRuns").get(0).path("candidateCount").asInt());
		assertTrue(rawReport.path("partialRuns").get(0).path("pgStatStatements").size() > 0);
	}

	@Test
	@EnabledIfSystemProperty(named = "issue383.measurement", matches = "true")
	void 승인_규모_기준선을_측정한다() throws Exception {
		for (MeasurementProfile profile : List.of(MEDIUM, LARGE)) {
			MeasurementReport report = measure(profile);
			assertEquals("SUCCESS", report.outcome());
			assertEquals(5, report.measuredRuns().size());
		}
	}

	private MeasurementReport measure(MeasurementProfile profile) throws Exception {
		return measure(profile, profile.dueRoomCount());
	}

	private MeasurementReport measure(MeasurementProfile profile, int expectedDueRoomCount) throws Exception {
		List<MeasurementRun> warmUpRuns = new ArrayList<>();
		List<MeasurementRun> measuredRuns = new ArrayList<>();
		MeasurementEnvironment measurementStartEnvironment = environment(profile);
		try {
			int candidateCount = verifyCandidateCount(profile, expectedDueRoomCount);
			warmUpRuns.add(executeRun(profile, "warm-up", 1, candidateCount));
			for (int iteration = 1; iteration <= 5; iteration++) {
				measuredRuns.add(executeRun(profile, "measured", iteration, candidateCount));
			}
			MeasurementReport report = MeasurementReport.success(
				measurementStartEnvironment, fixture(profile), warmUpRuns, measuredRuns, summary(measuredRuns));
			writeReport(reportPath(profile), report);
			return report;
		} catch (MeasurementRunFailureException exception) {
			MeasurementReport report = MeasurementReport.runFailure(
				measurementStartEnvironment, fixture(profile), warmUpRuns, measuredRuns,
				exception.partialRun(),
				new RunFailure(exception.getCause().getClass().getName(), failureCategory(exception.partialRun())));
			writeReport(failureReportPath(profile), report);
			throw exception;
		} finally {
			clearFixture();
		}
	}

	private String failureCategory(MeasurementRun partialRun) {
		return "candidate-check".equals(partialRun.phase())
			? "후보 수 사전 검증 실패"
			: "현행 일괄 트랜잭션 실패";
	}

	private int verifyCandidateCount(MeasurementProfile profile, int expectedDueRoomCount) {
		Integer candidateCount = null;
		try {
			clearFixture();
			seedFixture(profile.roomCount(), profile.dueRoomCount());
			candidateCount = findDueRoomCount();
			assertEquals(expectedDueRoomCount, candidateCount, "fixture due ROOM 수");
			return candidateCount;
		} catch (RuntimeException | AssertionError exception) {
			MeasurementRun partialRun = new MeasurementRun(
				"candidate-check", 0, candidateCount, null, null, null, null, capturePgStatStatements());
			throw new MeasurementRunFailureException(partialRun, exception);
		} finally {
			clearFixture();
		}
	}

	private MeasurementRun executeRun(MeasurementProfile profile, String phase, int iteration, int candidateCount) {
		MeasurementEnvironment runStartEnvironment = null;
		Long startedAtNanos = null;
		try {
			clearFixture();
			seedFixture(profile.roomCount(), profile.dueRoomCount());
			runStartEnvironment = environment(profile);
			jdbcTemplate.execute("select pg_stat_statements_reset()");
			startedAtNanos = System.nanoTime();
			int changedCount = coordinator.correctDueRooms(REQUEST_TIME);
			long elapsedNanos = System.nanoTime() - startedAtNanos;
			assertEquals(profile.dueRoomCount(), changedCount, "현행 전체 Entity 기준선 변경 수");
			assertEquals(NON_DUE_CLOSED_ROOM_COUNT, countNonDueClosedRooms(),
				"finishedThreshold 직후 CLOSED ROOM은 실행 결과에서 제외");
			return new MeasurementRun(phase, iteration, candidateCount, changedCount, elapsedNanos,
				throughputPerSecond(changedCount, elapsedNanos), runStartEnvironment, pgStatStatements());
		} catch (RuntimeException | AssertionError exception) {
			Long elapsedNanos = startedAtNanos == null ? null : System.nanoTime() - startedAtNanos;
			MeasurementRun partialRun = new MeasurementRun(phase, iteration, candidateCount, null, elapsedNanos, null,
				runStartEnvironment, capturePgStatStatements());
			throw new MeasurementRunFailureException(partialRun, exception);
		}
	}

	private MeasurementGate writeMeasurementGate() throws Exception {
		String selector = "cloud.bamsongi.albammate.room.measurement."
			+ "RoomStatusCorrectionBaselineMeasurementPostgresTest.승인_규모_기준선을_측정한다";
		String command = "$env:JAVA_TOOL_OPTIONS = '-Dissue383.measurement=true'\n"
			+ ".\\gradlew.bat postgresTest --tests \"" + selector + "\" --rerun --fail-fast";
		MeasurementGate gate = new MeasurementGate(
			List.of(SMALL), List.of(MEDIUM, LARGE),
			command,
			THROUGHPUT_FORMULA);
		writeReport(REPORT_DIRECTORY.resolve("room-09c-measurement-gate.json"), gate);
		return gate;
	}

	private void installRunFailureTrigger() {
		jdbcTemplate.execute("""
			create function room_09c_measurement_failure() returns trigger language plpgsql as $$
			begin
				raise exception 'ROOM-09c measurement forced run failure';
			end;
			$$;
			create trigger room_09c_measurement_failure_trigger
			before update on rooms
			for each row execute function room_09c_measurement_failure();
			""");
	}

	private int findDueRoomCount() {
		List<Room> dueRooms = roomRepository.findDueRooms(REQUEST_TIME, FINISHED_THRESHOLD);
		assertTrue(dueRooms.stream().noneMatch(room -> room.getStatus() == RoomStatus.CLOSED
			&& room.getStartAt().isAfter(FINISHED_THRESHOLD)),
			"finishedThreshold 직후 CLOSED ROOM은 후보에서 제외");
		return dueRooms.size();
	}

	private int countNonDueClosedRooms() {
		return jdbcTemplate.queryForObject("""
			select count(*)
			from rooms
			where title like 'ROOM-09c 기준선 non-due-closed %'
			  and status = 'CLOSED'
			  and start_at > ?
			""", Integer.class, Timestamp.from(FINISHED_THRESHOLD));
	}

	private List<PgStatStatement> pgStatStatements() {
		return jdbcTemplate.query("""
			select query, queryid::text, calls, total_exec_time, rows, shared_blks_hit, shared_blks_read
			from pg_stat_statements
			where dbid = (select oid from pg_database where datname = current_database())
			order by total_exec_time desc, queryid
			""", (resultSet, rowNum) -> new PgStatStatement(
			resultSet.getString("query"), resultSet.getString("queryid"), resultSet.getLong("calls"),
			resultSet.getDouble("total_exec_time"), resultSet.getLong("rows"),
			resultSet.getLong("shared_blks_hit"), resultSet.getLong("shared_blks_read")));
	}

	private List<PgStatStatement> capturePgStatStatements() {
		try {
			return pgStatStatements();
		} catch (RuntimeException ignored) {
			return List.of();
		}
	}

	private MeasurementEnvironment environment(MeasurementProfile profile) {
		Runtime runtime = Runtime.getRuntime();
		return new MeasurementEnvironment(
			gitSha(), System.getProperty("java.version"),
			jdbcTemplate.queryForObject("show server_version", String.class), System.getProperty("os.name"),
			System.getProperty("os.version"), runtime.availableProcessors(),
			runtime.totalMemory() - runtime.freeMemory(),
			runtime.maxMemory(), Map.of(
				"postgresImage", POSTGRES.getDockerImageName(),
				"sharedPreloadLibraries", jdbcTemplate.queryForObject("show shared_preload_libraries", String.class),
				"measurementProperty", System.getProperty("issue383.measurement", "false"),
				"profile", profile.name()));
	}

	private String gitSha() {
		try {
			Process process = new ProcessBuilder("git", "-c", "safe.directory=" + Path.of("").toAbsolutePath(),
				"rev-parse", "HEAD").redirectErrorStream(true).start();
			if (process.waitFor() == 0) {
				return new String(process.getInputStream().readAllBytes()).trim();
			}
		} catch (Exception ignored) {
			// 측정 원자료가 git을 읽지 못해도 실패 원인을 바꾸지 않는다.
		}
		return "UNAVAILABLE";
	}

	private Fixture fixture(MeasurementProfile profile) {
		return new Fixture(profile.name(), profile.roomCount(), profile.dueRoomCount(),
			profile.roomCount() - profile.dueRoomCount(), NON_DUE_CLOSED_ROOM_COUNT, 0,
			REQUEST_TIME.toString(), FIXTURE_SEED,
			FIXTURE_SEED + ":" + profile.name() + ":" + profile.roomCount() + ":" + profile.dueRoomCount());
	}

	private Summary summary(List<MeasurementRun> measuredRuns) {
		List<Long> elapsedNanos = measuredRuns.stream().map(MeasurementRun::elapsedNanos).sorted().toList();
		List<Double> throughputPerSecond = measuredRuns.stream()
			.map(MeasurementRun::throughputPerSecond).sorted().toList();
		return new Summary(
			elapsedNanos.getFirst(), elapsedNanos.get(elapsedNanos.size() / 2), elapsedNanos.getLast(),
			throughputPerSecond.getFirst(), throughputPerSecond.get(throughputPerSecond.size() / 2),
			throughputPerSecond.getLast());
	}

	private double throughputPerSecond(int changedCount, long elapsedNanos) {
		return changedCount * 1_000_000_000d / elapsedNanos;
	}

	private Path reportPath(MeasurementProfile profile) {
		return REPORT_DIRECTORY.resolve("room-09c-" + profile.name() + ".json");
	}

	private Path failureReportPath(MeasurementProfile profile) {
		return REPORT_DIRECTORY.resolve("room-09c-" + profile.name() + "-run-failure.json");
	}

	private void writeReport(Path reportPath, Object report) throws Exception {
		Files.createDirectories(reportPath.getParent());
		objectMapper.writerWithDefaultPrettyPrinter().writeValue(reportPath.toFile(), report);
	}

	private void seedFixture(int totalRoomCount, int dueRoomCount) {
		jdbcTemplate.update(
			"insert into users(email, password_hash, nickname, created_at, updated_at) values (?, ?, ?, ?, ?)",
			"room-09c-baseline@example.com", "postgres-test-hash", "ROOM-09c 기준선",
			Timestamp.from(REQUEST_TIME), Timestamp.from(REQUEST_TIME));
		Long hostUserId = jdbcTemplate.queryForObject(
			"select id from users where email = 'room-09c-baseline@example.com'", Long.class);
		List<Integer> roomIndexes = java.util.stream.IntStream.range(0, totalRoomCount).boxed().toList();

		jdbcTemplate.batchUpdate("""
			insert into rooms(host_user_id, room_type, title, experience_level, is_rulemaster_led, region,
				capacity, active_participant_count, start_at, place, status, version, created_at, updated_at)
			values (?, 'PERSON_FOCUSED', ?, 'ALL_LEVELS', false, '홍대', 4, 0, ?, '측정 장소', ?, 0, ?, ?)
			""", new BatchPreparedStatementSetter() {
			@Override
			public void setValues(PreparedStatement statement, int index) throws java.sql.SQLException {
				boolean due = index < dueRoomCount;
				boolean closedDueRoom = due && index % 2 == 1;
				boolean nonDueClosedRoom = !due && index < dueRoomCount + NON_DUE_CLOSED_ROOM_COUNT;
				boolean closedRoom = closedDueRoom || nonDueClosedRoom;
				Instant startAt;
				if (due) {
					startAt = closedDueRoom ? FINISHED_THRESHOLD : REQUEST_TIME;
				} else if (nonDueClosedRoom) {
					startAt = FINISHED_THRESHOLD.plusSeconds(1);
				} else {
					startAt = REQUEST_TIME.plusSeconds(24 * 60 * 60);
				}
				statement.setLong(1, hostUserId);
				String title = nonDueClosedRoom
					? "ROOM-09c 기준선 non-due-closed " + index
					: "ROOM-09c 기준선 " + index;
				statement.setString(2, title);
				statement.setTimestamp(3, Timestamp.from(startAt));
				statement.setString(4, closedRoom ? "CLOSED" : "RECRUITING");
				statement.setTimestamp(5, Timestamp.from(REQUEST_TIME));
				statement.setTimestamp(6, Timestamp.from(REQUEST_TIME));
			}

			@Override
			public int getBatchSize() {
				return roomIndexes.size();
			}
		});
	}

	private void clearFixture() {
		jdbcTemplate.update("delete from rooms where title like 'ROOM-09c 기준선 %'");
		jdbcTemplate.update("delete from users where email = 'room-09c-baseline@example.com'");
	}

	private static final class MeasurementRunFailureException extends RuntimeException {

		private final MeasurementRun partialRun;

		private MeasurementRunFailureException(MeasurementRun partialRun, Throwable cause) {
			super(Objects.requireNonNull(cause));
			this.partialRun = Objects.requireNonNull(partialRun);
		}

		private MeasurementRun partialRun() {
			return partialRun;
		}
	}

	private record MeasurementProfile(String name, int roomCount, int dueRoomCount) {
	}

	private record Fixture(
		String profile,
		int roomCount,
		int dueRoomCount,
		int nonDueRoomCount,
		int nonDueClosedRoomCount,
		int waitingRoomCount,
		String requestTime,
		String seed,
		String dataIdentifier) {
	}

	private record MeasurementEnvironment(
		String gitSha,
		String javaVersion,
		String postgresqlVersion,
		String operatingSystem,
		String operatingSystemVersion,
		int cpuCount,
		long startUsedHeapBytes,
		long maxHeapBytes,
		Map<String, String> configuration) {
	}

	private record PgStatStatement(
		String query,
		String queryId,
		long calls,
		double totalExecutionTimeMs,
		long rows,
		long sharedBufferHits,
		long sharedBufferReads) {
	}

	private record MeasurementRun(
		String phase,
		int iteration,
		Integer candidateCount,
		Integer changedCount,
		Long elapsedNanos,
		Double throughputPerSecond,
		MeasurementEnvironment runStartEnvironment,
		List<PgStatStatement> pgStatStatements) {
	}

	private record RunFailure(String exceptionType, String category) {
	}

	private record RoomFailure(Long roomId, String category) {
	}

	private record Summary(
		long minElapsedNanos,
		long medianElapsedNanos,
		long maxElapsedNanos,
		double minThroughputPerSecond,
		double medianThroughputPerSecond,
		double maxThroughputPerSecond) {
	}

	private record MeasurementReport(
		String outcome,
		String throughputFormula,
		MeasurementEnvironment measurementStartEnvironment,
		Fixture fixture,
		List<MeasurementRun> warmUpRuns,
		List<MeasurementRun> measuredRuns,
		List<MeasurementRun> partialRuns,
		RunFailure runFailure,
		List<RoomFailure> roomFailures,
		Summary summary) {

		private static MeasurementReport success(
			MeasurementEnvironment environment,
			Fixture fixture,
			List<MeasurementRun> warmUpRuns,
			List<MeasurementRun> measuredRuns,
			Summary summary) {
			return new MeasurementReport("SUCCESS", THROUGHPUT_FORMULA, environment, fixture, warmUpRuns,
				measuredRuns, List.of(), null,
				List.of(), summary);
		}

		private static MeasurementReport runFailure(
			MeasurementEnvironment environment,
			Fixture fixture,
			List<MeasurementRun> warmUpRuns,
			List<MeasurementRun> measuredRuns,
			MeasurementRun partialRun,
			RunFailure runFailure) {
			return new MeasurementReport("RUN_FAILURE", THROUGHPUT_FORMULA, environment, fixture, warmUpRuns,
				measuredRuns,
				List.of(partialRun), runFailure, List.of(), null);
		}
	}

	private record MeasurementGate(
		List<MeasurementProfile> defaultProfiles,
		List<MeasurementProfile> explicitProfiles,
		String command,
		String throughputFormula) {
	}
}
