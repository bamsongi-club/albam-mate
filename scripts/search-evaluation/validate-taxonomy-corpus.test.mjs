import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
    DEFAULT_MANIFEST_PATH,
    parseCsv,
    validateTaxonomyCorpus,
} from './validate-taxonomy-corpus.mjs';

test('CSV parser는 quoted comma를 query field 안에서 보존한다', () => {
    const rows = parseCsv('id,query\nQ001,"플레이어 3명, 최대 30분, 협력"\n');

    assert.deepEqual(rows, [
        ['id', 'query'],
        ['Q001', '플레이어 3명, 최대 30분, 협력'],
    ]);
});
test('taxonomy discovery v1 artifact를 검증한다', () => {
    const result = validateTaxonomyCorpus(DEFAULT_MANIFEST_PATH);

    assert.equal(result.queryCount, 150);
    assert.equal(result.annotationCount, 150);
    assert.equal(result.coverage.multiLabelCount, 83);
    assert.equal(result.coverage.ambiguousCount, 48);
    assert.equal(result.coverage.outlierCount, 4);
});

test('중복 query가 있으면 검증을 실패한다', () => {
    const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'search-04-taxonomy-'));
    try {
        cpSync(path.dirname(DEFAULT_MANIFEST_PATH), temporaryRoot, { recursive: true });
        const temporaryManifestPath = path.join(temporaryRoot, 'manifest.json');
        const temporaryQueriesPath = path.join(temporaryRoot, 'queries.csv');
        const originalQueries = readFileSync(temporaryQueriesPath, 'utf8');
        const duplicateQueries = originalQueries.replace(
            'Q002,"4명이 모두 초보여도 쉽게 즐길 수 있는 재미있는 파티 게임"',
            'Q002,"트릭테이킹 방식의 협력 게임 중 3인 이상 플레이 가능한 게임"',
        );
        writeFileSync(temporaryQueriesPath, duplicateQueries);
        const manifest = JSON.parse(readFileSync(temporaryManifestPath, 'utf8'));
        assert.throws(
            () => validateTaxonomyCorpus(temporaryManifestPath),
            /SHA-256이 manifest와 다릅니다/u,
        );
        manifest.artifacts.queries.sha256 = createHash('sha256').update(duplicateQueries).digest('hex');
        writeFileSync(temporaryManifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
        assert.throws(
            () => validateTaxonomyCorpus(temporaryManifestPath),
            /query가 중복됩니다/u,
        );
    } finally {
        rmSync(temporaryRoot, { recursive: true, force: true });
    }
});
