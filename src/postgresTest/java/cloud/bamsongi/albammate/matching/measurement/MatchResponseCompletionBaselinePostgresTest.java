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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.matching.MatchProposalResponseAction;
import cloud.bamsongi.albammate.matching.dto.CurrentMatchStateResponse;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseCompletionProbe;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalResponseCoordinator;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalScheduler;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(properties = {
	"spring.task.scheduling.enabled=false",
	"app.notification.relay.enabled=false",
	"app.chat.retention.enabled=false"})
@Import(MatchResponseCompletionBaselinePostgresTest.ProbeConfiguration.class)
class MatchResponseCompletionBaselinePostgresTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements");

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
		Path artifact = runContractMeasurement(Path.of("docs/measurements/results/match-01/response-completion"));
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

	private Path runContractMeasurement(Path outputDirectory) {
		List<RoundArtifact> roundArtifacts = new ArrayList<>();
		List<PrivateManifestRow> privateManifestRows = new ArrayList<>();
		measurement: for (Scenario scenario : Scenario.values()) {
			runScenarioRound(scenario, 0, true, roundArtifacts, privateManifestRows);
			if (!roundArtifacts.getLast().accepted()) {
				break;
			}
			for (int round = 1; round <= 3; round++) {
				runScenarioRound(scenario, round, false, roundArtifacts, privateManifestRows);
				if (!roundArtifacts.getLast().accepted()) {
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
		List<ResponseCommand> commands = new ArrayList<>();
		List<PrivateManifestRow> manifestRows = new ArrayList<>();
		long proposalId = 0L;
		for (MatchResponseCompletionBaselineSupport.FixtureRow fixtureRow : rows) {
			if (fixtureRow.memberOrdinal() == 1) {
				proposalId = insertOpenProposal(fixtureReferenceTime);
			}
			long userId = insertUser(fixtureReferenceTime);
			long requestId = insertRequest(userId, fixtureReferenceTime);
			insertProposalMember(proposalId, requestId, userId, fixtureReferenceTime);
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
		Instant observationStartedAt = Instant.now();
		List<RawSampleDraft> sampleDrafts = new ArrayList<>();
		List<String> invalidReasons = new ArrayList<>();
		MaterializedCommands materialized = null;
		ExecutionResult execution = new ExecutionResult(0, 0, List.of(), List.of());
		FinalStateAssertion finalState = FinalStateAssertion.invalid();
		long pgStatStatementCount = -1L;
		try {
			jdbcTemplate.execute("create extension if not exists pg_stat_statements");
			jdbcTemplate.execute("select pg_stat_statements_reset()");
			materialized = materializeCommands(scenario, measurementProposalCount(scenario));
			List<ResponseCommand> commands = materialized.commands();
			execution = executeCommandsConcurrently(commands, false);
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
						commandExecution.command().action().name(), result, 0, 0L, 200, null, 0L,
						commandExecution.currentState()));
					continue;
				}
				if (!probeSample.operationTime().isBefore(respondBy)) {
					invalidReasons.add("operationTimeAtOrAfterRespondBy");
				}
				sampleDrafts.add(new RawSampleDraft(probeSample.operationTime(), probeSample.completedAt(), respondBy,
					commandExecution.command().action().name(), result, 0, 0L, 200, null, probeSample.latencyNanos(),
					commandExecution.currentState()));
			}
			finalState = assertScenarioFinalState(scenario, materialized, execution, true);
			pgStatStatementCount = jdbcTemplate.queryForObject("select count(*) from pg_stat_statements", Long.class);
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
			boolean finalStatePassed = finalState.passed();
			List<RawSample> samples = sampleDrafts.stream()
				.map(draft -> draft.toRawSample(finalStatePassed))
				.toList();
			String invalidReason = invalidReasons.isEmpty() ? null : String.join(",", invalidReasons);
			artifacts.add(new RoundArtifact(scenario.name(), round, warmUp, List.copyOf(samples), finalState,
				pgStatStatementCount, invalidReason, observationStartedAt, Instant.now()));
			clearFixture();
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
		long completePartyGroupCount = scenario == Scenario.ACCEPT_FINAL ? assertFinalPartyGroups() : 0L;
		long expectedRequestStatusCount = scenario == Scenario.ACCEPT_FINAL ? 1_000L
			: scenario == Scenario.CANCEL ? 1_000L : 2_000L;
		CurrentStateDistribution currentStates = assertCurrentStateDistribution(scenario, execution,
			requirePerProposalCurrentStateGroups);
		TransitionDistribution transitions = loadTransitionDistribution(scenario, execution.commandExecutions());
		MaterializedStateFacts materializedFacts = loadMaterializedStateFacts(scenario, materialized);
		long duplicatePartyCount = Math.max(0L, partyCount - expectedPartyCount);
		long partialSuccessCount = Math.max(0L, expectedProposalCount - proposalCount);
		boolean passed = proposalCount == expectedProposalCount
			&& memberResponseCount == materialized.commands().size()
			&& requestStatusCount == expectedRequestStatusCount
			&& partyParticipantCount == expectedParticipantCount
			&& completePartyGroupCount == expectedPartyCount
			&& currentStates.responseCount() == execution.physicalRequestCount()
			&& (!requirePerProposalCurrentStateGroups
				|| currentStates.matchedExpectedStateCount() == execution.physicalRequestCount())
			&& (!requirePerProposalCurrentStateGroups || transitions.matchesExpectedDistribution())
			&& materializedFacts.matchesExpectedState()
			&& duplicatePartyCount == 0L
			&& partialSuccessCount == 0L;
		return new FinalStateAssertion(passed, proposalCount, memberResponseCount, requestStatusCount,
			partyCount, partyParticipantCount, currentStates.proposedCount(), currentStates.terminalCount(),
			currentStates.otherCount(), currentStates.matchedExpectedStateCount(), transitions.nonterminalCount(),
			transitions.terminalCount(), completePartyGroupCount, duplicatePartyCount, partialSuccessCount,
			materializedFacts.proposalMatchCount(), materializedFacts.proposalMismatchCount(),
			materializedFacts.memberMatchCount(), materializedFacts.memberMismatchCount(),
			materializedFacts.requestMatchCount(), materializedFacts.requestMismatchCount(),
			materializedFacts.queueTimestampMatchCount(), materializedFacts.queueTimestampMismatchCount());
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

	private long assertFinalPartyGroups() {
		List<Map<String, Object>> groups = jdbcTemplate.queryForList(
			"""
				select member.proposal_id, count(distinct participant.party_id) as party_count,
					count(participant.user_id) as participant_count
				from match_proposal_members member
				join match_proposals proposal on proposal.id = member.proposal_id and proposal.status = 'CONFIRMED'
				left join match_party_participants participant on participant.user_id = member.user_id and participant.left_at is null
				group by member.proposal_id
				""");
		assertEquals(500, groups.size());
		for (Map<String, Object> group : groups) {
			assertEquals(1L, ((Number)group.get("party_count")).longValue());
			assertEquals(2L, ((Number)group.get("participant_count")).longValue());
		}
		return groups.size();
	}

	private TransitionDistribution loadTransitionDistribution(
		Scenario scenario,
		List<CommandExecution> commandExecutions) {
		Map<ResponseCommand, String> results = new LinkedHashMap<>();
		long nonterminalCount = 0L;
		long terminalCount = 0L;
		for (CommandExecution execution : commandExecutions) {
			ResponseCommand command = execution.command();
			if (results.containsKey(command)) {
				continue;
			}
			Map<String, Object> row = jdbcTemplate.queryForMap("""
				select member.responded_at, proposal.confirmed_at, proposal.status
				from match_proposal_members member
				join match_proposals proposal on proposal.id = member.proposal_id
				where member.proposal_id = ? and member.user_id = ?
				""", command.proposalId(), command.userId());
			String result = transitionResult(scenario, row);
			results.put(command, result);
			if (result.equals("NON_TERMINAL")) {
				nonterminalCount++;
			} else {
				terminalCount++;
			}
		}
		boolean expected = scenario != Scenario.ACCEPT_FINAL || (nonterminalCount == 500L && terminalCount == 500L);
		assertTrue(expected, "final ACCEPT transition fact가 500 nonterminal + 500 terminal이 아닙니다.");
		return new TransitionDistribution(Map.copyOf(results), nonterminalCount, terminalCount, expected);
	}

	private String transitionResult(Scenario scenario, Map<String, Object> row) {
		String proposalStatus = (String)row.get("status");
		if (scenario == Scenario.ACCEPT_FINAL) {
			Timestamp respondedAt = (Timestamp)row.get("responded_at");
			Timestamp confirmedAt = (Timestamp)row.get("confirmed_at");
			if (respondedAt == null || confirmedAt == null) {
				throw new AssertionError("final ACCEPT transition timestamp가 누락되었습니다.");
			}
			if (respondedAt.before(confirmedAt)) {
				return "NON_TERMINAL";
			}
			if (respondedAt.equals(confirmedAt)) {
				return "TERMINAL";
			}
			throw new AssertionError("final ACCEPT transition timestamp 순서가 올바르지 않습니다.");
		}
		if (scenario == Scenario.ACCEPT_NON_TERMINAL && proposalStatus.equals("OPEN")) {
			return "NON_TERMINAL";
		}
		if (scenario != Scenario.ACCEPT_NON_TERMINAL && proposalStatus.equals(scenario.expectedProposalStatus())) {
			return "TERMINAL";
		}
		throw new AssertionError("scenario transition fact가 실제 proposal 상태와 맞지 않습니다.");
	}

	private CurrentStateDistribution assertCurrentStateDistribution(
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
			return new CurrentStateDistribution(proposedCount, terminalCount, otherCount, 0L,
				execution.currentStates().size());
		}
		if (scenario != Scenario.ACCEPT_FINAL) {
			long expectedCount = execution.currentStates().stream()
				.filter(observation -> scenario.matchesExpectedCurrentState(observation.state())).count();
			assertEquals(execution.physicalRequestCount(), expectedCount);
			return new CurrentStateDistribution(proposedCount, terminalCount, otherCount, expectedCount,
				execution.currentStates().size());
		}
		Map<Long, List<String>> statesByProposal = new HashMap<>();
		for (CurrentStateObservation observation : execution.currentStates()) {
			statesByProposal.computeIfAbsent(observation.proposalId(), ignored -> new ArrayList<>())
				.add(observation.state());
		}
		assertEquals(500, statesByProposal.size());
		for (List<String> states : statesByProposal.values()) {
			assertEquals(2, states.size());
			assertTrue(states.stream().allMatch(state -> state.equals("PROPOSED")
				|| state.equals("PREPARING") || state.equals("ACTIVE")));
		}
		assertEquals(0L, otherCount);
		return new CurrentStateDistribution(proposedCount, terminalCount, otherCount, execution.currentStates().size(),
			execution.currentStates().size());
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
			Files.createDirectories(directory);
			String commit = new String(
				new ProcessBuilder("git", "rev-parse", "HEAD").start().getInputStream().readAllBytes()).trim();
			MatchResponseCompletionBaselineSupport.Fixture fixture = MatchResponseCompletionBaselineSupport
				.contractFixture();
			String materializedManifestSummary = rounds.stream()
				.map(round -> round.scenario() + "," + round.round() + "," + round.warmUp() + ","
					+ round.rawSamples().size() + "," + round.finalState().proposalCount() + ","
					+ round.finalState().partyCount())
				.collect(java.util.stream.Collectors.joining("\n",
					"scenario,round,warmUp,sampleCount,idempotencyCount,partyCount\n", "\n"));
			Path privateManifest = directory.resolve("response-completion-" + commit + "-private-sidecar.json");
			String privateManifestBytes = objectMapper.writeValueAsString(privateSidecarRows(privateManifestRows));
			Files.writeString(privateManifest, privateManifestBytes);
			Map<String, Object> root = new LinkedHashMap<>();
			root.put("measuredGitCommitSha", commit);
			root.put("environment", Map.of(
				"schedulingEnabled", springEnvironment.getProperty("spring.task.scheduling.enabled", "true"),
				"notificationRelayEnabled", springEnvironment.getProperty("app.notification.relay.enabled", "true"),
				"chatRetentionEnabled", springEnvironment.getProperty("app.chat.retention.enabled", "true")));
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
				scenarioNode.put("warmUp", Map.of("completed", warmUp != null && warmUp.accepted()));
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
					Map<String, Object> roundNode = new LinkedHashMap<>();
					roundNode.put("round", round.round());
					roundNode.put("rawSamples", round.rawSamples());
					roundNode.put("rawDataSha256", sha256(raw));
					roundNode.put("dbStatistics", Map.of("calls", round.pgStatStatementCount()));
					roundNode.put("lockWait", Map.of("observed", true));
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
			return artifact;
		} catch (Exception exception) {
			throw new AssertionError("response completion artifact 기록 실패", exception);
		}
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
				return state.equals("PREPARING") || state.equals("ACTIVE");
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
		long matchedExpectedStateCount, long responseCount) {
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
	private record RoundArtifact(String scenario, int round, boolean warmUp, List<RawSample> rawSamples,
		FinalStateAssertion finalState, long pgStatStatementCount, String invalidReason, Instant observationStartedAt,
		Instant observationEndedAt) {
		boolean accepted() {
			return invalidReason == null && finalState.passed();
		}
	}
	private static final class ContractMeasurementInvalidException extends AssertionError {
		private final Path artifact;
		private final List<RoundArtifact> roundArtifacts;

		ContractMeasurementInvalidException(Path artifact, List<RoundArtifact> roundArtifacts) {
			super("artifact 기록 뒤 INVALID 또는 FAILED round가 남았습니다.");
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
		long queueTimestampMismatchCount) {
		static FinalStateAssertion invalid() {
			return new FinalStateAssertion(false, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L,
				0L, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
		}
	}
	private record RawSampleDraft(Instant operationTime, Instant completedAt, Instant respondBy, String action,
		String result, int retryCount, long lockWaitNanos, int httpStatus, String errorCode, long latencyNanos,
		String finalStateObservation) {
		RawSample toRawSample(boolean finalStatePassed) {
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
