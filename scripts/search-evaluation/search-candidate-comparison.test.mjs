import assert from "node:assert/strict";
import { execFileSync } from "node:child_process";
import { createHash } from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import test from "node:test";

import {
    buildComparisonReport,
    buildComparisonJudgementPacket,
    buildApprovedHumanQrels,
    buildProvisionalAiAdjudicationQrels,
    buildEvaluationMetadata,
    buildRrfRanking,
    compareFromManifest,
    loadComparisonManifest,
    normalizeCandidateResults,
    packetFromManifest,
    validateCandidateFixtures,
    writeJsonAtomically,
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

function rationalesFor(grades, prefix = "판정") {
    return Object.fromEntries(Object.keys(grades).map((gameId) => [gameId, `${prefix}: ${gameId}`]));
}

function approvedJudgements(
    grades = { "1": 2, "2": 1, "3": 0, "4": 2 },
    evaluation = TEST_EVALUATION,
) {
    return {
        schemaVersion: 1,
        kind: "search-04-search-candidate-qrels",
        status: "approved",
        packetSha256: "a".repeat(64),
        evaluation,
        queries: [
            {
                id: "Q-010",
                judges: [
                    { judgeId: "judge-a", status: "approved", grades, rationales: rationalesFor(grades, "A") },
                    { judgeId: "judge-b", status: "approved", grades, rationales: rationalesFor(grades, "B") },
                ],
                consensus: { status: "approved", method: "independent-agreement", grades },
            },
        ],
    };
}

function judgementPacket() {
    return buildComparisonJudgementPacket({
        queries: QUERY_FIXTURE,
        candidates: TEST_CANDIDATES,
        searchText: { games: [1, 2, 3, 4].map((gameId) => ({ gameId, searchText: `게임 ${gameId}` })) },
        topK: 3,
    });
}

function filledJudgementPacket(packet, grades, rationalePrefix) {
    const copy = JSON.parse(JSON.stringify(packet));
    copy.status = "filled";
    for (const query of copy.queries) {
        for (const candidate of query.candidates) {
            candidate.grade = grades[String(candidate.gameId)];
            candidate.rationale = `${rationalePrefix}: ${candidate.gameId}`;
        }
    }
    return copy;
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

test("approved qrels는 packet checksum이 없거나 canonical checksum과 다르면 거절한다", () => {
    const missingChecksum = approvedJudgements();
    delete missingChecksum.packetSha256;
    assert.throws(
        () => buildComparisonReport({
            queries: QUERY_FIXTURE,
            candidates: TEST_CANDIDATES,
            judgements: missingChecksum,
        }),
        /packetSha256가 없습니다/u,
    );

    assert.throws(
        () => buildComparisonReport({
            queries: QUERY_FIXTURE,
            candidates: TEST_CANDIDATES,
            judgements: approvedJudgements(),
            judgementPacketSha256: "b".repeat(64),
        }),
        /canonical packet과 다릅니다/u,
    );
});

test("approved qrels는 판정 근거가 없으면 거절한다", () => {
    const missingRationale = approvedJudgements();
    missingRationale.queries[0].judges[0].rationales["1"] = "";
    assert.throws(
        () => buildComparisonReport({
            queries: QUERY_FIXTURE,
            candidates: TEST_CANDIDATES,
            judgements: missingRationale,
        }),
        /판정 근거가 없습니다/u,
    );
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

test("독립 판정 packet 두 개의 일치 결과를 approved qrels로 보존한다", () => {
    const packet = judgementPacket();
    const grades = { "1": 2, "2": 1, "3": 0, "4": 2 };
    const qrels = buildApprovedHumanQrels({
        packet,
        judgePackets: [
            filledJudgementPacket(packet, grades, "A"),
            filledJudgementPacket(packet, grades, "B"),
        ],
        packetSha256: "a".repeat(64),
    });

    assert.equal(qrels.kind, "search-04-search-candidate-qrels");
    assert.equal(qrels.status, "approved");
    assert.equal(qrels.queries[0].judges.length, 2);
    assert.equal(qrels.queries[0].consensus.method, "independent-agreement");
    assert.deepEqual(qrels.queries[0].consensus.grades, grades);
});

test("판정 불일치는 제3 판정의 다수결과 근거를 함께 보존한다", () => {
    const packet = judgementPacket();
    const firstGrades = { "1": 2, "2": 1, "3": 0, "4": 2 };
    const secondGrades = { "1": 1, "2": 1, "3": 0, "4": 2 };
    const thirdGrades = { "1": 1, "2": 1, "3": 0, "4": 2 };
    const thirdPacket = filledJudgementPacket(packet, thirdGrades, "C");
    for (const candidate of thirdPacket.queries[0].candidates) {
        if (candidate.gameId !== 1) {
            candidate.grade = null;
            candidate.rationale = null;
        }
    }
    thirdPacket.queries[0].candidates = thirdPacket.queries[0].candidates
        .filter((candidate) => candidate.gameId === 1);
    const qrels = buildApprovedHumanQrels({
        packet,
        judgePackets: [
            filledJudgementPacket(packet, firstGrades, "A"),
            filledJudgementPacket(packet, secondGrades, "B"),
        ],
        thirdJudgePacket: thirdPacket,
        judgeIds: ["judge-a", "judge-b", "judge-c"],
        packetSha256: "a".repeat(64),
    });

    assert.equal(qrels.queries[0].judges.length, 3);
    assert.equal(qrels.queries[0].consensus.method, "third-judge-majority");
    assert.equal(qrels.queries[0].consensus.grades["1"], 1);
    assert.deepEqual(Object.keys(qrels.queries[0].judges[2].grades), ["1"]);
    assert.equal(qrels.queries[0].judges[2].rationales["1"], "C: 1");
    const report = buildComparisonReport({
        queries: QUERY_FIXTURE,
        candidates: TEST_CANDIDATES,
        judgements: qrels,
        evaluationTopK: 3,
    });
    assert.equal(report.status, "metrics-ready");

    const mismatchedRationaleKeys = JSON.parse(JSON.stringify(qrels));
    mismatchedRationaleKeys.queries[0].judges[2].rationales = { "2": "C: 2" };
    assert.throws(
        () => buildComparisonReport({
            queries: QUERY_FIXTURE,
            candidates: TEST_CANDIDATES,
            judgements: mismatchedRationaleKeys,
            evaluationTopK: 3,
        }),
        /grade\/rationale candidate 대상/u,
    );
});

test("AI C adjudication은 3-way 충돌을 provisional consensus로 보존한다", () => {
    const packet = judgementPacket();
    const firstGrades = { "1": 2, "2": 1, "3": 0, "4": 2 };
    const secondGrades = { "1": 1, "2": 1, "3": 0, "4": 2 };
    const thirdGrades = { "1": 0, "2": 1, "3": 0, "4": 2 };
    const thirdPacket = filledJudgementPacket(packet, thirdGrades, "C");
    thirdPacket.status = "filled-ai-drafted-not-independent-human";
    for (const candidate of thirdPacket.queries[0].candidates) {
        if (candidate.gameId !== 1) {
            candidate.grade = null;
            candidate.rationale = null;
        }
    }

    const qrels = buildProvisionalAiAdjudicationQrels({
        packet,
        judgePackets: [
            filledJudgementPacket(packet, firstGrades, "A"),
            filledJudgementPacket(packet, secondGrades, "B"),
        ],
        thirdJudgePacket: thirdPacket,
        thirdJudgeSource: "test/semantic-30-judge-c.json",
        packetSha256: "a".repeat(64),
    });

    assert.equal(qrels.status, "provisional-ai-adjudication");
    assert.equal(qrels.provenance.thirdJudgeSource, "test/semantic-30-judge-c.json");
    assert.equal(qrels.provenance.independentThirdJudge, false);
    assert.equal(qrels.provenance.threeWayDisagreementCount, 1);
    assert.equal(qrels.queries[0].consensus.method, "third-judge-adjudication");
    assert.equal(qrels.queries[0].consensus.grades["1"], 0);

    const unmarkedQrels = JSON.parse(JSON.stringify(qrels));
    delete unmarkedQrels.provenance.independentThirdJudge;
    assert.throws(
        () => buildComparisonReport({
            queries: QUERY_FIXTURE,
            candidates: TEST_CANDIDATES,
            judgements: unmarkedQrels,
            evaluationTopK: 3,
            allowProvisionalAiAdjudication: true,
        }),
        /independentThirdJudge=false provenance/u,
    );

    const pendingReport = buildComparisonReport({
        queries: QUERY_FIXTURE,
        candidates: TEST_CANDIDATES,
        judgements: qrels,
        evaluationTopK: 3,
    });
    assert.equal(pendingReport.status, "pending-human-judgement");

    const provisionalReport = buildComparisonReport({
        queries: QUERY_FIXTURE,
        candidates: TEST_CANDIDATES,
        judgements: qrels,
        evaluationTopK: 3,
        allowProvisionalAiAdjudication: true,
    });
    assert.equal(provisionalReport.status, "provisional-metrics-ready");
    assert.equal(provisionalReport.metrics.lexical.overall.queryCount, 1);
});

test("approved qrels는 AI-drafted 제3 판정 packet을 거부한다", () => {
    const packet = judgementPacket();
    const firstGrades = { "1": 2, "2": 1, "3": 0, "4": 2 };
    const secondGrades = { "1": 1, "2": 1, "3": 0, "4": 2 };
    const thirdPacket = filledJudgementPacket(packet, { "1": 1, "2": 1, "3": 0, "4": 2 }, "C");
    thirdPacket.status = "filled-ai-drafted-not-independent-human";
    for (const candidate of thirdPacket.queries[0].candidates) {
        if (candidate.gameId !== 1) {
            candidate.grade = null;
            candidate.rationale = null;
        }
    }

    assert.throws(
        () => buildApprovedHumanQrels({
            packet,
            judgePackets: [
                filledJudgementPacket(packet, firstGrades, "A"),
                filledJudgementPacket(packet, secondGrades, "B"),
            ],
            thirdJudgePacket: thirdPacket,
            judgeIds: ["judge-a", "judge-b", "judge-c"],
            packetSha256: "a".repeat(64),
        }),
        /독립 human 판정 packet이 아닙니다/u,
    );
});

test("provisional qrels는 AI-drafted 제3 판정 packet만 허용한다", () => {
    const packet = judgementPacket();
    const thirdPacket = filledJudgementPacket(packet, { "1": 1, "2": 1, "3": 0, "4": 2 }, "C");
    for (const candidate of thirdPacket.queries[0].candidates) {
        if (candidate.gameId !== 1) {
            candidate.grade = null;
            candidate.rationale = null;
        }
    }

    assert.throws(
        () => buildProvisionalAiAdjudicationQrels({
            packet,
            judgePackets: [
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "A"),
                filledJudgementPacket(packet, { "1": 1, "2": 1, "3": 0, "4": 2 }, "B"),
            ],
            thirdJudgePacket: thirdPacket,
            thirdJudgeSource: "test/semantic-30-judge-c.json",
            packetSha256: "a".repeat(64),
        }),
        /AI-drafted provisional 판정 packet이 아닙니다/u,
    );
});

test("canonical packet은 pending 상태와 빈 판정 필드를 강제한다", () => {
    const packet = judgementPacket();
    const filled = filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "A");

    assert.throws(
        () => buildApprovedHumanQrels({
            packet: filled,
            judgePackets: [
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "A"),
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "B"),
            ],
            packetSha256: "a".repeat(64),
        }),
        /pending-independent-human-judgement/u,
    );

    const partiallyFilled = JSON.parse(JSON.stringify(packet));
    partiallyFilled.queries[0].candidates[0].grade = 2;
    partiallyFilled.queries[0].candidates[0].rationale = "노출된 판정";
    assert.throws(
        () => buildApprovedHumanQrels({
            packet: partiallyFilled,
            judgePackets: [
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "A"),
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "B"),
            ],
            packetSha256: "a".repeat(64),
        }),
        /grade·rationale이 비어 있어야/u,
    );
});

test("판정 불일치에 제3 판정이 없으면 qrels 승인을 거부한다", () => {
    const packet = judgementPacket();
    assert.throws(
        () => buildApprovedHumanQrels({
            packet,
            judgePackets: [
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "A"),
                filledJudgementPacket(packet, { "1": 1, "2": 1, "3": 0, "4": 2 }, "B"),
            ],
            packetSha256: "a".repeat(64),
        }),
        /제3 판정/u,
    );
});

test("판정 불일치가 없으면 제3 판정 packet의 candidate 입력을 거부한다", () => {
    const packet = judgementPacket();
    const grades = { "1": 2, "2": 1, "3": 0, "4": 2 };

    assert.throws(
        () => buildApprovedHumanQrels({
            packet,
            judgePackets: [
                filledJudgementPacket(packet, grades, "A"),
                filledJudgementPacket(packet, grades, "B"),
            ],
            thirdJudgePacket: filledJudgementPacket(packet, grades, "C"),
            judgeIds: ["judge-a", "judge-b", "judge-c"],
            packetSha256: "a".repeat(64),
        }),
        /evaluation candidate pool 밖의 game ID/u,
    );
});

test("approved qrels는 판정 결과와 consensus method가 일치해야 한다", () => {
    const qrels = approvedJudgements();
    qrels.queries[0].judges.push({
        judgeId: "judge-c",
        status: "approved",
        grades: {},
        rationales: {},
    });

    assert.throws(
        () => buildComparisonReport({
            queries: QUERY_FIXTURE,
            candidates: TEST_CANDIDATES,
            judgements: qrels,
        }),
        /일치 판정은 2인/u,
    );
});

test("판정 packet의 query·evidence가 바뀌면 qrels 승인을 거부한다", () => {
    const packet = judgementPacket();
    const changed = filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "A");
    changed.queries[0].candidates[0].evidenceText = "변경된 evidence";

    assert.throws(
        () => buildApprovedHumanQrels({
            packet,
            judgePackets: [
                changed,
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "B"),
            ],
            packetSha256: "a".repeat(64),
        }),
        /canonical packet과 다릅니다/u,
    );
});

test("판정 packet에 ranking 단서를 추가하면 qrels 승인을 거부한다", () => {
    const packet = judgementPacket();
    const changed = filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "A");
    changed.queries[0].candidates[0].score = 0.99;

    assert.throws(
        () => buildApprovedHumanQrels({
            packet,
            judgePackets: [
                changed,
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "B"),
            ],
            packetSha256: "a".repeat(64),
        }),
        /허용되지 않은 필드/u,
    );
});

test("판정 packet의 중첩 metadata에 ranking 단서를 추가하면 승인을 거부한다", () => {
    const packet = judgementPacket();
    const changed = filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "A");
    changed.evaluation.score = 0.99;

    assert.throws(
        () => buildApprovedHumanQrels({
            packet,
            judgePackets: [
                changed,
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "B"),
            ],
            packetSha256: "a".repeat(64),
        }),
        /evaluation 필드가 canonical schema와 다릅니다/u,
    );
});

test("판정 query에 ranking 단서를 추가하면 qrels 승인을 거부한다", () => {
    const packet = judgementPacket();
    const changed = filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "A");
    changed.queries[0].sourceRank = { "1": 1 };

    assert.throws(
        () => buildApprovedHumanQrels({
            packet,
            judgePackets: [
                changed,
                filledJudgementPacket(packet, { "1": 2, "2": 1, "3": 0, "4": 2 }, "B"),
            ],
            packetSha256: "a".repeat(64),
        }),
        /허용되지 않은 필드/u,
    );
});

test("주 판정자 qrels는 candidate 누락을 허용하지 않는다", () => {
    assert.throws(
        () => buildComparisonReport({
            queries: QUERY_FIXTURE,
            candidates: TEST_CANDIDATES,
            judgements: {
                ...approvedJudgements(),
                queries: [{
                    id: "Q-010",
                    judges: [
                        {
                            judgeId: "judge-a",
                            status: "approved",
                            grades: { "2": 1, "3": 0, "4": 2 },
                            rationales: rationalesFor({ "2": 1, "3": 0, "4": 2 }, "A"),
                        },
                        {
                            judgeId: "judge-b",
                            status: "approved",
                            grades: { "1": 2, "2": 1, "3": 0, "4": 2 },
                            rationales: rationalesFor({ "1": 2, "2": 1, "3": 0, "4": 2 }, "B"),
                        },
                        {
                            judgeId: "judge-c",
                            status: "approved",
                            grades: { "1": 2, "2": 1, "3": 0, "4": 2 },
                            rationales: rationalesFor({ "1": 2, "2": 1, "3": 0, "4": 2 }, "C"),
                        },
                    ],
                    consensus: { status: "approved", grades: { "1": 2, "2": 1, "3": 0, "4": 2 } },
                }],
            },
        }),
        /candidate 결과 game ID가 없습니다/u,
    );
});

test("CLI는 두 판정 packet을 checksum 고정 qrels로 조립한다", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "search-candidate-qrels-test-"));
    try {
        const packet = judgementPacket();
        const grades = { "1": 2, "2": 1, "3": 0, "4": 2 };
        const checksum = (bytes) => createHash("sha256").update(bytes).digest("hex");
        const writeJson = (name, value) => {
            const filePath = path.join(directory, name);
            const bytes = Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
            fs.writeFileSync(filePath, bytes);
            return { filePath, descriptor: { path: name, sha256: checksum(bytes) } };
        };
        const packetArtifact = writeJson("packet.json", packet);
        const judgeAArtifact = writeJson("judge-a.json", filledJudgementPacket(packet, grades, "A"));
        const judgeBArtifact = writeJson("judge-b.json", filledJudgementPacket(packet, grades, "B"));
        const queryArtifact = writeJson("queries.json", QUERY_FIXTURE);
        const lexicalArtifact = writeJson("lexical.json", BASELINE_RESULTS);
        const denseArtifact = writeJson("dense.json", DENSE_RESULTS);
        const manifestPath = path.join(directory, "manifest.json");
        const outputPath = path.join(directory, "qrels.json");
        fs.writeFileSync(manifestPath, `${JSON.stringify({
            schemaVersion: 1,
            kind: "search-04-search-candidate-comparison-input",
            featureId: "SEARCH-04",
            approvalReference: "https://github.com/bamsongi-club/albam-mate/issues/897",
            evaluationTopK: 3,
            judgementPacket: packetArtifact.descriptor,
            candidates: [
                { name: "lexical", queryFixture: queryArtifact.descriptor, results: lexicalArtifact.descriptor },
                { name: "dense", queryFixture: queryArtifact.descriptor, results: denseArtifact.descriptor },
            ],
        }, null, 2)}\n`);

        const stdout = execFileSync(process.execPath, [
            path.resolve("scripts/search-evaluation/search-candidate-comparison.mjs"),
            "--qrels",
            "--manifest", manifestPath,
            "--canonical-packet", packetArtifact.filePath,
            "--judge-a", judgeAArtifact.filePath,
            "--judge-b", judgeBArtifact.filePath,
            "--out", outputPath,
        ], { encoding: "utf8" });

        assert.match(stdout, /"status": "approved"/u);
        const qrels = JSON.parse(fs.readFileSync(outputPath, "utf8"));
        assert.equal(qrels.status, "approved");
        assert.match(qrels.packetSha256, /^[a-f0-9]{64}$/u);
        assert.equal(qrels.provenance.schemaVersion, 1);
        assert.deepEqual(
            qrels.provenance.judgePackets.map(({ judgeId, independentHuman }) => ({ judgeId, independentHuman })),
            [
                { judgeId: "judge-a", independentHuman: true },
                { judgeId: "judge-b", independentHuman: true },
            ],
        );

        const report = compareFromManifest({ manifestPath, judgementsPath: outputPath });
        assert.equal(report.status, "metrics-ready");
        assert.deepEqual(report.provenance.judgements, {
            path: "qrels.json",
            sha256: checksum(fs.readFileSync(outputPath)),
        });

        const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
        const manifestQrelsPath = path.join(directory, "manifest-qrels.json");
        fs.copyFileSync(outputPath, manifestQrelsPath);
        const manifestQrelsBytes = fs.readFileSync(manifestQrelsPath);
        manifest.judgements = { path: "manifest-qrels.json", sha256: checksum(manifestQrelsBytes) };
        fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
        const overridePath = path.join(directory, "override-qrels.json");
        fs.copyFileSync(outputPath, overridePath);
        const overrideReport = compareFromManifest({ manifestPath, judgementsPath: overridePath });
        assert.deepEqual(overrideReport.provenance.judgements, {
            path: "override-qrels.json",
            sha256: checksum(fs.readFileSync(overridePath)),
        });

        const untrustedOutputPath = path.join(directory, "untrusted-qrels.json");
        const untrustedQrels = { ...qrels };
        delete untrustedQrels.provenance;
        fs.writeFileSync(untrustedOutputPath, `${JSON.stringify(untrustedQrels)}\n`);
        assert.throws(
            () => compareFromManifest({ manifestPath, judgementsPath: untrustedOutputPath }),
            /approved human qrels override provenance/u,
        );

        const duplicateSourceQrels = JSON.parse(fs.readFileSync(outputPath, "utf8"));
        duplicateSourceQrels.provenance.judgePackets[1] = {
            ...duplicateSourceQrels.provenance.judgePackets[1],
            path: duplicateSourceQrels.provenance.judgePackets[0].path,
            sha256: duplicateSourceQrels.provenance.judgePackets[0].sha256,
        };
        const duplicateSourcePath = path.join(directory, "duplicate-source-qrels.json");
        fs.writeFileSync(duplicateSourcePath, `${JSON.stringify(duplicateSourceQrels)}\n`);
        assert.throws(
            () => compareFromManifest({ manifestPath, judgementsPath: duplicateSourcePath }),
            /서로 다른 실제 파일이어야/u,
        );

        const samePathOutput = path.join(directory, "same-path.json");
        assert.throws(
            () => execFileSync(process.execPath, [
                path.resolve("scripts/search-evaluation/search-candidate-comparison.mjs"),
                "--qrels",
                "--manifest", manifestPath,
                "--canonical-packet", packetArtifact.filePath,
                "--judge-a", judgeAArtifact.filePath,
                "--judge-b", judgeAArtifact.filePath,
                "--out", samePathOutput,
            ], { encoding: "utf8" }),
            (error) => error.status === 1 && error.stderr.includes("서로 다른 실제 파일이어야"),
        );

        const symlinkPath = path.join(directory, "judge-a-symlink.json");
        fs.symlinkSync(judgeAArtifact.filePath, symlinkPath);
        const symlinkOutput = path.join(directory, "symlink.json");
        assert.throws(
            () => execFileSync(process.execPath, [
                path.resolve("scripts/search-evaluation/search-candidate-comparison.mjs"),
                "--qrels",
                "--manifest", manifestPath,
                "--canonical-packet", packetArtifact.filePath,
                "--judge-a", judgeAArtifact.filePath,
                "--judge-b", symlinkPath,
                "--out", symlinkOutput,
            ], { encoding: "utf8" }),
            (error) => error.status === 1 && error.stderr.includes("서로 다른 실제 파일이어야"),
        );

        const hardlinkPath = path.join(directory, "judge-a-hardlink.json");
        fs.linkSync(judgeAArtifact.filePath, hardlinkPath);
        const hardlinkOutput = path.join(directory, "hardlink.json");
        assert.throws(
            () => execFileSync(process.execPath, [
                path.resolve("scripts/search-evaluation/search-candidate-comparison.mjs"),
                "--qrels",
                "--manifest", manifestPath,
                "--canonical-packet", packetArtifact.filePath,
                "--judge-a", judgeAArtifact.filePath,
                "--judge-b", hardlinkPath,
                "--out", hardlinkOutput,
            ], { encoding: "utf8" }),
            (error) => error.status === 1 && error.stderr.includes("서로 다른 실제 파일이어야"),
        );

        assert.throws(
            () => execFileSync(process.execPath, [
                path.resolve("scripts/search-evaluation/search-candidate-comparison.mjs"),
                "--qrels",
                "--manifest", manifestPath,
                "--canonical-packet", judgeAArtifact.filePath,
                "--judge-a", judgeAArtifact.filePath,
                "--judge-b", judgeBArtifact.filePath,
                "--out", path.join(directory, "mismatch.json"),
            ], { encoding: "utf8" }),
            (error) => error.status === 1 && error.stderr.includes("path와 같아야"),
        );
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("provisional qrels는 manifest와 외부 override 모두 source provenance로 재생성 검증한다", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "search-candidate-provisional-qrels-test-"));
    try {
        const packet = judgementPacket();
        const checksum = (bytes) => createHash("sha256").update(bytes).digest("hex");
        const writeJson = (name, value) => {
            const filePath = path.join(directory, name);
            const bytes = Buffer.from(`${JSON.stringify(value, null, 2)}\n`);
            fs.writeFileSync(filePath, bytes);
            return { filePath, descriptor: { path: name, sha256: checksum(bytes) } };
        };
        const packetArtifact = writeJson("packet.json", packet);
        const judgeAArtifact = writeJson("judge-a.json", filledJudgementPacket(
            packet,
            { "1": 2, "2": 1, "3": 0, "4": 2 },
            "A",
        ));
        const judgeBArtifact = writeJson("judge-b.json", filledJudgementPacket(
            packet,
            { "1": 1, "2": 1, "3": 0, "4": 2 },
            "B",
        ));
        const judgeCPacket = filledJudgementPacket(
            packet,
            { "1": 0, "2": 1, "3": 0, "4": 2 },
            "C",
        );
        judgeCPacket.status = "filled-ai-drafted-not-independent-human";
        for (const candidateRow of judgeCPacket.queries[0].candidates) {
            if (candidateRow.gameId !== 1) {
                candidateRow.grade = null;
                candidateRow.rationale = null;
            }
        }
        const judgeCArtifact = writeJson("judge-c.json", judgeCPacket);
        const queryArtifact = writeJson("queries.json", QUERY_FIXTURE);
        const lexicalArtifact = writeJson("lexical.json", BASELINE_RESULTS);
        const denseArtifact = writeJson("dense.json", DENSE_RESULTS);
        const manifestPath = path.join(directory, "manifest.json");
        const manifest = {
            schemaVersion: 1,
            kind: "search-04-search-candidate-comparison-input",
            featureId: "SEARCH-04",
            approvalReference: "https://github.com/bamsongi-club/albam-mate/issues/897",
            evaluationTopK: 3,
            judgementPacket: packetArtifact.descriptor,
            candidates: [
                { name: "lexical", queryFixture: queryArtifact.descriptor, results: lexicalArtifact.descriptor },
                { name: "dense", queryFixture: queryArtifact.descriptor, results: denseArtifact.descriptor },
            ],
        };
        const writeManifest = () => fs.writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
        writeManifest();

        const qrelsPath = path.join(directory, "qrels.json");
        execFileSync(process.execPath, [
            path.resolve("scripts/search-evaluation/search-candidate-comparison.mjs"),
            "--qrels",
            "--provisional-ai-adjudication",
            "--manifest", manifestPath,
            "--canonical-packet", packetArtifact.filePath,
            "--judge-a", judgeAArtifact.filePath,
            "--judge-b", judgeBArtifact.filePath,
            "--judge-c", judgeCArtifact.filePath,
            "--judge-c-id", "judge-c-ai-drafted",
            "--out", qrelsPath,
        ], { encoding: "utf8" });
        const qrelsBytes = fs.readFileSync(qrelsPath);
        manifest.judgements = { path: "qrels.json", sha256: checksum(qrelsBytes) };
        writeManifest();

        const manifestReport = compareFromManifest({
            manifestPath,
            allowProvisionalAiAdjudication: true,
        });
        assert.equal(manifestReport.status, "provisional-metrics-ready");

        const overridePath = path.join(directory, "override.json");
        fs.copyFileSync(qrelsPath, overridePath);
        const overrideReport = compareFromManifest({
            manifestPath,
            judgementsPath: overridePath,
            allowProvisionalAiAdjudication: true,
        });
        assert.equal(overrideReport.status, "provisional-metrics-ready");

        const untrustedQrels = JSON.parse(qrelsBytes);
        delete untrustedQrels.provenance;
        const untrustedPath = path.join(directory, "untrusted.json");
        fs.writeFileSync(untrustedPath, `${JSON.stringify(untrustedQrels)}\n`);
        assert.throws(
            () => compareFromManifest({
                manifestPath,
                judgementsPath: untrustedPath,
                allowProvisionalAiAdjudication: true,
            }),
            /provisional AI qrels provenance/u,
        );

        const tamperedQrels = JSON.parse(qrelsBytes);
        tamperedQrels.queries[0].consensus.grades["1"] = 2;
        const tamperedPath = path.join(directory, "tampered.json");
        fs.writeFileSync(tamperedPath, `${JSON.stringify(tamperedQrels)}\n`);
        assert.throws(
            () => compareFromManifest({
                manifestPath,
                judgementsPath: tamperedPath,
                allowProvisionalAiAdjudication: true,
            }),
            /source packet으로 재생성한 결과/u,
        );
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("원자적 JSON 출력은 write·rename 실패에서 기존 결과와 임시 파일을 보존한다", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "search-candidate-atomic-test-"));
    const outputPath = path.join(directory, "results.json");
    fs.writeFileSync(outputPath, "previous\n");
    try {
        assert.throws(
            () => writeJsonAtomically(outputPath, "next\n", {
                randomId: () => "write-failure",
                writeFile: (fileDescriptor) => {
                    fs.writeSync(fileDescriptor, "partial");
                    throw new Error("write failed");
                },
            }),
            /write failed/u,
        );
        assert.equal(fs.readFileSync(outputPath, "utf8"), "previous\n");
        assert.equal(fs.existsSync(path.join(directory, ".results.json.write-failure.tmp")), false);

        assert.throws(
            () => writeJsonAtomically(outputPath, "next\n", {
                randomId: () => "rename-failure",
                publish: () => { throw new Error("publish failed"); },
            }),
            /publish failed/u,
        );
        assert.equal(fs.readFileSync(outputPath, "utf8"), "previous\n");
        assert.equal(fs.existsSync(path.join(directory, ".results.json.rename-failure.tmp")), false);

        const collisionPath = path.join(directory, ".results.json.collision.tmp");
        fs.writeFileSync(collisionPath, "another writer\n");
        assert.throws(
            () => writeJsonAtomically(outputPath, "next\n", { randomId: () => "collision" }),
            (error) => error.code === "EEXIST",
        );
        assert.equal(fs.readFileSync(outputPath, "utf8"), "previous\n");
        assert.equal(fs.readFileSync(collisionPath, "utf8"), "another writer\n");

        assert.throws(
            () => writeJsonAtomically(outputPath, "next\n", { randomId: () => "no-replace" }),
            (error) => error.code === "EEXIST",
        );
        assert.equal(fs.readFileSync(outputPath, "utf8"), "previous\n");
        assert.equal(fs.existsSync(path.join(directory, ".results.json.no-replace.tmp")), false);

        const cleanupOutputPath = path.join(directory, "cleanup.json");
        let cleanupAttempts = 0;
        assert.doesNotThrow(() => writeJsonAtomically(cleanupOutputPath, "published\n", {
            randomId: () => "cleanup-failure",
            unlink: () => {
                cleanupAttempts += 1;
                throw new Error("cleanup failed");
            },
        }));
        assert.equal(fs.readFileSync(cleanupOutputPath, "utf8"), "published\n");
        assert.equal(cleanupAttempts, 1);
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});

test("approved qrels는 comparison manifest의 canonical packet descriptor 없이는 사용할 수 없다", () => {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), "search-candidate-manifest-qrels-test-"));
    try {
        const queriesBytes = Buffer.from(`${JSON.stringify(QUERY_FIXTURE)}\n`);
        const lexicalBytes = Buffer.from(`${JSON.stringify(BASELINE_RESULTS)}\n`);
        const denseBytes = Buffer.from(`${JSON.stringify(DENSE_RESULTS)}\n`);
        const qrelsBytes = Buffer.from(`${JSON.stringify(approvedJudgements(
            { "1": 2, "2": 1, "3": 0, "4": 2 },
            buildEvaluationMetadata({ candidates: TEST_CANDIDATES, topK: 3 }),
        ))}\n`);
        const checksum = (bytes) => createHash("sha256").update(bytes).digest("hex");
        const write = (name, bytes) => {
            const filePath = path.join(directory, name);
            fs.writeFileSync(filePath, bytes);
            return { path: name, sha256: checksum(bytes) };
        };
        const queryDescriptor = write("queries.json", queriesBytes);
        const lexicalDescriptor = write("lexical.json", lexicalBytes);
        const denseDescriptor = write("dense.json", denseBytes);
        const qrelsDescriptor = write("qrels.json", qrelsBytes);
        const manifestPath = path.join(directory, "manifest.json");
        fs.writeFileSync(manifestPath, `${JSON.stringify({
            schemaVersion: 1,
            kind: "search-04-search-candidate-comparison-input",
            featureId: "SEARCH-04",
            approvalReference: "https://github.com/bamsongi-club/albam-mate/issues/897",
            evaluationTopK: 3,
            judgements: qrelsDescriptor,
            candidates: [
                { name: "lexical", queryFixture: queryDescriptor, results: lexicalDescriptor },
                { name: "dense", queryFixture: queryDescriptor, results: denseDescriptor },
            ],
        }, null, 2)}\n`);

        assert.throws(
            () => compareFromManifest({ manifestPath }),
            /canonical judgementPacket descriptor가 필요합니다/u,
        );
    } finally {
        fs.rmSync(directory, { recursive: true, force: true });
    }
});
