package cloud.bamsongi.albammate.matching.measurement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class MatchCandidateClaimBaselineExternalRunnerPostgresTest {

	@Test
	void 외부_측정_설정은_비밀번호를_환경변수에서만_읽고_환경_profile을_보존한다() {
		Properties properties = validProperties();

		MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration configuration = MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration
			.from(
				properties, Map.of("ISSUE775_JDBC_PASSWORD", "not-in-output"),
				"{\"stackId\":\"perf-test\",\"ephemeral\":true}");

		assertEquals("jdbc:postgresql://postgres.perf-test.albam.internal:5432/albam_mate", configuration.jdbcUrl());
		assertEquals("measurement", configuration.jdbcUsername());
		assertEquals("0123456789abcdef0123456789abcdef01234567", configuration.measuredGitSha());
		assertEquals(Path.of("build/reports/match01-external-input.json"), configuration.outputPath());
		assertEquals("{\"stackId\":\"perf-test\",\"ephemeral\":true}", configuration.environmentProfile());
		assertFalse(configuration.toString().contains("not-in-output"));
	}

	@Test
	void 외부_측정_설정은_측정_승인과_임시환경_변경_승인이_없으면_거절한다() {
		Properties missingMeasurement = validProperties();
		missingMeasurement.remove("issue775.measurement");

		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.from(
				missingMeasurement, Map.of("ISSUE775_JDBC_PASSWORD", "password"), "{\"ephemeral\":true}"));

		Properties missingMutationApproval = validProperties();
		missingMutationApproval.remove("match01.external.allow-mutation");

		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.from(
				missingMutationApproval, Map.of("ISSUE775_JDBC_PASSWORD", "password"), "{\"ephemeral\":true}"));
	}

	@Test
	void 외부_측정_설정은_비밀번호를_시스템_속성으로_받지_않는다() {
		Properties properties = validProperties();
		properties.setProperty("match01.external.jdbc-password", "password-in-property");

		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.from(
				properties, Map.of(), "{\"ephemeral\":true}"));
	}

	@Test
	void 외부_측정_환경_profile은_허용된_비밀값_없는_JSON_필드만_보존한다() {
		Properties properties = validProperties();

		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.ExternalMeasurementConfiguration.from(
				properties, Map.of("ISSUE775_JDBC_PASSWORD", "password"),
				"{\"stackId\":\"perf-test\",\"ephemeral\":true,\"apiKey\":\"secret\"}"));
	}

	@Test
	void 외부_측정은_dirty_worktree를_거절한다() {
		assertThrows(IllegalArgumentException.class,
			() -> MatchCandidateClaimBaselineExternalRunner.requireCleanWorktree(" M src/example.java\n?? secret.txt"));
	}

	private Properties validProperties() {
		Properties properties = new Properties();
		properties.setProperty("issue775.measurement", "true");
		properties.setProperty("match01.external.allow-mutation", "true");
		properties.setProperty("match01.external.jdbc-url",
			"jdbc:postgresql://postgres.perf-test.albam.internal:5432/albam_mate");
		properties.setProperty("match01.external.jdbc-username", "measurement");
		properties.setProperty("match01.external.measured-git-sha",
			"0123456789abcdef0123456789abcdef01234567");
		properties.setProperty("match01.external.output",
			"build/reports/match01-external-input.json");
		return properties;
	}
}
