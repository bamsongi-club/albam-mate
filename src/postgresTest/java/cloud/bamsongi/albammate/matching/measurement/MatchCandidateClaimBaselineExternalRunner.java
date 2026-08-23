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
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		verifyPostgreSql(dataSource);

		Map<String, Object> input = collectReportInput(jdbcTemplate, configuration);
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
			if (!"PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())) {
				throw new IllegalArgumentException("외부 JDBC target은 PostgreSQL이어야 합니다.");
			}
			try (var statement = connection.createStatement(); var resultSet = statement.executeQuery("select 1")) {
				if (!resultSet.next() || resultSet.getInt(1) != 1) {
					throw new IllegalArgumentException("외부 PostgreSQL 연결 확인 query가 실패했습니다.");
				}
			}
		}
	}

	private static Map<String, Object> collectReportInput(
		JdbcTemplate jdbcTemplate,
		ExternalMeasurementConfiguration configuration) throws Exception {
		MixedRangeSmokeEvidence mixedRangeSmoke = collectMixedRangeSmoke(jdbcTemplate, configuration);
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
					configuration.measuredGitSha());
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
			"environmentProfile", configuration.environmentProfile()));
		return Map.copyOf(input);
	}

	private static MixedRangeSmokeEvidence collectMixedRangeSmoke(
		JdbcTemplate jdbcTemplate,
		ExternalMeasurementConfiguration configuration) throws Exception {
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
				materialized,
				configuration.measuredGitSha());

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
				"--input", inputPath.toString(), "--output", outputPath.toString(), "--embed-raw-digest")
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
}
