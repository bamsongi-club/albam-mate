#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { performance } from "node:perf_hooks";

export const LIVE_EVIDENCE_KIND = "search-04e-hybrid-rrf-live-evidence";
export const SEARCH_TEXT_ROW_COUNT = 1000;
export const MEASURE_ROUNDS = 5;
export const CANDIDATE_K_VALUES = Object.freeze([50, 100, 200, 400, 800, 1000]);
export const RRF_K_VALUES = Object.freeze([10, 30, 60, 100, 200]);
export const TIMEOUT_SECONDS_VALUES = Object.freeze([1, 2, 3, 4, 5, 6]);
export const SELECTED_CANDIDATE_K = 200;
export const SELECTED_RRF_K = 60;
export const SELECTED_TIMEOUT_SECONDS = 6;
export const DENSE_EVIDENCE_SOURCE = "CloudflareWorkersAI-direct-REST";
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

const TOKEN_SPLIT = /[^\p{L}\p{Nd}]+/u;
const MIN_TOKEN_LENGTH = 2;
const DEFAULT_SEARCH_TEXT = "docs/p2/search-evaluation/dense-bge-m3/search-text-top1000.json";
const DEFAULT_OUTPUT = "docs/measurements/search-04e-hybrid-rrf-live.json";
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

export function validateLiveEvidence(evidence) {
    assertObject(evidence, "evidence");
    if (typeof evidence.sourceGitHead !== "string" || !/^[a-f0-9]{40}$/u.test(evidence.sourceGitHead)) {
        throw new Error("sourceGitHead가 40자리 Git SHA-1이 아닙니다.");
    }
    assertEqual(evidence.schemaVersion, 1, "schemaVersion은 1이어야 합니다.");
    assertEqual(evidence.kind, LIVE_EVIDENCE_KIND, "kind가 올바르지 않습니다.");
    assertEqual(evidence.issue, 1002, "issue는 1002여야 합니다.");
    assertEqual(evidence.status, "completed", "실패한 실행은 evidence로 기록할 수 없습니다.");

    const input = evidence.input;
    assertObject(input, "input");
    assertEqual(input.corpus?.rowCount, SEARCH_TEXT_ROW_COUNT, "approved corpus는 정확히 1,000행이어야 합니다.");
    assertSha256(input.corpus?.searchTextSha256, "input.corpus.searchTextSha256");
    assertSha256(input.corpus?.gameIdMembershipSha256, "input.corpus.gameIdMembershipSha256");
    assertEqual(input.corpus.gameIdMembershipSha256, input.index?.bggGameIdMembershipSha256,
        "index와 approved corpus의 BGG game ID membership이 다릅니다.");
    assertSha256(input.index?.internalGameIdMembershipSha256, "input.index.internalGameIdMembershipSha256");
    assertEqual(input.index.internalGameIdMembershipSha256, input.release?.manifestSha256,
        "release manifest checksum과 실제 index 내부 game ID membership이 다릅니다.");
    assertEqual(input.corpus.searchTextSha256, input.release?.searchTextChecksum,
        "release search_text checksum과 corpus artifact가 다릅니다.");
    assertEqual(input.index?.rowCount, SEARCH_TEXT_ROW_COUNT, "index는 정확히 1,000행이어야 합니다.");
    assertEqual(input.index?.status, "READY", "측정 index는 READY여야 합니다.");
    assertEqual(input.index?.productionReady, false, "측정용 index를 production index로 표시할 수 없습니다.");
    assertEqual(input.index?.provider, MODEL.provider, "index provider가 Cloudflare가 아닙니다.");
    assertEqual(input.index?.model, MODEL.model, "index model이 BGE-M3가 아닙니다.");
    assertEqual(input.index?.embeddingMode, MODEL.mode, "index embedding mode가 text가 아닙니다.");
    assertEqual(input.index?.dimension, MODEL.dimension, "index dimension이 1,024가 아닙니다.");
    assertEqual(input.index?.l2Normalized, MODEL.l2Normalized, "index L2 normalization이 다릅니다.");

    const execution = evidence.execution;
    assertObject(execution, "execution");
    assertEqual(execution.dense?.indexRows, SEARCH_TEXT_ROW_COUNT,
        "Dense READY index row 수가 1,000이 아닙니다.");
    assertEqual(execution.dense?.queryEmbeddings?.requestCount, QUERY_FIXTURES.length * MEASURE_ROUNDS,
        "Dense query embedding 요청 수가 8개 질의×5회가 아닙니다.");
    assertEqual(execution.dense?.queryEmbeddings?.successCount, execution.dense?.queryEmbeddings?.requestCount,
        "Dense query embedding 요청이 모두 성공하지 않았습니다.");
    assertEqual(execution.dense?.queryEmbeddings?.failureCount, 0,
        "Dense query embedding 실패가 기록되었습니다.");
    assertEqual(execution.dense?.queryRounds, MEASURE_ROUNDS, "Dense query 반복 횟수가 5회가 아닙니다.");
    assertEqual(execution.dense?.source, DENSE_EVIDENCE_SOURCE,
        "Dense source가 승인된 Cloudflare direct REST 경로가 아닙니다.");
    assertEqual(execution.sparse?.source, SPARSE_EVIDENCE_SOURCE,
        "Sparse source가 production SQL shape 경로가 아닙니다.");
    assertEqual(execution.sparse?.scope, "approved-search-text-corpus",
        "Sparse와 Dense의 corpus scope가 다릅니다.");
    assertEqual(execution.sparse?.maxCandidateLimit, SEARCH_TEXT_ROW_COUNT,
        "Sparse full candidate 실행이 1,000 상한을 확인하지 않았습니다.");
    assertEqual(execution.sparse?.queryCount, QUERY_FIXTURES.length, "Sparse query 수가 8개가 아닙니다.");
    assertEqual(execution.fusion?.queryCount, QUERY_FIXTURES.length, "Fusion query 수가 8개가 아닙니다.");
    assertObject(execution.phaseLatency, "execution.phaseLatency");
    for (const field of ["denseQuery", "sparseSql", "fusion"]) {
        assertPositiveNumber(execution.phaseLatency[field]?.p95, `execution.phaseLatency.${field}.p95`);
        assertPositiveNumber(execution.phaseLatency[field]?.max, `execution.phaseLatency.${field}.max`);
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
    if (evidence.parameters.observedParallelP95Ms >= 6000) {
        throw new Error("관찰된 parallel p95가 6초 budget을 초과합니다.");
    }
    return { queryCount: queries.length, commonQueryCount: commonQueries.length };
}

async function main() {
    const args = parseArgs(process.argv.slice(2));
    if (args.mode === "validate") {
        const evidence = readJson(args.input);
        console.log(JSON.stringify(validateLiveEvidence(evidence)));
        return;
    }
    const evidence = await runMeasurement(args);
    writeJson(args.output, evidence);
    validateLiveEvidence(evidence);
    console.log(`SEARCH-04e live evidence written: ${args.output}`);
}

async function runMeasurement(args) {
    const startedAt = new Date().toISOString();
    const searchTextPath = path.resolve(args.searchText);
    const searchTextBytes = fs.readFileSync(searchTextPath);
    const searchText = JSON.parse(searchTextBytes.toString("utf8"));
    validateSearchText(searchText);
    const games = searchText.games;
    const gameIds = games.map((game) => game.gameId);
    const searchTextSha256 = sha256(searchTextBytes);
    const membershipSha256 = gameIdMembershipSha256(gameIds);
    const releaseId = process.env.ALBAM_MATE_SEARCH_RELEASE_ID || "bgg-catalog-170k-v4-2026-08-19";
    const fieldVersion = process.env.ALBAM_MATE_SEARCH_FIELD_VERSION || "catalog-fields-v1";

    const database = loadDatabase(args.postgresContainer, gameIds, searchTextSha256, releaseId, fieldVersion);
    const endpoint = cloudflareEndpoint();

    const queryEvidence = [];
    const denseQueryLatencies = [];
    const sparseSqlLatencies = [];
    const fusionLatencies = [];
    for (const fixture of QUERY_FIXTURES) {
        const querySamples = [];
        for (let round = 0; round < MEASURE_ROUNDS; round++) {
            const sample = await embedQuery(fixture.query, endpoint);
            querySamples.push(sample);
            denseQueryLatencies.push(sample.latencyMs);
        }
        const denseCandidates = rankDense(database.documentVectors, querySamples[0].vector);
        const sparseMeasurement = measureSparse(
            fixture.query,
            args.postgresContainer,
            database.internalGameIds,
            database.bggIdByInternalId,
        );
        sparseSqlLatencies.push(sparseMeasurement.executionMs);
        const sparseCandidates = sparseMeasurement.candidates;
        const referenceHybrid = fuse(denseCandidates, sparseCandidates.slice(0, 1000), SELECTED_RRF_K);
        const hybridK200 = fuse(denseCandidates, sparseCandidates.slice(0, SELECTED_CANDIDATE_K), SELECTED_RRF_K);
        for (let round = 0; round < MEASURE_ROUNDS; round++) {
            const started = performance.now();
            fuse(denseCandidates, sparseCandidates.slice(0, SELECTED_CANDIDATE_K), SELECTED_RRF_K);
            fusionLatencies.push(performance.now() - started);
        }
        queryEvidence.push({
            id: fixture.id,
            querySha256: sha256(Buffer.from(fixture.query, "utf8")),
            anchorGameId: fixture.anchorGameId,
            sparseFullCount: sparseCandidates.length,
            denseTop20: denseCandidates.slice(0, 20).map((candidate) => candidate.gameId),
            sparseTop20: sparseCandidates.slice(0, 20).map((candidate) => candidate.gameId),
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
        });
    }

    const phaseLatency = {
        denseQuery: summarize(denseQueryLatencies),
        sparseSql: summarize(sparseSqlLatencies),
        fusion: summarize(fusionLatencies),
    };
    const observedParallelP95Ms = Math.max(
        phaseLatency.denseQuery.p95,
        phaseLatency.sparseSql.p95,
    ) + phaseLatency.fusion.p95;
    const timeoutComparison = TIMEOUT_SECONDS_VALUES.map((timeoutSeconds) => ({
        timeoutSeconds,
        budgetMs: timeoutSeconds * 1000,
        observedParallelP95Ms,
        marginMs: timeoutSeconds * 1000 - observedParallelP95Ms,
        passesObservedBudget: observedParallelP95Ms < timeoutSeconds * 1000,
    }));

    return {
        schemaVersion: 1,
        kind: LIVE_EVIDENCE_KIND,
        issue: 1002,
        featureId: "SEARCH-04",
        status: "completed",
        sourceGitHead: gitHead(),
        input: {
            catalogRelease: {
                releaseId,
                fieldVersion,
                reference: "docs/game-catalog/catalog-dataset-release.json",
            },
            corpus: {
                reference: path.relative(process.cwd(), searchTextPath),
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
                    requestCount: QUERY_FIXTURES.length * MEASURE_ROUNDS,
                    successCount: QUERY_FIXTURES.length * MEASURE_ROUNDS,
                    failureCount: 0,
                    latencyMs: phaseLatency.denseQuery,
                },
                queryRounds: MEASURE_ROUNDS,
            },
            sparse: {
                source: SPARSE_EVIDENCE_SOURCE,
                scope: "approved-search-text-corpus",
                maxCandidateLimit: SEARCH_TEXT_ROW_COUNT,
                queryCount: QUERY_FIXTURES.length,
                executionMs: phaseLatency.sparseSql,
            },
            fusion: {
                algorithm: "reciprocal-rank-fusion",
                candidateKValues: [...CANDIDATE_K_VALUES],
                rrfKValues: [...RRF_K_VALUES],
                queryCount: QUERY_FIXTURES.length,
            },
            phaseLatency,
            observedParallelP95Ms,
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
                timeoutSeconds: "retain-6s",
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
}

async function embedQuery(query, endpoint) {
    return embed(query, endpoint);
}

async function embed(text, endpoint) {
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
            signal: AbortSignal.timeout(10000),
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

function loadDatabase(container, approvedBggIds, searchTextSha256, expectedReleaseId, expectedFieldVersion) {
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
    const vectorOutput = runPsql(container,
        `select game_id, embedding::text from semantic_game_embeddings where index_version_id = '${id}' order by game_id;`);
    const vectorRows = vectorOutput.trim() === "" ? [] : vectorOutput.trim().split(/\r?\n/u).map((line) => {
        const separator = line.indexOf("|");
        return { internalId: Number(line.slice(0, separator)), vector: parseVector(line.slice(separator + 1)) };
    });
    if (vectorRows.length !== SEARCH_TEXT_ROW_COUNT
        || new Set(vectorRows.map((row) => row.internalId)).size !== SEARCH_TEXT_ROW_COUNT
        || vectorRows.some((row) => !bggIdByInternalId.has(row.internalId) || row.vector.length !== MODEL.dimension)) {
        throw new Error("활성 READY index의 row membership 또는 vector dimension이 approved corpus와 다릅니다.");
    }
    const actualInternalIds = vectorRows.map((row) => row.internalId);
    if (gameIdMembershipSha256(actualInternalIds) !== internalMembershipSha256) {
        throw new Error("활성 READY index의 내부 game ID membership이 approved corpus mapping과 다릅니다.");
    }
    const documentVectors = new Map(vectorRows.map((row) => [bggIdByInternalId.get(row.internalId), row.vector]));
    return {
        container,
        gameCount: mappingRows.length,
        internalGameIds,
        bggIdByInternalId,
        documentVectors,
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

function measureSparse(query, container, approvedInternalIds, bggIdByInternalId) {
    const sql = sparseSql(query, approvedInternalIds, 1000);
    const explainOutput = runPsql(container, `explain (analyze, format json) ${sql}`);
    const explain = JSON.parse(explainOutput.trim())[0];
    const executionMs = Number(explain["Execution Time"]);
    const candidateRows = runPsql(container, sql);
    const candidates = candidateRows.trim() === ""
        ? []
        : candidateRows.trim().split(/\r?\n/u).map((line) => {
            const [internalId, score] = line.split("|").map(Number);
            return { gameId: bggIdByInternalId.get(internalId), score };
        });
    return { executionMs, candidates };
}

function parseVector(value) {
    const trimmed = value.trim();
    if (!trimmed.startsWith("[") || !trimmed.endsWith("]")) return [];
    return trimmed.slice(1, -1).split(",").map(Number);
}

export function sparseSql(query, approvedGameIds, limit) {
    const tokens = tokenize(query);
    if (tokens.length === 0) throw new Error(`query token이 비어 있습니다: ${query}`);
    const approved = approvedGameIds.map((gameId) => `(${gameId})`).join(",");
    const tokenValues = tokens.map((token) => `('${sqlLiteral(token)}')`).join(",");
    return `with approved(game_id) as (values ${approved}), tokens(token) as (values ${tokenValues}), name_matches as (select g.id as game_id, count(distinct t.token) * 3.0 as weight from games g join approved a on a.game_id = g.id join tokens t on (lower(g.name) like '%' || t.token || '%' or lower(g.english_name) like '%' || t.token || '%' or lower(coalesce(g.alias, '')) like '%' || t.token || '%') group by g.id), description_matches as (select g.id as game_id, count(distinct t.token) * 1.0 as weight from games g join approved a on a.game_id = g.id join tokens t on lower(g.description) like '%' || t.token || '%' group by g.id), mechanism_matches as (select r.game_id as game_id, count(distinct t.token) * 2.0 as weight from game_mechanism_relations r join approved a on a.game_id = r.game_id join game_mechanisms m on m.id = r.mechanism_id and m.is_public = true join tokens t on (lower(m.name_ko) like '%' || t.token || '%' or lower(m.name_en) like '%' || t.token || '%') group by r.game_id), category_matches as (select r.game_id as game_id, count(distinct t.token) * 2.0 as weight from game_category_relations r join approved a on a.game_id = r.game_id join game_categories c on c.id = r.category_id join tokens t on (lower(c.name_ko) like '%' || t.token || '%' or lower(c.name_en) like '%' || t.token || '%') group by r.game_id), theme_matches as (select r.game_id as game_id, count(distinct t.token) * 2.0 as weight from game_theme_relations r join approved a on a.game_id = r.game_id join game_themes th on th.id = r.theme_id join tokens t on (lower(th.name_ko) like '%' || t.token || '%' or lower(th.name_en) like '%' || t.token || '%') group by r.game_id), combined as (select game_id, weight from name_matches union all select game_id, weight from description_matches union all select game_id, weight from mechanism_matches union all select game_id, weight from category_matches union all select game_id, weight from theme_matches) select game_id, sum(weight) as score from combined group by game_id order by score desc, game_id asc limit ${limit};`;
}

function rankDense(documentVectors, queryVector) {
    return [...documentVectors.entries()].map(([gameId, vector]) => ({
        gameId,
        score: dot(vector, queryVector),
    })).sort((left, right) => right.score - left.score || left.gameId - right.gameId);
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

function percentile(sorted, value) {
    const index = Math.min(sorted.length - 1, Math.max(0, Math.ceil(value / 100 * sorted.length) - 1));
    return sorted[index];
}

function roundMillis(value) {
    return Math.round(value * 1000) / 1000;
}

function dot(left, right) {
    let result = 0;
    for (let index = 0; index < left.length; index++) result += left[index] * right[index];
    return result;
}

function readJson(filePath) {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function writeJson(filePath, value) {
    fs.writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function parseArgs(argv) {
    if (argv[0] === "--validate" && argv[1]) return { mode: "validate", input: path.resolve(argv[1]) };
    const values = new Map();
    for (let index = 0; index < argv.length; index++) {
        const argument = argv[index];
        if (argument === "--search-text" || argument === "--postgres-container" || argument === "--out") {
            if (!argv[index + 1]) throw new Error(`${argument} 값이 필요합니다.`);
            values.set(argument, argv[++index]);
        } else {
            throw new Error(`알 수 없는 인자: ${argument}`);
        }
    }
    return {
        mode: "run",
        searchText: path.resolve(values.get("--search-text") || DEFAULT_SEARCH_TEXT),
        postgresContainer: values.get("--postgres-container") || process.env.ALBAM_MATE_POSTGRES_CONTAINER,
        output: path.resolve(values.get("--out") || DEFAULT_OUTPUT),
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
