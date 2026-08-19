import assert from "node:assert/strict";
import test from "node:test";

import {
    analyzeDescriptionQuality,
    classifyDescription,
    validateDescriptionProvenance,
} from "./description-quality.mjs";

test("설명 언어 상태를 영문·한국어·혼합·기타·누락으로 결정적으로 분류한다", () => {
    assert.equal(classifyDescription("Players draft cards and score points."), "english");
    assert.equal(classifyDescription("카드를 뽑고 승점을 얻는 게임입니다."), "korean");
    assert.equal(classifyDescription("Game about 전략적 politics and roles."), "mixed");
    assert.equal(classifyDescription("123 — 🎲"), "other");
    assert.equal(classifyDescription("  "), "missing");
});

test("완전한 영문 문장이 한국어 문장과 함께 있으면 mixed로 차단한다", () => {
    assert.equal(
        classifyDescription("Players draw cards. 카드를 뽑아 승점을 얻습니다."),
        "mixed",
    );
});

test("소문자 일반 영단어가 한국어 조사와 붙은 혼합 문장은 mixed로 차단한다", () => {
    assert.equal(
        classifyDescription("이 게임은 strategy를 요구합니다."),
        "mixed",
    );
});

test("별도 줄의 영문 게임명 뒤에 한국어 설명이 오면 korean으로 허용한다", () => {
    assert.equal(
        classifyDescription("Ticket to Ride: Europe\n\n이 게임은 유럽을 배경으로 합니다."),
        "korean",
    );
});

test("긴 영문 게임명과 고유명사가 포함된 정상 한국어 설명은 korean으로 허용한다", () => {
    assert.equal(
        classifyDescription(
            "The Lord of the Rings: The Card Game – The Fellowship of the Ring – Revised Core Set에서 플레이어는 중간계를 여행하며 반지를 파괴합니다.",
        ),
        "korean",
    );
    assert.equal(
        classifyDescription("BGG와 AI/VR 도구를 활용해 카드를 관리하는 게임입니다."),
        "korean",
    );
    assert.equal(
        classifyDescription("첫 번째 게임(First Game)은 2020년에 출시된 전략 게임입니다."),
        "korean",
    );
    assert.equal(
        classifyDescription('"First Game"은 2020년에 출시된 전략 게임입니다.'),
        "korean",
    );
    assert.equal(
        classifyDescription("3M이 만든 카드 게임으로 R2-D2가 등장합니다."),
        "korean",
    );
    assert.equal(
        classifyDescription("The Lord of the Rings: The Card Game에서 플레이어는 여행합니다."),
        "korean",
    );
});

test("영문 문장의 일부 단어만 한국어 조사와 연결된 경우 mixed로 차단한다", () => {
    assert.equal(
        classifyDescription("The game 은(는) 다음과 같은 방식으로 진행됩니다."),
        "mixed",
    );
    assert.equal(
        classifyDescription("It's a 보드게임입니다."),
        "mixed",
    );
    assert.equal(
        classifyDescription("It 은(는) 서로 협력하는 게임입니다."),
        "mixed",
    );
});

test("품질 보고서는 필드별 상태와 혼합 행 수를 기록한다", () => {
    const quality = analyzeDescriptionQuality([
        {
            bgg_id: 1,
            description: "한국어 소개입니다.",
            detail_description: "Game about 전략적 politics and roles.",
        },
        {
            bgg_id: 2,
            description: "Players score points.",
            detail_description: "한국어 상세 설명입니다.",
        },
    ]);

    assert.equal(quality.classifierVersion, "description-language-v2");
    assert.equal(quality.rowCounts.mixed, 1);
    assert.equal(quality.rowCounts.untranslated, 1);
    assert.equal(quality.fields.description.counts.english, 1);
    assert.equal(quality.fields.detail_description.counts.mixed, 1);
});

test("승인된 설명 provenance는 필드별 source와 processing 상태를 요구한다", () => {
    const valid = {
        provenance: {
            descriptionFields: {
                description: provenance(),
                detail_description: provenance(),
            },
        },
    };
    assert.deepEqual(validateDescriptionProvenance(valid), []);

    const invalid = structuredClone(valid);
    invalid.provenance.descriptionFields.description.status = "review-required";
    assert.ok(validateDescriptionProvenance(invalid).some(({ code }) => code === "DESCRIPTION_PROVENANCE_NOT_APPROVED"));

    const placeholder = structuredClone(valid);
    placeholder.provenance.descriptionFields.description.source = "TODO: 원천 확인";
    assert.ok(validateDescriptionProvenance(placeholder).some(({ code }) => code === "INVALID_DESCRIPTION_PROVENANCE"));

    for (const value of ["TBD", "N/A", "pending", "UNKNOWN source", "PLACEHOLDER"]) {
        const invalidPlaceholder = structuredClone(valid);
        invalidPlaceholder.provenance.descriptionFields.description.source = value;
        assert.ok(
            validateDescriptionProvenance(invalidPlaceholder).some(
                ({ code }) => code === "INVALID_DESCRIPTION_PROVENANCE",
            ),
            value,
        );
    }
});

function provenance() {
    return {
        source: "approved test input",
        sourceVersion: "description-v1",
        processing: "human-reviewed",
        status: "approved",
        reviewedBy: "test-reviewer",
        reviewedAt: "2026-08-13T00:00:00Z",
    };
}
