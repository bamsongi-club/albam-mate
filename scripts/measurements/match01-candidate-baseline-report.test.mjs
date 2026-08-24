import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";

import {
  buildCandidateBaselineReport,
  calculateRawMeasurementSha256,
  evaluateCandidateBaseline,
  fixtureInputSha256,
  nearestRank,
} from "./match01-candidate-baseline-report.mjs";

function completeTopology() {
  return {
    matcherCount: 2,
    claimAttempts: 500,
    configurationSha: "a".repeat(64),
  };
}

function completeRound(index, fixture = completeFixture(), topology = completeTopology()) {
  return {
    round: index,
    measuredGitCommitSha: "a".repeat(40),
    fixtureEvidence: {
      generator: fixture.generator,
      fixtureInputSha256: fixture.fixtureInputSha256,
      materializedManifestSha256: fixture.materializedManifestSha256,
    },
    topology,
    matcherProcesses: [
      { pid: 101, exitCode: 0, completed: true },
      { pid: 202, exitCode: 0, completed: true },
    ],
    logicalClaims: Array.from({ length: 1_000 }, (_, claim) => ({
      matcherPid: claim < 500 ? 101 : 202,
      durationNanos: (claim + 1) * 1_000,
      retryCount: 0,
      retryRawDurationsNanos: [],
    })),
    throughputPerSecond: 100,
    pgStatStatements: {
      calls: 1_000,
      totalExecutionTimeMs: 100,
      rows: 1_000,
      sharedBlockHits: 2_000,
      sharedBlockReads: 10,
      candidateStatements: [
        {
          query: "select * from match_requests where status = 'WAITING' order by priority_since asc, id asc limit $1 for update skip locked",
          calls: 1_000,
        },
      ],
    },
    lockSamples: {
      intervalMs: 10,
      observationStartedAtUtc: "2026-01-01T00:00:00Z",
      observationFinishedAtUtc: "2026-01-01T00:00:01Z",
      snapshotCount: 3,
      lockWaitSnapshotCount: 0,
      samplingFailure: null,
    },
    queryPlan: "anchorSql=select * from match_requests where status = 'WAITING' order by priority_since asc, id asc limit 1 for update skip locked\nanchorPlan=[{}]\ncandidatePageSql=select * from match_requests where status = 'WAITING' and (priority_since > timestamptz '2026-01-01T00:00:01Z' or (priority_since = timestamptz '2026-01-01T00:00:01Z' and id > 1)) order by priority_since asc, id asc limit 100 for update skip locked\ncandidatePagePlan=[{}]",
    correctnessInput: {
      proposalCount: 500,
      memberCount: 1_000,
      claimedRequestCount: 1_000,
      waitingRequestCount: 0,
      duplicateClaimCount: 0,
      partialClaimCount: 0,
      tieOrderMatches: true,
      tiePairResults: Array.from({ length: 100 }, (_, pair) => ({
        firstFixtureOrdinal: (pair * 2) + 1,
        secondFixtureOrdinal: (pair * 2) + 2,
        sameProposal: true,
      })),
    },
    integrity: {
      proposalCount: 500,
      memberCount: 1_000,
      claimedRequestCount: 1_000,
      duplicateClaimCount: 0,
      partialClaimCount: 0,
      tieOrderMatches: true,
    },
  };
}

function completeFixture() {
  const rows = ["fixtureOrdinal,userFixtureOrdinal,queuedAt,prioritySince,minPartySize,maxPartySize"];
  const manifest = [];
  for (let ordinal = 1; ordinal <= 1_000; ordinal += 1) {
    const prioritySecond = ordinal <= 200 ? Math.ceil(ordinal / 2) : ordinal;
    const timestamp = new Date(Date.UTC(2026, 0, 1) + (prioritySecond * 1_000))
      .toISOString().replace(".000Z", "Z");
    rows.push(`${ordinal},${ordinal},${timestamp},${timestamp},2,4`);
    manifest.push({ fixtureOrdinal: ordinal, userFixtureOrdinal: ordinal, queuedAt: timestamp, prioritySince: timestamp,
      minPartySize: 2, maxPartySize: 4, userId: ordinal, requestId: ordinal, expectedTieOrder: ordinal });
  }
  const inputCsv = `${rows.join("\n")}\n`;
  const materializedManifestSha256 = fixtureInputSha256(
    `fixtureOrdinal,userFixtureOrdinal,queuedAt,prioritySince,minPartySize,maxPartySize,userId,requestId,expectedTieOrder\n${manifest.map((entry) => [entry.fixtureOrdinal, entry.userFixtureOrdinal, entry.queuedAt, entry.prioritySince, entry.minPartySize, entry.maxPartySize, entry.userId, entry.requestId, entry.expectedTieOrder].join(",")).join("\n")}\n`,
  );
  return {
    generator: "MATCH-01-CANDIDATE-BASELINE-V2",
    fixtureInputSha256: fixtureInputSha256(inputCsv),
    inputCsv,
    materializedManifestSha256,
    manifest,
  };
}

function completeMixedRangeSmoke() {
  const rows = ["fixtureOrdinal,userFixtureOrdinal,queuedAt,prioritySince,minPartySize,maxPartySize"];
  const manifest = [];
  [[1, 2, 4], [2, 4, 4], [3, 2, 2], [4, 4, 4], [5, 4, 4]].forEach(([ordinal, minPartySize, maxPartySize]) => {
    const timestamp = new Date(Date.UTC(2026, 0, 1) + (ordinal * 1_000))
      .toISOString().replace(".000Z", "Z");
    rows.push(`${ordinal},${ordinal},${timestamp},${timestamp},${minPartySize},${maxPartySize}`);
    manifest.push({ fixtureOrdinal: ordinal, userFixtureOrdinal: ordinal, queuedAt: timestamp,
      prioritySince: timestamp, minPartySize, maxPartySize, userId: ordinal, requestId: ordinal,
      expectedTieOrder: ordinal });
  });
  const manifestBytes = `${[
    "fixtureOrdinal", "userFixtureOrdinal", "queuedAt", "prioritySince", "minPartySize", "maxPartySize",
    "userId", "requestId", "expectedTieOrder",
  ].join(",")}\n${manifest.map((entry) => [entry.fixtureOrdinal, entry.userFixtureOrdinal, entry.queuedAt,
    entry.prioritySince, entry.minPartySize, entry.maxPartySize, entry.userId, entry.requestId,
    entry.expectedTieOrder].join(",")).join("\n")}\n`;
  return {
    fixtureGenerator: "MATCH-01-CANDIDATE-MIXED-RANGE-V1",
    fixtureInputSha256: fixtureInputSha256(`${rows.join("\n")}\n`),
    materializedManifestSha256: fixtureInputSha256(manifestBytes),
    materializedManifest: manifest,
    measuredGitCommitSha: "a".repeat(40),
    proposalCount: 1,
    targetPartySize: 2,
    proposalMembers: ["R1", "R3"],
    waitingRequestCount: 3,
    passed: true,
  };
}

test("warm-up을 제외하고 measured round 원자료와 nearest-rank 통계를 보존한다", () => {
  const fixture = completeFixture();
  const topology = completeTopology();
  const report = buildCandidateBaselineReport({
    environmentProfile: { stackId: "perf-test", ephemeral: true },
    fixture,
    warmUp: completeRound(0, fixture, topology),
    measured: [completeRound(1, fixture, topology), completeRound(2, fixture, topology), completeRound(3, fixture, topology)],
  });

  assert.equal(report.warmUp.countsTowardBaseline, false);
  assert.equal(report.measuredGitCommitSha, "a".repeat(40));
  assert.deepEqual(report.environmentProfile, { stackId: "perf-test", ephemeral: true });
  assert.match(report.rawMeasurementSha256, /^[a-f0-9]{64}$/u);
  assert.equal(report.rawMeasurementDigestVerified, null);
  assert.equal(report.warmUp.round.measuredGitCommitSha, "a".repeat(40));
  assert.equal(report.measuredRounds.length, 3);
  assert.equal(report.measuredRounds[0].logicalClaims.length, 1_000);
  assert.equal(report.warmUp.round.fixtureEvidence.fixtureInputSha256, report.fixture.fixtureInputSha256);
  assert.equal(report.measuredRounds[0].topology.claimAttempts, 500);
  assert.equal(report.measuredRounds[0].logicalClaims[0].matcherPid, 101);
  assert.equal(report.measuredRounds[0].statistics.p95Nanos, 950_000);
  assert.equal(report.series.p95MedianNanos, 950_000);
  assert.equal(report.series.p95MaximumNanos, 950_000);
  assert.equal(report.measuredRounds[0].lockSamples.intervalMs, 10);
  assert.equal(report.measuredRounds[0].lockSamples.observationStartedAtUtc, "2026-01-01T00:00:00Z");
  assert.equal(report.fixture.inputCsv.includes("minPartySize,maxPartySize"), true);
  assert.equal(report.fixture.manifest[0].userId, 1);
  assert.equal(report.fixture.manifest[0].requestId, 1);
  assert.deepEqual(Object.keys(report.fixture.manifest[0]).sort(), [
    "expectedTieOrder", "fixtureOrdinal", "maxPartySize", "minPartySize", "prioritySince", "queuedAt",
    "requestId", "userFixtureOrdinal", "userId",
  ]);
  assert.deepEqual(report.measuredRounds[0].logicalClaims[0].retryRawDurationsNanos, []);
  assert.deepEqual(nearestRank([1, 2, 3, 4], 0.95), 4);
  assert.equal(JSON.stringify(report).includes("real-user"), false);
});

test("외부 T10 raw measurement digest가 원자료 변경을 INVALID로 판정한다", () => {
  const fixture = completeFixture();
  const externalInput = {
    measuredGitCommitSha: "a".repeat(40),
    environmentProfile: { stackId: "perf-test", ephemeral: true },
    externalProvenance: {
      runner: "MATCH-01-T10-EXTERNAL-POSTGRESQL-V1",
      target: "external-postgresql",
      environmentProfile: {
        stackId: "perf-test",
        databaseRole: "measurement",
        runner: "MATCH-01-T10-EXTERNAL-POSTGRESQL-V1",
        ephemeral: true,
        releaseSha: "a".repeat(40),
      },
    },
    mixedRangeSmoke: completeMixedRangeSmoke(),
    fixture,
    warmUp: completeRound(0, fixture),
    measured: [completeRound(1, fixture), completeRound(2, fixture), completeRound(3, fixture)],
  };
  const valid = buildCandidateBaselineReport({
    ...externalInput,
    rawMeasurementSha256: calculateRawMeasurementSha256(externalInput),
  });
  assert.equal(valid.rawMeasurementDigestVerified, true);
  assert.equal(evaluateCandidateBaseline(valid).outcome, "BASELINE_ACCEPTED");

  const mismatchedReleaseSha = structuredClone(externalInput);
  mismatchedReleaseSha.externalProvenance.environmentProfile.releaseSha = "b".repeat(40);
  mismatchedReleaseSha.rawMeasurementSha256 = calculateRawMeasurementSha256(mismatchedReleaseSha);
  const mismatchedReleaseShaReport = buildCandidateBaselineReport(mismatchedReleaseSha);
  assert.equal(evaluateCandidateBaseline(mismatchedReleaseShaReport).outcome, "INVALID");

  const tampered = structuredClone(valid);
  tampered.measuredRounds[0].logicalClaims[0].durationNanos += 1;
  assert.equal(evaluateCandidateBaseline(tampered).outcome, "INVALID");

  const missingDigest = buildCandidateBaselineReport(externalInput);
  assert.equal(evaluateCandidateBaseline(missingDigest).outcome, "INVALID");

  const missingSmoke = structuredClone(valid);
  delete missingSmoke.mixedRangeSmoke;
  assert.equal(evaluateCandidateBaseline(missingSmoke).outcome, "INVALID");

  const tamperedSmokeManifestHash = structuredClone(externalInput);
  tamperedSmokeManifestHash.mixedRangeSmoke.materializedManifestSha256 = "c".repeat(64);
  const selfConsistentTampering = buildCandidateBaselineReport({
    ...tamperedSmokeManifestHash,
    rawMeasurementSha256: calculateRawMeasurementSha256(tamperedSmokeManifestHash),
  });
  assert.equal(evaluateCandidateBaseline(selfConsistentTampering).outcome, "INVALID");
});

test("명시적 external CLI mode는 provenance 누락을 local report로 강등하지 않고 INVALID로 거절한다", () => {
  const directory = mkdtempSync(join(tmpdir(), "match01-external-provenance-"));
  const inputPath = join(directory, "input.json");
  const outputPath = join(directory, "output.json");
  writeFileSync(inputPath, JSON.stringify({}));
  try {
    const result = spawnSync(process.execPath, [
      "scripts/measurements/match01-candidate-baseline-report.mjs",
      "--input", inputPath, "--output", outputPath, "--external",
    ]);
    assert.equal(result.status, 0, result.stderr.toString());
    const output = JSON.parse(readFileSync(outputPath, "utf8"));
    assert.equal(output.report.executionMode, "external");
    assert.equal(output.decision.outcome, "INVALID");
    assert.match(output.decision.reason, /external provenance/u);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("명시적 external CLI mode는 mixed-range smoke와 raw digest를 항상 요구한다", () => {
  const fixture = completeFixture();
  const input = {
    measuredGitCommitSha: "a".repeat(40),
    environmentProfile: { stackId: "perf-test", ephemeral: true },
    executionMode: "external",
    externalProvenance: {
      runner: "MATCH-01-T10-EXTERNAL-POSTGRESQL-V1",
      target: "external-postgresql",
      environmentProfile: {
        stackId: "perf-test",
        databaseRole: "measurement",
        runner: "MATCH-01-T10-EXTERNAL-POSTGRESQL-V1",
        ephemeral: true,
        releaseSha: "a".repeat(40),
      },
    },
    fixture,
    warmUp: completeRound(0, fixture),
    measured: [completeRound(1, fixture), completeRound(2, fixture), completeRound(3, fixture)],
    mixedRangeSmoke: completeMixedRangeSmoke(),
  };
  input.rawMeasurementSha256 = calculateRawMeasurementSha256(input);
  const directory = mkdtempSync(join(tmpdir(), "match01-external-gates-"));
  const inputPath = join(directory, "input.json");
  const outputPath = join(directory, "output.json");
  try {
    const runExternal = (value) => {
      writeFileSync(inputPath, JSON.stringify(value));
      const result = spawnSync(process.execPath, [
        "scripts/measurements/match01-candidate-baseline-report.mjs",
        "--input", inputPath, "--output", outputPath, "--external",
      ]);
      assert.equal(result.status, 0, result.stderr.toString());
      return JSON.parse(readFileSync(outputPath, "utf8"));
    };

    const accepted = runExternal(input);
    assert.equal(accepted.report.executionMode, "external");
    assert.equal(accepted.decision.outcome, "BASELINE_ACCEPTED");

    const alteredProvenance = structuredClone(input);
    alteredProvenance.externalProvenance.runner = "untrusted-runner";
    alteredProvenance.rawMeasurementSha256 = calculateRawMeasurementSha256(alteredProvenance);
    const alteredProvenanceOutput = runExternal(alteredProvenance);
    assert.equal(alteredProvenanceOutput.decision.outcome, "INVALID");
    assert.match(alteredProvenanceOutput.decision.reason, /external provenance/u);

    const missingSmoke = structuredClone(input);
    delete missingSmoke.mixedRangeSmoke;
    const missingSmokeOutput = runExternal(missingSmoke);
    assert.equal(missingSmokeOutput.decision.outcome, "INVALID");
    assert.match(missingSmokeOutput.decision.reason, /혼합 범위 correctness smoke|mixed-range smoke/u);

    const missingRawDigest = structuredClone(input);
    delete missingRawDigest.rawMeasurementSha256;
    const missingRawOutput = runExternal(missingRawDigest);
    assert.equal(missingRawOutput.decision.outcome, "INVALID");
    assert.match(missingRawOutput.decision.reason, /raw measurement digest/u);

    const tamperedRaw = structuredClone(input);
    tamperedRaw.measured[0].logicalClaims[0].durationNanos += 1;
    const tamperedRawOutput = runExternal(tamperedRaw);
    assert.equal(tamperedRawOutput.decision.outcome, "INVALID");
    assert.match(tamperedRawOutput.decision.reason, /raw measurement digest/u);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("external provenance가 있는 입력을 local CLI로 강등하지 않는다", () => {
  const fixture = completeFixture();
  const input = {
    measuredGitCommitSha: "a".repeat(40),
    environmentProfile: { stackId: "perf-test", ephemeral: true },
    externalProvenance: {
      runner: "MATCH-01-T10-EXTERNAL-POSTGRESQL-V1",
      target: "external-postgresql",
      environmentProfile: {
        stackId: "perf-test",
        databaseRole: "measurement",
        runner: "MATCH-01-T10-EXTERNAL-POSTGRESQL-V1",
        ephemeral: true,
        releaseSha: "a".repeat(40),
      },
    },
    mixedRangeSmoke: completeMixedRangeSmoke(),
    fixture,
    warmUp: completeRound(0, fixture),
    measured: [completeRound(1, fixture), completeRound(2, fixture), completeRound(3, fixture)],
  };
  const directory = mkdtempSync(join(tmpdir(), "match01-provenance-downgrade-"));
  const inputPath = join(directory, "input.json");
  const outputPath = join(directory, "output.json");
  writeFileSync(inputPath, JSON.stringify(input));
  try {
    const result = spawnSync(process.execPath, [
      "scripts/measurements/match01-candidate-baseline-report.mjs",
      "--input", inputPath, "--output", outputPath,
    ]);
    assert.equal(result.status, 0, result.stderr.toString());
    const output = JSON.parse(readFileSync(outputPath, "utf8"));
    assert.equal(output.report.executionMode, "local");
    assert.equal(output.decision.outcome, "INVALID");
    assert.match(output.decision.reason, /강등/u);
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("local CLI mode는 기존 fixture와 round correctness gate를 유지한다", () => {
  const fixture = completeFixture();
  const report = buildCandidateBaselineReport({
    measuredGitCommitSha: "a".repeat(40),
    fixture,
    warmUp: completeRound(0, fixture),
    measured: [completeRound(1, fixture), completeRound(2, fixture), completeRound(3, fixture)],
  });

  assert.equal(report.executionMode, "local");
  assert.equal(evaluateCandidateBaseline(report).outcome, "BASELINE_ACCEPTED");
});

test("실행 SHA와 실제 candidate claim query plan 증거가 없거나 다르면 INVALID다", () => {
  const valid = buildCandidateBaselineReport({
    measuredGitCommitSha: "a".repeat(40),
    fixture: completeFixture(),
    warmUp: completeRound(0),
    measured: [completeRound(1), completeRound(2), completeRound(3)],
  });
  assert.equal(evaluateCandidateBaseline(valid).outcome, "BASELINE_ACCEPTED");

  const missingSha = structuredClone(valid);
  delete missingSha.measuredGitCommitSha;
  assert.equal(evaluateCandidateBaseline(missingSha).outcome, "INVALID");

  const mismatchedRoundSha = structuredClone(valid);
  mismatchedRoundSha.measuredRounds[1].measuredGitCommitSha = "b".repeat(40);
  assert.equal(evaluateCandidateBaseline(mismatchedRoundSha).outcome, "INVALID");

  const malformedSha = structuredClone(valid);
  malformedSha.measuredGitCommitSha = "short";
  assert.equal(evaluateCandidateBaseline(malformedSha).outcome, "INVALID");

  const oldPlan = structuredClone(valid);
  oldPlan.measuredRounds[0].queryPlan = "fixture candidate claim plan";
  assert.equal(evaluateCandidateBaseline(oldPlan).outcome, "INVALID");

  const missingCursorPredicate = structuredClone(valid);
  missingCursorPredicate.measuredRounds[0].queryPlan =
    "anchorSql=select * from match_requests where status = 'WAITING' order by priority_since asc, id asc limit 1 for update skip locked\nanchorPlan=[{}]\ncandidatePageSql=select * from match_requests where status = 'WAITING' order by priority_since asc, id asc limit 100 for update skip locked\ncandidatePagePlan=[{}]";
  assert.equal(evaluateCandidateBaseline(missingCursorPredicate).outcome, "INVALID");
});

test("모든 round의 fixture와 topology 증거, matcher별 500회 원자료가 아니면 INVALID다", () => {
  const fixture = completeFixture();
  const topology = completeTopology();
  const valid = buildCandidateBaselineReport({
    fixture,
    warmUp: completeRound(0, fixture, topology),
    measured: [completeRound(1, fixture, topology), completeRound(2, fixture, topology), completeRound(3, fixture, topology)],
  });
  assert.equal(evaluateCandidateBaseline(valid).outcome, "BASELINE_ACCEPTED");

  const missingWarmUp = structuredClone(valid);
  delete missingWarmUp.warmUp.round;
  assert.equal(evaluateCandidateBaseline(missingWarmUp).outcome, "INVALID");

  const mismatchedFixture = structuredClone(valid);
  mismatchedFixture.measuredRounds[1].fixtureEvidence.fixtureInputSha256 = "b".repeat(64);
  assert.equal(evaluateCandidateBaseline(mismatchedFixture).outcome, "INVALID");

  const mismatchedTopology = structuredClone(valid);
  mismatchedTopology.measuredRounds[2].topology.configurationSha = "b".repeat(64);
  assert.equal(evaluateCandidateBaseline(mismatchedTopology).outcome, "INVALID");

  const noClaimsFromSecondMatcher = structuredClone(valid);
  noClaimsFromSecondMatcher.measuredRounds[0].logicalClaims.forEach((claim) => { claim.matcherPid = 101; });
  assert.equal(evaluateCandidateBaseline(noClaimsFromSecondMatcher).outcome, "INVALID");

  const unevenClaims = structuredClone(valid);
  unevenClaims.measuredRounds[0].logicalClaims[499].matcherPid = 202;
  assert.equal(evaluateCandidateBaseline(unevenClaims).outcome, "INVALID");
});

test("round materialized manifest 증거가 canonical fixture와 다르면 INVALID다", () => {
  const fixture = completeFixture();
  const topology = completeTopology();
  const valid = buildCandidateBaselineReport({
    fixture,
    warmUp: completeRound(0, fixture, topology),
    measured: [completeRound(1, fixture, topology), completeRound(2, fixture, topology), completeRound(3, fixture, topology)],
  });

  valid.measuredRounds[1].fixtureEvidence.materializedManifestSha256 = "b".repeat(64);

  assert.equal(evaluateCandidateBaseline(valid).outcome, "INVALID");
});

test("고정 fixture generator와 모든 ordinal 규칙이 아니면 INVALID로 거절한다", () => {
  const valid = buildCandidateBaselineReport({
    fixture: completeFixture(),
    warmUp: completeRound(0),
    measured: [completeRound(1), completeRound(2), completeRound(3)],
  });
  assert.equal(valid.fixture.generator, "MATCH-01-CANDIDATE-BASELINE-V2");
  assert.equal(evaluateCandidateBaseline(valid).outcome, "BASELINE_ACCEPTED");

  const wrongGenerator = structuredClone(valid);
  wrongGenerator.fixture.generator = "other";
  assert.equal(evaluateCandidateBaseline(wrongGenerator).outcome, "INVALID");

  const wrongRange = structuredClone(valid);
  wrongRange.fixture.manifest[300].minPartySize = 1;
  assert.equal(evaluateCandidateBaseline(wrongRange).outcome, "INVALID");

  const wrongTiePriority = structuredClone(valid);
  wrongTiePriority.fixture.manifest[1].prioritySince = "2026-01-01T00:00:09Z";
  assert.equal(evaluateCandidateBaseline(wrongTiePriority).outcome, "INVALID");

  const wrongDistinctPriority = structuredClone(valid);
  wrongDistinctPriority.fixture.manifest[200].prioritySince = valid.fixture.manifest[199].prioritySince;
  assert.equal(evaluateCandidateBaseline(wrongDistinctPriority).outcome, "INVALID");
});

test("raw 배열 누락 CLI 입력도 INVALID decision JSON으로 보존한다", () => {
  const directory = mkdtempSync(join(tmpdir(), "match01-invalid-input-"));
  const inputPath = join(directory, "input.json");
  const outputPath = join(directory, "output.json");
  const input = {
    fixture: completeFixture(),
    warmUp: completeRound(0),
    measured: [completeRound(1), completeRound(2), completeRound(3)],
  };
  delete input.measured[1].logicalClaims;
  writeFileSync(inputPath, JSON.stringify(input));
  try {
    const result = spawnSync(process.execPath, ["scripts/measurements/match01-candidate-baseline-report.mjs", "--input", inputPath, "--output", outputPath]);
    assert.equal(result.status, 0, result.stderr.toString());
    const output = JSON.parse(readFileSync(outputPath, "utf8"));
    assert.equal(output.decision.outcome, "INVALID");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("lock 관측 창과 실제 fixture의 100개 tie 결과가 없으면 INVALID다", () => {
  const valid = buildCandidateBaselineReport({
    fixture: completeFixture(),
    warmUp: completeRound(0),
    measured: [completeRound(1), completeRound(2), completeRound(3)],
  });
  const missingLockWindow = structuredClone(valid);
  delete missingLockWindow.measuredRounds[0].lockSamples.observationStartedAtUtc;
  assert.equal(evaluateCandidateBaseline(missingLockWindow).outcome, "INVALID");

  const reversedLockWindow = structuredClone(valid);
  reversedLockWindow.measuredRounds[0].lockSamples.observationFinishedAtUtc = "2025-12-31T23:59:59Z";
  assert.equal(evaluateCandidateBaseline(reversedLockWindow).outcome, "INVALID");

  const missingTiePair = structuredClone(valid);
  missingTiePair.measuredRounds[0].correctnessInput.tiePairResults.pop();
  assert.equal(evaluateCandidateBaseline(missingTiePair).outcome, "INVALID");

  const failedTiePair = structuredClone(valid);
  failedTiePair.measuredRounds[0].correctnessInput.tiePairResults[0].sameProposal = false;
  assert.equal(evaluateCandidateBaseline(failedTiePair).outcome, "FAILED");

  const reversedCandidateSql = structuredClone(valid);
  reversedCandidateSql.measuredRounds[0].pgStatStatements.candidateStatements[0].query =
    "select * from match_requests where status = 'WAITING' order by priority_since desc, id asc limit $1 for update skip locked";
  assert.equal(evaluateCandidateBaseline(reversedCandidateSql).outcome, "INVALID");
});

test("lock wait snapshot 수가 없거나 0 이상의 정수가 아니면 INVALID다", () => {
  const valid = buildCandidateBaselineReport({
    fixture: completeFixture(),
    warmUp: completeRound(0),
    measured: [completeRound(1), completeRound(2), completeRound(3)],
  });

  for (const invalidValue of [undefined, -1, 0.5]) {
    const incomplete = structuredClone(valid);
    if (invalidValue === undefined) {
      delete incomplete.measuredRounds[0].lockSamples.lockWaitSnapshotCount;
    } else {
      incomplete.measuredRounds[0].lockSamples.lockWaitSnapshotCount = invalidValue;
    }
    assert.equal(evaluateCandidateBaseline(incomplete).outcome, "INVALID");
  }
});

test("관측과 process 누락은 INVALID, 완료 뒤 정합성 위반은 FAILED로 판정한다", () => {
  const valid = buildCandidateBaselineReport({
    fixture: completeFixture(),
    warmUp: completeRound(0),
    measured: [completeRound(1), completeRound(2), completeRound(3)],
  });
  assert.equal(evaluateCandidateBaseline(valid).outcome, "BASELINE_ACCEPTED");

  const missingObservation = structuredClone(valid);
  delete missingObservation.measuredRounds[1].pgStatStatements;
  assert.equal(evaluateCandidateBaseline(missingObservation).outcome, "INVALID");

  const missingPlan = structuredClone(valid);
  delete missingPlan.measuredRounds[1].queryPlan;
  assert.equal(evaluateCandidateBaseline(missingPlan).outcome, "INVALID");

  const missingSamplingFailure = structuredClone(valid);
  delete missingSamplingFailure.measuredRounds[1].lockSamples.samplingFailure;
  assert.equal(evaluateCandidateBaseline(missingSamplingFailure).outcome, "INVALID");

  const duplicatePid = structuredClone(valid);
  duplicatePid.measuredRounds[0].matcherProcesses[1].pid = duplicatePid.measuredRounds[0].matcherProcesses[0].pid;
  assert.equal(evaluateCandidateBaseline(duplicatePid).outcome, "INVALID");

  const invalidPid = structuredClone(valid);
  invalidPid.measuredRounds[0].matcherProcesses[0].pid = 0;
  assert.equal(evaluateCandidateBaseline(invalidPid).outcome, "INVALID");

  const samplingFailure = structuredClone(valid);
  samplingFailure.measuredRounds[1].lockSamples.samplingFailure = "SQLException";
  assert.equal(evaluateCandidateBaseline(samplingFailure).outcome, "INVALID");

  const missingMaterializedRequestId = structuredClone(valid);
  delete missingMaterializedRequestId.fixture.manifest[0].requestId;
  assert.equal(evaluateCandidateBaseline(missingMaterializedRequestId).outcome, "INVALID");

  const tamperedCsv = structuredClone(valid);
  tamperedCsv.fixture.inputCsv = tamperedCsv.fixture.inputCsv.replace("1,1,", "2,1,");
  assert.equal(evaluateCandidateBaseline(tamperedCsv).outcome, "INVALID");

  const duplicateId = structuredClone(valid);
  duplicateId.fixture.manifest[1].requestId = duplicateId.fixture.manifest[0].requestId;
  assert.equal(evaluateCandidateBaseline(duplicateId).outcome, "INVALID");

  const substitutedId = structuredClone(valid);
  substitutedId.fixture.manifest[0].userId = 9_999;
  assert.equal(evaluateCandidateBaseline(substitutedId).outcome, "INVALID");

  const reversedTie = structuredClone(valid);
  reversedTie.fixture.manifest[1].requestId = 0;
  assert.equal(evaluateCandidateBaseline(reversedTie).outcome, "INVALID");

  const wrongExpectedTieOrder = structuredClone(valid);
  wrongExpectedTieOrder.fixture.manifest[0].expectedTieOrder = 0;
  assert.equal(evaluateCandidateBaseline(wrongExpectedTieOrder).outcome, "INVALID");

  const correctnessDuplicate = structuredClone(valid);
  correctnessDuplicate.measuredRounds[0].correctnessInput.duplicateClaimCount = 1;
  delete correctnessDuplicate.measuredRounds[0].integrity;
  assert.equal(evaluateCandidateBaseline(correctnessDuplicate).outcome, "FAILED");

  const correctnessTie = structuredClone(valid);
  correctnessTie.measuredRounds[0].correctnessInput.tieOrderMatches = false;
  delete correctnessTie.measuredRounds[0].integrity;
  assert.equal(evaluateCandidateBaseline(correctnessTie).outcome, "FAILED");

  const inconsistent = structuredClone(valid);
  inconsistent.measuredRounds[2].integrity.duplicateClaimCount = 1;
  assert.equal(evaluateCandidateBaseline(inconsistent).outcome, "INVALID");
});
