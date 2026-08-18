#!/usr/bin/env node

import fs from "node:fs";
import { createHash } from "node:crypto";
import path from "node:path";
import process from "node:process";
import { fileURLToPath } from "node:url";

export const COHORT_RULES = Object.freeze({
    "exact/name variant": 15,
    "intent/description": 25,
    "intent+hard filter": 20,
});

export const EVALUATION_PROFILES = Object.freeze({
    "development-seed": Object.freeze({
        minQueries: 12,
        maxQueries: 15,
        cohortMinimums: Object.freeze({
            "exact/name variant": 1,
            "intent/description": 1,
            "intent+hard filter": 1,
        }),
    }),
    "final-quality": Object.freeze({
        minQueries: 60,
        maxQueries: Number.POSITIVE_INFINITY,
        cohortMinimums: COHORT_RULES,
    }),
});

const ALLOWED_SCOPE_PREFIXES = Object.freeze([
    "docs/p2/search-evaluation/",
    "docs/p2/search.md",
    "docs/adr/game/README.md",
    "docs/adr/game/0072-search-quality-corpus-membership-and-versioning.md",
    "scripts/p2-search-evaluation.mjs",
    "scripts/p2-search-evaluation.test.mjs",
    ".github/workflows/ci.yml",
]);

const SOURCE_INTEGRITY = Symbol("searchEvaluationSourceIntegrity");

function evaluationProfile(value) {
    if (!isNonEmptyString(value) || !(value in EVALUATION_PROFILES)) {
        fail("SEARCH-04 evaluationProfile이 올바르지 않습니다.");
    }
    return EVALUATION_PROFILES[value];
}

export function validateEvaluationManifest(manifest, options = {}) {
    requireObject(manifest, "manifest");
    if (manifest.schemaVersion !== 1) {
        fail("SEARCH-04 manifest schemaVersion은 1이어야 합니다.");
    }
    if (manifest.featureId !== "SEARCH-04") {
        fail("SEARCH-04 manifest featureId가 올바르지 않습니다.");
    }
    if (!["draft", "quality-ready"].includes(manifest.status)) {
        fail("SEARCH-04 manifest status는 draft 또는 quality-ready여야 합니다.");
    }

    const profile = evaluationProfile(manifest.evaluationProfile);
    validateCatalogDescriptor(manifest.catalog);
    validateQualityCorpusDescriptor(manifest.qualityCorpus);
    validateVersioningContract(manifest);
    if (manifest.qualityCorpusPath && !manifest.qualityCorpus) {
        fail("qualityCorpusPath 파일이 로드되지 않았습니다.");
    }
    if (manifest.qualityCorpusSha256 !== undefined && !isSha256(manifest.qualityCorpusSha256)) {
        fail("qualityCorpusSha256 형식이 올바르지 않습니다.");
    }
    const queries = manifest.queries;
    if (!Array.isArray(queries)
        || queries.length < profile.minQueries
        || queries.length > profile.maxQueries) {
        const maximum = Number.isFinite(profile.maxQueries) ? `~${profile.maxQueries}` : "+";
        fail(`SEARCH-04 ${manifest.evaluationProfile} fixture는 ${profile.minQueries}${maximum}개 query가 필요합니다.`);
    }

    const queryIds = new Set();
    const queryTexts = new Set();
    const cohortCounts = Object.fromEntries(Object.keys(COHORT_RULES).map((cohort) => [cohort, 0]));
    const anchors = [];

    for (const query of queries) {
        validateQuery(query, manifest, profile, queryIds, queryTexts, cohortCounts);
        if (query.anchor === true) anchors.push(query);
    }

    for (const [cohort, minimum] of Object.entries(profile.cohortMinimums)) {
        if (cohortCounts[cohort] < minimum) {
            fail(`${cohort} cohort 최소 표본 ${minimum}개를 충족하지 못했습니다.`);
        }
    }
    validateQualityCorpusReferences(queries, manifest.qualityCorpus);
    if (options.catalog !== undefined) validateCatalogReferences(queries, options.catalog);
    if (anchors.length !== 3) {
        fail(`대표 anchor는 3개여야 합니다. 현재 ${anchors.length}개입니다.`);
    }
    for (const anchor of anchors) validateAnchor(anchor);

    validateJudgementContract(manifest.judgement);
    validateQualityShape(manifest.quality);

    return {
        featureId: manifest.featureId,
        evaluationProfile: manifest.evaluationProfile,
        status: manifest.status,
        queryCount: queries.length,
        cohortCounts,
        anchorCount: anchors.length,
    };
}

export function validateQualityReadiness(manifest, options = {}) {
    const structural = validateEvaluationManifest(manifest, options);
    const errors = [];

    if (manifest.evaluationProfile !== "final-quality") {
        errors.push("development seed는 final quality gate를 통과할 수 없습니다");
    }
    if (manifest.status !== "quality-ready") {
        errors.push("status가 quality-ready가 아닙니다");
    }
    if (manifest.catalog.releaseStatus !== "approved") {
        errors.push("catalog release 승인 상태가 아닙니다");
    }
    if (!isSha256(manifest.catalog.datasetSha256) || !Number.isInteger(manifest.catalog.rowCount)) {
        errors.push("catalog release의 checksum/rowCount가 없습니다");
    }
    if (manifest.qualityCorpus.releaseStatus !== "approved") {
        errors.push("quality corpus 승인 상태가 아닙니다");
    }
    if (!isSha256(manifest.qualityCorpus.source.sourceArtifactSha256)) {
        errors.push("quality corpus 원천 checksum이 없습니다");
    }
    if (!isSha256(manifest.qualityCorpusSha256)) {
        errors.push("quality corpus projection checksum이 없습니다");
    }
    if (!isSha256(manifest.queriesSha256)) {
        errors.push("fixture queries checksum이 없습니다");
    }
    if (!isNonEmptyString(manifest.queriesPath)) {
        errors.push("fixture queriesPath가 없습니다");
    }
    if (!isNonEmptyString(manifest.qualityCorpusPath)) {
        errors.push("quality corpus path가 없습니다");
    }
    if (manifest[SOURCE_INTEGRITY]?.queries !== true
        || manifest[SOURCE_INTEGRITY]?.qualityCorpus !== true) {
        errors.push("queries·quality corpus 원자료가 loadManifest에서 checksum 대조되지 않았습니다");
    }
    if (!Array.isArray(manifest.approvalReferences) || manifest.approvalReferences.length === 0) {
        errors.push("승인 근거 reference가 없습니다");
    }

    if (manifest.judgement?.status !== "approved") {
        errors.push("독립 판정 상태가 approved가 아닙니다");
    }
    const judgementErrors = validateIndependentJudgements(manifest.queries);
    errors.push(...judgementErrors);
    const pendingLabels = manifest.queries.filter((query) => query.labelStatus !== "approved").length;
    if (pendingLabels > 0) errors.push(`${pendingLabels}개 query의 labelStatus가 approved가 아닙니다`);

    if (manifest.quality?.baseline?.status !== "approved") {
        errors.push("baseline status가 approved가 아닙니다");
    }
    for (const cohort of Object.keys(COHORT_RULES)) {
        const delta = cohortThreshold(manifest.cohorts?.[cohort]);
        if (!Number.isFinite(delta)) {
            errors.push(`${cohort} threshold가 승인되지 않았습니다`);
        }
    }
    if (qualityHardFilterViolationRate(manifest.quality) !== 0) {
        errors.push("hard-filter violation rate가 0이 아닙니다");
    }

    if (errors.length > 0) {
        fail(`quality-ready 판정/threshold/baseline 준비가 되지 않았습니다: ${errors.join("; ")}`);
    }
    return { ...structural, qualityReady: true };
}

export function calculateRankingMetrics({
    expectedGameIds,
    rankedGameIds,
    hardFilterViolationGameIds = [],
    k = 10,
}) {
    if (!Number.isInteger(k) || k < 1) fail("평가 k는 1 이상의 정수여야 합니다.");
    const expected = normalizeIds(expectedGameIds, "expectedGameIds");
    const ranked = normalizeIds(rankedGameIds, "rankedGameIds");
    const violations = new Set(normalizeIds(hardFilterViolationGameIds, "hardFilterViolationGameIds"));
    const topK = ranked.slice(0, k);
    const expectedSet = new Set(expected);
    const relevantRanks = topK
        .map((gameId, index) => ({ gameId, rank: index + 1 }))
        .filter(({ gameId }) => expectedSet.has(gameId));
    const relevantCount = relevantRanks.length;
    const recallAtK = expected.length === 0 ? 0 : relevantCount / expected.length;
    const mrrAtK = relevantRanks.length === 0 ? 0 : 1 / relevantRanks[0].rank;
    const dcg = relevantRanks.reduce((sum, { rank }) => sum + (1 / Math.log2(rank + 1)), 0);
    const idealRelevantCount = Math.min(expected.length, k);
    const idcg = Array.from({ length: idealRelevantCount }, (_, index) => 1 / Math.log2(index + 2))
        .reduce((sum, score) => sum + score, 0);
    const ndcgAtK = idcg === 0 ? 0 : dcg / idcg;
    const hardFilterViolationGameIdsInTopK = topK.filter((gameId) => violations.has(gameId));
    const hardFilterViolationRate = topK.length === 0
        ? 0
        : hardFilterViolationGameIdsInTopK.length / topK.length;
    const metricKeys = rankingMetricKeys(k);

    return {
        k,
        expectedCount: expected.length,
        relevantCountAtK: relevantCount,
        [metricKeys.recall]: recallAtK,
        [metricKeys.mrr]: mrrAtK,
        [metricKeys.ndcg]: ndcgAtK,
        hardFilterViolationRate,
        hardFilterViolationGameIds: hardFilterViolationGameIdsInTopK,
        qualityEligible: hardFilterViolationRate === 0,
    };
}

export function evaluateSearchResults({ manifest, candidateResults, baselineResults = undefined, catalog = undefined, k = 10 }) {
    validateEvaluationManifest(manifest, { catalog });
    const candidate = normalizeResults(candidateResults, "candidateResults");
    const baseline = baselineResults === undefined ? undefined : normalizeResults(baselineResults, "baselineResults");
    const perQuery = manifest.queries.map((query) => {
        const candidateResult = candidate.get(query.id);
        if (!candidateResult) fail(`query ${query.id}의 candidate 결과가 없습니다.`);
        const row = {
            queryId: query.id,
            cohorts: query.cohorts,
            candidate: calculateRankingMetrics({
                expectedGameIds: query.expectedGameIds,
                rankedGameIds: candidateResult.rankedGameIds,
                hardFilterViolationGameIds: candidateResult.hardFilterViolationGameIds,
                k,
            }),
        };
        if (baseline) {
            const baselineResult = baseline.get(query.id);
            if (!baselineResult) fail(`query ${query.id}의 baseline 결과가 없습니다.`);
            row.baseline = calculateRankingMetrics({
                expectedGameIds: query.expectedGameIds,
                rankedGameIds: baselineResult.rankedGameIds,
                hardFilterViolationGameIds: baselineResult.hardFilterViolationGameIds,
                k,
            });
            const metricKeys = rankingMetricKeys(k);
            row.delta = {
                [metricKeys.recall]: row.candidate[metricKeys.recall] - row.baseline[metricKeys.recall],
                [metricKeys.mrr]: row.candidate[metricKeys.mrr] - row.baseline[metricKeys.mrr],
                [metricKeys.ndcg]: row.candidate[metricKeys.ndcg] - row.baseline[metricKeys.ndcg],
            };
        }
        return row;
    });

    const cohorts = Object.fromEntries(Object.keys(COHORT_RULES).map((cohort) => [
        cohort,
        aggregateRows(perQuery.filter((row) => row.cohorts.includes(cohort))),
    ]));
    return {
        featureId: manifest.featureId,
        k,
        queryCount: perQuery.length,
        overall: aggregateRows(perQuery),
        cohorts,
        perQuery,
    };
}

export function validateScope(changedFiles) {
    if (!Array.isArray(changedFiles)) fail("changedFiles는 배열이어야 합니다.");
    const normalizedFiles = changedFiles.map((file) => {
        if (!isNonEmptyString(file)) fail("changedFiles에는 비어 있지 않은 경로만 허용됩니다.");
        const normalized = path.posix.normalize(file.replaceAll("\\", "/").replace(/^\.\//u, ""));
        return { original: file, normalized };
    });
    const invalid = normalizedFiles
        .filter(({ normalized }) => !ALLOWED_SCOPE_PREFIXES.some((allowed) => allowed.endsWith("/")
            ? normalized.startsWith(allowed)
            : normalized === allowed))
        .map(({ original }) => original);
    if (invalid.length > 0) {
        fail(`허용되지 않은 경로가 있습니다: ${invalid.join(", ")}`);
    }
    return { valid: true, changedFiles: changedFiles.length };
}

function validateQuery(query, manifest, profile, queryIds, queryTexts, cohortCounts) {
    requireObject(query, "query");
    if (!isNonEmptyString(query.id) || queryIds.has(query.id)) {
        fail(`query ID가 없거나 중복되었습니다: ${query.id ?? "<empty>"}`);
    }
    queryIds.add(query.id);
    if (!isNonEmptyString(query.query)) fail(`query ${query.id}의 query text가 없습니다.`);
    const normalizedText = query.query.trim().toLocaleLowerCase("ko-KR");
    if (queryTexts.has(normalizedText)) fail(`query text가 중복되었습니다: ${query.id}`);
    queryTexts.add(normalizedText);

    if (!Array.isArray(query.cohorts) || query.cohorts.length === 0) {
        fail(`query ${query.id}의 cohort가 없습니다.`);
    }
    if (new Set(query.cohorts).size !== query.cohorts.length) {
        fail(`query ${query.id}의 cohort가 중복되었습니다.`);
    }
    for (const cohort of query.cohorts) {
        if (!(cohort in COHORT_RULES)) fail(`query ${query.id}의 cohort가 올바르지 않습니다: ${cohort}`);
        cohortCounts[cohort] += 1;
    }

    if (!Array.isArray(query.expectedGameIds) || query.expectedGameIds.length === 0) {
        fail(`query ${query.id}의 expectedGameIds가 없습니다.`);
    }
    if (!Array.isArray(query.excludedGameIds)) fail(`query ${query.id}의 excludedGameIds가 없습니다.`);
    const expected = new Set(normalizeIds(query.expectedGameIds, `query ${query.id} expectedGameIds`));
    const excluded = new Set(normalizeIds(query.excludedGameIds, `query ${query.id} excludedGameIds`));
    if ([...expected].some((gameId) => excluded.has(gameId))) {
        fail(`query ${query.id}의 expected/excluded game ID가 겹칩니다.`);
    }
    if (!isNonEmptyObject(query.expectedReasons)) fail(`query ${query.id}의 expected 이유가 없습니다.`);
    for (const gameId of expected) {
        if (!isNonEmptyString(query.expectedReasons[gameId])) {
            fail(`query ${query.id} expected game ID ${gameId}의 이유가 없습니다.`);
        }
    }

    validateSource(query.source, manifest.catalog, manifest.qualityCorpus, query.id);
    validateHardFilters(query.hardFilters, query.id);
    if (profile === EVALUATION_PROFILES["development-seed"]) {
        if (query.evaluationStatus !== "development") {
            fail(`query ${query.id}는 development 상태여야 합니다.`);
        }
        if (query.labelStatus !== "provisional") {
            fail(`query ${query.id}의 labelStatus는 provisional이어야 합니다.`);
        }
    }
}

function validateAnchor(query) {
    if (query.expectedGameIds.length < 10 || query.expectedGameIds.length > 30) {
        fail(`anchor ${query.id}의 expected game ID는 10~30개여야 합니다.`);
    }
    if (query.excludedGameIds.length === 0) fail(`anchor ${query.id}의 excludedGameIds가 없습니다.`);
    if (!isNonEmptyObject(query.excludedReasons)) fail(`anchor ${query.id}의 excluded 이유가 없습니다.`);
    for (const gameId of query.excludedGameIds) {
        if (!isNonEmptyString(query.excludedReasons[gameId])) {
            fail(`anchor ${query.id} excluded game ID ${gameId}의 이유가 없습니다.`);
        }
    }
}

function validateCatalogDescriptor(catalog) {
    requireObject(catalog, "manifest.catalog");
    for (const field of ["releaseId", "datasetId", "fieldVersion", "manifestReference"]) {
        if (!isNonEmptyString(catalog[field])) fail(`manifest.catalog.${field}가 없습니다.`);
    }
    if (!["not-registered", "provisional", "approved"].includes(catalog.releaseStatus)) {
        fail("manifest.catalog.releaseStatus가 올바르지 않습니다.");
    }
}

function validateQualityCorpusDescriptor(corpus) {
    requireObject(corpus, "manifest.qualityCorpus");
    for (const field of ["corpusId", "corpusVersion", "releaseId", "releaseStatus"]) {
        if (!isNonEmptyString(corpus[field])) fail(`manifest.qualityCorpus.${field}가 없습니다.`);
    }
    if (!Number.isInteger(corpus.rankCutoff) || corpus.rankCutoff < 1) {
        fail("manifest.qualityCorpus.rankCutoff가 올바르지 않습니다.");
    }
    requireObject(corpus.source, "manifest.qualityCorpus.source");
    for (const field of [
        "releaseId",
        "releaseStatus",
        "datasetId",
        "sourceReference",
        "sourceManifestReference",
        "sourceArtifactSha256",
        "snapshotId",
        "snapshotVersion",
        "snapshotSha256",
        "catalogReleaseId",
        "mappingStatus",
    ]) {
        if (!isNonEmptyString(corpus.source[field])) {
            fail(`manifest.qualityCorpus.source.${field}가 없습니다.`);
        }
    }
    if (!isSha256(corpus.source.sourceArtifactSha256)) {
        fail("manifest.qualityCorpus.source.sourceArtifactSha256 형식이 올바르지 않습니다.");
    }
    if (!isSha256(corpus.source.snapshotSha256)) {
        fail("manifest.qualityCorpus.source.snapshotSha256 형식이 올바르지 않습니다.");
    }
    requireObject(corpus.selection, "manifest.qualityCorpus.selection");
    for (const field of [
        "mode",
        "note",
        "ruleVersion",
        "mappingKey",
        "memberIdField",
        "dedupe",
        "languageExclusionPolicy",
    ]) {
        if (!isNonEmptyString(corpus.selection[field])) {
            fail(`manifest.qualityCorpus.selection.${field}가 없습니다.`);
        }
    }
    if (![1000, 5000, 10000].includes(corpus.selection.targetSize)
        || corpus.selection.targetSize !== corpus.rankCutoff) {
        fail("quality corpus targetSize는 rankCutoff와 같은 1,000·5,000·10,000 중 하나여야 합니다.");
    }
    if (corpus.selection.memberIdField !== "gameId" || corpus.selection.mappingKey !== "bggId") {
        fail("quality corpus selection은 bggId를 gameId로 매핑해야 합니다.");
    }
    if (corpus.selection.dedupe !== "unique bggId; retain lowest valid boardlife rank") {
        fail("quality corpus 중복 제거 규칙이 올바르지 않습니다.");
    }
    if (corpus.selection.cutoffAppliedAfterMapping !== true || corpus.selection.snapshotPinned !== true) {
        fail("quality corpus cutoff은 mapping 뒤에 적용되고 snapshot을 고정해야 합니다.");
    }
    if (JSON.stringify(corpus.selection.order) !== JSON.stringify(["boardlifeRank:asc", "bggId:asc"])) {
        fail("quality corpus 정렬 규칙은 BoardLife rank와 BGG ID 오름차순이어야 합니다.");
    }
    if (!Array.isArray(corpus.members) || corpus.members.length === 0) {
        fail("manifest.qualityCorpus.members가 없습니다.");
    }
    if (corpus.selection.memberCount !== corpus.members.length) {
        fail("quality corpus selection.memberCount가 members와 다릅니다.");
    }
    const memberIds = new Set();
    let previousMember;
    for (const member of corpus.members) {
        requireObject(member, "manifest.qualityCorpus.member");
        const id = recordId(member);
        if (!Number.isInteger(id) || id < 1 || memberIds.has(String(id))) {
            fail(`manifest.qualityCorpus member ID가 없거나 중복되었습니다: ${id ?? "<empty>"}`);
        }
        memberIds.add(String(id));
        if (!Number.isInteger(member.boardlifeRank)
            || member.boardlifeRank < 1
            || member.boardlifeRank > corpus.rankCutoff) {
            fail(`manifest.qualityCorpus member ${id}의 BoardLife rank가 cutoff 밖입니다.`);
        }
        if (previousMember
            && (member.boardlifeRank < previousMember.boardlifeRank
                || (member.boardlifeRank === previousMember.boardlifeRank && id < previousMember.id))) {
            fail("quality corpus members는 BoardLife rank·BGG ID 오름차순이어야 합니다.");
        }
        previousMember = { boardlifeRank: member.boardlifeRank, id };
    }
}

function validateVersioningContract(manifest) {
    for (const field of ["evaluationVersion", "qualityCorpusVersion", "selectionRuleVersion"]) {
        if (!isNonEmptyString(manifest[field])) fail(`manifest.${field}가 없습니다.`);
    }
    if (manifest.qualityCorpusVersion !== manifest.qualityCorpus.corpusVersion) {
        fail("manifest qualityCorpusVersion과 quality corpus corpusVersion이 다릅니다.");
    }
    if (manifest.selectionRuleVersion !== manifest.qualityCorpus.selection.ruleVersion) {
        fail("manifest selectionRuleVersion과 quality corpus ruleVersion이 다릅니다.");
    }

    requireObject(manifest.corpusSnapshot, "manifest.corpusSnapshot");
    for (const field of ["snapshotId", "snapshotVersion", "snapshotSha256"]) {
        if (!isNonEmptyString(manifest.corpusSnapshot[field])) {
            fail(`manifest.corpusSnapshot.${field}가 없습니다.`);
        }
    }
    if (manifest.corpusSnapshot.fixed !== true || !isSha256(manifest.corpusSnapshot.snapshotSha256)) {
        fail("manifest.corpusSnapshot은 고정 snapshot과 SHA-256을 가져야 합니다.");
    }
    const source = manifest.qualityCorpus.source;
    if (manifest.corpusSnapshot.snapshotId !== source.snapshotId
        || manifest.corpusSnapshot.snapshotVersion !== source.snapshotVersion
        || manifest.corpusSnapshot.snapshotSha256 !== source.snapshotSha256) {
        fail("manifest snapshot과 quality corpus source snapshot이 다릅니다.");
    }
    if (source.catalogReleaseId !== manifest.catalog.releaseId) {
        fail("quality corpus mapping의 catalog release가 manifest와 다릅니다.");
    }

    requireObject(manifest.index, "manifest.index");
    if (manifest.index.corpusVersion !== manifest.qualityCorpusVersion
        || manifest.index.corpusSha256 !== manifest.qualityCorpusSha256) {
        fail("index가 quality corpus version/checksum을 고정하지 않았습니다.");
    }
    if (!isSha256(manifest.index.corpusSha256)) {
        fail("manifest.index.corpusSha256 형식이 올바르지 않습니다.");
    }
    if (!Array.isArray(manifest.index.allowedStates)
        || JSON.stringify(manifest.index.allowedStates) !== JSON.stringify(["BUILDING", "READY", "FAILED"])) {
        fail("manifest.index.allowedStates는 BUILDING·READY·FAILED여야 합니다.");
    }
    if (!isNonEmptyString(manifest.index.status)
        || !["not-registered", "BUILDING", "READY", "FAILED"].includes(manifest.index.status)) {
        fail("manifest.index.status가 올바르지 않습니다.");
    }
    if (manifest.index.rollbackPolicy !== "retain-previous-ready") {
        fail("index 실패 시 이전 READY를 유지하는 rollback 정책이 필요합니다.");
    }
}

function validateSource(source, catalog, qualityCorpus, queryId) {
    requireObject(source, `query ${queryId} source`);
    for (const field of ["releaseId", "fieldVersion", "reference", "qualityCorpusReleaseId", "qualityCorpusReference"]) {
        if (!isNonEmptyString(source[field])) fail(`query ${queryId} source.${field}가 없습니다.`);
    }
    if (source.releaseId !== catalog.releaseId || source.fieldVersion !== catalog.fieldVersion) {
        fail(`query ${queryId}의 source release/fieldVersion이 catalog와 다릅니다.`);
    }
    if (source.qualityCorpusReleaseId !== qualityCorpus.releaseId) {
        fail(`query ${queryId}의 source quality corpus release가 manifest와 다릅니다.`);
    }
}

function validateQualityCorpusReferences(queries, corpus) {
    const records = catalogRecords(corpus);
    for (const query of queries) {
        for (const gameId of [...query.expectedGameIds, ...query.excludedGameIds]) {
            if (!records.has(String(gameId))) {
                fail(`query ${query.id}의 Top 1,000 quality corpus 밖 game ID입니다: ${gameId}`);
            }
        }
        for (const gameId of query.expectedGameIds) {
            validateHardFilterCompatibility(query, records.get(String(gameId)));
        }
    }
}

function validateHardFilters(hardFilters, queryId) {
    if (hardFilters === undefined) return;
    requireObject(hardFilters, `query ${queryId} hardFilters`);
    for (const field of ["minPlayers", "maxPlayers", "maxPlayTimeMinutes"]) {
        if (hardFilters[field] !== undefined
            && (!Number.isInteger(hardFilters[field]) || hardFilters[field] < 1)) {
            fail(`query ${queryId} hard filter ${field}가 올바르지 않습니다.`);
        }
    }
    if (hardFilters.minPlayers !== undefined && hardFilters.maxPlayers !== undefined
        && hardFilters.minPlayers > hardFilters.maxPlayers) {
        fail(`query ${queryId} hard filter의 minPlayers가 maxPlayers보다 큽니다.`);
    }
}

function validateCatalogReferences(queries, catalog) {
    const records = catalogRecords(catalog);
    for (const query of queries) {
        for (const gameId of query.expectedGameIds) {
            const record = records.get(String(gameId));
            if (!record) fail(`query ${query.id}의 catalog 밖 game ID입니다: ${gameId}`);
            validateHardFilterCompatibility(query, record);
        }
        for (const gameId of query.excludedGameIds) {
            if (!records.has(String(gameId))) {
                fail(`query ${query.id}의 catalog 밖 excluded game ID입니다: ${gameId}`);
            }
        }
    }
}

function validateHardFilterCompatibility(query, record) {
    const filters = query.hardFilters ?? {};
    if (filters.minPlayers !== undefined && Number.isInteger(record.maxPlayers)
        && filters.minPlayers > record.maxPlayers) {
        fail(`query ${query.id}의 expected game ID ${recordId(record)}가 hard filter와 모순됩니다.`);
    }
    if (filters.maxPlayers !== undefined && Number.isInteger(record.minPlayers)
        && filters.maxPlayers < record.minPlayers) {
        fail(`query ${query.id}의 expected game ID ${recordId(record)}가 hard filter와 모순됩니다.`);
    }
    if (filters.maxPlayTimeMinutes !== undefined && Number.isInteger(record.maxPlayTimeMinutes)
        && filters.maxPlayTimeMinutes < record.maxPlayTimeMinutes) {
        fail(`query ${query.id}의 expected game ID ${recordId(record)}가 hard filter와 모순됩니다.`);
    }
}

function validateJudgementContract(judgement) {
    requireObject(judgement, "manifest.judgement");
    if (!Number.isInteger(judgement.requiredIndependentJudges)
        || judgement.requiredIndependentJudges < 2) {
        fail("독립 판정자는 최소 2명이어야 합니다.");
    }
    if (judgement.thirdJudgeRequiredOnDisagreement !== true) {
        fail("판정 불일치 시 제3 판정자 규칙이 필요합니다.");
    }
    if (!isNonEmptyString(judgement.status)) fail("manifest.judgement.status가 없습니다.");
}

function validateQualityShape(quality) {
    requireObject(quality, "manifest.quality");
    const violationRate = qualityHardFilterViolationRate(quality);
    if (!Number.isFinite(violationRate) || violationRate < 0 || violationRate > 1) {
        fail("manifest.quality.hard_filter_violation_rate가 올바르지 않습니다.");
    }
    requireObject(quality.baseline, "manifest.quality.baseline");
    if (!isNonEmptyString(quality.baseline.status)) fail("baseline status가 없습니다.");
}

function cohortThreshold(cohort) {
    return cohort?.min_delta_vs_baseline ?? cohort?.minDeltaVsBaseline;
}

function qualityHardFilterViolationRate(quality) {
    return quality?.hard_filter_violation_rate ?? quality?.hardFilterViolationRate;
}

function validateIndependentJudgements(queries) {
    const errors = [];
    const missing = [];
    const notApproved = [];
    const disagreements = [];
    const labelMismatches = [];
    for (const query of queries) {
        if (!Array.isArray(query.judgements) || query.judgements.length < 2) {
            missing.push(query.id);
            continue;
        }
        const judges = new Set();
        for (const judgement of query.judgements) {
            if (!isNonEmptyString(judgement.judgeId) || judges.has(judgement.judgeId)) {
                errors.push(`${query.id}의 판정자가 독립적이지 않습니다`);
            }
            judges.add(judgement.judgeId);
            if (judgement.status !== "approved") {
                notApproved.push(query.id);
            }
            if (!Array.isArray(judgement.expectedGameIds) || !Array.isArray(judgement.excludedGameIds)) {
                errors.push(`${query.id}의 판정 결과 ID가 없습니다`);
            }
        }
        const consensusSignature = judgementConsensusSignature(query.judgements);
        if (consensusSignature === null) {
            disagreements.push(query.id);
        } else if (judgementSignature(query) !== consensusSignature) {
            labelMismatches.push(query.id);
        }
    }
    if (missing.length > 0) errors.push(`${missing.length}개 query에 독립 판정 2개가 없습니다`);
    if (notApproved.length > 0) errors.push(`approved가 아닌 판정이 ${notApproved.length}개 있습니다`);
    if (disagreements.length > 0) {
        errors.push(`판정 불일치를 해소할 제3 판정 또는 다수 합의가 ${new Set(disagreements).size}개 없습니다`);
    }
    if (labelMismatches.length > 0) {
        errors.push(`query label이 승인 판정 합의와 다른 query가 ${labelMismatches.length}개 있습니다`);
    }
    return errors;
}

function judgementSignature(judgement) {
    return JSON.stringify({
        expectedGameIds: normalizeIds(judgement.expectedGameIds ?? [], "judgement expectedGameIds").sort(),
        excludedGameIds: normalizeIds(judgement.excludedGameIds ?? [], "judgement excludedGameIds").sort(),
    });
}

function judgementConsensusSignature(judgements) {
    const counts = new Map();
    for (const judgement of judgements) {
        const signature = judgementSignature(judgement);
        counts.set(signature, (counts.get(signature) ?? 0) + 1);
    }
    const [consensus] = [...counts.entries()].sort((left, right) => right[1] - left[1]);
    if (!consensus || consensus[1] <= judgements.length / 2) return null;
    return consensus[0];
}

function normalizeResults(results, name) {
    const entries = Array.isArray(results)
        ? results.map((result) => [result.queryId, result])
        : Object.entries(results ?? {});
    const normalized = new Map();
    for (const [queryId, result] of entries) {
        if (!isNonEmptyString(queryId) || normalized.has(queryId)) fail(`${name} query ID가 중복되었습니다.`);
        requireObject(result, `${name}.${queryId}`);
        normalized.set(queryId, {
            rankedGameIds: result.rankedGameIds ?? result.ranked ?? [],
            hardFilterViolationGameIds: result.hardFilterViolationGameIds ?? [],
        });
    }
    return normalized;
}

function aggregateRows(rows) {
    if (rows.length === 0) return null;
    const metricKeys = rankingMetricKeys(rows[0].candidate.k);
    const metrics = [metricKeys.recall, metricKeys.mrr, metricKeys.ndcg, "hardFilterViolationRate"];
    const average = Object.fromEntries(metrics.map((metric) => [
        metric,
        rows.reduce((sum, row) => sum + row.candidate[metric], 0) / rows.length,
    ]));
    const summary = {
        queryCount: rows.length,
        ...average,
        qualityEligible: average.hardFilterViolationRate === 0,
    };
    if (rows.every((row) => row.baseline)) {
        summary.baseline = Object.fromEntries(metrics.map((metric) => [
            metric,
            rows.reduce((sum, row) => sum + row.baseline[metric], 0) / rows.length,
        ]));
        summary.delta = Object.fromEntries(metrics
            .filter((metric) => metric !== "hardFilterViolationRate")
            .map((metric) => [metric, average[metric] - summary.baseline[metric]]));
    }
    return summary;
}

function rankingMetricKeys(k) {
    return {
        recall: `recallAt${k}`,
        mrr: `mrrAt${k}`,
        ndcg: `ndcgAt${k}`,
    };
}

function catalogRecords(catalog) {
    const values = Array.isArray(catalog) ? catalog : catalog?.games ?? catalog?.members;
    if (!Array.isArray(values)) fail("catalog은 game record 배열이어야 합니다.");
    return new Map(values.map((record) => [String(recordId(record)), record]));
}

function recordId(record) {
    return record?.id ?? record?.gameId ?? record?.bggId;
}

function normalizeIds(ids, name) {
    if (!Array.isArray(ids)) fail(`${name}는 배열이어야 합니다.`);
    const normalized = ids.map((id) => String(id));
    if (normalized.some((id) => id === "" || id === "undefined" || id === "null")) {
        fail(`${name}에 올바르지 않은 ID가 있습니다.`);
    }
    if (new Set(normalized).size !== normalized.length) fail(`${name}에 중복 ID가 있습니다.`);
    return normalized;
}

function requireObject(value, name) {
    if (value === null || typeof value !== "object" || Array.isArray(value)) {
        fail(`${name}은 object여야 합니다.`);
    }
}

function isNonEmptyObject(value) {
    return value !== null && typeof value === "object" && !Array.isArray(value)
        && Object.keys(value).length > 0;
}

function isNonEmptyString(value) {
    return typeof value === "string" && value.trim().length > 0;
}

function isSha256(value) {
    return typeof value === "string" && /^[a-f0-9]{64}$/u.test(value);
}

function fail(message) {
    throw new Error(message);
}

function parseArgs(args) {
    const options = { check: false, qualityGate: false, metrics: false };
    for (let index = 0; index < args.length; index += 1) {
        const argument = args[index];
        if (argument === "--check") {
            options.check = true;
        } else if (argument === "--quality-gate") {
            options.qualityGate = true;
        } else if (argument === "--metrics") {
            options.metrics = true;
        } else if (argument.startsWith("--")) {
            const value = args[index + 1];
            if (!value || value.startsWith("--")) fail(`${argument} 값이 필요합니다.`);
            const optionName = argument.slice(2).replace(/-([a-z])/gu, (_, letter) => letter.toUpperCase());
            options[optionName] = value;
            index += 1;
        } else {
            fail(`알 수 없는 인자입니다: ${argument}`);
        }
    }
    return options;
}

function readJson(filePath) {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

export function loadManifest(manifestPath) {
    const manifest = readJson(manifestPath);
    const sourceIntegrity = { queries: false, qualityCorpus: false };
    if (manifest.queriesPath) {
        const queryPath = path.resolve(path.dirname(manifestPath), manifest.queriesPath);
        const queryBytes = fs.readFileSync(queryPath);
        const queriesFromPath = JSON.parse(queryBytes.toString("utf8"));
        if (!Array.isArray(queriesFromPath)) fail("queriesPath 파일은 query 배열이어야 합니다.");
        if (manifest.queries && JSON.stringify(manifest.queries) !== JSON.stringify(queriesFromPath)) {
            fail("inline queries와 queriesPath 파일이 다릅니다.");
        }
        manifest.queries = queriesFromPath;
        if (manifest.queriesSha256 && !isSha256(manifest.queriesSha256)) {
            fail("queriesSha256 형식이 올바르지 않습니다.");
        }
        if (manifest.queriesSha256) {
            const actualSha256 = createHash("sha256").update(queryBytes).digest("hex");
            if (actualSha256 !== manifest.queriesSha256) fail("queriesSha256가 queries 원자료와 다릅니다.");
            sourceIntegrity.queries = true;
        }
    }
    if (manifest.qualityCorpusPath) {
        const corpusPath = path.resolve(path.dirname(manifestPath), manifest.qualityCorpusPath);
        const corpusBytes = fs.readFileSync(corpusPath);
        const qualityCorpusFromPath = JSON.parse(corpusBytes.toString("utf8"));
        if (manifest.qualityCorpus && JSON.stringify(manifest.qualityCorpus) !== JSON.stringify(qualityCorpusFromPath)) {
            fail("inline quality corpus와 qualityCorpusPath 파일이 다릅니다.");
        }
        manifest.qualityCorpus = qualityCorpusFromPath;
        if (manifest.qualityCorpusSha256 && !isSha256(manifest.qualityCorpusSha256)) {
            fail("qualityCorpusSha256 형식이 올바르지 않습니다.");
        }
        if (manifest.qualityCorpusSha256) {
            const actualSha256 = createHash("sha256").update(corpusBytes).digest("hex");
            if (actualSha256 !== manifest.qualityCorpusSha256) {
                fail("qualityCorpusSha256가 quality corpus 원자료와 다릅니다.");
            }
            sourceIntegrity.qualityCorpus = true;
        }
    }
    Object.defineProperty(manifest, SOURCE_INTEGRITY, {
        value: Object.freeze(sourceIntegrity),
        enumerable: false,
    });
    return manifest;
}

function main() {
    try {
        const options = parseArgs(process.argv.slice(2));
        if (!options.manifest) fail("--manifest 경로가 필요합니다.");
        const manifestPath = path.resolve(options.manifest);
        const manifest = loadManifest(manifestPath);
        const catalog = options.catalog ? readJson(path.resolve(options.catalog)) : undefined;

        if (options.changedFiles) {
            validateScope(options.changedFiles.split(",").map((file) => file.trim()).filter(Boolean));
        }
        let result;
        if (options.qualityGate) {
            result = validateQualityReadiness(manifest, { catalog });
        } else if (options.metrics) {
            if (!options.results) fail("--metrics에는 --results 경로가 필요합니다.");
            const candidate = readJson(path.resolve(options.results));
            const baseline = options.baseline ? readJson(path.resolve(options.baseline)) : undefined;
            result = evaluateSearchResults({ manifest, candidateResults: candidate, baselineResults: baseline, catalog });
        } else {
            result = validateEvaluationManifest(manifest, { catalog });
        }
        console.log(JSON.stringify({ ok: true, ...result }, null, 2));
    } catch (error) {
        console.error(JSON.stringify({ ok: false, error: error.message }, null, 2));
        process.exitCode = 1;
    }
}

if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) main();
