import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";
import test from "node:test";

import { evaluateIntegrationGate } from "./match01-integration-gate.mjs";

function sha256(contents) {
  return createHash("sha256").update(contents).digest("hex");
}

function createRepository() {
  const repository = mkdtempSync(path.join(tmpdir(), "match01-integration-gate-"));
  execFileSync("git", ["init", "--quiet", repository]);
  execFileSync("git", ["-C", repository, "config", "user.email", "gate@example.com"]);
  execFileSync("git", ["-C", repository, "config", "user.name", "MATCH gate"]);
  writeFileSync(path.join(repository, "source.txt"), "source-1\n");
  execFileSync("git", ["-C", repository, "add", "source.txt"]);
  execFileSync("git", ["-C", repository, "commit", "--quiet", "-m", "source 1"]);
  writeFileSync(path.join(repository, "source.txt"), "source-2\n");
  execFileSync("git", ["-C", repository, "add", "source.txt"]);
  execFileSync("git", ["-C", repository, "commit", "--quiet", "-m", "source 2"]);
  return repository;
}

function currentCommit(repository) {
  return execFileSync("git", ["-C", repository, "rev-parse", "HEAD"], { encoding: "utf8" }).trim();
}

function previousCommit(repository) {
  return execFileSync("git", ["-C", repository, "rev-parse", "HEAD^"], { encoding: "utf8" }).trim();
}

function writeArtifact(repository, relativePath, artifact) {
  const artifactPath = path.join(repository, relativePath);
  mkdirSync(path.dirname(artifactPath), { recursive: true });
  const contents = `${JSON.stringify(artifact, null, 2)}\n`;
  writeFileSync(artifactPath, contents);
  return { relativePath, contents };
}

function commitArtifacts(repository) {
  execFileSync("git", ["-C", repository, "add", "."]);
  execFileSync("git", ["-C", repository, "commit", "--quiet", "-m", "gate fixtures"]);
}

function evidence(id, writtenArtifact) {
  return {
    id,
    path: writtenArtifact.relativePath,
    gitCanonicalBlobSha256: sha256(writtenArtifact.contents),
    artifactSha256: sha256(writtenArtifact.contents),
  };
}

function completeGate(repository) {
  const gateSha = currentCommit(repository);
  const separateEvidenceSha = previousCommit(repository);
  const candidate = writeArtifact(repository, "docs/measurements/results/match-01/candidate-claim/result.json", {
    measuredGitCommitSha: gateSha,
    decision: { outcome: "BASELINE_ACCEPTED" },
  });
  const t1 = writeArtifact(repository, "docs/measurements/results/match-01/integration/t1.json", {
    measuredGitCommitSha: gateSha,
    outcome: "PASSED",
  });
  const t5 = writeArtifact(repository, "docs/measurements/results/match-01/integration/t5.json", {
    measuredGitCommitSha: gateSha,
    outcome: "PASSED",
  });
  const t6 = writeArtifact(repository, "docs/measurements/results/match-01/integration/t6.json", {
    measuredGitCommitSha: gateSha,
    outcome: "PASSED",
  });
  const t7 = writeArtifact(repository, "docs/measurements/results/match-01/integration/t7.json", {
    measuredGitCommitSha: gateSha,
    outcome: "PASSED",
  });
  const response = writeArtifact(repository, "docs/measurements/results/match-01/response-completion/result.json", {
    measuredGitCommitSha: separateEvidenceSha,
    decision: { outcome: "RESPONSE_BASELINE_ACCEPTED" },
  });
  const t12 = writeArtifact(repository, "docs/measurements/results/match-01/integration/t12.json", {
    measuredGitCommitSha: separateEvidenceSha,
    outcome: "PASSED",
  });
  commitArtifacts(repository);

  return {
    measuredGitCommitSha: gateSha,
    candidateClaim: evidence("MATCH-01-CANDIDATE-CLAIM", candidate),
    integrationEvidence: [
      evidence("MATCH-01-T1", t1),
      evidence("MATCH-01-T5", t5),
      evidence("MATCH-01-T6", t6),
      evidence("MATCH-01-T7", t7),
    ],
    responseCompletionConsumer: evidence("MATCH-01-RESPONSE-COMPLETION", response),
    currentStateConsumer: evidence("MATCH-01-T12", t12),
  };
}

test("정상 동일 SHA candidate 입력과 분리된 response·T12 consumer만 MATCH-01 gate를 통과시킨다", () => {
  const repository = createRepository();
  const gate = completeGate(repository);

  const result = evaluateIntegrationGate(repository, gate);

  assert.deepEqual(result, { outcome: "ACCEPTED" });
  assert.equal(gate.integrationEvidence.some((entry) => entry.id === "MATCH-01-T12"), false);
  assert.equal(gate.integrationEvidence.some((entry) => entry.id === "MATCH-01-RESPONSE-COMPLETION"), false);
});

test("측정 이후 커밋되거나 미커밋된 MATCH 검증 대상 코드 변경은 INVALID다", () => {
  const repository = createRepository();
  const gate = completeGate(repository);
  const changedPath = path.join(
    repository,
    "src/main/java/cloud/bamsongi/albammate/matching/MatchRequest.java",
  );
  mkdirSync(path.dirname(changedPath), { recursive: true });
  writeFileSync(changedPath, "changed\n");

  const uncommittedResult = evaluateIntegrationGate(repository, gate);

  assert.equal(uncommittedResult.outcome, "INVALID");
  assert.match(uncommittedResult.reason, /검증 대상 코드/u);

  execFileSync("git", ["-C", repository, "add", "."]);
  execFileSync("git", ["-C", repository, "commit", "--quiet", "-m", "change match source"]);

  const committedResult = evaluateIntegrationGate(repository, gate);

  assert.equal(committedResult.outcome, "INVALID");
  assert.match(committedResult.reason, /검증 대상 코드/u);
});

test("측정 이후 문서 전용 변경은 evidence provenance를 무효화하지 않는다", () => {
  const repository = createRepository();
  const gate = completeGate(repository);
  writeFileSync(path.join(repository, "docs/after-measurement.md"), "docs\n");
  execFileSync("git", ["-C", repository, "add", "."]);
  execFileSync("git", ["-C", repository, "commit", "--quiet", "-m", "change docs"]);

  assert.deepEqual(evaluateIntegrationGate(repository, gate), { outcome: "ACCEPTED" });
});

test("필수 evidence 누락·중복 ID·상대 경로 이탈·commit/blob/digest 불일치는 INVALID다", () => {
  const repository = createRepository();
  const gate = completeGate(repository);
  const cases = [
    (value) => value.integrationEvidence.pop(),
    (value) => value.integrationEvidence.push({ ...value.integrationEvidence[0] }),
    (value) => { value.integrationEvidence[0].path = "../outside.json"; },
    (value) => { value.integrationEvidence[0].gitCanonicalBlobSha256 = "0".repeat(64); },
    (value) => { value.integrationEvidence[0].artifactSha256 = "0".repeat(64); },
    (value) => { value.integrationEvidence[0].path = value.candidateClaim.path; },
  ];

  for (const mutate of cases) {
    const invalidGate = structuredClone(gate);
    mutate(invalidGate);
    assert.equal(evaluateIntegrationGate(repository, invalidGate).outcome, "INVALID");
  }
});

test("artifact의 실행 SHA 불일치와 INVALID·FAILED 결과는 통과로 바꾸지 않는다", () => {
  const repository = createRepository();
  const gate = completeGate(repository);

  const wrongSha = structuredClone(gate);
  wrongSha.measuredGitCommitSha = "b".repeat(40);
  assert.equal(evaluateIntegrationGate(repository, wrongSha).outcome, "INVALID");

  const invalid = structuredClone(gate);
  invalid.currentStateConsumer.path = "docs/measurements/results/match-01/integration/missing.json";
  assert.equal(evaluateIntegrationGate(repository, invalid).outcome, "INVALID");

  const failedRepository = createRepository();
  const failedGate = completeGate(failedRepository);
  const failedPath = path.join(failedRepository, failedGate.integrationEvidence[2].path);
  writeFileSync(failedPath, `${JSON.stringify({ measuredGitCommitSha: failedGate.measuredGitCommitSha, outcome: "FAILED" })}\n`);
  execFileSync("git", ["-C", failedRepository, "add", "."]);
  execFileSync("git", ["-C", failedRepository, "commit", "--quiet", "-m", "failed fixture"]);
  const failedContents = `${JSON.stringify({ measuredGitCommitSha: failedGate.measuredGitCommitSha, outcome: "FAILED" })}\n`;
  failedGate.integrationEvidence[2].gitCanonicalBlobSha256 = sha256(failedContents);
  failedGate.integrationEvidence[2].artifactSha256 = sha256(failedContents);
  assert.equal(evaluateIntegrationGate(failedRepository, failedGate).outcome, "FAILED");
});

test("response completion 결과는 candidate와 다른 SHA의 별도 consumer로 허용하되 candidate 입력에 섞이면 INVALID다", () => {
  const repository = createRepository();
  const gate = completeGate(repository);

  const wrongResponseSha = structuredClone(gate);
  wrongResponseSha.responseCompletionConsumer.id = "MATCH-01-T12";
  assert.equal(evaluateIntegrationGate(repository, wrongResponseSha).outcome, "INVALID");

  const mixedResponse = structuredClone(gate);
  mixedResponse.integrationEvidence.push(mixedResponse.responseCompletionConsumer);
  assert.equal(evaluateIntegrationGate(repository, mixedResponse).outcome, "INVALID");
});

test("1MB를 초과하는 candidate artifact도 Git blob과 digest를 검증한다", () => {
  const repository = createRepository();
  const gate = completeGate(repository);
  const candidatePath = path.join(repository, gate.candidateClaim.path);
  const largeCandidate = {
    measuredGitCommitSha: gate.measuredGitCommitSha,
    decision: { outcome: "BASELINE_ACCEPTED" },
    report: "x".repeat(1_100_000),
  };
  const contents = `${JSON.stringify(largeCandidate)}\n`;
  writeFileSync(candidatePath, contents);
  execFileSync("git", ["-C", repository, "add", "."]);
  execFileSync("git", ["-C", repository, "commit", "--quiet", "-m", "large candidate fixture"]);
  gate.candidateClaim.gitCanonicalBlobSha256 = sha256(contents);
  gate.candidateClaim.artifactSha256 = sha256(contents);

  assert.deepEqual(evaluateIntegrationGate(repository, gate), { outcome: "ACCEPTED" });
});
