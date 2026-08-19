import assert from "node:assert/strict";
import test from "node:test";

import {
    parseGameCatalogSqlChunks,
    parseGameCatalogSqlText,
} from "./parse-game-catalog-sql.mjs";

test("SQL parser는 여러 INSERT와 문자열 내부 comma·괄호·개행·NULL을 보존한다", () => {
    const sql = `
INSERT INTO games (bgg_id, description, detail_description, note) VALUES
  (1, '한국어, (설명)'' 입니다
둘째 줄', '한국어 상세\\경로입니다', NULL),
  (2, 'Players draw cards. 카드를 뽑습니다.', '한국어 상세 설명입니다', 'note');
INSERT INTO games (detail_description, description, bgg_id) VALUES
  ('한국어 상세 설명입니다', NULL, 3) ON CONFLICT (bgg_id) DO UPDATE SET description = EXCLUDED.description;
`;

    const report = parseGameCatalogSqlText(sql);

    assert.equal(report.insertStatements, 2);
    assert.equal(report.rows, 3);
    assert.equal(report.uniqueBggIds, 3);
    assert.equal(report.maxBggId, 3);
    assert.equal(report.descriptionQuality.fields.description.counts.korean, 1);
    assert.equal(report.descriptionQuality.fields.description.counts.mixed, 1);
    assert.equal(report.descriptionQuality.fields.description.counts.missing, 1);
    assert.equal(report.descriptionQuality.fields.detail_description.counts.korean, 3);
    assert.equal(report.descriptionQuality.rowCounts.mixed, 1);
    assert.equal(report.descriptionQuality.rowCounts.missing, 1);
    assert.equal(report.bothFieldsKoreanRows, 1);
    assert.match(
        report.descriptionQuality.fields.description.samples.korean[0].sample,
        /한국어, \(설명\)' 입니다\n둘째 줄/u,
    );
});

test("SQL parser는 INSERT header·문자열·행 경계가 chunk에서 잘려도 같은 결과를 낸다", () => {
    const sql = `INSERT INTO games (bgg_id, description, detail_description)
VALUES (10, '경로 \\ ''와, 괄호(가) 포함', '상세 설명');`;
    const chunks = [...sql].map((character) => character);

    const chunked = parseGameCatalogSqlChunks(chunks);
    const whole = parseGameCatalogSqlText(sql);
    assert.deepEqual({ ...chunked, source: null }, { ...whole, source: null });
});

test("SQL parser는 닫히지 않은 문자열을 조용히 행으로 세지 않는다", () => {
    assert.throws(
        () => parseGameCatalogSqlText(
            "INSERT INTO games (bgg_id, description, detail_description) VALUES (1, '닫히지 않음, 상세);",
        ),
        /입력이 끝났다/u,
    );
});

test("SQL parser는 games INSERT가 아닌 입력을 측정하지 않는다", () => {
    assert.throws(
        () => parseGameCatalogSqlText("SELECT 1;"),
        /games INSERT statement를 찾지 못했다/u,
    );
});
