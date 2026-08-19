package cloud.bamsongi.albammate.room.measurement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** 후보 A·B·C가 같은 모집단과 metric schema를 사용하도록 고정하는 공통 계약이다. */
final class RoomLockComparisonMeasurementContract {

	static final String BASE_SHA = "49b960a1f7537574b39d67ff22df8890a3891ef6";
	static final Instant FIXED_TIME = Instant.parse("2026-08-17T00:00:00Z");
	static final String FIXTURE_SEED = "ROOM-LOCK-01-20260817";
	static final int CONCURRENCY_LEVEL = 2;
	static final int T2_ROUND_REPETITIONS = 3;

	static final List<String> SINGLE_T2_SCENARIOS = List.of(
		"T2-due-room-order",
		"T2-lock",
		"T2-rollback");

	static final List<String> REPEATED_T2_SCENARIOS = List.of(
		"T2-waitlist-new-promotion",
		"T2-waitlist-new-cancel-first-promotion",
		"T2-waitlist-reactivation-promotion",
		"T2-waitlist-reactivation-cancel-first-promotion",
		"T2-start-direct-participation-first",
		"T2-start-correction-first",
		"T2-start-waitlist-new-registration-first",
		"T2-start-waitlist-new-correction-first",
		"T2-start-waitlist-reactivation-registration-first",
		"T2-start-waitlist-reactivation-correction-first",
		"T2-start-participation-cancel-first",
		"T2-start-participation-correction-first",
		"T2-start-waitlist-cancel-first",
		"T2-start-waitlist-correction-first");

	static final List<String> T3_SCENARIOS = List.of(
		"T3-lock-timeout",
		"T3-optimistic-exhausted",
		"T3-deadlock",
		"T3-unexpected-technical");

	static final List<String> METRIC_FIELDS = List.of(
		"candidate",
		"scenario",
		"requestCount",
		"success",
		"businessFailure",
		"concurrencyFailure",
		"technicalFailure",
		"conflictCount",
		"retry0",
		"retry1",
		"retry2",
		"exhausted",
		"responseNanos",
		"calls",
		"totalExecMs",
		"rows",
		"sharedBlksHit",
		"sharedBlksRead");

	private RoomLockComparisonMeasurementContract() {}

	static List<String> requiredRawScenarios() {
		List<String> scenarios = new ArrayList<>();
		scenarios.add("T1");
		SINGLE_T2_SCENARIOS.forEach(scenario -> scenarios.add(scenario));
		for (String scenario : REPEATED_T2_SCENARIOS) {
			for (int repetition = 0; repetition < T2_ROUND_REPETITIONS; repetition++) {
				scenarios.add(scenario);
			}
		}
		T3_SCENARIOS.forEach(scenario -> scenarios.add(scenario));
		return List.copyOf(scenarios);
	}

	static String scenarioSetDigest() {
		String canonicalScenarioSet = requiredRawScenarios().stream().sorted().reduce(
			new StringBuilder(),
			(builder, scenario) -> builder.append(scenario).append('\n'),
			StringBuilder::append).toString();
		try {
			return HexFormat.of().withUpperCase().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(canonicalScenarioSet.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new AssertionError("SHA-256 알고리즘을 사용할 수 없습니다.", exception);
		}
	}

	static void assertScenarioSet(Collection<String> actualScenarios) {
		List<String> actual = actualScenarios.stream().filter(Objects::nonNull).sorted().toList();
		List<String> expected = requiredRawScenarios().stream().sorted().toList();
		if (!expected.equals(actual)) {
			throw new AssertionError("공통 ROOM-LOCK scenario set이 다릅니다. expected="
				+ expected + ", actual=" + actual);
		}
	}

	static boolean isRepeatedT2(String scenario) {
		return REPEATED_T2_SCENARIOS.contains(scenario);
	}
}
