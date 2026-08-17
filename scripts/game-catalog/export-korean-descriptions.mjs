#!/usr/bin/env node
// 한국어로 검수한 게임 설명을 적재용 UPSERT SQL로 내보낸다.
//
//   node export-korean-descriptions.mjs --input <json> --out <sql>
//
// 입력은 `[{ "bgg_id": 1, "description": "...", "detail_description": "..." }]` 형태이며,
// 두 필드가 모두 한국어인 행만 내보낸다. 미번역 행이 섞이면 적재 게이트가 막으므로
// 여기서 먼저 걸러 실패를 앞당긴다.

import { readFileSync, writeFileSync } from 'node:fs';
import { resolve } from 'node:path';

const HANGUL_LETTERS = /[가-힣ㄱ-ㅎㅏ-ㅣ]/g;
const LATIN_LETTERS = /[A-Za-z]/g;
const MIN_HANGUL_LETTER_SHARE = 0.3;
const REQUIRED_FIELDS = ['description', 'detail_description'];

export function buildKoreanDescriptionUpsertSql(rows) {
    if (!Array.isArray(rows) || rows.length === 0) {
        throw new Error('입력은 비어 있지 않은 JSON 배열이어야 한다');
    }

    const seen = new Set();
    const exported = [];
    for (const row of rows) {
        const bggId = row.bgg_id ?? row.bggId;
        if (!Number.isSafeInteger(bggId) || bggId <= 0) {
            throw new Error(`bgg_id가 양의 정수가 아니다: ${JSON.stringify(row.bgg_id ?? null)}`);
        }
        if (seen.has(bggId)) {
            throw new Error(`bgg_id가 중복됐다: ${bggId}`);
        }
        seen.add(bggId);

        for (const field of REQUIRED_FIELDS) {
            const value = row[field];
            if (typeof value !== 'string' || value.trim() === '') {
                throw new Error(`${bggId}의 ${field}가 비어 있다`);
            }
            if (isUntranslated(value)) {
                throw new Error(`${bggId}의 ${field}가 한국어로 번역되지 않았다`);
            }
        }
        exported.push({
            bggId,
            description: row.description,
            detailDescription: row.detail_description,
        });
    }

    exported.sort((left, right) => left.bggId - right.bggId);

    const values = exported
        .map(({ bggId, description, detailDescription }) =>
            `    (${bggId}, ${quote(description)}, ${quote(detailDescription)})`)
        .join(',\n');

    return {
        rows: exported.length,
        sql: `-- 한국어 검수 게임 설명 ${exported.length}건 적재본
-- 생성: scripts/game-catalog/export-korean-descriptions.mjs
BEGIN;

UPDATE games AS g
SET description = v.description,
    detail_description = v.detail_description,
    updated_at = now()
FROM (VALUES
${values}
) AS v(bgg_id, description, detail_description)
WHERE g.bgg_id = v.bgg_id;

COMMIT;
`,
    };
}

// 한글 비율이 30% 미만이면 미번역으로 본다. catalog-analysis.mjs 의 게이트와 같은 기준이다.
function isUntranslated(value) {
    const hangul = value.match(HANGUL_LETTERS)?.length ?? 0;
    const latin = value.match(LATIN_LETTERS)?.length ?? 0;
    const letters = hangul + latin;
    if (letters === 0) {
        return false;
    }
    return hangul / letters < MIN_HANGUL_LETTER_SHARE;
}

function quote(value) {
    return `'${value.replaceAll("'", "''")}'`;
}

function parseOptions(args) {
    const values = {};
    for (let index = 0; index < args.length; index += 2) {
        const key = args[index];
        const value = args[index + 1];
        if (!key?.startsWith('--') || !value) failUsage();
        values[key.slice(2)] = value;
    }
    if (!values.input || !values.out) failUsage();
    return { input: resolve(values.input), out: resolve(values.out) };
}

function failUsage() {
    process.stderr.write(
        'usage: node export-korean-descriptions.mjs --input <json> --out <sql>\n',
    );
    process.exit(2);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
    const options = parseOptions(process.argv.slice(2));
    const result = buildKoreanDescriptionUpsertSql(JSON.parse(readFileSync(options.input, 'utf8')));
    writeFileSync(options.out, result.sql, 'utf8');
    process.stdout.write(`${result.rows}건 내보냄 -> ${options.out}\n`);
}
