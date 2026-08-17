import assert from 'node:assert/strict';
import test from 'node:test';
import { buildKoreanDescriptionUpsertSql } from './export-korean-descriptions.mjs';

test('한국어 설명을 bgg_id 오름차순 UPSERT로 내보낸다', () => {
    const { rows, sql } = buildKoreanDescriptionUpsertSql([
        koreanRow(20), koreanRow(3),
    ]);

    assert.equal(rows, 2);
    assert.match(sql, /BEGIN;/u);
    assert.match(sql, /UPDATE games AS g/u);
    assert.match(sql, /COMMIT;\n$/u);
    assert.ok(sql.indexOf('(3, ') < sql.indexOf('(20, '), 'bgg_id 오름차순이어야 한다');
});

test('작은따옴표를 이스케이프해 SQL이 깨지지 않는다', () => {
    const { sql } = buildKoreanDescriptionUpsertSql([
        { bgg_id: 7, description: "'재미'있는 게임입니다", detail_description: '설명이 길게 이어집니다' },
    ]);

    assert.match(sql, /'''재미''있는 게임입니다'/u);
});

test('번역되지 않은 설명은 내보내지 않는다', () => {
    assert.throws(
        () => buildKoreanDescriptionUpsertSql([
            { bgg_id: 1, description: 'Wingspan is a competitive board game.', detail_description: '한국어 상세 설명입니다' },
        ]),
        /description가 한국어로 번역되지 않았다/u,
    );
    assert.throws(
        () => buildKoreanDescriptionUpsertSql([
            { bgg_id: 1, description: '한국어 간단 설명입니다', detail_description: 'Players draft cards and score points.' },
        ]),
        /detail_description가 한국어로 번역되지 않았다/u,
    );
});

test('영문 단어만 섞인 한국어 설명은 그대로 내보낸다', () => {
    assert.doesNotThrow(() => buildKoreanDescriptionUpsertSql([
        { bgg_id: 1, description: 'Paths of Glory의 전투를 다루는 게임입니다', detail_description: '3M이 만든 고전입니다' },
    ]));
});

test('빈 필드와 중복 bgg_id, 빈 입력을 막는다', () => {
    assert.throws(
        () => buildKoreanDescriptionUpsertSql([{ bgg_id: 1, description: '  ', detail_description: '한국어 설명입니다' }]),
        /description가 비어 있다/u,
    );
    assert.throws(
        () => buildKoreanDescriptionUpsertSql([koreanRow(5), koreanRow(5)]),
        /bgg_id가 중복됐다/u,
    );
    assert.throws(() => buildKoreanDescriptionUpsertSql([]), /비어 있지 않은 JSON 배열/u);
    assert.throws(
        () => buildKoreanDescriptionUpsertSql([{ bgg_id: 0, description: '한국어', detail_description: '한국어' }]),
        /bgg_id가 양의 정수가 아니다/u,
    );
});

function koreanRow(bggId) {
    return {
        bgg_id: bggId,
        description: '타일을 놓아 도시를 넓히는 게임입니다',
        detail_description: '자기 차례에 타일을 한 장 놓고 말을 올립니다',
    };
}
