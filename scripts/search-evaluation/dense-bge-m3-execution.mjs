#!/usr/bin/env node

import assert from "node:assert/strict";
import fs from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import process from "node:process";
import { pathToFileURL } from "node:url";

import { buildGoldJudgementPacket } from "./build-gold-judgement-packet.mjs";

export const MODEL_DESCRIPTOR = Object.freeze({
    provider: "local",
    modelId: "BAAI/bge-m3",
    revision: "5617a9f61b028005a4858fdac845db406aefb181",
    dimension: 1024,
    pooling: "cls",
    normalized: true,
    prefix: "none",
    denseOnly: true,
    similarity: "normalized-dot",
});

export const PLAY_INTENT_QUERIES = Object.freeze({
    "Q-010": "가볍게 웃으면서 즐길 수 있는 게임",
    "Q-011": "상대의 반응을 살피며 서로 눈치를 보는 재미가 있는 게임",
    "Q-012": "보드게임을 처음 하는 초보자와도 부담 없이 시작할 수 있는 게임",
});

export const APPROVAL_REFERENCE = "https://github.com/bamsongi-club/albam-mate/issues/868#issuecomment-5341110812";
export const APPROVED_INPUT_GIT_HEAD = "592de01644e33554dcce5a13bfcb5e9d5bfac882";
export const BLIND_SEED = "search-04-dense-bge-m3-v1";
export const APPROVED_MODEL_ARTIFACT_FILES = Object.freeze({
    "1_Pooling/config.json": "e54c164a07274f2eb45bb724f54a79d1efcc90c41573887cd9a29aeee0597352",
    "config.json": "26159e7ad065073448460117eb24b7a4572f6f4e78eadff65dc0a11c052449fa",
    "config_sentence_transformers.json": "1eef72430e7194a1e59680e635aed81ffa083f05668dbc5bb1c56c04c0999c38",
    "modules.json": "84e40c8e006c9b1d6c122e02cba9b02458120b5fb0c87b746c41e0207cf642cf",
    "pytorch_model.bin": "b5e0ce3470abf5ef3831aa1bd5553b486803e83251590ab7ff35a117cf6aad38",
    "sentence_bert_config.json": "eb9b44b13c0f52a3b3685c3b1cbdea1ba8b04bea123b98f61610048940776eb1",
    "sentencepiece.bpe.model": "cfc8146abe2a0488e9e2a0c56de7952f7c11ab059eca145a0a727afce0db2865",
    "special_tokens_map.json": "8c785abebea9ae3257b61681b4e6fd8365ceafde980c21970d001e834cf10835",
    "tokenizer.json": "21106b6d7dab2952c1d496fb21d5dc9db75c28ed361a05f5020bbba27810dd08",
    "tokenizer_config.json": "a62b2b6784f990259fddef5f16388693a8043be4f69179e6a5257eeb3f9abac4",
});

const SHA256 = /^[a-f0-9]{64}$/u;
const EXECUTION_KIND = "search-04-dense-execution";
const RUNTIME_FIELDS = ["python", "sentenceTransformers", "torch", "numpy", "device"];

export function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
}

export function readJson(filePath) {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

export function validateDenseExecutionManifest(manifest, { baseDir, verifyFiles = false } = {}) {
    assertObject(manifest, "manifest");
    assertEqual(manifest.schemaVersion, 1, "manifest.schemaVersion은 1이어야 합니다.");
    assertEqual(manifest.kind, EXECUTION_KIND, `manifest.kind는 ${EXECUTION_KIND}이어야 합니다.`);
    assertEqual(manifest.featureId, "SEARCH-04", "manifest.featureId가 올바르지 않습니다.");
    assertEqual(manifest.evaluationProfile, "development-seed", "development-seed 실행만 허용합니다.");
    if (!["completed", "evidence-only"].includes(manifest.status)) fail("실패하거나 미완료한 실행만 manifest에 기록할 수 없습니다.");
    assertEqual(manifest.execution?.mode, "offline", "offline 실행만 허용합니다.");
    assertEqual(manifest.execution?.network, "disabled", "실행 네트워크는 disabled여야 합니다.");
    assertEqual(manifest.execution?.externalApi, false, "외부 Embedding API를 사용할 수 없습니다.");
    assertEqual(manifest.execution?.secretRequired, false, "secret이 필요한 실행은 허용하지 않습니다.");
    assertEqual(manifest.execution?.provider, "local", "provider는 local이어야 합니다.");
    validateRuntime(manifest.execution?.runtime, "execution.runtime");
    assertEqual(manifest.model?.provider, MODEL_DESCRIPTOR.provider, "model.provider가 올바르지 않습니다.");
    for (const field of Object.keys(MODEL_DESCRIPTOR)) {
        assertEqual(manifest.model?.[field], MODEL_DESCRIPTOR[field], `model.${field}가 승인 계약과 다릅니다.`);
    }
    assertNonEmptyString(manifest.approvalReference, "approvalReference");
    assertEqual(manifest.approvalReference, APPROVAL_REFERENCE, "승인된 #868 코멘트만 사용할 수 있습니다.");
    assertNonEmptyString(manifest.branch, "branch");
    assertGitSha(manifest.inputGitHead, "inputGitHead");
    assertEqual(manifest.inputGitHead, APPROVED_INPUT_GIT_HEAD, "승인된 입력 Git SHA만 사용할 수 있습니다.");
    validateSource(manifest.sources?.qualityCorpus, "sources.qualityCorpus", baseDir, verifyFiles);
    validateSource(manifest.sources?.searchText, "sources.searchText", baseDir, verifyFiles);
    validateSource(manifest.sources?.queries, "sources.queries", baseDir, verifyFiles);
    validateSource(manifest.sources?.displayMap, "sources.displayMap", baseDir, verifyFiles);
    validateSource(manifest.sources?.modelArtifactManifest, "sources.modelArtifactManifest", baseDir, verifyFiles);
    if (verifyFiles) {
        const artifactManifestPath = resolveSafe(baseDir, manifest.sources.modelArtifactManifest.path, "sources.modelArtifactManifest");
        validateModelArtifactManifest(readJson(artifactManifestPath));
    }
    validateOutput(manifest.outputs?.results, "outputs.results", baseDir, verifyFiles, 3);
    validateOutput(manifest.outputs?.candidatePool, "outputs.candidatePool", baseDir, verifyFiles, 3);
    validateOutput(manifest.outputs?.blindJudgement, "outputs.blindJudgement", baseDir, verifyFiles, 3);
    validateOutput(manifest.outputs?.goldJudgementPacket, "outputs.goldJudgementPacket", baseDir, verifyFiles, 3);
    assertEqual(manifest.outputs.results.topK, 20, "results.topK은 20이어야 합니다.");
    assertEqual(manifest.hybrid?.status, "deferred", "현재 #866 결과가 없어 Hybrid는 deferred여야 합니다.");
    assertNonEmptyString(manifest.hybrid?.reason, "hybrid.reason");
    assertEqual(manifest.quality?.status, "unjudged", "사람 판정 전 품질 상태는 unjudged여야 합니다.");
    assertEqual(manifest.quality?.qualityReady, false, "사람 판정 전 quality-ready를 표시할 수 없습니다.");
    return manifest;
}

export function validateDenseQueries(queries) {
    if (!Array.isArray(queries) || queries.length !== 3) fail("Dense play-intent query는 정확히 3개여야 합니다.");
    const seen = new Set();
    for (const query of queries) {
        assertObject(query, "query");
        if (!Object.hasOwn(PLAY_INTENT_QUERIES, query.id) || seen.has(query.id)) fail(`query ID가 올바르지 않거나 중복되었습니다: ${query.id}`);
        seen.add(query.id);
        assertEqual(query.query, PLAY_INTENT_QUERIES[query.id], `${query.id} query가 승인 문구와 다릅니다.`);
        if (!Array.isArray(query.cohorts) || !query.cohorts.includes("intent/description")) fail(`${query.id}는 intent/description cohort여야 합니다.`);
        if (query.labelStatus !== "unjudged") fail(`${query.id} labelStatus는 unjudged여야 합니다.`);
        if (query.evaluationStatus !== "development") fail(`${query.id} evaluationStatus는 development여야 합니다.`);
        if (!query.hardFilters || Object.keys(query.hardFilters).length !== 0) fail(`${query.id}는 이번 실행에서 hard filter를 가져서는 안 됩니다.`);
        if (Object.hasOwn(query, "expectedGameIds") && query.expectedGameIds.length > 0) {
            fail(`${query.id}의 provisional expected ID를 새 gold로 사용할 수 없습니다.`);
        }
        assertNonEmptyString(query.judgementRubric?.relevant, `${query.id}.judgementRubric.relevant`);
        assertNonEmptyString(query.judgementRubric?.borderline, `${query.id}.judgementRubric.borderline`);
        assertNonEmptyString(query.judgementRubric?.irrelevant, `${query.id}.judgementRubric.irrelevant`);
    }
    return queries;
}

export function validateDenseInputArtifacts({ qualityCorpus, searchText, displayMap, queries }) {
    assertObject(qualityCorpus, "qualityCorpus");
    if (!Array.isArray(qualityCorpus.members) || qualityCorpus.members.length !== 1000) {
        fail("qualityCorpus는 1,000개 member를 가져야 합니다.");
    }
    const qualityIds = new Set();
    for (const member of qualityCorpus.members) {
        if (!Number.isSafeInteger(member?.gameId) || member.gameId < 1 || qualityIds.has(member.gameId)) {
            fail("qualityCorpus gameId는 중복 없는 양의 정수여야 합니다.");
        }
        qualityIds.add(member.gameId);
    }

    assertObject(searchText, "searchText");
    if (searchText.gameCount !== 1000 || !Array.isArray(searchText.games) || searchText.games.length !== 1000) {
        fail("searchText는 정확히 1,000개 game을 가져야 합니다.");
    }
    const searchTextIds = new Set();
    for (const game of searchText.games) {
        if (!Number.isSafeInteger(game?.gameId) || game.gameId < 1 || searchTextIds.has(game.gameId)) {
            fail("searchText gameId는 중복 없는 양의 정수여야 합니다.");
        }
        assertNonEmptyString(game.searchText, `searchText.${game.gameId}.searchText`);
        searchTextIds.add(game.gameId);
    }

    assertObject(displayMap, "displayMap");
    if (displayMap.gameCount !== 1000 || !Array.isArray(displayMap.games) || displayMap.games.length !== 1000) {
        fail("displayMap은 정확히 1,000개 game을 가져야 합니다.");
    }
    const displayById = new Map();
    for (const game of displayMap.games) {
        if (!Number.isSafeInteger(game?.gameId) || game.gameId < 1 || displayById.has(game.gameId)) {
            fail("displayMap gameId는 중복 없는 양의 정수여야 합니다.");
        }
        for (const field of ["name", "englishName"]) {
            if (game[field] !== null && typeof game[field] !== "string") {
                fail(`displayMap.${game.gameId}.${field}가 올바르지 않습니다.`);
            }
        }
        displayById.set(game.gameId, { name: game.name ?? null, englishName: game.englishName ?? null });
    }

    assertSameGameIdSet(qualityIds, searchTextIds, "qualityCorpus와 searchText의 gameId 집합이 다릅니다.");
    assertSameGameIdSet(searchTextIds, new Set(displayById.keys()), "searchText와 displayMap의 gameId 집합이 다릅니다.");
    const validatedQueries = validateDenseQueries(queries);
    return {
        corpusRows: searchTextIds.size,
        gameIds: searchTextIds,
        displayById,
        queriesById: new Map(validatedQueries.map((query) => [query.id, query])),
    };
}

export function validateDenseResults(results, {
    expectedQueryIds = Object.keys(PLAY_INTENT_QUERIES),
    topK = 20,
    expectedProvenance,
    expectedInputs,
} = {}) {
    assertObject(results, "results");
    assertEqual(results.schemaVersion, 1, "results.schemaVersion은 1이어야 합니다.");
    assertEqual(results.kind, EXECUTION_KIND, "results.kind가 올바르지 않습니다.");
    for (const field of Object.keys(MODEL_DESCRIPTOR)) assertEqual(results.model?.[field], MODEL_DESCRIPTOR[field], `results.model.${field}가 올바르지 않습니다.`);
    assertEqual(results.topK, topK, `results.topK은 ${topK}이어야 합니다.`);
    validateRuntime(results.runtime, "results.runtime");
    assertEqual(results.inputs?.corpusRows, expectedInputs?.corpusRows ?? 1000, "results.inputs.corpusRows가 고정 corpus와 다릅니다.");
    if (expectedProvenance) validateResultProvenance(results, expectedProvenance);
    if (!Array.isArray(results.queries) || results.queries.length !== expectedQueryIds.length) fail("results의 query 수가 fixture와 다릅니다.");
    const expected = new Set(expectedQueryIds);
    const seenQueries = new Set();
    for (const query of results.queries) {
        if (!expected.has(query.id) || seenQueries.has(query.id)) fail(`results query ID가 올바르지 않거나 중복되었습니다: ${query.id}`);
        seenQueries.add(query.id);
        const expectedQuery = expectedInputs?.queriesById.get(query.id);
        if (expectedInputs && !expectedQuery) fail(`results의 ${query.id}가 고정 queries에 없습니다.`);
        if (expectedQuery) assertEqual(query.query, expectedQuery.query, `${query.id} query 문구가 고정 queries와 다릅니다.`);
        if (!Array.isArray(query.ranked) || query.ranked.length !== topK) fail(`${query.id} 결과는 Top${topK}이어야 합니다.`);
        const seenGames = new Set();
        let previous;
        query.ranked.forEach((row, index) => {
            if (!Number.isSafeInteger(row.gameId) || row.gameId < 1 || seenGames.has(row.gameId)) fail(`${query.id}의 gameId가 없거나 중복되었습니다.`);
            seenGames.add(row.gameId);
            if (expectedInputs && !expectedInputs.gameIds.has(row.gameId)) {
                fail(`${query.id}의 gameId가 고정 Top 1,000 membership에 없습니다.`);
            }
            if (expectedInputs) {
                const expectedDisplay = expectedInputs.displayById.get(row.gameId);
                if (row.name !== expectedDisplay.name || row.englishName !== expectedDisplay.englishName) {
                    fail(`${query.id}의 gameId ${row.gameId} display 값이 고정 displayMap과 다릅니다.`);
                }
            }
            assertEqual(row.rank, index + 1, `${query.id} rank가 연속적이지 않습니다.`);
            if (!Number.isFinite(row.score)) fail(`${query.id} score에 NaN/Inf가 있습니다.`);
            if (previous !== undefined && (row.score > previous.score || (row.score === previous.score && row.gameId < previous.gameId))) {
                fail(`${query.id} 결과가 score DESC·gameId ASC 순서가 아닙니다.`);
            }
            previous = row;
        });
        if (query.hardFilterViolationGameIds?.length > 0) fail(`${query.id}에 hard-filter 위반 결과가 있습니다.`);
    }
    if (seenQueries.size !== expected.size) fail("results에 fixture query가 모두 포함되지 않았습니다.");
    return results;
}

export function calculateGradedMetrics({ grades, rankedGameIds, k = 10 }) {
    if (!Number.isInteger(k) || k < 1) fail("k는 1 이상의 정수여야 합니다.");
    assertObject(grades, "grades");
    if (!Array.isArray(rankedGameIds)) fail("rankedGameIds는 배열이어야 합니다.");
    const seenRankedGameIds = new Set();
    for (const gameId of rankedGameIds) {
        if (!Number.isSafeInteger(gameId) || gameId < 1 || seenRankedGameIds.has(gameId)) fail("rankedGameIds는 중복 없는 양의 정수여야 합니다.");
        seenRankedGameIds.add(gameId);
    }
    const gradesByGameId = new Map();
    const gradeEntries = Object.entries(grades).map(([gameId, grade]) => {
        if (!/^\d+$/u.test(gameId) || String(Number(gameId)) !== gameId) fail("grade gameId는 canonical decimal key여야 합니다.");
        const id = Number(gameId);
        if (!Number.isSafeInteger(id) || id < 1 || ![0, 1, 2].includes(grade) || gradesByGameId.has(id)) fail("grade는 중복 없는 양의 gameId별 0·1·2 중 하나여야 합니다.");
        gradesByGameId.set(id, grade);
        return [id, grade];
    });
    const relevant = new Set(gradeEntries.filter(([, grade]) => grade === 2).map(([id]) => id));
    const topK = rankedGameIds.slice(0, k);
    const firstRelevant = topK.findIndex((gameId) => relevant.has(gameId));
    const recallAtK = relevant.size === 0 ? 0 : topK.filter((gameId) => relevant.has(gameId)).length / relevant.size;
    const mrrAtK = firstRelevant < 0 ? 0 : 1 / (firstRelevant + 1);
    const dcg = topK.reduce((sum, gameId, index) => sum + gain(gradesByGameId.get(gameId) ?? 0) / Math.log2(index + 2), 0);
    const ideal = gradeEntries.map(([, grade]) => grade).sort((left, right) => right - left).slice(0, k);
    const idcg = ideal.reduce((sum, grade, index) => sum + gain(grade) / Math.log2(index + 2), 0);
    return { k, recallAtK, mrrAtK, ndcgAtK: idcg === 0 ? 0 : dcg / idcg, judgedRelevantCount: relevant.size };
}

export function buildCandidatePool(results) {
    validateDenseResults(results);
    return {
        schemaVersion: 1,
        kind: "search-04-dense-candidate-pool",
        topK: results.topK,
        sourceModels: [MODEL_DESCRIPTOR.modelId],
        queries: results.queries.map((query) => ({
            id: query.id,
            candidates: query.ranked.map((row) => ({
                gameId: row.gameId,
                display: { name: row.name ?? null, englishName: row.englishName ?? null },
                sources: [{ modelId: MODEL_DESCRIPTOR.modelId, rank: row.rank, score: row.score }],
            })),
        })),
    };
}

export function buildBlindJudgementExport({ queries, candidatePool, seed = BLIND_SEED }) {
    validateDenseQueries(queries);
    assertObject(candidatePool, "candidatePool");
    return {
        schemaVersion: 1,
        kind: "search-04-blind-judgement",
        seed,
        hides: ["modelId", "score", "sourceRank"],
        gradeScale: { relevant: 2, borderline: 1, irrelevant: 0 },
        queries: queries.map((query) => {
            const pool = candidatePool.queries.find((item) => item.id === query.id);
            if (!pool) fail(`candidatePool에 ${query.id}가 없습니다.`);
            const candidates = [...pool.candidates].sort((left, right) => {
                const leftKey = stableKey(seed, query.id, left.gameId);
                const rightKey = stableKey(seed, query.id, right.gameId);
                return leftKey < rightKey ? -1 : leftKey > rightKey ? 1 : left.gameId - right.gameId;
            });
            return {
                id: query.id,
                query: query.query,
                judgementRubric: query.judgementRubric,
                candidates: candidates.map((candidate, index) => ({
                    blindRank: index + 1,
                    gameId: candidate.gameId,
                    name: candidate.display.name,
                    englishName: candidate.display.englishName,
                    grade: null,
                })),
            };
        }),
    };
}

export function validateCandidatePool(candidatePool, results) {
    const expected = buildCandidatePool(results);
    try {
        assert.deepStrictEqual(candidatePool, expected);
    } catch {
        fail("candidatePool이 results에서 재구성한 후보와 다릅니다.");
    }
    return candidatePool;
}

export function validateBlindJudgementExport(blind, { queries, candidatePool }) {
    assertObject(blind, "blindJudgement");
    assertEqual(blind.seed, BLIND_SEED, "blindJudgement.seed가 승인된 값과 다릅니다.");
    const expected = buildBlindJudgementExport({ queries, candidatePool, seed: BLIND_SEED });
    try {
        assert.deepStrictEqual(blind, expected);
    } catch {
        fail("blindJudgement가 승인된 candidatePool에서 재구성한 결과와 다릅니다.");
    }
    return blind;
}

export function validateGoldJudgementPacket(packet, { queries, candidatePool, blindJudgement, searchText, sources }) {
    validateBlindJudgementExport(blindJudgement, { queries, candidatePool });
    const expected = buildGoldJudgementPacket({ blind: blindJudgement, searchText, sources });
    try {
        assert.deepStrictEqual(packet, expected);
    } catch {
        fail("goldJudgementPacket이 검증된 blind/searchText에서 재구성한 결과와 다릅니다.");
    }
    return packet;
}

function validateSource(source, field, baseDir, verifyFiles) {
    assertObject(source, field);
    assertSafeRelativePath(source.path, `${field}.path`);
    assertSha256(source.sha256, `${field}.sha256`);
    if (!Number.isSafeInteger(source.rows) || source.rows < 1) fail(`${field}.rows가 올바르지 않습니다.`);
    if (verifyFiles) verifyArtifact(source, field, baseDir);
}

function validateOutput(output, field, baseDir, verifyFiles, expectedRows) {
    validateSource(output, field, baseDir, verifyFiles);
    if (output.rows !== expectedRows) fail(`${field}.rows는 ${expectedRows}이어야 합니다.`);
}

function validateRuntime(runtime, field) {
    assertObject(runtime, field);
    for (const runtimeField of RUNTIME_FIELDS) assertNonEmptyString(runtime[runtimeField], `${field}.${runtimeField}`);
}

function validateModelArtifactManifest(artifact) {
    assertObject(artifact, "modelArtifactManifest");
    assertEqual(artifact.schemaVersion, 1, "modelArtifactManifest.schemaVersion은 1이어야 합니다.");
    assertEqual(artifact.kind, "search-04-local-model-artifact", "modelArtifactManifest.kind가 올바르지 않습니다.");
    assertEqual(artifact.modelId, MODEL_DESCRIPTOR.modelId, "modelArtifactManifest.modelId가 올바르지 않습니다.");
    assertEqual(artifact.revision, MODEL_DESCRIPTOR.revision, "modelArtifactManifest.revision이 올바르지 않습니다.");
    const files = {};
    if (!Array.isArray(artifact.requiredFiles)) fail("modelArtifactManifest.requiredFiles가 없습니다.");
    for (const file of artifact.requiredFiles) {
        assertObject(file, "modelArtifactManifest.requiredFile");
        assertSafeRelativePath(file.path, "modelArtifactManifest.requiredFile.path");
        assertSha256(file.sha256, `modelArtifactManifest.${file.path}.sha256`);
        if (Object.hasOwn(files, file.path)) fail(`modelArtifactManifest.requiredFiles가 중복되었습니다: ${file.path}`);
        files[file.path] = file.sha256;
    }
    try {
        assert.deepStrictEqual(files, APPROVED_MODEL_ARTIFACT_FILES);
    } catch {
        fail("modelArtifactManifest.requiredFiles가 승인된 BGE-M3 snapshot과 다릅니다.");
    }
}

function validateResultProvenance(results, expected) {
    assertEqual(results.sourceGitHead, expected.sourceGitHead, "results.sourceGitHead가 manifest.inputGitHead와 다릅니다.");
    for (const field of ["qualityCorpusSha256", "searchTextSha256", "querySha256", "displayMapSha256", "modelArtifactManifestSha256"]) {
        assertEqual(results.inputs?.[field], expected[field], `results.inputs.${field}가 manifest source와 다릅니다.`);
    }
}

function assertSameGameIdSet(left, right, message) {
    if (left.size !== right.size || [...left].some((gameId) => !right.has(gameId))) fail(message);
}

function verifyArtifact(descriptor, field, baseDir) {
    if (!baseDir) fail(`파일 checksum 검증에는 baseDir가 필요합니다: ${field}`);
    const actualPath = resolveSafe(baseDir, descriptor.path, field);
    const contents = fs.readFileSync(actualPath);
    if (sha256(contents) !== descriptor.sha256) fail(`${field}.sha256가 실제 파일과 다릅니다.`);
}

function resolveSafe(baseDir, relativePath, field) {
    const actual = path.resolve(baseDir, relativePath);
    const root = path.resolve(baseDir);
    if (actual !== root && !actual.startsWith(`${root}${path.sep}`)) fail(`${field}.path가 manifest 디렉터리 밖을 가리킵니다.`);
    return actual;
}

function assertSafeRelativePath(value, field) {
    assertNonEmptyString(value, field);
    if (path.isAbsolute(value) || /^[a-zA-Z]:[\\/]/u.test(value) || value.split(/[\\/]/u).includes("..")) fail(`${field}는 안전한 상대 경로여야 합니다.`);
}

function stableKey(seed, queryId, gameId) {
    return sha256(`${seed}\u0000${queryId}\u0000${gameId}`);
}

function gain(grade) {
    return (2 ** grade) - 1;
}

function assertObject(value, field) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) fail(`${field}는 object여야 합니다.`);
}

function assertEqual(actual, expected, message) {
    if (actual !== expected) fail(message);
}

function assertNonEmptyString(value, field) {
    if (typeof value !== "string" || value.trim() === "") fail(`${field}가 없습니다.`);
}

function assertSha256(value, field) {
    if (typeof value !== "string" || !SHA256.test(value)) fail(`${field}는 SHA-256이어야 합니다.`);
}

function assertGitSha(value, field) {
    if (typeof value !== "string" || !/^[a-f0-9]{40}$/u.test(value)) fail(`${field}는 40자리 Git SHA여야 합니다.`);
}

function fail(message) {
    throw new Error(message);
}

function parseArgs(argv) {
    if (argv.length === 3 && argv[0] === "--check" && argv[1] === "--manifest" && argv[2]) {
        return { mode: "check", manifest: argv[2] };
    }
    if (argv.length === 5 && argv[0] === "--assemble" && argv[1] === "--manifest" && argv[2] && argv[3] === "--results" && argv[4]) {
        return { mode: "assemble", manifest: argv[2], results: argv[4] };
    }
    return null;
}

function manifestInputPaths(manifest, baseDir) {
    return {
        qualityCorpus: resolveSafe(baseDir, manifest.sources.qualityCorpus.path, "sources.qualityCorpus"),
        searchText: resolveSafe(baseDir, manifest.sources.searchText.path, "sources.searchText"),
        queries: resolveSafe(baseDir, manifest.sources.queries.path, "sources.queries"),
        displayMap: resolveSafe(baseDir, manifest.sources.displayMap.path, "sources.displayMap"),
    };
}

function resultProvenance(manifest) {
    return {
        sourceGitHead: manifest.inputGitHead,
        qualityCorpusSha256: manifest.sources.qualityCorpus.sha256,
        searchTextSha256: manifest.sources.searchText.sha256,
        querySha256: manifest.sources.queries.sha256,
        displayMapSha256: manifest.sources.displayMap.sha256,
        modelArtifactManifestSha256: manifest.sources.modelArtifactManifest.sha256,
    };
}

function goldSources(manifest, blindJudgement = manifest.outputs.blindJudgement) {
    return {
        blindJudgement: {
            path: blindJudgement.path,
            sha256: blindJudgement.sha256,
        },
        searchText: manifest.sources.searchText,
    };
}

function serializeJson(value) {
    return `${JSON.stringify(value, null, 2)}\n`;
}

function outputDescriptor(output, content, rows) {
    return { ...output, sha256: sha256(Buffer.from(content)), rows };
}

export function assembleDenseExecutionFiles({ manifest, baseDir, manifestPath, results }) {
    validateDenseExecutionManifest(manifest, { baseDir, verifyFiles: true });
    const inputPaths = manifestInputPaths(manifest, baseDir);
    const inputs = validateDenseInputArtifacts({
        qualityCorpus: readJson(inputPaths.qualityCorpus),
        searchText: readJson(inputPaths.searchText),
        displayMap: readJson(inputPaths.displayMap),
        queries: readJson(inputPaths.queries),
    });
    validateDenseResults(results, {
        topK: manifest.outputs.results.topK,
        expectedProvenance: resultProvenance(manifest),
        expectedInputs: inputs,
    });
    const queries = readJson(inputPaths.queries);
    const searchText = readJson(inputPaths.searchText);
    const candidatePool = buildCandidatePool(results);
    const blindJudgement = buildBlindJudgementExport({ queries, candidatePool });
    const blindContent = serializeJson(blindJudgement);
    const blindDescriptor = outputDescriptor(manifest.outputs.blindJudgement, blindContent, blindJudgement.queries.length);
    const goldJudgementPacket = buildGoldJudgementPacket({
        blind: blindJudgement,
        searchText,
        sources: goldSources(manifest, blindDescriptor),
    });
    const contents = {
        results: serializeJson(results),
        candidatePool: serializeJson(candidatePool),
        blindJudgement: blindContent,
        goldJudgementPacket: serializeJson(goldJudgementPacket),
    };
    const nextManifest = structuredClone(manifest);
    nextManifest.outputs.results = outputDescriptor(manifest.outputs.results, contents.results, results.queries.length);
    nextManifest.outputs.candidatePool = outputDescriptor(manifest.outputs.candidatePool, contents.candidatePool, candidatePool.queries.length);
    nextManifest.outputs.blindJudgement = outputDescriptor(manifest.outputs.blindJudgement, contents.blindJudgement, blindJudgement.queries.length);
    nextManifest.outputs.goldJudgementPacket = outputDescriptor(manifest.outputs.goldJudgementPacket, contents.goldJudgementPacket, goldJudgementPacket.queries.length);

    for (const [name, content] of Object.entries(contents)) {
        const outputPath = resolveSafe(baseDir, nextManifest.outputs[name].path, `outputs.${name}`);
        fs.writeFileSync(outputPath, content, "utf8");
    }
    fs.writeFileSync(manifestPath, serializeJson(nextManifest), "utf8");
    return nextManifest;
}

function main() {
    const args = parseArgs(process.argv.slice(2));
    if (!args) {
        console.error("사용법: node scripts/search-evaluation/dense-bge-m3-execution.mjs --check --manifest <manifest.json> | --assemble --manifest <manifest.json> --results <results.json>");
        process.exitCode = 2;
        return;
    }
    try {
        const manifestPath = path.resolve(args.manifest);
        const baseDir = path.dirname(manifestPath);
        const manifest = readJson(manifestPath);
        if (args.mode === "assemble") {
            const nextManifest = assembleDenseExecutionFiles({
                manifest,
                baseDir,
                manifestPath,
                results: readJson(path.resolve(args.results)),
            });
            console.log(`Dense 실행 산출물 조립 통과: ${manifestPath} (results=${nextManifest.outputs.results.sha256})`);
            return;
        }

        validateDenseExecutionManifest(manifest, { baseDir, verifyFiles: true });
        const inputPaths = manifestInputPaths(manifest, baseDir);
        const queries = readJson(inputPaths.queries);
        const searchText = readJson(inputPaths.searchText);
        const inputs = validateDenseInputArtifacts({
            qualityCorpus: readJson(inputPaths.qualityCorpus),
            searchText,
            displayMap: readJson(inputPaths.displayMap),
            queries,
        });
        const results = readJson(resolveSafe(baseDir, manifest.outputs.results.path, "outputs.results"));
        const candidatePool = readJson(resolveSafe(baseDir, manifest.outputs.candidatePool.path, "outputs.candidatePool"));
        const blindJudgement = readJson(resolveSafe(baseDir, manifest.outputs.blindJudgement.path, "outputs.blindJudgement"));
        const goldJudgementPacket = readJson(resolveSafe(baseDir, manifest.outputs.goldJudgementPacket.path, "outputs.goldJudgementPacket"));
        validateDenseResults(results, {
            topK: manifest.outputs.results.topK,
            expectedProvenance: resultProvenance(manifest),
            expectedInputs: inputs,
        });
        validateCandidatePool(candidatePool, results);
        validateBlindJudgementExport(blindJudgement, { queries, candidatePool });
        validateGoldJudgementPacket(goldJudgementPacket, {
            queries,
            candidatePool,
            blindJudgement,
            searchText,
            sources: goldSources(manifest),
        });
        console.log(`Dense 실행 manifest 검증 통과: ${manifestPath} (model=${MODEL_DESCRIPTOR.modelId}, query=3, topK=20, gold=verified)`);
    } catch (error) {
        console.error(`Dense 실행 manifest 검증 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) main();
