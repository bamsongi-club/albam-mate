import assert from "node:assert/strict";
import test from "node:test";

import { buildGoldJudgementPacket } from "./build-gold-judgement-packet.mjs";

test("gold packet은 후보 설명을 붙이고 모델 provenance를 숨긴다", () => {
    const packet = buildGoldJudgementPacket({
        blind: buildBlindFixture(),
        searchText: buildSearchTextFixture(),
    });

    assert.equal(packet.status, "pending-independent-human-judgement");
    assert.equal(packet.queries[0].candidates[0].evidenceText, "게임 1");
    assert.equal(packet.queries[0].candidates[0].grade, null);
    assert.equal(packet.queries[0].candidates[0].rationale, null);
    for (const field of ["modelId", "score", "sourceRank", "sourceModel"]) {
        assert.equal(Object.hasOwn(packet.queries[0].candidates[0], field), false);
    }
});

test("gold packet은 Top 1,000 밖 후보를 거절한다", () => {
    const blind = buildBlindFixture();
    blind.queries[0].candidates[0].gameId = 1001;
    assert.throws(
        () => buildGoldJudgementPacket({
            blind,
            searchText: buildSearchTextFixture(),
        }),
        /searchText에 없습니다/u,
    );
});

function buildBlindFixture() {
    return {
        schemaVersion: 1,
        kind: "search-04-blind-judgement",
        gradeScale: { relevant: 2, borderline: 1, irrelevant: 0 },
        queries: ["Q-010", "Q-011", "Q-012"].map((id, queryIndex) => ({
            id,
            query: id,
            judgementRubric: { relevant: "웃음", borderline: "근거 약함", irrelevant: "무관" },
            candidates: Array.from({ length: 20 }, (_, index) => ({
                gameId: queryIndex * 20 + index + 1,
                name: "게임",
                englishName: "Game",
                grade: null,
            })),
        })),
    };
}

function buildSearchTextFixture() {
    return {
        games: Array.from({ length: 1000 }, (_, index) => ({ gameId: index + 1, searchText: `게임 ${index + 1}` })),
    };
}
