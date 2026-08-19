#!/usr/bin/env node

import { createHash } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

import { calculateGradedMetrics } from "./dense-bge-m3-execution.mjs";

export const COMPARISON_SCHEMA_VERSION = 1;
export const COMPARISON_KIND = "search-04-search-candidate-comparison";
export const COMPARISON_INPUT_KIND = "search-04-search-candidate-comparison-input";
export const DEFAULT_METRIC_K = 10;
export const DEFAULT_RRF_K = 60;
export const COMPARISON_BLIND_SEED = "search-04-candidate-comparison-v1";

export function sha256(value) {
    return createHash("sha256").update(value).digest("hex");
}

export function loadComparisonManifest(manifestPath) {
    const resolvedManifestPath = path.resolve(manifestPath);
    const baseDir = path.dirname(resolvedManifestPath);
    const manifest = readJson(resolvedManifestPath, "comparison manifest");
    if (manifest.schemaVersion !== COMPARISON_SCHEMA_VERSION) fail("comparison manifest schemaVersion이 올바르지 않습니다.");
    if (manifest.kind !== COMPARISON_INPUT_KIND) fail(`comparison manifest kind은 ${COMPARISON_INPUT_KIND}이어야 합니다.`);
    if (manifest.featureId !== "SEARCH-04") fail("comparison manifest featureId가 올바르지 않습니다.");
    if (!isNonEmptyString(manifest.approvalReference)) fail("comparison manifest approvalReference가 없습니다.");
    if (!Array.isArray(manifest.candidates) || manifest.candidates.length === 0) fail("comparison manifest candidates가 없습니다.");

    const names = new Set();
    const candidates = manifest.candidates.map((candidate) => {
        if (!isNonEmptyString(candidate?.name) || names.has(candidate.name)) fail("comparison candidate 이름이 없거나 중복되었습니다.");
        names.add(candidate.name);
        const queryFixture = loadArtifact(baseDir, candidate.queryFixture, `${candidate.name}.queryFixture`);
        const results = loadArtifact(baseDir, candidate.results, `${candidate.name}.results`);
        return {
            ...candidate,
            queries: queryFixture.value,
            results: results.value,
            provenance: {
                queryFixture: queryFixture.descriptor,
                results: results.descriptor,
            },
        };
    });
    const searchText = manifest.searchText
        ? loadArtifact(baseDir, manifest.searchText, "searchText")
        : null;
    const inputContract = manifest.inputContract
        ? loadArtifact(baseDir, manifest.inputContract, "inputContract")
        : null;
    const judgements = manifest.judgements
        ? loadArtifact(baseDir, manifest.judgements, "judgements")
        : null;
    return {
        manifest,
        candidates,
        searchText: searchText?.value,
        inputContract: inputContract?.value,
        inputContractDescriptor: inputContract?.descriptor,
        judgements: judgements?.value,
        baseDir,
    };
}

export function compareFromManifest({
    manifestPath,
    judgementsPath = undefined,
    includeHybrid = false,
    rrfK = DEFAULT_RRF_K,
}) {
    const loaded = loadComparisonManifest(manifestPath);
    const judgements = judgementsPath
        ? readJson(path.resolve(loaded.baseDir, judgementsPath), "human qrels")
        : loaded.judgements;
    const queries = loaded.candidates[0].queries;
    const report = buildComparisonReport({
        queries,
        candidates: loaded.candidates,
        judgements,
        includeHybrid,
        rrfK,
    });
    return {
        ...report,
        provenance: {
            approvalReference: loaded.manifest.approvalReference,
            candidates: loaded.candidates.map((candidate) => ({
                name: candidate.name,
                sourcePullRequest: candidate.sourcePullRequest ?? null,
                queryFixture: candidate.provenance.queryFixture,
                results: candidate.provenance.results,
            })),
            inputContract: loaded.inputContractDescriptor ?? null,
        },
    };
}

export function packetFromManifest({ manifestPath, topK = 20 }) {
    const loaded = loadComparisonManifest(manifestPath);
    if (!loaded.searchText) fail("judgement packet 생성에는 searchText descriptor가 필요합니다.");
    const packet = buildComparisonJudgementPacket({
        queries: loaded.candidates[0].queries,
        candidates: loaded.candidates,
        searchText: loaded.searchText,
        topK,
    });
    return {
        ...packet,
        provenance: {
            approvalReference: loaded.manifest.approvalReference,
            searchText: { sha256: loaded.manifest.searchText.sha256 },
            inputContract: loaded.inputContractDescriptor
                ? { sha256: loaded.inputContractDescriptor.sha256 }
                : null,
            candidateCount: loaded.candidates.length,
        },
    };
}

export function normalizeCandidateResults(results, candidateName) {
    requireObject(results, `${candidateName} results`);
    const entries = Array.isArray(results.queries)
        ? results.queries.map((query) => [query?.id, query])
        : Object.entries(results);
    if (entries.length === 0) fail(`${candidateName} results가 비어 있습니다.`);

    const normalized = {};
    for (const [queryId, result] of entries) {
        if (!isNonEmptyString(queryId) || Object.hasOwn(normalized, queryId)) {
            fail(`${candidateName} query ID가 없거나 중복되었습니다: ${queryId ?? "<empty>"}`);
        }
        requireObject(result, `${candidateName}.${queryId}`);
        const rankedGameIds = result.rankedGameIds ?? result.ranked;
        const ranked = Array.isArray(rankedGameIds)
            ? rankedGameIds.map((row) => typeof row === "object" && row !== null ? row.gameId : row)
            : rankedGameIds;
        const normalizedRanked = normalizeIds(ranked, `${candidateName}.${queryId}.rankedGameIds`);
        const violations = normalizeIds(
            result.hardFilterViolationGameIds ?? [],
            `${candidateName}.${queryId}.hardFilterViolationGameIds`,
        );
        if (violations.some((gameId) => !normalizedRanked.includes(gameId))) {
            fail(`${candidateName}.${queryId} hard-filter 위반 ID가 ranked 결과에 없습니다.`);
        }
        normalized[queryId] = {
            rankedGameIds: normalizedRanked,
            hardFilterViolationGameIds: violations,
        };
    }
    return normalized;
}

export function validateCandidateFixtures({ candidates }) {
    if (!Array.isArray(candidates) || candidates.length < 2) {
        fail("비교 후보는 2개 이상이어야 합니다.");
    }

    const names = new Set();
    const normalizedCandidates = candidates.map((candidate) => {
        requireObject(candidate, "candidate");
        if (!isNonEmptyString(candidate.name) || names.has(candidate.name)) {
            fail(`candidate 이름이 없거나 중복되었습니다: ${candidate.name ?? "<empty>"}`);
        }
        names.add(candidate.name);
        const queries = normalizeQueryFixture(candidate.queries, candidate.name);
        const results = normalizeCandidateResults(candidate.results, candidate.name);
        const queryIds = queries.map((query) => query.id);
        const resultIds = Object.keys(results);
        if (!sameValues(queryIds, resultIds)) {
            fail(`${candidate.name} query ID와 results query ID가 다릅니다.`);
        }
        return { ...candidate, queries, results };
    });

    const referenceQueries = normalizedCandidates[0].queries;
    const referenceSignature = queryFixtureSignature(referenceQueries);
    for (const candidate of normalizedCandidates.slice(1)) {
        if (queryFixtureSignature(candidate.queries) !== referenceSignature) {
            fail(`candidate 간 query fixture가 다릅니다: ${candidate.name}; ${queryFixtureDifference(referenceQueries, candidate.queries)}`);
        }
    }

    return { queries: referenceQueries, candidates: normalizedCandidates };
}

export function buildComparisonReport({
    queries,
    candidates,
    judgements,
    k = DEFAULT_METRIC_K,
    includeHybrid = false,
    rrfK = DEFAULT_RRF_K,
}) {
    const validated = validateCandidateFixtures({ candidates });
    const comparisonCandidates = [...validated.candidates];
    const hybrid = includeHybrid
        ? buildRrfCandidateFromValidated(validated, { rrfK })
        : null;
    if (hybrid) comparisonCandidates.push(hybrid);
    const queryIds = validated.queries.map((query) => query.id);
    const requiredGameIdsByQuery = Object.fromEntries(validated.queries.map((query) => [
        query.id,
        [...new Set(comparisonCandidates.flatMap((candidate) => candidate.results[query.id].rankedGameIds))],
    ]));
    const judgementState = validateHumanJudgements(judgements, { queryIds, requiredGameIdsByQuery });
    if (judgementState.status !== "approved") {
        return {
            schemaVersion: COMPARISON_SCHEMA_VERSION,
            kind: COMPARISON_KIND,
            status: "pending-human-judgement",
            queryCount: validated.queries.length,
            blockingReasons: judgementState.blockingReasons,
            hybrid: hybrid ? { rule: "rrf", rrfK, status: "pending-human-judgement" } : null,
            selection: {
                status: "pending-human-judgement",
                selectedMethod: null,
            },
        };
    }

    const metrics = Object.fromEntries(comparisonCandidates.map((candidate) => {
        const perQuery = validated.queries.map((query) => {
            const result = candidate.results[query.id];
            return {
                queryId: query.id,
                cohorts: query.cohorts,
                analysisClass: query.analysisClass,
                anchor: query.anchor,
                ...calculateCandidateMetrics({
                    grades: judgementState.gradesByQuery[query.id],
                    result,
                    k,
                }),
            };
        });
        return [candidate.name, {
            perQuery,
            overall: aggregateMetrics(perQuery, k),
            cohorts: Object.fromEntries([...new Set(validated.queries.flatMap((query) => query.cohorts))].map((cohort) => [
                cohort,
                aggregateMetrics(perQuery.filter((row) => row.cohorts.includes(cohort)), k),
            ])),
            analysisClasses: Object.fromEntries([...new Set(
                perQuery.map((row) => row.analysisClass).filter(isNonEmptyString),
            )].map((analysisClass) => [
                analysisClass,
                aggregateMetrics(perQuery.filter((row) => row.analysisClass === analysisClass), k),
            ])),
        }];
    }));

    return {
        schemaVersion: COMPARISON_SCHEMA_VERSION,
        kind: COMPARISON_KIND,
        status: "metrics-ready",
        queryCount: validated.queries.length,
        hybrid: hybrid ? { rule: "rrf", rrfK, status: "included" } : null,
        metrics,
        selection: {
            status: "pending-human-decision",
            selectedMethod: null,
            reason: "metrics만으로 최종 방식을 자동 선택하지 않습니다.",
        },
    };
}

export function buildRrfRanking({ rankedLists, rrfK = DEFAULT_RRF_K, topK = 20 }) {
    if (!Array.isArray(rankedLists) || rankedLists.length < 2) fail("RRF에는 2개 이상의 ranked list가 필요합니다.");
    if (!Number.isInteger(rrfK) || rrfK < 1) fail("RRF k는 1 이상의 정수여야 합니다.");
    if (!Number.isInteger(topK) || topK < 1) fail("RRF topK는 1 이상의 정수여야 합니다.");

    const scores = new Map();
    rankedLists.forEach((rankedGameIds, listIndex) => {
        const normalized = normalizeIds(rankedGameIds, `RRF ranked list ${listIndex + 1}`);
        normalized.forEach((gameId, index) => {
            const numericId = Number(gameId);
            scores.set(numericId, (scores.get(numericId) ?? 0) + 1 / (rrfK + index + 1));
        });
    });
    return [...scores.entries()]
        .sort((left, right) => right[1] - left[1] || left[0] - right[0])
        .slice(0, topK)
        .map(([gameId]) => gameId);
}

function buildRrfCandidateFromValidated(validated, { rrfK }) {
    return {
        name: "hybrid-rrf",
        queries: validated.queries,
        results: Object.fromEntries(validated.queries.map((query) => {
            const candidateResults = validated.candidates.map((candidate) => candidate.results[query.id]);
            const rankedGameIds = buildRrfRanking({
                rankedLists: candidateResults.map((result) => result.rankedGameIds),
                rrfK,
                topK: 20,
            });
            const violations = new Set(candidateResults.flatMap((result) => result.hardFilterViolationGameIds));
            return [query.id, {
                rankedGameIds,
                hardFilterViolationGameIds: rankedGameIds.filter((gameId) => violations.has(gameId)),
            }];
        })),
    };
}

export function buildComparisonJudgementPacket({
    queries,
    candidates,
    searchText,
    seed = COMPARISON_BLIND_SEED,
    topK = 20,
}) {
    if (!Number.isInteger(topK) || topK < 1) fail("judgement packet topK는 1 이상의 정수여야 합니다.");
    const validated = validateCandidateFixtures({ candidates: candidates ?? [] });
    const searchTextRows = Array.isArray(searchText) ? searchText : searchText?.games;
    if (!Array.isArray(searchTextRows)) fail("searchText games가 없습니다.");
    const evidenceById = new Map(searchTextRows.map((row) => [Number(row?.gameId), row?.searchText]));

    return {
        schemaVersion: COMPARISON_SCHEMA_VERSION,
        kind: "search-04-search-candidate-judgement-packet",
        status: "pending-independent-human-judgement",
        seed,
        hides: ["candidate", "score", "sourceRank"],
        gradeScale: { relevant: 2, borderline: 1, irrelevant: 0 },
        judgementContract: {
            requiredIndependentJudges: 2,
            thirdJudgeRequiredOnDisagreement: true,
            gradeMeaning: "2=relevant, 1=borderline, 0=irrelevant",
        },
        queries: validated.queries.map((query) => {
            const gameIds = [...new Set(validated.candidates.flatMap((candidate) => (
                candidate.results[query.id].rankedGameIds.slice(0, topK)
            )))];
            const candidatesForJudgement = gameIds
                .sort((left, right) => {
                    const leftKey = stableKey(seed, query.id, left);
                    const rightKey = stableKey(seed, query.id, right);
                    return leftKey < rightKey ? -1 : leftKey > rightKey ? 1 : left - right;
                })
                .map((gameId, index) => {
                    const evidenceText = evidenceById.get(gameId);
                    if (typeof evidenceText !== "string" || evidenceText.trim() === "") {
                        fail(`${query.id} judgement 후보 ${gameId}의 evidence가 없습니다.`);
                    }
                    return {
                        blindRank: index + 1,
                        gameId,
                        evidenceText,
                        grade: null,
                        rationale: null,
                    };
                });
            return {
                id: query.id,
                query: query.query,
                cohorts: query.cohorts,
                analysisClass: query.analysisClass,
                anchor: query.anchor,
                hardFilters: query.hardFilters,
                judgementRubric: query.judgementRubric ?? null,
                candidates: candidatesForJudgement,
            };
        }),
    };
}

export function validateHumanJudgements(judgements, { queryIds, requiredGameIdsByQuery }) {
    if (!judgements || judgements.status !== "approved") {
        return {
            status: "pending-human-judgement",
            blockingReasons: ["독립 human qrels가 approved 상태가 아닙니다."],
        };
    }
    if (!Array.isArray(judgements.queries)) fail("human qrels queries가 없습니다.");
    const byId = new Map();
    for (const query of judgements.queries) {
        if (!isNonEmptyString(query?.id) || byId.has(query.id)) fail(`human qrels query ID가 없거나 중복되었습니다: ${query?.id ?? "<empty>"}`);
        byId.set(query.id, query);
    }

    const gradesByQuery = {};
    for (const queryId of queryIds) {
        const judgement = byId.get(queryId);
        if (!judgement) fail(`human qrels에 ${queryId}가 없습니다.`);
        if (!Array.isArray(judgement.judges) || judgement.judges.length < 2) {
            fail(`${queryId}에 독립 판정자 2명이 없습니다.`);
        }
        const judgeIds = new Set();
        const judgeGrades = judgement.judges.map((judge) => {
            if (!isNonEmptyString(judge?.judgeId) || judgeIds.has(judge.judgeId)) {
                fail(`${queryId}의 판정자가 독립적이지 않습니다.`);
            }
            judgeIds.add(judge.judgeId);
            if (judge.status !== "approved") fail(`${queryId}의 판정 상태가 approved가 아닙니다.`);
            return normalizeGrades(judge.grades, `${queryId}.${judge.judgeId}.grades`);
        });
        const consensus = normalizeGrades(judgement.consensus?.grades, `${queryId}.consensus.grades`);
        if (judgement.consensus?.status !== "approved") fail(`${queryId} consensus가 approved가 아닙니다.`);
        const requiredIds = requiredGameIdsByQuery[queryId] ?? [];
        for (const gameId of requiredIds) {
            if (!Object.hasOwn(consensus, String(gameId))) {
                fail(`${queryId} qrels에 candidate 결과 game ID가 없습니다: ${gameId}`);
            }
        }
        for (const gameId of Object.keys(consensus)) {
            const votes = judgeGrades.filter((grades) => grades[gameId] === consensus[gameId]).length;
            if (votes <= judgeGrades.length / 2) {
                fail(`${queryId}의 consensus가 독립 판정 다수결과 다릅니다: ${gameId}`);
            }
        }
        gradesByQuery[queryId] = consensus;
    }
    return { status: "approved", gradesByQuery };
}

function calculateCandidateMetrics({ grades, result, k }) {
    const graded = calculateGradedMetrics({
        grades,
        rankedGameIds: result.rankedGameIds.map(Number),
        k,
    });
    const topK = result.rankedGameIds.slice(0, k);
    const violations = new Set(result.hardFilterViolationGameIds);
    const hardFilterViolationGameIds = topK.filter((gameId) => violations.has(gameId));
    const hardFilterViolationRate = topK.length === 0 ? 0 : hardFilterViolationGameIds.length / topK.length;
    return {
        ...graded,
        [`recallAt${k}`]: graded.recallAtK,
        [`mrrAt${k}`]: graded.mrrAtK,
        [`ndcgAt${k}`]: graded.ndcgAtK,
        hardFilterViolationRate,
        hardFilterViolationGameIds,
        qualityEligible: hardFilterViolationRate === 0,
    };
}

function aggregateMetrics(rows, k) {
    if (rows.length === 0) return null;
    const metricNames = [`recallAt${k}`, `mrrAt${k}`, `ndcgAt${k}`, "hardFilterViolationRate"];
    const averages = Object.fromEntries(metricNames.map((metric) => [
        metric,
        rows.reduce((sum, row) => sum + row[metric], 0) / rows.length,
    ]));
    return {
        queryCount: rows.length,
        ...averages,
        qualityEligible: averages.hardFilterViolationRate === 0,
    };
}

function normalizeQueryFixture(queries, candidateName) {
    if (!Array.isArray(queries) || queries.length === 0) fail(`${candidateName} query fixture가 비어 있습니다.`);
    const seen = new Set();
    return queries.map((query) => {
        requireObject(query, `${candidateName} query`);
        if (!isNonEmptyString(query.id) || seen.has(query.id)) fail(`${candidateName} query ID가 없거나 중복되었습니다.`);
        seen.add(query.id);
        if (!isNonEmptyString(query.query)) fail(`${candidateName} ${query.id} query 문구가 없습니다.`);
        if (!Array.isArray(query.cohorts) || query.cohorts.length === 0) fail(`${candidateName} ${query.id} cohort가 없습니다.`);
        if (query.analysisClass !== undefined && !isNonEmptyString(query.analysisClass)) {
            fail(`${candidateName} ${query.id} analysisClass가 올바르지 않습니다.`);
        }
        if (query.anchor !== undefined && typeof query.anchor !== "boolean") {
            fail(`${candidateName} ${query.id} anchor가 올바르지 않습니다.`);
        }
        requireObject(query.hardFilters ?? {}, `${candidateName} ${query.id} hardFilters`);
        return {
            id: query.id,
            query: query.query,
            cohorts: [...query.cohorts],
            analysisClass: query.analysisClass ?? null,
            anchor: query.anchor === true,
            hardFilters: { ...(query.hardFilters ?? {}) },
            judgementRubric: query.judgementRubric ?? null,
        };
    });
}

function queryFixtureSignature(queries) {
    return JSON.stringify(queries.map((query) => ({
        id: query.id,
        query: query.query,
        cohorts: query.cohorts,
        analysisClass: query.analysisClass,
        anchor: query.anchor,
        hardFilters: query.hardFilters,
        judgementRubric: query.judgementRubric,
    })));
}

function queryFixtureDifference(reference, candidate) {
    if (reference.length !== candidate.length) {
        return `query 수 reference=${reference.length}, candidate=${candidate.length}`;
    }
    for (let index = 0; index < reference.length; index += 1) {
        const left = reference[index];
        const right = candidate[index];
        if (left.id !== right.id) return `query ID reference=${left.id}, candidate=${right.id}`;
        if (left.query !== right.query) {
            return `${right.id} query 문구 reference="${left.query}", candidate="${right.query}"`;
        }
        if (JSON.stringify(left.cohorts) !== JSON.stringify(right.cohorts)) {
            return `${right.id} cohort가 다릅니다.`;
        }
        if (left.analysisClass !== right.analysisClass) {
            return `${right.id} analysisClass가 다릅니다.`;
        }
        if (left.anchor !== right.anchor) {
            return `${right.id} anchor가 다릅니다.`;
        }
        if (JSON.stringify(left.hardFilters) !== JSON.stringify(right.hardFilters)) {
            return `${right.id} hard filter가 다릅니다.`;
        }
        if (JSON.stringify(left.judgementRubric) !== JSON.stringify(right.judgementRubric)) {
            return `${right.id} judgement rubric이 다릅니다.`;
        }
    }
    return "query fixture가 다릅니다.";
}

function normalizeGrades(grades, name) {
    requireObject(grades, name);
    const normalized = {};
    for (const [gameId, grade] of Object.entries(grades)) {
        if (!/^\d+$/u.test(gameId) || String(Number(gameId)) !== gameId || !Number.isSafeInteger(Number(gameId))) {
            fail(`${name} game ID가 canonical decimal이 아닙니다: ${gameId}`);
        }
        if (![0, 1, 2].includes(grade)) fail(`${name} grade는 0·1·2 중 하나여야 합니다.`);
        normalized[gameId] = grade;
    }
    return normalized;
}

function normalizeIds(ids, name) {
    if (!Array.isArray(ids)) fail(`${name}는 배열이어야 합니다.`);
    const normalized = ids.map((id) => {
        if (!Number.isSafeInteger(Number(id)) || Number(id) < 1 || String(Number(id)) !== String(id)) {
            fail(`${name}에 올바르지 않은 game ID가 있습니다: ${id}`);
        }
        return Number(id);
    });
    if (new Set(normalized).size !== normalized.length) fail(`${name}에 중복 game ID가 있습니다.`);
    return normalized;
}

function sameValues(left, right) {
    return left.length === right.length && left.every((value, index) => value === right[index]);
}

function stableKey(seed, queryId, gameId) {
    return sha256(`${seed}\u0000${queryId}\u0000${gameId}`);
}

function loadArtifact(baseDir, descriptor, field) {
    requireObject(descriptor, field);
    if (!isNonEmptyString(descriptor.path) || path.isAbsolute(descriptor.path)
        || descriptor.path.split(/[\\/]/u).includes("..")) {
        fail(`${field}.path는 안전한 상대 경로여야 합니다.`);
    }
    if (!/^[a-f0-9]{64}$/u.test(descriptor.sha256 ?? "")) fail(`${field}.sha256가 올바르지 않습니다.`);
    const resolvedPath = path.resolve(baseDir, descriptor.path);
    const bytes = fs.readFileSync(resolvedPath);
    if (sha256(bytes) !== descriptor.sha256) fail(`${field}.sha256 checksum이 실제 파일과 다릅니다.`);
    return {
        descriptor: { path: descriptor.path, sha256: descriptor.sha256 },
        value: parseJson(bytes, field),
    };
}

function readJson(filePath, field) {
    return parseJson(fs.readFileSync(filePath), field);
}

function parseJson(bytes, field) {
    try {
        return JSON.parse(bytes.toString("utf8"));
    } catch (error) {
        fail(`${field} JSON을 읽을 수 없습니다: ${error.message}`);
    }
}

function requireObject(value, name) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) fail(`${name}은 object여야 합니다.`);
}

function isNonEmptyString(value) {
    return typeof value === "string" && value.trim().length > 0;
}

function fail(message) {
    throw new Error(message);
}

function parseArgs(args) {
    const options = { mode: null, hybridRrf: false };
    const valueOptions = new Set(["manifest", "out", "judgements", "topK"]);
    for (let index = 0; index < args.length; index += 1) {
        const argument = args[index];
        if (argument === "--check" || argument === "--packet" || argument === "--metrics") {
            if (options.mode !== null) fail("--check·--packet·--metrics 중 하나만 선택해야 합니다.");
            options.mode = argument.slice(2);
            continue;
        }
        if (argument === "--hybrid-rrf") {
            if (options.hybridRrf) fail("--hybrid-rrf가 중복되었습니다.");
            options.hybridRrf = true;
            continue;
        }
        if (!argument.startsWith("--")) fail(`알 수 없는 인자입니다: ${argument}`);
        const option = argument.slice(2).replace(/-([a-z])/gu, (_, letter) => letter.toUpperCase());
        if (!valueOptions.has(option) || options[option] !== undefined) fail(`알 수 없거나 중복된 옵션입니다: ${argument}`);
        const value = args[index + 1];
        if (!value || value.startsWith("--")) fail(`${argument} 값이 필요합니다.`);
        options[option] = value;
        index += 1;
    }
    if (!options.mode || !options.manifest) fail("실행 모드와 --manifest가 필요합니다.");
    if (options.mode === "packet" && !options.out) fail("--packet에는 --out이 필요합니다.");
    if (options.topK !== undefined && (!/^\d+$/u.test(options.topK) || Number(options.topK) < 1)) {
        fail("--top-k는 1 이상의 정수여야 합니다.");
    }
    return options;
}

function writeNewJson(outputPath, value, inputPaths = []) {
    const resolvedOutput = path.resolve(outputPath);
    if (inputPaths.some((inputPath) => path.resolve(inputPath) === resolvedOutput)) {
        fail("--out은 입력 파일을 덮어쓸 수 없습니다.");
    }
    if (fs.existsSync(resolvedOutput)) fail(`--out 파일이 이미 존재합니다: ${resolvedOutput}`);
    fs.writeFileSync(resolvedOutput, `${JSON.stringify(value, null, 2)}\n`, { encoding: "utf8", flag: "wx", mode: 0o600 });
    return resolvedOutput;
}

function main() {
    try {
        const options = parseArgs(process.argv.slice(2));
        const manifestPath = path.resolve(options.manifest);
        const loaded = loadComparisonManifest(manifestPath);
        const inputPaths = [
            manifestPath,
            ...loaded.candidates.flatMap((candidate) => [
                path.resolve(loaded.baseDir, candidate.provenance.queryFixture.path),
            path.resolve(loaded.baseDir, candidate.provenance.results.path),
            ]),
            ...(loaded.inputContractDescriptor
                ? [path.resolve(loaded.baseDir, loaded.inputContractDescriptor.path)]
                : []),
            ...(loaded.manifest.searchText
                ? [path.resolve(loaded.baseDir, loaded.manifest.searchText.path)]
                : []),
            ...(loaded.manifest.judgements
                ? [path.resolve(loaded.baseDir, loaded.manifest.judgements.path)]
                : []),
        ];
        if (options.mode === "check") {
            const validated = validateCandidateFixtures({ candidates: loaded.candidates });
            console.log(JSON.stringify({
                ok: true,
                status: "compatible",
                queryCount: validated.queries.length,
                candidates: validated.candidates.map((candidate) => candidate.name),
            }, null, 2));
            return;
        }
        if (options.mode === "packet") {
            const packet = packetFromManifest({ manifestPath, topK: options.topK ? Number(options.topK) : 20 });
            const output = writeNewJson(options.out, packet, inputPaths);
            console.log(JSON.stringify({ ok: true, status: packet.status, output }, null, 2));
            return;
        }
        const report = compareFromManifest({
            manifestPath,
            judgementsPath: options.judgements,
            includeHybrid: options.hybridRrf,
        });
        if (options.out) {
            const output = writeNewJson(options.out, report, inputPaths);
            console.log(JSON.stringify({ ok: true, status: report.status, output }, null, 2));
            return;
        }
        console.log(JSON.stringify({ ok: true, ...report }, null, 2));
    } catch (error) {
        console.error(JSON.stringify({ ok: false, error: error.message }, null, 2));
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? path.resolve(process.argv[1]) : null;
if (entryPoint === fileURLToPath(import.meta.url)) main();
