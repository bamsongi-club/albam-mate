import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
    calculateRankingMetrics,
    evaluateSearchResults,
    loadManifest,
    validateEvaluationManifest,
    validateQualityReadiness,
    validateScope,
} from "./p2-search-evaluation.mjs";

test("draft fixture는 세 cohort 최소 표본과 대표 query 계약을 검증한다", () => {
    const manifest = buildManifest();

    assert.doesNotThrow(() => validateEvaluationManifest(manifest));
    assert.equal(manifest.queries.length, 60);
});

test("저장된 draft fixture의 manifest와 query 원자료를 검증한다", () => {
    const manifest = JSON.parse(fs.readFileSync(new URL("../docs/p2/search-evaluation/manifest.json", import.meta.url)));
    const queries = JSON.parse(fs.readFileSync(new URL("../docs/p2/search-evaluation/queries.json", import.meta.url)));
    const qualityCorpus = JSON.parse(fs.readFileSync(new URL("../docs/p2/search-evaluation/quality-corpus.json", import.meta.url)));

    assert.equal(manifest.queriesSha256.length, 64);
    assert.equal(manifest.evaluationProfile, "development-seed");
    assert.doesNotThrow(() => validateEvaluationManifest({ ...manifest, queries, qualityCorpus }));
    assert.equal(queries.filter((query) => query.anchor).length, 3);
    assert.equal(queries.length, 15);
});

test("저장된 query 원자료 checksum이 manifest와 일치한다", () => {
    const manifest = JSON.parse(fs.readFileSync(new URL("../docs/p2/search-evaluation/manifest.json", import.meta.url)));
    const queryBytes = fs.readFileSync(new URL("../docs/p2/search-evaluation/queries.json", import.meta.url));
    const actualSha256 = createHash("sha256").update(queryBytes).digest("hex");

    assert.equal(manifest.queriesSha256, actualSha256);
});

test("저장된 quality corpus projection checksum이 manifest와 일치한다", () => {
    const manifest = JSON.parse(fs.readFileSync(new URL("../docs/p2/search-evaluation/manifest.json", import.meta.url)));
    const corpusBytes = fs.readFileSync(new URL("../docs/p2/search-evaluation/quality-corpus.json", import.meta.url));
    const actualSha256 = createHash("sha256").update(corpusBytes).digest("hex");

    assert.equal(manifest.qualityCorpusSha256, actualSha256);
});

test("development seed는 Top 1,000 membership 밖 ID와 final 승격을 거절한다", () => {
    const manifest = JSON.parse(fs.readFileSync(new URL("../docs/p2/search-evaluation/manifest.json", import.meta.url)));
    const queries = JSON.parse(fs.readFileSync(new URL("../docs/p2/search-evaluation/queries.json", import.meta.url)));
    const qualityCorpus = JSON.parse(fs.readFileSync(new URL("../docs/p2/search-evaluation/quality-corpus.json", import.meta.url)));

    assert.throws(() => validateQualityReadiness({ ...manifest, queries, qualityCorpus }), /development seed/u);
    const invalid = { ...manifest, queries, qualityCorpus };
    invalid.queries[0].expectedGameIds = [999999];
    invalid.queries[0].expectedReasons = { 999999: "테스트 후보" };
    assert.throws(() => validateEvaluationManifest(invalid), /Top 1,000 quality corpus/u);
});

test("cohort 표본이 부족하면 fixture를 거절한다", () => {
    const manifest = buildManifest();
    manifest.queries[59].cohorts = ["exact/name variant"];

    assert.throws(
        () => validateEvaluationManifest(manifest),
        /intent\+hard filter cohort 최소 표본 20개/u,
    );
});

test("query ID 중복을 거절한다", () => {
    const manifest = buildManifest();
    manifest.queries[1].id = manifest.queries[0].id;

    assert.throws(
        () => validateEvaluationManifest(manifest),
        /중복/u,
    );
});

test("query text 누락을 거절한다", () => {
    const manifest = buildManifest();
    manifest.queries[1].query = "";

    assert.throws(
        () => validateEvaluationManifest(manifest),
        /query text/u,
    );
});

test("catalog 밖의 game ID와 hard filter 모순을 거절한다", () => {
    const manifest = buildManifest();
    manifest.queries[0].expectedGameIds = [999];
    manifest.queries[0].expectedReasons = { 999: "테스트 기대 근거" };
    manifest.qualityCorpus.members.push({
        gameId: 999,
        boardlifeRank: 999,
        minPlayers: 2,
        maxPlayers: 6,
        maxPlayTimeMinutes: 30,
    });
    manifest.qualityCorpus.selection.memberCount = manifest.qualityCorpus.members.length;
    assert.throws(
        () => validateEvaluationManifest(manifest, {
            catalog: [{ id: 1, minPlayers: 2, maxPlayers: 4 }],
        }),
        /catalog.*ID/u,
    );

    const contradiction = buildManifest();
    contradiction.queries[0].expectedGameIds = [1];
    contradiction.queries[0].expectedReasons = { 1: "테스트 기대 근거" };
    contradiction.queries[0].hardFilters = { minPlayers: 5 };
    assert.throws(
        () => validateEvaluationManifest(contradiction, {
            catalog: [{ id: 1, minPlayers: 2, maxPlayers: 4 }],
        }),
        /hard filter/u,
    );
});

test("query source release/version 누락을 거절한다", () => {
    const manifest = buildManifest();
    delete manifest.queries[0].source.reference;

    assert.throws(
        () => validateEvaluationManifest(manifest),
        /source\.reference/u,
    );
});

test("quality-ready는 두 독립 판정과 승인된 임계값이 없으면 실패한다", () => {
    const manifest = buildManifest();

    assert.throws(
        () => validateQualityReadiness(manifest),
        /판정|threshold|baseline/u,
    );
});

test("승인된 판정 합의와 query label이 일치하면 quality-ready를 통과한다", () => {
    const result = withLoadedQualityReadyManifest((manifest) => validateQualityReadiness(manifest));

    assert.equal(result.qualityReady, true);
});

test("quality-ready는 loadManifest가 검증한 원자료 없이 통과하지 않는다", () => {
    assert.throws(
        () => validateQualityReadiness(buildQualityReadyManifest()),
        /원자료|loadManifest/u,
    );
});

test("quality-ready는 queriesPath와 qualityCorpusPath를 모두 요구한다", () => {
    const missingQueriesPath = buildQualityReadyManifest();
    delete missingQueriesPath.queriesPath;
    assert.throws(() => validateQualityReadiness(missingQueriesPath), /queriesPath/u);

    const missingCorpusPath = buildQualityReadyManifest();
    delete missingCorpusPath.qualityCorpusPath;
    assert.throws(() => validateQualityReadiness(missingCorpusPath), /quality corpus path/u);
});

test("loadManifest는 inline 원자료와 path 파일이 다르면 거절한다", () => {
    const fixture = createQualityReadyFixture();
    try {
        const manifest = JSON.parse(fs.readFileSync(fixture.manifestPath, "utf8"));
        manifest.queries[0].query = "inline 불일치";
        fs.writeFileSync(fixture.manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

        assert.throws(
            () => loadManifest(fixture.manifestPath),
            /inline queries와 queriesPath/u,
        );
    } finally {
        fs.rmSync(fixture.directory, { recursive: true, force: true });
    }
});

test("quality-ready는 queries checksum 형식이 아니면 실패한다", () => {
    const manifest = buildQualityReadyManifest();
    manifest.queriesSha256 = "pending";

    assert.throws(
        () => validateQualityReadiness(manifest),
        /checksum/u,
    );
});

test("query label이 승인 판정 합의와 다르면 quality-ready를 거절한다", () => {
    const manifest = buildQualityReadyManifest();
    manifest.queries[0].expectedGameIds = [2, 3, 4, 5, 6, 7, 8, 9, 10, 11];
    manifest.queries[0].expectedReasons = Object.fromEntries(
        manifest.queries[0].expectedGameIds.map((gameId) => [gameId, "테스트 기대 근거"]),
    );

    assert.throws(
        () => validateQualityReadiness(manifest),
        /label.*합의/u,
    );
});

test("판정자 간 불일치에는 제3 판정이 필요하다", () => {
    const manifest = buildQualityReadyManifest();
    manifest.queries[0].judgements[1].expectedGameIds = [999];

    assert.throws(
        () => validateQualityReadiness(manifest),
        /제3 판정/u,
    );
});

test("제3 판정이 다수 합의를 만들면 quality-ready를 통과한다", () => {
    const result = withLoadedQualityReadyManifest((manifest) => {
        const query = manifest.queries[0];
        query.judgements[1].expectedGameIds = [2];
        query.judgements.push({
            judgeId: "judge-c",
            status: "approved",
            expectedGameIds: query.expectedGameIds,
            excludedGameIds: query.excludedGameIds,
        });
        return validateQualityReadiness(manifest);
    });

    assert.equal(result.qualityReady, true);
});

test("quality corpus target은 170,000 전체 catalog를 품질 corpus로 승격하지 못한다", () => {
    const manifest = buildManifest();
    manifest.qualityCorpus.selection.targetSize = 170000;

    assert.throws(
        () => validateEvaluationManifest(manifest),
        /1,000·5,000·10,000|targetSize/u,
    );
});

test("quality corpus version·snapshot·selection 규칙이 서로 다르면 거절한다", () => {
    const mixedVersion = buildManifest();
    mixedVersion.index.corpusVersion = "other-corpus-v1";
    assert.throws(() => validateEvaluationManifest(mixedVersion), /version\/checksum/u);

    const contradictoryTarget = buildManifest();
    contradictoryTarget.qualityCorpus.selection.targetSize = 5000;
    assert.throws(() => validateEvaluationManifest(contradictoryTarget), /rankCutoff|targetSize/u);

    const changedSnapshot = buildManifest();
    changedSnapshot.corpusSnapshot.snapshotSha256 = "e".repeat(64);
    assert.throws(() => validateEvaluationManifest(changedSnapshot), /snapshot/u);
});

test("ADR-0072 selection·index 불변식은 각각 회귀를 거절한다", () => {
    const cutoffBeforeMapping = buildManifest();
    cutoffBeforeMapping.qualityCorpus.selection.cutoffAppliedAfterMapping = false;
    assert.throws(() => validateEvaluationManifest(cutoffBeforeMapping), /mapping 뒤에/u);

    const snapshotNotPinned = buildManifest();
    snapshotNotPinned.qualityCorpus.selection.snapshotPinned = false;
    assert.throws(() => validateEvaluationManifest(snapshotNotPinned), /snapshot/u);

    const invalidDedupe = buildManifest();
    invalidDedupe.qualityCorpus.selection.dedupe = "first row wins";
    assert.throws(() => validateEvaluationManifest(invalidDedupe), /중복 제거/u);

    const unsortedMembers = buildManifest();
    [unsortedMembers.qualityCorpus.members[0], unsortedMembers.qualityCorpus.members[1]] = [
        unsortedMembers.qualityCorpus.members[1],
        unsortedMembers.qualityCorpus.members[0],
    ];
    assert.throws(() => validateEvaluationManifest(unsortedMembers), /오름차순/u);

    const invalidRollback = buildManifest();
    invalidRollback.index.rollbackPolicy = "delete-previous-ready";
    assert.throws(() => validateEvaluationManifest(invalidRollback), /rollback/u);

    const missingLanguagePolicy = buildManifest();
    delete missingLanguagePolicy.qualityCorpus.selection.languageExclusionPolicy;
    assert.throws(() => validateEvaluationManifest(missingLanguagePolicy), /languageExclusionPolicy/u);
});

test("quality corpus members 구조 오류를 memberCount보다 먼저 진단한다", () => {
    const missingMembers = buildManifest();
    delete missingMembers.qualityCorpus.members;
    assert.throws(() => validateEvaluationManifest(missingMembers), /members가 없습니다/u);

    const emptyMembers = buildManifest();
    emptyMembers.qualityCorpus.members = [];
    assert.throws(() => validateEvaluationManifest(emptyMembers), /members가 없습니다/u);
});

test("Recall·MRR·nDCG를 k별 반환 key와 함께 재현한다", () => {
    const metricsAt10 = calculateRankingMetrics({
        expectedGameIds: [1, 2, 3],
        rankedGameIds: [9, 2, 8, 1, 7, 3],
        k: 10,
    });

    assert.equal(metricsAt10.recallAt10, 1);
    assert.equal(metricsAt10.mrrAt10, 1 / 2);
    assert.equal(metricsAt10.ndcgAt10, (1 / Math.log2(3) + 1 / Math.log2(5) + 1 / Math.log2(7)) / (1 + 1 / Math.log2(3) + 1 / 2));

    const metricsAt5 = calculateRankingMetrics({
        expectedGameIds: [1, 2, 3],
        rankedGameIds: [9, 2, 8, 1, 7, 3],
        k: 5,
    });

    assert.equal(metricsAt5.recallAt5, 2 / 3);
    assert.equal(metricsAt5.mrrAt5, 1 / 2);
    assert.equal(metricsAt5.ndcgAt5, (1 / Math.log2(3) + 1 / Math.log2(5)) / (1 + 1 / Math.log2(3) + 1 / 2));
});

test("hard-filter violation이 있으면 품질 결과가 합격할 수 없다", () => {
    const metrics = calculateRankingMetrics({
        expectedGameIds: [1],
        rankedGameIds: [1, 2],
        hardFilterViolationGameIds: [2],
        k: 10,
    });

    assert.equal(metrics.hardFilterViolationRate, 1 / 2);
    assert.equal(metrics.qualityEligible, false);
});

test("cohort와 전체 집합의 평가 결과를 같은 fixture에서 재현한다", () => {
    const manifest = buildManifest();
    const candidateResults = Object.fromEntries(manifest.queries.map((query) => [query.id, {
        rankedGameIds: [query.expectedGameIds[0]],
        hardFilterViolationGameIds: [],
    }]));

    const first = evaluateSearchResults({ manifest, candidateResults });
    const second = evaluateSearchResults({ manifest, candidateResults });

    assert.deepEqual(first, second);
    assert.equal(first.overall.queryCount, 60);
    assert.equal(first.cohorts["exact/name variant"].queryCount, 15);
    assert.equal(first.cohorts["intent/description"].queryCount, 25);
    assert.equal(first.cohorts["intent+hard filter"].queryCount, 20);
    assert.equal(first.overall.hardFilterViolationRate, 0);

    const kFive = evaluateSearchResults({ manifest, candidateResults, k: 5 });
    assert.equal(typeof kFive.overall.recallAt5, "number");
    assert.equal(kFive.overall.recallAt10, undefined);
});

test("검색 평가 범위를 벗어난 변경 파일을 거절한다", () => {
    assert.doesNotThrow(() => validateScope([
        "docs/p2/search-evaluation/manifest.json",
        "docs/p2/search-evaluation/quality-corpus.json",
        "scripts/p2-search-evaluation.mjs",
        "scripts/p2-search-evaluation.test.mjs",
        ".github/workflows/ci.yml",
    ]));
    assert.throws(
        () => validateScope(["src/main/java/cloud/bamsongi/Game.java"]),
        /허용되지 않은 경로/u,
    );
    assert.throws(
        () => validateScope(["docs/p2/search-evaluation/../../../src/main/java/cloud/bamsongi/Game.java"]),
        /허용되지 않은 경로/u,
    );
});

function buildManifest() {
    const cohorts = [
        "exact/name variant",
        "intent/description",
        "intent+hard filter",
    ];
    const queries = Array.from({ length: 60 }, (_, index) => {
        const cohort = index < 15 ? cohorts[0] : index < 40 ? cohorts[1] : cohorts[2];
        const isAnchor = index < 3;
        return {
            id: `Q-${String(index + 1).padStart(3, "0")}`,
            query: isAnchor ? `대표 질의 ${index + 1}` : `확장 질의 ${index + 1}`,
            cohorts: [cohort],
            anchor: isAnchor,
            evaluationStatus: "final",
            labelStatus: "proposed",
            hardFilters: index === 0 ? { minPlayers: 3 } : {},
            expectedGameIds: isAnchor ? [1, 2, 3, 4, 5, 6, 7, 8, 9, 10] : [1],
            excludedGameIds: [99],
            excludedReasons: { 99: "대표 질의의 제외 근거" },
            expectedReasons: isAnchor
                ? Object.fromEntries([1, 2, 3, 4, 5, 6, 7, 8, 9, 10].map((id) => [id, "대표 질의의 공개 catalog 근거"]))
                : { 1: "확장 질의의 공개 catalog 근거" },
            source: {
                releaseId: "catalog-release-2026-08-10",
                fieldVersion: "catalog-fields-v1",
                reference: "docs/game-catalog/source-manifest.json",
                qualityCorpusReleaseId: "quality-corpus-test",
                qualityCorpusReference: "docs/p2/search-evaluation/quality-corpus.json",
            },
            judgements: [],
        };
    });

    return {
        schemaVersion: 1,
        featureId: "SEARCH-04",
        evaluationProfile: "final-quality",
        status: "draft",
        evaluationVersion: "search-04-final-quality-test-v1",
        qualityCorpusVersion: "quality-corpus-test-v1",
        selectionRuleVersion: "quality-corpus-selection-test-v1",
        corpusSnapshot: {
            snapshotId: "boardlife-ranking-test",
            snapshotVersion: "2026-08-10",
            snapshotSha256: "d".repeat(64),
            fixed: true,
        },
        catalog: {
            releaseId: "catalog-release-2026-08-10",
            datasetId: "bgg-catalog-170k",
            fieldVersion: "catalog-fields-v1",
            manifestReference: "docs/game-catalog/source-manifest.json",
            releaseStatus: "not-registered",
        },
        queriesPath: "queries.json",
        qualityCorpusPath: "quality-corpus.json",
        qualityCorpusSha256: "c".repeat(64),
        qualityCorpus: {
            schemaVersion: 1,
            corpusId: "quality-corpus-test",
            corpusVersion: "quality-corpus-test-v1",
            status: "provisional",
            releaseId: "quality-corpus-test",
            releaseStatus: "provisional",
            rankCutoff: 1000,
            source: {
                releaseId: "quality-corpus-test",
                releaseStatus: "provisional",
                datasetId: "boardlife-quality-top1000",
                sourceReference: "https://boardlife.co.kr/rank",
                sourceManifestReference: "external://quality-corpus-test.json",
                sourceArtifactSha256: "d".repeat(64),
                snapshotId: "boardlife-ranking-test",
                snapshotVersion: "2026-08-10",
                snapshotSha256: "d".repeat(64),
                catalogReleaseId: "catalog-release-2026-08-10",
                mappingStatus: "provisional",
            },
            selection: {
                mode: "fixture-membership-subset",
                note: "테스트용 projection",
                ruleVersion: "quality-corpus-selection-test-v1",
                targetSize: 1000,
                mappingKey: "bggId",
                memberIdField: "gameId",
                order: ["boardlifeRank:asc", "bggId:asc"],
                dedupe: "unique bggId; retain lowest valid boardlife rank",
                cutoffAppliedAfterMapping: true,
                snapshotPinned: true,
                languageExclusionPolicy: "membership is not replaced by missing Korean data",
                memberCount: 99,
            },
            members: Array.from({ length: 99 }, (_, index) => ({
                gameId: index + 1,
                boardlifeRank: index + 1,
                minPlayers: 2,
                maxPlayers: 6,
                maxPlayTimeMinutes: 30,
            })),
        },
        index: {
            corpusVersion: "quality-corpus-test-v1",
            corpusSha256: "c".repeat(64),
            status: "not-registered",
            allowedStates: ["BUILDING", "READY", "FAILED"],
            rollbackPolicy: "retain-previous-ready",
        },
        cohorts: {
            "exact/name variant": { minimum: 15, minDeltaVsBaseline: null },
            "intent/description": { minimum: 25, minDeltaVsBaseline: null },
            "intent+hard filter": { minimum: 20, minDeltaVsBaseline: null },
        },
        quality: {
            hardFilterViolationRate: 0,
            baseline: { status: "pending" },
        },
        judgement: {
            requiredIndependentJudges: 2,
            thirdJudgeRequiredOnDisagreement: true,
            status: "pending",
        },
        queries,
    };
}

function buildQualityReadyManifest() {
    const manifest = buildManifest();
    manifest.status = "quality-ready";
    manifest.catalog.releaseStatus = "approved";
    manifest.qualityCorpus.status = "approved";
    manifest.qualityCorpus.releaseStatus = "approved";
    manifest.catalog.datasetSha256 = "a".repeat(64);
    manifest.catalog.rowCount = 60;
    manifest.queriesSha256 = "b".repeat(64);
    manifest.approvalReferences = ["approval://search-04-test"];
    manifest.judgement.status = "approved";
    manifest.quality.baseline = {
        status: "approved",
        reference: "baseline://search-04-test",
    };
    for (const cohort of Object.keys(manifest.cohorts)) {
        manifest.cohorts[cohort].minDeltaVsBaseline = 0;
    }
    manifest.queries = manifest.queries.map((query) => ({
        ...query,
        labelStatus: "approved",
        judgements: [
            {
                judgeId: "judge-a",
                status: "approved",
                expectedGameIds: query.expectedGameIds,
                excludedGameIds: query.excludedGameIds,
            },
            {
                judgeId: "judge-b",
                status: "approved",
                expectedGameIds: query.expectedGameIds,
                excludedGameIds: query.excludedGameIds,
            },
        ],
    }));
    return manifest;
}

function createQualityReadyFixture() {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "search-evaluation-test-"));
    const manifest = buildQualityReadyManifest();
    const queryBytes = Buffer.from(`${JSON.stringify(manifest.queries, null, 2)}\n`);
    const corpusBytes = Buffer.from(`${JSON.stringify(manifest.qualityCorpus, null, 2)}\n`);
    const manifestPath = path.join(directory, "manifest.json");
    const queriesPath = path.join(directory, "queries.json");
    const corpusPath = path.join(directory, "quality-corpus.json");

    fs.writeFileSync(queriesPath, queryBytes);
    fs.writeFileSync(corpusPath, corpusBytes);
    manifest.queriesSha256 = createHash("sha256").update(queryBytes).digest("hex");
    manifest.qualityCorpusSha256 = createHash("sha256").update(corpusBytes).digest("hex");
    manifest.index.corpusSha256 = manifest.qualityCorpusSha256;
    fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

    return { directory, manifestPath };
}

function withLoadedQualityReadyManifest(callback) {
    const fixture = createQualityReadyFixture();
    try {
        return callback(loadManifest(fixture.manifestPath));
    } finally {
        fs.rmSync(fixture.directory, { recursive: true, force: true });
    }
}
