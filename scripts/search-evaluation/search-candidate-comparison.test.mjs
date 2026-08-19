import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
    buildComparisonReport,
    buildComparisonJudgementPacket,
    buildEvaluationMetadata,
    buildRrfRanking,
    loadComparisonManifest,
    normalizeCandidateResults,
    packetFromManifest,
    validateCandidateFixtures,
} from "./search-candidate-comparison.mjs";

const QUERY_FIXTURE = [
    {
        id: "Q-010",
        query: "가볍게 웃으면서 즐길 수 있는 게임",
        cohorts: ["intent/description"],
        analysisClass: "semantic-core",
        hardFilters: {},
    },
];

const BASELINE_RESULTS = {
    "Q-010": {
        rankedGameIds: [1, 2, 3],
        hardFilterViolationGameIds: [],
    },
};

const DENSE_RESULTS = {
    schemaVersion: 1,
    kind: "search-04-dense-execution",
    queries: [
        {
            id: "Q-010",
            query: QUERY_FIXTURE[0].query,
            ranked: [
                { rank: 1, gameId: 2, score: 0.9 },
                { rank: 2, gameId: 3, score: 0.8 },
                { rank: 3, gameId: 4, score: 0.7 },
            ],
            hardFilterViolationGameIds: [],
        },
    ],
};

function candidate(name, queries, results) {
    return { name, queries, results };
}

const TEST_CANDIDATES = [
    candidate("lexical", QUERY_FIXTURE, BASELINE_RESULTS),
    candidate("dense", QUERY_FIXTURE, DENSE_RESULTS),
];
const TEST_EVALUATION = buildEvaluationMetadata({ candidates: TEST_CANDIDATES });

function approvedJudgements(
    grades = { "1": 2, "2": 1, "3": 0, "4": 2 },
    evaluation = TEST_EVALUATION,
) {
    return {
        schemaVersion: 1,
        kind: "search-04-search-candidate-qrels",
        status: "approved",
        evaluation,
        queries: [
            {
                id: "Q-010",
                judges: [
                    { judgeId: "judge-a", status: "approved", grades },
                    { judgeId: "judge-b", status: "approved", grades },
                ],
                consensus: { status: "approved", grades },
            },
        ],
    };
}

test("baseline·Dense 결과 형식을 공통 ranked ID 형식으로 정규화한다", () => {
    assert.deepEqual(normalizeCandidateResults(BASELINE_RESULTS, "lexical"), {
        "Q-010": { rankedGameIds: [1, 2, 3], hardFilterViolationGameIds: [] },
    });
    assert.deepEqual(normalizeCandidateResults(DENSE_RESULTS, "dense"), {
        "Q-010": { rankedGameIds: [2, 3, 4], hardFilterViolationGameIds: [] },
    });
});

test("query ID가 같아도 query 문구가 다르면 비교를 중단한다", () => {
    const mismatchedDenseQueries = [{ ...QUERY_FIXTURE[0], query: "다른 질의" }];

    assert.throws(
        () => validateCandidateFixtures({
            candidates: [
                candidate("lexical", QUERY_FIXTURE, BASELINE_RESULTS),
                candidate("dense", mismatchedDenseQueries, DENSE_RESULTS),
            ],
        }),
        /다른 질의/u,
    );
});

test("사람 qrels가 없으면 비교 결과를 pending으로 남기고 방식을 선택하지 않는다", () => {
    const report = buildComparisonReport({
        queries: QUERY_FIXTURE,
        candidates: [
            candidate("lexical", QUERY_FIXTURE, BASELINE_RESULTS),
            candidate("dense", QUERY_FIXTURE, DENSE_RESULTS),
        ],
        judgements: { status: "pending-human-judgement", queries: [] },
    });

    assert.equal(report.status, "pending-human-judgement");
    assert.equal(report.selection.selectedMethod, null);
    assert.match(report.blockingReasons.join(" "), /독립.*qrels|판정/u);
});

test("승인된 consensus qrels로 후보별 graded metrics를 재현한다", () => {
    const report = buildComparisonReport({
        queries: QUERY_FIXTURE,
        candidates: [
            candidate("lexical", QUERY_FIXTURE, BASELINE_RESULTS),
            candidate("dense", QUERY_FIXTURE, DENSE_RESULTS),
        ],
        judgements: approvedJudgements(),
    });

    assert.equal(report.status, "metrics-ready");
    assert.equal(report.metrics.lexical.overall.queryCount, 1);
    assert.equal(report.metrics.lexical.overall.recallAt10, 1 / 2);
    assert.equal(report.metrics.dense.overall.recallAt10, 1 / 2);
    assert.equal(report.selection.selectedMethod, null);
});

test("semantic analysisClass는 metrics와 blind packet에 보존된다", () => {
    const report = buildComparisonReport({
        queries: QUERY_FIXTURE,
        candidates: [
            candidate("lexical", QUERY_FIXTURE, BASELINE_RESULTS),
            candidate("dense", QUERY_FIXTURE, DENSE_RESULTS),
        ],
        judgements: approvedJudgements(),
    });
    assert.equal(report.metrics.lexical.analysisClasses["semantic-core"].queryCount, 1);
    assert.equal(report.metrics.lexical.perQuery[0].analysisClass, "semantic-core");

    const packet = buildComparisonJudgementPacket({
        queries: QUERY_FIXTURE,
        candidates: [
            candidate("lexical", QUERY_FIXTURE, BASELINE_RESULTS),
            candidate("dense", QUERY_FIXTURE, DENSE_RESULTS),
        ],
        searchText: { games: [1, 2, 3, 4].map((gameId) => ({ gameId, searchText: `게임 ${gameId}` })) },
        topK: 3,
    });
    assert.equal(packet.queries[0].analysisClass, "semantic-core");
});

test("candidate Top-K에 대한 qrels가 빠지면 approved 비교를 거절한다", () => {
    const judgements = approvedJudgements({ "1": 2, "2": 1, "3": 0 });

    assert.throws(
        () => buildComparisonReport({
            queries: QUERY_FIXTURE,
            candidates: [
                candidate("lexical", QUERY_FIXTURE, BASELINE_RESULTS),
                candidate("dense", QUERY_FIXTURE, DENSE_RESULTS),
            ],
            judgements,
        }),
        /qrels.*game ID|grade.*4/u,
    );
});

test("packet과 metrics는 동일한 evaluation Top-K pool checksum을 사용한다", () => {
    const queryFixture = [{
        id: "Q-010",
        query: "가볍게 웃으면서 즐길 수 있는 게임",
        cohorts: ["intent/description"],
        analysisClass: "semantic-core",
        hardFilters: {},
    }];
    const candidates = [
        candidate("lexical", queryFixture, {
            "Q-010": { rankedGameIds: Array.from({ length: 25 }, (_, index) => index + 1), hardFilterViolationGameIds: [] },
        }),
        candidate("dense", queryFixture, {
            "Q-010": { rankedGameIds: Array.from({ length: 25 }, (_, index) => index + 26), hardFilterViolationGameIds: [] },
        }),
    ];
    const packet = buildComparisonJudgementPacket({
        queries: queryFixture,
        candidates,
        searchText: { games: Array.from({ length: 50 }, (_, index) => ({ gameId: index + 1, searchText: `게임 ${index + 1}` })) },
    });
    const evaluationGameIds = [
        ...Array.from({ length: 20 }, (_, index) => index + 1),
        ...Array.from({ length: 20 }, (_, index) => index + 26),
    ];
    const grades = Object.fromEntries(evaluationGameIds.map((gameId) => [String(gameId), 0]));
    const report = buildComparisonReport({
        queries: queryFixture,
        candidates,
        judgements: approvedJudgements(grades, packet.evaluation),
    });

    assert.equal(report.status, "metrics-ready");
    assert.deepEqual(report.evaluation, packet.evaluation);
});

test("RRF는 고정 k와 game ID tie-break로 결정적으로 순위를 합친다", () => {
    assert.deepEqual(buildRrfRanking({
        rankedLists: [[1, 2, 3], [2, 3, 4]],
        rrfK: 60,
        topK: 4,
    }), [2, 3, 1, 4]);
});

test("Hybrid/RRF는 명시적으로 요청할 때만 기존 후보 ranked output에 추가된다", () => {
    const report = buildComparisonReport({
        queries: QUERY_FIXTURE,
        candidates: [
            candidate("lexical", QUERY_FIXTURE, BASELINE_RESULTS),
            candidate("dense", QUERY_FIXTURE, DENSE_RESULTS),
        ],
        judgements: approvedJudgements(),
        includeHybrid: true,
    });

    assert.equal(report.hybrid.rule, "rrf");
    assert.equal(report.hybrid.rrfK, 60);
    assert.ok(report.metrics["hybrid-rrf"]);
});

test("사람 판정 packet은 후보 union과 evidence만 담고 모델 순위를 숨긴다", () => {
    const packet = buildComparisonJudgementPacket({
        queries: QUERY_FIXTURE,
        candidates: [
            candidate("lexical", QUERY_FIXTURE, BASELINE_RESULTS),
            candidate("dense", QUERY_FIXTURE, DENSE_RESULTS),
        ],
        searchText: {
            games: [1, 2, 3, 4].map((gameId) => ({ gameId, searchText: `게임 ${gameId}` })),
        },
        topK: 3,
    });

    assert.equal(packet.status, "pending-independent-human-judgement");
    assert.deepEqual(
        packet.queries[0].candidates.map((candidateRow) => candidateRow.gameId).sort((left, right) => left - right),
        [1, 2, 3, 4],
    );
    assert.equal(packet.queries[0].candidates[0].grade, null);
    assert.equal(packet.queries[0].candidates[0].evidenceText.startsWith("게임 "), true);
    assert.equal(Object.hasOwn(packet.queries[0].candidates[0], "score"), false);
});

test("hard-filter 위반 후보는 metrics-ready 비교에서도 quality eligible이 아니다", () => {
    const violatingResults = {
        "Q-010": {
            rankedGameIds: [1, 2, 3],
            hardFilterViolationGameIds: [2],
        },
    };
    const report = buildComparisonReport({
        queries: QUERY_FIXTURE,
        candidates: [
            candidate("lexical", QUERY_FIXTURE, violatingResults),
            candidate("dense", QUERY_FIXTURE, DENSE_RESULTS),
        ],
        judgements: approvedJudgements(),
    });

    assert.equal(report.status, "metrics-ready");
    assert.equal(report.metrics.lexical.overall.hardFilterViolationRate, 1 / 3);
    assert.equal(report.metrics.lexical.overall.qualityEligible, false);
});

test("comparison manifest는 candidate fixture와 결과 checksum을 검증한다", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "search-candidate-comparison-test-"));
    try {
        const queriesPath = path.join(directory, "queries.json");
        const resultsPath = path.join(directory, "results.json");
        const queriesBytes = Buffer.from(`${JSON.stringify(QUERY_FIXTURE)}\n`);
        const resultsBytes = Buffer.from(`${JSON.stringify(BASELINE_RESULTS)}\n`);
        fs.writeFileSync(queriesPath, queriesBytes);
        fs.writeFileSync(resultsPath, resultsBytes);
        const manifestPath = path.join(directory, "manifest.json");
        fs.writeFileSync(manifestPath, `${JSON.stringify({
            schemaVersion: 1,
            kind: "search-04-search-candidate-comparison-input",
            featureId: "SEARCH-04",
            approvalReference: "https://github.com/bamsongi-club/albam-mate/issues/885#issuecomment-test",
            candidates: [{
                name: "lexical",
                queryFixture: {
                    path: "queries.json",
                    sha256: createHash("sha256").update(queriesBytes).digest("hex"),
                },
                results: {
                    path: "results.json",
                    sha256: createHash("sha256").update(resultsBytes).digest("hex"),
                },
            }],
        }, null, 2)}\n`);

        const loaded = loadComparisonManifest(manifestPath);
        assert.deepEqual(loaded.candidates[0].queries, QUERY_FIXTURE);
        assert.deepEqual(loaded.candidates[0].results, BASELINE_RESULTS);

        fs.writeFileSync(resultsPath, `${JSON.stringify({ changed: true })}\n`);
        assert.throws(() => loadComparisonManifest(manifestPath), /checksum/u);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("manifest로 만든 blind packet은 provenance에서 후보 이름을 노출하지 않는다", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "search-candidate-blind-test-"));
    try {
        const queriesBytes = Buffer.from(`${JSON.stringify(QUERY_FIXTURE)}\n`);
        const lexicalBytes = Buffer.from(`${JSON.stringify(BASELINE_RESULTS)}\n`);
        const denseBytes = Buffer.from(`${JSON.stringify(DENSE_RESULTS)}\n`);
        const searchTextBytes = Buffer.from(`${JSON.stringify({ games: [1, 2, 3, 4].map((gameId) => ({ gameId, searchText: `게임 ${gameId}` })) })}\n`);
        fs.writeFileSync(path.join(directory, "queries.json"), queriesBytes);
        fs.writeFileSync(path.join(directory, "lexical.json"), lexicalBytes);
        fs.writeFileSync(path.join(directory, "dense.json"), denseBytes);
        fs.writeFileSync(path.join(directory, "search-text.json"), searchTextBytes);
        const checksum = (bytes) => createHash("sha256").update(bytes).digest("hex");
        const manifest = {
            schemaVersion: 1,
            kind: "search-04-search-candidate-comparison-input",
            featureId: "SEARCH-04",
            approvalReference: "https://github.com/bamsongi-club/albam-mate/issues/885#issuecomment-test",
            searchText: { path: "search-text.json", sha256: checksum(searchTextBytes) },
            candidates: [
                {
                    name: "lexical",
                    queryFixture: { path: "queries.json", sha256: checksum(queriesBytes) },
                    results: { path: "lexical.json", sha256: checksum(lexicalBytes) },
                },
                {
                    name: "dense",
                    queryFixture: { path: "queries.json", sha256: checksum(queriesBytes) },
                    results: { path: "dense.json", sha256: checksum(denseBytes) },
                },
            ],
        };
        const manifestPath = path.join(directory, "manifest.json");
        fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);

        const packet = packetFromManifest({ manifestPath, topK: 3 });
        assert.equal(Object.hasOwn(packet, "provenance"), true);
        assert.equal(Object.hasOwn(packet.provenance, "candidates"), false);
        assert.equal(JSON.stringify(packet).includes("lexical"), false);
        assert.equal(JSON.stringify(packet).includes("dense"), false);
        assert.deepEqual(packet.provenance.searchText, { sha256: checksum(searchTextBytes) });
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});
