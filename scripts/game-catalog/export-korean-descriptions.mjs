#!/usr/bin/env node
// 한국어로 검수한 게임 설명을 적재용 UPSERT SQL로 내보낸다.
//
//   node export-korean-descriptions.mjs --input <json> --out <sql>
//
// 입력은 `[{ "bgg_id": 1, "description": "...", "detail_description": "..." }]` 형태이며,
// 두 필드가 모두 한국어인 행만 내보낸다. 미번역 행이 섞이면 적재 게이트가 막으므로
// 여기서 먼저 걸러 실패를 앞당긴다.

import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { basename, resolve } from 'node:path';

import { validateApprovedReleaseManifest } from './catalog-release-manifest.mjs';
import { classifyDescription } from './description-quality.mjs';

const REQUIRED_FIELDS = ['description', 'detail_description'];

export function buildKoreanDescriptionUpsertSql(rows, manifest, { actualDescriptionInput } = {}) {
    if (!Array.isArray(rows) || rows.length === 0) {
        throw new Error('입력은 비어 있지 않은 JSON 배열이어야 한다');
    }
    try {
        validateApprovedReleaseManifest(manifest, {
            actualDescriptionInput,
            requiredProcessingScopes: ['description-correction'],
        });
    } catch (error) {
        throw new Error(
            `승인된 description correction release manifest가 필요하다: ${error.message}`,
            { cause: error },
        );
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
            if (classifyDescription(value) !== 'korean') {
                throw new Error(`${bggId}의 ${field}가 정상 한국어 설명이 아니다`);
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
    if (!values.input || !values.manifest || !values.out) failUsage();
    return {
        input: resolve(values.input),
        manifest: resolve(values.manifest),
        out: resolve(values.out),
    };
}

function failUsage() {
    process.stderr.write(
        'usage: node export-korean-descriptions.mjs --input <json> --manifest <json> --out <sql>\n',
    );
    process.exit(2);
}

if (process.argv[1] && import.meta.url.endsWith(process.argv[1].split('/').pop())) {
    const options = parseOptions(process.argv.slice(2));
    const inputContents = readFileSync(options.input, 'utf8');
    const rows = JSON.parse(inputContents);
    const manifest = JSON.parse(readFileSync(options.manifest, 'utf8'));
    const result = buildKoreanDescriptionUpsertSql(rows, manifest, {
        actualDescriptionInput: {
            fileName: basename(options.input),
            sha256: sha256(inputContents),
            rows: rows.length,
        },
    });
    writeFileSync(options.out, result.sql, 'utf8');
    process.stdout.write(`${result.rows}건 내보냄 -> ${options.out}\n`);
}

function sha256(value) {
    return createHash('sha256').update(value).digest('hex');
}
