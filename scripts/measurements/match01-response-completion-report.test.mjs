import assert from "node:assert/strict";
import test from "node:test";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";

import {
  assertResponseArtifact,
  evaluateResponseArtifact,
  sha256,
} from "./match01-response-completion-report.mjs";

const sha = "a".repeat(40);

function completeArtifact() {
  return {
    measuredGitCommitSha: sha,
    fixture: {
      seed: "MATCH-01-RESPONSE-COMPLETION-V2",
      fixtureInput: "fixture",
      fixtureInputSha256: sha256("fixture"),
      privateCanonicalManifestFile: "response-completion-private-sidecar.json",
      materializedManifestSha256: sha256(JSON.stringify(completeSidecar())),
    },
    scenarios: ["ACCEPT_NON_TERMINAL", "ACCEPT_FINAL", "REQUEUE", "CANCEL"].map((scenario) => {
      const rawSamples = samplesFor(scenario);
      const rawData = JSON.stringify(rawSamples);
      return {
        scenario,
        warmUp: { completed: true },
        measuredRounds: [1, 2, 3].map((round) => ({
          round,
          rawSamples,
          rawDataSha256: sha256(rawData),
          dbStatistics: completeDatabaseStatistics(),
          lockWait: completeLockWait(rawSamples),
          observationWindow: {
            startedAt: "2026-08-21T00:00:00Z",
            endedAt: "2026-08-21T00:00:01Z",
          },
          metrics: completeMetrics(rawSamples),
          finalStateAssertion: completeFinalStateAssertion(scenario),
        })),
      };
    }),
  };
}

function completeDatabaseStatistics() {
  return {
    observed: true,
    statements: [{
      queryId: "123",
      calls: 1_000,
      totalExecTimeMillis: 10,
      rows: 1_000,
      sharedBlksHit: 2_000,
      sharedBlksRead: 0,
    }],
    statementCount: 1,
    totalCalls: 1_000,
    totalExecTimeMillis: 10,
    totalRows: 1_000,
    sharedBlksHit: 2_000,
    sharedBlksRead: 0,
  };
}

function completeLockWait(rawSamples) {
  return {
    observed: true,
    pollCount: 1,
    waitingSessionSampleCount: 0,
    sampledWaitNanos: 0,
    sampleTotalNanos: rawSamples.reduce((total, sample) => total + sample.lockWaitNanos, 0),
    sampleMaxNanos: Math.max(...rawSamples.map((sample) => sample.lockWaitNanos)),
  };
}

function completeMetrics(rawSamples) {
  return {
    sampleCount: rawSamples.length,
    observationDurationNanos: 1_000_000_000,
    latencyNanos: { p50: 500, p95: 950, p99: 990 },
    throughputPerSecond: 1_000,
    retry: { total: 0, max: 0 },
    failure: { count: 0, rate: 0 },
  };
}

function completeSidecar() {
  return ["ACCEPT_NON_TERMINAL", "ACCEPT_FINAL", "REQUEUE", "CANCEL"].flatMap((scenario) =>
    [1, 2, 3].flatMap((round) => sidecarRowsFor(scenario, round)));
}

function sidecarRowsFor(scenario, round) {
  const proposalCount = scenario === "ACCEPT_FINAL" ? 500 : 1_000;
  return Array.from({ length: proposalCount }, (_, proposalIndex) => [1, 2].map((memberOrdinal) => {
    const target = scenario === "ACCEPT_FINAL" || memberOrdinal === 1;
    const expected = expectedFacts(scenario, target);
    return {
      scenario,
      warmUp: false,
      round,
      proposalOrdinal: proposalIndex + 1,
      memberOrdinal,
      proposalId: proposalIndex + 1,
      memberProposalId: proposalIndex + 1,
      memberRequestId: proposalIndex * 2 + memberOrdinal,
      requestId: proposalIndex * 2 + memberOrdinal,
      expected,
      observed: { ...expected },
    };
  })).flat();
}

function expectedFacts(scenario, target) {
  const queueFact = scenario === "REQUEUE" && target ? "CHANGED"
    : ((scenario === "REQUEUE" || scenario === "CANCEL") && !target ? "UNCHANGED" : "NOT_APPLICABLE");
  return {
    proposalStatus: scenario === "ACCEPT_NON_TERMINAL" ? "OPEN"
      : (scenario === "ACCEPT_FINAL" ? "CONFIRMED" : (scenario === "REQUEUE" ? "DECLINED" : "CANCELED")),
    memberResponseStatus: target ? (scenario === "REQUEUE" ? "REQUEUED" : (scenario === "CANCEL" ? "CANCELED" : "ACCEPTED")) : "PENDING",
    requestStatus: scenario === "ACCEPT_NON_TERMINAL" ? "PROPOSED"
      : (scenario === "ACCEPT_FINAL" ? "MATCHED" : (scenario === "REQUEUE" ? "WAITING" : (target ? "CANCELED" : "WAITING"))),
    queueTimestamp: queueFact,
  };
}

function samplesFor(scenario) {
  const action = scenario === "REQUEUE" || scenario === "CANCEL" ? scenario : "ACCEPT";
  return Array.from({ length: 1_000 }, (_, index) => ({
    operationTime: "2026-08-21T00:00:00Z",
    completedAt: "2026-08-21T00:00:01Z",
    respondBy: "2026-08-21T00:00:30Z",
    action,
    result: scenario === "ACCEPT_FINAL" ? (index < 500 ? "NON_TERMINAL" : "TERMINAL")
      : (scenario === "ACCEPT_NON_TERMINAL" ? "NON_TERMINAL" : "TERMINAL"),
    retryCount: 0,
    lockWaitNanos: 0,
    httpStatus: 200,
    errorCode: null,
    latencyNanos: index + 1,
    finalStateObservation: scenario === "ACCEPT_NON_TERMINAL" ? "PROPOSED" : "PREPARING",
    finalStatePassed: true,
  }));
}

function completeFinalStateAssertion(scenario) {
  const proposalCount = scenario === "ACCEPT_FINAL" ? 500 : 1_000;
  const requestCount = scenario === "ACCEPT_FINAL" ? 1_000 : 2_000;
  return {
    passed: true,
    proposalCount,
    memberResponseCount: 1_000,
    requestStatusCount: requestCount,
    partyCount: scenario === "ACCEPT_FINAL" ? 500 : 0,
    partyParticipantCount: scenario === "ACCEPT_FINAL" ? 1_000 : 0,
    currentStateCount: 1_000,
    proposedCurrentStateCount: scenario === "ACCEPT_FINAL" ? 500 : 1_000,
    terminalCurrentStateCount: scenario === "ACCEPT_FINAL" ? 500 : 0,
    otherCurrentStateCount: 0,
    matchedExpectedCurrentStateCount: 1_000,
    nonterminalTransitionCount: scenario === "ACCEPT_FINAL" ? 500 : (scenario === "ACCEPT_NON_TERMINAL" ? 1_000 : 0),
    terminalTransitionCount: scenario === "ACCEPT_FINAL" ? 500 : (scenario === "ACCEPT_NON_TERMINAL" ? 0 : 1_000),
    completePartyGroupCount: scenario === "ACCEPT_FINAL" ? 500 : 0,
    duplicatePartyCount: 0,
    partialSuccessCount: 0,
    proposalFactMatchCount: proposalCount,
    proposalFactMismatchCount: 0,
    memberFactMatchCount: requestCount,
    memberFactMismatchCount: 0,
    requestFactMatchCount: requestCount,
    requestFactMismatchCount: 0,
    queueTimestampMatchCount: requestCount,
    queueTimestampMismatchCount: 0,
  };
}

test("T5 fixture 또는 관측 누락은 INVALID이고 완결 뒤 정합성 위반은 FAILED로 판정한다", () => {
  const invalid = completeArtifact();
  invalid.scenarios[0].measuredRounds[0].dbStatistics = null;
  assert.equal(evaluateResponseArtifact(invalid).outcome, "INVALID");

  const failed = completeArtifact();
  failed.scenarios[1].measuredRounds[2].finalStateAssertion.duplicatePartyCount = 1;
  assert.equal(evaluateResponseArtifact(failed).outcome, "FAILED");

  assert.equal(evaluateResponseArtifact(completeArtifact()).outcome, "RESPONSE_BASELINE_ACCEPTED");

  const missingAssertion = completeArtifact();
  missingAssertion.scenarios[0].measuredRounds[0].finalStateAssertion = null;
  assert.equal(evaluateResponseArtifact(missingAssertion).outcome, "INVALID");

  const missingFact = completeArtifact();
  delete missingFact.scenarios[0].measuredRounds[0].finalStateAssertion.partyParticipantCount;
  assert.equal(evaluateResponseArtifact(missingFact).outcome, "INVALID");

  const missingDistribution = completeArtifact();
  delete missingDistribution.scenarios[0].measuredRounds[0].finalStateAssertion.proposedCurrentStateCount;
  assert.equal(evaluateResponseArtifact(missingDistribution).outcome, "INVALID");

  const invalidFinalTransition = completeArtifact();
  invalidFinalTransition.scenarios[1].measuredRounds[0].finalStateAssertion.nonterminalTransitionCount = 499;
  assert.equal(evaluateResponseArtifact(invalidFinalTransition).outcome, "FAILED");

  const missingRawObservation = completeArtifact();
  delete missingRawObservation.scenarios[0].measuredRounds[0].rawSamples[0].operationTime;
  assert.equal(evaluateResponseArtifact(missingRawObservation).outcome, "INVALID");

  const incompleteFactCoverage = completeArtifact();
  incompleteFactCoverage.scenarios[0].measuredRounds[0].finalStateAssertion.memberFactMatchCount = 1_999;
  assert.equal(evaluateResponseArtifact(incompleteFactCoverage).outcome, "INVALID");
});

test("T2 T4 T5 measured round 번호, 모든 final-state 표본, 재계산 metric을 보존하지 않으면 INVALID다", () => {
  const duplicateRound = completeArtifact();
  duplicateRound.scenarios[0].measuredRounds[2].round = 1;
  assert.equal(evaluateResponseArtifact(duplicateRound).outcome, "INVALID");

  const missingRound = completeArtifact();
  missingRound.scenarios[0].measuredRounds[2].round = 4;
  assert.equal(evaluateResponseArtifact(missingRound).outcome, "INVALID");

  const failedSample = completeArtifact();
  failedSample.scenarios[0].measuredRounds[0].rawSamples[0].finalStatePassed = false;
  assert.equal(evaluateResponseArtifact(failedSample).outcome, "INVALID");

  const missingMetrics = completeArtifact();
  delete missingMetrics.scenarios[0].measuredRounds[0].metrics;
  assert.equal(evaluateResponseArtifact(missingMetrics).outcome, "INVALID");

  const recalculationMismatch = completeArtifact();
  recalculationMismatch.scenarios[0].measuredRounds[0].metrics.latencyNanos.p95 = 949;
  assert.equal(evaluateResponseArtifact(recalculationMismatch).outcome, "INVALID");

  const missingLockWaitObservation = completeArtifact();
  missingLockWaitObservation.scenarios[0].measuredRounds[0].lockWait.pollCount = 0;
  assert.equal(evaluateResponseArtifact(missingLockWaitObservation).outcome, "INVALID");

  const dbStatisticsMismatch = completeArtifact();
  dbStatisticsMismatch.scenarios[0].measuredRounds[0].dbStatistics.totalCalls = 999;
  assert.equal(evaluateResponseArtifact(dbStatisticsMismatch).outcome, "INVALID");

  const retryMismatch = completeArtifact();
  retryMismatch.scenarios[0].measuredRounds[0].metrics.retry.max = 1;
  assert.equal(evaluateResponseArtifact(retryMismatch).outcome, "INVALID");
});

test("T7 provenance와 raw data digest는 실제 bytes와 일치하고 candidate 및 gate SHA를 요구하지 않는다", () => {
  const artifact = completeArtifact();
  assert.equal(assertResponseArtifact(artifact, JSON.stringify(completeSidecar())).outcome, "RESPONSE_BASELINE_ACCEPTED");
  assert.equal("candidateBaselineSha" in artifact, false);
  assert.equal("gateManifestSha" in artifact, false);

  artifact.scenarios[0].measuredRounds[0].rawDataSha256 = "0".repeat(64);
  assert.equal(assertResponseArtifact(artifact, JSON.stringify(completeSidecar())).outcome, "INVALID");

  const fixtureTampered = completeArtifact();
  fixtureTampered.fixture.fixtureInput = "changed fixture";
  assert.equal(assertResponseArtifact(fixtureTampered, JSON.stringify(completeSidecar())).outcome, "INVALID");

  const manifestTampered = completeArtifact();
  assert.equal(assertResponseArtifact(manifestTampered, "changed manifest").outcome, "INVALID");
});

test("G2 invalid round artifact는 보존되고 reporter는 INVALID를 출력한다", () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "issue776-invalid-"));
  const artifact = completeArtifact();
  artifact.scenarios[0].measuredRounds[0].invalidReason = "technicalError: simulated";
  const artifactPath = path.join(directory, "artifact.json");
  const sidecarPath = path.join(directory, artifact.fixture.privateCanonicalManifestFile);
  fs.writeFileSync(artifactPath, JSON.stringify(artifact));
  fs.writeFileSync(sidecarPath, JSON.stringify(completeSidecar()));

  assert.equal(fs.existsSync(artifactPath), true);
  assert.equal(assertResponseArtifact(artifact, fs.readFileSync(sidecarPath, "utf8")).outcome, "INVALID");
});

test("G3 sidecar duplicate, missing, observed mismatch, scalar disagreement는 INVALID다", () => {
  const cases = [
    (sidecar) => sidecar.push({ ...sidecar[0] }),
    (sidecar) => sidecar.pop(),
    (sidecar) => { sidecar[0].proposalOrdinal = 1_001; },
    (sidecar) => { sidecar[0].observed.requestStatus = "CANCELED"; },
    (_sidecar, artifact) => { artifact.scenarios[0].measuredRounds[0].finalStateAssertion.requestFactMatchCount = 1_999; },
  ];

  for (const mutate of cases) {
    const artifact = completeArtifact();
    const sidecar = completeSidecar();
    mutate(sidecar, artifact);
    artifact.fixture.materializedManifestSha256 = sha256(JSON.stringify(sidecar));
    assert.equal(assertResponseArtifact(artifact, JSON.stringify(sidecar)).outcome, "INVALID");
  }
});
