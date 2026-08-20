#!/usr/bin/env node

import { createHash, randomUUID } from "node:crypto";
import fs from "node:fs";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

import { calculateGradedMetrics } from "./dense-bge-m3-execution.mjs";

export const COMPARISON_SCHEMA_VERSION = 1;
export const COMPARISON_KIND = "search-04-search-candidate-comparison";
export const COMPARISON_INPUT_KIND = "search-04-search-candidate-comparison-input";
export const HUMAN_QRELS_KIND = "search-04-search-candidate-qrels";
export const DEFAULT_METRIC_K = 10;
export const DEFAULT_EVALUATION_TOP_K = 20;
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
    const evaluationTopK = manifest.evaluationTopK ?? DEFAULT_EVALUATION_TOP_K;
    validateTopK(evaluationTopK, "comparison manifest evaluationTopK");
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
    const judgementPacket = manifest.judgementPacket
        ? loadArtifact(baseDir, manifest.judgementPacket, "judgementPacket")
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
        judgementPacket: judgementPacket?.value,
        judgementPacketDescriptor: judgementPacket?.descriptor,
        judgements: judgements?.value,
        evaluationTopK,
        baseDir,
    };
}

export function compareFromManifest({
    manifestPath,
    judgementsPath = undefined,
    includeHybrid = false,
    rrfK = DEFAULT_RRF_K,
    evaluationTopK = undefined,
    allowProvisionalAiAdjudication = false,
}) {
    const loaded = loadComparisonManifest(manifestPath);
    const judgements = judgementsPath
        ? readJson(path.resolve(loaded.baseDir, judgementsPath), "human qrels")
        : loaded.judgements;
    if (judgements?.status === "approved" && !loaded.judgementPacketDescriptor) {
        fail("approved human qrels에는 canonical judgementPacket descriptor가 필요합니다.");
    }
    const queries = loaded.candidates[0].queries;
    const report = buildComparisonReport({
        queries,
        candidates: loaded.candidates,
        judgements,
        judgementPacketSha256: loaded.judgementPacketDescriptor?.sha256,
        includeHybrid,
        rrfK,
        evaluationTopK: evaluationTopK ?? loaded.evaluationTopK,
        allowProvisionalAiAdjudication,
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
            judgementPacket: loaded.judgementPacketDescriptor ?? null,
            judgements: loaded.manifest.judgements ?? null,
        },
    };
}

export function packetFromManifest({ manifestPath, topK = undefined }) {
    const loaded = loadComparisonManifest(manifestPath);
    if (!loaded.searchText) fail("judgement packet 생성에는 searchText descriptor가 필요합니다.");
    const packet = buildComparisonJudgementPacket({
        queries: loaded.candidates[0].queries,
        candidates: loaded.candidates,
        searchText: loaded.searchText,
        topK: topK ?? loaded.evaluationTopK,
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

function validateTopK(topK, name) {
    if (!Number.isInteger(topK) || topK < 1) fail(`${name}는 1 이상의 정수여야 합니다.`);
    return topK;
}

function createEvaluationMetadata(candidates, topK) {
    const candidatePoolByQuery = Object.fromEntries(candidates[0].queries.map((query) => [
        query.id,
        [...new Set(candidates.flatMap((candidate) => (
            candidate.results[query.id].rankedGameIds.slice(0, topK)
        )))].sort((left, right) => left - right),
    ]));
    const candidatePoolSha256 = sha256(JSON.stringify(candidatePoolByQuery));
    return {
        public: { topK, candidatePoolSha256 },
        candidatePoolByQuery,
    };
}

export function buildEvaluationMetadata({ candidates, topK = DEFAULT_EVALUATION_TOP_K }) {
    validateTopK(topK, "evaluationTopK");
    const validated = validateCandidateFixtures({ candidates });
    return createEvaluationMetadata(validated.candidates, topK).public;
}

export function buildComparisonReport({
    queries,
    candidates,
    judgements,
    k = DEFAULT_METRIC_K,
    includeHybrid = false,
    rrfK = DEFAULT_RRF_K,
    evaluationTopK = DEFAULT_EVALUATION_TOP_K,
    judgementPacketSha256 = undefined,
    allowProvisionalAiAdjudication = false,
}) {
    const validated = validateCandidateFixtures({ candidates });
    validateTopK(evaluationTopK, "evaluationTopK");
    const comparisonCandidates = [...validated.candidates];
    const evaluation = createEvaluationMetadata(validated.candidates, evaluationTopK);
    const hybrid = includeHybrid
        ? buildRrfCandidateFromValidated(validated, { rrfK, topK: evaluationTopK })
        : null;
    if (hybrid) comparisonCandidates.push(hybrid);
    const queryIds = validated.queries.map((query) => query.id);
    const requiredGameIdsByQuery = evaluation.candidatePoolByQuery;
    const judgementState = validateHumanJudgements(judgements, {
        queryIds,
        requiredGameIdsByQuery,
        evaluation: evaluation.public,
        judgementPacketSha256,
    });
    const isProvisionalAiAdjudication = judgementState.status === "provisional-ai-adjudication";
    if (judgementState.status !== "approved"
        && !(allowProvisionalAiAdjudication && isProvisionalAiAdjudication)) {
        return {
            schemaVersion: COMPARISON_SCHEMA_VERSION,
            kind: COMPARISON_KIND,
            status: "pending-human-judgement",
            queryCount: validated.queries.length,
            evaluation: evaluation.public,
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
        status: isProvisionalAiAdjudication ? "provisional-metrics-ready" : "metrics-ready",
        queryCount: validated.queries.length,
        evaluation: evaluation.public,
        hybrid: hybrid
            ? { rule: "rrf", rrfK, status: isProvisionalAiAdjudication ? "provisional-included" : "included" }
            : null,
        metrics,
        selection: {
            status: "pending-human-decision",
            selectedMethod: null,
            reason: "metrics만으로 최종 방식을 자동 선택하지 않습니다.",
        },
    };
}

export function buildRrfRanking({ rankedLists, rrfK = DEFAULT_RRF_K, topK = DEFAULT_EVALUATION_TOP_K }) {
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

function buildRrfCandidateFromValidated(validated, { rrfK, topK = DEFAULT_EVALUATION_TOP_K }) {
    return {
        name: "hybrid-rrf",
        queries: validated.queries,
        results: Object.fromEntries(validated.queries.map((query) => {
            const candidateResults = validated.candidates.map((candidate) => candidate.results[query.id]);
            const rankedGameIds = buildRrfRanking({
                rankedLists: candidateResults.map((result) => result.rankedGameIds.slice(0, topK)),
                rrfK,
                topK,
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
    topK = DEFAULT_EVALUATION_TOP_K,
}) {
    validateTopK(topK, "judgement packet topK");
    const validated = validateCandidateFixtures({ candidates: candidates ?? [] });
    const evaluation = createEvaluationMetadata(validated.candidates, topK);
    const searchTextRows = Array.isArray(searchText) ? searchText : searchText?.games;
    if (!Array.isArray(searchTextRows)) fail("searchText games가 없습니다.");
    const evidenceById = new Map(searchTextRows.map((row) => [Number(row?.gameId), row?.searchText]));

    return {
        schemaVersion: COMPARISON_SCHEMA_VERSION,
        kind: "search-04-search-candidate-judgement-packet",
        status: "pending-independent-human-judgement",
        seed,
        evaluation: evaluation.public,
        hides: ["candidate", "score", "sourceRank"],
        gradeScale: { relevant: 2, borderline: 1, irrelevant: 0 },
        judgementContract: {
            requiredIndependentJudges: 2,
            thirdJudgeRequiredOnDisagreement: true,
            gradeMeaning: "2=relevant, 1=borderline, 0=irrelevant",
        },
        queries: validated.queries.map((query) => {
            const gameIds = evaluation.candidatePoolByQuery[query.id];
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

export function buildApprovedHumanQrels({
    packet,
    judgePackets,
    thirdJudgePacket = null,
    judgeIds = ["judge-a", "judge-b"],
    packetSha256 = null,
}) {
    const reference = validateJudgementPacket(packet, "canonical packet");
    if (!Array.isArray(judgePackets) || judgePackets.length !== 2) {
        fail("독립 판정 packet은 2개가 필요합니다.");
    }
    if (!Array.isArray(judgeIds) || (judgeIds.length !== 2 && judgeIds.length !== 3)) {
        fail("judge ID는 2개 또는 3개여야 합니다.");
    }
    if (judgeIds.some((judgeId) => !isNonEmptyString(judgeId))
        || new Set(judgeIds).size !== judgeIds.length) {
        fail("judge ID가 없거나 중복되었습니다.");
    }
    if ((judgeIds.length === 3) !== (thirdJudgePacket !== null)) {
        fail("제3 판정 packet과 judge ID를 함께 지정해야 합니다.");
    }
    if (!/^[a-f0-9]{64}$/u.test(packetSha256 ?? "")) {
        fail("packetSha256가 올바르지 않습니다.");
    }

    const normalizedJudgePackets = judgePackets.map((judgePacket, index) => {
        const normalized = validateJudgementPacket(judgePacket, `judge ${index + 1} packet`);
        if (judgementPacketIdentity(reference) !== judgementPacketIdentity(normalized)) {
            fail(`judge ${index + 1} packet이 canonical packet과 다릅니다.`);
        }
        return normalized;
    });
    const normalizedThirdJudgePacket = thirdJudgePacket
        ? validateJudgementPacket(thirdJudgePacket, "third judge packet")
        : null;
    if (normalizedThirdJudgePacket && judgementPacketIdentity(reference) !== judgementPacketIdentity(normalizedThirdJudgePacket)) {
        fail("third judge packet이 canonical packet과 다릅니다.");
    }

    const queries = reference.queries.map((query, queryIndex) => {
        const judgeResults = normalizedJudgePackets.map((judgePacket, judgeIndex) => extractJudgeResult(
            judgePacket.queries[queryIndex],
            query,
            `judge ${judgeIndex + 1} ${query.id}`,
        ));
        const thirdResult = normalizedThirdJudgePacket
            ? extractJudgeResult(normalizedThirdJudgePacket.queries[queryIndex], query, `third judge ${query.id}`, { allowPartial: true })
            : null;
        const candidateIds = query.candidates.map((candidate) => String(candidate.gameId));
        const disagreementIds = candidateIds.filter((gameId) => (
            judgeResults[0].grades[gameId] !== judgeResults[1].grades[gameId]
        ));
        const hasDisagreement = disagreementIds.length > 0;
        if (hasDisagreement && !thirdResult) {
            fail(`${query.id} 판정 불일치에는 제3 판정이 필요합니다.`);
        }
        if (thirdResult) {
            requireExactGradeKeys(thirdResult.grades, disagreementIds, `third judge ${query.id}.grades`);
            requireExactGradeKeys(thirdResult.rationales, disagreementIds, `third judge ${query.id}.rationales`);
        }

        const consensusGrades = {};
        for (const gameId of candidateIds) {
            const first = judgeResults[0].grades[gameId];
            const second = judgeResults[1].grades[gameId];
            const third = thirdResult?.grades[gameId];
            if (first === second) {
                consensusGrades[gameId] = first;
                continue;
            }
            if (third === undefined) {
                fail(`${query.id} ${gameId}의 제3 판정이 없습니다.`);
            }
            if (third !== first && third !== second) {
                fail(`${query.id} ${gameId}의 제3 판정이 다수결을 만들지 못합니다.`);
            }
            consensusGrades[gameId] = third === first ? first : second;
        }

        const usedJudgeIndexes = hasDisagreement ? [0, 1, 2] : [0, 1];
        return {
            id: query.id,
            query: query.query,
            cohorts: query.cohorts,
            analysisClass: query.analysisClass,
            anchor: query.anchor,
            hardFilters: query.hardFilters,
            judges: usedJudgeIndexes.map((judgeIndex) => ({
                judgeId: judgeIds[judgeIndex],
                status: "approved",
                grades: judgeResults[judgeIndex]?.grades ?? thirdResult.grades,
                rationales: judgeResults[judgeIndex]?.rationales ?? thirdResult.rationales,
            })),
            consensus: {
                status: "approved",
                method: hasDisagreement ? "third-judge-majority" : "independent-agreement",
                grades: consensusGrades,
            },
        };
    });

    return {
        schemaVersion: COMPARISON_SCHEMA_VERSION,
        kind: HUMAN_QRELS_KIND,
        status: "approved",
        packetSha256,
        evaluation: reference.evaluation,
        judgementContract: {
            requiredIndependentJudges: 2,
            thirdJudgeRequiredOnDisagreement: true,
            gradeMeaning: "2=relevant, 1=borderline, 0=irrelevant",
        },
        queries,
    };
}

export function buildProvisionalAiAdjudicationQrels({
    packet,
    judgePackets,
    thirdJudgePacket,
    thirdJudgeSource = null,
    judgeIds = ["judge-a", "judge-b", "judge-c-ai-drafted"],
    packetSha256 = null,
}) {
    const reference = validateJudgementPacket(packet, "canonical packet");
    if (!Array.isArray(judgePackets) || judgePackets.length !== 2) {
        fail("독립 판정 packet은 2개가 필요합니다.");
    }
    if (!thirdJudgePacket) fail("AI adjudication에는 제3 판정 packet이 필요합니다.");
    if (!isNonEmptyString(thirdJudgeSource)) fail("AI adjudication에는 제3 판정 source가 필요합니다.");
    if (!Array.isArray(judgeIds) || judgeIds.length !== 3) {
        fail("AI adjudication judge ID는 3개여야 합니다.");
    }
    if (judgeIds.some((judgeId) => !isNonEmptyString(judgeId))
        || new Set(judgeIds).size !== judgeIds.length) {
        fail("judge ID가 없거나 중복되었습니다.");
    }
    if (!/^[a-f0-9]{64}$/u.test(packetSha256 ?? "")) {
        fail("packetSha256가 올바르지 않습니다.");
    }

    const normalizedJudgePackets = judgePackets.map((judgePacket, index) => {
        const normalized = validateJudgementPacket(judgePacket, `judge ${index + 1} packet`);
        if (judgementPacketIdentity(reference) !== judgementPacketIdentity(normalized)) {
            fail(`judge ${index + 1} packet이 canonical packet과 다릅니다.`);
        }
        return normalized;
    });
    const normalizedThirdJudgePacket = validateJudgementPacket(thirdJudgePacket, "third judge packet");
    if (judgementPacketIdentity(reference) !== judgementPacketIdentity(normalizedThirdJudgePacket)) {
        fail("third judge packet이 canonical packet과 다릅니다.");
    }

    let threeWayDisagreementCount = 0;
    const queries = reference.queries.map((query, queryIndex) => {
        const judgeResults = normalizedJudgePackets.map((judgePacket, judgeIndex) => extractJudgeResult(
            judgePacket.queries[queryIndex],
            query,
            `judge ${judgeIndex + 1} ${query.id}`,
        ));
        const thirdResult = extractJudgeResult(
            normalizedThirdJudgePacket.queries[queryIndex],
            query,
            `third judge ${query.id}`,
            { allowPartial: true },
        );
        const candidateIds = query.candidates.map((candidate) => String(candidate.gameId));
        const disagreementIds = candidateIds.filter((gameId) => (
            judgeResults[0].grades[gameId] !== judgeResults[1].grades[gameId]
        ));
        requireExactGradeKeys(thirdResult.grades, disagreementIds, `third judge ${query.id}.grades`);
        requireExactGradeKeys(thirdResult.rationales, disagreementIds, `third judge ${query.id}.rationales`);

        const consensusGrades = {};
        for (const gameId of candidateIds) {
            const first = judgeResults[0].grades[gameId];
            const second = judgeResults[1].grades[gameId];
            if (first === second) {
                consensusGrades[gameId] = first;
                continue;
            }
            const third = thirdResult.grades[gameId];
            if (third !== first && third !== second) threeWayDisagreementCount += 1;
            consensusGrades[gameId] = third;
        }

        const hasDisagreement = disagreementIds.length > 0;
        const usedJudgeIndexes = hasDisagreement ? [0, 1, 2] : [0, 1];
        return {
            id: query.id,
            query: query.query,
            cohorts: query.cohorts,
            analysisClass: query.analysisClass,
            anchor: query.anchor,
            hardFilters: query.hardFilters,
            judges: usedJudgeIndexes.map((judgeIndex) => ({
                judgeId: judgeIds[judgeIndex],
                status: "provisional",
                grades: judgeResults[judgeIndex]?.grades ?? thirdResult.grades,
                rationales: judgeResults[judgeIndex]?.rationales ?? thirdResult.rationales,
            })),
            consensus: {
                status: "provisional",
                method: hasDisagreement ? "third-judge-adjudication" : "independent-agreement",
                grades: consensusGrades,
            },
        };
    });

    return {
        schemaVersion: COMPARISON_SCHEMA_VERSION,
        kind: HUMAN_QRELS_KIND,
        status: "provisional-ai-adjudication",
        packetSha256,
        evaluation: reference.evaluation,
        judgementContract: {
            requiredIndependentJudges: 2,
            thirdJudgeRequiredOnDisagreement: true,
            gradeMeaning: "2=relevant, 1=borderline, 0=irrelevant",
        },
        provenance: {
            thirdJudgeSource,
            independentThirdJudge: false,
            adjudicationRule: "A/B 일치값은 유지하고 불일치값은 AI C 판정을 provisional consensus로 사용",
            threeWayDisagreementCount,
        },
        queries,
    };
}

export function validateHumanJudgements(
    judgements,
    { queryIds, requiredGameIdsByQuery, evaluation, judgementPacketSha256 = undefined },
) {
    const isApproved = judgements?.status === "approved";
    const isProvisionalAiAdjudication = judgements?.status === "provisional-ai-adjudication";
    if (!isApproved && !isProvisionalAiAdjudication) {
        return {
            status: "pending-human-judgement",
            blockingReasons: ["독립 human qrels가 approved 상태가 아닙니다."],
        };
    }
    if (isProvisionalAiAdjudication && judgements.provenance?.independentThirdJudge !== false) {
        fail("provisional-ai-adjudication qrels는 independentThirdJudge=false provenance가 필요합니다.");
    }
    if (judgements.schemaVersion !== COMPARISON_SCHEMA_VERSION || judgements.kind !== HUMAN_QRELS_KIND) {
        fail("human qrels kind/schemaVersion이 올바르지 않습니다.");
    }
    if (!/^[a-f0-9]{64}$/u.test(judgements.packetSha256 ?? "")) {
        fail("human qrels packetSha256가 없습니다.");
    }
    if (judgementPacketSha256 !== undefined && judgements.packetSha256 !== judgementPacketSha256) {
        fail("human qrels packetSha256가 canonical packet과 다릅니다.");
    }
    if (!Array.isArray(judgements.queries)) fail("human qrels queries가 없습니다.");
    if (judgements.evaluation?.topK !== evaluation.topK
        || judgements.evaluation?.candidatePoolSha256 !== evaluation.candidatePoolSha256) {
        fail("human qrels의 evaluation topK/candidate pool checksum이 packet과 다릅니다.");
    }
    const byId = new Map();
    for (const query of judgements.queries) {
        if (!isNonEmptyString(query?.id) || byId.has(query.id)) fail(`human qrels query ID가 없거나 중복되었습니다: ${query?.id ?? "<empty>"}`);
        byId.set(query.id, query);
    }
    for (const queryId of byId.keys()) {
        if (!queryIds.includes(queryId)) fail(`human qrels에 알 수 없는 query ID가 있습니다: ${queryId}`);
    }

    const gradesByQuery = {};
    for (const queryId of queryIds) {
        const judgement = byId.get(queryId);
        if (!judgement) fail(`human qrels에 ${queryId}가 없습니다.`);
        if (!Array.isArray(judgement.judges) || (judgement.judges.length !== 2 && judgement.judges.length !== 3)) {
            fail(`${queryId}에 독립 판정자 2명이 없습니다.`);
        }
        const judgeIds = new Set();
        const judgeGrades = judgement.judges.map((judge, judgeIndex) => {
            if (!isNonEmptyString(judge?.judgeId) || judgeIds.has(judge.judgeId)) {
                fail(`${queryId}의 판정자가 독립적이지 않습니다.`);
            }
            judgeIds.add(judge.judgeId);
            const expectedStatus = isProvisionalAiAdjudication ? "provisional" : "approved";
            if (judge.status !== expectedStatus) fail(`${queryId}의 판정 상태가 ${expectedStatus}가 아닙니다.`);
            const grades = normalizeGrades(judge.grades, `${queryId}.${judge.judgeId}.grades`);
            const rationales = normalizeRationales(judge.rationales, `${queryId}.${judge.judgeId}.rationales`);
            requireSameKeys(grades, rationales, `${queryId}.${judge.judgeId}`);
            if (judgeIndex < 2) {
                requireExactGradeKeys(grades, requiredGameIdsByQuery[queryId] ?? [], `${queryId}.${judge.judgeId}.grades`);
                requireExactGradeKeys(rationales, requiredGameIdsByQuery[queryId] ?? [], `${queryId}.${judge.judgeId}.rationales`);
            } else {
                requireSubsetGradeKeys(grades, requiredGameIdsByQuery[queryId] ?? [], `${queryId}.${judge.judgeId}.grades`);
                requireSubsetGradeKeys(rationales, requiredGameIdsByQuery[queryId] ?? [], `${queryId}.${judge.judgeId}.rationales`);
            }
            return grades;
        });
        const consensus = normalizeGrades(judgement.consensus?.grades, `${queryId}.consensus.grades`);
        const expectedConsensusStatus = isProvisionalAiAdjudication ? "provisional" : "approved";
        if (judgement.consensus?.status !== expectedConsensusStatus) {
            fail(`${queryId} consensus가 ${expectedConsensusStatus}가 아닙니다.`);
        }
        const requiredIds = requiredGameIdsByQuery[queryId] ?? [];
        requireExactGradeKeys(consensus, requiredIds, `${queryId}.consensus.grades`);
        const disagreementIds = requiredIds
            .map((gameId) => String(gameId))
            .filter((gameId) => judgeGrades[0][gameId] !== judgeGrades[1][gameId]);
        if (disagreementIds.length === 0) {
            if (judgeGrades.length !== 2 || judgement.consensus.method !== "independent-agreement") {
                fail(`${queryId} 일치 판정은 2인 independent-agreement여야 합니다.`);
            }
        } else {
            const expectedMethod = isProvisionalAiAdjudication
                ? "third-judge-adjudication"
                : "third-judge-majority";
            if (judgeGrades.length !== 3 || judgement.consensus.method !== expectedMethod) {
                fail(`${queryId} 불일치 판정은 제3 판정과 ${expectedMethod}여야 합니다.`);
            }
            requireExactGradeKeys(judgeGrades[2], disagreementIds, `${queryId}.${judgement.judges[2].judgeId}.grades`);
            requireExactGradeKeys(
                normalizeRationales(judgement.judges[2].rationales, `${queryId}.${judgement.judges[2].judgeId}.rationales`),
                disagreementIds,
                `${queryId}.${judgement.judges[2].judgeId}.rationales`,
            );
        }
        for (const gameId of Object.keys(consensus)) {
            const primaryDisagreement = judgeGrades[0][gameId] !== judgeGrades[1][gameId];
            if (primaryDisagreement && (!judgeGrades[2] || !Object.hasOwn(judgeGrades[2], gameId))) {
                fail(`${queryId} ${gameId}의 제3 판정이 없습니다.`);
            }
            if (isProvisionalAiAdjudication && primaryDisagreement) {
                if (judgeGrades[2][gameId] !== consensus[gameId]) {
                    fail(`${queryId}의 provisional consensus가 AI C 판정과 다릅니다: ${gameId}`);
                }
                continue;
            }
            const votes = judgeGrades.filter((grades) => grades[gameId] === consensus[gameId]).length;
            if (votes <= judgeGrades.length / 2) {
                fail(`${queryId}의 consensus가 독립 판정 다수결과 다릅니다: ${gameId}`);
            }
        }
        gradesByQuery[queryId] = consensus;
    }
    return {
        status: isProvisionalAiAdjudication ? "provisional-ai-adjudication" : "approved",
        gradesByQuery,
        blockingReasons: isProvisionalAiAdjudication
            ? ["AI C adjudication은 독립 human qrels가 아니므로 provisional 참고 지표로만 사용합니다."]
            : [],
    };
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

function requireExactGradeKeys(grades, requiredIds, name) {
    const required = new Set(requiredIds.map((gameId) => String(gameId)));
    for (const gameId of required) {
        if (!Object.hasOwn(grades, gameId)) fail(`${name}에 candidate 결과 game ID가 없습니다: ${gameId}`);
    }
    for (const gameId of Object.keys(grades)) {
        if (!required.has(gameId)) fail(`${name}가 evaluation candidate pool 밖의 game ID를 포함합니다: ${gameId}`);
    }
}

function requireSubsetGradeKeys(grades, requiredIds, name) {
    const required = new Set(requiredIds.map((gameId) => String(gameId)));
    for (const gameId of Object.keys(grades)) {
        if (!required.has(gameId)) fail(`${name}가 evaluation candidate pool 밖의 game ID를 포함합니다: ${gameId}`);
    }
}

function normalizeRationales(rationales, name) {
    requireObject(rationales, name);
    const normalized = {};
    for (const [gameId, rationale] of Object.entries(rationales)) {
        if (!/^\d+$/u.test(gameId) || String(Number(gameId)) !== gameId || !Number.isSafeInteger(Number(gameId))) {
            fail(`${name} game ID가 canonical decimal이 아닙니다: ${gameId}`);
        }
        if (!isNonEmptyString(rationale)) fail(`${name}에 판정 근거가 없습니다: ${gameId}`);
        normalized[gameId] = rationale;
    }
    return normalized;
}

function requireSameKeys(left, right, name) {
    const leftKeys = Object.keys(left).sort();
    const rightKeys = Object.keys(right).sort();
    if (JSON.stringify(leftKeys) !== JSON.stringify(rightKeys)) {
        fail(`${name}의 grade/rationale candidate 대상이 다릅니다.`);
    }
}

function requireAllowedObjectKeys(value, allowedKeys, name) {
    requireObject(value, name);
    for (const key of Object.keys(value)) {
        if (!allowedKeys.includes(key)) fail(`${name}에 허용되지 않은 필드가 있습니다: ${key}`);
    }
}

function requireExactObjectKeys(value, requiredKeys, name) {
    requireObject(value, name);
    const actualKeys = Object.keys(value).sort();
    const expectedKeys = [...requiredKeys].sort();
    if (JSON.stringify(actualKeys) !== JSON.stringify(expectedKeys)) {
        fail(`${name} 필드가 canonical schema와 다릅니다.`);
    }
}

function requireExactStringArray(value, expected, name) {
    if (!Array.isArray(value) || value.some((item) => !isNonEmptyString(item))
        || JSON.stringify(value) !== JSON.stringify(expected)) {
        fail(`${name}이 canonical schema와 다릅니다.`);
    }
}

function validateSha256Object(value, name) {
    requireExactObjectKeys(value, ["sha256"], name);
    if (!/^[a-f0-9]{64}$/u.test(value.sha256 ?? "")) fail(`${name}.sha256가 올바르지 않습니다.`);
}

function validateJudgementPacket(packet, name) {
    requireObject(packet, name);
    if (packet.schemaVersion !== COMPARISON_SCHEMA_VERSION
        || packet.kind !== "search-04-search-candidate-judgement-packet") {
        fail(`${name} kind/schemaVersion이 올바르지 않습니다.`);
    }
    const allowedPacketKeys = new Set([
        "schemaVersion", "kind", "status", "seed", "evaluation", "hides", "gradeScale",
        "judgementContract", "queries", "provenance",
    ]);
    for (const key of Object.keys(packet)) {
        if (!allowedPacketKeys.has(key)) fail(`${name}에 허용되지 않은 필드가 있습니다: ${key}`);
    }
    requireExactObjectKeys(packet.evaluation, ["topK", "candidatePoolSha256"], `${name}.evaluation`);
    validateTopK(packet.evaluation?.topK, `${name}.evaluation.topK`);
    if (!/^[a-f0-9]{64}$/u.test(packet.evaluation?.candidatePoolSha256 ?? "")) {
        fail(`${name}.evaluation.candidatePoolSha256가 올바르지 않습니다.`);
    }
    requireExactStringArray(packet.hides, ["candidate", "score", "sourceRank"], `${name}.hides`);
    requireExactObjectKeys(packet.gradeScale, ["relevant", "borderline", "irrelevant"], `${name}.gradeScale`);
    if (packet.gradeScale.relevant !== 2
        || packet.gradeScale.borderline !== 1
        || packet.gradeScale.irrelevant !== 0) {
        fail(`${name}.gradeScale이 canonical schema와 다릅니다.`);
    }
    requireExactObjectKeys(packet.judgementContract, [
        "requiredIndependentJudges", "thirdJudgeRequiredOnDisagreement", "gradeMeaning",
    ], `${name}.judgementContract`);
    if (packet.judgementContract.requiredIndependentJudges !== 2
        || packet.judgementContract.thirdJudgeRequiredOnDisagreement !== true
        || packet.judgementContract.gradeMeaning !== "2=relevant, 1=borderline, 0=irrelevant") {
        fail(`${name}.judgementContract가 canonical schema와 다릅니다.`);
    }
    if (packet.provenance !== undefined) {
        requireExactObjectKeys(packet.provenance, ["approvalReference", "searchText", "inputContract", "candidateCount"], `${name}.provenance`);
        if (!isNonEmptyString(packet.provenance.approvalReference)) fail(`${name}.provenance.approvalReference가 없습니다.`);
        validateSha256Object(packet.provenance.searchText, `${name}.provenance.searchText`);
        if (packet.provenance.inputContract !== null) {
            validateSha256Object(packet.provenance.inputContract, `${name}.provenance.inputContract`);
        }
        if (!Number.isSafeInteger(packet.provenance.candidateCount) || packet.provenance.candidateCount < 2) {
            fail(`${name}.provenance.candidateCount가 올바르지 않습니다.`);
        }
    }
    if (!Array.isArray(packet.queries) || packet.queries.length === 0) fail(`${name}.queries가 비어 있습니다.`);
    const queryIds = new Set();
    for (const query of packet.queries) {
        requireObject(query, `${name} query`);
        const allowedQueryKeys = new Set([
            "id", "query", "cohorts", "analysisClass", "anchor", "hardFilters", "judgementRubric", "candidates",
        ]);
        for (const key of Object.keys(query)) {
            if (!allowedQueryKeys.has(key)) fail(`${name} ${query.id ?? "<empty>"} query에 허용되지 않은 필드가 있습니다: ${key}`);
        }
        if (!isNonEmptyString(query?.id) || queryIds.has(query.id)) fail(`${name} query ID가 없거나 중복되었습니다.`);
        queryIds.add(query.id);
        if (!isNonEmptyString(query.query) || !Array.isArray(query.cohorts)
            || query.cohorts.some((cohort) => !isNonEmptyString(cohort))
            || !isNonEmptyString(query.analysisClass)
            || (query.anchor !== undefined && typeof query.anchor !== "boolean")) {
            fail(`${name} ${query.id} query metadata가 올바르지 않습니다.`);
        }
        requireAllowedObjectKeys(query.hardFilters, ["minPlayers", "maxPlayers", "maxPlayTimeMinutes"], `${name} ${query.id}.hardFilters`);
        for (const [key, value] of Object.entries(query.hardFilters)) {
            if (!Number.isSafeInteger(value) || value < 1) fail(`${name} ${query.id}.hardFilters.${key}가 올바르지 않습니다.`);
        }
        if (query.judgementRubric !== null) {
            requireExactObjectKeys(query.judgementRubric, ["relevant", "borderline", "irrelevant"], `${name} ${query.id}.judgementRubric`);
            if (Object.values(query.judgementRubric).some((rubric) => !isNonEmptyString(rubric))) {
                fail(`${name} ${query.id}.judgementRubric가 올바르지 않습니다.`);
            }
        }
        if (!Array.isArray(query.candidates) || query.candidates.length === 0) {
            fail(`${name} ${query.id} candidates가 비어 있습니다.`);
        }
        const candidateIds = new Set();
        for (const candidate of query.candidates) {
            requireObject(candidate, `${name} ${query.id} candidate`);
            const allowedCandidateKeys = new Set(["blindRank", "gameId", "evidenceText", "grade", "rationale"]);
            for (const key of Object.keys(candidate)) {
                if (!allowedCandidateKeys.has(key)) fail(`${name} ${query.id} candidate에 허용되지 않은 필드가 있습니다: ${key}`);
            }
            if (!Number.isSafeInteger(Number(candidate?.gameId)) || Number(candidate.gameId) < 1) {
                fail(`${name} ${query.id} candidate game ID가 올바르지 않습니다.`);
            }
            const gameId = String(Number(candidate.gameId));
            if (candidateIds.has(gameId)) fail(`${name} ${query.id} candidate game ID가 중복되었습니다: ${gameId}`);
            candidateIds.add(gameId);
            if (!Number.isSafeInteger(candidate.blindRank) || candidate.blindRank < 1
                || !isNonEmptyString(candidate.evidenceText)
                || (candidate.grade !== null && ![0, 1, 2].includes(candidate.grade))
                || (candidate.rationale !== null && !isNonEmptyString(candidate.rationale))) {
                fail(`${name} ${query.id} candidate metadata가 올바르지 않습니다.`);
            }
        }
    }
    return packet;
}

function judgementPacketIdentity(packet) {
    return JSON.stringify({
        schemaVersion: packet.schemaVersion,
        kind: packet.kind,
        seed: packet.seed ?? null,
        evaluation: packet.evaluation,
        hides: packet.hides ?? null,
        gradeScale: packet.gradeScale ?? null,
        judgementContract: packet.judgementContract ?? null,
        provenance: packet.provenance ?? null,
        queries: packet.queries.map((query) => ({
            id: query.id,
            query: query.query,
            cohorts: query.cohorts,
            analysisClass: query.analysisClass,
            anchor: query.anchor,
            hardFilters: query.hardFilters,
            judgementRubric: query.judgementRubric ?? null,
            candidates: query.candidates.map((candidate) => ({
                blindRank: candidate.blindRank,
                gameId: Number(candidate.gameId),
                evidenceText: candidate.evidenceText,
            })),
        })),
    });
}

function extractJudgeResult(judgeQuery, referenceQuery, name, { allowPartial = false } = {}) {
    if (judgeQuery?.id !== referenceQuery.id) fail(`${name} query ID가 canonical packet과 다릅니다.`);
    const grades = {};
    const rationales = {};
    for (const candidate of judgeQuery.candidates) {
        const gameId = String(Number(candidate.gameId));
        if (allowPartial && candidate.grade === null && candidate.rationale === null) continue;
        if (![0, 1, 2].includes(candidate.grade)) fail(`${name} ${gameId} grade는 0·1·2 중 하나여야 합니다.`);
        if (!isNonEmptyString(candidate.rationale)) fail(`${name} ${gameId} 판정 근거가 없습니다.`);
        grades[gameId] = candidate.grade;
        rationales[gameId] = candidate.rationale;
    }
    if (allowPartial) {
        requireSubsetGradeKeys(grades, referenceQuery.candidates.map((candidate) => candidate.gameId), `${name}.grades`);
    } else {
        requireExactGradeKeys(grades, referenceQuery.candidates.map((candidate) => candidate.gameId), `${name}.grades`);
    }
    return { grades, rationales };
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
    const options = { mode: null, hybridRrf: false, provisionalAiAdjudication: false };
    const valueOptions = new Set([
        "manifest", "out", "judgements", "topK", "canonicalPacket", "judgeA", "judgeB", "judgeC",
        "judgeAId", "judgeBId", "judgeCId",
    ]);
    for (let index = 0; index < args.length; index += 1) {
        const argument = args[index];
        if (argument === "--check" || argument === "--packet" || argument === "--metrics" || argument === "--qrels") {
            if (options.mode !== null) fail("--check·--packet·--metrics·--qrels 중 하나만 선택해야 합니다.");
            options.mode = argument.slice(2);
            continue;
        }
        if (argument === "--hybrid-rrf") {
            if (options.hybridRrf) fail("--hybrid-rrf가 중복되었습니다.");
            options.hybridRrf = true;
            continue;
        }
        if (argument === "--provisional-ai-adjudication") {
            if (options.provisionalAiAdjudication) fail("--provisional-ai-adjudication이 중복되었습니다.");
            options.provisionalAiAdjudication = true;
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
    if (!options.mode) fail("실행 모드가 필요합니다.");
    if (options.mode !== "qrels" && !options.manifest) fail("실행 모드와 --manifest가 필요합니다.");
    if (options.provisionalAiAdjudication && options.mode !== "qrels" && options.mode !== "metrics") {
        fail("--provisional-ai-adjudication은 --qrels 또는 --metrics에서만 사용할 수 있습니다.");
    }
    if (options.mode === "packet" && !options.out) fail("--packet에는 --out이 필요합니다.");
    if (options.mode === "qrels" && (!options.manifest || !options.canonicalPacket || !options.judgeA || !options.judgeB || !options.out)) {
        fail("--qrels에는 --manifest·--canonical-packet·--judge-a·--judge-b·--out이 필요합니다.");
    }
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
    writeJsonAtomically(resolvedOutput, `${JSON.stringify(value, null, 2)}\n`);
    return resolvedOutput;
}

export function writeJsonAtomically(outputPath, contents, {
    openFile = (filePath, flags, mode) => fs.openSync(filePath, flags, mode),
    writeFile = (fileDescriptor, value, options) => fs.writeFileSync(fileDescriptor, value, options),
    closeFile = fs.closeSync,
    publish = fs.linkSync,
    unlink = fs.unlinkSync,
    randomId = randomUUID,
} = {}) {
    const temporaryPath = path.join(
        path.dirname(outputPath),
        `.${path.basename(outputPath)}.${randomId()}.tmp`,
    );
    let fileDescriptor = null;
    let temporaryCreated = false;
    try {
        fileDescriptor = openFile(temporaryPath, "wx", 0o600);
        temporaryCreated = true;
        try {
            writeFile(fileDescriptor, contents, { encoding: "utf8" });
        } finally {
            if (fileDescriptor !== null) {
                const descriptorToClose = fileDescriptor;
                fileDescriptor = null;
                closeFile(descriptorToClose);
            }
        }
        publish(temporaryPath, outputPath);
        unlink(temporaryPath);
        temporaryCreated = false;
    } catch (error) {
        if (fileDescriptor !== null) {
            try {
                closeFile(fileDescriptor);
            } catch {
                // Preserve the original write/publish error.
            }
        }
        if (temporaryCreated) {
            try {
                unlink(temporaryPath);
            } catch {
                // Preserve the original write/publish error.
            }
        }
        throw error;
    }
}

function main() {
    try {
        const options = parseArgs(process.argv.slice(2));
        const manifestPath = path.resolve(options.manifest);
        if (options.mode === "qrels") {
            const loaded = loadComparisonManifest(manifestPath);
            const canonicalPacketPath = path.resolve(options.canonicalPacket);
            if (!loaded.judgementPacketDescriptor) {
                fail("--manifest에 canonical judgementPacket descriptor가 필요합니다.");
            }
            const expectedPacketPath = path.resolve(loaded.baseDir, loaded.judgementPacketDescriptor.path);
            if (canonicalPacketPath !== expectedPacketPath) {
                fail("--canonical-packet은 --manifest의 judgementPacket.path와 같아야 합니다.");
            }
            const judgeAPath = path.resolve(options.judgeA);
            const judgeBPath = path.resolve(options.judgeB);
            const thirdJudgePath = options.judgeC ? path.resolve(options.judgeC) : null;
            const canonicalPacketBytes = fs.readFileSync(canonicalPacketPath);
            if (sha256(canonicalPacketBytes) !== loaded.judgementPacketDescriptor.sha256) {
                fail("--canonical-packet이 manifest의 judgementPacket.sha256와 다릅니다.");
            }
            const qrelsBuilder = options.provisionalAiAdjudication
                ? buildProvisionalAiAdjudicationQrels
                : buildApprovedHumanQrels;
            const qrels = qrelsBuilder({
                packet: parseJson(canonicalPacketBytes, "canonical packet"),
                judgePackets: [
                    readJson(judgeAPath, "judge A packet"),
                    readJson(judgeBPath, "judge B packet"),
                ],
                thirdJudgePacket: thirdJudgePath ? readJson(thirdJudgePath, "third judge packet") : null,
                thirdJudgeSource: thirdJudgePath
                    ? path.relative(loaded.baseDir, thirdJudgePath)
                    : null,
                judgeIds: thirdJudgePath
                    ? [options.judgeAId ?? "judge-a", options.judgeBId ?? "judge-b", options.judgeCId ?? "judge-c"]
                    : [options.judgeAId ?? "judge-a", options.judgeBId ?? "judge-b"],
                packetSha256: sha256(canonicalPacketBytes),
            });
            const inputPaths = [
                manifestPath,
                canonicalPacketPath,
                judgeAPath,
                judgeBPath,
                ...(thirdJudgePath ? [thirdJudgePath] : []),
            ];
            const output = writeNewJson(options.out, qrels, inputPaths);
            console.log(JSON.stringify({ ok: true, status: qrels.status, output }, null, 2));
            return;
        }
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
            ...(options.judgements ? [path.resolve(loaded.baseDir, options.judgements)] : []),
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
            const packet = packetFromManifest({
                manifestPath,
                topK: options.topK ? Number(options.topK) : loaded.evaluationTopK,
            });
            const output = writeNewJson(options.out, packet, inputPaths);
            console.log(JSON.stringify({ ok: true, status: packet.status, output }, null, 2));
            return;
        }
        const report = compareFromManifest({
            manifestPath,
            judgementsPath: options.judgements,
            includeHybrid: options.hybridRrf,
            evaluationTopK: options.topK ? Number(options.topK) : undefined,
            allowProvisionalAiAdjudication: options.provisionalAiAdjudication,
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
