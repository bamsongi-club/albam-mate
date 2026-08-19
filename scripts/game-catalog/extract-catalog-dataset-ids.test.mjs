import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import test from 'node:test';

import {
    assertManifestSha256,
    buildCanonicalDataset,
    collectDatasetIds,
    extractCatalogDatasetIds,
    sha256,
} from './extract-catalog-dataset-ids.mjs';

function batch({
    file,
    requestIds,
    responseIds = requestIds,
    httpStatus = 200,
}) {
    return { file, requestIds, responseIds, httpStatus };
}

test('T1: manifest sha256이 기대값과 다르면 실패하고 출력을 만들지 않는다', () => {
    const contents = Buffer.from('{"schemaVersion":1,"files":[]}', 'utf8');
    const expected = createHash('sha256').update('something-else').digest('hex');

    assert.throws(
        () => assertManifestSha256(contents, expected),
        /sha256 mismatch/u,
    );

    assert.throws(
        () => extractCatalogDatasetIds(contents, { expectedManifestSha256: expected, expectedRows: 0 }),
        /sha256 mismatch/u,
    );
});

test('T1: manifest sha256이 기대값과 일치하면 통과한다', () => {
    const contents = Buffer.from('{"schemaVersion":1,"files":[]}', 'utf8');
    const expected = sha256(contents);

    assert.equal(assertManifestSha256(contents, expected), expected);
});

test('T2: httpStatus가 200이 아닌 batch가 있으면 실패한다', () => {
    const files = [
        batch({ file: 'batch-00001.xml', requestIds: [1, 2] }),
        batch({ file: 'batch-00002.xml', requestIds: [3, 4], httpStatus: 503 }),
    ];

    assert.throws(() => collectDatasetIds(files), /httpStatus is not 200/u);
});

test('T2: requestIds와 responseIds의 원소가 다르면 실패한다', () => {
    const files = [
        batch({ file: 'batch-00001.xml', requestIds: [1, 2], responseIds: [1, 3] }),
    ];

    assert.throws(() => collectDatasetIds(files), /requestIds\/responseIds elements do not match/u);
});

test('T2: requestIds와 responseIds가 순서만 다르면 통과한다', () => {
    const files = [
        batch({ file: 'batch-00001.xml', requestIds: [2, 1], responseIds: [1, 2] }),
    ];

    const ids = collectDatasetIds(files);
    assert.deepEqual([...ids].sort((a, b) => a - b), [1, 2]);
});

test('T3: 고유 responseIds 집합이 기대 행 수와 다르면 실패한다', () => {
    const ids = new Set([1, 2, 3]);

    assert.throws(() => buildCanonicalDataset(ids, 4), /unique response id count must be 4, got 3/u);
});

test('T3: 정상 입력이면 BGG ID 오름차순 canonical JSON과 rows\\/sha256\\/idSetSha256을 만든다', () => {
    const files = [
        batch({ file: 'batch-00001.xml', requestIds: [30, 10] }),
        batch({ file: 'batch-00002.xml', requestIds: [20, 10] }),
    ];
    const ids = collectDatasetIds(files);
    const result = buildCanonicalDataset(ids, 3);

    assert.equal(result.rows, 3);
    assert.equal(result.datasetText, `${JSON.stringify([
        { bgg_id: 10 },
        { bgg_id: 20 },
        { bgg_id: 30 },
    ], null, 2)}\n`);
    assert.equal(result.sha256, sha256(Buffer.from(result.datasetText, 'utf8')));
    assert.equal(result.idSetSha256, sha256(Buffer.from('10\n20\n30\n', 'utf8')));
});

test('T4: 동일한 입력으로 두 번 실행하면 바이트 단위로 완전히 동일한 결과가 재현된다', () => {
    const manifest = {
        schemaVersion: 1,
        files: [
            batch({ file: 'batch-00001.xml', requestIds: [30, 10] }),
            batch({ file: 'batch-00002.xml', requestIds: [20, 10] }),
        ],
    };
    const contents = Buffer.from(JSON.stringify(manifest), 'utf8');
    const expectedManifestSha256 = sha256(contents);

    const first = extractCatalogDatasetIds(contents, { expectedManifestSha256, expectedRows: 3 });
    const second = extractCatalogDatasetIds(contents, { expectedManifestSha256, expectedRows: 3 });

    assert.deepEqual(first, second);
    assert.equal(first.datasetText, second.datasetText);
    assert.equal(first.sha256, second.sha256);
    assert.equal(first.idSetSha256, second.idSetSha256);
});

test('extractCatalogDatasetIds는 실제 CATALOG_SOURCE_MANIFEST_SHA256 상수를 기본값으로 사용한다', async () => {
    const { CATALOG_SOURCE_MANIFEST_SHA256, CATALOG_DATASET_ROWS } = await import('./catalog-dataset-release-manifest.mjs');
    const contents = Buffer.from('not the real manifest', 'utf8');

    assert.throws(
        () => extractCatalogDatasetIds(contents),
        new RegExp(`expected ${CATALOG_SOURCE_MANIFEST_SHA256}`, 'u'),
    );
    assert.notEqual(CATALOG_DATASET_ROWS, undefined);
});
