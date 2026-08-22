import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import { readFileSync, realpathSync, statSync } from "node:fs";
import path from "node:path";

const CANDIDATE_EVIDENCE_ID = "MATCH-01-CANDIDATE-CLAIM";
const INTEGRATION_EVIDENCE_IDS = ["MATCH-01-T1", "MATCH-01-T5", "MATCH-01-T6", "MATCH-01-T7"];
const RESPONSE_EVIDENCE_ID = "MATCH-01-RESPONSE-COMPLETION";
const CURRENT_STATE_EVIDENCE_ID = "MATCH-01-T12";
const COMMIT_SHA_PATTERN = /^[0-9a-f]{40}$/u;
const DIGEST_PATTERN = /^[0-9a-f]{64}$/u;
const MATCH_VERIFICATION_SOURCE_PATH_PREFIXES = [
  "src/main/java/cloud/bamsongi/albammate/matching/",
  "src/main/java/cloud/bamsongi/albammate/chat/match/",
  "src/main/java/cloud/bamsongi/albammate/infra/redis/RedisMatchChat",
  "src/test/java/cloud/bamsongi/albammate/matching/",
  "src/test/java/cloud/bamsongi/albammate/chat/match/",
  "src/test/java/cloud/bamsongi/albammate/infra/redis/RedisMatchChat",
  "src/postgresTest/java/cloud/bamsongi/albammate/matching/",
  "src/postgresTest/java/cloud/bamsongi/albammate/chat/match/",
];
const EXPECTED_PATH_PREFIXES = new Map([
  [CANDIDATE_EVIDENCE_ID, "docs/measurements/results/match-01/candidate-claim/"],
  ["MATCH-01-T1", "docs/measurements/results/match-01/integration/"],
  ["MATCH-01-T5", "docs/measurements/results/match-01/integration/"],
  ["MATCH-01-T6", "docs/measurements/results/match-01/integration/"],
  ["MATCH-01-T7", "docs/measurements/results/match-01/integration/"],
  [RESPONSE_EVIDENCE_ID, "docs/measurements/results/match-01/response-completion/"],
  [CURRENT_STATE_EVIDENCE_ID, "docs/measurements/results/match-01/integration/"],
]);

function invalid(reason) {
  return { outcome: "INVALID", reason };
}

function sha256(contents) {
  return createHash("sha256").update(contents).digest("hex");
}

function isRelativeRepositoryPath(value) {
  if (typeof value !== "string" || value.length === 0 || path.isAbsolute(value)) {
    return false;
  }
  const normalized = path.posix.normalize(value.replaceAll("\\", "/"));
  return normalized !== ".." && !normalized.startsWith("../");
}

function canonicalArtifactPath(repository, relativePath) {
  if (!isRelativeRepositoryPath(relativePath)) {
    throw new Error("artifact path는 repository 상대 경로여야 합니다.");
  }
  const canonicalRepository = realpathSync(repository);
  const candidate = path.resolve(canonicalRepository, relativePath);
  const canonicalArtifact = realpathSync(candidate);
  if (path.relative(canonicalRepository, canonicalArtifact).startsWith("..") || !statSync(canonicalArtifact).isFile()) {
    throw new Error("artifact path가 repository 밖을 가리킵니다.");
  }
  return canonicalArtifact;
}

function gitBlobBytes(repository, relativePath) {
  const blobId = execFileSync("git", ["-C", repository, "rev-parse", `HEAD:${relativePath}`], {
    encoding: "utf8",
  }).trim();
  return execFileSync("git", ["-C", repository, "cat-file", "blob", blobId], {
    maxBuffer: 64 * 1024 * 1024,
  });
}

function gitCommitExists(repository, commitSha) {
  execFileSync("git", ["-C", repository, "rev-parse", "--verify", `${commitSha}^{commit}`], { stdio: "ignore" });
}

function gitCommitIsInRepositoryHistory(repository, commitSha) {
  try {
    execFileSync("git", ["-C", repository, "merge-base", "--is-ancestor", commitSha, "HEAD"]);
    return true;
  } catch {
    return false;
  }
}

function gitChangedPathsFromCommitToWorktree(repository, commitSha) {
  const committedAndWorkingTreeChanges = execFileSync("git", [
    "-C",
    repository,
    "diff",
    "--name-only",
    "--no-renames",
    commitSha,
  ], { encoding: "utf8" });
  const untrackedFiles = execFileSync("git", [
    "-C",
    repository,
    "ls-files",
    "--others",
    "--exclude-standard",
  ], { encoding: "utf8" });
  return [...new Set(`${committedAndWorkingTreeChanges}\n${untrackedFiles}`
    .split(/\r?\n/u)
    .filter(Boolean)
    .map((changedPath) => changedPath.replaceAll("\\", "/")))];
}

function changedMatchVerificationSourcePaths(repository, commitSha) {
  return gitChangedPathsFromCommitToWorktree(repository, commitSha)
    .filter((changedPath) => MATCH_VERIFICATION_SOURCE_PATH_PREFIXES
      .some((prefix) => changedPath.startsWith(prefix)));
}

function artifactOutcome(artifact, evidenceId) {
  if (evidenceId === CANDIDATE_EVIDENCE_ID) {
    return artifact?.decision?.outcome === "BASELINE_ACCEPTED" ? "ACCEPTED" : artifact?.decision?.outcome;
  }
  if (evidenceId === RESPONSE_EVIDENCE_ID) {
    return artifact?.decision?.outcome === "RESPONSE_BASELINE_ACCEPTED" ? "ACCEPTED" : artifact?.decision?.outcome;
  }
  return artifact?.outcome;
}

function validateEvidence(repository, gateSha, evidence, expectedId, requiresGateSha) {
  if (!evidence || evidence.id !== expectedId) {
    return invalid(`${expectedId} evidence ID가 올바르지 않습니다.`);
  }
  if (!DIGEST_PATTERN.test(evidence.gitCanonicalBlobSha256) || !DIGEST_PATTERN.test(evidence.artifactSha256)) {
    return invalid(`${expectedId} evidence digest 형식이 올바르지 않습니다.`);
  }
  const normalizedPath = evidence.path?.replaceAll("\\", "/");
  const expectedPathPrefix = EXPECTED_PATH_PREFIXES.get(expectedId);
  if (!expectedPathPrefix || !normalizedPath?.startsWith(expectedPathPrefix)) {
    return invalid(`${expectedId} artifact path가 소유 evidence 경로와 다릅니다.`);
  }

  let artifactBytes;
  let blobBytes;
  try {
    const artifactPath = canonicalArtifactPath(repository, evidence.path);
    artifactBytes = readFileSync(artifactPath);
    blobBytes = gitBlobBytes(repository, evidence.path.replaceAll("\\", "/"));
  } catch (error) {
    return invalid(`${expectedId} artifact를 확인할 수 없습니다: ${error.message}`);
  }
  if (sha256(blobBytes) !== evidence.gitCanonicalBlobSha256) {
    return invalid(`${expectedId} Git blob SHA-256이 일치하지 않습니다.`);
  }
  if (sha256(artifactBytes) !== evidence.artifactSha256) {
    return invalid(`${expectedId} artifact digest가 일치하지 않습니다.`);
  }

  let artifact;
  try {
    artifact = JSON.parse(artifactBytes.toString("utf8"));
  } catch (error) {
    return invalid(`${expectedId} artifact JSON이 올바르지 않습니다.`);
  }
  if (!COMMIT_SHA_PATTERN.test(artifact.measuredGitCommitSha ?? "")) {
    return invalid(`${expectedId} artifact measuredGitCommitSha 형식이 올바르지 않습니다.`);
  }
  try {
    gitCommitExists(repository, artifact.measuredGitCommitSha);
  } catch (error) {
    return invalid(`${expectedId} artifact measuredGitCommitSha commit을 확인할 수 없습니다: ${error.message}`);
  }
  if (!gitCommitIsInRepositoryHistory(repository, artifact.measuredGitCommitSha)) {
    return invalid(`${expectedId} artifact measuredGitCommitSha가 현재 저장소 이력에 없습니다.`);
  }
  if (requiresGateSha && artifact.measuredGitCommitSha !== gateSha) {
    return invalid(`${expectedId} measuredGitCommitSha가 gate와 다릅니다.`);
  }

  const outcome = artifactOutcome(artifact, expectedId);
  if (outcome === "FAILED") {
    return { outcome: "FAILED", reason: `${expectedId} artifact가 FAILED입니다.` };
  }
  if (outcome !== "ACCEPTED" && outcome !== "PASSED") {
    return invalid(`${expectedId} artifact 결과가 완료되지 않았습니다.`);
  }
  return { outcome: "ACCEPTED" };
}

function validateShape(gate) {
  if (!gate || !COMMIT_SHA_PATTERN.test(gate.measuredGitCommitSha)) {
    return invalid("gate measuredGitCommitSha는 40자 소문자 Git SHA여야 합니다.");
  }
  if (!Array.isArray(gate.integrationEvidence) || gate.integrationEvidence.length !== INTEGRATION_EVIDENCE_IDS.length) {
    return invalid("candidate gate에는 T1·T5·T6·T7 evidence가 정확히 하나씩 필요합니다.");
  }

  const allEvidence = [
    gate.candidateClaim,
    ...gate.integrationEvidence,
    gate.responseCompletionConsumer,
    gate.currentStateConsumer,
  ];
  const ids = allEvidence.map((evidence) => evidence?.id);
  const paths = allEvidence.map((evidence) => evidence?.path);
  if (new Set(ids).size !== ids.length || new Set(paths).size !== paths.length) {
    return invalid("evidence ID 또는 artifact path가 중복되었습니다.");
  }
  if (gate.candidateClaim?.id !== CANDIDATE_EVIDENCE_ID
    || gate.responseCompletionConsumer?.id !== RESPONSE_EVIDENCE_ID
    || gate.currentStateConsumer?.id !== CURRENT_STATE_EVIDENCE_ID) {
    return invalid("candidate·response·T12 consumer ID가 계약과 다릅니다.");
  }
  const integrationIds = gate.integrationEvidence.map((evidence) => evidence?.id).sort();
  if (JSON.stringify(integrationIds) !== JSON.stringify([...INTEGRATION_EVIDENCE_IDS].sort())) {
    return invalid("candidate gate 입력은 T1·T5·T6·T7만 포함해야 합니다.");
  }
  return { outcome: "ACCEPTED" };
}

export function evaluateIntegrationGate(repository, gate) {
  const shape = validateShape(gate);
  if (shape.outcome !== "ACCEPTED") {
    return shape;
  }
  try {
    gitCommitExists(repository, gate.measuredGitCommitSha);
  } catch (error) {
    return invalid(`gate measuredGitCommitSha commit을 확인할 수 없습니다: ${error.message}`);
  }
  if (!gitCommitIsInRepositoryHistory(repository, gate.measuredGitCommitSha)) {
    return invalid("gate measuredGitCommitSha가 현재 저장소 이력에 없습니다.");
  }
  const changedSourcePaths = changedMatchVerificationSourcePaths(repository, gate.measuredGitCommitSha);
  if (changedSourcePaths.length > 0) {
    return invalid(
      `gate measuredGitCommitSha 이후 MATCH 검증 대상 코드가 변경되었습니다: ${changedSourcePaths.join(", ")}`,
    );
  }

  const evidenceById = new Map([
    [CANDIDATE_EVIDENCE_ID, gate.candidateClaim],
    ...gate.integrationEvidence.map((evidence) => [evidence.id, evidence]),
    [RESPONSE_EVIDENCE_ID, gate.responseCompletionConsumer],
    [CURRENT_STATE_EVIDENCE_ID, gate.currentStateConsumer],
  ]);
  let failed = null;
  for (const evidenceId of [
    CANDIDATE_EVIDENCE_ID,
    ...INTEGRATION_EVIDENCE_IDS,
    RESPONSE_EVIDENCE_ID,
    CURRENT_STATE_EVIDENCE_ID,
  ]) {
    const result = validateEvidence(
      repository,
      gate.measuredGitCommitSha,
      evidenceById.get(evidenceId),
      evidenceId,
      evidenceId === CANDIDATE_EVIDENCE_ID || INTEGRATION_EVIDENCE_IDS.includes(evidenceId),
    );
    if (result.outcome === "INVALID") {
      return result;
    }
    if (result.outcome === "FAILED") {
      failed = result;
    }
  }
  return failed ?? { outcome: "ACCEPTED" };
}

function main() {
  const [option, gatePath] = process.argv.slice(2);
  if (option !== "--check" || !gatePath || process.argv.length !== 4) {
    throw new Error("사용법: node scripts/measurements/match01-integration-gate.mjs --check <gate-manifest.json>");
  }
  const repository = process.cwd();
  let gate;
  try {
    gate = JSON.parse(readFileSync(gatePath, "utf8"));
  } catch (error) {
    const result = invalid(`gate manifest를 읽을 수 없습니다: ${error.message}`);
    process.stdout.write(`${JSON.stringify(result)}\n`);
    process.exitCode = 1;
    return;
  }
  const result = evaluateIntegrationGate(repository, gate);
  process.stdout.write(`${JSON.stringify(result)}\n`);
  if (result.outcome !== "ACCEPTED") {
    process.exitCode = 1;
  }
}

if (process.argv[1]?.endsWith("match01-integration-gate.mjs")) {
  main();
}
