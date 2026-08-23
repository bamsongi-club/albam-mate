#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

import { assertResponseArtifact } from "./match01-response-completion-report.mjs";

export const RESPONSE_COMPARISON_ACCEPTED = "RESPONSE_COMPARISON_ACCEPTED";
export const SCENARIOS = ["ACCEPT_NON_TERMINAL", "ACCEPT_FINAL", "REQUEUE", "CANCEL"];

const SHA_256 = /^[a-f0-9]{64}$/u;
const GIT_SHA = /^[a-f0-9]{40}$/u;
const IMAGE_DIGEST = /@sha256:[a-f0-9]{64}$/u;
const RESPONSE_FIXTURE_SEED = "MATCH-01-RESPONSE-COMPLETION-V2";
const REQUIRED_PROFILE_KEYS = [
  "target",
  "stackId",
  "region",
  "releaseSha",
  "appInstanceType",
  "postgresInstanceType",
  "redisInstanceType",
  "backendImage",
  "webImage",
  "postgresImage",
  "redisImage",
  "applicationConfigSha256",
  "responseTopology",
];

export function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function invalid(reason) {
  return { outcome: "INVALID", reason };
}

function canonicalize(value) {
  if (Array.isArray(value)) {
    return value.map(canonicalize);
  }
  if (value !== null && typeof value === "object") {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]));
  }
  return value;
}

function sameValue(left, right) {
  return JSON.stringify(canonicalize(left)) === JSON.stringify(canonicalize(right));
}

function profileFromArtifact(artifact, label) {
  const profile = artifact?.environment?.profile;
  if (profile === null || typeof profile !== "object" || Array.isArray(profile)) {
    return invalid(label + " environment.profile이 없습니다.");
  }
  for (const key of REQUIRED_PROFILE_KEYS) {
    if (typeof profile[key] !== "string" || profile[key].length === 0) {
      return invalid(label + " environment.profile." + key + "가 없습니다.");
    }
  }
  if (!GIT_SHA.test(profile.releaseSha)) {
    return invalid(label + " environment.profile.releaseSha가 40자리 SHA가 아닙니다.");
  }
  for (const key of ["applicationConfigSha256"]) {
    if (!SHA_256.test(profile[key])) {
      return invalid(label + " environment.profile." + key + "가 SHA-256 digest가 아닙니다.");
    }
  }
  for (const key of ["backendImage", "webImage", "postgresImage", "redisImage"]) {
    if (!IMAGE_DIGEST.test(profile[key])) {
      return invalid(label + " environment.profile." + key + "가 이미지 digest가 아닙니다.");
    }
  }
  return { outcome: "ACCEPTED", profile };
}

function findScenario(artifact, scenario) {
  return artifact.scenarios.find((entry) => entry.scenario === scenario);
}

function findRound(scenario, round) {
  return scenario?.measuredRounds?.find((entry) => entry.round === round);
}

function roundMetricSnapshot(round, label) {
  const metrics = round?.metrics;
  const latency = metrics?.latencyNanos;
  const retry = metrics?.retry;
  const failure = metrics?.failure;
  const lockWait = round?.lockWait;
  const assertion = round?.finalStateAssertion;
  if (!metrics || !latency || !retry || !failure || !lockWait || !assertion
    || !SHA_256.test(round.rawDataSha256 ?? "")) {
    return invalid(label + " metric·lock wait·최종 상태 assertion·raw digest가 없습니다.");
  }
  return {
    outcome: "ACCEPTED",
    snapshot: {
      rawDataSha256: round.rawDataSha256,
      latencyNanos: {
        p50: latency.p50,
        p95: latency.p95,
        p99: latency.p99,
      },
      throughputPerSecond: metrics.throughputPerSecond,
      retry: {
        total: retry.total,
        max: retry.max,
      },
      lockWait: {
        sampledWaitNanos: lockWait.sampledWaitNanos,
        sampleTotalNanos: lockWait.sampleTotalNanos,
        sampleMaxNanos: lockWait.sampleMaxNanos,
      },
      failure: {
        count: failure.count,
        rate: failure.rate,
      },
      finalStateAssertion: assertion,
    },
  };
}

function p95Summary(snapshots) {
  const values = snapshots.map((snapshot) => snapshot.latencyNanos.p95).sort((left, right) => left - right);
  return {
    medianNanos: values[1],
    maxNanos: values[values.length - 1],
  };
}

function delta(before, after) {
  return {
    p50Nanos: after.latencyNanos.p50 - before.latencyNanos.p50,
    p95Nanos: after.latencyNanos.p95 - before.latencyNanos.p95,
    p99Nanos: after.latencyNanos.p99 - before.latencyNanos.p99,
    throughputPerSecond: after.throughputPerSecond - before.throughputPerSecond,
    retryTotal: after.retry.total - before.retry.total,
    retryMax: after.retry.max - before.retry.max,
    sampledWaitNanos: after.lockWait.sampledWaitNanos - before.lockWait.sampledWaitNanos,
    sampleTotalNanos: after.lockWait.sampleTotalNanos - before.lockWait.sampleTotalNanos,
    failureRate: after.failure.rate - before.failure.rate,
  };
}

export function compareAcceptedArtifacts(beforeArtifact, afterArtifact, provenance = {}) {
  const beforeProfileResult = profileFromArtifact(beforeArtifact, "before");
  if (beforeProfileResult.outcome !== "ACCEPTED") {
    return beforeProfileResult;
  }
  const afterProfileResult = profileFromArtifact(afterArtifact, "after");
  if (afterProfileResult.outcome !== "ACCEPTED") {
    return afterProfileResult;
  }
  if (!sameValue(beforeProfileResult.profile, afterProfileResult.profile)) {
    return invalid("before/after environment.profile이 다릅니다.");
  }
  if (beforeArtifact.fixture?.seed !== RESPONSE_FIXTURE_SEED
    || afterArtifact.fixture?.seed !== RESPONSE_FIXTURE_SEED) {
    return invalid("before/after fixture seed가 MATCH-01-RESPONSE-COMPLETION-V2가 아닙니다.");
  }
  if (beforeArtifact.fixture?.fixtureInputSha256 !== afterArtifact.fixture?.fixtureInputSha256
    || !SHA_256.test(beforeArtifact.fixture?.fixtureInputSha256 ?? "")) {
    return invalid("before/after fixtureInputSha256가 같지 않거나 올바르지 않습니다.");
  }

  const scenarios = [];
  for (const scenarioName of SCENARIOS) {
    const beforeScenario = findScenario(beforeArtifact, scenarioName);
    const afterScenario = findScenario(afterArtifact, scenarioName);
    if (!beforeScenario || !afterScenario
      || beforeScenario.warmUp?.completed !== true || afterScenario.warmUp?.completed !== true) {
      return invalid(scenarioName + " before/after warm-up이 없습니다.");
    }
    const measuredRounds = [];
    const beforeSnapshots = [];
    const afterSnapshots = [];
    for (const roundNumber of [1, 2, 3]) {
      const beforeMetric = roundMetricSnapshot(
        findRound(beforeScenario, roundNumber),
        "before " + scenarioName + " round " + roundNumber,
      );
      if (beforeMetric.outcome !== "ACCEPTED") {
        return beforeMetric;
      }
      const afterMetric = roundMetricSnapshot(
        findRound(afterScenario, roundNumber),
        "after " + scenarioName + " round " + roundNumber,
      );
      if (afterMetric.outcome !== "ACCEPTED") {
        return afterMetric;
      }
      beforeSnapshots.push(beforeMetric.snapshot);
      afterSnapshots.push(afterMetric.snapshot);
      measuredRounds.push({
        round: roundNumber,
        before: beforeMetric.snapshot,
        after: afterMetric.snapshot,
        delta: delta(beforeMetric.snapshot, afterMetric.snapshot),
      });
    }
    scenarios.push({
      scenario: scenarioName,
      before: { p95: p95Summary(beforeSnapshots) },
      after: { p95: p95Summary(afterSnapshots) },
      measuredRounds,
    });
  }

  return {
    outcome: RESPONSE_COMPARISON_ACCEPTED,
    fixture: {
      seed: beforeArtifact.fixture.seed,
      fixtureInputSha256: beforeArtifact.fixture.fixtureInputSha256,
    },
    environmentProfile: beforeProfileResult.profile,
    before: {
      measuredGitCommitSha: beforeArtifact.measuredGitCommitSha,
      artifactSha256: provenance.beforeArtifactSha256,
    },
    after: {
      measuredGitCommitSha: afterArtifact.measuredGitCommitSha,
      artifactSha256: provenance.afterArtifactSha256,
    },
    scenarios,
  };
}

export function compareResponseFiles(beforePath, afterPath) {
  const before = loadArtifact(beforePath, "before");
  if (before.outcome !== "ACCEPTED") {
    return before;
  }
  const after = loadArtifact(afterPath, "after");
  if (after.outcome !== "ACCEPTED") {
    return after;
  }
  return compareAcceptedArtifacts(before.artifact, after.artifact, {
    beforeArtifactSha256: sha256(before.bytes),
    afterArtifactSha256: sha256(after.bytes),
  });
}

export function reportPathFromArguments(args) {
  const reportIndex = args.indexOf("--write-report");
  if (reportIndex < 0) {
    return null;
  }
  const reportPath = args[reportIndex + 1];
  if (!reportPath || reportPath.startsWith("--")) {
    throw new Error("--write-report에는 report 파일 경로가 필요합니다.");
  }
  return reportPath;
}

function loadArtifact(file, label) {
  try {
    const resolvedPath = path.resolve(file);
    const bytes = fs.readFileSync(resolvedPath);
    const artifact = JSON.parse(bytes.toString("utf8"));
    const sidecarPath = path.resolve(path.dirname(resolvedPath), artifact.fixture.privateCanonicalManifestFile);
    const sidecar = fs.readFileSync(sidecarPath, "utf8");
    const result = assertResponseArtifact(artifact, sidecar);
    if (result.outcome === "FAILED") {
      return { outcome: "FAILED", reason: label + ": " + result.reason };
    }
    if (result.outcome !== "RESPONSE_BASELINE_ACCEPTED") {
      return invalid(label + ": " + result.reason);
    }
    return { outcome: "ACCEPTED", artifact, bytes };
  } catch (error) {
    return invalid(label + " artifact 또는 private sidecar를 읽을 수 없습니다: " + error.message);
  }
}

export function renderComparisonMarkdown(comparison) {
  const document = [
    "# MATCH-01 T11 response completion before/after 비교",
    "",
    "- 판정: " + comparison.outcome,
    "- before 측정 SHA: " + comparison.before.measuredGitCommitSha,
    "- before artifact SHA-256: " + comparison.before.artifactSha256,
    "- after 측정 SHA: " + comparison.after.measuredGitCommitSha,
    "- after artifact SHA-256: " + comparison.after.artifactSha256,
    "- fixture seed: " + comparison.fixture.seed,
    "- fixtureInputSha256: " + comparison.fixture.fixtureInputSha256,
    "- comparison runner SHA: " + (comparison.comparisonRunnerGitCommitSha ?? "미기록"),
    "",
    "## 동일 환경 profile",
    "",
    "~~~json",
    JSON.stringify(comparison.environmentProfile, null, 2),
    "~~~",
    "",
    "| 시나리오 | round | before p95 (ns) | after p95 (ns) | delta (ns) | before 처리량 | after 처리량 | before 실패율 | after 실패율 | raw digest pair |",
    "| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
  ];
  for (const scenario of comparison.scenarios) {
    for (const measuredRound of scenario.measuredRounds) {
      const before = measuredRound.before;
      const after = measuredRound.after;
      document.push("| " + scenario.scenario + " | " + measuredRound.round + " | "
        + before.latencyNanos.p95 + " | " + after.latencyNanos.p95 + " | "
        + measuredRound.delta.p95Nanos + " | " + before.throughputPerSecond.toFixed(3) + " | "
        + after.throughputPerSecond.toFixed(3) + " | " + before.failure.rate.toFixed(6) + " | "
        + after.failure.rate.toFixed(6) + " | " + before.rawDataSha256 + " / "
        + after.rawDataSha256 + " |");
    }
  }
  document.push(
    "",
    "각 행은 양쪽의 p50·p95·p99·처리량·retry·lock wait·실패율·최종 상태 assertion과 raw digest를 함께 보존한다.",
  );
  return document.join("\n") + "\n";
}

function currentGitSha() {
  try {
    return execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim();
  } catch {
    return "unknown";
  }
}

function main() {
  const args = process.argv.slice(2);
  const beforeIndex = args.indexOf("--before");
  const afterIndex = args.indexOf("--after");
  if (beforeIndex < 0 || afterIndex < 0 || !args[beforeIndex + 1] || !args[afterIndex + 1]) {
    throw new Error(
      "사용법: node scripts/measurements/match01-response-completion-comparison.mjs --check "
        + "--before <before.json> --after <after.json> [--write-report <report.md>]",
    );
  }
  const reportPath = reportPathFromArguments(args);
  const result = compareResponseFiles(args[beforeIndex + 1], args[afterIndex + 1]);
  if (result.outcome === RESPONSE_COMPARISON_ACCEPTED) {
    result.comparisonRunnerGitCommitSha = currentGitSha();
    if (reportPath !== null) {
      fs.writeFileSync(reportPath, renderComparisonMarkdown(result));
      result.reportPath = path.relative(process.cwd(), path.resolve(reportPath)).replaceAll("\\", "/");
    }
  }
  process.stdout.write(JSON.stringify(result) + "\n");
  if (args.includes("--check") && result.outcome !== RESPONSE_COMPARISON_ACCEPTED) {
    process.exitCode = 1;
  }
}

if (process.argv[1]?.endsWith("match01-response-completion-comparison.mjs")) {
  main();
}
