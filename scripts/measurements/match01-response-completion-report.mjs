#!/usr/bin/env node

import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";

const SCENARIOS = ["ACCEPT_NON_TERMINAL", "ACCEPT_FINAL", "REQUEUE", "CANCEL"];
const SHA_256 = /^[a-f0-9]{64}$/u;
const GIT_SHA = /^[a-f0-9]{40}$/u;

export function sha256(value) {
  return createHash("sha256").update(value).digest("hex");
}

function invalid(reason) {
  return { outcome: "INVALID", reason };
}

function hasExactlyExpectedScenarios(artifact) {
  const actual = artifact.scenarios?.map((scenario) => scenario.scenario).sort();
  return Array.isArray(actual) && JSON.stringify(actual) === JSON.stringify([...SCENARIOS].sort());
}

function isUtcTimestamp(value) {
  return typeof value === "string" && value.endsWith("Z") && !Number.isNaN(Date.parse(value));
}

function expectedAction(scenario) {
  return scenario === "REQUEUE" || scenario === "CANCEL" ? scenario : "ACCEPT";
}

function hasExpectedResultDistribution(scenario, rawSamples) {
  const nonterminalCount = rawSamples.filter((sample) => sample.result === "NON_TERMINAL").length;
  const terminalCount = rawSamples.filter((sample) => sample.result === "TERMINAL").length;
  if (scenario === "ACCEPT_FINAL") {
    return nonterminalCount === 500 && terminalCount === 500;
  }
  return nonterminalCount === (scenario === "ACCEPT_NON_TERMINAL" ? 1_000 : 0)
    && terminalCount === (scenario === "ACCEPT_NON_TERMINAL" ? 0 : 1_000);
}

function sampleIsComplete(scenario, sample) {
  return isUtcTimestamp(sample.operationTime)
    && isUtcTimestamp(sample.completedAt)
    && isUtcTimestamp(sample.respondBy)
    && Date.parse(sample.operationTime) <= Date.parse(sample.completedAt)
    && Date.parse(sample.operationTime) < Date.parse(sample.respondBy)
    && Number.isInteger(sample.latencyNanos) && sample.latencyNanos > 0
    && sample.action === expectedAction(scenario)
    && (sample.result === "NON_TERMINAL" || sample.result === "TERMINAL")
    && Number.isInteger(sample.retryCount) && sample.retryCount >= 0
    && Number.isInteger(sample.lockWaitNanos) && sample.lockWaitNanos >= 0
    && Number.isInteger(sample.httpStatus) && sample.httpStatus >= 100 && sample.httpStatus <= 599
    && Object.hasOwn(sample, "errorCode") && (sample.errorCode === null || typeof sample.errorCode === "string")
    && typeof sample.finalStateObservation === "string" && sample.finalStateObservation.length > 0
    && sample.finalStatePassed === true;
}

function isNonNegativeInteger(value) {
  return Number.isInteger(value) && value >= 0;
}

function nearestRank(samples, percentile) {
  const sorted = [...samples].sort((left, right) => left - right);
  return sorted[Math.ceil(percentile * sorted.length) - 1];
}

function hasRecalculableDatabaseStatistics(statistics) {
  if (statistics?.observed !== true || !Array.isArray(statistics.statements)
    || statistics.statements.length === 0) {
    return false;
  }
  const integerFields = ["calls", "rows", "sharedBlksHit", "sharedBlksRead"];
  if (!statistics.statements.every((statement) => typeof statement.queryId === "string"
    && statement.queryId.length > 0
    && integerFields.every((field) => isNonNegativeInteger(statement[field]))
    && typeof statement.totalExecTimeMillis === "number" && statement.totalExecTimeMillis >= 0)) {
    return false;
  }
  const totals = {
    statementCount: statistics.statements.length,
    totalCalls: statistics.statements.reduce((total, statement) => total + statement.calls, 0),
    totalExecTimeMillis: statistics.statements.reduce((total, statement) => total + statement.totalExecTimeMillis, 0),
    totalRows: statistics.statements.reduce((total, statement) => total + statement.rows, 0),
    sharedBlksHit: statistics.statements.reduce((total, statement) => total + statement.sharedBlksHit, 0),
    sharedBlksRead: statistics.statements.reduce((total, statement) => total + statement.sharedBlksRead, 0),
  };
  return isNonNegativeInteger(statistics.statementCount)
    && isNonNegativeInteger(statistics.totalCalls)
    && typeof statistics.totalExecTimeMillis === "number" && statistics.totalExecTimeMillis >= 0
    && isNonNegativeInteger(statistics.totalRows)
    && isNonNegativeInteger(statistics.sharedBlksHit)
    && isNonNegativeInteger(statistics.sharedBlksRead)
    && Object.entries(totals).every(([field, value]) => statistics[field] === value);
}

function hasRecalculableMetrics(round) {
  const metrics = round.metrics;
  const samples = round.rawSamples;
  if (!metrics || metrics.sampleCount !== samples.length
    || !isNonNegativeInteger(metrics.observationDurationNanos) || metrics.observationDurationNanos === 0
    || typeof metrics.throughputPerSecond !== "number" || metrics.throughputPerSecond < 0
    || !metrics.latencyNanos || !metrics.retry || !metrics.failure
    || !["p50", "p95", "p99"].every((field) => isNonNegativeInteger(metrics.latencyNanos[field]))
    || !isNonNegativeInteger(metrics.retry.total) || !isNonNegativeInteger(metrics.retry.max)
    || !isNonNegativeInteger(metrics.failure.count)
    || typeof metrics.failure.rate !== "number" || metrics.failure.rate < 0 || metrics.failure.rate > 1) {
    return false;
  }
  const latencies = samples.map((sample) => sample.latencyNanos);
  const expectedThroughput = samples.length * 1_000_000_000 / metrics.observationDurationNanos;
  const retryTotal = samples.reduce((total, sample) => total + sample.retryCount, 0);
  const retryMax = Math.max(...samples.map((sample) => sample.retryCount));
  const failureCount = samples.filter((sample) => sample.errorCode !== null || sample.httpStatus >= 400).length;
  const expected = {
    p50: nearestRank(latencies, 0.50),
    p95: nearestRank(latencies, 0.95),
    p99: nearestRank(latencies, 0.99),
  };
  return Object.entries(expected).every(([field, value]) => metrics.latencyNanos[field] === value)
    && Math.abs(metrics.throughputPerSecond - expectedThroughput) < 0.000001
    && metrics.retry.total === retryTotal && metrics.retry.max === retryMax
    && metrics.failure.count === failureCount
    && Math.abs(metrics.failure.rate - failureCount / samples.length) < 0.000001;
}

function hasObservedLockWait(round) {
  const lockWait = round.lockWait;
  if (lockWait?.observed !== true || !isNonNegativeInteger(lockWait.pollCount) || lockWait.pollCount === 0
    || !isNonNegativeInteger(lockWait.waitingSessionSampleCount)
    || !isNonNegativeInteger(lockWait.sampledWaitNanos)
    || !isNonNegativeInteger(lockWait.sampleTotalNanos)
    || !isNonNegativeInteger(lockWait.sampleMaxNanos)) {
    return false;
  }
  const sampleWaits = round.rawSamples.map((sample) => sample.lockWaitNanos);
  return lockWait.sampleTotalNanos === sampleWaits.reduce((total, wait) => total + wait, 0)
    && lockWait.sampleMaxNanos === Math.max(...sampleWaits);
}

function hasExactFactCoverage(scenario, assertion) {
  const proposalCount = scenario === "ACCEPT_FINAL" ? 500 : 1_000;
  const memberAndRequestCount = scenario === "ACCEPT_FINAL" ? 1_000 : 2_000;
  return assertion.proposalFactMatchCount === proposalCount
    && assertion.memberFactMatchCount === memberAndRequestCount
    && assertion.requestFactMatchCount === memberAndRequestCount
    && assertion.queueTimestampMatchCount === memberAndRequestCount
    && assertion.proposalFactMismatchCount === 0
    && assertion.memberFactMismatchCount === 0
    && assertion.requestFactMismatchCount === 0
    && assertion.queueTimestampMismatchCount === 0;
}

function roundIsComplete(round) {
  const assertion = round.finalStateAssertion;
  return Array.isArray(round.rawSamples)
    && round.rawSamples.length === 1_000
    && typeof round.invalidReason !== "string"
    && hasRecalculableDatabaseStatistics(round.dbStatistics)
    && hasObservedLockWait(round)
    && isUtcTimestamp(round.observationWindow?.startedAt)
    && isUtcTimestamp(round.observationWindow?.endedAt)
    && Date.parse(round.observationWindow.startedAt) <= Date.parse(round.observationWindow.endedAt)
    && round.rawSamples.every((sample) => sampleIsComplete(round.scenario, sample))
    && hasExpectedResultDistribution(round.scenario, round.rawSamples)
    && hasRecalculableMetrics(round)
    && assertion !== null
    && assertion !== undefined
    && [
      "proposalCount",
      "memberResponseCount",
      "requestStatusCount",
      "partyCount",
      "partyParticipantCount",
      "currentStateCount",
      "proposedCurrentStateCount",
      "terminalCurrentStateCount",
      "otherCurrentStateCount",
      "matchedExpectedCurrentStateCount",
      "nonterminalTransitionCount",
      "terminalTransitionCount",
      "completePartyGroupCount",
      "duplicatePartyCount",
      "partialSuccessCount",
      "proposalFactMatchCount",
      "proposalFactMismatchCount",
      "memberFactMatchCount",
      "memberFactMismatchCount",
      "requestFactMatchCount",
      "requestFactMismatchCount",
      "queueTimestampMatchCount",
      "queueTimestampMismatchCount",
    ].every((field) => Number.isInteger(assertion[field]) && assertion[field] >= 0)
    && hasExactFactCoverage(round.scenario, assertion)
    && typeof round.rawDataSha256 === "string";
}

export function evaluateResponseArtifact(artifact) {
  if (!artifact?.fixture || !hasExactlyExpectedScenarios(artifact)) {
    return invalid("fixture 또는 네 시나리오가 없습니다.");
  }
  for (const scenario of artifact.scenarios) {
    if (scenario.warmUp?.completed !== true || scenario.measuredRounds?.length !== 3) {
      return invalid(`${scenario.scenario}의 warm-up 또는 measured round가 완전하지 않습니다.`);
    }
    const roundNumbers = scenario.measuredRounds.map((round) => round.round).sort((left, right) => left - right);
    if (JSON.stringify(roundNumbers) !== JSON.stringify([1, 2, 3])) {
      return invalid(`${scenario.scenario}의 measured round는 1, 2, 3을 각각 한 번만 포함해야 합니다.`);
    }
    for (const measuredRound of scenario.measuredRounds) {
      const round = { ...measuredRound, scenario: scenario.scenario };
      if (typeof round.invalidReason === "string" && round.invalidReason.length > 0) {
        return invalid(`${scenario.scenario} round ${round.round}: ${round.invalidReason}`);
      }
      if (!roundIsComplete(round)) {
        return invalid(`${scenario.scenario} round ${round.round}의 관측이 누락되었습니다.`);
      }
      const assertion = round.finalStateAssertion;
      if (assertion.passed !== true || assertion.duplicatePartyCount !== 0 || assertion.partialSuccessCount !== 0
        || assertion.proposalFactMismatchCount !== 0 || assertion.memberFactMismatchCount !== 0
        || assertion.requestFactMismatchCount !== 0 || assertion.queueTimestampMismatchCount !== 0) {
        return { outcome: "FAILED", reason: `${scenario.scenario} 최종 상태 정합성이 어긋났습니다.` };
      }
      if (assertion.matchedExpectedCurrentStateCount !== 1_000) {
        return { outcome: "FAILED", reason: `${scenario.scenario} current-state DTO 관측이 완결되지 않았습니다.` };
      }
      if (scenario.scenario === "ACCEPT_FINAL"
        && (assertion.nonterminalTransitionCount !== 500
          || assertion.terminalTransitionCount !== 500
          || assertion.completePartyGroupCount !== 500)) {
        return { outcome: "FAILED", reason: "ACCEPT_FINAL transition fact의 500 nonterminal/500 terminal 분포가 어긋났습니다." };
      }
    }
  }
  return { outcome: "RESPONSE_BASELINE_ACCEPTED" };
}

export function assertResponseArtifact(artifact, privateCanonicalManifest) {
  if (!GIT_SHA.test(artifact?.measuredGitCommitSha ?? "")) {
    return invalid("measuredGitCommitSha는 40자 git SHA여야 합니다.");
  }
  const fixture = artifact.fixture;
  if (!SHA_256.test(fixture?.fixtureInputSha256 ?? "") || !SHA_256.test(fixture?.materializedManifestSha256 ?? "")) {
    return invalid("fixture provenance digest가 올바르지 않습니다.");
  }
  if (typeof fixture.fixtureInput !== "string" || sha256(fixture.fixtureInput) !== fixture.fixtureInputSha256) {
    return invalid("fixture input digest가 실제 bytes와 다릅니다.");
  }
  if (typeof fixture.privateCanonicalManifestFile !== "string"
    || typeof privateCanonicalManifest !== "string"
    || sha256(privateCanonicalManifest) !== fixture.materializedManifestSha256) {
    return invalid("materialized manifest digest가 실제 bytes와 다릅니다.");
  }
  for (const scenario of artifact.scenarios ?? []) {
    for (const round of scenario.measuredRounds ?? []) {
      const actualDigest = sha256(JSON.stringify(round.rawSamples));
      if (round.rawDataSha256 !== actualDigest) {
        return invalid(`raw data digest가 실제 bytes와 다릅니다: ${scenario.scenario}/${round.round}`);
      }
    }
  }
  let sidecar;
  try {
    sidecar = JSON.parse(privateCanonicalManifest);
  } catch {
    return invalid("private sidecar JSON을 파싱할 수 없습니다.");
  }
  const sidecarReason = validatePrivateSidecar(artifact, sidecar);
  if (sidecarReason !== null) {
    return invalid(sidecarReason);
  }
  return evaluateResponseArtifact(artifact);
}

function validatePrivateSidecar(artifact, sidecar) {
  if (!Array.isArray(sidecar)) {
    return "private sidecar가 배열이 아닙니다.";
  }
  for (const scenario of artifact.scenarios ?? []) {
    for (const round of scenario.measuredRounds ?? []) {
      const expectedProposalCount = scenario.scenario === "ACCEPT_FINAL" ? 500 : 1_000;
      const expectedMemberCount = scenario.scenario === "ACCEPT_FINAL" ? 1_000 : 2_000;
      const rows = sidecar.filter((row) => row?.scenario === scenario.scenario
        && row.warmUp === false && row.round === round.round);
      if (rows.length !== expectedMemberCount) {
        return `${scenario.scenario}/${round.round} sidecar row 수가 정확하지 않습니다.`;
      }
      const keys = new Set();
      const proposalOrdinals = new Set();
      const memberOrdinals = new Set();
      const proposalRows = new Map();
      let memberMatches = 0;
      let requestMatches = 0;
      let queueMatches = 0;
      for (const row of rows) {
        if (!validSidecarRow(row)) {
          return `${scenario.scenario}/${round.round} sidecar row 형식이 올바르지 않습니다.`;
        }
        const key = `${row.scenario}/${row.warmUp}/${row.round}/${row.proposalOrdinal}/${row.memberOrdinal}`;
        if (keys.has(key)) {
          return `${scenario.scenario}/${round.round} sidecar run key가 중복되었습니다.`;
        }
        keys.add(key);
        proposalOrdinals.add(row.proposalOrdinal);
        memberOrdinals.add(`${row.proposalOrdinal}/${row.memberOrdinal}`);
        if (!proposalRows.has(row.proposalId)) {
          proposalRows.set(row.proposalId, []);
        }
        proposalRows.get(row.proposalId).push(row);
        memberMatches += Number(row.expected.memberResponseStatus === row.observed.memberResponseStatus);
        requestMatches += Number(row.expected.requestStatus === row.observed.requestStatus);
        queueMatches += Number(row.expected.queueTimestamp === row.observed.queueTimestamp);
      }
      if (proposalRows.size !== expectedProposalCount
        || [...proposalRows.values()].some((proposal) => proposal.length !== 2)) {
        return `${scenario.scenario}/${round.round} proposal/member sidecar coverage가 정확하지 않습니다.`;
      }
      for (let proposalOrdinal = 1; proposalOrdinal <= expectedProposalCount; proposalOrdinal += 1) {
        if (!proposalOrdinals.has(proposalOrdinal)
          || !memberOrdinals.has(`${proposalOrdinal}/1`)
          || !memberOrdinals.has(`${proposalOrdinal}/2`)) {
          return `${scenario.scenario}/${round.round} sidecar ordinal coverage가 정확하지 않습니다.`;
        }
      }
      let proposalMatches = 0;
      for (const proposal of proposalRows.values()) {
        const expectedStatus = proposal[0].expected.proposalStatus;
        if (proposal.some((row) => row.expected.proposalStatus !== expectedStatus
          || row.observed.proposalStatus !== expectedStatus)) {
          continue;
        }
        proposalMatches += 1;
      }
      const assertion = round.finalStateAssertion;
      if (assertion.proposalFactMatchCount !== proposalMatches
        || assertion.proposalFactMismatchCount !== expectedProposalCount - proposalMatches
        || assertion.memberFactMatchCount !== memberMatches
        || assertion.memberFactMismatchCount !== expectedMemberCount - memberMatches
        || assertion.requestFactMatchCount !== requestMatches
        || assertion.requestFactMismatchCount !== expectedMemberCount - requestMatches
        || assertion.queueTimestampMatchCount !== queueMatches
        || assertion.queueTimestampMismatchCount !== expectedMemberCount - queueMatches) {
        return `${scenario.scenario}/${round.round} sidecar와 public finalStateAssertion scalar가 다릅니다.`;
      }
    }
  }
  return null;
}

function validSidecarRow(row) {
  const idFields = ["proposalOrdinal", "memberOrdinal", "proposalId", "memberProposalId", "memberRequestId", "requestId"];
  const factFields = ["proposalStatus", "memberResponseStatus", "requestStatus", "queueTimestamp"];
  if (typeof row.scenario !== "string" || typeof row.warmUp !== "boolean" || !Number.isInteger(row.round)
    || !idFields.every((field) => Number.isInteger(row[field]) && row[field] > 0)
    || row.memberProposalId !== row.proposalId
    || !row.expected || !row.observed) {
    return false;
  }
  return factFields.every((field) => typeof row.expected[field] === "string" && row.expected[field].length > 0
    && typeof row.observed[field] === "string" && row.observed[field].length > 0);
}

function main() {
  const check = process.argv.includes("--check");
  const file = process.argv.slice(2).find((argument) => argument !== "--check");
  if (!file) {
    throw new Error("사용법: node scripts/measurements/match01-response-completion-report.mjs [--check] <artifact.json>");
  }
  let result;
  try {
    const artifact = JSON.parse(fs.readFileSync(file, "utf8"));
    const privateManifestPath = path.resolve(path.dirname(file), artifact.fixture.privateCanonicalManifestFile);
    const privateCanonicalManifest = fs.readFileSync(privateManifestPath, "utf8");
    result = assertResponseArtifact(artifact, privateCanonicalManifest);
  } catch (error) {
    result = invalid(`artifact 또는 private sidecar를 읽을 수 없습니다: ${error.message}`);
  }
  process.stdout.write(`${JSON.stringify(result)}\n`);
  if (check && result.outcome !== "RESPONSE_BASELINE_ACCEPTED") {
    process.exitCode = 1;
  }
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].replaceAll("\\", "/"))) {
  main();
}
