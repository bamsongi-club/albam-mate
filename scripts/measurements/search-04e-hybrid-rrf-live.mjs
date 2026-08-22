#!/usr/bin/env node

import { execFile, execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { performance } from "node:perf_hooks";
import { promisify } from "node:util";
import { fileURLToPath } from "node:url";

const execFileAsync = promisify(execFile);
const REPOSITORY_ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), "../..");

export const LIVE_EVIDENCE_KIND = "search-04e-hybrid-rrf-live-evidence";
export const LIVE_EVIDENCE_SCHEMA_VERSION = 2;
export const SEARCH_TEXT_ROW_COUNT = 1000;
export const MEASURE_ROUNDS = 5;
export const CANDIDATE_K_VALUES = Object.freeze([50, 100, 200, 400, 800, 1000]);
export const RRF_K_VALUES = Object.freeze([10, 30, 60, 100, 200]);
export const TIMEOUT_SECONDS_VALUES = Object.freeze([1, 2, 3, 4, 5, 6]);
export const SELECTED_CANDIDATE_K = 200;
export const SELECTED_RRF_K = 60;
export const SELECTED_TIMEOUT_SECONDS = 6;
export const PROVIDER_TIMEOUT_MS = 5000;
export const COMMON_DEADLINE_MS = 6000;
export const DENSE_CANDIDATE_LIMIT = 1000;
export const SERVING_SPARSE_CANDIDATE_LIMIT = 200;
export const DENSE_EVIDENCE_SOURCE = "CloudflareWorkersAI-direct-REST";
export const DENSE_QUERY_SOURCE = "PgVectorDenseCandidateSource-production-SQL";
export const SPARSE_EVIDENCE_SOURCE = "StructuredSparseCandidateSource-production-SQL";
export const STONE_AGE_GAME_ID = 34635;

export const QUERY_FIXTURES = Object.freeze([
    Object.freeze({ id: "STONE-01", query: "일꾼 놓고 밥 먹이는 게임", anchorGameId: STONE_AGE_GAME_ID }),
    Object.freeze({ id: "STONE-02", query: "일꾼 배치하고 식량으로 부족을 부양하는 게임", anchorGameId: STONE_AGE_GAME_ID }),
    Object.freeze({ id: "STONE-03", query: "place workers and feed your population", anchorGameId: STONE_AGE_GAME_ID }),
    Object.freeze({ id: "STONE-04", query: "worker placement and food management game", anchorGameId: STONE_AGE_GAME_ID }),
    Object.freeze({ id: "COMMON-01", query: "게임", anchorGameId: null }),
    Object.freeze({ id: "COMMON-02", query: "game", anchorGameId: null }),
    Object.freeze({ id: "COMMON-03", query: "플레이어", anchorGameId: null }),
    Object.freeze({ id: "COMMON-04", query: "카드 게임", anchorGameId: null }),
]);

export const EXECUTION_REQUEST_COUNT = QUERY_FIXTURES.length * MEASURE_ROUNDS;

const TOKEN_SPLIT = /[^\p{L}\p{Nd}]+/u;
const MIN_TOKEN_LENGTH = 2;
const DEFAULT_SEARCH_TEXT = "docs/p2/search-evaluation/dense-bge-m3/search-text-top1000.json";
const DEFAULT_OUTPUT = "docs/measurements/search-04e-hybrid-rrf-live.json";
const DEFAULT_MANIFEST = "docs/measurements/search-04e-hybrid-rrf-live.manifest.json";
// 이 값은 manifest 파일 자체의 bytes checksum이다. manifest를 바꾸면 이 상수도 함께 바꿔야 한다.
const PINNED_MANIFEST_SHA256 = "1e3a19cbbee508ec2d65fd35f14bccb543136f0fd9cd3edf90d30f3c0fed5bf5";
const MODEL = Object.freeze({
    provider: "cloudflare-workers-ai",
    model: "@cf/baai/bge-m3",
    mode: "text",
    dimension: 1024,
    l2Normalized: true,
});

export function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
}

export function gameIdMembershipSha256(gameIds) {
    return sha256(Buffer.from([...new Set(gameIds)].sort((left, right) => left - right)
        .map((gameId) => `${gameId},`).join(""), "utf8"));
}

export function summarize(values) {
    if (!Array.isArray(values) || values.length === 0) {
        throw new Error("latency sample이 비어 있습니다.");
    }
    const sorted = [...values].sort((left, right) => left - right);
    return {
        count: sorted.length,
        p50: roundMillis(percentile(sorted, 50)),
        p95: roundMillis(percentile(sorted, 95)),
        max: roundMillis(sorted[sorted.length - 1]),
    };
}

function canonicalize(value) {
    if (Array.isArray(value)) return value.map((item) => canonicalize(item));
    if (value !== null && typeof value === "object") {
        return Object.fromEntries(Object.keys(value).sort().map((key) => [key, canonicalize(value[key])]));
    }
    return value;
}

function resultPayload(evidence) {
    const execution = structuredClone(evidence.execution);
    delete execution.startedAt;
    delete execution.completedAt;
    delete execution.resultSha256;
    return canonicalize(execution);
}

export function resultSha256(evidence) {
    return sha256(Buffer.from(JSON.stringify(resultPayload(evidence)), "utf8"));
}

function repositoryPath(relativePath) {
    if (typeof relativePath !== "string" || path.isAbsolute(relativePath)) {
        throw new Error(`repository 상대 경로가 아닙니다: ${String(relativePath)}`);
    }
    const resolved = path.resolve(REPOSITORY_ROOT, relativePath);
    const relative = path.relative(REPOSITORY_ROOT, resolved);
    if (!relative || relative.startsWith("..") || path.isAbsolute(relative)) {
        throw new Error(`repository 밖의 경로입니다: ${relativePath}`);
    }
    const stat = fs.statSync(resolved);
    if (!stat.isFile()) throw new Error(`regular file이 아닙니다: ${relativePath}`);
    return resolved;
}

function readPinnedFile(descriptor, field) {
    assertObject(descriptor, field);
    assertSha256(descriptor.sha256, `${field}.sha256`);
    const filePath = repositoryPath(descriptor.path);
    const bytes = fs.readFileSync(filePath);
    assertEqual(sha256(bytes), descriptor.sha256, `${field} 실제 bytes checksum이 다릅니다.`);
    return {
        descriptor,
        filePath,
        bytes,
        value: JSON.parse(bytes.toString("utf8")),
    };
}

function readPinnedInputs(manifestPath = path.join(REPOSITORY_ROOT, DEFAULT_MANIFEST)) {
    const absoluteManifestPath = repositoryPath(path.relative(REPOSITORY_ROOT, path.resolve(manifestPath)));
    const manifestBytes = fs.readFileSync(absoluteManifestPath);
    const manifestSha256 = sha256(manifestBytes);
    assertEqual(manifestSha256, PINNED_MANIFEST_SHA256, "live evidence execution manifest checksum이 고정값과 다릅니다.");
    const manifest = JSON.parse(manifestBytes.toString("utf8"));
    assertEqual(manifest.schemaVersion, 1, "execution manifest schemaVersion은 1이어야 합니다.");
    assertEqual(manifest.kind, "search-04e-hybrid-rrf-live-execution-manifest",
        "execution manifest kind가 올바르지 않습니다.");
    const catalogRelease = readPinnedFile(manifest.catalogRelease, "catalogRelease");
    const qualityCorpus = readPinnedFile(manifest.qualityCorpus, "qualityCorpus");
    const searchText = readPinnedFile(manifest.searchText, "searchText");
    const executionInput = readPinnedFile(manifest.executionInput, "executionInput");
    const result = manifest.result;
    assertObject(result, "result manifest");
    assertSha256(result.sha256, "result.sha256");
    return {
        manifest,
        manifestPath: absoluteManifestPath,
        manifestRelativePath: path.relative(REPOSITORY_ROOT, absoluteManifestPath),
        manifestSha256,
        catalogRelease,
        qualityCorpus,
        searchText,
        executionInput,
    };
}

function validatePinnedFiles(pinned) {
    const catalog = pinned.catalogRelease.value;
    assertEqual(catalog.schemaVersion, 1, "catalog release schemaVersion이 올바르지 않습니다.");
    assertEqual(catalog.kind, "catalog-dataset-release", "catalog release kind가 올바르지 않습니다.");
    assertEqual(catalog.releaseId, pinned.manifest.catalogRelease.releaseId,
        "catalog release ID가 execution manifest와 다릅니다.");
    assertEqual(catalog.datasetId, pinned.manifest.catalogRelease.datasetId,
        "catalog dataset ID가 execution manifest와 다릅니다.");
    assertEqual(catalog.fieldVersion, pinned.manifest.catalogRelease.fieldVersion,
        "catalog field version이 execution manifest와 다릅니다.");
    assertEqual(catalog.approved, true, "catalog release가 approved가 아닙니다.");
    assertEqual(catalog.testOnly, false, "test-only catalog release를 사용할 수 없습니다.");

    const qualityCorpus = pinned.qualityCorpus.value;
    if (!Array.isArray(qualityCorpus.members) || qualityCorpus.members.length !== SEARCH_TEXT_ROW_COUNT) {
        throw new Error("quality corpus membership가 정확히 1,000개가 아닙니다.");
    }
    const qualityGameIds = qualityCorpus.members.map((member) => member.gameId);
    assertEqual(gameIdMembershipSha256(qualityGameIds), pinned.manifest.qualityCorpus.gameIdMembershipSha256,
        "quality corpus membership checksum이 고정값과 다릅니다.");

    const searchText = pinned.searchText.value;
    validateSearchText(searchText);
    const searchTextGameIds = searchText.games.map((game) => game.gameId);
    assertEqual(gameIdMembershipSha256(searchTextGameIds), pinned.manifest.searchText.gameIdMembershipSha256,
        "search_text membership checksum이 고정값과 다릅니다.");
    assertEqual(gameIdMembershipSha256(searchTextGameIds), gameIdMembershipSha256(qualityGameIds),
        "quality corpus와 search_text membership이 다릅니다.");

    const executionInput = pinned.executionInput.value;
    assertEqual(executionInput.datasetRelease?.releaseId, pinned.manifest.catalogRelease.releaseId,
        "execution input의 release ID가 다릅니다.");
    assertEqual(executionInput.datasetRelease?.manifestSha256, pinned.catalogRelease.descriptor.sha256,
        "execution input이 다른 catalog release manifest를 참조합니다.");
    assertEqual(executionInput.sources?.corpus?.sha256, pinned.qualityCorpus.descriptor.sha256,
        "execution input의 quality corpus checksum이 다릅니다.");
    assertEqual(executionInput.outputs?.searchText?.sha256, pinned.searchText.descriptor.sha256,
        "execution input의 search_text checksum이 다릅니다.");
}

function validateRunnerProvenance(evidence, pinned) {
    const runner = evidence.runner;
    assertObject(runner, "runner");
    assertEqual(evidence.sourceGitHead, pinned.manifest.runner.sourceGitHead,
        "sourceGitHead가 고정된 실행 commit과 다릅니다.");
    assertEqual(runner.sourceGitHead, pinned.manifest.runner.sourceGitHead,
        "runner sourceGitHead가 고정된 실행 commit과 다릅니다.");
    assertEqual(runner.path, pinned.manifest.runner.path, "runner 경로가 고정값과 다릅니다.");
    assertEqual(runner.fileSha256, pinned.manifest.runner.fileSha256,
        "runner 파일 checksum이 고정값과 다릅니다.");
    assertEqual(runner.sourceClean, pinned.manifest.runner.sourceClean,
        "runner source clean 상태가 고정값과 다릅니다.");
    if (runner.sourceClean !== true) throw new Error("dirty source에서 생성한 evidence는 허용하지 않습니다.");
    if (!/^[a-f0-9]{40}$/u.test(runner.sourceGitHead)) {
        throw new Error("runner sourceGitHead가 40자리 Git SHA-1이 아닙니다.");
    }
    try {
        const runnerBytes = execFileSync("git", ["show", `${runner.sourceGitHead}:${runner.path}`], {
            cwd: REPOSITORY_ROOT,
            encoding: "buffer",
        });
        assertEqual(sha256(runnerBytes), runner.fileSha256,
            "실행 commit의 runner 파일 checksum이 evidence와 다릅니다.");
    } catch (error) {
        throw new Error(`runner source commit/file을 확인할 수 없습니다: ${error.message}`);
    }
}

export function validateLiveEvidence(evidence, options = {}) {
    const pinned = readPinnedInputs(options.manifestPath);
    validatePinnedFiles(pinned);
    assertObject(evidence, "evidence");
    validateRunnerProvenance(evidence, pinned);
    assertEqual(evidence.schemaVersion, LIVE_EVIDENCE_SCHEMA_VERSION, `schemaVersion은 ${LIVE_EVIDENCE_SCHEMA_VERSION}이어야 합니다.`);
    assertEqual(evidence.kind, LIVE_EVIDENCE_KIND, "kind가 올바르지 않습니다.");
    assertEqual(evidence.issue, 1002, "issue는 1002여야 합니다.");
    if (!["completed", "timeout-observed"].includes(evidence.status)) {
        throw new Error("evidence status는 completed 또는 timeout-observed여야 합니다.");
    }

    const input = evidence.input;
    assertObject(input, "input");
    assertEqual(input.executionManifest?.path, pinned.manifestRelativePath,
        "execution manifest 경로가 고정값과 다릅니다.");
    assertEqual(input.executionManifest?.sha256, pinned.manifestSha256,
        "execution manifest checksum이 고정값과 다릅니다.");
    assertEqual(input.catalogRelease?.path, pinned.manifest.catalogRelease.path,
        "catalog release 경로가 고정값과 다릅니다.");
    assertEqual(input.catalogRelease?.manifestSha256, pinned.catalogRelease.descriptor.sha256,
        "catalog release manifest checksum이 고정값과 다릅니다.");
    assertEqual(input.qualityCorpus?.path, pinned.manifest.qualityCorpus.path,
        "quality corpus 경로가 고정값과 다릅니다.");
    assertEqual(input.qualityCorpus?.sha256, pinned.qualityCorpus.descriptor.sha256,
        "quality corpus checksum이 고정값과 다릅니다.");
    assertEqual(input.corpus?.rowCount, SEARCH_TEXT_ROW_COUNT, "approved corpus는 정확히 1,000행이어야 합니다.");
    assertEqual(input.corpus?.reference, pinned.manifest.searchText.path,
        "search_text 경로가 고정값과 다릅니다.");
    assertEqual(input.corpus?.searchTextSha256, pinned.searchText.descriptor.sha256,
        "search_text checksum이 고정값과 다릅니다.");
    assertEqual(input.corpus?.gameIdMembershipSha256, pinned.manifest.searchText.gameIdMembershipSha256,
        "search_text membership이 고정값과 다릅니다.");
    assertSha256(input.corpus?.searchTextSha256, "input.corpus.searchTextSha256");
    assertSha256(input.corpus?.gameIdMembershipSha256, "input.corpus.gameIdMembershipSha256");
    assertEqual(input.corpus.gameIdMembershipSha256, input.index?.bggGameIdMembershipSha256,
        "index와 approved corpus의 BGG game ID membership이 다릅니다.");
    assertSha256(input.index?.internalGameIdMembershipSha256, "input.index.internalGameIdMembershipSha256");
    assertEqual(input.index.internalGameIdMembershipSha256, input.release?.manifestSha256,
        "release manifest checksum과 실제 index 내부 game ID membership이 다릅니다.");
    assertEqual(input.corpus.searchTextSha256, input.release?.searchTextChecksum,
        "release search_text checksum과 corpus artifact가 다릅니다.");
    assertEqual(input.catalogRelease?.releaseId, pinned.manifest.catalogRelease.releaseId,
        "catalog release ID가 고정값과 다릅니다.");
    assertEqual(input.catalogRelease?.fieldVersion, pinned.manifest.catalogRelease.fieldVersion,
        "catalog field version이 고정값과 다릅니다.");
    assertEqual(input.qualityCorpus?.rowCount, SEARCH_TEXT_ROW_COUNT,
        "quality corpus는 정확히 1,000행이어야 합니다.");
    assertEqual(input.qualityCorpus?.gameIdMembershipSha256, pinned.manifest.qualityCorpus.gameIdMembershipSha256,
        "quality corpus membership이 고정값과 다릅니다.");
    assertEqual(input.index?.rowCount, SEARCH_TEXT_ROW_COUNT, "index는 정확히 1,000행이어야 합니다.");
    assertEqual(input.index?.status, "READY", "측정 index는 READY여야 합니다.");
    assertEqual(input.index?.productionReady, false, "측정용 index를 production index로 표시할 수 없습니다.");
    assertEqual(input.index?.provider, MODEL.provider, "index provider가 Cloudflare가 아닙니다.");
    assertEqual(input.index?.model, MODEL.model, "index model이 BGE-M3가 아닙니다.");
    assertEqual(input.index?.embeddingMode, MODEL.mode, "index embedding mode가 text가 아닙니다.");
    assertEqual(input.index?.dimension, MODEL.dimension, "index dimension이 1,024가 아닙니다.");
    assertEqual(input.index?.l2Normalized, MODEL.l2Normalized, "index L2 normalization이 다릅니다.");
    for (const field of [
        "version", "releaseId", "fieldVersion", "manifestSha256", "searchTextChecksum", "rowCount",
        "bggGameIdMembershipSha256", "internalGameIdMembershipSha256", "provider", "model", "embeddingMode",
        "dimension", "l2Normalized", "status",
    ]) {
        assertEqual(input.index?.[field], pinned.manifest.index[field], `index.${field}가 고정값과 다릅니다.`);
    }

    const execution = evidence.execution;
    assertObject(execution, "execution");
    assertEqual(execution.dense?.indexRows, SEARCH_TEXT_ROW_COUNT,
        "Dense READY index row 수가 1,000이 아닙니다.");
    assertEqual(execution.dense?.queryEmbeddings?.requestCount, QUERY_FIXTURES.length * MEASURE_ROUNDS,
        "Dense query embedding 요청 수가 8개 질의×5회가 아닙니다.");
    assertEqual(execution.dense?.queryEmbeddings?.successCount
        + execution.dense?.queryEmbeddings?.failureCount, execution.dense?.queryEmbeddings?.requestCount,
        "Dense query embedding 성공·실패 요청 수가 맞지 않습니다.");
    assertEqual(execution.dense?.queryRounds, MEASURE_ROUNDS, "Dense query 반복 횟수가 5회가 아닙니다.");
    assertEqual(execution.dense?.source, DENSE_EVIDENCE_SOURCE,
        "Dense source가 승인된 Cloudflare direct REST 경로가 아닙니다.");
    assertEqual(execution.dense?.candidateQueries?.source, DENSE_QUERY_SOURCE,
        "Dense pgvector 후보 조회 source가 production SQL 경로가 아닙니다.");
    assertEqual(execution.dense?.candidateQueries?.candidateLimit, DENSE_CANDIDATE_LIMIT,
        "Dense pgvector 후보 조회 상한이 production 값과 다릅니다.");
    assertEqual(execution.dense?.candidateQueries?.requestCount, EXECUTION_REQUEST_COUNT,
        "Dense pgvector 후보 조회 요청 수가 8개 질의×5회가 아닙니다.");
    assertEqual(execution.sparse?.serving?.source, SPARSE_EVIDENCE_SOURCE,
        "Sparse serving source가 production SQL shape 경로가 아닙니다.");
    assertEqual(execution.sparse?.serving?.scope, "all-games",
        "Sparse serving은 전체 games scope여야 합니다.");
    assertEqual(execution.sparse?.serving?.candidateLimit, SERVING_SPARSE_CANDIDATE_LIMIT,
        "Sparse serving candidate limit이 production 값과 다릅니다.");
    assertEqual(execution.sparse?.serving?.requestCount, EXECUTION_REQUEST_COUNT,
        "Sparse serving query 수가 8개 질의×5회가 아닙니다.");
    assertEqual(execution.sparse?.corpus?.scope, "approved-search-text-corpus",
        "Sparse corpus 비교 scope가 다릅니다.");
    assertEqual(execution.sparse?.corpus?.maxCandidateLimit, SEARCH_TEXT_ROW_COUNT,
        "Sparse corpus 비교 상한이 1,000이 아닙니다.");
    assertEqual(execution.sparse?.corpus?.queryCount, QUERY_FIXTURES.length, "Sparse corpus query 수가 8개가 아닙니다.");
    assertEqual(execution.fusion?.queryCount, QUERY_FIXTURES.length, "Fusion query 수가 8개가 아닙니다.");
    assertObject(execution.phaseLatency, "execution.phaseLatency");
    for (const field of [
        "denseEmbedding", "denseCandidateQuery", "denseBranch", "sparseServingSql", "sparseCorpusSql", "fusion", "parallel",
    ]) {
        assertPositiveNumber(execution.phaseLatency[field]?.p95, `execution.phaseLatency.${field}.p95`);
        assertPositiveNumber(execution.phaseLatency[field]?.max, `execution.phaseLatency.${field}.max`);
    }
    assertEqual(execution.parallel?.providerTimeoutMs, PROVIDER_TIMEOUT_MS,
        "provider timeout은 5초여야 합니다.");
    assertEqual(execution.parallel?.commonDeadlineMs, COMMON_DEADLINE_MS,
        "공통 deadline은 6초여야 합니다.");
    assertEqual(execution.parallel?.requestCount, EXECUTION_REQUEST_COUNT,
        "동시 실행 request 수가 8개 질의×5회가 아닙니다.");
    assertEqual(execution.parallel?.completedCount + execution.parallel?.timeoutCount, EXECUTION_REQUEST_COUNT,
        "동시 실행 완료·timeout request 수가 맞지 않습니다.");
    if (!Array.isArray(execution.requests) || execution.requests.length !== EXECUTION_REQUEST_COUNT) {
        throw new Error("요청별 동시 실행 결과 40개를 모두 보존해야 합니다.");
    }
    for (const request of execution.requests) {
        if (!QUERY_FIXTURES.some((fixture) => fixture.id === request?.queryId)) {
            throw new Error(`승인되지 않은 concurrent query ID입니다: ${request?.queryId}`);
        }
        assertInteger(request.round, `${request.queryId}.round`);
        if (request.round < 1 || request.round > MEASURE_ROUNDS) {
            throw new Error(`${request.queryId}.round가 1~5 범위를 벗어났습니다.`);
        }
        assertEqual(request.deadlineMs, COMMON_DEADLINE_MS, `${request.queryId}.deadlineMs가 다릅니다.`);
        assertEqual(request.providerTimeoutMs, PROVIDER_TIMEOUT_MS, `${request.queryId}.providerTimeoutMs가 다릅니다.`);
        if (!["success", "timeout", "failure"].includes(request.dense?.status)) {
            throw new Error(`${request.queryId} Dense branch 상태가 올바르지 않습니다.`);
        }
        if (!["success", "timeout", "failure"].includes(request.sparse?.status)) {
            throw new Error(`${request.queryId} Sparse branch 상태가 올바르지 않습니다.`);
        }
        if (request.completedWithinDeadline !== (request.dense.status === "success"
            && request.sparse.status === "success")) {
            throw new Error(`${request.queryId} branch 상태와 deadline 결과가 다릅니다.`);
        }
        assertPositiveNumber(request.parallelElapsedMs, `${request.queryId}.parallelElapsedMs`);
        if (request.dense.status === "success") {
            assertPositiveNumber(request.dense.embeddingElapsedMs, `${request.queryId}.dense.embeddingElapsedMs`);
            assertPositiveNumber(request.dense.candidateQueryElapsedMs, `${request.queryId}.dense.candidateQueryElapsedMs`);
        }
        if (request.sparse.status === "success") {
            assertPositiveNumber(request.sparse.sqlElapsedMs, `${request.queryId}.sparse.sqlElapsedMs`);
        }
        if (request.dense.status !== "success" || request.sparse.status !== "success") {
            assertEqual(request.fusionElapsedMs, 0, `${request.queryId} 실패 request의 fusion은 0이어야 합니다.`);
        } else {
            assertPositiveNumber(request.fusionElapsedMs, `${request.queryId}.fusionElapsedMs`);
        }
    }
    const timeoutRequestCount = execution.requests.filter((request) => !request.completedWithinDeadline).length;
    assertEqual(execution.parallel.timeoutCount, timeoutRequestCount,
        "parallel timeoutCount와 요청별 timeout 수가 다릅니다.");
    assertEqual(execution.parallel.completedCount, EXECUTION_REQUEST_COUNT - timeoutRequestCount,
        "parallel completedCount와 요청별 완료 수가 다릅니다.");
    if (evidence.status === "completed" && timeoutRequestCount !== 0) {
        throw new Error("timeout request가 있는 실행을 completed로 기록할 수 없습니다.");
    }
    if (evidence.status === "timeout-observed" && timeoutRequestCount === 0) {
        throw new Error("timeout-observed evidence에는 timeout request가 있어야 합니다.");
    }

    const queries = execution.queries;
    if (!Array.isArray(queries) || queries.length !== QUERY_FIXTURES.length) {
        throw new Error("Stone Age 4개와 common query 4개를 모두 기록해야 합니다.");
    }
    const queryIds = new Set();
    for (const query of queries) {
        if (!query?.id || queryIds.has(query.id)) throw new Error("query ID가 없거나 중복되었습니다.");
        queryIds.add(query.id);
        const fixture = QUERY_FIXTURES.find((candidate) => candidate.id === query.id);
        if (!fixture) throw new Error(`승인되지 않은 query fixture입니다: ${query.id}`);
        assertEqual(query.querySha256, sha256(Buffer.from(fixture.query, "utf8")),
            `${query.id}.querySha256가 승인 fixture와 다릅니다.`);
        assertEqual(query.anchorGameId, fixture.anchorGameId, `${query.id}.anchorGameId가 승인 fixture와 다릅니다.`);
        assertSha256(query.querySha256, `${query.id}.querySha256`);
        assertInteger(query.sparseFullCount, `${query.id}.sparseFullCount`);
        if (query.sparseFullCount < 0 || query.sparseFullCount > SEARCH_TEXT_ROW_COUNT) {
            throw new Error(`${query.id}.sparseFullCount가 승인 corpus 범위를 벗어났습니다.`);
        }
        if (!Array.isArray(query.denseTop20) || query.denseTop20.length !== 20) {
            throw new Error(`${query.id}.denseTop20은 20개여야 합니다.`);
        }
        if (!Array.isArray(query.candidateKComparison) || query.candidateKComparison.length !== CANDIDATE_K_VALUES.length) {
            throw new Error(`${query.id}.candidateKComparison이 불완전합니다.`);
        }
        if (!Array.isArray(query.rrfKComparison) || query.rrfKComparison.length !== RRF_K_VALUES.length) {
            throw new Error(`${query.id}.rrfKComparison이 불완전합니다.`);
        }
        assertEqual(query.candidateKComparison.map((row) => row.candidateK).join(","), CANDIDATE_K_VALUES.join(","),
            `${query.id}.candidateKComparison의 K 목록이 다릅니다.`);
        assertEqual(query.rrfKComparison.map((row) => row.rrfK).join(","), RRF_K_VALUES.join(","),
            `${query.id}.rrfKComparison의 RRF k 목록이 다릅니다.`);
    }
    for (const fixture of QUERY_FIXTURES) {
        if (!queryIds.has(fixture.id)) throw new Error(`승인 fixture가 누락되었습니다: ${fixture.id}`);
    }
    const commonQueries = queries.filter((query) => query.id.startsWith("COMMON-") && query.sparseFullCount > 200);
    const commonFixtureCount = QUERY_FIXTURES.filter((fixture) => fixture.id.startsWith("COMMON-")).length;
    if (commonQueries.length !== commonFixtureCount) {
        throw new Error(`common query ${commonFixtureCount}개가 모두 200개를 넘어야 합니다.`);
    }
    assertEqual(evidence.parameters?.selected?.candidateK, SELECTED_CANDIDATE_K,
        "candidate K 확정값은 현재 200이어야 합니다.");
    assertEqual(evidence.parameters?.selected?.rrfK, SELECTED_RRF_K,
        "RRF k 확정값은 현재 60이어야 합니다.");
    assertEqual(evidence.parameters?.selected?.timeoutSeconds, SELECTED_TIMEOUT_SECONDS,
        "공통 timeout 확정값은 현재 6초여야 합니다.");
    assertPositiveNumber(evidence.parameters?.observedParallelP95Ms, "parameters.observedParallelP95Ms");
    if (evidence.status === "completed" && evidence.parameters.observedParallelP95Ms >= COMMON_DEADLINE_MS) {
        throw new Error("관찰된 parallel p95가 6초 budget을 초과합니다.");
    }
    assertEqual(execution.resultSha256, pinned.manifest.result.sha256,
        "execution result digest가 고정 artifact와 다릅니다.");
    assertEqual(resultSha256(evidence), pinned.manifest.result.sha256,
        "execution 결과 필드가 고정 artifact digest와 다릅니다.");
    return { queryCount: queries.length, commonQueryCount: commonQueries.length };
}

async function main() {
    const args = parseArgs(process.argv.slice(2));
    if (args.mode === "validate") {
        const evidence = readJson(args.input);
        console.log(JSON.stringify(validateLiveEvidence(evidence, { manifestPath: args.manifest })));
        return;
    }
    const evidence = await runMeasurement(args);
    writeJson(args.output, evidence);
    if (!args.skipValidation) validateLiveEvidence(evidence, { manifestPath: args.manifest });
    console.log(`SEARCH-04e live evidence written: ${args.output}`);
}

async function runMeasurement(args) {
    const startedAt = new Date().toISOString();
    const pinned = readPinnedInputs(args.manifest);
    validatePinnedFiles(pinned);
    if (!args.postgresContainer) throw new Error("--postgres-container 또는 ALBAM_MATE_POSTGRES_CONTAINER가 필요합니다.");
    const searchTextPath = repositoryPath(pinned.manifest.searchText.path);
    if (path.resolve(args.searchText) !== searchTextPath) {
        throw new Error(`--search-text는 고정 manifest 경로와 같아야 합니다: ${pinned.manifest.searchText.path}`);
    }
    const searchTextBytes = fs.readFileSync(searchTextPath);
    const searchText = JSON.parse(searchTextBytes.toString("utf8"));
    validateSearchText(searchText);
    const games = searchText.games;
    const gameIds = games.map((game) => game.gameId);
    const searchTextSha256 = sha256(searchTextBytes);
    const membershipSha256 = gameIdMembershipSha256(gameIds);
    assertEqual(searchTextSha256, pinned.manifest.searchText.sha256, "search_text checksum이 manifest와 다릅니다.");
    assertEqual(membershipSha256, pinned.manifest.searchText.gameIdMembershipSha256,
        "search_text membership이 manifest와 다릅니다.");
    const releaseId = pinned.manifest.catalogRelease.releaseId;
    const fieldVersion = pinned.manifest.catalogRelease.fieldVersion;
    if (process.env.ALBAM_MATE_SEARCH_RELEASE_ID
        && process.env.ALBAM_MATE_SEARCH_RELEASE_ID !== releaseId) {
        throw new Error("ALBAM_MATE_SEARCH_RELEASE_ID가 고정 release와 다릅니다.");
    }
    if (process.env.ALBAM_MATE_SEARCH_FIELD_VERSION
        && process.env.ALBAM_MATE_SEARCH_FIELD_VERSION !== fieldVersion) {
        throw new Error("ALBAM_MATE_SEARCH_FIELD_VERSION이 고정 field version과 다릅니다.");
    }

    const database = loadDatabase(args.postgresContainer, gameIds, searchTextSha256, releaseId, fieldVersion,
        pinned.manifest.index);
    const endpoint = cloudflareEndpoint();

    const queryEvidence = [];
    const requests = [];
    const denseEmbeddingLatencies = [];
    const denseCandidateQueryLatencies = [];
    const denseBranchLatencies = [];
    const sparseServingLatencies = [];
    const sparseCorpusLatencies = [];
    const parallelLatencies = [];
    const fusionLatencies = [];
    for (const fixture of QUERY_FIXTURES) {
        const rounds = [];
        for (let round = 0; round < MEASURE_ROUNDS; round++) {
            const roundEvidence = await measureConcurrentRound(fixture, round + 1, {
                endpoint,
                database,
                container: args.postgresContainer,
            });
            rounds.push(roundEvidence);
            requests.push(roundEvidence.request);
            if (roundEvidence.request.dense.status === "success") {
                denseEmbeddingLatencies.push(roundEvidence.request.dense.embeddingElapsedMs);
                denseCandidateQueryLatencies.push(roundEvidence.request.dense.candidateQueryElapsedMs);
            }
            denseBranchLatencies.push(roundEvidence.request.dense.branchElapsedMs);
            sparseServingLatencies.push(roundEvidence.request.sparse.sqlElapsedMs);
            parallelLatencies.push(roundEvidence.request.parallelElapsedMs);
            if (roundEvidence.request.fusionElapsedMs > 0) fusionLatencies.push(roundEvidence.request.fusionElapsedMs);
        }
        const corpusMeasurement = await measureSparseCandidates(
            fixture.query,
            args.postgresContainer,
            database.internalGameIds,
            database.bggIdByInternalId,
            SEARCH_TEXT_ROW_COUNT,
        );
        sparseCorpusLatencies.push(corpusMeasurement.elapsedMs);
        const successfulRound = rounds.find((round) => round.denseCandidates && round.servingSparseCandidates);
        if (!successfulRound) throw new Error(`${fixture.id}에서 Dense·Sparse serving 성공 round를 확보하지 못했습니다.`);
        const denseCandidates = successfulRound.denseCandidates;
        const servingSparseCandidates = successfulRound.servingSparseCandidates;
        const sparseCandidates = corpusMeasurement.candidates;
        const referenceHybrid = fuse(denseCandidates, sparseCandidates, SELECTED_RRF_K);
        const hybridK200 = fuse(denseCandidates, sparseCandidates.slice(0, SELECTED_CANDIDATE_K), SELECTED_RRF_K);
        queryEvidence.push({
            id: fixture.id,
            querySha256: sha256(Buffer.from(fixture.query, "utf8")),
            anchorGameId: fixture.anchorGameId,
            sparseFullCount: sparseCandidates.length,
            servingSparseCount: servingSparseCandidates.length,
            denseTop20: denseCandidates.slice(0, 20).map((candidate) => candidate.gameId),
            sparseTop20: sparseCandidates.slice(0, 20).map((candidate) => candidate.gameId),
            servingSparseTop20: servingSparseCandidates.slice(0, 20).map((candidate) => candidate.gameId),
            hybridTop20: hybridK200.slice(0, 20).map((candidate) => candidate.gameId),
            anchorRanks: {
                dense: rankOf(denseCandidates, fixture.anchorGameId),
                sparse: rankOf(sparseCandidates, fixture.anchorGameId),
                hybridK200: rankOf(hybridK200, fixture.anchorGameId),
                hybridK1000: rankOf(referenceHybrid, fixture.anchorGameId),
            },
            candidateKComparison: CANDIDATE_K_VALUES.map((candidateK) => {
                const hybrid = fuse(denseCandidates, sparseCandidates.slice(0, candidateK), SELECTED_RRF_K);
                return {
                    candidateK,
                    availableSparseCandidates: Math.min(candidateK, sparseCandidates.length),
                    hybridTop20: hybrid.slice(0, 20).map((candidate) => candidate.gameId),
                    overlapWithK1000: overlap(hybrid, referenceHybrid),
                    droppedFromK1000Top20: droppedTop20(hybrid, referenceHybrid),
                    anchorRank: rankOf(hybrid, fixture.anchorGameId),
                };
            }),
            rrfKComparison: RRF_K_VALUES.map((rrfK) => {
                const hybrid = fuse(denseCandidates, sparseCandidates.slice(0, SELECTED_CANDIDATE_K), rrfK);
                return {
                    rrfK,
                    hybridTop20: hybrid.slice(0, 20).map((candidate) => candidate.gameId),
                    overlapWithRrfK60: overlap(hybrid, hybridK200),
                    anchorRank: rankOf(hybrid, fixture.anchorGameId),
                };
            }),
            measurement: {
                denseBranch: summarize(rounds.map((round) => round.request.dense.branchElapsedMs)),
                servingSparseSql: summarize(rounds.map((round) => round.request.sparse.sqlElapsedMs)),
                corpusSparseSql: corpusMeasurement.elapsedMs,
                fusion: summarize(rounds.map((round) => round.request.fusionElapsedMs)),
            },
        });
    }

    const phaseLatency = {
        denseEmbedding: summarize(denseEmbeddingLatencies),
        denseCandidateQuery: summarize(denseCandidateQueryLatencies),
        denseBranch: summarize(denseBranchLatencies),
        sparseServingSql: summarize(sparseServingLatencies),
        sparseCorpusSql: summarize(sparseCorpusLatencies),
        parallel: summarize(parallelLatencies),
        fusion: summarize(fusionLatencies),
    };
    const observedParallelP95Ms = phaseLatency.parallel.p95 + phaseLatency.fusion.p95;
    const timeoutComparison = TIMEOUT_SECONDS_VALUES.map((timeoutSeconds) => ({
        timeoutSeconds,
        budgetMs: timeoutSeconds * 1000,
        observedParallelP95Ms,
        marginMs: timeoutSeconds * 1000 - observedParallelP95Ms,
        passesObservedBudget: observedParallelP95Ms < timeoutSeconds * 1000,
    }));
    const timeoutCount = requests.filter((request) => !request.completedWithinDeadline).length;
    const evidenceStatus = timeoutCount === 0 ? "completed" : "timeout-observed";

    const evidence = {
        schemaVersion: LIVE_EVIDENCE_SCHEMA_VERSION,
        kind: LIVE_EVIDENCE_KIND,
        issue: 1002,
        featureId: "SEARCH-04",
        status: evidenceStatus,
        sourceGitHead: gitHead(),
        runner: runnerProvenance(),
        input: {
            executionManifest: {
                path: pinned.manifestRelativePath,
                sha256: pinned.manifestSha256,
            },
            catalogRelease: {
                releaseId,
                fieldVersion,
                path: pinned.manifest.catalogRelease.path,
                manifestSha256: pinned.catalogRelease.descriptor.sha256,
            },
            qualityCorpus: {
                path: pinned.manifest.qualityCorpus.path,
                rowCount: pinned.manifest.qualityCorpus.rowCount,
                sha256: pinned.qualityCorpus.descriptor.sha256,
                gameIdMembershipSha256: pinned.manifest.qualityCorpus.gameIdMembershipSha256,
            },
            corpus: {
                reference: pinned.manifest.searchText.path,
                rowCount: games.length,
                searchTextSha256,
                gameIdMembershipSha256: membershipSha256,
            },
            release: {
                releaseId: database.indexMetadata.releaseId,
                fieldVersion: database.indexMetadata.fieldVersion,
                manifestSha256: database.indexMetadata.manifestSha256,
                searchTextChecksum: searchTextSha256,
            },
            index: {
                kind: "active-pgvector-exact-cosine",
                status: database.indexMetadata.status,
                productionReady: false,
                provider: database.indexMetadata.provider,
                model: database.indexMetadata.model,
                embeddingMode: database.indexMetadata.embeddingMode,
                dimension: database.indexMetadata.dimension,
                l2Normalized: database.indexMetadata.l2Normalized,
                rowCount: database.indexMetadata.rowCount,
                bggGameIdMembershipSha256: membershipSha256,
                internalGameIdMembershipSha256: database.indexMetadata.internalGameIdMembershipSha256,
                version: database.indexMetadata.id,
            },
        },
        execution: {
            startedAt,
            completedAt: new Date().toISOString(),
            database: {
                container: database.container,
                approvedGameRowCount: database.gameCount,
                activeIndexId: database.indexMetadata.id,
                activeIndexInternalGameIdMembershipSha256: database.indexMetadata.internalGameIdMembershipSha256,
            },
            dense: {
                source: DENSE_EVIDENCE_SOURCE,
                provider: MODEL.provider,
                model: MODEL.model,
                indexRows: games.length,
                queryEmbeddings: {
                    requestCount: EXECUTION_REQUEST_COUNT,
                    successCount: denseEmbeddingLatencies.length,
                    failureCount: EXECUTION_REQUEST_COUNT - denseEmbeddingLatencies.length,
                    latencyMs: phaseLatency.denseEmbedding,
                },
                queryRounds: MEASURE_ROUNDS,
                candidateQueries: {
                    source: DENSE_QUERY_SOURCE,
                    candidateLimit: DENSE_CANDIDATE_LIMIT,
                    requestCount: EXECUTION_REQUEST_COUNT,
                    queryCount: EXECUTION_REQUEST_COUNT,
                    successCount: denseCandidateQueryLatencies.length,
                    failureCount: EXECUTION_REQUEST_COUNT - denseCandidateQueryLatencies.length,
                    latencyMs: phaseLatency.denseCandidateQuery,
                },
                branchLatency: phaseLatency.denseBranch,
            },
            sparse: {
                serving: {
                    source: SPARSE_EVIDENCE_SOURCE,
                    scope: "all-games",
                    candidateLimit: SERVING_SPARSE_CANDIDATE_LIMIT,
                    requestCount: EXECUTION_REQUEST_COUNT,
                    queryCount: EXECUTION_REQUEST_COUNT,
                    successCount: requests.filter((request) => request.sparse.status === "success").length,
                    timeoutCount,
                    executionMs: phaseLatency.sparseServingSql,
                },
                corpus: {
                    source: SPARSE_EVIDENCE_SOURCE,
                    scope: "approved-search-text-corpus",
                    maxCandidateLimit: SEARCH_TEXT_ROW_COUNT,
                    queryCount: QUERY_FIXTURES.length,
                    executionMs: phaseLatency.sparseCorpusSql,
                },
            },
            fusion: {
                algorithm: "reciprocal-rank-fusion",
                candidateKValues: [...CANDIDATE_K_VALUES],
                rrfKValues: [...RRF_K_VALUES],
                queryCount: QUERY_FIXTURES.length,
            },
            phaseLatency,
            observedParallelP95Ms,
            parallel: {
                providerTimeoutMs: PROVIDER_TIMEOUT_MS,
                commonDeadlineMs: COMMON_DEADLINE_MS,
                requestCount: requests.length,
                completedCount: requests.filter((request) => request.completedWithinDeadline).length,
                timeoutCount: requests.filter((request) => !request.completedWithinDeadline).length,
                failureCount: requests.filter((request) => request.dense.status !== "success"
                    || request.sparse.status !== "success").length,
            },
            resultSha256: null,
            requests,
            queries: queryEvidence,
            timeoutComparison,
        },
        parameters: {
            selected: {
                candidateK: SELECTED_CANDIDATE_K,
                rrfK: SELECTED_RRF_K,
                timeoutSeconds: SELECTED_TIMEOUT_SECONDS,
            },
            candidateK: [...CANDIDATE_K_VALUES],
            rrfK: [...RRF_K_VALUES],
            timeoutSeconds: [...TIMEOUT_SECONDS_VALUES],
            observedParallelP95Ms,
            recommendation: {
                candidateK: "retain-200-until-quality-qrels",
                rrfK: "retain-60-until-quality-qrels",
                timeoutSeconds: timeoutCount === 0 ? "retain-6s" : "not-validated-timeout-observed",
            },
        },
        runtime: {
            node: process.version,
            postgresContainer: args.postgresContainer,
            cloudflareAccountConfigured: Boolean(process.env.CLOUDFLARE_ACCOUNT_ID),
            cloudflareTokenConfigured: Boolean(process.env.CLOUDFLARE_API_TOKEN),
            configuredRelease: process.env.ALBAM_MATE_SEARCH_RELEASE_ID
                ? {
                    releaseId: process.env.ALBAM_MATE_SEARCH_RELEASE_ID,
                    fieldVersion: process.env.ALBAM_MATE_SEARCH_FIELD_VERSION || null,
                    manifestSha256: process.env.ALBAM_MATE_SEARCH_MANIFEST_SHA256 || null,
                    searchTextChecksum: process.env.ALBAM_MATE_SEARCH_TEXT_CHECKSUM || null,
                }
            : null,
        },
    };
    evidence.execution.resultSha256 = resultSha256(evidence);
    return evidence;
}

async function embedQuery(query, endpoint, signal) {
    return embed(query, endpoint, signal);
}

async function embed(text, endpoint, signal) {
    const started = performance.now();
    let response;
    try {
        response = await fetch(endpoint, {
            method: "POST",
            headers: {
                Authorization: `Bearer ${process.env.CLOUDFLARE_API_TOKEN}`,
                "Content-Type": "application/json",
            },
            body: JSON.stringify({ text: [text] }),
            signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(PROVIDER_TIMEOUT_MS)])
                : AbortSignal.timeout(PROVIDER_TIMEOUT_MS),
        });
    } catch (error) {
        throw new Error(`Cloudflare embedding 요청 실패: ${error.name}`);
    }
    const latencyMs = performance.now() - started;
    let payload;
    try {
        payload = await response.json();
    } catch {
        throw new Error(`Cloudflare embedding 응답 JSON 파싱 실패: status=${response.status}`);
    }
    if (!response.ok || payload.success !== true) {
        throw new Error(`Cloudflare embedding 응답 실패: status=${response.status}`);
    }
    const rawVector = payload.result?.data?.[0];
    if (!Array.isArray(rawVector) || rawVector.length !== MODEL.dimension) {
        throw new Error(`Cloudflare embedding 차원 불일치: status=${response.status}`);
    }
    const squaredNorm = rawVector.reduce((sum, value) => {
        if (typeof value !== "number" || !Number.isFinite(value)) throw new Error("Cloudflare embedding invalid vector");
        return sum + value * value;
    }, 0);
    if (!Number.isFinite(squaredNorm) || squaredNorm === 0) throw new Error("Cloudflare embedding zero vector");
    const norm = Math.sqrt(squaredNorm);
    return { vector: rawVector.map((value) => value / norm), latencyMs };
}

async function measureConcurrentRound(fixture, round, context) {
    const controller = new AbortController();
    const startedAt = performance.now();
    const densePromise = (async () => {
        const embedding = await embedQuery(fixture.query, context.endpoint, controller.signal);
        const dense = await measureDenseCandidates(
            embedding.vector,
            context.container,
            context.database,
            controller.signal,
        );
        return {
            embedding,
            dense,
            branchElapsedMs: performance.now() - startedAt,
        };
    })();
    const sparsePromise = measureSparseCandidates(
        fixture.query,
        context.container,
        null,
        context.database.bggIdByInternalId,
        SERVING_SPARSE_CANDIDATE_LIMIT,
        controller.signal,
    );
    const [denseOutcome, sparseOutcome] = await Promise.all([
        settleWithinDeadline(densePromise, startedAt, controller),
        settleWithinDeadline(sparsePromise, startedAt, controller),
    ]);
    const parallelElapsedMs = performance.now() - startedAt;
    const denseValue = denseOutcome.status === "success" ? denseOutcome.value : null;
    const sparseValue = sparseOutcome.status === "success" ? sparseOutcome.value : null;
    let fusionElapsedMs = 0;
    if (denseValue && sparseValue) {
        const fusionStarted = performance.now();
        fuse(denseValue.dense.candidates, sparseValue.candidates, SELECTED_RRF_K);
        fusionElapsedMs = performance.now() - fusionStarted;
    }
    const elapsedForFailedBranch = Math.max(parallelElapsedMs, 0.001);
    const requestSucceeded = denseOutcome.status === "success"
        && sparseOutcome.status === "success"
        && parallelElapsedMs < COMMON_DEADLINE_MS;
    return {
        denseCandidates: denseValue?.dense.candidates ?? null,
        servingSparseCandidates: sparseValue?.candidates ?? null,
        request: {
            queryId: fixture.id,
            round,
            deadlineMs: COMMON_DEADLINE_MS,
            providerTimeoutMs: PROVIDER_TIMEOUT_MS,
            parallelElapsedMs,
            completedWithinDeadline: requestSucceeded,
            dense: {
                status: denseOutcome.status,
                reasonCode: denseOutcome.status === "success" ? null
                    : denseOutcome.status === "timeout" ? "COMMON_DEADLINE" : "BRANCH_FAILURE",
                embeddingElapsedMs: denseValue?.embedding.latencyMs ?? null,
                candidateQueryElapsedMs: denseValue?.dense.elapsedMs ?? null,
                branchElapsedMs: denseValue?.branchElapsedMs ?? elapsedForFailedBranch,
            },
            sparse: {
                status: sparseOutcome.status,
                reasonCode: sparseOutcome.status === "success" ? null
                    : sparseOutcome.status === "timeout" ? "COMMON_DEADLINE" : "BRANCH_FAILURE",
                sqlElapsedMs: sparseValue?.elapsedMs ?? elapsedForFailedBranch,
                candidateLimit: SERVING_SPARSE_CANDIDATE_LIMIT,
                scope: "all-games",
            },
            fusionElapsedMs,
        },
    };
}

async function settleWithinDeadline(promise, startedAt, controller) {
    const remainingMs = COMMON_DEADLINE_MS - (performance.now() - startedAt);
    if (remainingMs <= 0) {
        controller.abort();
        return { status: "timeout" };
    }
    let timer;
    const wrapped = promise.then(
        (value) => ({ status: "success", value }),
        (error) => ({ status: "failure", error }),
    );
    const timeout = new Promise((resolve) => {
        timer = setTimeout(() => {
            controller.abort();
            resolve({ status: "timeout" });
        }, remainingMs);
    });
    const outcome = await Promise.race([wrapped, timeout]);
    clearTimeout(timer);
    return outcome;
}

async function measureDenseCandidates(queryVector, container, database, signal) {
    const sql = denseSql(queryVector, database.indexMetadata, DENSE_CANDIDATE_LIMIT);
    const started = performance.now();
    const output = await runPsqlAsync(container, sql, signal);
    const elapsedMs = performance.now() - started;
    const candidates = parseCandidateRows(output, database.bggIdByInternalId);
    if (candidates.length !== DENSE_CANDIDATE_LIMIT) {
        throw new Error(`Dense pgvector 후보 수가 ${DENSE_CANDIDATE_LIMIT}개가 아닙니다: ${candidates.length}`);
    }
    return { elapsedMs, candidates };
}

async function measureSparseCandidates(query, container, approvedInternalIds, bggIdByInternalId, limit, signal) {
    const sql = sparseSql(query, approvedInternalIds, limit);
    const started = performance.now();
    const output = await runPsqlAsync(container, sql, signal);
    const elapsedMs = performance.now() - started;
    return { elapsedMs, candidates: parseCandidateRows(output, bggIdByInternalId) };
}

function parseCandidateRows(output, bggIdByInternalId) {
    if (output.trim() === "") return [];
    return output.trim().split(/\r?\n/u).map((line) => {
        const [internalId, score] = line.split("|").map(Number);
        const gameId = bggIdByInternalId.get(internalId) ?? internalId;
        if (!Number.isSafeInteger(gameId) || !Number.isFinite(score)) {
            throw new Error(`candidate row가 올바르지 않습니다: ${line}`);
        }
        return { gameId, score };
    });
}

function loadDatabase(container, approvedBggIds, searchTextSha256, expectedReleaseId, expectedFieldVersion, expectedIndex) {
    const values = approvedBggIds.map((gameId) => `(${gameId})`).join(",");
    const mappingOutput = runPsql(container,
        `select id, bgg_id from games where bgg_id in (select bgg_id from (values ${values}) as approved(bgg_id)) order by id;`);
    const mappingRows = mappingOutput.trim() === "" ? [] : mappingOutput.trim().split(/\r?\n/u).map((line) => {
        const [internalId, bggId] = line.split("|").map(Number);
        return { internalId, bggId };
    });
    if (mappingRows.length !== approvedBggIds.length) {
        throw new Error(`approved corpus가 PostgreSQL games에 모두 존재하지 않습니다: ${mappingRows.length}/${approvedBggIds.length}`);
    }
    const bggIdByInternalId = new Map(mappingRows.map((row) => [row.internalId, row.bggId]));
    const internalGameIds = mappingRows.map((row) => row.internalId);
    const internalMembershipSha256 = gameIdMembershipSha256(internalGameIds);
    const metadataOutput = runPsql(container, `select v.id::text, v.release_id, v.field_version, v.manifest_sha256, v.search_text_checksum, v.provider, v.model, v.embedding_mode, v.dimension, v.l2_normalized, v.status, count(e.game_id)::int from semantic_search_index_versions v left join semantic_game_embeddings e on e.index_version_id = v.id where v.active = true and v.status = 'READY' group by v.id, v.release_id, v.field_version, v.manifest_sha256, v.search_text_checksum, v.provider, v.model, v.embedding_mode, v.dimension, v.l2_normalized, v.status;`);
    const metadataRows = metadataOutput.trim() === "" ? [] : metadataOutput.trim().split(/\r?\n/u).map((line) => line.split("|"));
    if (metadataRows.length !== 1) throw new Error(`활성 READY semantic index가 정확히 1개가 아닙니다: ${metadataRows.length}`);
    const [id, releaseId, fieldVersion, manifestSha256, searchTextChecksum, provider, model, embeddingMode,
        dimension, l2Normalized, status, rowCount] = metadataRows[0];
    if (releaseId !== expectedReleaseId || fieldVersion !== expectedFieldVersion
        || searchTextChecksum !== searchTextSha256 || manifestSha256 !== internalMembershipSha256
        || provider !== MODEL.provider || model !== MODEL.model || embeddingMode !== MODEL.mode
        || Number(dimension) !== MODEL.dimension || l2Normalized !== "t" || status !== "READY"
        || Number(rowCount) !== SEARCH_TEXT_ROW_COUNT) {
        throw new Error("활성 READY index의 release/provenance/row count가 approved corpus와 일치하지 않습니다.");
    }
    for (const field of [
        "id", "releaseId", "fieldVersion", "manifestSha256", "searchTextChecksum", "provider", "model",
        "embeddingMode", "dimension", "l2Normalized", "status", "rowCount", "internalGameIdMembershipSha256",
    ]) {
        const actual = field === "id" ? id
            : field === "internalGameIdMembershipSha256" ? internalMembershipSha256
                : field === "rowCount" ? Number(rowCount)
                    : field === "dimension" ? Number(dimension)
                        : field === "l2Normalized" ? l2Normalized === "t"
                            : field === "releaseId" ? releaseId
                                : field === "fieldVersion" ? fieldVersion
                                    : field === "manifestSha256" ? manifestSha256
                                        : field === "searchTextChecksum" ? searchTextChecksum
                                            : field === "provider" ? provider
                                                : field === "model" ? model
                                                    : field === "embeddingMode" ? embeddingMode
                                                        : field === "status" ? status
                                                            : undefined;
        assertEqual(actual, expectedIndex[field], `active index ${field}가 고정값과 다릅니다.`);
    }
    const vectorOutput = runPsql(container,
        `select game_id from semantic_game_embeddings where index_version_id = '${id}' order by game_id;`);
    const vectorRows = vectorOutput.trim() === "" ? [] : vectorOutput.trim().split(/\r?\n/u).map((line) => ({
        internalId: Number(line),
    }));
    if (vectorRows.length !== SEARCH_TEXT_ROW_COUNT
        || new Set(vectorRows.map((row) => row.internalId)).size !== SEARCH_TEXT_ROW_COUNT
        || vectorRows.some((row) => !bggIdByInternalId.has(row.internalId))) {
        throw new Error("활성 READY index의 row membership이 approved corpus와 다릅니다.");
    }
    const actualInternalIds = vectorRows.map((row) => row.internalId);
    if (gameIdMembershipSha256(actualInternalIds) !== internalMembershipSha256) {
        throw new Error("활성 READY index의 내부 game ID membership이 approved corpus mapping과 다릅니다.");
    }
    return {
        container,
        gameCount: mappingRows.length,
        internalGameIds,
        bggIdByInternalId,
        indexMetadata: {
            id,
            releaseId,
            fieldVersion,
            manifestSha256,
            searchTextChecksum,
            provider,
            model,
            embeddingMode,
            dimension: Number(dimension),
            l2Normalized: l2Normalized === "t",
            status,
            rowCount: Number(rowCount),
            internalGameIdMembershipSha256: internalMembershipSha256,
        },
    };
}

export function denseSql(queryVector, indexMetadata, limit) {
    const vector = sqlLiteral(vectorLiteral(queryVector));
    return `with matching_active_version as (select version.id from semantic_search_index_versions version where version.active = true and version.status = 'READY' and version.release_id = '${sqlLiteral(indexMetadata.releaseId)}' and version.field_version = '${sqlLiteral(indexMetadata.fieldVersion)}' and version.manifest_sha256 = '${sqlLiteral(indexMetadata.manifestSha256)}' and version.search_text_checksum = '${sqlLiteral(indexMetadata.searchTextChecksum)}' and version.provider = '${sqlLiteral(indexMetadata.provider)}' and version.model = '${sqlLiteral(indexMetadata.model)}' and version.embedding_mode = '${sqlLiteral(indexMetadata.embeddingMode)}' and version.dimension = ${indexMetadata.dimension} and version.l2_normalized = ${indexMetadata.l2Normalized} and (select count(*) from semantic_game_embeddings where index_version_id = version.id) = ${SEARCH_TEXT_ROW_COUNT}) select embedding.game_id, 1 - (embedding.embedding <=> cast('${vector}' as vector)) as relevance from semantic_game_embeddings embedding join matching_active_version version on version.id = embedding.index_version_id order by embedding.embedding <=> cast('${vector}' as vector), embedding.game_id limit ${limit};`;
}

function vectorLiteral(vector) {
    return `[${vector.map((value) => Number(value).toPrecision(17)).join(",")}]`;
}

export function sparseSql(query, approvedGameIds, limit) {
    const tokens = tokenize(query);
    if (tokens.length === 0) throw new Error(`query token이 비어 있습니다: ${query}`);
    const approved = approvedGameIds ? approvedGameIds.map((gameId) => `(${gameId})`).join(",") : null;
    const tokenValues = tokens.map((token) => `('${sqlLiteral(token)}')`).join(",");
    const ctePrefix = approved
        ? `with approved(game_id) as (values ${approved}), tokens(token) as (values ${tokenValues}),`
        : `with tokens(token) as (values ${tokenValues}),`;
    const approvedJoin = approved ? " join approved a on a.game_id = g.id" : "";
    const approvedRelationJoin = approved ? " join approved a on a.game_id = r.game_id" : "";
    return `${ctePrefix} name_matches as (select g.id as game_id, count(distinct t.token) * 3.0 as weight from games g${approvedJoin} join tokens t on (lower(g.name) like '%' || t.token || '%' or lower(g.english_name) like '%' || t.token || '%' or lower(coalesce(g.alias, '')) like '%' || t.token || '%') group by g.id), description_matches as (select g.id as game_id, count(distinct t.token) * 1.0 as weight from games g${approvedJoin} join tokens t on lower(g.description) like '%' || t.token || '%' group by g.id), mechanism_matches as (select r.game_id as game_id, count(distinct t.token) * 2.0 as weight from game_mechanism_relations r${approvedRelationJoin} join game_mechanisms m on m.id = r.mechanism_id and m.is_public = true join tokens t on (lower(m.name_ko) like '%' || t.token || '%' or lower(m.name_en) like '%' || t.token || '%') group by r.game_id), category_matches as (select r.game_id as game_id, count(distinct t.token) * 2.0 as weight from game_category_relations r${approvedRelationJoin} join game_categories c on c.id = r.category_id join tokens t on (lower(c.name_ko) like '%' || t.token || '%' or lower(c.name_en) like '%' || t.token || '%') group by r.game_id), theme_matches as (select r.game_id as game_id, count(distinct t.token) * 2.0 as weight from game_theme_relations r${approvedRelationJoin} join game_themes th on th.id = r.theme_id join tokens t on (lower(th.name_ko) like '%' || t.token || '%' or lower(th.name_en) like '%' || t.token || '%') group by r.game_id), combined as (select game_id, weight from name_matches union all select game_id, weight from description_matches union all select game_id, weight from mechanism_matches union all select game_id, weight from category_matches union all select game_id, weight from theme_matches) select game_id, sum(weight) as score from combined group by game_id order by score desc, game_id asc limit ${limit};`;
}

function fuse(denseCandidates, sparseCandidates, rrfK) {
    const scoreByGameId = new Map();
    for (const candidates of [denseCandidates, sparseCandidates]) {
        for (let index = 0; index < candidates.length; index++) {
            const candidate = candidates[index];
            const rank = index + 1;
            scoreByGameId.set(candidate.gameId, (scoreByGameId.get(candidate.gameId) || 0) + 1 / (rrfK + rank));
        }
    }
    return [...scoreByGameId.entries()]
        .map(([gameId, score]) => ({ gameId, score }))
        .sort((left, right) => right.score - left.score || left.gameId - right.gameId);
}

function overlap(left, right) {
    const rightTop20 = new Set(right.slice(0, 20).map((candidate) => candidate.gameId));
    return left.slice(0, 20).filter((candidate) => rightTop20.has(candidate.gameId)).length;
}

function droppedTop20(left, right) {
    const leftTop20 = new Set(left.slice(0, 20).map((candidate) => candidate.gameId));
    return right.slice(0, 20).filter((candidate) => !leftTop20.has(candidate.gameId)).length;
}

function rankOf(candidates, gameId) {
    if (!gameId) return null;
    const index = candidates.findIndex((candidate) => candidate.gameId === gameId);
    return index < 0 ? null : index + 1;
}

function tokenize(rawQuery) {
    const tokens = new Set();
    for (const token of rawQuery.toLocaleLowerCase("ko-KR").split(TOKEN_SPLIT)) {
        if (token.length >= MIN_TOKEN_LENGTH) tokens.add(token.replaceAll("\\", "\\\\").replaceAll("%", "\\%").replaceAll("_", "\\_"));
    }
    return [...tokens];
}

function sqlLiteral(value) {
    return value.replaceAll("'", "''");
}

function runPsql(container, sql) {
    try {
        return execFileSync("docker", [
            "exec",
            "-e",
            `PGQUERY=${sql}`,
            container,
            "sh",
            "-c",
            "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -v ON_ERROR_STOP=1 -AtF \"|\" -c \"$PGQUERY\"",
        ], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024 });
    } catch (error) {
        const stderr = error.stderr?.toString().trim();
        const detail = stderr || error.message || `exit=${error.status ?? "unknown"}`;
        throw new Error(`PostgreSQL 측정 query 실패: ${detail}`);
    }
}

async function runPsqlAsync(container, sql, signal) {
    try {
        const result = await execFileAsync("docker", [
            "exec",
            "-e",
            `PGQUERY=${sql}`,
            container,
            "sh",
            "-c",
            "psql -U \"$POSTGRES_USER\" -d \"$POSTGRES_DB\" -v ON_ERROR_STOP=1 -AtF \"|\" -c \"$PGQUERY\"",
        ], { encoding: "utf8", maxBuffer: 64 * 1024 * 1024, signal });
        return result.stdout;
    } catch (error) {
        const stderr = error.stderr?.toString().trim();
        const detail = stderr || error.message || `exit=${error.status ?? "unknown"}`;
        throw new Error(`PostgreSQL 측정 query 실패: ${detail}`);
    }
}

function cloudflareEndpoint() {
    if (!process.env.CLOUDFLARE_ACCOUNT_ID || !process.env.CLOUDFLARE_API_TOKEN) {
        throw new Error("CLOUDFLARE_ACCOUNT_ID와 CLOUDFLARE_API_TOKEN이 필요합니다.");
    }
    return `https://api.cloudflare.com/client/v4/accounts/${process.env.CLOUDFLARE_ACCOUNT_ID}/ai/run/${MODEL.model}`;
}

function validateSearchText(searchText) {
    if (searchText?.gameCount !== SEARCH_TEXT_ROW_COUNT || !Array.isArray(searchText.games)
        || searchText.games.length !== SEARCH_TEXT_ROW_COUNT) {
        throw new Error("승인된 search_text artifact는 정확히 1,000개여야 합니다.");
    }
    const gameIds = new Set();
    for (const game of searchText.games) {
        if (!Number.isSafeInteger(game?.gameId) || gameIds.has(game.gameId) || !game.searchText?.trim()) {
            throw new Error("search_text artifact의 game ID 또는 searchText가 올바르지 않습니다.");
        }
        gameIds.add(game.gameId);
    }
}

function gitHead() {
    try {
        return execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim();
    } catch {
        return "unknown";
    }
}

function runnerProvenance() {
    const runnerPath = path.relative(REPOSITORY_ROOT, fileURLToPath(import.meta.url));
    return {
        path: runnerPath,
        sourceGitHead: gitHead(),
        fileSha256: sha256(fs.readFileSync(fileURLToPath(import.meta.url))),
        sourceClean: gitSourceClean(),
    };
}

function gitSourceClean() {
    try {
        return execFileSync("git", ["status", "--porcelain=v1", "--untracked-files=all"], {
            cwd: REPOSITORY_ROOT,
            encoding: "utf8",
        }).trim() === "";
    } catch {
        return false;
    }
}

function percentile(sorted, value) {
    const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(value / 100 * sorted.length) - 1));
    return sorted[index];
}

function roundMillis(value) {
    return Math.round(value * 1000) / 1000;
}

function readJson(filePath) {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function writeJson(filePath, value) {
    fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function parseArgs(argv) {
    const mode = argv[0] === "--validate" ? "validate" : "run";
    if (mode === "validate") argv = argv.slice(1);
    const values = new Map();
    let skipValidation = false;
    if (mode === "validate") {
        if (!argv[0]) throw new Error("--validate 대상 파일이 필요합니다.");
        values.set("--input", path.resolve(argv.shift()));
    }
    for (let index = 0; index < argv.length; index++) {
        const argument = argv[index];
        if (argument === "--search-text" || argument === "--postgres-container" || argument === "--out"
            || argument === "--manifest") {
            if (!argv[index + 1]) throw new Error(`${argument} 값이 필요합니다.`);
            values.set(argument, argv[++index]);
        } else if (argument === "--skip-validation") {
            skipValidation = true;
        } else {
            throw new Error(`알 수 없는 인자: ${argument}`);
        }
    }
    const manifest = path.resolve(REPOSITORY_ROOT, values.get("--manifest") || DEFAULT_MANIFEST);
    if (mode === "validate") {
        return { mode, input: values.get("--input"), manifest };
    }
    return {
        mode,
        manifest,
        skipValidation,
        searchText: path.resolve(REPOSITORY_ROOT, values.get("--search-text") || DEFAULT_SEARCH_TEXT),
        postgresContainer: values.get("--postgres-container") || process.env.ALBAM_MATE_POSTGRES_CONTAINER,
        output: path.resolve(REPOSITORY_ROOT, values.get("--out") || DEFAULT_OUTPUT),
    };
}

function assertObject(value, field) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) throw new Error(`${field}가 object가 아닙니다.`);
}

function assertEqual(actual, expected, message) {
    if (actual !== expected) throw new Error(`${message} actual=${String(actual)} expected=${String(expected)}`);
}

function assertInteger(value, field) {
    if (!Number.isInteger(value)) throw new Error(`${field}가 정수가 아닙니다.`);
}

function assertPositiveNumber(value, field) {
    if (!(typeof value === "number" && Number.isFinite(value) && value > 0)) throw new Error(`${field}가 양수가 아닙니다.`);
}

function assertSha256(value, field) {
    if (typeof value !== "string" || !/^[a-f0-9]{64}$/u.test(value)) throw new Error(`${field}가 SHA-256이 아닙니다.`);
}

if (import.meta.url === `file://${process.argv[1]}`) {
    main().catch((error) => {
        console.error(error.message);
        process.exitCode = 1;
    });
}
