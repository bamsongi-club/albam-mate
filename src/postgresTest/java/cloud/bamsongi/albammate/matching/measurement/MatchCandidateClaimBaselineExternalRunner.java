package cloud.bamsongi.albammate.matching.measurement;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import tools.jackson.databind.ObjectMapper;

/** Testcontainers 밖의 전용 PostgreSQL target에서 MATCH-01 T10 원자료를 수집하는 진입점이다. */
public final class MatchCandidateClaimBaselineExternalRunner {

	static final int WARM_UP_ROUND_COUNT = 1;
	static final int MEASURED_ROUND_COUNT = 3;
	static final int MATCHER_COUNT = 2;
	static final int CLAIM_ATTEMPTS_PER_MATCHER = 500;

	private static final String MEASUREMENT_PROPERTY = "issue775.measurement";
	private static final String ALLOW_MUTATION_PROPERTY = "match01.external.allow-mutation";
	private static final String JDBC_URL_PROPERTY = "match01.external.jdbc-url";
	private static final String JDBC_USERNAME_PROPERTY = "match01.external.jdbc-username";
	private static final String MEASURED_GIT_SHA_PROPERTY = "match01.external.measured-git-sha";
	private static final String OUTPUT_PROPERTY = "match01.external.output";
	private static final String ENVIRONMENT_PROFILE_FILE_PROPERTY = "match01.external.environment-profile-file";
	private static final String PASSWORD_PROPERTY = "match01.external.jdbc-password";
	private static final String PASSWORD_ENVIRONMENT = "ISSUE775_JDBC_PASSWORD";
	private static final String EXTERNAL_RUNNER_ID = "MATCH-01-T10-EXTERNAL-POSTGRESQL-V1";
	private static final String TRUSTED_METADATA_SCHEMA = "match01_control";
	private static final String TRUSTED_METADATA_VERSION = "MATCH-01-EXTERNAL-TARGET-V1";
	private static final String TRUSTED_METADATA_TABLE = "match01_external_target_metadata";
	private static final String TRUSTED_METADATA_ID = EXTERNAL_RUNNER_ID;
	private static final String PG_STAT_STATEMENTS_RESET_FUNCTION = "public.pg_stat_statements_reset(oid, oid, bigint, boolean)";
	private static final Set<String> ENVIRONMENT_PROFILE_FIELDS = Set.of(
		"stackId", "region", "accountAlias", "databaseRole", "runner", "ephemeral", "releaseSha");

	private MatchCandidateClaimBaselineExternalRunner() {}

	public static void main(String[] args) throws Exception {
		if (args.length != 0) {
			throw new IllegalArgumentException("외부 baseline runner는 명령행 인자를 받지 않습니다.");
		}
		Properties properties = System.getProperties();
		Path environmentProfilePath = Path.of(requiredProperty(properties, ENVIRONMENT_PROFILE_FILE_PROPERTY));
		String environmentProfile = Files.readString(environmentProfilePath, StandardCharsets.UTF_8);
		ExternalMeasurementConfiguration configuration = ExternalMeasurementConfiguration.from(
			properties, System.getenv(), environmentProfile);
		verifyCurrentCheckout(configuration.measuredGitSha());

		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.postgresql.Driver");
		dataSource.setUrl(configuration.jdbcUrl());
		dataSource.setUsername(configuration.jdbcUsername());
		dataSource.setPassword(configuration.jdbcPassword());
		Map<String, Object> input;
		try (Connection verifiedConnection = dataSource.getConnection()) {
			TrustedTargetMetadata trustedMetadata = verifyTrustedTarget(verifiedConnection, configuration);
			JdbcTemplate jdbcTemplate = new JdbcTemplate(new SingleConnectionDataSource(verifiedConnection, true));
			input = collectReportInput(jdbcTemplate, configuration, workerConnectionInitSql(trustedMetadata));
		}
		Path inputPath = configuration.outputPath();
		createParentDirectory(inputPath);
		new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(inputPath.toFile(), input);
		runNodeReport(inputPath, reportPath(inputPath));
	}

	private static void verifyCurrentCheckout(String measuredGitSha) {
		String currentGitSha = MatchCandidateClaimBaselineSupport.currentGitSha();
		if (!currentGitSha.equals(measuredGitSha)) {
			throw new IllegalArgumentException(
				"runner checkout SHA와 측정 SHA가 다릅니다. runner=" + currentGitSha + ", measured=" + measuredGitSha);
		}
		requireCleanWorktree(readWorktreeStatus());
	}

	static void requireCleanWorktree(String porcelainStatus) {
		if (porcelainStatus != null && !porcelainStatus.isBlank()) {
			throw new IllegalArgumentException("외부 측정은 clean worktree에서만 실행할 수 있습니다.");
		}
	}

	private static String readWorktreeStatus() {
		try {
			Process process = new ProcessBuilder("git", "status", "--porcelain=v1", "--untracked-files=all")
				.redirectErrorStream(true)
				.start();
			String status = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
			if (!process.waitFor(5, TimeUnit.SECONDS) || process.exitValue() != 0) {
				throw new IllegalStateException("측정 worktree 상태를 확인하지 못했습니다.");
			}
			return status;
		} catch (java.io.IOException exception) {
			throw new IllegalStateException("측정 worktree 상태를 확인하지 못했습니다.", exception);
		} catch (InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("측정 worktree 상태 확인이 중단되었습니다.", exception);
		}
	}

	private static void verifyPostgreSql(DriverManagerDataSource dataSource) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			verifyPostgreSql(connection);
		}
	}

	private static void verifyPostgreSql(Connection connection) throws Exception {
		if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
			throw new IllegalArgumentException("외부 JDBC target은 PostgreSQL이어야 합니다.");
		}
		try (var statement = connection.createStatement(); var resultSet = statement.executeQuery("select 1")) {
			if (!resultSet.next() || resultSet.getInt(1) != 1) {
				throw new IllegalArgumentException("외부 PostgreSQL 연결 확인 query가 실패했습니다.");
			}
		}
	}

	private static void verifyTrustedTarget(
		DriverManagerDataSource dataSource, ExternalMeasurementConfiguration configuration) throws Exception {
		try (Connection connection = dataSource.getConnection()) {
			verifyTrustedTarget(connection, configuration);
		}
	}

	private static TrustedTargetMetadata verifyTrustedTarget(
		Connection connection, ExternalMeasurementConfiguration configuration) throws Exception {
		verifyPostgreSql(connection);
		TrustedTargetMetadata metadata = readTrustedTargetMetadata(connection);
		JdbcTarget jdbcTarget = JdbcTarget.from(configuration.jdbcUrl());
		String metadataServerAddress = normalizeServerAddress(metadata.serverAddress()).toLowerCase(Locale.ROOT);
		String metadataEndpoint = endpoint(metadataServerAddress, metadata.serverPort());
		if (!jdbcTarget.endpoint().equals(metadata.jdbcEndpoint())
			|| !jdbcTarget.database().equals(metadata.database())
			|| !metadataEndpoint.equals(metadata.jdbcEndpoint())
			|| !jdbcTarget.host().equals(metadataServerAddress)
			|| jdbcTarget.port() != metadata.serverPort()) {
			throw new IllegalArgumentException(
				"JDBC target은 provisioner가 기록한 실제 server address와 port를 직접 사용해야 합니다.");
		}
		TargetIdentity identity = readTargetIdentity(connection);
		if (!metadata.database().equals(identity.database())
			|| !metadata.databaseRole().equals(identity.user())
			|| !metadata.databaseUser().equals(identity.user())
			|| !metadataServerAddress.equals(identity.serverAddress())
			|| metadata.serverPort() != identity.serverPort()
			|| !configuration.jdbcUsername().equals(identity.user())) {
			throw new IllegalArgumentException(
				"PostgreSQL database identity가 provisioner target metadata와 일치하지 않습니다.");
		}
		Map<String, Object> profile = readEnvironmentProfile(configuration.environmentProfile());
		String profileStackId = requiredProfileValue(profile, "stackId");
		String profileRunner = requiredProfileValue(profile, "runner");
		String profileDatabaseRole = requiredProfileValue(profile, "databaseRole");
		String profileReleaseSha = requiredProfileValue(profile, "releaseSha");
		if (!metadata.stackId().equals(profileStackId)
			|| !metadata.runner().equals(profileRunner)
			|| !metadata.databaseRole().equals(profileDatabaseRole)
			|| !metadata.releaseSha().equals(profileReleaseSha)
			|| !configuration.measuredGitSha().equals(profileReleaseSha)
			|| !metadata.ephemeral()
			|| !Boolean.TRUE.equals(profile.get("ephemeral"))) {
			throw new IllegalArgumentException(
				"runner profile의 release provenance가 provisioner target metadata와 일치하지 않습니다.");
		}
		verifyPgStatStatements(connection);
		return metadata;
	}

	private static void verifyPgStatStatements(Connection connection) throws Exception {
		boolean extensionInstalled;
		try (var statement = connection.createStatement();
			var resultSet = statement.executeQuery(
				"select exists (select 1 from pg_extension where extname = 'pg_stat_statements')")) {
			if (!resultSet.next()) {
				throw new IllegalArgumentException("pg_stat_statements extension 설치 여부를 확인하지 못했습니다.");
			}
			extensionInstalled = resultSet.getBoolean(1);
		}
		if (!extensionInstalled) {
			throw new IllegalArgumentException(
				"외부 PostgreSQL target에는 pg_stat_statements extension이 provisioner에 의해 설치되어야 합니다.");
		}

		try (var statement = connection.createStatement();
			var resultSet = statement.executeQuery("select exists (select 1 from pg_stat_statements)")) {
			if (!resultSet.next()) {
				throw new IllegalArgumentException("pg_stat_statements view를 읽을 수 없습니다.");
			}
		} catch (java.sql.SQLException exception) {
			throw new IllegalArgumentException(
				"외부 PostgreSQL target의 pg_stat_statements shared_preload_libraries 설정을 확인할 수 없습니다.", exception);
		}

		String privilegeQuery = "select to_regprocedure('" + PG_STAT_STATEMENTS_RESET_FUNCTION + "') is not null, "
			+ "case when to_regprocedure('" + PG_STAT_STATEMENTS_RESET_FUNCTION + "') is null then false "
			+ "else has_function_privilege(current_user, "
			+ "to_regprocedure('" + PG_STAT_STATEMENTS_RESET_FUNCTION + "'), 'EXECUTE') end";
		try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(privilegeQuery)) {
			if (!resultSet.next() || !resultSet.getBoolean(1) || !resultSet.getBoolean(2)) {
				throw new IllegalArgumentException(
					"외부 PostgreSQL target에는 " + PG_STAT_STATEMENTS_RESET_FUNCTION + " EXECUTE 권한이 필요합니다.");
			}
		}
	}

	private static String workerConnectionInitSql(TrustedTargetMetadata metadata) {
		String serverAddress = normalizeServerAddress(metadata.serverAddress());
		return "do $match01$ declare actual_server_address text; begin "
			+ "actual_server_address := split_part(inet_server_addr()::text, '/', 1); "
			+ "if current_database() <> " + sqlLiteral(metadata.database())
			+ " or current_user <> " + sqlLiteral(metadata.databaseUser())
			+ " or lower(actual_server_address) <> lower(" + sqlLiteral(serverAddress)
			+ ") or inet_server_port() <> " + metadata.serverPort()
			+ " then raise exception 'MATCH-01 worker PostgreSQL identity mismatch'; end if; "
			+ "if not exists (select 1 from pg_class c "
			+ "join pg_namespace schema_namespace on schema_namespace.oid = c.relnamespace "
			+ "join pg_roles table_owner on table_owner.oid = c.relowner "
			+ "join pg_roles schema_owner on schema_owner.oid = schema_namespace.nspowner "
			+ "where schema_namespace.nspname = " + sqlLiteral(TRUSTED_METADATA_SCHEMA)
			+ " and c.relname = " + sqlLiteral(TRUSTED_METADATA_TABLE)
			+ " and c.relkind = 'r' and not table_owner.rolcanlogin and not schema_owner.rolcanlogin "
			+ "and has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'SELECT') "
			+ "and not has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'INSERT') "
			+ "and not has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'UPDATE') "
			+ "and not has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'DELETE') "
			+ "and not has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'TRUNCATE')) "
			+ "then raise exception 'MATCH-01 worker trusted metadata boundary mismatch'; end if; "
			+ "if not exists (select 1 from match01_control.match01_external_target_metadata "
			+ "where metadata_id = " + sqlLiteral(TRUSTED_METADATA_ID)
			+ " and schema_version = " + sqlLiteral(TRUSTED_METADATA_VERSION)
			+ " and stack_id = " + sqlLiteral(metadata.stackId())
			+ " and database_name = " + sqlLiteral(metadata.database())
			+ " and database_role = " + sqlLiteral(metadata.databaseRole())
			+ " and database_user = " + sqlLiteral(metadata.databaseUser())
			+ " and jdbc_endpoint = " + sqlLiteral(metadata.jdbcEndpoint())
			+ " and server_address = " + sqlLiteral(serverAddress)
			+ " and server_port = " + metadata.serverPort()
			+ " and runner = " + sqlLiteral(metadata.runner())
			+ " and ephemeral is true and release_sha = " + sqlLiteral(metadata.releaseSha())
			+ ") then raise exception 'MATCH-01 worker trusted metadata row mismatch'; end if; "
			+ "end $match01$;";
	}

	private static String sqlLiteral(String value) {
		return "'" + value.replace("'", "''") + "'";
	}

	private static TrustedTargetMetadata readTrustedTargetMetadata(Connection connection) throws Exception {
		verifyTrustedMetadataTable(connection);
		String query = "select schema_version, stack_id, database_name, database_role, database_user, "
			+ "jdbc_endpoint, server_address, server_port, runner, ephemeral, release_sha "
			+ "from " + TRUSTED_METADATA_SCHEMA + "." + TRUSTED_METADATA_TABLE + " where metadata_id = ?";
		try (var statement = connection.prepareStatement(query)) {
			statement.setString(1, TRUSTED_METADATA_ID);
			try (var resultSet = statement.executeQuery()) {
				if (!resultSet.next()) {
					throw new IllegalArgumentException("provisioner-owned 외부 PostgreSQL target metadata가 없습니다.");
				}
				String schemaVersion = requiredMetadataColumn(resultSet, "schema_version");
				int serverPort = resultSet.getInt("server_port");
				boolean serverPortWasNull = resultSet.wasNull();
				boolean ephemeral = resultSet.getBoolean("ephemeral");
				boolean ephemeralWasNull = resultSet.wasNull();
				TrustedTargetMetadata metadata = new TrustedTargetMetadata(
					requiredMetadataColumn(resultSet, "jdbc_endpoint"),
					requiredMetadataColumn(resultSet, "stack_id"),
					requiredMetadataColumn(resultSet, "database_name"),
					requiredMetadataColumn(resultSet, "database_role"),
					requiredMetadataColumn(resultSet, "database_user"),
					requiredMetadataColumn(resultSet, "server_address"),
					serverPort,
					requiredMetadataColumn(resultSet, "runner"),
					ephemeral,
					requiredMetadataColumn(resultSet, "release_sha"));
				if (!TRUSTED_METADATA_VERSION.equals(schemaVersion)
					|| serverPortWasNull || ephemeralWasNull || !EXTERNAL_RUNNER_ID.equals(metadata.runner())
					|| !MatchCandidateClaimBaselineSupport.isGitSha(metadata.releaseSha())
					|| metadata.serverPort() < 1 || metadata.serverPort() > 65535) {
					throw new IllegalArgumentException(
						"trusted metadata의 schema, runner, release SHA 또는 port가 올바르지 않습니다.");
				}
				if (resultSet.next()) {
					throw new IllegalArgumentException("외부 PostgreSQL target metadata가 여러 행입니다.");
				}
				return metadata;
			}
		}
	}

	private static String requiredMetadataColumn(java.sql.ResultSet resultSet, String name) throws Exception {
		String value = resultSet.getString(name);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException("trusted metadata에 " + name + "이 필요합니다.");
		}
		return value;
	}

	private static void verifyTrustedMetadataTable(Connection connection) throws Exception {
		String query = """
			select c.relkind, table_owner.rolname, table_owner.rolcanlogin,
			       schema_owner.rolcanlogin,
			       has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'SELECT'),
			       has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'INSERT'),
			       has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'UPDATE'),
			       has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'DELETE'),
			       has_table_privilege(current_user, 'match01_control.match01_external_target_metadata', 'TRUNCATE')
			from pg_class c
			join pg_namespace schema_namespace on schema_namespace.oid = c.relnamespace
			join pg_roles table_owner on table_owner.oid = c.relowner
			join pg_roles schema_owner on schema_owner.oid = schema_namespace.nspowner
			where schema_namespace.nspname = 'match01_control'
			  and c.relname = 'match01_external_target_metadata'
			""";
		try (var statement = connection.createStatement(); var resultSet = statement.executeQuery(query)) {
			if (!resultSet.next()) {
				throw new IllegalArgumentException("provisioner-owned 외부 PostgreSQL metadata table이 없습니다.");
			}
			String currentUser = connection.getMetaData().getUserName();
			boolean valid = "r".equals(resultSet.getString(1))
				&& !currentUser.equals(resultSet.getString(2))
				&& !resultSet.getBoolean(3)
				&& !resultSet.getBoolean(4)
				&& resultSet.getBoolean(5)
				&& !resultSet.getBoolean(6)
				&& !resultSet.getBoolean(7)
				&& !resultSet.getBoolean(8)
				&& !resultSet.getBoolean(9);
			if (!valid || resultSet.next()) {
				throw new IllegalArgumentException(
					"외부 PostgreSQL metadata table은 login 불가 provisioner 소유이며 runner는 SELECT만 가져야 합니다.");
			}
		}
	}

	private static TargetIdentity readTargetIdentity(Connection connection) throws Exception {
		try (var statement = connection.createStatement();
			var resultSet = statement.executeQuery(
				"select current_database(), current_user, inet_server_addr()::text, inet_server_port()")) {
			if (!resultSet.next() || resultSet.getString(1) == null || resultSet.getString(2) == null
				|| resultSet.getString(3) == null || resultSet.getObject(4) == null) {
				throw new IllegalArgumentException("외부 PostgreSQL database identity를 읽지 못했습니다.");
			}
			return new TargetIdentity(
				resultSet.getString(1), resultSet.getString(2),
				normalizeServerAddress(resultSet.getString(3)).toLowerCase(Locale.ROOT),
				resultSet.getInt(4));
		}
	}

	private static String normalizeServerAddress(String value) {
		int maskSeparator = value.indexOf('/');
		return maskSeparator < 0 ? value : value.substring(0, maskSeparator);
	}

	private static String endpoint(String serverAddress, int serverPort) {
		return serverAddress.contains(":")
			? "[" + serverAddress + "]:" + serverPort
			: serverAddress + ":" + serverPort;
	}

	private static Map<String, Object> readEnvironmentProfile(String rawProfile) throws Exception {
		Object parsed = new ObjectMapper().readValue(rawProfile, Map.class);
		if (!(parsed instanceof Map<?, ?> profile)) {
			throw new IllegalArgumentException("환경 profile은 JSON object여야 합니다.");
		}
		Map<String, Object> values = new LinkedHashMap<>();
		for (Map.Entry<?, ?> entry : profile.entrySet()) {
			if (entry.getKey() instanceof String key) {
				values.put(key, entry.getValue());
			}
		}
		return Map.copyOf(values);
	}

	private static String requiredProfileValue(Map<String, Object> profile, String name) {
		Object value = profile.get(name);
		if (!(value instanceof String stringValue) || stringValue.isBlank()) {
			throw new IllegalArgumentException("환경 profile에 " + name + "이 필요합니다.");
		}
		return stringValue;
	}

	private static Map<String, Object> collectReportInput(
		JdbcTemplate jdbcTemplate,
		ExternalMeasurementConfiguration configuration,
		String workerConnectionInitSql) throws Exception {
		MixedRangeSmokeEvidence mixedRangeSmoke = collectMixedRangeSmoke(
			jdbcTemplate, configuration, workerConnectionInitSql);
		List<MatchCandidateClaimBaselineSupport.ReportRoundInput> measuredRounds = new ArrayList<>();
		MatchCandidateClaimBaselineSupport.ReportRoundInput warmUp = null;
		MatchCandidateClaimBaselineSupport.FixtureReportInput fixtureReport = null;
		int totalRoundCount = WARM_UP_ROUND_COUNT + MEASURED_ROUND_COUNT;
		for (int round = 0; round < totalRoundCount; round++) {
			MatchCandidateClaimBaselineSupport.truncateMeasurementTables(jdbcTemplate);
			MatchCandidateClaimBaselineSupport.CandidateFixture fixture = MatchCandidateClaimBaselineSupport
				.createContractFixture();
			MatchCandidateClaimBaselineSupport.MaterializedFixture materialized = MatchCandidateClaimBaselineSupport
				.materialize(jdbcTemplate, fixture);
			fixtureReport = MatchCandidateClaimBaselineSupport.reportFixture(jdbcTemplate, fixture, materialized);
			MatchCandidateClaimBaselineSupport.SmallRoundReport collected = MatchCandidateClaimBaselineSupport
				.collectSmallRound(
					configuration.jdbcUrl(), configuration.jdbcUsername(), configuration.jdbcPassword(), jdbcTemplate,
					fixture, materialized, fixtureReport, CLAIM_ATTEMPTS_PER_MATCHER,
					configuration.measuredGitSha(), workerConnectionInitSql);
			if (collected.logicalClaims().size() != MATCHER_COUNT * CLAIM_ATTEMPTS_PER_MATCHER
				|| collected.correctnessInput().tiePairResults().size() != 100) {
				throw new IllegalArgumentException("T10 round의 logical claim 또는 tie 결과 수가 계약과 다릅니다.");
			}
			MatchCandidateClaimBaselineSupport.ReportRoundInput reportRound = MatchCandidateClaimBaselineSupport
				.withRound(collected, round);
			if (round < WARM_UP_ROUND_COUNT) {
				warmUp = reportRound;
			} else {
				measuredRounds.add(reportRound);
			}
		}

		Map<String, Object> input = new LinkedHashMap<>();
		input.put("fixture", fixtureReport);
		input.put("measuredGitCommitSha", configuration.measuredGitSha());
		input.put("environmentProfile", configuration.environmentProfile());
		input.put("mixedRangeSmoke", mixedRangeSmoke);
		input.put("warmUp", warmUp);
		input.put("measured", List.copyOf(measuredRounds));
		input.put("externalProvenance", Map.of(
			"runner", "MATCH-01-T10-EXTERNAL-POSTGRESQL-V1",
			"target", "external-postgresql",
			"environmentProfile", readEnvironmentProfile(configuration.environmentProfile())));
		return Map.copyOf(input);
	}

	private static MixedRangeSmokeEvidence collectMixedRangeSmoke(
		JdbcTemplate jdbcTemplate,
		ExternalMeasurementConfiguration configuration,
		String workerConnectionInitSql) throws Exception {
		MatchCandidateClaimBaselineSupport.truncateMeasurementTables(jdbcTemplate);
		MatchCandidateClaimBaselineSupport.CandidateFixture fixture = MatchCandidateClaimBaselineSupport
			.createMixedRangeSmokeFixture();
		MatchCandidateClaimBaselineSupport.MaterializedFixture materialized = MatchCandidateClaimBaselineSupport
			.materialize(jdbcTemplate, fixture);
		MatchCandidateClaimBaselineSupport.FixtureReportInput fixtureReport = MatchCandidateClaimBaselineSupport
			.reportFixture(jdbcTemplate, fixture, materialized);
		MatchCandidateClaimBaselineSupport.WorkerEntryExecution worker = MatchCandidateClaimBaselineSupport
			.runSingleMatcherProcess(
				configuration.jdbcUrl(), configuration.jdbcUsername(), configuration.jdbcPassword(), fixture,
				materialized, configuration.measuredGitSha(), workerConnectionInitSql);

		Integer proposalCount = jdbcTemplate.queryForObject("select count(*) from match_proposals", Integer.class);
		Integer targetPartySize = jdbcTemplate.queryForObject("select party_size from match_proposals", Integer.class);
		List<String> proposalMembers = jdbcTemplate.queryForList("""
			select u.nickname
			from match_proposal_members member
			join users u on u.id = member.user_id
			order by u.nickname
			""", String.class);
		Integer waitingRequestCount = jdbcTemplate.queryForObject(
			"select count(*) from match_requests where status = 'WAITING'", Integer.class);
		boolean passed = worker.completed()
			&& worker.measuredGitCommitSha().equals(configuration.measuredGitSha())
			&& worker.logicalClaims().size() == 1
			&& fixture.requests().size() == 5
			&& proposalCount != null
			&& proposalCount == 1
			&& targetPartySize != null
			&& targetPartySize == 2
			&& proposalMembers.equals(List.of("R1", "R3"))
			&& waitingRequestCount != null
			&& waitingRequestCount == 3;
		MixedRangeSmokeEvidence evidence = new MixedRangeSmokeEvidence(
			fixture.generator(), fixture.fixtureInputSha256(),
			fixtureReport.materializedManifestSha256(),
			List.copyOf(fixtureReport.manifest()),
			configuration.measuredGitSha(), proposalCount == null ? -1 : proposalCount,
			targetPartySize == null ? -1 : targetPartySize, List.copyOf(proposalMembers),
			waitingRequestCount == null ? -1 : waitingRequestCount, passed);
		if (!passed) {
			throw new IllegalStateException("혼합 범위 correctness smoke가 계약과 일치하지 않습니다: " + evidence);
		}
		return evidence;
	}

	private static Path reportPath(Path inputPath) {
		String fileName = inputPath.getFileName().toString();
		String reportFileName = fileName.endsWith(".json")
			? fileName.substring(0, fileName.length() - ".json".length()) + ".report.json"
			: fileName + ".report.json";
		return inputPath.resolveSibling(reportFileName);
	}

	private static void createParentDirectory(Path path) throws Exception {
		Path parent = path.toAbsolutePath().getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}
	}

	private static void runNodeReport(Path inputPath, Path outputPath) throws Exception {
		createParentDirectory(outputPath);
		Path diagnostic = Files.createTempFile("match01-external-report-", ".log");
		Process process = null;
		try {
			process = new ProcessBuilder(
				"node", "scripts/measurements/match01-candidate-baseline-report.mjs",
				"--input", inputPath.toString(), "--output", outputPath.toString(), "--external", "--embed-raw-digest")
				.directory(Path.of(System.getProperty("user.dir")).toFile())
				.redirectErrorStream(true)
				.redirectOutput(diagnostic.toFile())
				.start();
			if (!process.waitFor(90, TimeUnit.SECONDS) || process.exitValue() != 0) {
				String diagnosticText = Files.readString(diagnostic, StandardCharsets.UTF_8);
				throw new IllegalStateException("candidate baseline report 생성에 실패했습니다: " + diagnosticText);
			}
		} finally {
			if (process != null && process.isAlive()) {
				process.destroyForcibly();
			}
			Files.deleteIfExists(diagnostic);
		}
	}

	private static String requiredProperty(Properties properties, String name) {
		String value = properties.getProperty(name);
		if (value == null || value.isBlank()) {
			throw new IllegalArgumentException(name + " 시스템 속성이 필요합니다.");
		}
		return value;
	}

	static final class ExternalMeasurementConfiguration {

		private final String jdbcUrl;
		private final String jdbcUsername;
		private final String jdbcPassword;
		private final String measuredGitSha;
		private final Path outputPath;
		private final String environmentProfile;

		private ExternalMeasurementConfiguration(
			String jdbcUrl,
			String jdbcUsername,
			String jdbcPassword,
			String measuredGitSha,
			Path outputPath,
			String environmentProfile) {
			this.jdbcUrl = jdbcUrl;
			this.jdbcUsername = jdbcUsername;
			this.jdbcPassword = jdbcPassword;
			this.measuredGitSha = measuredGitSha;
			this.outputPath = outputPath;
			this.environmentProfile = environmentProfile;
		}

		static ExternalMeasurementConfiguration from(
			Properties properties, Map<String, String> environment, String environmentProfile) {
			if (!Boolean.parseBoolean(properties.getProperty(MEASUREMENT_PROPERTY, "false"))) {
				throw new IllegalArgumentException(MEASUREMENT_PROPERTY + "=true가 필요합니다.");
			}
			if (!Boolean.parseBoolean(properties.getProperty(ALLOW_MUTATION_PROPERTY, "false"))) {
				throw new IllegalArgumentException(ALLOW_MUTATION_PROPERTY + "=true가 필요합니다.");
			}
			if (properties.containsKey(PASSWORD_PROPERTY)) {
				throw new IllegalArgumentException("JDBC 비밀번호는 시스템 속성으로 전달할 수 없습니다.");
			}
			String jdbcUrl = requiredProperty(properties, JDBC_URL_PROPERTY);
			if (!jdbcUrl.startsWith("jdbc:postgresql://")
				|| jdbcUrl.toLowerCase(Locale.ROOT).contains("password=")) {
				throw new IllegalArgumentException("PostgreSQL JDBC URL에 비밀번호를 포함할 수 없습니다.");
			}
			String jdbcUsername = requiredProperty(properties, JDBC_USERNAME_PROPERTY);
			String measuredGitSha = requiredProperty(properties, MEASURED_GIT_SHA_PROPERTY);
			if (!MatchCandidateClaimBaselineSupport.isGitSha(measuredGitSha)) {
				throw new IllegalArgumentException("측정 Git SHA는 40자리 소문자 hex여야 합니다.");
			}
			String password = requiredEnvironment(environment, PASSWORD_ENVIRONMENT);
			String output = requiredProperty(properties, OUTPUT_PROPERTY);
			if (environmentProfile == null || environmentProfile.isBlank()) {
				throw new IllegalArgumentException("환경 profile 파일이 비어 있습니다.");
			}
			String validatedEnvironmentProfile = validateEnvironmentProfile(environmentProfile);
			return new ExternalMeasurementConfiguration(
				jdbcUrl, jdbcUsername, password, measuredGitSha, Path.of(output), validatedEnvironmentProfile);
		}

		private static String validateEnvironmentProfile(String rawProfile) {
			try {
				Object parsed = new ObjectMapper().readValue(rawProfile, Map.class);
				if (!(parsed instanceof Map<?, ?> profile)) {
					throw new IllegalArgumentException("환경 profile은 JSON object여야 합니다.");
				}
				Object stackId = profile.get("stackId");
				if (!(stackId instanceof String stackIdValue) || stackIdValue.isBlank()) {
					throw new IllegalArgumentException("환경 profile에 비어 있지 않은 stackId가 필요합니다.");
				}
				if (!Boolean.TRUE.equals(profile.get("ephemeral"))) {
					throw new IllegalArgumentException("환경 profile은 ephemeral=true 전용 target이어야 합니다.");
				}
				for (Map.Entry<?, ?> entry : profile.entrySet()) {
					if (!(entry.getKey() instanceof String field) || !ENVIRONMENT_PROFILE_FIELDS.contains(field)) {
						throw new IllegalArgumentException("환경 profile에 허용되지 않은 필드가 있습니다.");
					}
					Object profileValue = entry.getValue();
					if (!(profileValue instanceof String || profileValue instanceof Number
						|| profileValue instanceof Boolean)) {
						throw new IllegalArgumentException("환경 profile 값은 비밀값이 없는 scalar만 허용합니다.");
					}
				}
				return new ObjectMapper().writeValueAsString(profile);
			} catch (IllegalArgumentException exception) {
				throw exception;
			} catch (Exception exception) {
				throw new IllegalArgumentException("환경 profile JSON을 검증하지 못했습니다.", exception);
			}
		}

		private static String requiredProperty(Properties properties, String name) {
			String value = properties.getProperty(name);
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(name + " 시스템 속성이 필요합니다.");
			}
			return value;
		}

		private static String requiredEnvironment(Map<String, String> environment, String name) {
			String value = environment.get(name);
			if (value == null || value.isBlank()) {
				throw new IllegalArgumentException(name + " 환경변수가 필요합니다.");
			}
			return value;
		}

		String jdbcUrl() {
			return jdbcUrl;
		}

		String jdbcUsername() {
			return jdbcUsername;
		}

		String jdbcPassword() {
			return jdbcPassword;
		}

		String measuredGitSha() {
			return measuredGitSha;
		}

		Path outputPath() {
			return outputPath;
		}

		String environmentProfile() {
			return environmentProfile;
		}

		@Override
		public String toString() {
			return "ExternalMeasurementConfiguration{jdbcUrl='" + jdbcUrl + "', jdbcUsername='" + jdbcUsername
				+ "', measuredGitSha='" + measuredGitSha + "', outputPath=" + outputPath
				+ ", environmentProfile='" + environmentProfile + "'}";
		}
	}

	record MixedRangeSmokeEvidence(
		String fixtureGenerator,
		String fixtureInputSha256,
		String materializedManifestSha256,
		List<MatchCandidateClaimBaselineSupport.MaterializedManifestEntry> materializedManifest,
		String measuredGitCommitSha,
		int proposalCount,
		int targetPartySize,
		List<String> proposalMembers,
		int waitingRequestCount,
		boolean passed) {
	}

	private record TrustedTargetMetadata(
		String jdbcEndpoint,
		String stackId,
		String database,
		String databaseRole,
		String databaseUser,
		String serverAddress,
		int serverPort,
		String runner,
		boolean ephemeral,
		String releaseSha) {
	}

	private record TargetIdentity(String database, String user, String serverAddress, int serverPort) {
	}

	private record JdbcTarget(String endpoint, String host, int port, String database) {

		private static JdbcTarget from(String jdbcUrl) {
			try {
				String prefix = "jdbc:postgresql://";
				if (!jdbcUrl.startsWith(prefix)) {
					throw new IllegalArgumentException("외부 PostgreSQL JDBC URL의 scheme이 올바르지 않습니다.");
				}
				String authorityAndDatabase = jdbcUrl.substring(prefix.length());
				int slash = authorityAndDatabase.indexOf('/');
				if (slash <= 0 || slash == authorityAndDatabase.length() - 1) {
					throw new IllegalArgumentException(
						"외부 PostgreSQL JDBC URL의 authority 또는 database가 올바르지 않습니다: " + jdbcUrl);
				}
				String authority = authorityAndDatabase.substring(0, slash);
				String databaseAndOptions = authorityAndDatabase.substring(slash + 1);
				int optionsStart = databaseAndOptions.indexOf('?');
				String database = optionsStart < 0 ? databaseAndOptions : databaseAndOptions.substring(0, optionsStart);
				if (database.isBlank() || database.contains("/")) {
					throw new IllegalArgumentException("외부 PostgreSQL JDBC URL의 database가 올바르지 않습니다: " + jdbcUrl);
				}
				String host;
				int port = 5432;
				if (authority.startsWith("[")) {
					int closingBracket = authority.indexOf(']');
					if (closingBracket <= 1) {
						throw new IllegalArgumentException("외부 PostgreSQL JDBC URL의 IPv6 host가 올바르지 않습니다.");
					}
					host = authority.substring(1, closingBracket);
					if (closingBracket + 1 < authority.length()) {
						if (authority.charAt(closingBracket + 1) != ':') {
							throw new IllegalArgumentException("외부 PostgreSQL JDBC URL의 port가 올바르지 않습니다.");
						}
						port = Integer.parseInt(authority.substring(closingBracket + 2));
					}
				} else {
					int colon = authority.lastIndexOf(':');
					if (colon > 0) {
						host = authority.substring(0, colon);
						port = Integer.parseInt(authority.substring(colon + 1));
					} else {
						host = authority;
					}
				}
				if (host.isBlank() || host.contains("@") || port < 1 || port > 65535) {
					throw new IllegalArgumentException(
						"외부 PostgreSQL JDBC URL의 endpoint 또는 port가 올바르지 않습니다: " + jdbcUrl);
				}
				String normalizedHost = host.toLowerCase(Locale.ROOT);
				String endpoint = normalizedHost.contains(":")
					? "[" + normalizedHost + "]:" + port
					: normalizedHost + ":" + port;
				return new JdbcTarget(endpoint, normalizedHost, port, database);
			} catch (IllegalArgumentException exception) {
				throw exception;
			} catch (Exception exception) {
				throw new IllegalArgumentException("외부 PostgreSQL JDBC URL을 해석하지 못했습니다.", exception);
			}
		}
	}
}
