package cloud.bamsongi.albammate.matching.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import cloud.bamsongi.albammate.AlbamMateApplication;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(classes = AlbamMateApplication.class)
class MatchCandidateClaimBaselinePostgresTest {

	@Container
	@ServiceConnection
	static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18.4")
		.withCommand("postgres", "-c", "shared_preload_libraries=pg_stat_statements")
		.withDatabaseName("albam_mate_match_candidate_baseline");

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@AfterEach
	void tearDown() {
		jdbcTemplate.execute("""
			truncate table match_idempotency_records, match_proposal_members, match_proposals,
			match_party_participants, match_parties, match_requests, match_blocks, users restart identity cascade
			""");
	}

	@Test
	void fixture_read_back은_queuedAt과_prioritySince가_다른_입력을_각각_보존한다() {
		MatchCandidateClaimBaselineSupport.CandidateFixture fixture = MatchCandidateClaimBaselineSupport
			.createDistinctQueuedAtFixture();
		MatchCandidateClaimBaselineSupport.MaterializedFixture materialized = MatchCandidateClaimBaselineSupport
			.materialize(jdbcTemplate, fixture);
		MatchCandidateClaimBaselineSupport.FixtureReportInput report = MatchCandidateClaimBaselineSupport
			.reportFixture(jdbcTemplate, fixture, materialized);
		assertTrue(report.inputCsv().contains("2026-01-01T00:00:00Z,2026-01-01T00:00:01Z"));
		assertEquals("2026-01-01T00:00:00Z", report.manifest().getFirst().queuedAt());
		assertEquals("2026-01-01T00:00:01Z", report.manifest().getFirst().prioritySince());
	}

	@Test
	void fixture_generator는_동일한_입력_CSV와_manifest를_결정적으로_만들고_변조를_거절한다() {
		MatchCandidateClaimBaselineSupport.CandidateFixture first = MatchCandidateClaimBaselineSupport
			.createContractFixture();
		MatchCandidateClaimBaselineSupport.CandidateFixture second = MatchCandidateClaimBaselineSupport
			.createContractFixture();

		assertEquals(1_000, first.requests().size());
		assertEquals(100, first.tiePairs().size());
		assertEquals(first.fixtureInputSha256(), second.fixtureInputSha256());
		assertEquals(first.inputCsv(), second.inputCsv());
		assertEquals(first.fixtureInputSha256(), sha256(first.inputCsv()));
		MatchCandidateClaimBaselineSupport.MaterializedFixture materialized = MatchCandidateClaimBaselineSupport
			.materialize(jdbcTemplate, first);
		assertEquals(1_000, materialized.requests().size());
		assertEquals(1_000, jdbcTemplate.queryForObject("""
			select count(*) from match_requests
			where status = 'WAITING' and min_party_size = 2 and max_party_size = 4
				and queued_at = priority_since
			""", Integer.class));
		for (MatchCandidateClaimBaselineSupport.TiePair tiePair : first.tiePairs()) {
			long firstRequestId = materialized.requests().get(tiePair.firstOrdinal() - 1).requestId();
			long secondRequestId = materialized.requests().get(tiePair.secondOrdinal() - 1).requestId();
			assertTrue(firstRequestId < secondRequestId);
		}
		assertEquals(1_000,
			materialized.requests().stream().map(MatchCandidateClaimBaselineSupport.MaterializedRequest::userId)
				.distinct().count());
		assertEquals(1_000,
			materialized.requests().stream().map(MatchCandidateClaimBaselineSupport.MaterializedRequest::requestId)
				.distinct().count());
		assertTrue(
			materialized.requests().stream().allMatch(request -> request.userId() > 0 && request.requestId() > 0));
		MatchCandidateClaimBaselineSupport.FixtureReportInput reportFixture = MatchCandidateClaimBaselineSupport
			.reportFixture(jdbcTemplate, first, materialized);
		assertEquals(first.inputCsv(), reportFixture.inputCsv());
		assertEquals("MATCH-01-CANDIDATE-BASELINE-V2", reportFixture.generator());
		assertEquals(first.fixtureInputSha256(), reportFixture.fixtureInputSha256());
		assertEquals(1_000, reportFixture.manifest().size());
		assertEquals(2, reportFixture.manifest().getFirst().minPartySize());
		assertEquals(4, reportFixture.manifest().getFirst().maxPartySize());
		assertTrue(reportFixture.manifest().stream().allMatch(entry -> entry.userId() > 0 && entry.requestId() > 0));
		List<MatchCandidateClaimBaselineSupport.MaterializedRequest> substituted = new java.util.ArrayList<>(
			materialized.requests());
		MatchCandidateClaimBaselineSupport.MaterializedRequest firstRequest = substituted.getFirst();
		MatchCandidateClaimBaselineSupport.MaterializedRequest secondRequest = substituted.get(1);
		substituted.set(0, new MatchCandidateClaimBaselineSupport.MaterializedRequest(
			firstRequest.fixtureOrdinal(), firstRequest.label(), secondRequest.userId(), secondRequest.requestId()));
		substituted.set(1, new MatchCandidateClaimBaselineSupport.MaterializedRequest(
			secondRequest.fixtureOrdinal(), secondRequest.label(), firstRequest.userId(), firstRequest.requestId()));
		assertThrows(IllegalArgumentException.class, () -> MatchCandidateClaimBaselineSupport
			.reportFixture(jdbcTemplate, first, new MatchCandidateClaimBaselineSupport.MaterializedFixture(
				first.fixtureInputSha256(), List.copyOf(substituted))));
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.verifyWorkerInput(first, materialized, "tampered"));
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.verifyWorkerInput(first,
				materialized.withFixtureInputSha256("tampered"), first.fixtureInputSha256()));
	}

	@Test
	void worker_entry는_실제_coordinator_claim_경로로_혼합_범위_smoke를_수행한다() throws Exception {
		MatchCandidateClaimBaselineSupport.CandidateFixture fixture = MatchCandidateClaimBaselineSupport
			.createMixedRangeSmokeFixture();
		MatchCandidateClaimBaselineSupport.MaterializedFixture materialized = MatchCandidateClaimBaselineSupport
			.materialize(jdbcTemplate, fixture);

		MatchCandidateClaimBaselineSupport.WorkerEntryExecution worker = MatchCandidateClaimBaselineSupport
			.runSingleMatcherProcess(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), fixture, materialized);

		assertTrue(worker.workerPid() > 0);
		assertTrue(worker.completed());
		assertEquals(1, worker.logicalClaims().size());
		assertTrue(worker.logicalClaims().getFirst().durationNanos() > 0);
		assertEquals(MatchCandidateClaimBaselineSupport.currentGitSha(), worker.measuredGitCommitSha());
		assertEquals(1, jdbcTemplate.queryForObject("select count(*) from match_proposals", Integer.class));
		assertEquals(2, jdbcTemplate.queryForObject("select party_size from match_proposals", Integer.class));
		assertEquals(List.of("R1", "R3"), jdbcTemplate.queryForList("""
			select u.nickname
			from match_proposal_members member
			join users u on u.id = member.user_id
			order by u.nickname
			""", String.class));
		assertEquals(3, jdbcTemplate.queryForObject("select count(*) from match_requests where status = 'WAITING'",
			Integer.class));
	}

	@Test
	void 두_matcher는_서로_다른_OS_process로_같은_barrier를_통과해야_유효하다() throws Exception {
		MatchCandidateClaimBaselineSupport.CandidateFixture fixture = MatchCandidateClaimBaselineSupport
			.createSmallProcessFixture();
		MatchCandidateClaimBaselineSupport.MaterializedFixture materialized = MatchCandidateClaimBaselineSupport
			.materialize(jdbcTemplate, fixture);

		MatchCandidateClaimBaselineSupport.ProcessOrchestration orchestration = MatchCandidateClaimBaselineSupport
			.runMatcherProcesses(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), fixture, materialized, 1);

		assertEquals(2, orchestration.matcherPids().stream().distinct().count());
		assertTrue(orchestration.sameBarrierReleased());
		assertTrue(orchestration.completed());
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.validateProcessEvidence(
				new MatchCandidateClaimBaselineSupport.ProcessOrchestration(
					List.of(10L, 10L), true, true, orchestration.measuredGitCommitSha(), false, List.of())));
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.validateProcessEvidence(
				new MatchCandidateClaimBaselineSupport.ProcessOrchestration(
					List.of(10L, 20L), false, true, orchestration.measuredGitCommitSha(), false, List.of())));
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.validateProcessEvidence(
				new MatchCandidateClaimBaselineSupport.ProcessOrchestration(
					List.of(10L, 20L), true, false, orchestration.measuredGitCommitSha(), false, List.of())));
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.validateProcessEvidence(
				new MatchCandidateClaimBaselineSupport.ProcessOrchestration(
					List.of(10L, 20L), true, true, "", false, List.of())));
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.validateProcessEvidence(
				new MatchCandidateClaimBaselineSupport.ProcessOrchestration(
					List.of(10L), true, true, orchestration.measuredGitCommitSha(), false,
					List.of(new MatchCandidateClaimBaselineSupport.LogicalClaim(1L, 0, List.of())))));
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.validateReadyMessage(
				"READY|11|fixture|git|config", "fixture", "git", "different-config"));
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.validateReadyMessage(
				"READY|11|fixture|different-git|config", "fixture", "git", "config"));
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineSupport.validateReadyMessage(
				"READY|11||git|config", "fixture", "git", "config"));
	}

	@Test
	void small_round는_실제_coordinator_경로의_관측_원자료를_report_schema_입력으로_수집한다() throws Exception {
		MatchCandidateClaimBaselineSupport.CandidateFixture fixture = MatchCandidateClaimBaselineSupport
			.createSmallProcessFixture();
		MatchCandidateClaimBaselineSupport.MaterializedFixture materialized = MatchCandidateClaimBaselineSupport
			.materialize(jdbcTemplate, fixture);
		MatchCandidateClaimBaselineSupport.FixtureReportInput fixtureReport = MatchCandidateClaimBaselineSupport
			.reportFixture(jdbcTemplate, fixture, materialized);

		MatchCandidateClaimBaselineSupport.SmallRoundReport report = MatchCandidateClaimBaselineSupport
			.collectSmallRound(
				POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), jdbcTemplate,
				fixture, materialized, fixtureReport, 1);

		assertEquals(2, report.logicalClaims().size());
		assertEquals(2, report.reportInput().matcherProcesses().size());
		assertTrue(report.reportInput().matcherProcesses().stream()
			.allMatch(process -> process.exitCode() == 0 && process.completed()));
		assertEquals(report.logicalClaims(), report.reportInput().logicalClaims());
		assertEquals(report.throughputPerSecond(), report.reportInput().throughputPerSecond());
		assertEquals(report.pgStatStatements(), report.reportInput().pgStatStatements());
		assertEquals(report.lockSamples(), report.reportInput().lockSamples());
		assertEquals(report.queryPlan(), report.reportInput().queryPlan());
		assertEquals(report.correctnessInput(), report.reportInput().correctnessInput());
		assertTrue(report.logicalClaims().stream().allMatch(claim -> claim.durationNanos() > 0
			&& claim.retryCount() == 0 && claim.retryRawDurationsNanos().isEmpty()));
		assertTrue(report.throughputPerSecond() > 0);
		assertTrue(report.pgStatStatements().calls() > 0);
		assertTrue(report.pgStatStatements().totalExecutionTimeMs() >= 0);
		assertTrue(report.pgStatStatements().rows() >= 0);
		assertTrue(report.pgStatStatements().sharedBlockHits() >= 0);
		assertTrue(report.pgStatStatements().sharedBlockReads() >= 0);
		assertFalse(report.pgStatStatements().candidateStatements().isEmpty());
		assertTrue(report.pgStatStatements().candidateStatements().stream()
			.allMatch(statement -> statement.calls() > 0
				&& statement.query().toLowerCase().contains("order by priority_since")
				&& statement.query().toLowerCase().contains("for update skip locked")));
		assertEquals(10, report.lockSamples().intervalMs());
		assertTrue(report.lockSamples().observationStartedAtUtc() != null);
		assertTrue(report.lockSamples().observationFinishedAtUtc() != null);
		assertTrue(Instant.parse(report.lockSamples().observationStartedAtUtc())
			.compareTo(Instant.parse(report.lockSamples().observationFinishedAtUtc())) <= 0);
		assertTrue(report.lockSamples().snapshotCount() > 0);
		assertEquals(null, report.lockSamples().samplingFailure());
		assertFalse(report.queryPlan().isBlank());
		assertEquals(1, report.correctnessInput().proposalCount());
		assertEquals(2, report.correctnessInput().memberCount());
		assertEquals(2, report.correctnessInput().claimedRequestCount());
		assertEquals(2, report.correctnessInput().waitingRequestCount());
		assertEquals(0, report.correctnessInput().duplicateClaimCount());
		assertEquals(0, report.correctnessInput().partialClaimCount());
		assertTrue(report.correctnessInput().tieOrderMatches());
		assertTrue(report.correctnessInput().tiePairResults().isEmpty());
	}

	@Test
	void warm_up과_measured_round_원자료와_nearest_rank_산식이_Node_report에_보존된다() throws Exception {
		assertEquals(0, nodeContractTest("warm-up을 제외하고 measured round 원자료와 nearest-rank 통계를 보존한다"));
	}

	@Test
	void Node_판정기는_관측_누락을_INVALID로_완결_정합성_위반을_FAILED로_분리한다() throws Exception {
		assertEquals(0, nodeContractTest("관측과 process 누락은 INVALID, 완료 뒤 정합성 위반은 FAILED로 판정한다"));
	}

	@Test
	void Node_판정기는_고정_fixture_generator와_ordinal_계약을_검증한다() throws Exception {
		assertEquals(0, nodeContractTest("고정 fixture generator와 모든 ordinal 규칙이 아니면 INVALID로 거절한다"));
	}

	@Test
	void Node_판정기는_raw_배열_누락을_INVALID_decision으로_보존한다() throws Exception {
		assertEquals(0, nodeContractTest("raw 배열 누락 CLI 입력도 INVALID decision JSON으로 보존한다"));
	}

	@Test
	void Node_판정기는_lock_관측_창과_실제_fixture_tie_결과를_검증한다() throws Exception {
		assertEquals(0, nodeContractTest("lock 관측 창과 실제 fixture의 100개 tie 결과가 없으면 INVALID다"));
	}

	@Test
	void 계약_크기_측정_실행은_Tag과_시스템_속성과_전용_task를_모두_요구한다() throws Exception {
		Method heavyMethod = getClass().getDeclaredMethod("계약_크기_측정은_명시적_승인에서만_실행한다");
		assertTrue(heavyMethod.isAnnotationPresent(Tag.class));
		assertThrows(IllegalStateException.class, MatchCandidateClaimBaselineSupport::requireMeasurementEnabled);
	}

	@Test
	@Tag("measurement")
	void 계약_크기_측정은_명시적_승인에서만_실행한다() throws Exception {
		MatchCandidateClaimBaselineSupport.requireMeasurementEnabled();
		List<MatchCandidateClaimBaselineSupport.ReportRoundInput> measuredRounds = new java.util.ArrayList<>();
		MatchCandidateClaimBaselineSupport.ReportRoundInput warmUp = null;
		MatchCandidateClaimBaselineSupport.FixtureReportInput fixtureReport = null;
		for (int round = 0; round < 4; round++) {
			truncateMeasurementTables();
			MatchCandidateClaimBaselineSupport.CandidateFixture fixture = MatchCandidateClaimBaselineSupport
				.createContractFixture();
			MatchCandidateClaimBaselineSupport.MaterializedFixture materialized = MatchCandidateClaimBaselineSupport
				.materialize(jdbcTemplate, fixture);
			// 결과 report는 claim 전 WAITING DB mapping으로 고정한다. 마지막 measured round의 snapshot을 사용한다.
			fixtureReport = MatchCandidateClaimBaselineSupport.reportFixture(jdbcTemplate, fixture, materialized);
			MatchCandidateClaimBaselineSupport.SmallRoundReport collected = MatchCandidateClaimBaselineSupport
				.collectSmallRound(
					POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), jdbcTemplate,
					fixture, materialized, fixtureReport, 500);
			assertEquals(1_000, collected.logicalClaims().size());
			assertTrue(collected.logicalClaims().stream()
				.allMatch(claim -> claim.retryCount() == 0 && claim.retryRawDurationsNanos().isEmpty()));
			assertEquals(100, collected.correctnessInput().tiePairResults().size());
			MatchCandidateClaimBaselineSupport.ReportRoundInput reportRound = MatchCandidateClaimBaselineSupport
				.withRound(collected, round);
			if (round == 0) {
				warmUp = reportRound;
			} else {
				measuredRounds.add(reportRound);
			}
		}
		Path reportDirectory = Path.of("build", "reports", "measurements");
		Files.createDirectories(reportDirectory);
		Path inputPath = reportDirectory.resolve("match01-candidate-baseline-input.json");
		Path outputPath = reportDirectory.resolve("match01-candidate-baseline-report.json");
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(inputPath.toFile(),
			Map.of("fixture", fixtureReport, "warmUp", warmUp, "measured", measuredRounds));
		runNodeReport(inputPath, outputPath);
		boolean allTieOrdersMatch = measuredRounds.stream()
			.allMatch(round -> round.correctnessInput().tieOrderMatches());
		String expectedOutcome = allTieOrdersMatch ? "BASELINE_ACCEPTED" : "FAILED";
		String output = Files.readString(outputPath);
		assertTrue(output.contains("\"outcome\": \"" + expectedOutcome + "\""));
		assertFalse(output.contains("\"outcome\": \"INVALID\""));
	}

	private void truncateMeasurementTables() {
		jdbcTemplate.execute("""
			truncate table match_idempotency_records, match_proposal_members, match_proposals,
			match_party_participants, match_parties, match_requests, match_blocks, users restart identity cascade
			""");
	}

	private void runNodeReport(Path inputPath, Path outputPath) throws Exception {
		Path diagnostic = Files.createTempFile("issue775-node-report-", ".log");
		Process process = new ProcessBuilder(
			"node", "scripts/measurements/match01-candidate-baseline-report.mjs",
			"--input", inputPath.toString(), "--output", outputPath.toString())
			.directory(new java.io.File(System.getProperty("user.dir")))
			.redirectErrorStream(true)
			.redirectOutput(diagnostic.toFile())
			.start();
		try {
			if (!process.waitFor(90, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Node report child가 종료되지 않았습니다.");
				throw new AssertionError("Node report timeout: " + Files.readString(diagnostic));
			}
			assertEquals(0, process.exitValue(), Files.readString(diagnostic));
		} finally {
			Files.deleteIfExists(diagnostic);
		}
	}

	private int nodeContractTest(String testNamePattern) throws Exception {
		Path outputFile = Files.createTempFile("issue775-node-contract-", ".log");
		Process process = new ProcessBuilder(
			"node",
			"--test",
			"--test-name-pattern",
			testNamePattern,
			"scripts/measurements/match01-candidate-baseline-report.test.mjs")
			.directory(new java.io.File(System.getProperty("user.dir")))
			.redirectErrorStream(true)
			.redirectOutput(outputFile.toFile())
			.start();
		try {
			if (!process.waitFor(30, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				assertTrue(process.waitFor(5, TimeUnit.SECONDS), "timeout Node child가 종료되지 않았습니다.");
				throw new AssertionError("Node 판정기 테스트가 timeout되었습니다: " + Files.readString(outputFile));
			}
			String output = Files.readString(outputFile, StandardCharsets.UTF_8);
			assertEquals(0, process.exitValue(), output);
			return process.exitValue();
		} finally {
			boolean deleted = false;
			for (int attempt = 0; attempt < 3; attempt++) {
				try {
					Files.deleteIfExists(outputFile);
					deleted = true;
					break;
				} catch (java.io.IOException exception) {
					Thread.sleep(50);
				}
			}
			if (!deleted) {
				outputFile.toFile().deleteOnExit();
			}
		}
	}

	private String sha256(String value) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}
}
