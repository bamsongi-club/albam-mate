import { createHash } from "node:crypto";
import { readFileSync, writeFileSync } from "node:fs";

const MEASURED_ROUND_COUNT = 3;
const LOGICAL_CLAIM_COUNT = 1_000;
const REQUIRED_PROCESS_COUNT = 2;
const FIXTURE_GENERATOR = "MATCH-01-CANDIDATE-BASELINE-V2";
const FIXTURE_BASE_TIME_MS = Date.UTC(2026, 0, 1);
const FIXTURE_MANIFEST_FIELDS = [
  "fixtureOrdinal", "userFixtureOrdinal", "queuedAt", "prioritySince", "minPartySize", "maxPartySize",
  "userId", "requestId", "expectedTieOrder",
];

export function nearestRank(values, percentile) {
  if (!Array.isArray(values) || values.length === 0) {
    throw new Error("nearest-rank에는 하나 이상의 값이 필요합니다.");
  }
  if (percentile <= 0 || percentile > 1) {
    throw new Error("percentile은 0보다 크고 1 이하여야 합니다.");
  }
  const sorted = [...values].sort((left, right) => left - right);
  return sorted[Math.ceil(percentile * sorted.length) - 1];
}

function reportRound(round) {
  const matcherProcesses = Array.isArray(round?.matcherProcesses) ? round.matcherProcesses : [];
  const logicalClaims = Array.isArray(round?.logicalClaims) ? round.logicalClaims : [];
  const topology = round?.topology ?? round?.topologyEvidence;
  const durations = logicalClaims.map((claim) => claim.durationNanos);
  return {
    round: round?.round,
    fixtureEvidence: {
      generator: round?.fixtureEvidence?.generator,
      fixtureInputSha256: round?.fixtureEvidence?.fixtureInputSha256,
      materializedManifestSha256: round?.fixtureEvidence?.materializedManifestSha256,
    },
    topology: {
      matcherCount: topology?.matcherCount,
      claimAttempts: topology?.claimAttempts,
      configurationSha: topology?.configurationSha,
    },
    matcherProcesses: matcherProcesses.map((process) => ({
      pid: process?.pid,
      exitCode: process?.exitCode,
      completed: process?.completed,
    })),
    logicalClaims: logicalClaims.map((claim) => ({
      matcherPid: claim?.matcherPid,
      durationNanos: claim?.durationNanos,
      retryCount: claim?.retryCount,
      retryRawDurationsNanos: Array.isArray(claim?.retryRawDurationsNanos)
        ? [...claim.retryRawDurationsNanos]
        : null,
    })),
    throughputPerSecond: round?.throughputPerSecond,
    pgStatStatements: round?.pgStatStatements,
    lockSamples: round?.lockSamples,
    queryPlan: round?.queryPlan,
    correctnessInput: round?.correctnessInput,
    integrity: round?.integrity,
    statistics: durations.length === 0
      ? { p50Nanos: null, p95Nanos: null, p99Nanos: null }
      : {
        p50Nanos: nearestRank(durations, 0.5),
        p95Nanos: nearestRank(durations, 0.95),
        p99Nanos: nearestRank(durations, 0.99),
      },
  };
}

function fixtureSummary(fixture) {
  const manifest = Array.isArray(fixture?.manifest) ? fixture.manifest : [];
  return {
    generator: fixture?.generator,
    fixtureInputSha256: fixture?.fixtureInputSha256,
    materializedManifestSha256: fixture?.materializedManifestSha256,
    inputCsv: fixture?.inputCsv,
    manifest: manifest.map((entry) => Object.fromEntries(
      FIXTURE_MANIFEST_FIELDS.map((field) => [field, entry?.[field]]),
    )),
  };
}

export function buildCandidateBaselineReport(input) {
  const { fixture, warmUp, measured } = input ?? {};
  const measuredRounds = Array.isArray(measured) ? measured.map(reportRound) : [];
  const p95Values = measuredRounds.length === MEASURED_ROUND_COUNT
    ? measuredRounds.map((round) => round.statistics.p95Nanos).sort((left, right) => left - right)
    : [];
  return {
    schemaVersion: 1,
    fixture: fixtureSummary(fixture),
    warmUp: { countsTowardBaseline: false, round: reportRound(warmUp) },
    measuredRounds,
    series: {
      p95MedianNanos: p95Values[1],
      p95MaximumNanos: p95Values[2],
    },
  };
}

function hasCompleteObservations(round) {
  return Array.isArray(round.matcherProcesses)
    && round.matcherProcesses.length === REQUIRED_PROCESS_COUNT
    && new Set(round.matcherProcesses.map((process) => process.pid)).size === REQUIRED_PROCESS_COUNT
    && round.matcherProcesses.every((process) => Number.isInteger(process.pid) && process.pid > 0
      && process.exitCode === 0 && process.completed === true)
    && Array.isArray(round.logicalClaims)
    && round.logicalClaims.length === LOGICAL_CLAIM_COUNT
    && round.logicalClaims.every((claim) => Number.isInteger(claim.matcherPid) && claim.matcherPid > 0
      && Number.isFinite(claim.durationNanos)
      && Number.isInteger(claim.retryCount)
      && claim.retryCount >= 0
      && Array.isArray(claim.retryRawDurationsNanos)
      && claim.retryRawDurationsNanos.length === claim.retryCount
      && claim.retryRawDurationsNanos.every(Number.isFinite))
    && hasExpectedMatcherClaimDistribution(round)
    && Number.isFinite(round.throughputPerSecond)
    && Number.isFinite(round.pgStatStatements?.calls)
    && Number.isFinite(round.pgStatStatements?.totalExecutionTimeMs)
    && Number.isFinite(round.pgStatStatements?.rows)
    && Number.isFinite(round.pgStatStatements?.sharedBlockHits)
    && Number.isFinite(round.pgStatStatements?.sharedBlockReads)
    && hasContractCandidateStatements(round.pgStatStatements?.candidateStatements)
    && round.lockSamples?.intervalMs === 10
    && isOrderedUtcWindow(round.lockSamples.observationStartedAtUtc, round.lockSamples.observationFinishedAtUtc)
    && Number.isInteger(round.lockSamples.snapshotCount)
    && round.lockSamples.snapshotCount > 0
    && Number.isInteger(round.lockSamples.lockWaitSnapshotCount)
    && round.lockSamples.lockWaitSnapshotCount >= 0
    && round.lockSamples.samplingFailure === null
    && typeof round.queryPlan === "string"
    && round.queryPlan.length > 0
    && Number.isFinite(round.correctnessInput?.proposalCount)
    && Number.isFinite(round.correctnessInput?.memberCount)
    && Number.isFinite(round.correctnessInput?.claimedRequestCount)
    && Number.isFinite(round.correctnessInput?.waitingRequestCount)
    && Number.isFinite(round.correctnessInput?.duplicateClaimCount)
    && Number.isFinite(round.correctnessInput?.partialClaimCount)
    && typeof round.correctnessInput?.tieOrderMatches === "boolean"
    && hasCompleteTiePairResults(round.correctnessInput?.tiePairResults);
}

function hasExpectedMatcherClaimDistribution(round) {
  const processPids = new Set(round.matcherProcesses.map((process) => process.pid));
  if (processPids.size !== round.topology.matcherCount
    || round.logicalClaims.some((claim) => !processPids.has(claim.matcherPid))) return false;
  const attemptsByMatcher = new Map(round.matcherProcesses.map((process) => [process.pid, 0]));
  for (const claim of round.logicalClaims) {
    attemptsByMatcher.set(claim.matcherPid, attemptsByMatcher.get(claim.matcherPid) + 1);
  }
  return [...attemptsByMatcher.values()].every((attempts) => attempts === round.topology.claimAttempts);
}

function hasCompleteRoundEvidence(report) {
  const rounds = [report.warmUp?.round, ...report.measuredRounds];
  if (rounds.some((round) => !hasRoundFixtureEvidence(round, report.fixture)
    || !hasContractTopology(round?.topology))) return false;
  const canonicalTopology = rounds[0]?.topology;
  return rounds.every((round) => round.topology.matcherCount === canonicalTopology.matcherCount
    && round.topology.claimAttempts === canonicalTopology.claimAttempts
    && round.topology.configurationSha === canonicalTopology.configurationSha);
}

function hasRoundFixtureEvidence(round, fixture) {
  const evidence = round?.fixtureEvidence;
  return evidence?.generator === fixture.generator
    && evidence.fixtureInputSha256 === fixture.fixtureInputSha256
    && evidence.materializedManifestSha256 === fixture.materializedManifestSha256;
}

function hasContractTopology(topology) {
  return topology?.matcherCount === REQUIRED_PROCESS_COUNT
    && topology.claimAttempts === LOGICAL_CLAIM_COUNT / REQUIRED_PROCESS_COUNT
    && typeof topology.configurationSha === "string"
    && /^[a-f0-9]{64}$/.test(topology.configurationSha);
}

function isOrderedUtcWindow(startedAtUtc, finishedAtUtc) {
  if (typeof startedAtUtc !== "string" || typeof finishedAtUtc !== "string") return false;
  const startedAt = Date.parse(startedAtUtc);
  const finishedAt = Date.parse(finishedAtUtc);
  return Number.isFinite(startedAt) && Number.isFinite(finishedAt) && startedAt <= finishedAt;
}

function hasCompleteTiePairResults(tiePairResults) {
  return Array.isArray(tiePairResults)
    && tiePairResults.length === 100
    && tiePairResults.every((result, pair) => result?.firstFixtureOrdinal === (pair * 2) + 1
      && result?.secondFixtureOrdinal === (pair * 2) + 2
      && typeof result?.sameProposal === "boolean");
}

function hasContractCandidateStatements(candidateStatements) {
  return Array.isArray(candidateStatements)
    && candidateStatements.length > 0
    && candidateStatements.every((statement) => typeof statement?.query === "string"
      && Number.isInteger(statement?.calls) && statement.calls > 0
      && normalizeCandidateQuery(statement.query).includes("order by priority_since asc, id asc")
      && normalizeCandidateQuery(statement.query).includes("for update skip locked"));
}

function normalizeCandidateQuery(query) {
  return query.toLowerCase().replaceAll(/\s+/g, " ").trim();
}

function fixtureTimestamp(ordinal) {
  const prioritySecond = ordinal <= 200 ? Math.ceil(ordinal / 2) : ordinal;
  return new Date(FIXTURE_BASE_TIME_MS + (prioritySecond * 1_000)).toISOString().replace(".000Z", "Z");
}

function hasCompleteFixture(fixture) {
  if (fixture?.generator !== FIXTURE_GENERATOR || typeof fixture?.fixtureInputSha256 !== "string" || typeof fixture?.inputCsv !== "string"
    || fixture.fixtureInputSha256 !== fixtureInputSha256(fixture.inputCsv) || typeof fixture.materializedManifestSha256 !== "string" || !Array.isArray(fixture.manifest)) return false;
  if (fixture.inputCsv.includes("\r") || !fixture.inputCsv.endsWith("\n")) return false;
  const lines = fixture.inputCsv.trimEnd().split("\n");
  if (lines.length !== 1_001 || lines[0] !== "fixtureOrdinal,userFixtureOrdinal,queuedAt,prioritySince,minPartySize,maxPartySize") return false;
  const userIds = new Set(); const requestIds = new Set(); const tiePairs = [];
  for (let index = 0; index < 1_000; index += 1) {
    const values = lines[index + 1].split(","); const entry = fixture.manifest[index];
    if (values.length !== 6 || !entry || !FIXTURE_MANIFEST_FIELDS.every((field) => entry[field] !== undefined)) return false;
    const [fixtureOrdinal, userFixtureOrdinal, queuedAt, prioritySince, minPartySize, maxPartySize] = values;
    const ordinal = index + 1;
    const expectedTimestamp = fixtureTimestamp(ordinal);
    if (fixtureOrdinal !== String(ordinal) || userFixtureOrdinal !== String(ordinal)
      || queuedAt !== expectedTimestamp || prioritySince !== expectedTimestamp
      || minPartySize !== "2" || maxPartySize !== "4"
      || entry.fixtureOrdinal !== Number(fixtureOrdinal) || entry.userFixtureOrdinal !== Number(userFixtureOrdinal)
      || entry.queuedAt !== queuedAt || entry.prioritySince !== prioritySince
      || entry.minPartySize !== Number(minPartySize) || entry.maxPartySize !== Number(maxPartySize)
      || !Number.isInteger(entry.expectedTieOrder) || entry.expectedTieOrder !== entry.fixtureOrdinal
      || !Number.isInteger(entry.userId) || entry.userId <= 0 || !Number.isInteger(entry.requestId) || entry.requestId <= 0
      || !userIds.add(entry.userId) || !requestIds.add(entry.requestId)) return false;
    if (index < 200 && index % 2 === 1) tiePairs.push([fixture.manifest[index - 1], entry]);
  }
  const manifestBytes = `${FIXTURE_MANIFEST_FIELDS.join(",")}\n${fixture.manifest.map((entry) => FIXTURE_MANIFEST_FIELDS.map((field) => entry[field]).join(",")).join("\n")}\n`;
  return fixture.manifest.length === 1_000 && fixture.materializedManifestSha256 === fixtureInputSha256(manifestBytes) && tiePairs.length === 100
    && tiePairs.every(([left, right]) => left.prioritySince === right.prioritySince && left.requestId < right.requestId);
}

function hasCandidateClaimIntegrity(round) {
  const correctness = round.correctnessInput;
  return correctness.proposalCount === 500 && correctness.memberCount === 1_000
    && correctness.claimedRequestCount === 1_000 && correctness.waitingRequestCount === 0
    && correctness.duplicateClaimCount === 0 && correctness.partialClaimCount === 0
    && correctness.tieOrderMatches === true
    && correctness.tiePairResults.every((result) => result.sameProposal === true);
}

function hasIntegrityMismatch(round) {
  const { integrity, correctnessInput } = round;
  return integrity !== undefined && ["proposalCount", "memberCount", "claimedRequestCount", "duplicateClaimCount", "partialClaimCount", "tieOrderMatches"]
    .some((field) => integrity[field] !== correctnessInput[field]);
}

export function evaluateCandidateBaseline(report) {
  if (!report || typeof report !== "object" || !hasCompleteFixture(report.fixture)) {
    return { outcome: "INVALID", reason: "fixture input 또는 materialized manifest가 완결되지 않았습니다." };
  }
  if (!Array.isArray(report.measuredRounds) || report.measuredRounds.length !== MEASURED_ROUND_COUNT) {
    return { outcome: "INVALID", reason: "measured round가 3회가 아닙니다." };
  }
  if (report.warmUp?.countsTowardBaseline !== false || !hasCompleteRoundEvidence(report)) {
    return { outcome: "INVALID", reason: "warm-up 또는 round fixture/topology 증거가 완결되지 않았습니다." };
  }
  if (report.measuredRounds.some((round) => !hasCompleteObservations(round))) {
    return { outcome: "INVALID", reason: "실행 또는 관측 원자료가 완결되지 않았습니다." };
  }
  if (report.measuredRounds.some(hasIntegrityMismatch)) return { outcome: "INVALID", reason: "integrity가 correctnessInput과 다릅니다." };
  if (report.measuredRounds.some((round) => !hasCandidateClaimIntegrity(round))) {
    return { outcome: "FAILED", reason: "완결 실행에서 candidate claim 정합성이 어긋났습니다." };
  }
  return { outcome: "BASELINE_ACCEPTED" };
}

export function fixtureInputSha256(csv) {
  return createHash("sha256").update(csv, "utf8").digest("hex");
}

if (process.argv[1]?.endsWith("match01-candidate-baseline-report.mjs")) {
  const inputIndex = process.argv.indexOf("--input");
  const outputIndex = process.argv.indexOf("--output");
  if (inputIndex < 0 || outputIndex < 0 || !process.argv[inputIndex + 1] || !process.argv[outputIndex + 1]) {
    throw new Error("--input과 --output이 필요합니다.");
  }
  const input = JSON.parse(readFileSync(process.argv[inputIndex + 1], "utf8"));
  const report = buildCandidateBaselineReport(input);
  writeFileSync(process.argv[outputIndex + 1], JSON.stringify({ report, decision: evaluateCandidateBaseline(report) }, null, 2));
}
