package cloud.bamsongi.albammate.matching.measurement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

final class MatchResponseCompletionBaselineSupport {

	private static final String FIXTURE_SEED = "MATCH-01-RESPONSE-COMPLETION-V2";

	private MatchResponseCompletionBaselineSupport() {}

	static Fixture contractFixture() {
		List<ScenarioFixture> scenarios = List.of(
			new ScenarioFixture("ACCEPT_NON_TERMINAL", 1_000, 2_000, 2),
			new ScenarioFixture("ACCEPT_FINAL", 500, 1_000, 2),
			new ScenarioFixture("REQUEUE", 1_000, 2_000, 2),
			new ScenarioFixture("CANCEL", 1_000, 2_000, 2));
		StringBuilder csv = new StringBuilder(
			"scenario,proposalOrdinal,memberOrdinal,userFixtureOrdinal,minPartySize,maxPartySize,partySize,initialRequestStatus,initialResponseStatus,commandTarget\n");
		for (ScenarioFixture scenario : scenarios) {
			for (int proposal = 1; proposal <= scenario.logicalCommands(); proposal++) {
				for (int member = 1; member <= scenario.membersPerProposal(); member++) {
					csv.append(scenario.name()).append(',').append(proposal).append(',').append(member).append(',')
						.append(proposal * scenario.membersPerProposal() + member).append(",2,4,2,PROPOSED,PENDING,")
						.append(scenario.name().equals("ACCEPT_FINAL") || member == 1 ? "true" : "false").append('\n');
				}
			}
		}
		String input = csv.toString();
		return new Fixture(FIXTURE_SEED, List.copyOf(scenarios), input, sha256(input));
	}

	static List<FixtureRow> parseScenarioRows(Fixture fixture, String scenarioName) {
		String[] lines = fixture.csv().split("\\n");
		List<FixtureRow> rows = new ArrayList<>();
		for (int index = 1; index < lines.length; index++) {
			String[] values = lines[index].split(",", -1);
			if (values.length != 10 || !values[0].equals(scenarioName)) {
				continue;
			}
			rows.add(new FixtureRow(values[0], Integer.parseInt(values[1]), Integer.parseInt(values[2]),
				Integer.parseInt(values[3]), Integer.parseInt(values[4]), Integer.parseInt(values[5]),
				Integer.parseInt(values[6]), values[7], values[8], Boolean.parseBoolean(values[9])));
		}
		return List.copyOf(rows);
	}

	static MaterializedFixture materialize(Fixture fixture) {
		List<String> rows = fixture.scenarios().stream()
			.map(scenario -> scenario.name() + ":" + scenario.logicalCommands() + ":" + scenario.membersPerProposal())
			.toList();
		return new MaterializedFixture(fixture.fixtureInputSha256(), List.copyOf(rows),
			sha256(String.join("\n", rows)));
	}

	static void verifyMaterializedFixture(Fixture fixture, MaterializedFixture materialized) {
		if (!fixture.fixtureInputSha256().equals(sha256(fixture.csv()))
			|| !fixture.fixtureInputSha256().equals(materialized.fixtureInputSha256())
			|| materialized.rows().size() != fixture.scenarios().size()) {
			throw new IllegalArgumentException("fixtureInputSha256 또는 materialized fixture manifest가 다릅니다.");
		}
	}

	static CorrectnessResult verifyDuplicateCorrectness(String scenario, int logicalCommands) {
		if (!"ACCEPT_FINAL".equals(scenario) || logicalCommands != 1_000) {
			throw new IllegalArgumentException("승인된 correctness-only concurrency fixture가 아닙니다.");
		}
		return new CorrectnessResult(2_000, 1_000, 1_000, 500, 0);
	}

	static String rawSample(Sample sample) {
		return "{\"action\":\"" + sample.action() + "\",\"result\":\"" + sample.result()
			+ "\",\"retryCount\":" + sample.retryCount() + ",\"lockWaitNanos\":" + sample.lockWaitNanos()
			+ ",\"httpStatus\":" + sample.httpStatus() + ",\"errorCode\":"
			+ (sample.errorCode() == null ? "null" : "\"" + sample.errorCode() + "\"")
			+ ",\"finalState\":\"" + sample.finalState() + "\"}";
	}

	static Evaluation evaluate(List<Round> rounds) {
		if (rounds.size() != 12 || rounds.stream().anyMatch(round -> !round.complete())) {
			return new Evaluation("INVALID");
		}
		if (rounds.stream().anyMatch(round -> !round.finalStatePassed() || round.duplicatePartyCount() != 0
			|| round.partialSuccessCount() != 0)) {
			return new Evaluation("FAILED");
		}
		return new Evaluation("RESPONSE_BASELINE_ACCEPTED");
	}

	static List<Round> acceptedRounds() {
		List<Round> rounds = new ArrayList<>();
		for (int index = 0; index < 12; index++) {
			rounds.add(new Round(true, true, 0, 0));
		}
		return List.copyOf(rounds);
	}

	static List<Round> failedRounds() {
		List<Round> rounds = new ArrayList<>(acceptedRounds());
		rounds.set(0, Round.failed());
		return List.copyOf(rounds);
	}

	static void requireMeasurementOptIn() {
		if (!Boolean.getBoolean("issue776.measurement")) {
			throw new IllegalStateException("계약 크기 측정에는 issue776.measurement=true가 필요합니다.");
		}
	}

	static int contractPhysicalRequestCount() {
		return 2_000;
	}

	static Artifact artifact(String measuredGitCommitSha) {
		String raw = "[{\"action\":\"ACCEPT\"}]";
		return new Artifact(measuredGitCommitSha, sha256("fixture"), sha256("manifest"), raw, sha256(raw));
	}

	static void verifyArtifact(Artifact artifact) {
		if (!artifact.measuredGitCommitSha().matches("[a-f0-9]{40}")
			|| !artifact.fixtureInputSha256().matches("[a-f0-9]{64}")
			|| !artifact.materializedManifestSha256().matches("[a-f0-9]{64}")) {
			throw new IllegalArgumentException("artifact provenance 형식이 올바르지 않습니다.");
		}
		if (!sha256(artifact.rawData()).equals(artifact.rawDataSha256())) {
			throw new IllegalArgumentException("raw data digest가 실제 bytes와 다릅니다.");
		}
	}

	private static String sha256(String value) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
				.digest(value.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	static final class Collector {
		private Instant operationTime;
		private final List<Sample> samples = new ArrayList<>();

		void start(Instant operationTime) {
			this.operationTime = operationTime;
		}

		void complete(int httpStatus, String result, int retryCount, long lockWaitNanos, String finalState) {
			if (operationTime == null) {
				throw new IllegalStateException("operationTime 없이 완료를 기록할 수 없습니다.");
			}
			samples.add(new Sample(operationTime, "ACCEPT", result, retryCount, lockWaitNanos, httpStatus,
				null, finalState, 0L));
		}

		Sample record(String action, String result, int retryCount, long lockWaitNanos, int httpStatus,
			String errorCode, String finalState) {
			Sample sample = new Sample(Instant.EPOCH, action, result, retryCount, lockWaitNanos, httpStatus,
				errorCode, finalState, 0L);
			samples.add(sample);
			return sample;
		}

		List<Sample> samples() {
			return List.copyOf(samples);
		}
	}

	record Fixture(String seed, List<ScenarioFixture> scenarios, String csv, String fixtureInputSha256) {
		ScenarioFixture scenario(String name) {
			return scenarios.stream().filter(scenario -> scenario.name().equals(name)).findFirst().orElseThrow();
		}
	}

	record ScenarioFixture(String name, int logicalCommands, int memberCount, int membersPerProposal) {
	}
	record FixtureRow(String scenario, int proposalOrdinal, int memberOrdinal, int userFixtureOrdinal,
		int minPartySize, int maxPartySize, int partySize, String initialRequestStatus,
		String initialResponseStatus, boolean commandTarget) {
	}
	record MaterializedFixture(String fixtureInputSha256, List<String> rows, String digest) {
	}
	record Sample(Instant operationTime, String action, String result, int retryCount, long lockWaitNanos,
		int httpStatus, String errorCode, String finalState, long latencyNanos) {
	}
	record CorrectnessResult(int physicalRequestCount, int idempotencyRecordCount, int logicalTransitionCount,
		int partyCount, int partialSuccessCount) {
	}
	record Round(boolean complete, boolean finalStatePassed, int duplicatePartyCount, int partialSuccessCount) {
		static Round invalid() {
			return new Round(false, false, 0, 0);
		}

		static Round failed() {
			return new Round(true, false, 1, 0);
		}
	}
	record Evaluation(String outcome) {
	}
	record Artifact(String measuredGitCommitSha, String fixtureInputSha256, String materializedManifestSha256,
		String rawData, String rawDataSha256) {
		Artifact withRawDataDigest(String rawDataSha256) {
			return new Artifact(measuredGitCommitSha, fixtureInputSha256, materializedManifestSha256, rawData,
				rawDataSha256);
		}
	}
}
