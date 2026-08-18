import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import test from "node:test";

import {
    calculateRankingMetrics,
    evaluateSearchResults,
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
    manifest.queries[59].cohorts = [];

    assert.throws(
        () => validateEvaluationManifest(manifest),
        /최소 표본|cohort/u,
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

test("판정자 간 불일치에는 제3 판정이 필요하다", () => {
    const manifest = buildQualityReadyManifest();
    manifest.queries[0].judgements[1].expectedGameIds = [999];

    assert.throws(
        () => validateQualityReadiness(manifest),
        /제3 판정/u,
    );
});

test("Recall@10·MRR@10·nDCG@10을 고정된 순위에서 재현한다", () => {
    const metrics = calculateRankingMetrics({
        expectedGameIds: [1, 2, 3],
        rankedGameIds: [9, 2, 8, 1, 7, 3],
        k: 5,
    });

    assert.equal(metrics.recallAt10, 2 / 3);
    assert.equal(metrics.mrrAt10, 1 / 2);
    assert.equal(metrics.ndcgAt10, (1 / Math.log2(3) + 1 / Math.log2(5)) / (1 + 1 / Math.log2(3) + 1 / 2));
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
        catalog: {
            releaseId: "catalog-release-2026-08-10",
            datasetId: "bgg-catalog-170k",
            fieldVersion: "catalog-fields-v1",
            manifestReference: "docs/game-catalog/source-manifest.json",
            releaseStatus: "not-registered",
        },
        qualityCorpusSha256: "c".repeat(64),
        qualityCorpus: {
            schemaVersion: 1,
            corpusId: "quality-corpus-test",
            status: "provisional",
            releaseId: "quality-corpus-test",
            releaseStatus: "provisional",
            rankCutoff: 1000,
            source: {
                datasetId: "boardlife-quality-top1000",
                sourceReference: "https://boardlife.co.kr/rank",
                sourceManifestReference: "external://quality-corpus-test.json",
                sourceArtifactSha256: "d".repeat(64),
            },
            members: Array.from({ length: 99 }, (_, index) => ({
                gameId: index + 1,
                boardlifeRank: index + 1,
                minPlayers: 2,
                maxPlayers: 6,
                maxPlayTimeMinutes: 30,
            })),
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
