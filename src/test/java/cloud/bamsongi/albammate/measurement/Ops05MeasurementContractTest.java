package cloud.bamsongi.albammate.measurement;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class Ops05MeasurementContractTest {

	private static final Path REPOSITORY_ROOT = Path.of("").toAbsolutePath();
	private static final String VALIDATOR = "scripts/measurements/ops05-manifest-validator.mjs";
	private static final String RELEASE_SHA = repositoryHead();

	@Test
	void T1_격리_fixture의_세_기능_최종_결과가_모두_있을때만_통과한다() throws Exception {
		try (ArtifactBundle artifacts = artifactBundle("normal", 1)) {
			ValidationResult result = validate(manifest("normal", normalWorkflows(), artifactBlock(artifacts)),
				artifacts, RELEASE_SHA);
			ValidationResult inconsistentSummary = validate(
				manifest("normal",
					normalWorkflows().replace("\"notificationRecorded\": true", "\"notificationRecorded\": false"),
					artifactBlock(artifacts)),
				artifacts,
				RELEASE_SHA);
			try (ArtifactBundle zeroWorkArtifacts = artifactBundle("normal", 0)) {
				ValidationResult zeroWork = validate(
					manifest("normal", normalWorkflows().replace("\"attempt\": 1", "\"attempt\": 0")
						.replace("\"businessSuccess\": 1", "\"businessSuccess\": 0")
						.replace("\"processed\": 1", "\"processed\": 0"), artifactBlock(zeroWorkArtifacts)),
					zeroWorkArtifacts,
					RELEASE_SHA);

				assertThat(result.exitCode()).withFailMessage(result.output()).isZero();
				assertThat(result.output())
					.contains("\"verdict\":\"PASS\"")
					.contains("\"validatedWorkflowCount\":3");
				assertThat(inconsistentSummary.exitCode()).isZero();
				assertThat(inconsistentSummary.output()).contains("\"verdict\":\"FAIL\"");
				assertThat(zeroWork.exitCode()).isZero();
				assertThat(zeroWork.output()).contains("\"verdict\":\"FAIL\"");
			}
		}
	}

	@Test
	void T2_통제된_거절과_기술_실패를_구분하고_복구_뒤_불변식_위반이_없어야_한다() throws Exception {
		try (ArtifactBundle artifacts = artifactBundle("controlled-recovery", 3);
			ArtifactBundle unpartitionedArtifacts = artifactBundle("controlled-recovery", 4)) {
			ValidationResult result = validate(
				manifest("controlled-recovery", recoveryWorkflows(), artifactBlock(artifacts)), artifacts, RELEASE_SHA);
			ValidationResult unpartitioned = validate(
				manifest("controlled-recovery", recoveryWorkflows().replace("\"attempt\": 3", "\"attempt\": 4"),
					artifactBlock(unpartitionedArtifacts)),
				unpartitionedArtifacts,
				RELEASE_SHA);
			ValidationResult inconsistentNotificationRelay = validate(
				manifest("controlled-recovery",
					recoveryWorkflows().replace("\"technicalFailure\": 2", "\"technicalFailure\": 1"),
					artifactBlock(artifacts)),
				artifacts,
				RELEASE_SHA);

			assertThat(result.exitCode()).isZero();
			assertThat(result.output())
				.contains("\"verdict\":\"PASS\"")
				.contains("\"mode\":\"controlled-recovery\"");
			assertThat(unpartitioned.exitCode()).isZero();
			assertThat(unpartitioned.output()).contains("\"verdict\":\"FAIL\"");
			assertThat(inconsistentNotificationRelay.exitCode()).isZero();
			assertThat(inconsistentNotificationRelay.output()).contains("\"verdict\":\"FAIL\"");
		}
	}

	@Test
	void T3_근거가_누락되면_INVALID이고_유효한_실행의_불변식_위반은_FAIL이다() throws Exception {
		try (ArtifactBundle artifacts = artifactBundle("normal", 1)) {
			ValidationResult missingArtifact = validate(manifest("normal", normalWorkflows(), "{}"), artifacts,
				RELEASE_SHA);
			ValidationResult mismatchedRelease = validate(
				manifest("normal", normalWorkflows(), artifactBlock(artifacts)), artifacts, "f".repeat(40));
			ValidationResult unisolatedFixture = validate(
				manifest("normal", normalWorkflows(), artifactBlock(artifacts))
					.replace("\"hasActualUserData\": false", "\"hasActualUserData\": true"),
				artifacts, RELEASE_SHA);
			ValidationResult mismatchedScenarioSource = validate(
				manifest("normal", normalWorkflows(), artifactBlock(artifacts))
					.replace(sha256("load-tests/k6/jiho/notification-delivery-contract.js"), "0".repeat(64)),
				artifacts, RELEASE_SHA);
			ValidationResult traversalArtifact = validate(
				manifest("normal", normalWorkflows(), artifactBlock(artifacts)).replace("\"path\": \"http.json\"",
					"\"path\": \"../outside.json\""),
				artifacts, RELEASE_SHA);
			ValidationResult invariantViolation = validate(
				manifest("normal", invalidQueueWorkflow(), artifactBlock(artifacts)), artifacts, RELEASE_SHA);
			try (DirtyReleaseRoot dirtyRelease = dirtyReleaseRoot()) {
				String source = "load-tests/k6/jiho/notification-delivery-contract.js";
				String dirtyManifest = manifest("normal", normalWorkflows(), artifactBlock(artifacts))
					.replace(RELEASE_SHA, dirtyRelease.head())
					.replace(sha256(source), sha256(dirtyRelease.root(), source));
				ValidationResult dirtySource = validate(dirtyManifest, artifacts, dirtyRelease.head(),
					dirtyRelease.root());

				assertThat(missingArtifact.exitCode()).isZero();
				assertThat(missingArtifact.output()).contains("\"verdict\":\"INVALID\"");
				assertThat(mismatchedRelease.exitCode()).isZero();
				assertThat(mismatchedRelease.output()).contains("\"verdict\":\"INVALID\"");
				assertThat(unisolatedFixture.exitCode()).isZero();
				assertThat(unisolatedFixture.output()).contains("\"verdict\":\"INVALID\"");
				assertThat(mismatchedScenarioSource.exitCode()).isZero();
				assertThat(mismatchedScenarioSource.output()).contains("\"verdict\":\"INVALID\"");
				assertThat(traversalArtifact.exitCode()).isZero();
				assertThat(traversalArtifact.output()).contains("\"verdict\":\"INVALID\"");
				assertThat(invariantViolation.exitCode()).isZero();
				assertThat(invariantViolation.output()).contains("\"verdict\":\"FAIL\"");
				assertThat(dirtySource.exitCode()).isZero();
				assertThat(dirtySource.output()).contains("\"verdict\":\"INVALID\"");
			}
		}
	}

	private ValidationResult validate(String manifest, ArtifactBundle artifacts, String expectedRelease)
		throws IOException, InterruptedException {
		return validate(manifest, artifacts, expectedRelease, REPOSITORY_ROOT);
	}

	private ValidationResult validate(String manifest, ArtifactBundle artifacts, String expectedRelease,
		Path releaseRoot)
		throws IOException, InterruptedException {
		Path input = Files.createTempFile("ops05-manifest-contract-", ".json");
		try {
			Files.writeString(input, manifest, StandardCharsets.UTF_8);
			Process process = new ProcessBuilder(
				"node", VALIDATOR, "--manifest", input.toString(), "--release-root", releaseRoot.toString(),
				"--bundle-root", artifacts.root().toString(), "--expected-release-sha", expectedRelease)
				.directory(REPOSITORY_ROOT.toFile())
				.redirectErrorStream(true)
				.start();
			boolean completed = process.waitFor(10, TimeUnit.SECONDS);
			assertThat(completed).isTrue();
			return new ValidationResult(process.exitValue(),
				new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
		} finally {
			Files.deleteIfExists(input);
		}
	}

	private String manifest(String mode, String workflows, Object artifacts) throws IOException {
		return """
			{
			  "schemaVersion": 1,
			  "releaseSha": "%s",
			  "execution": {"startedAt": "2026-08-19T00:00:00Z", "finishedAt": "2026-08-19T00:10:00Z"},
			  "fixture": {"classification": "isolated", "fixtureHash": "%s", "hasActualUserData": false, "hasProductionRoomData": false},
			  "sources": [
			    {"path": "load-tests/k6/jiho/notification-delivery-contract.js", "sha256": "%s"},
			    {"path": "load-tests/k6/eungi/websocket-contract.js", "sha256": "%s"},
			    {"path": "load-tests/k6/jiwon/t1-cancel-promotion.js", "sha256": "%s"}
			  ],
			  "artifacts": %s,
			  "mode": "%s",
			  "workflows": %s
			}
			"""
			.formatted(
				RELEASE_SHA,
				"a".repeat(64),
				sha256("load-tests/k6/jiho/notification-delivery-contract.js"),
				sha256("load-tests/k6/eungi/websocket-contract.js"),
				sha256("load-tests/k6/jiwon/t1-cancel-promotion.js"),
				artifacts,
				mode,
				workflows);
	}

	private String artifactBlock(ArtifactBundle artifacts) throws IOException {
		return """
			{
			  "http": {"path": "http.json", "sha256": "%s"},
			  "database": {"path": "database.json", "sha256": "%s"},
			  "metrics": {"path": "metrics.json", "sha256": "%s"},
			  "logs": {"path": "logs.json", "sha256": "%s"},
			  "dashboard": {"path": "dashboard.json", "sha256": "%s"}
			}
			""".formatted(artifacts.sha256("http.json"), artifacts.sha256("database.json"),
			artifacts.sha256("metrics.json"),
			artifacts.sha256("logs.json"), artifacts.sha256("dashboard.json"));
	}

	private ArtifactBundle artifactBundle(String mode, int attempt) throws IOException {
		Path root = Files.createTempDirectory("ops05-artifacts-");
		String outcomes = "{\"attempt\": %d, \"businessSuccess\": 1, \"businessRejection\": %d, \"technicalFailure\": %d}"
			.formatted(attempt, mode.equals("normal") ? 0 : 1, mode.equals("normal") ? 0 : 1);
		write(root, "http.json",
			"{\"notification\":{\"technicalAccepted\":true},\"chat\":{\"technicalAccepted\":true},\"waitingQueue\":{\"technicalAccepted\":true}}");
		write(root, "database.json",
			"{\"notification\":{\"businessResult\":true,\"notificationRecorded\":true},\"chat\":{\"businessResult\":true,\"messageStored\":true},\"waitingQueue\":{\"businessResult\":true,\"registered\":true,\"canceled\":true,\"fifoPromoted\":true,\"invariantViolations\":0}}");
		String notificationOutcomes = mode.equals("normal")
			? "{\"attempt\":1,\"businessSuccess\":1,\"businessRejection\":0,\"technicalFailure\":0,\"retryScheduled\":0,\"failed\":0,\"processed\":1}"
			: "{\"attempt\":3,\"businessSuccess\":1,\"businessRejection\":0,\"technicalFailure\":2,\"retryScheduled\":1,\"failed\":1,\"processed\":1}";
		write(root, "metrics.json",
			"{\"notification\":%s,\"chat\":%s,\"waitingQueue\":%s}".formatted(notificationOutcomes, outcomes,
				outcomes));
		String followUpSucceeded = mode.equals("normal") ? "false" : "true";
		write(root, "logs.json",
			"{\"notification\":{\"inboxVisible\":true,\"followUpSucceeded\":%s},\"chat\":{\"delivered\":true,\"reconnectRecovered\":true,\"followUpSucceeded\":%s},\"waitingQueue\":{\"followUpSucceeded\":%s}}"
				.formatted(followUpSucceeded, followUpSucceeded, followUpSucceeded));
		write(root, "dashboard.json",
			"{\"notification\":{\"userVisibleResult\":true},\"chat\":{\"userVisibleResult\":true},\"waitingQueue\":{\"userVisibleResult\":true}}");
		return new ArtifactBundle(root);
	}

	private void write(Path root, String fileName, String contents) throws IOException {
		Files.writeString(root.resolve(fileName), contents, StandardCharsets.UTF_8);
	}

	private String normalWorkflows() {
		return """
			{
			  "notification": {"technicalAccepted": true, "businessResult": true, "userVisibleResult": true, "notificationRecorded": true, "inboxVisible": true, "retryScheduled": 0, "failed": 0, "processed": 1, "outcomes": {"attempt": 1, "businessSuccess": 1, "businessRejection": 0, "technicalFailure": 0}},
			  "chat": {"technicalAccepted": true, "businessResult": true, "userVisibleResult": true, "messageStored": true, "delivered": true, "reconnectRecovered": true, "outcomes": {"attempt": 1, "businessSuccess": 1, "businessRejection": 0, "technicalFailure": 0}},
			  "waitingQueue": {"technicalAccepted": true, "businessResult": true, "userVisibleResult": true, "registered": true, "canceled": true, "fifoPromoted": true, "invariantViolations": 0, "outcomes": {"attempt": 1, "businessSuccess": 1, "businessRejection": 0, "technicalFailure": 0}}
			}
			""";
	}

	private String recoveryWorkflows() {
		return """
			{
			  "notification": {"technicalAccepted": true, "businessResult": true, "userVisibleResult": true, "notificationRecorded": true, "inboxVisible": true, "retryScheduled": 1, "failed": 1, "processed": 1, "followUpSucceeded": true, "outcomes": {"attempt": 3, "businessSuccess": 1, "businessRejection": 0, "technicalFailure": 2}},
			  "chat": {"technicalAccepted": true, "businessResult": true, "userVisibleResult": true, "messageStored": true, "delivered": true, "reconnectRecovered": true, "followUpSucceeded": true, "outcomes": {"attempt": 3, "businessSuccess": 1, "businessRejection": 1, "technicalFailure": 1}},
			  "waitingQueue": {"technicalAccepted": true, "businessResult": true, "userVisibleResult": true, "registered": true, "canceled": true, "fifoPromoted": true, "invariantViolations": 0, "followUpSucceeded": true, "outcomes": {"attempt": 3, "businessSuccess": 1, "businessRejection": 1, "technicalFailure": 1}}
			}
			""";
	}

	private String invalidQueueWorkflow() {
		return normalWorkflows().replace("\"invariantViolations\": 0", "\"invariantViolations\": 1");
	}

	private String sha256(String relativePath) throws IOException {
		return sha256(REPOSITORY_ROOT, relativePath);
	}

	private String sha256(Path root, String relativePath) throws IOException {
		try {
			return java.util.HexFormat.of().formatHex(
				MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(relativePath))));
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException(exception);
		}
	}

	private static String repositoryHead() {
		try {
			Process process = new ProcessBuilder("git", "rev-parse", "HEAD")
				.directory(REPOSITORY_ROOT.toFile())
				.redirectErrorStream(true)
				.start();
			if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
				throw new IllegalStateException("fixed release HEAD is unavailable");
			}
			return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
		} catch (IOException | InterruptedException exception) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("fixed release HEAD is unavailable", exception);
		}
	}

	private DirtyReleaseRoot dirtyReleaseRoot() throws Exception {
		Path root = Files.createTempDirectory("ops05-dirty-release-");
		List<String> sources = List.of("load-tests/k6/jiho/notification-delivery-contract.js",
			"load-tests/k6/eungi/websocket-contract.js", "load-tests/k6/jiwon/t1-cancel-promotion.js");
		for (String source : sources) {
			Path target = root.resolve(source);
			Files.createDirectories(target.getParent());
			Files.copy(REPOSITORY_ROOT.resolve(source), target);
		}
		git(root, "init", "-q");
		git(root, "add", ".");
		git(root, "-c", "user.name=OPS05", "-c", "user.email=ops05@example.invalid", "commit", "-qm", "release");
		Files.writeString(root.resolve(sources.getFirst()), "\n", StandardOpenOption.APPEND);
		return new DirtyReleaseRoot(root, git(root, "rev-parse", "HEAD").trim());
	}

	private String git(Path root, String... arguments) throws Exception {
		String[] command = new String[arguments.length + 1];
		command[0] = "git";
		System.arraycopy(arguments, 0, command, 1, arguments.length);
		Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
		assertThat(process.waitFor(10, TimeUnit.SECONDS)).isTrue();
		String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		assertThat(process.exitValue()).withFailMessage(output).isZero();
		return output;
	}

	private record ValidationResult(int exitCode, String output) {
	}

	private record ArtifactBundle(Path root) implements AutoCloseable {

		private String sha256(String fileName) throws IOException {
			try {
				return java.util.HexFormat.of()
					.formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(root.resolve(fileName))));
			} catch (NoSuchAlgorithmException exception) {
				throw new IllegalStateException(exception);
			}
		}

		@Override
		public void close() throws IOException {
			for (String fileName : List.of("http.json", "database.json", "metrics.json", "logs.json",
				"dashboard.json")) {
				Files.deleteIfExists(root.resolve(fileName));
			}
			Files.deleteIfExists(root);
		}
	}

	private record DirtyReleaseRoot(Path root, String head) implements AutoCloseable {

		@Override
		public void close() throws IOException {
			try (var paths = Files.walk(root)) {
				paths.sorted(Comparator.reverseOrder()).forEach(path -> {
					try {
						Files.deleteIfExists(path);
					} catch (IOException exception) {
						// Windows Git handles may outlive the child process briefly; the temp directory is OS-managed.
					}
				});
			}
		}
	}
}
