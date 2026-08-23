package cloud.bamsongi.albammate.matching.measurement;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import cloud.bamsongi.albammate.AlbamMateApplication;
import cloud.bamsongi.albammate.matching.service.command.MatchProposalCoordinator;

public final class MatchCandidateClaimBaselineSupport {

	private static final Instant FIXTURE_TIME = Instant.parse("2026-01-01T00:00:00Z");
	private static final String FIXTURE_GENERATOR = "MATCH-01-CANDIDATE-BASELINE-V2";
	private static final String MIXED_RANGE_FIXTURE_GENERATOR = "MATCH-01-CANDIDATE-MIXED-RANGE-V1";
	private static final int MATCHER_COUNT = 2;
	private static final int PROCESS_TIMEOUT_SECONDS = 90;

	private MatchCandidateClaimBaselineSupport() {}

	static CandidateFixture createContractFixture() {
		List<FixtureRequest> requests = new ArrayList<>();
		List<TiePair> tiePairs = new ArrayList<>();
		for (int ordinal = 1; ordinal <= 1_000; ordinal++) {
			Instant prioritySince = ordinal <= 200
				? FIXTURE_TIME.plusSeconds((ordinal + 1L) / 2)
				: FIXTURE_TIME.plusSeconds(ordinal);
			requests.add(new FixtureRequest(ordinal, "F" + ordinal, 2, 4, prioritySince));
			if (ordinal <= 200 && ordinal % 2 == 0) {
				tiePairs.add(new TiePair(ordinal - 1, ordinal));
			}
		}
		return fixture(requests, tiePairs);
	}

	static CandidateFixture createMixedRangeSmokeFixture() {
		return fixture(MIXED_RANGE_FIXTURE_GENERATOR, List.of(
			new FixtureRequest(1, "R1", 2, 4, FIXTURE_TIME.plusSeconds(1)),
			new FixtureRequest(2, "R2", 4, 4, FIXTURE_TIME.plusSeconds(2)),
			new FixtureRequest(3, "R3", 2, 2, FIXTURE_TIME.plusSeconds(3)),
			new FixtureRequest(4, "R4", 4, 4, FIXTURE_TIME.plusSeconds(4)),
			new FixtureRequest(5, "R5", 4, 4, FIXTURE_TIME.plusSeconds(5))), List.of());
	}

	static CandidateFixture createSmallProcessFixture() {
		return fixture(List.of(
			new FixtureRequest(1, "P1", 2, 2, FIXTURE_TIME.plusSeconds(1)),
			new FixtureRequest(2, "P2", 2, 2, FIXTURE_TIME.plusSeconds(2)),
			new FixtureRequest(3, "P3", 2, 2, FIXTURE_TIME.plusSeconds(3)),
			new FixtureRequest(4, "P4", 2, 2, FIXTURE_TIME.plusSeconds(4))), List.of());
	}

	static CandidateFixture createDistinctQueuedAtFixture() {
		return fixture(List.of(new FixtureRequest(1, "Q1", 2, 4, FIXTURE_TIME, FIXTURE_TIME.plusSeconds(1))),
			List.of());
	}

	private static CandidateFixture fixture(List<FixtureRequest> requests, List<TiePair> tiePairs) {
		return fixture(FIXTURE_GENERATOR, requests, tiePairs);
	}

	private static CandidateFixture fixture(
		String generator, List<FixtureRequest> requests, List<TiePair> tiePairs) {
		StringBuilder csv = new StringBuilder();
		csv.append("fixtureOrdinal,userFixtureOrdinal,queuedAt,prioritySince,minPartySize,maxPartySize\n");
		for (FixtureRequest request : requests) {
			csv.append(request.fixtureOrdinal()).append(',').append(request.fixtureOrdinal()).append(',')
				.append(request.queuedAt()).append(',').append(request.prioritySince()).append(',')
				.append(request.minPartySize()).append(',').append(request.maxPartySize()).append('\n');
		}
		String inputCsv = csv.toString();
		return new CandidateFixture(generator, List.copyOf(requests), inputCsv, sha256(inputCsv),
			List.copyOf(tiePairs));
	}

	static MaterializedFixture materialize(JdbcTemplate jdbcTemplate, CandidateFixture fixture) {
		return jdbcTemplate.execute((ConnectionCallback<MaterializedFixture>)connection -> {
			boolean originalAutoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				List<MaterializedRequest> requests = new ArrayList<>();
				for (FixtureRequest request : fixture.requests()) {
					long userId = insertUser(connection, request);
					long requestId = insertRequest(connection, request, userId);
					requests.add(new MaterializedRequest(request.fixtureOrdinal(), request.label(), userId, requestId));
				}
				connection.commit();
				return new MaterializedFixture(fixture.fixtureInputSha256(), List.copyOf(requests));
			} catch (SQLException exception) {
				connection.rollback();
				throw exception;
			} finally {
				connection.setAutoCommit(originalAutoCommit);
			}
		});
	}

	private static long insertUser(Connection connection, FixtureRequest request) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			insert into users (email, password_hash, nickname, created_at, updated_at)
			values (?, 'fixture-hash', ?, ?, ?) returning id
			""")) {
			statement.setString(1, "match-baseline-" + request.fixtureOrdinal() + "@example.com");
			statement.setString(2, request.label());
			statement.setTimestamp(3, Timestamp.from(request.prioritySince()));
			statement.setTimestamp(4, Timestamp.from(request.queuedAt()));
			return returnedId(statement);
		}
	}

	private static long insertRequest(Connection connection, FixtureRequest request, long userId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
			insert into match_requests
			(user_id, min_party_size, max_party_size, status, queued_at, priority_since, created_at, updated_at)
			values (?, ?, ?, 'WAITING', ?, ?, ?, ?) returning id
			""")) {
			statement.setLong(1, userId);
			statement.setInt(2, request.minPartySize());
			statement.setInt(3, request.maxPartySize());
			statement.setTimestamp(4, Timestamp.from(request.queuedAt()));
			statement.setTimestamp(5, Timestamp.from(request.prioritySince()));
			statement.setTimestamp(6, Timestamp.from(request.prioritySince()));
			statement.setTimestamp(7, Timestamp.from(request.prioritySince()));
			return returnedId(statement);
		}
	}

	private static long returnedId(PreparedStatement statement) throws SQLException {
		try (ResultSet resultSet = statement.executeQuery()) {
			if (!resultSet.next()) {
				throw new SQLException("fixture insert가 ID를 반환하지 않았습니다.");
			}
			return resultSet.getLong(1);
		}
	}

	static void verifyWorkerInput(CandidateFixture fixture, MaterializedFixture materialized,
		String workerFixtureInputSha256) {
		if (!fixture.fixtureInputSha256().equals(sha256(fixture.inputCsv()))
			|| !fixture.fixtureInputSha256().equals(workerFixtureInputSha256)) {
			throw new IllegalArgumentException("worker fixture input hash가 다릅니다.");
		}
		if (!fixture.fixtureInputSha256().equals(materialized.fixtureInputSha256())) {
			throw new IllegalArgumentException("materialized fixture manifest hash가 다릅니다.");
		}
		if (fixture.requests().size() != materialized.requests().size()) {
			throw new IllegalArgumentException("materialized fixture request 수가 다릅니다.");
		}
		java.util.Set<Long> userIds = new java.util.HashSet<>();
		java.util.Set<Long> requestIds = new java.util.HashSet<>();
		for (int index = 0; index < fixture.requests().size(); index++) {
			FixtureRequest request = fixture.requests().get(index);
			MaterializedRequest materializedRequest = materialized.requests().get(index);
			if (request.fixtureOrdinal() != materializedRequest.fixtureOrdinal()
				|| !request.label().equals(materializedRequest.label())
				|| materializedRequest.userId() <= 0
				|| materializedRequest.requestId() <= 0
				|| !userIds.add(materializedRequest.userId())
				|| !requestIds.add(materializedRequest.requestId())) {
				throw new IllegalArgumentException("materialized fixture manifest가 입력과 다릅니다.");
			}
		}
	}

	/** CSV 입력과 materialized synthetic ID를 함께 보존하는 Node baseline report fixture 입력이다. */
	static FixtureReportInput reportFixture(JdbcTemplate jdbcTemplate, CandidateFixture fixture,
		MaterializedFixture materialized) {
		verifyWorkerInput(fixture, materialized, fixture.fixtureInputSha256());
		verifyMaterializedDatabase(jdbcTemplate, fixture, materialized);
		List<MaterializedManifestEntry> manifest = new ArrayList<>();
		for (int index = 0; index < fixture.requests().size(); index++) {
			FixtureRequest request = fixture.requests().get(index);
			MaterializedRequest materializedRequest = materialized.requests().get(index);
			manifest.add(new MaterializedManifestEntry(
				request.fixtureOrdinal(), request.fixtureOrdinal(), request.queuedAt().toString(),
				request.prioritySince().toString(), request.minPartySize(), request.maxPartySize(),
				materializedRequest.userId(), materializedRequest.requestId(), request.fixtureOrdinal()));
		}
		String manifestBytes = manifest.stream().map(entry -> entry.fixtureOrdinal() + "," + entry.userFixtureOrdinal()
			+ "," + entry.queuedAt() + "," + entry.prioritySince() + "," + entry.minPartySize() + ","
			+ entry.maxPartySize() + "," + entry.userId() + "," + entry.requestId() + "," + entry.expectedTieOrder())
			.collect(java.util.stream.Collectors.joining("\n",
				"fixtureOrdinal,userFixtureOrdinal,queuedAt,prioritySince,minPartySize,maxPartySize,userId,requestId,expectedTieOrder\n",
				"\n"));
		return new FixtureReportInput(fixture.generator(), fixture.fixtureInputSha256(), fixture.inputCsv(),
			sha256(manifestBytes),
			List.copyOf(manifest));
	}

	private static void verifyMaterializedDatabase(
		JdbcTemplate jdbcTemplate, CandidateFixture fixture, MaterializedFixture materialized) {
		if (jdbcTemplate.queryForObject("select count(*) from match_requests", Integer.class) != fixture.requests()
			.size()) {
			throw new IllegalArgumentException("materialized request 행 수가 fixture와 다릅니다.");
		}
		for (int index = 0; index < fixture.requests().size(); index++) {
			FixtureRequest fixtureRequest = fixture.requests().get(index);
			MaterializedRequest materializedRequest = materialized.requests().get(index);
			Integer rows = jdbcTemplate.queryForObject("""
				select count(*) from match_requests request join users user_row on user_row.id = request.user_id
				where request.id = ? and request.user_id = ? and user_row.nickname = ? and request.status = 'WAITING'
					and request.min_party_size = ? and request.max_party_size = ?
					and request.queued_at = ? and request.priority_since = ?
				""", Integer.class, materializedRequest.requestId(), materializedRequest.userId(),
				fixtureRequest.label(),
				fixtureRequest.minPartySize(), fixtureRequest.maxPartySize(), Timestamp.from(fixtureRequest.queuedAt()),
				Timestamp.from(fixtureRequest.prioritySince()));
			if (rows == null || rows != 1) {
				throw new IllegalArgumentException(
					"materialized fixture DB mapping이 ordinal " + fixtureRequest.fixtureOrdinal() + "과 다릅니다.");
			}
		}
	}

	static ProcessOrchestration runMatcherProcesses(
		String jdbcUrl,
		String jdbcUsername,
		String jdbcPassword,
		CandidateFixture fixture,
		MaterializedFixture materialized,
		int claimAttempts) throws Exception {
		return runMatcherProcesses(jdbcUrl, jdbcUsername, jdbcPassword, fixture, materialized, claimAttempts,
			currentGitSha(), () -> {});
	}

	static ProcessOrchestration runMatcherProcesses(
		String jdbcUrl,
		String jdbcUsername,
		String jdbcPassword,
		CandidateFixture fixture,
		MaterializedFixture materialized,
		int claimAttempts,
		String measuredGitSha) throws Exception {
		return runMatcherProcesses(jdbcUrl, jdbcUsername, jdbcPassword, fixture, materialized, claimAttempts,
			measuredGitSha, () -> {});
	}

	private static ProcessOrchestration runMatcherProcesses(
		String jdbcUrl,
		String jdbcUsername,
		String jdbcPassword,
		CandidateFixture fixture,
		MaterializedFixture materialized,
		int claimAttempts,
		String measuredGitSha,
		Runnable barrierReleased) throws Exception {
		verifyWorkerInput(fixture, materialized, fixture.fixtureInputSha256());
		if (!isGitSha(measuredGitSha)) {
			throw new IllegalArgumentException("측정 Git SHA는 40자리 소문자 hex여야 합니다.");
		}
		String configurationSha = sha256("matcherCount=" + MATCHER_COUNT + ";claimAttempts=" + claimAttempts);
		List<WorkerProcess> workers = new ArrayList<>();
		long measurementStartedAtNanos = 0L;
		String barrierReleasedAtUtc = null;
		try (ServerSocket barrier = new ServerSocket(0)) {
			barrier.setSoTimeout(PROCESS_TIMEOUT_SECONDS * 1_000);
			for (int index = 0; index < MATCHER_COUNT; index++) {
				workers.add(startWorker(jdbcUrl, jdbcUsername, jdbcPassword, barrier.getLocalPort(), fixture,
					measuredGitSha, configurationSha, claimAttempts));
			}
			List<BarrierClient> clients = new ArrayList<>();
			Map<BarrierClient, Long> matcherPidsByClient = new HashMap<>();
			List<LogicalClaim> logicalClaims = new ArrayList<>();
			try {
				for (int index = 0; index < MATCHER_COUNT; index++) {
					clients.add(awaitConnected(barrier));
				}
				for (BarrierClient client : clients) {
					matcherPidsByClient.put(client,
						awaitReady(client, fixture.fixtureInputSha256(), measuredGitSha, configurationSha));
				}
				barrierReleased.run();
				measurementStartedAtNanos = System.nanoTime();
				barrierReleasedAtUtc = Instant.now().toString();
				for (BarrierClient client : clients) {
					client.writer().write("GO\n");
					client.writer().flush();
				}
				for (BarrierClient client : clients) {
					String done = client.reader().readLine();
					if (done == null || !done.startsWith("DONE|")) {
						throw new IllegalArgumentException("matcher가 barrier 이후 완료를 보고하지 않았습니다.");
					}
					long matcherPid = matcherPidsByClient.get(client);
					for (String duration : done.substring("DONE|".length()).split(",", -1)) {
						logicalClaims.add(new LogicalClaim(matcherPid, Long.parseLong(duration), 0, List.of()));
					}
				}
			} finally {
				for (BarrierClient client : clients) {
					client.close();
				}
			}
			for (WorkerProcess worker : workers) {
				Process process = worker.process();
				if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS) || process.exitValue() != 0) {
					throw new IllegalArgumentException("matcher process가 timeout 또는 실패했습니다: "
						+ workerDiagnostic(worker));
				}
			}
			String matcherFinishedAtUtc = Instant.now().toString();
			long measurementDurationNanos = System.nanoTime() - measurementStartedAtNanos;
			TopologyEvidence topologyEvidence = new TopologyEvidence(MATCHER_COUNT, claimAttempts, configurationSha);
			ProcessOrchestration orchestration = new ProcessOrchestration(
				workers.stream().map(worker -> worker.process().pid()).toList(), true, true, measuredGitSha, false,
				topologyEvidence, List.copyOf(logicalClaims), barrierReleasedAtUtc, matcherFinishedAtUtc,
				measurementDurationNanos);
			validateProcessEvidence(orchestration);
			return orchestration;
		} catch (IOException exception) {
			throw new IllegalArgumentException("matcher barrier 연결이 timeout 또는 실패했습니다: "
				+ workers.stream().map(MatchCandidateClaimBaselineSupport::workerDiagnostic)
					.collect(java.util.stream.Collectors.joining("; ")),
				exception);
		} finally {
			cleanupWorkers(workers);
		}
	}

	/** T2 smoke는 contention baseline이 아니라 worker entry가 production coordinator를 호출하는지만 확인한다. */
	static WorkerEntryExecution runSingleMatcherProcess(
		String jdbcUrl,
		String jdbcUsername,
		String jdbcPassword,
		CandidateFixture fixture,
		MaterializedFixture materialized) throws Exception {
		return runSingleMatcherProcess(jdbcUrl, jdbcUsername, jdbcPassword, fixture, materialized, currentGitSha());
	}

	static WorkerEntryExecution runSingleMatcherProcess(
		String jdbcUrl,
		String jdbcUsername,
		String jdbcPassword,
		CandidateFixture fixture,
		MaterializedFixture materialized,
		String measuredGitSha) throws Exception {
		verifyWorkerInput(fixture, materialized, fixture.fixtureInputSha256());
		if (!isGitSha(measuredGitSha)) {
			throw new IllegalArgumentException("측정 Git SHA는 40자리 소문자 hex여야 합니다.");
		}
		String configurationSha = sha256("matcherCount=1;claimAttempts=1");
		WorkerProcess worker = null;
		try (ServerSocket barrier = new ServerSocket(0)) {
			barrier.setSoTimeout(PROCESS_TIMEOUT_SECONDS * 1_000);
			worker = startWorker(jdbcUrl, jdbcUsername, jdbcPassword, barrier.getLocalPort(), fixture,
				measuredGitSha, configurationSha, 1);
			BarrierClient client = awaitConnected(barrier);
			try {
				try {
					awaitReady(client, fixture.fixtureInputSha256(), measuredGitSha, configurationSha);
				} catch (IOException exception) {
					throw new IOException(exception.getMessage() + " " + workerDiagnostic(worker), exception);
				}
				client.writer().write("GO\n");
				client.writer().flush();
				List<LogicalClaim> logicalClaims = logicalClaimsFromDone(client.reader().readLine(),
					worker.process().pid());
				if (!worker.process().waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
					|| worker.process().exitValue() != 0) {
					throw new IllegalArgumentException("single matcher worker가 timeout 또는 실패했습니다: "
						+ workerDiagnostic(worker));
				}
				return new WorkerEntryExecution(worker.process().pid(), measuredGitSha, true, logicalClaims);
			} finally {
				client.close();
			}
		} finally {
			if (worker != null) {
				cleanupWorkers(List.of(worker));
			}
		}
	}

	private static List<LogicalClaim> logicalClaimsFromDone(String done, long matcherPid) {
		if (done == null || !done.startsWith("DONE|")) {
			throw new IllegalArgumentException("matcher가 barrier 이후 완료를 보고하지 않았습니다.");
		}
		List<LogicalClaim> logicalClaims = new ArrayList<>();
		for (String duration : done.substring("DONE|".length()).split(",", -1)) {
			logicalClaims.add(new LogicalClaim(matcherPid, Long.parseLong(duration), 0, List.of()));
		}
		return List.copyOf(logicalClaims);
	}

	/**
	 * 계약 크기 실행을 열지 않고도 production coordinator 경로가 report 입력의 모든 관측값을 채우는지
	 * 확인하는 작은 결정적 round이다. coordinator 자체에는 retry 정책이 없으므로 retry 원자료는 0회와 빈
	 * duration 목록으로 명시한다.
	 */
	static SmallRoundReport collectSmallRound(
		String jdbcUrl,
		String jdbcUsername,
		String jdbcPassword,
		JdbcTemplate jdbcTemplate,
		CandidateFixture fixture,
		MaterializedFixture materialized,
		FixtureReportInput fixtureReport,
		int claimAttempts) throws Exception {
		return collectSmallRound(jdbcUrl, jdbcUsername, jdbcPassword, jdbcTemplate, fixture, materialized,
			fixtureReport, claimAttempts, currentGitSha());
	}

	static SmallRoundReport collectSmallRound(
		String jdbcUrl,
		String jdbcUsername,
		String jdbcPassword,
		JdbcTemplate jdbcTemplate,
		CandidateFixture fixture,
		MaterializedFixture materialized,
		FixtureReportInput fixtureReport,
		int claimAttempts,
		String measuredGitSha) throws Exception {
		jdbcTemplate.execute("create extension if not exists pg_stat_statements");
		jdbcTemplate.execute("select pg_stat_statements_reset()");
		LockSampler lockSampler = new LockSampler(jdbcTemplate);
		ProcessOrchestration orchestration;
		try {
			orchestration = runMatcherProcesses(
				jdbcUrl, jdbcUsername, jdbcPassword, fixture, materialized, claimAttempts, measuredGitSha,
				lockSampler::start);
		} finally {
			lockSampler.stop();
		}
		List<CandidateClaimStatement> candidateStatements = jdbcTemplate.query("""
			select query, calls
			from pg_stat_statements
			where dbid = (select oid from pg_database where datname = current_database())
				and lower(query) like '%from match_requests%'
				and lower(query) like '%for update skip locked%'
			""", (resultSet, rowNumber) -> new CandidateClaimStatement(
			resultSet.getString(1), resultSet.getLong(2)));
		PgStatStatements pgStatStatements = jdbcTemplate.queryForObject("""
			select coalesce(sum(calls), 0), coalesce(sum(total_exec_time), 0), coalesce(sum(rows), 0),
				coalesce(sum(shared_blks_hit), 0), coalesce(sum(shared_blks_read), 0)
			from pg_stat_statements
			where dbid = (select oid from pg_database where datname = current_database())
				and lower(query) like '%from match_requests%'
				and lower(query) like '%for update skip locked%'
			""", (resultSet, rowNumber) -> new PgStatStatements(
			resultSet.getLong(1), resultSet.getDouble(2), resultSet.getLong(3), resultSet.getLong(4),
			resultSet.getLong(5), List.copyOf(candidateStatements)));
		String queryPlan = collectCandidateClaimQueryPlan(jdbcTemplate, fixtureReport);
		CorrectnessInput correctnessInput = readCorrectnessInput(jdbcTemplate, fixtureReport);
		double throughputPerSecond = orchestration.logicalClaims().isEmpty()
			? 0.0
			: orchestration.logicalClaims().size() / (orchestration.measurementDurationNanos() / 1_000_000_000.0);
		FixtureEvidence fixtureEvidence = new FixtureEvidence(
			fixtureReport.generator(), fixtureReport.fixtureInputSha256(), fixtureReport.materializedManifestSha256());
		ReportRoundInput reportInput = new ReportRoundInput(
			1,
			orchestration.measuredGitCommitSha(),
			fixtureEvidence,
			orchestration.topologyEvidence(),
			orchestration.matcherPids().stream().map(pid -> new MatcherProcess(pid, 0, true)).toList(),
			orchestration.logicalClaims(),
			throughputPerSecond,
			pgStatStatements,
			lockSampler.snapshot(),
			queryPlan,
			correctnessInput,
			new Integrity(
				correctnessInput.proposalCount(), correctnessInput.memberCount(),
				correctnessInput.claimedRequestCount(),
				correctnessInput.duplicateClaimCount(), correctnessInput.partialClaimCount(),
				correctnessInput.tieOrderMatches()));
		return new SmallRoundReport(
			orchestration,
			orchestration.logicalClaims(),
			throughputPerSecond,
			pgStatStatements,
			reportInput.lockSamples(),
			queryPlan,
			correctnessInput,
			reportInput);
	}

	private static String collectCandidateClaimQueryPlan(
		JdbcTemplate jdbcTemplate, FixtureReportInput fixtureReport) {
		if (fixtureReport.manifest().isEmpty()) {
			throw new IllegalArgumentException("query plan cursor에 사용할 fixture manifest가 없습니다.");
		}
		MaterializedManifestEntry anchor = fixtureReport.manifest().getFirst();
		String prioritySince = anchor.prioritySince();
		String anchorSql = """
			select * from match_requests
			where status = 'WAITING'
			order by priority_since asc, id asc
			limit 1
			for update skip locked
			""".stripIndent().strip();
		String candidatePageSql = """
			select * from match_requests
			where status = 'WAITING'
			  and (priority_since > timestamptz '%s'
			    or (priority_since = timestamptz '%s' and id > %d))
			order by priority_since asc, id asc
			limit 100
			for update skip locked
			""".formatted(prioritySince, prioritySince, anchor.requestId()).stripIndent().strip();
		String anchorPlan = String.valueOf(jdbcTemplate.queryForObject(
			"explain (format json) " + anchorSql, Object.class));
		String candidatePagePlan = String.valueOf(jdbcTemplate.queryForObject(
			"explain (format json) " + candidatePageSql, Object.class));
		return "anchorSql=" + anchorSql
			+ "\nanchorPlan=" + anchorPlan
			+ "\ncandidatePageSql=" + candidatePageSql
			+ "\ncandidatePagePlan=" + candidatePagePlan;
	}

	static ReportRoundInput withRound(SmallRoundReport collected, int round) {
		ReportRoundInput input = collected.reportInput();
		return new ReportRoundInput(
			round, input.measuredGitCommitSha(), input.fixtureEvidence(), input.topologyEvidence(),
			input.matcherProcesses(), input.logicalClaims(),
			input.throughputPerSecond(),
			input.pgStatStatements(), input.lockSamples(), input.queryPlan(), input.correctnessInput(),
			input.integrity());
	}

	private static CorrectnessInput readCorrectnessInput(JdbcTemplate jdbcTemplate, FixtureReportInput fixtureReport) {
		long proposalCount = jdbcTemplate.queryForObject("select count(*) from match_proposals", Long.class);
		long memberCount = jdbcTemplate.queryForObject("select count(*) from match_proposal_members", Long.class);
		long claimedRequestCount = jdbcTemplate.queryForObject(
			"select count(*) from match_requests where status = 'PROPOSED'", Long.class);
		long waitingRequestCount = jdbcTemplate.queryForObject(
			"select count(*) from match_requests where status = 'WAITING'", Long.class);
		long duplicateClaimCount = jdbcTemplate.queryForObject("""
			select count(*) from (
				select match_request_id from match_proposal_members group by match_request_id having count(*) > 1
			) duplicate_claims
			""", Long.class);
		long partialClaimCount = jdbcTemplate.queryForObject("""
			select count(*) from (
				select proposal.id from match_proposals proposal
				left join match_proposal_members member on member.proposal_id = proposal.id
				group by proposal.id, proposal.party_size
				having count(member.match_request_id) <> proposal.party_size
			) partial_claims
			""", Long.class);
		List<TiePairResult> tiePairResults = readTiePairResults(jdbcTemplate, fixtureReport);
		boolean tieOrderMatches = tiePairResults.stream().allMatch(TiePairResult::sameProposal);
		return new CorrectnessInput(
			proposalCount, memberCount, claimedRequestCount, waitingRequestCount,
			duplicateClaimCount, partialClaimCount, tieOrderMatches, tiePairResults);
	}

	private static List<TiePairResult> readTiePairResults(JdbcTemplate jdbcTemplate, FixtureReportInput fixtureReport) {
		List<TiePairResult> results = new ArrayList<>();
		List<MaterializedManifestEntry> manifest = fixtureReport.manifest();
		if (manifest.size() != 1_000) {
			return List.of();
		}
		for (int index = 0; index + 1 < manifest.size() && index < 200; index += 2) {
			MaterializedManifestEntry first = manifest.get(index);
			MaterializedManifestEntry second = manifest.get(index + 1);
			Long commonProposalCount = jdbcTemplate.queryForObject("""
				select count(*)
				from match_proposal_members first_member
				join match_proposal_members second_member on second_member.proposal_id = first_member.proposal_id
				where first_member.match_request_id = ? and second_member.match_request_id = ?
				""", Long.class, first.requestId(), second.requestId());
			boolean sameProposal = commonProposalCount != null && commonProposalCount == 1;
			results.add(new TiePairResult(first.fixtureOrdinal(), second.fixtureOrdinal(), sameProposal));
		}
		return List.copyOf(results);
	}

	private static WorkerProcess startWorker(
		String jdbcUrl,
		String jdbcUsername,
		String jdbcPassword,
		int barrierPort,
		CandidateFixture fixture,
		String gitSha,
		String configurationSha,
		int claimAttempts) throws IOException {
		String javaExecutable = System.getProperty("java.home") + System.getProperty("file.separator") + "bin"
			+ System.getProperty("file.separator") + "java";
		return startWorker(
			jdbcUrl, jdbcUsername, jdbcPassword, barrierPort, fixture, gitSha, configurationSha, claimAttempts,
			javaExecutable, "issue775-matcher-");
	}

	static WorkerProcess startWorker(
		String jdbcUrl,
		String jdbcUsername,
		String jdbcPassword,
		int barrierPort,
		CandidateFixture fixture,
		String gitSha,
		String configurationSha,
		int claimAttempts,
		String javaExecutable,
		String temporaryFilePrefix) throws IOException {
		List<String> arguments = List.of(
			"-cp",
			System.getProperty("java.class.path"),
			MatchCandidateClaimBaselineSupport.class.getName(),
			"--worker",
			"--jdbc-url", jdbcUrl,
			"--jdbc-username", jdbcUsername,
			"--barrier-port", String.valueOf(barrierPort),
			"--fixture-sha", fixture.fixtureInputSha256(),
			"--git-sha", gitSha,
			"--configuration-sha", configurationSha,
			"--claim-attempts", String.valueOf(claimAttempts));
		Path argumentFile = null;
		Path outputFile = null;
		try {
			argumentFile = Files.createTempFile(temporaryFilePrefix, ".args");
			Files.writeString(argumentFile, arguments.stream()
				.map(MatchCandidateClaimBaselineSupport::argumentFileLine)
				.collect(java.util.stream.Collectors.joining("\n")), StandardCharsets.UTF_8);
			outputFile = Files.createTempFile(temporaryFilePrefix, ".log");
			ProcessBuilder processBuilder = new ProcessBuilder(javaExecutable, "@" + argumentFile);
			processBuilder.redirectErrorStream(true);
			processBuilder.redirectOutput(outputFile.toFile());
			processBuilder.environment().put("ISSUE775_JDBC_PASSWORD", jdbcPassword);
			processBuilder.environment().put("ISSUE775_GIT_SHA", gitSha);
			return new WorkerProcess(processBuilder.start(), argumentFile, outputFile);
		} catch (IOException exception) {
			if (argumentFile != null) {
				deleteTemporaryFile(argumentFile);
			}
			if (outputFile != null) {
				deleteTemporaryFile(outputFile);
			}
			throw exception;
		}
	}

	private static String workerDiagnostic(WorkerProcess worker) {
		try {
			String output = Files.readString(worker.outputFile(), StandardCharsets.UTF_8);
			String diagnosticOutput = output.length() <= 12_000
				? output
				: output.substring(0, 6_000) + " ... [중략] ... " + output.substring(output.length() - 6_000);
			String state = worker.process().isAlive() ? "상태=running" : "exit=" + worker.process().exitValue();
			return "pid=" + worker.process().pid() + ", " + state
				+ ", stderr/stdout=" + diagnosticOutput.replace(System.lineSeparator(), " ");
		} catch (IOException exception) {
			String state = worker.process().isAlive() ? "상태=running" : "exit=" + worker.process().exitValue();
			return "pid=" + worker.process().pid() + ", " + state
				+ ", output-read-failed";
		}
	}

	static void cleanupWorkers(List<WorkerProcess> workers) {
		boolean interrupted = false;
		for (WorkerProcess worker : workers) {
			Process process = worker.process();
			if (process.isAlive()) {
				process.destroyForcibly();
			}
			try {
				process.waitFor(5, TimeUnit.SECONDS);
			} catch (InterruptedException exception) {
				interrupted = true;
			}
			deleteTemporaryFile(worker.argumentFile());
			deleteTemporaryFile(worker.outputFile());
		}
		if (interrupted) {
			Thread.currentThread().interrupt();
		}
	}

	private static void deleteTemporaryFile(Path path) {
		for (int attempt = 0; attempt < 3; attempt++) {
			try {
				Files.deleteIfExists(path);
				return;
			} catch (IOException exception) {
				try {
					Thread.sleep(50);
				} catch (InterruptedException interruptedException) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}
		path.toFile().deleteOnExit();
		System.err.println("Issue #775 matcher temporary-file cleanup deferred: " + path.getFileName());
	}

	private static String argumentFileLine(String argument) {
		return '"' + argument.replace("\"", "\\\"") + '"';
	}

	private static BarrierClient awaitConnected(ServerSocket barrier) throws IOException {
		Socket socket = barrier.accept();
		socket.setSoTimeout(PROCESS_TIMEOUT_SECONDS * 1_000);
		BufferedReader reader = new BufferedReader(
			new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
		BufferedWriter writer = new BufferedWriter(
			new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
		if (!"CONNECTED".equals(reader.readLine())) {
			socket.close();
			throw new IllegalArgumentException("matcher가 barrier 연결을 증명하지 않았습니다.");
		}
		return new BarrierClient(socket, reader, writer);
	}

	private static long awaitReady(
		BarrierClient client,
		String expectedFixtureSha,
		String expectedGitSha,
		String expectedConfigurationSha) throws IOException {
		String readyLine = client.reader().readLine();
		if (readyLine == null) {
			throw new IOException("matcher가 READY 전에 연결을 닫았습니다.");
		}
		validateReadyMessage(readyLine, expectedFixtureSha, expectedGitSha, expectedConfigurationSha);
		return Long.parseLong(readyLine.split("\\|", -1)[1]);
	}

	static void validateReadyMessage(
		String readyLine,
		String expectedFixtureSha,
		String expectedGitSha,
		String expectedConfigurationSha) {
		String[] ready = readyLine.split("\\|", -1);
		if (ready.length != 5 || !"READY".equals(ready[0]) || ready[1].isBlank()
			|| !expectedFixtureSha.equals(ready[2]) || expectedGitSha.isBlank()
			|| !expectedGitSha.equals(ready[3]) || expectedConfigurationSha.isBlank()
			|| !expectedConfigurationSha.equals(ready[4])) {
			throw new IllegalArgumentException("matcher barrier 입력이 fixture, SHA 또는 설정과 다릅니다.");
		}
	}

	static void validateProcessEvidence(ProcessOrchestration orchestration) {
		if (orchestration.matcherPids().size() != MATCHER_COUNT
			|| orchestration.matcherPids().stream().distinct().count() != MATCHER_COUNT
			|| !orchestration.sameBarrierReleased()
			|| !orchestration.completed()
			|| orchestration.measuredGitCommitSha().isBlank()
			|| orchestration.topologyEvidence().matcherCount() != MATCHER_COUNT
			|| orchestration.topologyEvidence().claimAttempts() <= 0
			|| orchestration.topologyEvidence().configurationSha().isBlank()
			|| orchestration.logicalClaims().isEmpty()
			|| !hasExpectedClaimAttempts(orchestration)
			|| orchestration.countsTowardBaseline()) {
			throw new IllegalArgumentException("독립 matcher process 실행 증거가 유효하지 않습니다.");
		}
	}

	private static boolean hasExpectedClaimAttempts(ProcessOrchestration orchestration) {
		Map<Long, Long> attemptsByMatcher = orchestration.logicalClaims().stream()
			.collect(java.util.stream.Collectors.groupingBy(LogicalClaim::matcherPid,
				java.util.stream.Collectors.counting()));
		return attemptsByMatcher.keySet().containsAll(orchestration.matcherPids())
			&& attemptsByMatcher.size() == MATCHER_COUNT
			&& attemptsByMatcher.values().stream()
				.allMatch(attempts -> attempts == orchestration.topologyEvidence().claimAttempts());
	}

	static String currentGitSha() {
		try {
			Process process = new ProcessBuilder("git", "rev-parse", "HEAD").start();
			try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
				String sha = reader.readLine();
				if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0 || sha == null) {
					throw new IllegalStateException("현재 Git SHA를 읽지 못했습니다.");
				}
				return sha.trim();
			}
		} catch (IOException | InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("현재 Git SHA를 읽지 못했습니다.", exception);
		}
	}

	static boolean isGitSha(String value) {
		return value != null && value.matches("[0-9a-f]{40}");
	}

	static void truncateMeasurementTables(JdbcTemplate jdbcTemplate) {
		jdbcTemplate.execute("""
			truncate table match_idempotency_records, match_proposal_members, match_proposals,
			match_party_participants, match_parties, match_requests, match_blocks, users restart identity cascade
			""");
	}

	static void requireMeasurementEnabled() {
		if (!Boolean.getBoolean("issue775.measurement")) {
			throw new IllegalStateException("issue775.measurement=true가 있어야 계약 크기 측정을 실행할 수 있습니다.");
		}
	}

	public static void main(String[] args) throws Exception {
		if (args.length == 0 || !"--worker".equals(args[0])) {
			throw new IllegalArgumentException("matcher worker 실행 인자가 필요합니다.");
		}
		Map<String, String> values = arguments(args);
		String expectedGitSha = required(values, "--git-sha");
		String fixtureSha = required(values, "--fixture-sha");
		String configurationSha = required(values, "--configuration-sha");
		if (!workerGitSha().equals(expectedGitSha)) {
			throw new IllegalArgumentException("worker Git SHA가 부모 실행과 다릅니다.");
		}
		try (Socket socket = new Socket("127.0.0.1", Integer.parseInt(required(values, "--barrier-port")));
			BufferedReader reader = new BufferedReader(
				new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
			BufferedWriter writer = new BufferedWriter(
				new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
			writer.write("CONNECTED\n");
			writer.flush();
			verifyWorkerDatabaseConnection(required(values, "--jdbc-url"), required(values, "--jdbc-username"));
			List<String> durations = new ArrayList<>();
			try (ConfigurableApplicationContext context = new SpringApplicationBuilder(AlbamMateApplication.class)
				.web(WebApplicationType.SERVLET)
				.properties(Map.of(
					"spring.datasource.url", required(values, "--jdbc-url"),
					"spring.datasource.username", required(values, "--jdbc-username"),
					"spring.datasource.password", "${ISSUE775_JDBC_PASSWORD}",
					"spring.flyway.enabled", "false",
					"spring.task.scheduling.enabled", "false",
					"app.notification.relay.enabled", "false",
					"app.chat.retention.enabled", "false"))
				.run(
					"--server.port=0",
					"--spring.datasource.url=" + required(values, "--jdbc-url"),
					"--spring.datasource.username=" + required(values, "--jdbc-username"),
					"--spring.datasource.password=${ISSUE775_JDBC_PASSWORD}",
					"--spring.task.scheduling.enabled=false",
					"--app.notification.relay.enabled=false",
					"--app.chat.retention.enabled=false")) {
				MatchProposalCoordinator coordinator = context.getBean(MatchProposalCoordinator.class);
				writer.write("READY|" + ProcessHandle.current().pid() + "|" + fixtureSha + "|" + expectedGitSha
					+ "|" + configurationSha + "\n");
				writer.flush();
				if (!"GO".equals(reader.readLine())) {
					throw new IllegalArgumentException("matcher barrier가 시작 신호를 주지 않았습니다.");
				}
				for (int attempt = 0; attempt < Integer.parseInt(required(values, "--claim-attempts")); attempt++) {
					long startedAt = System.nanoTime();
					coordinator.claimAvailableCandidates();
					durations.add(String.valueOf(System.nanoTime() - startedAt));
				}
			}
			writer.write("DONE|" + String.join(",", durations) + "\n");
			writer.flush();
		}
	}

	private static String workerGitSha() {
		String configuredGitSha = System.getenv("ISSUE775_GIT_SHA");
		return configuredGitSha == null || configuredGitSha.isBlank() ? currentGitSha() : configuredGitSha;
	}

	private static void verifyWorkerDatabaseConnection(String jdbcUrl, String jdbcUsername) throws SQLException {
		try (Connection connection = DriverManager.getConnection(
			jdbcUrl, jdbcUsername, System.getenv("ISSUE775_JDBC_PASSWORD"));
			var statement = connection.createStatement();
			var resultSet = statement.executeQuery("select 1")) {
			if (!resultSet.next() || resultSet.getInt(1) != 1) {
				throw new SQLException("worker PostgreSQL 연결 확인 query가 실패했습니다.");
			}
		}
	}

	private static Map<String, String> arguments(String[] args) {
		Map<String, String> values = new LinkedHashMap<>();
		for (int index = 1; index < args.length; index += 2) {
			if (index + 1 >= args.length || !args[index].startsWith("--")) {
				throw new IllegalArgumentException("matcher worker 인자가 올바르지 않습니다.");
			}
			values.put(args[index], args[index + 1]);
		}
		return values;
	}

	private static String required(Map<String, String> values, String key) {
		String value = values.get(key);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(key + " 값이 필요합니다.");
		}
		return value;
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256을 사용할 수 없습니다.", exception);
		}
	}

	record CandidateFixture(
		String generator,
		List<FixtureRequest> requests,
		String inputCsv,
		String fixtureInputSha256,
		List<TiePair> tiePairs) {
	}

	record FixtureRequest(int fixtureOrdinal, String label, int minPartySize, int maxPartySize, Instant queuedAt,
		Instant prioritySince) {
		FixtureRequest(int fixtureOrdinal, String label, int minPartySize, int maxPartySize, Instant prioritySince) {
			this(fixtureOrdinal, label, minPartySize, maxPartySize, prioritySince, prioritySince);
		}
	}

	record TiePair(int firstOrdinal, int secondOrdinal) {
	}

	record MaterializedFixture(String fixtureInputSha256, List<MaterializedRequest> requests) {

		MaterializedFixture withFixtureInputSha256(String replacement) {
			return new MaterializedFixture(replacement, requests);
		}
	}

	record MaterializedRequest(int fixtureOrdinal, String label, long userId, long requestId) {
	}

	record FixtureReportInput(String generator, String fixtureInputSha256, String inputCsv,
		String materializedManifestSha256,
		List<MaterializedManifestEntry> manifest) {
	}

	record MaterializedManifestEntry(
		int fixtureOrdinal,
		int userFixtureOrdinal,
		String queuedAt,
		String prioritySince,
		int minPartySize,
		int maxPartySize,
		long userId,
		long requestId,
		int expectedTieOrder) {
	}

	record ProcessOrchestration(
		List<Long> matcherPids,
		boolean sameBarrierReleased,
		boolean completed,
		String measuredGitCommitSha,
		boolean countsTowardBaseline,
		TopologyEvidence topologyEvidence,
		List<LogicalClaim> logicalClaims,
		String barrierReleasedAtUtc,
		String matcherFinishedAtUtc,
		long measurementDurationNanos) {

		ProcessOrchestration(
			List<Long> matcherPids,
			boolean sameBarrierReleased,
			boolean completed,
			String measuredGitCommitSha,
			boolean countsTowardBaseline,
			TopologyEvidence topologyEvidence,
			List<LogicalClaim> logicalClaims) {
			this(matcherPids, sameBarrierReleased, completed, measuredGitCommitSha, countsTowardBaseline,
				topologyEvidence, logicalClaims, null, null, 0L);
		}
	}

	record WorkerEntryExecution(long workerPid, String measuredGitCommitSha, boolean completed,
		List<LogicalClaim> logicalClaims) {
	}

	record LogicalClaim(long matcherPid, long durationNanos, int retryCount, List<Long> retryRawDurationsNanos) {
	}

	record FixtureEvidence(String generator, String fixtureInputSha256, String materializedManifestSha256) {
	}

	record TopologyEvidence(int matcherCount, int claimAttempts, String configurationSha) {
	}

	record SmallRoundReport(
		ProcessOrchestration orchestration,
		List<LogicalClaim> logicalClaims,
		double throughputPerSecond,
		PgStatStatements pgStatStatements,
		LockSamples lockSamples,
		String queryPlan,
		CorrectnessInput correctnessInput,
		ReportRoundInput reportInput) {
	}

	/** Node reportRound 입력과 같은 field 이름으로 직렬화할 수 있는 실제 PostgreSQL 수집 결과다. */
	record ReportRoundInput(
		int round,
		String measuredGitCommitSha,
		FixtureEvidence fixtureEvidence,
		TopologyEvidence topologyEvidence,
		List<MatcherProcess> matcherProcesses,
		List<LogicalClaim> logicalClaims,
		double throughputPerSecond,
		PgStatStatements pgStatStatements,
		LockSamples lockSamples,
		String queryPlan,
		CorrectnessInput correctnessInput,
		Integrity integrity) {
	}

	record MatcherProcess(long pid, int exitCode, boolean completed) {
	}

	record PgStatStatements(
		long calls,
		double totalExecutionTimeMs,
		long rows,
		long sharedBlockHits,
		long sharedBlockReads,
		List<CandidateClaimStatement> candidateStatements) {
	}

	record CandidateClaimStatement(String query, long calls) {
	}

	record LockSamples(
		int intervalMs,
		String observationStartedAtUtc,
		String observationFinishedAtUtc,
		int snapshotCount,
		int lockWaitSnapshotCount,
		String samplingFailure) {
	}

	record CorrectnessInput(
		long proposalCount,
		long memberCount,
		long claimedRequestCount,
		long waitingRequestCount,
		long duplicateClaimCount,
		long partialClaimCount,
		boolean tieOrderMatches,
		List<TiePairResult> tiePairResults) {
	}

	record TiePairResult(
		int firstFixtureOrdinal,
		int secondFixtureOrdinal,
		boolean sameProposal) {
	}

	record Integrity(
		long proposalCount,
		long memberCount,
		long claimedRequestCount,
		long duplicateClaimCount,
		long partialClaimCount,
		boolean tieOrderMatches) {
	}

	private static final class LockSampler {

		private static final int INTERVAL_MILLIS = 10;

		private final JdbcTemplate jdbcTemplate;
		private final AtomicInteger snapshotCount = new AtomicInteger();
		private final AtomicInteger lockWaitSnapshotCount = new AtomicInteger();
		private final AtomicReference<String> samplingFailure = new AtomicReference<>();
		private final AtomicReference<Instant> observationStartedAt = new AtomicReference<>();
		private final AtomicReference<Instant> observationFinishedAt = new AtomicReference<>();
		private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

		private LockSampler(JdbcTemplate jdbcTemplate) {
			this.jdbcTemplate = jdbcTemplate;
		}

		private void start() {
			observationStartedAt.compareAndSet(null, Instant.now());
			sample();
			executor.scheduleAtFixedRate(this::sample, INTERVAL_MILLIS, INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
		}

		private void sample() {
			try {
				Integer lockWaits = jdbcTemplate.queryForObject("""
					select count(*) from pg_stat_activity where datname = current_database()
						and wait_event_type = 'Lock'
					""", Integer.class);
				if (lockWaits != null && lockWaits > 0) {
					lockWaitSnapshotCount.incrementAndGet();
				}
				snapshotCount.incrementAndGet();
			} catch (RuntimeException exception) {
				samplingFailure.compareAndSet(null, exception.getClass().getSimpleName());
			}
		}

		private void stop() {
			executor.shutdown();
			try {
				if (!executor.awaitTermination(1, TimeUnit.SECONDS)) {
					executor.shutdownNow();
				}
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
				executor.shutdownNow();
			}
			if (observationStartedAt.get() != null) {
				observationFinishedAt.compareAndSet(null, Instant.now());
			}
		}

		private LockSamples snapshot() {
			return new LockSamples(
				INTERVAL_MILLIS,
				observationStartedAt.get() == null ? null : observationStartedAt.get().toString(),
				observationFinishedAt.get() == null ? null : observationFinishedAt.get().toString(),
				snapshotCount.get(),
				lockWaitSnapshotCount.get(),
				samplingFailure.get());
		}
	}

	private record BarrierClient(Socket socket, BufferedReader reader, BufferedWriter writer) {

		private void close() throws IOException {
			socket.close();
		}
	}

	record WorkerProcess(Process process, Path argumentFile, Path outputFile) {
	}
}
