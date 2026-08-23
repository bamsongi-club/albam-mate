import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import assert from "node:assert/strict";
import test from "node:test";

import {
	RESPONSE_COMPARISON_ACCEPTED,
	SCENARIOS,
	compareAcceptedArtifacts,
	compareResponseFiles,
	reportPathFromArguments,
	renderComparisonMarkdown,
} from "./match01-response-completion-comparison.mjs";

const PROFILE = {
  target: "aws",
  stackId: "perf-jiwon",
  region: "ap-northeast-2",
  releaseSha: "b".repeat(40),
  appInstanceType: "t4g.small",
  postgresInstanceType: "t4g.micro",
  redisInstanceType: "t4g.micro",
  backendImage: "backend@sha256:" + "1".repeat(64),
  webImage: "web@sha256:" + "2".repeat(64),
  postgresImage: "postgres@sha256:" + "3".repeat(64),
  redisImage: "redis@sha256:" + "4".repeat(64),
  applicationConfigSha256: "5".repeat(64),
  responseTopology: "single-jvm-direct-jdbc",
};

function round(scenarioIndex, roundNumber, baseLatency) {
  return {
    round: roundNumber,
      rawDataSha256: String(scenarioIndex + roundNumber).repeat(64),
    metrics: {
      latencyNanos: {
        p50: baseLatency,
        p95: baseLatency + 95,
        p99: baseLatency + 99,
      },
      throughputPerSecond: 100 + roundNumber,
      retry: { total: 0, max: 0 },
      failure: { count: 0, rate: 0 },
    },
    lockWait: {
      sampledWaitNanos: 0,
      sampleTotalNanos: 0,
      sampleMaxNanos: 0,
    },
    finalStateAssertion: {
      passed: true,
      duplicatePartyCount: 0,
      partialSuccessCount: 0,
    },
  };
}

function artifact(profile = PROFILE, fixtureInputSha256 = "a".repeat(64), baseLatency = 1_000) {
  return {
    measuredGitCommitSha: "b".repeat(40),
    environment: { profile },
    fixture: {
      seed: "MATCH-01-RESPONSE-COMPLETION-V2",
      fixtureInputSha256,
    },
    scenarios: SCENARIOS.map((scenario, scenarioIndex) => ({
      scenario,
      warmUp: { completed: true },
      measuredRounds: [1, 2, 3].map((roundNumber) => round(
        scenarioIndex,
        roundNumber,
        baseLatency + scenarioIndex * 100 + roundNumber,
      )),
    })),
  };
}

test("동일 fixture·AWS profile의 네 시나리오 전후 비교를 채택한다", () => {
  const result = compareAcceptedArtifacts(artifact(), artifact(PROFILE, "a".repeat(64), 2_000));

  assert.equal(result.outcome, RESPONSE_COMPARISON_ACCEPTED);
  assert.equal(result.scenarios.length, 4);
  assert.equal(result.scenarios.every((scenario) => scenario.measuredRounds.length === 3), true);
  assert.equal(result.scenarios[0].measuredRounds[0].delta.p95Nanos, 1_000);
  assert.match(renderComparisonMarkdown(result), /raw digest pair/u);
});

test("환경 profile·fixtureInputSha256가 다르면 비교를 INVALID로 중단한다", () => {
  const profileMismatch = compareAcceptedArtifacts(
    artifact(),
    artifact({ ...PROFILE, postgresInstanceType: "t4g.small" }),
  );
  assert.equal(profileMismatch.outcome, "INVALID");

  const fixtureMismatch = compareAcceptedArtifacts(artifact(), artifact(PROFILE, "c".repeat(64)));
  assert.equal(fixtureMismatch.outcome, "INVALID");

  const seedMismatch = artifact();
  seedMismatch.fixture.seed = "UNEXPECTED-SEED";
  assert.equal(compareAcceptedArtifacts(artifact(), seedMismatch).outcome, "INVALID");

  const releaseShaMismatch = artifact({ ...PROFILE, releaseSha: "unknown" });
  assert.equal(compareAcceptedArtifacts(artifact(), releaseShaMismatch).outcome, "INVALID");

  const imageDigestMismatch = artifact({ ...PROFILE, backendImage: "backend:latest" });
  assert.equal(compareAcceptedArtifacts(artifact(), imageDigestMismatch).outcome, "INVALID");
});

test("warm-up 또는 measured round가 누락되면 비교를 INVALID로 중단한다", () => {
  const after = artifact();
  after.scenarios[0].measuredRounds.pop();

  const result = compareAcceptedArtifacts(artifact(), after);

  assert.equal(result.outcome, "INVALID");
});

test("파일 비교 경로는 sidecar INVALID와 최종 상태 FAILED를 전파한다", () => {
  const sourceDirectory = path.resolve("docs/measurements/results/match-01/response-completion");
  const sourceArtifactPath = path.join(sourceDirectory,
    "response-completion-c017d2f52f6548dc85ab86fed0f0d668397a3fe3.json");
  const sourceSidecarPath = path.join(sourceDirectory,
    "response-completion-c017d2f52f6548dc85ab86fed0f0d668397a3fe3-private-sidecar.json");
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "match-01-response-comparison-"));

  try {
    const writeArtifact = (name, mutate) => {
      const value = JSON.parse(fs.readFileSync(sourceArtifactPath, "utf8"));
      value.environment = { ...value.environment, profile: PROFILE };
      value.fixture.privateCanonicalManifestFile = "private-sidecar.json";
      mutate?.(value);
      const artifactPath = path.join(temporaryDirectory, name + ".json");
      fs.writeFileSync(artifactPath, JSON.stringify(value));
      fs.copyFileSync(sourceSidecarPath, path.join(temporaryDirectory, "private-sidecar.json"));
      return artifactPath;
    };

    const beforePath = writeArtifact("before", () => {});
    const afterPath = writeArtifact("after", () => {});
    assert.equal(compareResponseFiles(beforePath, afterPath).outcome, RESPONSE_COMPARISON_ACCEPTED);

    const invalidPath = writeArtifact("invalid", (value) => {
      value.scenarios[0].measuredRounds[0].rawDataSha256 = "0".repeat(64);
    });
    assert.equal(compareResponseFiles(beforePath, invalidPath).outcome, "INVALID");

    const failedPath = writeArtifact("failed", (value) => {
      value.scenarios[0].measuredRounds[0].finalStateAssertion.duplicatePartyCount = 1;
    });
    assert.equal(compareResponseFiles(beforePath, failedPath).outcome, "FAILED");
  } finally {
    fs.rmSync(temporaryDirectory, { recursive: true, force: true });
  }
});

test("write-report 플래그는 report 경로를 요구한다", () => {
  assert.equal(reportPathFromArguments(["--before", "before.json", "--after", "after.json"]), null);
  assert.equal(reportPathFromArguments(["--write-report", "report.md"]), "report.md");
  assert.throws(() => reportPathFromArguments(["--write-report"]), /report 파일 경로가 필요합니다/u);
  assert.throws(() => reportPathFromArguments(["--write-report", "--check"]), /report 파일 경로가 필요합니다/u);
});
