package cloud.bamsongi.albammate.matching.measurement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.matching.MatchProposalResponseAction;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseCompletionProbe;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseCoordinator;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalScheduler;
import cloud.bamsongi.albammate.testsupport.PgVectorPostgresImages;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false"})
@Import(MatchResponseCompletionBaselinePostgresTest.ProbeConfiguration.class)
class MatchResponseCompletionBaselinePostgresTest {

	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PgVectorPostgresImages.postgres18())
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");
	private static final List<String> REQUIRED_EXTERNAL_PROFILE_PROPERTIES = List.of(
		"issue776.measurement.target",
		"issue776.environment.stackId",
		"issue776.environment.region",
		"issue776.environment.releaseSha",
		"issue776.environment.appInstanceType",
		"issue776.environment.postgresInstanceType",
		"issue776.environment.redisInstanceType",
		"issue776.environment.backendImage",
		"issue776.environment.webImage",
		"issue776.environment.postgresImage",
		"issue776.environment.redisImage",
		"issue776.environment.applicationConfigSha256",
		"issue776.environment.responseTopology");

	@DynamicPropertySource
	static void configureMeasurementDataSource(DynamicPropertyRegistry registry) {
		if (externalMeasurement()) {
			validateExternalMeasurementConfiguration();
			registry.add("spring.datasource.url", () -> requiredEnvironmentVariable("ISSUE776_JDBC_URL"));
			registry.add("spring.datasource.username", () -> requiredEnvironmentVariable("ISSUE776_JDBC_USERNAME"));
			registry.add("spring.datasource.password", () -> requiredEnvironmentVariable("ISSUE776_JDBC_PASSWORD"));
			registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
			registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
			registry.add("spring.flyway.enabled", () -> false);
			registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
			return;
		}
		if (!POSTGRES.isRunning()) {
			POSTGRES.start();
		}
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.datasource.hikari.minimum-idle", () -> 0);
	}

	@AfterAll
	static void stopMeasurementPostgres() {
		if (!externalMeasurement() && POSTGRES.isRunning()) {
			POSTGRES.stop();
		}
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private MatchProposalResponseCoordinator responseCoordinator;
	@Autowired
	private RecordingProbe completionProbe;
	@Autowired
	private ObjectMapper objectMapper;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private Environment springEnvironment;
	@Autowired
	private ApplicationContext applicationContext;
	private Integer controlledMeasurementProposalCount;
	private String controlledFutureFailureIdempotencyKeySuffix;

	@BeforeEach
	@AfterEach
	void clearFixture() {
		jdbcTemplate.execute(
			"truncate table match_idempotency_records, match_proposal_members, match_proposals, match_party_participants, match_parties, match_requests, users restart identity cascade");
		completionProbe.clear();
		controlledMeasurementProposalCount = null;
		controlledFutureFailureIdempotencyKeySuffix = null;
	}

	@Test
	void 고정_fixture는_네_시나리오_개수_CSV_규칙과_materialized_manifest를_검증한다() {
		MatchResponseCompletionBaselineSupport.Fixture fixture = MatchResponseCompletionBaselineSupport
			.contractFixture();

		assertEquals("MATCH-01-RESPONSE-COMPLETION-V2", fixture.seed());
		assertEquals(4, fixture.scenarios().size());
		assertTrue(fixture.csv().endsWith("\n"));
		assertFalse(fixture.csv().contains("\r"));
		assertEquals(7_000, fixture.csv().lines().skip(1).count());
		assertEquals(1_000, fixture.scenario("ACCEPT_NON_TERMINAL").logicalCommands());
		assertEquals(500, fixture.scenario("ACCEPT_FINAL").logicalCommands());
		assertDoesNotThrow(() -> MatchResponseCompletionBaselineSupport.verifyMaterializedFixture(
			fixture, MatchResponseCompletionBaselineSupport.materialize(fixture)));
	}

	@Test
	void fixture는_시나리오별_단일_transaction_timestamp와_삼십초_응답_창을_사용한다() {
		materializeCommands(Scenario.ACCEPT_NON_TERMINAL, 10);

		List<Map<String, Object>> proposals = jdbcTemplate.queryForList(
			"select created_at, respond_by from match_proposals order by id");
		Timestamp fixtureReferenceTime = (Timestamp)proposals.getFirst().get("created_at");
		for (Map<String, Object> proposal : proposals) {
			assertEquals(fixtureReferenceTime, proposal.get("created_at"));
			assertEquals(fixtureReferenceTime.toInstant().plusSeconds(30),
				((Timestamp)proposal.get("respond_by")).toInstant());
		}
		for (Map<String, Object> request : jdbcTemplate.queryForList(
			"select min_party_size, max_party_size from match_requests")) {
			assertEquals(2L, ((Number)request.get("min_party_size")).longValue());
			assertEquals(4L, ((Number)request.get("max_party_size")).longValue());
		}
	}

	@Test
	void 측정_context는_scheduler_relay_retention을_비활성화한다() {
		assertEquals("false", springEnvironment.getProperty("spring.task.scheduling.enabled"));
		assertEquals("false", springEnvironment.getProperty("app.notification.relay.enabled"));
		assertEquals("false", springEnvironment.getProperty("app.chat.retention.enabled"));
		assertTrue(applicationContext.getBeansOfType(MatchProposalScheduler.class).isEmpty());
	}

	@Test
	void operationTime부터_commit과_DTO_조합까지의_완료_관측을_표본으로_기록한다() {
		long userId = insertUser();
		long requestId = insertRequest(userId);
		long proposalId = insertOpenProposal();
		insertProposalMember(proposalId, requestId, userId);

		responseCoordinator.respond(userId, proposalId, MatchProposalResponseAction.ACCEPT, "response-completion-t2");

		RecordedProbeSample probeSample = completionProbe.takeCompleted();
		assertTrue(probeSample.operationTime().isBefore(probeSample.completedAt()));
		assertTrue(probeSample.latencyNanos() > 0);
		assertEquals("ACCEPTED", jdbcTemplate.queryForObject(
			"select response_status from match_proposal_members where proposal_id = ? and match_request_id = ?",
			String.class, proposalId, requestId));
	}

	@Test
	void materialized_manifest_기준으로_target과_non_target의_개별_최종_상태를_검증한다() {
		MaterializedCommands materialized = materializeCommands(Scenario.ACCEPT_NON_TERMINAL, 1);
		ExecutionResult execution = executeCommandsConcurrently(materialized.commands(), false);
		PrivateManifestRow target = materialized.manifestRows().stream()
			.filter(PrivateManifestRow::commandTarget)
			.findFirst()
			.orElseThrow();
		PrivateManifestRow nonTarget = materialized.manifestRows().stream()
			.filter(row -> !row.commandTarget())
			.findFirst()
			.orElseThrow();

		jdbcTemplate.update(
			"update match_proposal_members set response_status = 'PENDING', responded_at = null where proposal_id = ? and match_request_id = ?",
			target.proposalId(), target.memberRequestId());
		jdbcTemplate.update(
			"update match_proposal_members set response_status = 'ACCEPTED', responded_at = transaction_timestamp() where proposal_id = ? and match_request_id = ?",
			nonTarget.proposalId(), nonTarget.memberRequestId());

		FinalStateAssertion finalState = assertScenarioFinalState(Scenario.ACCEPT_NON_TERMINAL, materialized,
			execution, false);
		assertFalse(finalState.passed());
	}

	@Test
	@Tag("measurement")
	void 동일_명령의_두_물리_요청은_단일_멱등성_전이와_최종_ACCEPT_Party_하나로_수렴한다() {
		MatchResponseCompletionBaselineSupport.requireMeasurementOptIn();
		for (Scenario scenario : Scenario.values()) {
			MaterializedCommands materialized = materializeCommands(scenario,
				scenario == Scenario.ACCEPT_FINAL ? 500 : 1_000);
			List<ResponseCommand> commands = materialized.commands();
			assertEquals(1_000, commands.size());
			ExecutionResult execution = executeCommandsConcurrently(commands, true);
			assertEquals(2_000, execution.physicalRequestCount());
			assertEquals(2_000, execution.readyRequestCount());
			if (scenario == Scenario.ACCEPT_FINAL) {
				assertFinalAcceptReleaseGroup(commands);
			}
			assertEquals(1_000,
				jdbcTemplate.queryForObject("select count(*) from match_idempotency_records", Integer.class));
			assertEquals(scenario == Scenario.ACCEPT_FINAL ? 500 : 0,
				jdbcTemplate.queryForObject("select count(*) from match_parties", Integer.class));
			FinalStateAssertion finalState = assertScenarioFinalState(scenario, materialized, execution, false);
			assertTrue(finalState.passed(), "correctness-only duplicate final state assertion 실패: " + scenario.name());
			clearFixture();
		}
	}

	@Test
	void raw_sample은_비식별_관측값과_UTC_창만_보존한다() {
		MatchResponseCompletionBaselineSupport.Sample sample = new MatchResponseCompletionBaselineSupport.Collector()
			.record("CANCEL", "TERMINAL", 0, 12L, 200, null, "CANCELED");

		String raw = MatchResponseCompletionBaselineSupport.rawSample(sample);
		assertTrue(raw.contains("CANCEL"));
		assertTrue(raw.contains("lockWaitNanos"));
		assertFalse(raw.contains("userId"));
		assertFalse(raw.contains("idempotencyKey"));
		assertFalse(raw.contains("payload"));
	}

	@Test
	void fixture와_관측_누락은_INVALID_완결_후_정합성_위반은_FAILED로_분리한다() {
		assertEquals("INVALID", MatchResponseCompletionBaselineSupport.evaluate(
			List.of(MatchResponseCompletionBaselineSupport.Round.invalid())).outcome());
		assertEquals("FAILED", MatchResponseCompletionBaselineSupport.evaluate(
			MatchResponseCompletionBaselineSupport.failedRounds()).outcome());
		assertEquals("RESPONSE_BASELINE_ACCEPTED", MatchResponseCompletionBaselineSupport.evaluate(
			MatchResponseCompletionBaselineSupport.acceptedRounds()).outcome());
	}

	@Test
	@Tag("measurement")
	void 계약_크기_측정은_issue776_opt_in에서만_실행한다() {
		if (!Boolean.getBoolean("issue776.measurement")) {
			assertThrows(IllegalStateException.class,
				() -> MatchResponseCompletionBaselineSupport.requireMeasurementOptIn());
			return;
		}
		assertDoesNotThrow(MatchResponseCompletionBaselineSupport::requireMeasurementOptIn);
		Path artifact = runContractMeasurement(measurementOutputDirectory());
		assertTrue(Files.exists(artifact));
	}

	@Test
	void 기본_postgresTest는_opt_in_없는_계약_크기_측정을_실행하지_않는다() {
		assertFalse(Boolean.getBoolean("issue776.measurement"));
		assertThrows(IllegalStateException.class, MatchResponseCompletionBaselineSupport::requireMeasurementOptIn);
	}

	@Test
	void 기술_오류_round는_부분_raw와_INVALID_artifact를_기록한_뒤_reporter가_INVALID로_판정한다() throws Exception {
		controlledMeasurementProposalCount = 2;
		controlledFutureFailureIdempotencyKeySuffix = "-2-1";
		Path artifactDirectory = Files.createTempDirectory("issue776-invalid-artifact-");
		ContractMeasurementInvalidException failure = assertThrows(ContractMeasurementInvalidException.class,
			() -> runContractMeasurement(artifactDirectory));
		RoundArtifact round = failure.roundArtifacts().getFirst();
		assertEquals(1, round.rawSamples().size());
		assertTrue(round.invalidReason().contains("commandFailure:IllegalStateException"));
		Path artifact = failure.artifact();
		assertTrue(Files.exists(artifact));
		Process reporter = new ProcessBuilder("node", "scripts/measurements/match01-response-completion-report.mjs",
			artifact.toString()).start();
		assertEquals(0, reporter.waitFor());
		assertTrue(new String(reporter.getInputStream().readAllBytes()).contains("INVALID"));
	}

	@Test
	void measured_round는_fixture와_통계_초기화_뒤의_실제_PostgreSQL_관측과_재계산_metric을_보존한다()
		throws Exception {
		controlledMeasurementProposalCount = 2;
		List<RoundArtifact> artifacts = new ArrayList<>();
		List<PrivateManifestRow> privateManifestRows = new ArrayList<>();

		runScenarioRound(Scenario.ACCEPT_NON_TERMINAL, 1, false, artifacts, privateManifestRows);

		Path artifact = writeArtifact(artifacts, privateManifestRows,
			Files.createTempDirectory("issue776-observation-artifact-"));
		@SuppressWarnings("unchecked") Map<String, Object> root = objectMapper.readValue(Files.readString(artifact),
			Map.class);
		@SuppressWarnings("unchecked") Map<String, Object> scenario = ((List<Map<String, Object>>)root.get("scenarios"))
			.getFirst();
		@SuppressWarnings("unchecked") Map<String, Object> round = ((List<Map<String, Object>>)scenario
			.get("measuredRounds")).getFirst();
		@SuppressWarnings("unchecked") Map<String, Object> dbStatistics = (Map<String, Object>)round
			.get("dbStatistics");
		@SuppressWarnings("unchecked") Map<String, Object> lockWait = (Map<String, Object>)round.get("lockWait");
		@SuppressWarnings("unchecked") Map<String, Object> metrics = (Map<String, Object>)round.get("metrics");

		assertEquals(true, dbStatistics.get("observed"));
		assertTrue(((List<?>)dbStatistics.get("statements")).size() > 0);
		assertTrue(((Number)dbStatistics.get("totalCalls")).longValue() > 0L);
		assertEquals(true, lockWait.get("observed"));
		assertTrue(((Number)lockWait.get("pollCount")).longValue() > 0L);
		assertTrue(((Number)metrics.get("observationDurationNanos")).longValue() > 0L);
		assertTrue(metrics.containsKey("latencyNanos"));
		assertTrue(metrics.containsKey("throughputPerSecond"));
		assertTrue(metrics.containsKey("retry"));
		assertTrue(metrics.containsKey("failure"));
	}

	private Path runContractMeasurement(Path outputDirectory) {
		List<RoundArtifact> roundArtifacts = new ArrayList<>();
		List<PrivateManifestRow> privateManifestRows = new ArrayList<>();
		measurement: for (Scenario scenario : Scenario.values()) {
			runScenarioRound(scenario, 0, true, roundArtifacts, privateManifestRows);
			if (roundArtifacts.getLast().invalidReason() != null) {
				break;
			}
			for (int round = 1; round <= 3; round++) {
				runScenarioRound(scenario, round, false, roundArtifacts, privateManifestRows);
				if (roundArtifacts.getLast().invalidReason() != null) {
					break measurement;
				}
			}
		}
		Path artifact = writeArtifact(roundArtifacts, privateManifestRows, outputDirectory);
		if (roundArtifacts.size() != 16 || roundArtifacts.stream().anyMatch(round -> !round.accepted())) {
			throw new ContractMeasurementInvalidException(artifact, List.copyOf(roundArtifacts));
		}
		return artifact;
	}

	private static Path measurementOutputDirectory() {
		String configuredDirectory = System.getProperty("issue776.outputDirectory");
		return configuredDirectory == null || configuredDirectory.isBlank()
			? Path.of("docs/measurements/results/match-01/response-completion")
			: Path.of(configuredDirectory);
	}

	@Test
	void artifact_provenance는_실제_SHA와_raw_data_digest를_검증한다() {
		MatchResponseCompletionBaselineSupport.Artifact artifact = MatchResponseCompletionBaselineSupport
			.artifact("a".repeat(40));

		assertDoesNotThrow(() -> MatchResponseCompletionBaselineSupport.verifyArtifact(artifact));
		assertThrows(IllegalArgumentException.class,
			() -> MatchResponseCompletionBaselineSupport.verifyArtifact(artifact.withRawDataDigest("0".repeat(64))));
	}

	private long insertUser() {
		return insertUser(Instant.now());
	}

	private long insertUser(Instant referenceTime) {
		return jdbcTemplate.queryForObject(
			"insert into users (email, password_hash, nickname, created_at, updated_at) values (?, 'hash', ?, ?, ?) returning id",
			Long.class, "issue776-" + UUID.randomUUID() + "@example.com", "issue776", Timestamp.from(referenceTime),
			Timestamp.from(referenceTime));
	}

	private long insertRequest(long userId) {
		return insertRequest(userId, Instant.now());
	}

	private long insertRequest(long userId, Instant referenceTime) {
		return jdbcTemplate.queryForObject(
			"insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, proposed_at, created_at, updated_at) values (?, 2, 4, 'PROPOSED', ?, ?, ?, ?, ?) returning id",
			Long.class, userId, Timestamp.from(referenceTime), Timestamp.from(referenceTime),
			Timestamp.from(referenceTime),
			Timestamp.from(referenceTime), Timestamp.from(referenceTime));
	}

	private long insertOpenProposal() {
		return insertOpenProposal(Instant.now());
	}

	private long insertOpenProposal(Instant fixtureReferenceTime) {
		return jdbcTemplate.queryForObject(
			"insert into match_proposals (party_size, status, respond_by, created_at, updated_at) values (2, 'OPEN', ?, ?, ?) returning id",
			Long.class, Timestamp.from(fixtureReferenceTime.plusSeconds(30)), Timestamp.from(fixtureReferenceTime),
			Timestamp.from(fixtureReferenceTime));
	}

	private void insertProposalMember(long proposalId, long requestId, long userId) {
		insertProposalMember(proposalId, requestId, userId, Instant.now());
	}

	private void insertProposalMember(long proposalId, long requestId, long userId, Instant referenceTime) {
		jdbcTemplate.update(
			"insert into match_proposal_members (proposal_id, match_request_id, user_id, response_status, created_at, updated_at) values (?, ?, ?, 'PENDING', ?, ?)",
			proposalId, requestId, userId, Timestamp.from(referenceTime), Timestamp.from(referenceTime));
	}

	private MaterializedCommands materializeCommands(Scenario scenario, int proposalCount) {
		MatchResponseCompletionBaselineSupport.Fixture fixture = MatchResponseCompletionBaselineSupport
			.contractFixture();
		List<MatchResponseCompletionBaselineSupport.FixtureRow> rows = MatchResponseCompletionBaselineSupport
			.parseScenarioRows(fixture, scenario.name()).stream()
			.filter(row -> row.proposalOrdinal() <= proposalCount)
			.toList();
		assertEquals(proposalCount * 2, rows.size());
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		return transaction.execute(status -> materializeCommandsInFixtureTransaction(scenario, rows));
	}

	private MaterializedCommands materializeCommandsInFixtureTransaction(Scenario scenario,
		List<MatchResponseCompletionBaselineSupport.FixtureRow> rows) {
		Instant fixtureReferenceTime = jdbcTemplate.queryForObject("select transaction_timestamp()", Timestamp.class)
			.toInstant();
		Instant respondBy = fixtureReferenceTime.plusSeconds(30);
		List<MatchResponseCompletionBaselineSupport.FixtureRow> proposalRows = rows.stream()
			.filter(row -> row.memberOrdinal() == 1)
			.toList();
		List<Long> proposalIds = insertFixtureProposals(proposalRows.size(), fixtureReferenceTime, respondBy);
		List<Long> userIds = insertFixtureUsers(scenario, rows, fixtureReferenceTime);
		List<Long> requestIds = insertFixtureRequests(rows, userIds, fixtureReferenceTime);
		insertFixtureProposalMembers(rows, proposalIds, userIds, requestIds, fixtureReferenceTime);
		List<ResponseCommand> commands = new ArrayList<>();
		List<PrivateManifestRow> manifestRows = new ArrayList<>();
		for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
			MatchResponseCompletionBaselineSupport.FixtureRow fixtureRow = rows.get(rowIndex);
			int proposalIndex = fixtureRow.proposalOrdinal() - 1;
			long proposalId = proposalIds.get(proposalIndex);
			long userId = userIds.get(rowIndex);
			long requestId = requestIds.get(rowIndex);
			boolean commandTarget = fixtureRow.commandTarget();
			manifestRows.add(new PrivateManifestRow(fixtureRow.proposalOrdinal(), fixtureRow.memberOrdinal(),
				proposalId, proposalId, requestId, requestId, scenario.name(), commandTarget,
				scenario.expectedProposalStatus(), scenario.expectedMemberResponseStatus(commandTarget),
				scenario.expectedRequestStatus(commandTarget), expectedQueueTimestamp(scenario, commandTarget),
				fixtureReferenceTime, respondBy, fixtureReferenceTime, fixtureReferenceTime, null, null, null, null,
				null, null));
			if (commandTarget) {
				commands.add(new ResponseCommand(userId, proposalId, scenario.action(),
					"issue776-" + scenario.name() + "-" + fixtureRow.proposalOrdinal() + "-"
						+ fixtureRow.memberOrdinal()));
			}
		}
		return new MaterializedCommands(List.copyOf(commands), List.copyOf(manifestRows), fixtureReferenceTime);
	}

	private List<Long> insertFixtureProposals(int proposalCount, Instant fixtureReferenceTime, Instant respondBy) {
		Timestamp createdAt = Timestamp.from(fixtureReferenceTime);
		Timestamp respondByTimestamp = Timestamp.from(respondBy);
		String sql = "insert into match_proposals (party_size, status, respond_by, created_at, updated_at) values "
			+ repeatedValues("(2, 'OPEN', ?, ?, ?)", proposalCount) + " returning id";
		return queryGeneratedFixtureIds(sql, proposalCount, statement -> {
			for (int index = 0; index < proposalCount; index++) {
				int parameter = index * 3 + 1;
				statement.setTimestamp(parameter, respondByTimestamp);
				statement.setTimestamp(parameter + 1, createdAt);
				statement.setTimestamp(parameter + 2, createdAt);
			}
		}, "match proposal");
	}

	private List<Long> insertFixtureUsers(Scenario scenario,
		List<MatchResponseCompletionBaselineSupport.FixtureRow> rows, Instant fixtureReferenceTime) {
		Timestamp createdAt = Timestamp.from(fixtureReferenceTime);
		List<String> emails = rows.stream()
			.map(row -> "issue776-" + scenario.name() + "-" + row.proposalOrdinal() + "-"
				+ row.memberOrdinal() + "-" + UUID.randomUUID() + "@example.com")
			.toList();
		String sql = "insert into users (email, password_hash, nickname, created_at, updated_at) values "
			+ repeatedValues("(?, 'hash', 'issue776', ?, ?)", rows.size()) + " returning id";
		return queryGeneratedFixtureIds(sql, rows.size(), statement -> {
			for (int index = 0; index < rows.size(); index++) {
				int parameter = index * 3 + 1;
				statement.setString(parameter, emails.get(index));
				statement.setTimestamp(parameter + 1, createdAt);
				statement.setTimestamp(parameter + 2, createdAt);
			}
		}, "fixture user");
	}

	private List<Long> insertFixtureRequests(List<MatchResponseCompletionBaselineSupport.FixtureRow> rows,
		List<Long> userIds, Instant fixtureReferenceTime) {
		Timestamp createdAt = Timestamp.from(fixtureReferenceTime);
		String sql = "insert into match_requests (user_id, min_party_size, max_party_size, status, queued_at, priority_since, proposed_at, created_at, updated_at) values "
			+ repeatedValues("(?, 2, 4, 'PROPOSED', ?, ?, ?, ?, ?)", rows.size()) + " returning id";
		return queryGeneratedFixtureIds(sql, rows.size(), statement -> {
			for (int index = 0; index < rows.size(); index++) {
				int parameter = index * 6 + 1;
				statement.setLong(parameter, userIds.get(index));
				for (int timestampParameter = 1; timestampParameter <= 5; timestampParameter++) {
					statement.setTimestamp(parameter + timestampParameter, createdAt);
				}
			}
		}, "fixture request");
	}

	private void insertFixtureProposalMembers(List<MatchResponseCompletionBaselineSupport.FixtureRow> rows,
		List<Long> proposalIds, List<Long> userIds, List<Long> requestIds, Instant fixtureReferenceTime) {
		Timestamp createdAt = Timestamp.from(fixtureReferenceTime);
		String sql = "insert into match_proposal_members (proposal_id, match_request_id, user_id, response_status, created_at, updated_at) values "
			+ repeatedValues("(?, ?, ?, 'PENDING', ?, ?)", rows.size());
		jdbcTemplate.update(sql, statement -> {
			for (int index = 0; index < rows.size(); index++) {
				MatchResponseCompletionBaselineSupport.FixtureRow row = rows.get(index);
				int parameter = index * 5 + 1;
				statement.setLong(parameter, proposalIds.get(row.proposalOrdinal() - 1));
				statement.setLong(parameter + 1, requestIds.get(index));
				statement.setLong(parameter + 2, userIds.get(index));
				statement.setTimestamp(parameter + 3, createdAt);
				statement.setTimestamp(parameter + 4, createdAt);
			}
		});
	}

	private List<Long> queryGeneratedFixtureIds(String sql, int batchSize, PreparedStatementSetter setter,
		String entityName) {
		List<Long> ids = jdbcTemplate.query(sql, setter, (resultSet, rowNumber) -> resultSet.getLong("id"));
		if (ids.size() != batchSize) {
			throw new IllegalStateException(entityName + " generated id 개수가 맞지 않습니다: expected=" + batchSize
				+ ", actual=" + ids.size());
		}
		return List.copyOf(ids);
	}

	private String repeatedValues(String rowValues, int count) {
		StringBuilder values = new StringBuilder(rowValues.length() * count + count * 2);
		for (int index = 0; index < count; index++) {
			if (index > 0) {
				values.append(", ");
			}
			values.append(rowValues);
		}
		return values.toString();
	}

	private ExecutionResult executeCommandsConcurrently(List<ResponseCommand> commands, boolean duplicate) {
		int copiesPerCommand = duplicate ? 2 : 1;
		int physicalRequestCount = commands.size() * copiesPerCommand;
		ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
		CountDownLatch ready = new CountDownLatch(physicalRequestCount);
		CountDownLatch start = new CountDownLatch(1);
		List<SubmittedRequest> requests = new ArrayList<>();
		List<CommandExecution> successes = new ArrayList<>();
		List<CommandFailure> failures = new ArrayList<>();
		try {
			for (ResponseCommand command : commands) {
				for (int copy = 0; copy < copiesPerCommand; copy++) {
					Future<CommandExecution> future = executor.submit(() -> {
						ready.countDown();
						if (!start.await(5, TimeUnit.SECONDS)) {
							throw new TimeoutException("response barrier timeout");
						}
						if (shouldFailFuture(command)) {
							throw new IllegalStateException("controlled response Future failure");
						}
						CurrentMatchStateResponse response = responseCoordinator.respond(command.userId(),
							command.proposalId(),
							command.action(), command.idempotencyKey());
						return new CommandExecution(command,
							response.state() == null ? "EMPTY" : response.state().name(),
							completionProbe.takeCompletedForCurrentThreadOrNull());
					});
					requests.add(new SubmittedRequest(command, future));
				}
			}
			try {
				if (!ready.await(5, TimeUnit.SECONDS)) {
					failures.add(CommandFailure.general("barrierReadyTimeout"));
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				failures.add(CommandFailure.general("barrierReadyInterrupted"));
			}
			start.countDown();
			for (SubmittedRequest request : requests) {
				try {
					successes.add(request.future().get(60, TimeUnit.SECONDS));
				} catch (TimeoutException exception) {
					request.future().cancel(true);
					failures.add(CommandFailure.forCommand(request.command(), "timeout"));
				} catch (CancellationException exception) {
					failures.add(CommandFailure.forCommand(request.command(), "cancelled"));
				} catch (ExecutionException exception) {
					failures.add(CommandFailure.forCommand(request.command(),
						exception.getCause().getClass().getSimpleName()));
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					request.future().cancel(true);
					failures.add(CommandFailure.forCommand(request.command(), "interrupted"));
					break;
				}
			}
		} finally {
			start.countDown();
			executor.shutdownNow();
		}
		return new ExecutionResult(physicalRequestCount, physicalRequestCount - (int)ready.getCount(),
			List.copyOf(successes), List.copyOf(failures));
	}

	private void assertFinalAcceptReleaseGroup(List<ResponseCommand> commands) {
		Map<Long, Integer> commandsByProposal = new HashMap<>();
		for (ResponseCommand command : commands) {
			commandsByProposal.merge(command.proposalId(), 1, Integer::sum);
		}
		assertEquals(500, commandsByProposal.size());
		for (int commandCount : commandsByProposal.values()) {
			assertEquals(2, commandCount);
		}
	}

	private void runScenarioRound(
		Scenario scenario,
		int round,
		boolean warmUp,
		List<RoundArtifact> artifacts,
		List<PrivateManifestRow> privateManifestRows) {
		Instant observationStartedAt = null;
		Instant observationEndedAt = null;
		List<RawSampleDraft> sampleDrafts = new ArrayList<>();
		List<String> invalidReasons = new ArrayList<>();
		MaterializedCommands materialized = null;
		ExecutionResult execution = new ExecutionResult(0, 0, List.of(), List.of());
		FinalStateAssertion finalState = FinalStateAssertion.invalid();
		DatabaseStatistics databaseStatistics = DatabaseStatistics.unobserved();
		LockWaitObservation lockWait = LockWaitObservation.unobserved();
		try {
			jdbcTemplate.execute("create extension if not exists pg_stat_statements");
			materialized = materializeCommands(scenario, measurementProposalCount(scenario));
			jdbcTemplate.execute("select pg_stat_statements_reset()");
			List<ResponseCommand> commands = materialized.commands();
			LockWaitMonitor lockWaitMonitor = new LockWaitMonitor(jdbcTemplate);
			try {
				lockWaitMonitor.start();
				if (!lockWaitMonitor.awaitFirstPoll(5, TimeUnit.SECONDS)) {
					invalidReasons.add("lockWaitInitialPollMissing");
				}
				observationStartedAt = Instant.now();
				lockWaitMonitor.observeNow();
				execution = executeCommandsConcurrently(commands, false);
				lockWaitMonitor.observeNow();
				observationEndedAt = Instant.now();
			} finally {
				if (observationEndedAt == null) {
					observationEndedAt = Instant.now();
				}
				lockWait = lockWaitMonitor.stop(observationStartedAt, observationEndedAt);
			}
			if (!lockWait.observed()) {
				invalidReasons.add("lockWaitObservationMissing");
			}
			databaseStatistics = readDatabaseStatistics();
			if (!databaseStatistics.observed()) {
				invalidReasons.add("dbStatisticsMissing");
			}
			if (execution.readyRequestCount() != commands.size()) {
				invalidReasons.add("barrierReadyTimeout");
			}
			for (CommandFailure failure : execution.failures()) {
				invalidReasons.add("commandFailure:" + failure.reason());
			}
			TransitionDistribution transitions = loadTransitionDistribution(scenario, execution.commandExecutions());
			for (CommandExecution commandExecution : execution.commandExecutions()) {
				RecordedProbeSample probeSample = commandExecution.probeSample();
				String result = transitions.resultFor(commandExecution.command());
				Instant respondBy = respondByFor(materialized, commandExecution.command());
				if (probeSample == null) {
					invalidReasons.add("completionProbeMissing");
					sampleDrafts.add(new RawSampleDraft(null, null, respondBy,
						commandExecution.command().action().name(), result, 0, 200, null, 0L,
						commandExecution.currentState()));
					continue;
				}
				if (!probeSample.operationTime().isBefore(respondBy)) {
					invalidReasons.add("operationTimeAtOrAfterRespondBy");
				}
				sampleDrafts.add(new RawSampleDraft(probeSample.operationTime(), probeSample.completedAt(), respondBy,
					commandExecution.command().action().name(), result, 0, 200, null, probeSample.latencyNanos(),
					commandExecution.currentState()));
			}
			finalState = assertScenarioFinalState(scenario, materialized, execution, true);
		} catch (AssertionError | Exception exception) {
			invalidReasons.add("technicalError:" + exception.getClass().getSimpleName());
		} finally {
			if (materialized != null) {
				try {
					privateManifestRows.addAll(observePrivateManifestRows(scenario, round, warmUp, materialized));
				} catch (Exception exception) {
					invalidReasons.add("observabilityMissing:" + exception.getClass().getSimpleName());
				}
			}
			if (observationStartedAt == null) {
				observationStartedAt = Instant.now();
			}
			if (observationEndedAt == null) {
				observationEndedAt = observationStartedAt;
			}
			boolean finalStatePassed = finalState.passed();
			LockWaitObservation observedLockWait = lockWait;
			List<Long> attributedWaitNanos = observedLockWait.attributeWaitNanos(sampleDrafts);
			List<RawSample> samples = new ArrayList<>();
			for (int index = 0; index < sampleDrafts.size(); index++) {
				samples.add(sampleDrafts.get(index).toRawSample(finalStatePassed, attributedWaitNanos.get(index)));
			}
			LockWaitObservation attributedLockWait = observedLockWait.withSampledWaitNanos(
				attributedWaitNanos.stream().mapToLong(Long::longValue).sum());
			String invalidReason = invalidReasons.isEmpty() ? null : String.join(",", invalidReasons);
			artifacts.add(new RoundArtifact(scenario.name(), round, warmUp, List.copyOf(samples), finalState,
				databaseStatistics, attributedLockWait,
				RoundMetrics.from(samples, observationStartedAt, observationEndedAt),
				invalidReason, observationStartedAt, observationEndedAt));
			clearFixture();
		}
	}

	private DatabaseStatistics readDatabaseStatistics() {
		try {
			List<StatementStatistics> statements = jdbcTemplate.query("""
				select queryid::text as query_id, calls, total_exec_time, rows, shared_blks_hit, shared_blks_read
				from pg_stat_statements
				where queryid is not null
				order by queryid
				""", (resultSet, rowNumber) -> new StatementStatistics(resultSet.getString("query_id"),
				resultSet.getLong("calls"), resultSet.getDouble("total_exec_time"), resultSet.getLong("rows"),
				resultSet.getLong("shared_blks_hit"), resultSet.getLong("shared_blks_read")));
			if (statements.isEmpty()) {
				return DatabaseStatistics.unobserved();
			}
			return DatabaseStatistics.observed(statements);
		} catch (Exception exception) {
			return DatabaseStatistics.unobserved();
		}
	}

	private int measurementProposalCount(Scenario scenario) {
		if (controlledMeasurementProposalCount != null) {
			return controlledMeasurementProposalCount;
		}
		return scenario == Scenario.ACCEPT_FINAL ? 500 : 1_000;
	}

	private boolean shouldFailFuture(ResponseCommand command) {
		return controlledFutureFailureIdempotencyKeySuffix != null
			&& command.idempotencyKey().endsWith(controlledFutureFailureIdempotencyKeySuffix);
	}

	private Instant respondByFor(MaterializedCommands materialized, ResponseCommand command) {
		return materialized.manifestRows().stream()
			.filter(row -> row.proposalId() == command.proposalId() && row.commandTarget())
			.map(PrivateManifestRow::respondBy)
			.findFirst()
			.orElseThrow(() -> new IllegalStateException("materialized command respondBy가 누락되었습니다."));
	}

	private String expectedQueueTimestamp(Scenario scenario, boolean commandTarget) {
		if (scenario == Scenario.REQUEUE && commandTarget) {
			return "CHANGED";
		}
		if ((scenario == Scenario.REQUEUE || scenario == Scenario.CANCEL) && !commandTarget) {
			return "UNCHANGED";
		}
		return "NOT_APPLICABLE";
	}

	private List<PrivateManifestRow> observePrivateManifestRows(
		Scenario scenario,
		int round,
		boolean warmUp,
		MaterializedCommands materialized) {
		List<PrivateManifestRow> observedRows = new ArrayList<>();
		for (PrivateManifestRow row : materialized.manifestRows()) {
			Map<String, Object> actual = jdbcTemplate.queryForMap("""
				select proposal.status as proposal_status, member.response_status as member_response_status,
					request.status as request_status, request.queued_at, request.priority_since
				from match_proposal_members member
				join match_proposals proposal on proposal.id = member.proposal_id
				join match_requests request on request.id = member.match_request_id
				where member.proposal_id = ? and member.match_request_id = ?
				""", row.memberProposalId(), row.memberRequestId());
			String observedQueueTimestamp = observedQueueTimestamp(row, actual);
			observedRows.add(row.withObservation(round, warmUp, (String)actual.get("proposal_status"),
				(String)actual.get("member_response_status"), (String)actual.get("request_status"),
				observedQueueTimestamp));
		}
		return List.copyOf(observedRows);
	}

	private String observedQueueTimestamp(PrivateManifestRow row, Map<String, Object> actual) {
		if (row.expectedQueueTimestamp().equals("NOT_APPLICABLE")) {
			return "NOT_APPLICABLE";
		}
		Instant actualQueuedAt = ((Timestamp)actual.get("queued_at")).toInstant();
		Instant actualPrioritySince = ((Timestamp)actual.get("priority_since")).toInstant();
		if (actualQueuedAt.equals(row.initialQueuedAt()) && actualPrioritySince.equals(row.initialPrioritySince())) {
			return "UNCHANGED";
		}
		return "CHANGED";
	}

	private FinalStateAssertion assertScenarioFinalState(
		Scenario scenario,
		MaterializedCommands materialized,
		ExecutionResult execution,
		boolean requirePerProposalCurrentStateGroups) {
		long proposalCount = jdbcTemplate.queryForObject(
			"select count(*) from match_proposals where status = ?", Long.class, scenario.expectedProposalStatus());
		long memberResponseCount = jdbcTemplate.queryForObject(
			"select count(*) from match_proposal_members where response_status = ?", Long.class,
			scenario.expectedMemberResponseStatus(true));
		long requestStatusCount = jdbcTemplate.queryForObject(
			"select count(*) from match_requests where status = ?", Long.class, scenario.expectedRequestStatus(true));
		long partyCount = jdbcTemplate.queryForObject("select count(*) from match_parties", Long.class);
		long partyParticipantCount = jdbcTemplate.queryForObject("select count(*) from match_party_participants",
			Long.class);
		long expectedProposalCount = scenario == Scenario.ACCEPT_FINAL ? 500L : 1_000L;
		long expectedPartyCount = scenario == Scenario.ACCEPT_FINAL ? 500L : 0L;
		long expectedParticipantCount = scenario == Scenario.ACCEPT_FINAL ? 1_000L : 0L;
		PartyGroupDistribution partyGroups = scenario == Scenario.ACCEPT_FINAL
			? loadFinalPartyGroups() : PartyGroupDistribution.notApplicable();
		long expectedRequestStatusCount = scenario == Scenario.ACCEPT_FINAL ? 1_000L
			: scenario == Scenario.CANCEL ? 1_000L : 2_000L;
		TransitionDistribution transitions = loadTransitionDistribution(scenario, execution.commandExecutions());
		CurrentStateDistribution currentStates = inspectCurrentStateDistribution(scenario, execution,
			requirePerProposalCurrentStateGroups);
		MaterializedStateFacts materializedFacts = loadMaterializedStateFacts(scenario, materialized);
		IdempotencyRecordFacts idempotencyRecords = loadIdempotencyRecordFacts(materialized.commands());
		long duplicatePartyCount = Math.max(0L, partyCount - expectedPartyCount);
		long partialSuccessCount = Math.max(0L, expectedProposalCount - proposalCount);
		boolean passed = proposalCount == expectedProposalCount
			&& memberResponseCount == materialized.commands().size()
			&& requestStatusCount == expectedRequestStatusCount
			&& partyParticipantCount == expectedParticipantCount
			&& partyGroups.groupCount() == expectedPartyCount
			&& partyGroups.matchesExpectedState()
			&& currentStates.responseCount() == execution.physicalRequestCount()
			&& currentStates.matchesExpectedState()
			&& transitions.matchesExpectedDistribution()
			&& materializedFacts.matchesExpectedState()
			&& idempotencyRecords.matchesExpectedState()
			&& duplicatePartyCount == 0L
			&& partialSuccessCount == 0L;
		return new FinalStateAssertion(passed, proposalCount, memberResponseCount, requestStatusCount,
			partyCount, partyParticipantCount, currentStates.proposedCount(), currentStates.terminalCount(),
			currentStates.otherCount(), currentStates.matchedExpectedStateCount(), transitions.nonterminalCount(),
			transitions.terminalCount(), partyGroups.groupCount(), duplicatePartyCount, partialSuccessCount,
			materializedFacts.proposalMatchCount(), materializedFacts.proposalMismatchCount(),
			materializedFacts.memberMatchCount(), materializedFacts.memberMismatchCount(),
			materializedFacts.requestMatchCount(), materializedFacts.requestMismatchCount(),
			materializedFacts.queueTimestampMatchCount(), materializedFacts.queueTimestampMismatchCount(),
			idempotencyRecords.recordCount(), idempotencyRecords.matchCount(), idempotencyRecords.mismatchCount());
	}

	private MaterializedStateFacts loadMaterializedStateFacts(Scenario scenario, MaterializedCommands materialized) {
		Map<Long, String> proposalStatuses = new HashMap<>();
		for (Map<String, Object> proposal : jdbcTemplate.queryForList("select id, status from match_proposals")) {
			proposalStatuses.put(((Number)proposal.get("id")).longValue(), (String)proposal.get("status"));
		}
		Map<MemberKey, Map<String, Object>> membersById = new HashMap<>();
		for (Map<String, Object> member : jdbcTemplate.queryForList("""
			select member.proposal_id, member.match_request_id, member.response_status,
				request.status as request_status, request.queued_at, request.priority_since
			from match_proposal_members member
			join match_requests request on request.id = member.match_request_id
			""")) {
			membersById.put(new MemberKey(((Number)member.get("proposal_id")).longValue(),
				((Number)member.get("match_request_id")).longValue()), member);
		}
		long proposalMatchCount = 0L;
		long proposalMismatchCount = 0L;
		for (Long proposalId : materialized.manifestRows().stream().map(PrivateManifestRow::proposalId).distinct()
			.toList()) {
			PrivateManifestRow row = materialized.manifestRows().stream()
				.filter(candidate -> candidate.proposalId() == proposalId)
				.findFirst()
				.orElseThrow();
			if (row.expectedProposalStatus().equals(proposalStatuses.get(proposalId))) {
				proposalMatchCount++;
			} else {
				proposalMismatchCount++;
			}
		}
		long memberMatchCount = 0L;
		long memberMismatchCount = 0L;
		long requestMatchCount = 0L;
		long requestMismatchCount = 0L;
		long queueTimestampMatchCount = 0L;
		long queueTimestampMismatchCount = 0L;
		for (PrivateManifestRow row : materialized.manifestRows()) {
			Map<String, Object> actual = membersById.get(new MemberKey(row.memberProposalId(), row.memberRequestId()));
			if (actual == null) {
				memberMismatchCount++;
				requestMismatchCount++;
				queueTimestampMismatchCount++;
				continue;
			}
			if (row.expectedMemberResponseStatus().equals(actual.get("response_status"))) {
				memberMatchCount++;
			} else {
				memberMismatchCount++;
			}
			if (row.expectedRequestStatus().equals(actual.get("request_status"))) {
				requestMatchCount++;
			} else {
				requestMismatchCount++;
			}
			if (matchesExpectedQueueTimestamps(scenario, row, actual)) {
				queueTimestampMatchCount++;
			} else {
				queueTimestampMismatchCount++;
			}
		}
		boolean matchesExpectedState = proposalMismatchCount == 0L && memberMismatchCount == 0L
			&& requestMismatchCount == 0L && queueTimestampMismatchCount == 0L;
		return new MaterializedStateFacts(proposalMatchCount, proposalMismatchCount, memberMatchCount,
			memberMismatchCount, requestMatchCount, requestMismatchCount, queueTimestampMatchCount,
			queueTimestampMismatchCount, matchesExpectedState);
	}

	private IdempotencyRecordFacts loadIdempotencyRecordFacts(List<ResponseCommand> commands) {
		List<Map<String, Object>> records = jdbcTemplate.queryForList("""
			select user_id, idempotency_key, operation, payload_fingerprint,
				result_entity_type, result_entity_id, result_state
			from match_idempotency_records
			""");
		Map<String, List<Map<String, Object>>> recordsByKey = new HashMap<>();
		for (Map<String, Object> record : records) {
			String key = idempotencyRecordKey(((Number)record.get("user_id")).longValue(),
				(String)record.get("idempotency_key"));
			recordsByKey.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
		}
		long matchCount = 0L;
		for (ResponseCommand command : commands) {
			List<Map<String, Object>> matchingRecords = recordsByKey.getOrDefault(
				idempotencyRecordKey(command.userId(), command.idempotencyKey()), List.of());
			if (matchingRecords.size() == 1 && matchesExpectedIdempotencyRecord(command, matchingRecords.getFirst())) {
				matchCount++;
			}
		}
		long mismatchCount = Math.max(0L, records.size() - matchCount);
		boolean matchesExpectedState = records.size() == commands.size() && matchCount == commands.size();
		return new IdempotencyRecordFacts(records.size(), matchCount, mismatchCount, matchesExpectedState);
	}

	private boolean matchesExpectedIdempotencyRecord(ResponseCommand command, Map<String, Object> record) {
		return ((Number)record.get("user_id")).longValue() == command.userId()
			&& command.idempotencyKey().equals(record.get("idempotency_key"))
			&& "MATCH_PROPOSAL_RESPONSE".equals(record.get("operation"))
			&& (command.proposalId() + ":" + command.action().name()).equals(record.get("payload_fingerprint"))
			&& "MATCH_PROPOSAL".equals(record.get("result_entity_type"))
			&& String.valueOf(command.proposalId()).equals(String.valueOf(record.get("result_entity_id")))
			&& "RESPONDED".equals(record.get("result_state"));
	}

	private String idempotencyRecordKey(long userId, String idempotencyKey) {
		return userId + ":" + idempotencyKey;
	}

	private boolean matchesExpectedQueueTimestamps(Scenario scenario, PrivateManifestRow row,
		Map<String, Object> actual) {
		Instant actualQueuedAt = ((Timestamp)actual.get("queued_at")).toInstant();
		Instant actualPrioritySince = ((Timestamp)actual.get("priority_since")).toInstant();
		if (scenario == Scenario.REQUEUE && row.commandTarget()) {
			return !actualQueuedAt.equals(row.initialQueuedAt())
				&& !actualPrioritySince.equals(row.initialPrioritySince());
		}
		if ((scenario == Scenario.REQUEUE || scenario == Scenario.CANCEL) && !row.commandTarget()) {
			return actualQueuedAt.equals(row.initialQueuedAt())
				&& actualPrioritySince.equals(row.initialPrioritySince());
		}
		return true;
	}

	private PartyGroupDistribution loadFinalPartyGroups() {
		List<Map<String, Object>> groups = jdbcTemplate.queryForList(
			"""
				select member.proposal_id, count(distinct participant.party_id) as party_count,
					count(participant.user_id) as participant_count
				from match_proposal_members member
				join match_proposals proposal on proposal.id = member.proposal_id and proposal.status = 'CONFIRMED'
				left join match_party_participants participant on participant.user_id = member.user_id and participant.left_at is null
				group by member.proposal_id
				""");
		boolean matchesExpectedState = groups.size() == 500;
		for (Map<String, Object> group : groups) {
			matchesExpectedState = matchesExpectedState
				&& ((Number)group.get("party_count")).longValue() == 1L
				&& ((Number)group.get("participant_count")).longValue() == 2L;
		}
		return new PartyGroupDistribution(groups.size(), matchesExpectedState);
	}

	private TransitionDistribution loadTransitionDistribution(
		Scenario scenario,
		List<CommandExecution> commandExecutions) {
		Map<ResponseCommand, String> results = new LinkedHashMap<>();
		long nonterminalCount = 0L;
		long terminalCount = 0L;
		boolean matchesExpectedState = true;
		for (CommandExecution execution : commandExecutions) {
			ResponseCommand command = execution.command();
			if (results.containsKey(command)) {
				continue;
			}
			List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
				select member.responded_at, proposal.confirmed_at, proposal.status
				from match_proposal_members member
				join match_proposals proposal on proposal.id = member.proposal_id
				where member.proposal_id = ? and member.user_id = ?
				""", command.proposalId(), command.userId());
			TransitionObservation observation = rows.isEmpty()
				? new TransitionObservation(scenario.result(), false)
				: transitionObservation(scenario, rows.getFirst());
			String result = observation.result();
			matchesExpectedState = matchesExpectedState && observation.matchesExpectedState();
			results.put(command, result);
			if (result.equals("NON_TERMINAL")) {
				nonterminalCount++;
			} else {
				terminalCount++;
			}
		}
		boolean expectedDistribution = scenario != Scenario.ACCEPT_FINAL
			|| (nonterminalCount == 500L && terminalCount == 500L);
		return new TransitionDistribution(Map.copyOf(results), nonterminalCount, terminalCount,
			matchesExpectedState && expectedDistribution);
	}

	private TransitionObservation transitionObservation(Scenario scenario, Map<String, Object> row) {
		String proposalStatus = (String)row.get("status");
		if (scenario == Scenario.ACCEPT_FINAL) {
			Timestamp respondedAt = (Timestamp)row.get("responded_at");
			Timestamp confirmedAt = (Timestamp)row.get("confirmed_at");
			if (respondedAt == null || confirmedAt == null) {
				return new TransitionObservation(scenario.result(), false);
			}
			if (respondedAt.before(confirmedAt)) {
				return new TransitionObservation("NON_TERMINAL", true);
			}
			if (respondedAt.equals(confirmedAt)) {
				return new TransitionObservation("TERMINAL", true);
			}
			return new TransitionObservation(scenario.result(), false);
		}
		if (scenario == Scenario.ACCEPT_NON_TERMINAL && "OPEN".equals(proposalStatus)) {
			return new TransitionObservation("NON_TERMINAL", true);
		}
		if (scenario != Scenario.ACCEPT_NON_TERMINAL && scenario.expectedProposalStatus().equals(proposalStatus)) {
			return new TransitionObservation("TERMINAL", true);
		}
		return new TransitionObservation(scenario.result(), false);
	}

	private CurrentStateDistribution inspectCurrentStateDistribution(
		Scenario scenario,
		ExecutionResult execution,
		boolean requirePerProposalCurrentStateGroups) {
		long proposedCount = execution.currentStates().stream()
			.filter(observation -> observation.state().equals("PROPOSED")).count();
		long terminalCount = execution.currentStates().stream()
			.filter(observation -> observation.state().equals("PREPARING")
				|| observation.state().equals("ACTIVE"))
			.count();
		long otherCount = execution.currentStates().size() - proposedCount - terminalCount;
		if (!requirePerProposalCurrentStateGroups) {
			long matchedExpectedStateCount = execution.currentStates().stream()
				.filter(observation -> scenario.matchesExpectedCurrentState(observation.state())).count();
			return new CurrentStateDistribution(proposedCount, terminalCount, otherCount, matchedExpectedStateCount,
				execution.currentStates().size(), matchedExpectedStateCount == execution.currentStates().size());
		}
		if (scenario != Scenario.ACCEPT_FINAL) {
			long expectedCount = execution.currentStates().stream()
				.filter(observation -> scenario.matchesExpectedCurrentState(observation.state())).count();
			return new CurrentStateDistribution(proposedCount, terminalCount, otherCount, expectedCount,
				execution.currentStates().size(), expectedCount == execution.currentStates().size());
		}
		Map<Long, List<String>> statesByProposal = new HashMap<>();
		for (CurrentStateObservation observation : execution.currentStates()) {
			statesByProposal.computeIfAbsent(observation.proposalId(), ignored -> new ArrayList<>())
				.add(observation.state());
		}
		boolean matchesExpectedState = statesByProposal.size() == 500;
		for (List<String> states : statesByProposal.values()) {
			matchesExpectedState = matchesExpectedState && states.size() == 2
				&& states.stream().allMatch(state -> state.equals("PROPOSED")
					|| state.equals("PREPARING") || state.equals("ACTIVE"));
		}
		long matchedExpectedStateCount = matchesExpectedState ? execution.currentStates().size() : 0L;
		return new CurrentStateDistribution(proposedCount, terminalCount, otherCount, matchedExpectedStateCount,
			execution.currentStates().size(), matchesExpectedState && otherCount == 0L
				&& execution.currentStates().size() == 1_000);
	}

	private Path writeArtifact(List<RoundArtifact> rounds, List<PrivateManifestRow> privateManifestRows) {
		return writeArtifact(rounds, privateManifestRows,
			Path.of("docs/measurements/results/match-01/response-completion"));
	}

	private Path writeArtifact(
		List<RoundArtifact> rounds,
		List<PrivateManifestRow> privateManifestRows,
		Path directory) {
		try {
			if (Boolean.getBoolean("issue776.measurement")) {
				requireCleanMeasurementSource(directory);
			}
			Files.createDirectories(directory);
			String commit = new String(
				new ProcessBuilder("git", "rev-parse", "HEAD").start().getInputStream().readAllBytes()).trim();
			MatchResponseCompletionBaselineSupport.Fixture fixture = MatchResponseCompletionBaselineSupport
				.contractFixture();
			String materializedManifestSummary = rounds.stream()
				.map(round -> round.scenario() + "," + round.round() + "," + round.warmUp() + ","
					+ round.rawSamples().size() + "," + round.finalState().proposalCount() + ","
					+ round.finalState().idempotencyRecordCount() + "," + round.finalState().partyCount())
				.collect(java.util.stream.Collectors.joining("\n",
					"scenario,round,warmUp,sampleCount,proposalCount,idempotencyCount,partyCount\n", "\n"));
			Path privateManifest = directory.resolve("response-completion-" + commit + "-private-sidecar.json");
			String privateManifestBytes = objectMapper.writeValueAsString(privateSidecarRows(privateManifestRows));
			Files.writeString(privateManifest, privateManifestBytes);
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("measuredGitCommitSha", commit);
			root.put("environment", measurementEnvironment());
			root.put("fixture", Map.of("seed", "MATCH-01-RESPONSE-COMPLETION-V2",
				"fixtureInput", fixture.csv(),
				"fixtureInputSha256", fixture.fixtureInputSha256(),
				"materializedManifestSummary", materializedManifestSummary,
				"privateCanonicalManifestFile", privateManifest.getFileName().toString(),
				"materializedManifestSha256", sha256(privateManifestBytes)));
			List<Map<String, Object>> scenarios = new ArrayList<>();
			for (Scenario scenario : Scenario.values()) {
				List<RoundArtifact> scenarioRounds = rounds.stream()
					.filter(round -> round.scenario().equals(scenario.name())).toList();
				Map<String, Object> scenarioNode = new LinkedHashMap<>();
				scenarioNode.put("scenario", scenario.name());
				RoundArtifact warmUp = scenarioRounds.stream().filter(RoundArtifact::warmUp).findFirst().orElse(null);
				scenarioNode.put("warmUp", Map.of("completed", warmUp != null && warmUp.invalidReason() == null));
				List<Map<String, Object>> measured = new ArrayList<>();
				for (RoundArtifact round : scenarioRounds.stream().filter(round -> !round.warmUp()).toList()) {
					String raw = objectMapper.writeValueAsString(round.rawSamples());
					FinalStateAssertion assertion = round.finalState();
					Map<String, Object> assertionNode = new LinkedHashMap<>();
					assertionNode.put("passed", assertion.passed());
					assertionNode.put("proposalCount", assertion.proposalCount());
					assertionNode.put("memberResponseCount", assertion.memberResponseCount());
					assertionNode.put("requestStatusCount", assertion.requestStatusCount());
					assertionNode.put("partyCount", assertion.partyCount());
					assertionNode.put("partyParticipantCount", assertion.partyParticipantCount());
					assertionNode.put("proposedCurrentStateCount", assertion.proposedCurrentStateCount());
					assertionNode.put("terminalCurrentStateCount", assertion.terminalCurrentStateCount());
					assertionNode.put("otherCurrentStateCount", assertion.otherCurrentStateCount());
					assertionNode.put("matchedExpectedCurrentStateCount", assertion.matchedExpectedCurrentStateCount());
					assertionNode.put("currentStateCount", assertion.matchedExpectedCurrentStateCount());
					assertionNode.put("nonterminalTransitionCount", assertion.nonterminalTransitionCount());
					assertionNode.put("terminalTransitionCount", assertion.terminalTransitionCount());
					assertionNode.put("completePartyGroupCount", assertion.completePartyGroupCount());
					assertionNode.put("duplicatePartyCount", assertion.duplicatePartyCount());
					assertionNode.put("partialSuccessCount", assertion.partialSuccessCount());
					assertionNode.put("proposalFactMatchCount", assertion.proposalFactMatchCount());
					assertionNode.put("proposalFactMismatchCount", assertion.proposalFactMismatchCount());
					assertionNode.put("memberFactMatchCount", assertion.memberFactMatchCount());
					assertionNode.put("memberFactMismatchCount", assertion.memberFactMismatchCount());
					assertionNode.put("requestFactMatchCount", assertion.requestFactMatchCount());
					assertionNode.put("requestFactMismatchCount", assertion.requestFactMismatchCount());
					assertionNode.put("queueTimestampMatchCount", assertion.queueTimestampMatchCount());
					assertionNode.put("queueTimestampMismatchCount", assertion.queueTimestampMismatchCount());
					assertionNode.put("idempotencyRecordCount", assertion.idempotencyRecordCount());
					assertionNode.put("idempotencyRecordMatchCount", assertion.idempotencyRecordMatchCount());
					assertionNode.put("idempotencyRecordMismatchCount", assertion.idempotencyRecordMismatchCount());
					Map<String, Object> roundNode = new LinkedHashMap<>();
					roundNode.put("round", round.round());
					roundNode.put("rawSamples", round.rawSamples());
					roundNode.put("rawDataSha256", sha256(raw));
					roundNode.put("dbStatistics", round.databaseStatistics().toArtifactNode());
					roundNode.put("lockWait", round.lockWait().toArtifactNode(round.rawSamples()));
					roundNode.put("metrics", round.metrics().toArtifactNode());
					roundNode.put("observationWindow", Map.of("startedAt", round.observationStartedAt().toString(),
						"endedAt", round.observationEndedAt().toString()));
					roundNode.put("finalStateAssertion", assertionNode);
					if (round.invalidReason() != null) {
						roundNode.put("invalidReason", round.invalidReason());
					}
					measured.add(roundNode);
				}
				scenarioNode.put("measuredRounds", measured);
				scenarios.add(scenarioNode);
			}
			root.put("scenarios", scenarios);
			Path artifact = directory.resolve("response-completion-" + commit + ".json");
			String publicArtifactBytes = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root)
				.replace("\r\n", "\n");
			Files.writeString(artifact, publicArtifactBytes);
			writeResultDocument(directory, artifact, privateManifest, publicArtifactBytes, rounds);
			return artifact;
		} catch (Exception exception) {
			throw new AssertionError("response completion artifact 기록 실패", exception);
		}
	}

	private Map<String, Object> measurementEnvironment() {
		Map<String, Object> environment = new LinkedHashMap<>();
		environment.put("schedulingEnabled", springEnvironment.getProperty("spring.task.scheduling.enabled", "true"));
		environment.put("notificationRelayEnabled",
			springEnvironment.getProperty("app.notification.relay.enabled", "true"));
		environment.put("chatRetentionEnabled", springEnvironment.getProperty("app.chat.retention.enabled", "true"));
		environment.put("flywayEnabled", springEnvironment.getProperty("spring.flyway.enabled", "true"));
		environment.put("hibernateDdlAuto",
			springEnvironment.getProperty("spring.jpa.hibernate.ddl-auto", "none"));
		environment.put("fixtureInsertMode", "single-statement-multi-values");

		Map<String, String> profile = new LinkedHashMap<>();
		profile.put("target", measurementProfileValue("issue776.measurement.target", "testcontainer"));
		profile.put("stackId", measurementProfileValue("issue776.environment.stackId", "local"));
		profile.put("region", measurementProfileValue("issue776.environment.region", "local"));
		profile.put("releaseSha", measurementProfileValue("issue776.environment.releaseSha", "local"));
		profile.put("appInstanceType", measurementProfileValue("issue776.environment.appInstanceType", "local"));
		profile.put("postgresInstanceType",
			measurementProfileValue("issue776.environment.postgresInstanceType", "local"));
		profile.put("redisInstanceType", measurementProfileValue("issue776.environment.redisInstanceType", "local"));
		profile.put("backendImage", measurementProfileValue("issue776.environment.backendImage", "local"));
		profile.put("webImage", measurementProfileValue("issue776.environment.webImage", "local"));
		profile.put("postgresImage", measurementProfileValue("issue776.environment.postgresImage", "local"));
		profile.put("redisImage", measurementProfileValue("issue776.environment.redisImage", "local"));
		profile.put("applicationConfigSha256",
			measurementProfileValue("issue776.environment.applicationConfigSha256", "local"));
		profile.put("responseTopology",
			measurementProfileValue("issue776.environment.responseTopology", "single-jvm-direct-jdbc"));
		environment.put("profile", profile);
		return environment;
	}

	private static boolean externalMeasurement() {
		return Boolean.getBoolean("issue776.external");
	}

	private static String requiredEnvironmentVariable(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " 환경변수가 필요합니다.");
		}
		return value;
	}

	private static void validateExternalMeasurementConfiguration() {
		requiredEnvironmentVariable("ISSUE776_JDBC_URL");
		requiredEnvironmentVariable("ISSUE776_JDBC_USERNAME");
		requiredEnvironmentVariable("ISSUE776_JDBC_PASSWORD");
		for (String propertyName : REQUIRED_EXTERNAL_PROFILE_PROPERTIES) {
			requiredSystemProperty(propertyName);
		}
	}

	private static String requiredSystemProperty(String name) {
		String value = System.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalStateException(name + " 시스템 속성이 필요합니다.");
		}
		return value;
	}

	private static void requireCleanMeasurementSource(Path outputDirectory) throws Exception {
		Path workingDirectory = Path.of("").toAbsolutePath().normalize();
		Path absoluteOutputDirectory = outputDirectory.toAbsolutePath().normalize();
		String allowedOutputPath = workingDirectory.relativize(absoluteOutputDirectory).toString()
			.replace('\\', '/')
			.replaceAll("/+$", "") + "/";
		Process process = new ProcessBuilder("git", "status", "--porcelain", "--untracked-files=all")
			.redirectErrorStream(true)
			.start();
		String status = new String(process.getInputStream().readAllBytes()).trim();
		int exitCode = process.waitFor();
		if (exitCode != 0) {
			throw new IllegalStateException("측정 전 git status 확인에 실패했습니다.");
		}
		List<String> unexpectedChanges = status.lines()
			.filter(line -> !line.isBlank())
			.filter(line -> !line.substring(Math.min(3, line.length())).trim().replace('\\', '/')
				.startsWith(allowedOutputPath))
			.toList();
		if (!unexpectedChanges.isEmpty()) {
			throw new IllegalStateException("측정 source가 clean commit이 아닙니다: " + unexpectedChanges);
		}
	}

	private static String measurementProfileValue(String propertyName, String localDefault) {
		String value = System.getProperty(propertyName);
		if (value != null && !value.isBlank()) {
			return value;
		}
		if (externalMeasurement()) {
			throw new IllegalStateException(propertyName + " 시스템 속성이 필요합니다.");
		}
		return localDefault;
	}

	private void writeResultDocument(
		Path directory,
		Path artifact,
		Path privateManifest,
		String publicArtifactBytes,
		List<RoundArtifact> rounds) throws Exception {
		StringBuilder document = new StringBuilder("# MATCH-01 응답 완료 baseline 결과\n\n");
		document.append("- 측정 실행 SHA: `").append(artifact.getFileName().toString()
			.replace("response-completion-", "").replace(".json", "")).append("`\n");
		document.append("- private sidecar: `").append(privateManifest.getFileName()).append("`\n");
		document.append("- materialized sidecar SHA-256: `").append(sha256(Files.readString(privateManifest)))
			.append("`\n");
		document.append("- artifact: `").append(artifact.getFileName()).append("`\n");
		document.append("- artifact SHA-256: `").append(sha256(publicArtifactBytes)).append("`\n");
		document.append("- 판정: `").append(measurementOutcome(rounds)).append("`\n\n");
		document.append(
			"| 시나리오 | round | p50 (ns) | p95 (ns) | p99 (ns) | 처리량 (req/s) | retry (total/max) | lock wait (sampled/raw ns) | 실패율 |\n");
		document.append("| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |\n");
		for (Scenario scenario : Scenario.values()) {
			List<RoundArtifact> measuredRounds = rounds.stream()
				.filter(round -> round.scenario().equals(scenario.name()) && !round.warmUp())
				.toList();
			for (RoundArtifact round : measuredRounds) {
				RoundMetrics metrics = round.metrics();
				document.append("| ").append(scenario.name()).append(" | ").append(round.round()).append(" | ")
					.append(metrics.latencyNanos().p50()).append(" | ")
					.append(metrics.latencyNanos().p95()).append(" | ")
					.append(metrics.latencyNanos().p99()).append(" | ")
					.append(String.format(java.util.Locale.ROOT, "%.3f", metrics.throughputPerSecond())).append(" | ")
					.append(metrics.retry().total()).append("/").append(metrics.retry().max()).append(" | ")
					.append(round.lockWait().sampledWaitNanos()).append("/")
					.append(round.lockWait().sampleTotalNanos(round.rawSamples())).append(" | ")
					.append(String.format(java.util.Locale.ROOT, "%.6f", metrics.failure().rate())).append(" |\n");
			}
		}
		document.append("\n| 시나리오 | 세 measured round p95 중앙값 (ns) | 세 measured round p95 최댓값 (ns) |\n");
		document.append("| --- | ---: | ---: |\n");
		for (Scenario scenario : Scenario.values()) {
			List<Long> p95Values = rounds.stream()
				.filter(round -> round.scenario().equals(scenario.name()) && !round.warmUp())
				.map(round -> round.metrics().latencyNanos().p95())
				.sorted()
				.toList();
			if (p95Values.size() == 3) {
				document.append("| ").append(scenario.name()).append(" | ").append(p95Values.get(1)).append(" | ")
					.append(p95Values.getLast()).append(" |\n");
			}
		}
		document.append("\n각 measured round는 1,000개 비식별 raw sample과 fixture/DB 통계/lock-wait 관측을 보존한다. "
			+ "이 결과는 운영 SLO 또는 후보 선점 baseline 통과를 의미하지 않는다.\n");
		Files.writeString(directory.resolve("response-completion-baseline-result.md"), document.toString());
	}

	private String measurementOutcome(List<RoundArtifact> rounds) {
		if (rounds.size() != 16 || rounds.stream().anyMatch(round -> round.invalidReason() != null
			|| !round.databaseStatistics().observed() || !round.lockWait().observed())) {
			return "INVALID";
		}
		if (rounds.stream().anyMatch(round -> !round.finalState().passed())) {
			return "FAILED";
		}
		return "RESPONSE_BASELINE_ACCEPTED";
	}

	private List<Map<String, Object>> privateSidecarRows(List<PrivateManifestRow> privateManifestRows) {
		List<Map<String, Object>> rows = new ArrayList<>();
		for (PrivateManifestRow row : privateManifestRows) {
			Map<String, Object> sidecarRow = new LinkedHashMap<>();
			sidecarRow.put("scenario", row.scenario());
			sidecarRow.put("warmUp", row.warmUp());
			sidecarRow.put("round", row.round());
			sidecarRow.put("proposalOrdinal", row.proposalOrdinal());
			sidecarRow.put("memberOrdinal", row.memberOrdinal());
			sidecarRow.put("proposalId", row.proposalId());
			sidecarRow.put("memberProposalId", row.memberProposalId());
			sidecarRow.put("memberRequestId", row.memberRequestId());
			sidecarRow.put("requestId", row.requestId());
			sidecarRow.put("expected", Map.of("proposalStatus", row.expectedProposalStatus(),
				"memberResponseStatus", row.expectedMemberResponseStatus(), "requestStatus",
				row.expectedRequestStatus(), "queueTimestamp", row.expectedQueueTimestamp()));
			sidecarRow.put("observed", Map.of("proposalStatus", row.observedProposalStatus(),
				"memberResponseStatus", row.observedMemberResponseStatus(), "requestStatus",
				row.observedRequestStatus(), "queueTimestamp", row.observedQueueTimestamp()));
			rows.add(sidecarRow);
		}
		return List.copyOf(rows);
	}

	private String sha256(String value) {
		try {
			return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes()));
		} catch (Exception exception) {
			throw new IllegalStateException(exception);
		}
	}

	private enum Scenario {
		ACCEPT_NON_TERMINAL(MatchProposalResponseAction.ACCEPT),
		ACCEPT_FINAL(MatchProposalResponseAction.ACCEPT),
		REQUEUE(MatchProposalResponseAction.REQUEUE),
		CANCEL(MatchProposalResponseAction.CANCEL);

		private final MatchProposalResponseAction action;

		Scenario(MatchProposalResponseAction action) {
			this.action = action;
		}

		MatchProposalResponseAction action() {
			return action;
		}

		String result() {
			return this == ACCEPT_NON_TERMINAL ? "NON_TERMINAL" : "TERMINAL";
		}

		String expectedProposalStatus() {
			return switch (this) {
				case ACCEPT_NON_TERMINAL -> "OPEN";
				case ACCEPT_FINAL -> "CONFIRMED";
				case REQUEUE -> "DECLINED";
				case CANCEL -> "CANCELED";
			};
		}

		String expectedMemberResponseStatus(boolean commandTarget) {
			if (!commandTarget) {
				return "PENDING";
			}
			return switch (this) {
				case ACCEPT_NON_TERMINAL, ACCEPT_FINAL -> "ACCEPTED";
				case REQUEUE -> "REQUEUED";
				case CANCEL -> "CANCELED";
			};
		}

		String expectedRequestStatus(boolean commandTarget) {
			if (!commandTarget && this == CANCEL) {
				return "WAITING";
			}
			return switch (this) {
				case ACCEPT_NON_TERMINAL -> "PROPOSED";
				case ACCEPT_FINAL -> "MATCHED";
				case REQUEUE -> "WAITING";
				case CANCEL -> "CANCELED";
			};
		}

		boolean matchesExpectedCurrentState(String state) {
			if (this == ACCEPT_FINAL) {
				return state.equals("PROPOSED") || state.equals("PREPARING") || state.equals("ACTIVE");
			}
			return state.equals(switch (this) {
				case ACCEPT_NON_TERMINAL -> "PROPOSED";
				case REQUEUE -> "WAITING";
				case CANCEL -> "EMPTY";
				case ACCEPT_FINAL -> throw new IllegalStateException("ACCEPT_FINAL은 위에서 처리합니다.");
			});
		}
	}

	private record ResponseCommand(long userId, long proposalId, MatchProposalResponseAction action,
		String idempotencyKey) {
	}
	private record MaterializedCommands(List<ResponseCommand> commands, List<PrivateManifestRow> manifestRows,
		Instant fixtureReferenceTime) {
	}
	private record ExecutionResult(int physicalRequestCount, int readyRequestCount,
		List<CommandExecution> commandExecutions, List<CommandFailure> failures) {
		List<CurrentStateObservation> currentStates() {
			return commandExecutions.stream()
				.map(execution -> new CurrentStateObservation(execution.command().proposalId(),
					execution.currentState()))
				.toList();
		}
	}
	private record SubmittedRequest(ResponseCommand command, Future<CommandExecution> future) {
	}
	private record CommandFailure(ResponseCommand command, String reason) {
		static CommandFailure forCommand(ResponseCommand command, String reason) {
			return new CommandFailure(command, reason);
		}

		static CommandFailure general(String reason) {
			return new CommandFailure(null, reason);
		}
	}
	private record CommandExecution(ResponseCommand command, String currentState, RecordedProbeSample probeSample) {
	}
	private record CurrentStateObservation(long proposalId, String state) {
	}
	private record CurrentStateDistribution(long proposedCount, long terminalCount, long otherCount,
		long matchedExpectedStateCount, long responseCount, boolean matchesExpectedState) {
	}
	private record PartyGroupDistribution(long groupCount, boolean matchesExpectedState) {
		static PartyGroupDistribution notApplicable() {
			return new PartyGroupDistribution(0L, true);
		}
	}
	private record MemberKey(long proposalId, long requestId) {
	}
	private record MaterializedStateFacts(long proposalMatchCount, long proposalMismatchCount,
		long memberMatchCount, long memberMismatchCount, long requestMatchCount, long requestMismatchCount,
		long queueTimestampMatchCount, long queueTimestampMismatchCount, boolean matchesExpectedState) {
	}
	private record TransitionDistribution(Map<ResponseCommand, String> results, long nonterminalCount,
		long terminalCount, boolean matchesExpectedDistribution) {
		String resultFor(ResponseCommand command) {
			String result = results.get(command);
			if (result == null) {
				throw new IllegalStateException("raw sample transition fact가 누락되었습니다.");
			}
			return result;
		}
	}
	private record TransitionObservation(String result, boolean matchesExpectedState) {
	}
	private record IdempotencyRecordFacts(long recordCount, long matchCount, long mismatchCount,
		boolean matchesExpectedState) {
	}
	private record PrivateManifestRow(int proposalOrdinal, int memberOrdinal, long proposalId, long memberProposalId,
		long memberRequestId, long requestId, String scenario, boolean commandTarget, String expectedProposalStatus,
		String expectedMemberResponseStatus, String expectedRequestStatus, String expectedQueueTimestamp,
		Instant fixtureReferenceTime, Instant respondBy, Instant initialQueuedAt, Instant initialPrioritySince,
		Integer round, Boolean warmUp, String observedProposalStatus, String observedMemberResponseStatus,
		String observedRequestStatus, String observedQueueTimestamp) {
		PrivateManifestRow withObservation(int round, boolean warmUp, String observedProposalStatus,
			String observedMemberResponseStatus, String observedRequestStatus, String observedQueueTimestamp) {
			return new PrivateManifestRow(proposalOrdinal, memberOrdinal, proposalId, memberProposalId, memberRequestId,
				requestId, scenario, commandTarget, expectedProposalStatus, expectedMemberResponseStatus,
				expectedRequestStatus, expectedQueueTimestamp, fixtureReferenceTime, respondBy, initialQueuedAt,
				initialPrioritySince, round, warmUp, observedProposalStatus, observedMemberResponseStatus,
				observedRequestStatus, observedQueueTimestamp);
		}
	}
	private record StatementStatistics(String queryId, long calls, double totalExecTimeMillis, long rows,
		long sharedBlksHit, long sharedBlksRead) {
		Map<String, Object> toArtifactNode() {
			return Map.of("queryId", queryId, "calls", calls, "totalExecTimeMillis", totalExecTimeMillis,
				"rows", rows, "sharedBlksHit", sharedBlksHit, "sharedBlksRead", sharedBlksRead);
		}
	}

	private record DatabaseStatistics(boolean observed, List<StatementStatistics> statements) {
		static DatabaseStatistics observed(List<StatementStatistics> statements) {
			return new DatabaseStatistics(true, List.copyOf(statements));
		}

		static DatabaseStatistics unobserved() {
			return new DatabaseStatistics(false, List.of());
		}

		Map<String, Object> toArtifactNode() {
			long totalCalls = 0L;
			double totalExecTimeMillis = 0D;
			long totalRows = 0L;
			long sharedBlksHit = 0L;
			long sharedBlksRead = 0L;
			List<Map<String, Object>> statementNodes = new ArrayList<>();
			for (StatementStatistics statement : statements) {
				statementNodes.add(statement.toArtifactNode());
				totalCalls += statement.calls();
				totalExecTimeMillis += statement.totalExecTimeMillis();
				totalRows += statement.rows();
				sharedBlksHit += statement.sharedBlksHit();
				sharedBlksRead += statement.sharedBlksRead();
			}
			Map<String, Object> node = new LinkedHashMap<>();
			node.put("observed", observed);
			node.put("statements", statementNodes);
			node.put("statementCount", statementNodes.size());
			node.put("totalCalls", totalCalls);
			node.put("totalExecTimeMillis", totalExecTimeMillis);
			node.put("totalRows", totalRows);
			node.put("sharedBlksHit", sharedBlksHit);
			node.put("sharedBlksRead", sharedBlksRead);
			return node;
		}
	}

	private record LockWaitPoll(Instant observedAt, long observedAtNanos, long waitingSessionCount) {
	}

	private record LockWaitObservation(boolean observed, long pollCount, long executionWindowPollCount,
		long waitingSessionSampleCount, long sampledWaitNanos, List<LockWaitPoll> polls) {
		static LockWaitObservation unobserved() {
			return new LockWaitObservation(false, 0L, 0L, 0L, 0L, List.of());
		}

		List<Long> attributeWaitNanos(List<RawSampleDraft> drafts) {
			long[] attributed = new long[drafts.size()];
			if (!observed || polls.size() < 2) {
				return java.util.Arrays.stream(attributed).boxed().toList();
			}
			List<LockWaitPoll> orderedPolls = polls.stream()
				.sorted(java.util.Comparator.comparing(LockWaitPoll::observedAtNanos)).toList();
			for (int index = 1; index < orderedPolls.size(); index++) {
				LockWaitPoll previous = orderedPolls.get(index - 1);
				LockWaitPoll current = orderedPolls.get(index);
				if (previous.waitingSessionCount() == 0L) {
					continue;
				}
				if (!previous.observedAt().isBefore(current.observedAt())) {
					continue;
				}
				List<Integer> candidateIndexes = new ArrayList<>();
				for (int draftIndex = 0; draftIndex < drafts.size(); draftIndex++) {
					RawSampleDraft draft = drafts.get(draftIndex);
					if (draft.operationTime() == null || draft.completedAt() == null) {
						continue;
					}
					Instant overlapStart = previous.observedAt().isAfter(draft.operationTime())
						? previous.observedAt() : draft.operationTime();
					Instant overlapEnd = current.observedAt().isBefore(draft.completedAt())
						? current.observedAt() : draft.completedAt();
					if (overlapStart.isBefore(overlapEnd)) {
						candidateIndexes.add(draftIndex);
					}
				}
				if (candidateIndexes.isEmpty()) {
					continue;
				}
				long observedWaitNanos = java.time.Duration.between(previous.observedAt(), current.observedAt())
					.toNanos()
					* previous.waitingSessionCount();
				long perSampleNanos = observedWaitNanos / candidateIndexes.size();
				long remainder = observedWaitNanos % candidateIndexes.size();
				for (int candidateIndex = 0; candidateIndex < candidateIndexes.size(); candidateIndex++) {
					int draftIndex = candidateIndexes.get(candidateIndex);
					attributed[draftIndex] += perSampleNanos + (candidateIndex < remainder ? 1L : 0L);
				}
			}
			return java.util.Arrays.stream(attributed).boxed().toList();
		}

		LockWaitObservation withSampledWaitNanos(long attributedWaitNanos) {
			return new LockWaitObservation(observed, pollCount, executionWindowPollCount,
				waitingSessionSampleCount, attributedWaitNanos, polls);
		}

		long sampleTotalNanos(List<RawSample> samples) {
			return samples.stream().mapToLong(RawSample::lockWaitNanos).sum();
		}

		long sampleMaxNanos(List<RawSample> samples) {
			return samples.stream().mapToLong(RawSample::lockWaitNanos).max().orElse(0L);
		}

		Map<String, Object> toArtifactNode(List<RawSample> samples) {
			return Map.of("observed", observed, "pollCount", pollCount,
				"executionWindowPollCount", executionWindowPollCount,
				"waitingSessionSampleCount", waitingSessionSampleCount, "sampledWaitNanos", sampledWaitNanos,
				"sampleTotalNanos", sampleTotalNanos(samples), "sampleMaxNanos", sampleMaxNanos(samples));
		}
	}

	private record LatencyPercentiles(long p50, long p95, long p99) {
		Map<String, Object> toArtifactNode() {
			return Map.of("p50", p50, "p95", p95, "p99", p99);
		}
	}

	private record RetryMetrics(long total, long max) {
		Map<String, Object> toArtifactNode() {
			return Map.of("total", total, "max", max);
		}
	}

	private record FailureMetrics(long count, double rate) {
		Map<String, Object> toArtifactNode() {
			return Map.of("count", count, "rate", rate);
		}
	}

	private record RoundMetrics(int sampleCount, long observationDurationNanos, LatencyPercentiles latencyNanos,
		double throughputPerSecond, RetryMetrics retry, FailureMetrics failure) {
		static RoundMetrics from(List<RawSample> samples, Instant observationStartedAt, Instant observationEndedAt) {
			long durationNanos = Math.max(1L,
				java.time.Duration.between(observationStartedAt, observationEndedAt).toNanos());
			if (samples.isEmpty()) {
				return new RoundMetrics(0, durationNanos, new LatencyPercentiles(0L, 0L, 0L), 0D,
					new RetryMetrics(0L, 0L), new FailureMetrics(0L, 0D));
			}
			List<Long> latencies = samples.stream().map(RawSample::latencyNanos).sorted().toList();
			long retryTotal = samples.stream().mapToLong(RawSample::retryCount).sum();
			long retryMax = samples.stream().mapToLong(RawSample::retryCount).max().orElse(0L);
			long failureCount = samples.stream()
				.filter(sample -> sample.errorCode() != null || sample.httpStatus() >= 400)
				.count();
			return new RoundMetrics(samples.size(), durationNanos,
				new LatencyPercentiles(nearestRank(latencies, 0.50D), nearestRank(latencies, 0.95D),
					nearestRank(latencies, 0.99D)),
				samples.size() * 1_000_000_000D / durationNanos, new RetryMetrics(retryTotal, retryMax),
				new FailureMetrics(failureCount, (double)failureCount / samples.size()));
		}

		private static long nearestRank(List<Long> samples, double percentile) {
			return samples.get((int)Math.ceil(percentile * samples.size()) - 1);
		}

		Map<String, Object> toArtifactNode() {
			return Map.of("sampleCount", sampleCount, "observationDurationNanos", observationDurationNanos,
				"latencyNanos", latencyNanos.toArtifactNode(), "throughputPerSecond", throughputPerSecond,
				"retry", retry.toArtifactNode(), "failure", failure.toArtifactNode());
		}
	}

	private static final class LockWaitMonitor {
		private static final long POLL_INTERVAL_MILLIS = 2L;

		private final JdbcTemplate jdbcTemplate;
		private final AtomicBoolean running = new AtomicBoolean();
		private final AtomicLong pollCount = new AtomicLong();
		private final AtomicLong waitingSessionSampleCount = new AtomicLong();
		private final AtomicLong sampledWaitNanos = new AtomicLong();
		private final AtomicLong lastPollNanos = new AtomicLong();
		private final AtomicReference<String> observationFailure = new AtomicReference<>();
		private final ConcurrentLinkedQueue<LockWaitPoll> polls = new ConcurrentLinkedQueue<>();
		private final CountDownLatch firstPollCompleted = new CountDownLatch(1);
		private final AtomicBoolean firstPollSucceeded = new AtomicBoolean();
		private ExecutorService executor;

		LockWaitMonitor(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		void start() {
			if (!running.compareAndSet(false, true)) {
				throw new IllegalStateException("lock-wait observer가 이미 실행 중입니다.");
			}
			executor = Executors.newSingleThreadExecutor();
			executor.submit(() -> {
				while (running.get()) {
					observe();
					try {
						TimeUnit.MILLISECONDS.sleep(POLL_INTERVAL_MILLIS);
					} catch (InterruptedException exception) {
						Thread.currentThread().interrupt();
						return;
					}
				}
			});
		}

		void observeNow() {
			observe();
		}

		boolean awaitFirstPoll(long timeout, TimeUnit unit) {
			try {
				return firstPollCompleted.await(timeout, unit) && firstPollSucceeded.get()
					&& observationFailure.get() == null;
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				return false;
			}
		}

		LockWaitObservation stop(Instant executionStartedAt, Instant executionEndedAt) {
			running.set(false);
			if (executor != null) {
				executor.shutdownNow();
				try {
					if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
						observationFailure.compareAndSet(null, "shutdownTimeout");
					}
				} catch (InterruptedException exception) {
					Thread.currentThread().interrupt();
					observationFailure.compareAndSet(null, "shutdownInterrupted");
				}
			}
			List<LockWaitPoll> observedPolls = List.copyOf(polls);
			long executionWindowPollCount = observedPolls.stream()
				.filter(poll -> executionStartedAt != null && executionEndedAt != null
					&& !poll.observedAt().isBefore(executionStartedAt)
					&& !poll.observedAt().isAfter(executionEndedAt))
				.count();
			boolean observed = observationFailure.get() == null && pollCount.get() > 0L
				&& executionWindowPollCount >= 2L;
			return new LockWaitObservation(observed, pollCount.get(), executionWindowPollCount,
				waitingSessionSampleCount.get(), sampledWaitNanos.get(), observedPolls);
		}

		private void observe() {
			if (observationFailure.get() != null) {
				return;
			}
			try {
				Integer waitingSessions = jdbcTemplate.queryForObject("""
					select count(*)
					from pg_stat_activity
					where datname = current_database() and wait_event_type = 'Lock'
					""", Integer.class);
				if (waitingSessions == null) {
					throw new IllegalStateException("lock-wait observer count가 null입니다.");
				}
				long observedAtNanos = System.nanoTime();
				Instant observedAt = Instant.now();
				long previousPollNanos = lastPollNanos.getAndSet(observedAtNanos);
				pollCount.incrementAndGet();
				waitingSessionSampleCount.addAndGet(waitingSessions);
				polls.add(new LockWaitPoll(observedAt, observedAtNanos, waitingSessions));
				firstPollSucceeded.set(true);
				firstPollCompleted.countDown();
				if (waitingSessions > 0 && previousPollNanos > 0L) {
					sampledWaitNanos.addAndGet((observedAtNanos - previousPollNanos) * waitingSessions);
				}
			} catch (Exception exception) {
				observationFailure.compareAndSet(null, exception.getClass().getSimpleName());
				firstPollCompleted.countDown();
			}
		}
	}

	private record RoundArtifact(String scenario, int round, boolean warmUp, List<RawSample> rawSamples,
		FinalStateAssertion finalState, DatabaseStatistics databaseStatistics, LockWaitObservation lockWait,
		RoundMetrics metrics, String invalidReason, Instant observationStartedAt, Instant observationEndedAt) {
		boolean accepted() {
			return invalidReason == null && finalState.passed();
		}
	}
	private static final class ContractMeasurementInvalidException extends AssertionError {
		private final Path artifact;
		private final List<RoundArtifact> roundArtifacts;

		ContractMeasurementInvalidException(Path artifact, List<RoundArtifact> roundArtifacts) {
			super("artifact 기록 뒤 INVALID 또는 FAILED round가 남았습니다: "
				+ roundArtifacts.stream()
					.map(round -> round.scenario() + "/" + round.round() + "/warmUp=" + round.warmUp()
						+ " accepted=" + round.accepted() + " samples=" + round.rawSamples().size()
						+ " invalidReason=" + round.invalidReason())
					.collect(java.util.stream.Collectors.joining("; ")));
			this.artifact = artifact;
			this.roundArtifacts = roundArtifacts;
		}

		Path artifact() {
			return artifact;
		}

		List<RoundArtifact> roundArtifacts() {
			return roundArtifacts;
		}
	}
	private record FinalStateAssertion(boolean passed, long proposalCount, long memberResponseCount,
		long requestStatusCount, long partyCount, long partyParticipantCount, long proposedCurrentStateCount,
		long terminalCurrentStateCount, long otherCurrentStateCount, long matchedExpectedCurrentStateCount,
		long nonterminalTransitionCount, long terminalTransitionCount, long completePartyGroupCount,
		long duplicatePartyCount, long partialSuccessCount, long proposalFactMatchCount,
		long proposalFactMismatchCount, long memberFactMatchCount, long memberFactMismatchCount,
		long requestFactMatchCount, long requestFactMismatchCount, long queueTimestampMatchCount,
		long queueTimestampMismatchCount, long idempotencyRecordCount, long idempotencyRecordMatchCount,
		long idempotencyRecordMismatchCount) {
		static FinalStateAssertion invalid() {
			return new FinalStateAssertion(false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
		}
	}
	private record RawSampleDraft(Instant operationTime, Instant completedAt, Instant respondBy, String action,
		String result, int retryCount, int httpStatus, String errorCode, long latencyNanos,
		String finalStateObservation) {
		RawSample toRawSample(boolean finalStatePassed, long lockWaitNanos) {
			return new RawSample(operationTime == null ? null : operationTime.toString(),
				completedAt == null ? null : completedAt.toString(), respondBy.toString(), action, result, retryCount,
				lockWaitNanos, httpStatus, errorCode, latencyNanos, finalStateObservation, finalStatePassed);
		}
	}
	private record RawSample(String operationTime, String completedAt, String respondBy, String action, String result,
		int retryCount, long lockWaitNanos, int httpStatus, String errorCode, long latencyNanos,
		String finalStateObservation, boolean finalStatePassed) {
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeConfiguration {
		@Bean
		@Primary
		RecordingProbe responseCompletionProbe() {
			return new RecordingProbe();
		}
	}

	static final class RecordingProbe implements MatchProposalResponseCompletionProbe {
		private final ThreadLocal<Instant> operationTimes = new ThreadLocal<>();
		private final ConcurrentLinkedQueue<RecordedProbeSample> completed = new ConcurrentLinkedQueue<>();
		private final java.util.concurrent.ConcurrentHashMap<Thread, RecordedProbeSample> completedByThread = new java.util.concurrent.ConcurrentHashMap<>();

		@Override
		public void start(Instant operationTime) {
			operationTimes.set(operationTime);
		}

		@Override
		public void complete() {
			Instant operationTime = operationTimes.get();
			if (operationTime == null) {
				throw new IllegalStateException("probe operationTime 누락");
			}
			Instant completedAt = Instant.now();
			RecordedProbeSample sample = new RecordedProbeSample(operationTime, completedAt,
				Math.max(1L, java.time.Duration.between(operationTime, completedAt).toNanos()));
			completed.add(sample);
			completedByThread.put(Thread.currentThread(), sample);
			operationTimes.remove();
		}

		@Override
		public void fail(FailureStage failureStage) {
			operationTimes.remove();
		}

		RecordedProbeSample takeCompleted() {
			RecordedProbeSample sample = completed.poll();
			if (sample == null) {
				throw new IllegalStateException("probe completion sample 누락");
			}
			return sample;
		}

		RecordedProbeSample takeCompletedForCurrentThreadOrNull() {
			return completedByThread.remove(Thread.currentThread());
		}

		void clear() {
			completed.clear();
			completedByThread.clear();
			operationTimes.remove();
		}
	}

	private record RecordedProbeSample(Instant operationTime, Instant completedAt, long latencyNanos) {
	}
}
