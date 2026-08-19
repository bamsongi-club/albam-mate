import assert from 'node:assert/strict';
import test from 'node:test';
import {
    ARTIFACT_BASENAMES,
    CATALOG_DATASET_ID,
    CATALOG_DATASET_ROWS,
    CATALOG_DATASET_RELEASE_KIND,
    CATALOG_FIELD_VERSION,
    CATALOG_SOURCE_BATCH_ID,
    CATALOG_SOURCE_MANIFEST_SHA256,
    COVERAGE_SERIALIZATIONS,
    EXPECTED_COVERAGE_ROWS,
    validateCatalogDatasetReleaseManifest,
    validateCatalogDatasetReleaseReference,
} from './catalog-dataset-release-manifest.mjs';

test('catalog dataset release는 embedding 정보 없이 승인할 수 있다', () => {
    const manifest = validManifest();
    assert.deepEqual(validateCatalogDatasetReleaseManifest(manifest), manifest);
    assert.equal('embedding' in manifest, false);
});

test('승인되지 않았거나 test-only이면 차단한다', () => {
    assert.throws(
        () => validateCatalogDatasetReleaseManifest({ ...validManifest(), approved: false }),
        /approved/u,
    );
    assert.throws(
        () => validateCatalogDatasetReleaseManifest({ ...validManifest(), testOnly: true }),
        /testOnly/u,
    );
});

test('실제 dataset rows/hash/id-set hash가 manifest와 모두 일치해야 한다', () => {
    const manifest = validManifest();
    const actualDataset = { ...manifest.dataset };

    assert.doesNotThrow(() =>
        validateCatalogDatasetReleaseManifest(manifest, { actualDataset }),
    );

    assert.throws(
        () => validateCatalogDatasetReleaseManifest(manifest, {
            actualDataset: { ...actualDataset, rows: actualDataset.rows - 1 },
        }),
        /dataset\.rows does not match/u,
    );
    assert.throws(
        () => validateCatalogDatasetReleaseManifest(manifest, {
            actualDataset: { ...actualDataset, sha256: '0'.repeat(64) },
        }),
        /dataset\.sha256 does not match/u,
    );
    assert.throws(
        () => validateCatalogDatasetReleaseManifest(manifest, {
            actualDataset: { ...actualDataset, idSetSha256: '0'.repeat(64) },
        }),
        /dataset\.idSetSha256 does not match/u,
    );
});

test('01~02 승인 artifact와 실제 checksum/bytes가 모두 일치해야 한다', () => {
    const manifest = validManifest();
    const actualArtifacts = Object.fromEntries(
        Object.entries(manifest.artifacts).map(([key, value]) => [key, {
            sha256: value.sha256,
            bytes: value.bytes,
        }]),
    );

    assert.doesNotThrow(() =>
        validateCatalogDatasetReleaseManifest(manifest, { actualArtifacts }),
    );

    assert.throws(
        () => validateCatalogDatasetReleaseManifest(manifest, {
            actualArtifacts: {
                ...actualArtifacts,
                '02': { ...actualArtifacts['02'], sha256: '0'.repeat(64) },
            },
        }),
        /02.*sha256/u,
    );
});

test('dataset rows/hash와 coverage가 없으면 차단한다', () => {
    const missingRows = validManifest();
    delete missingRows.dataset.rows;
    assert.throws(() => validateCatalogDatasetReleaseManifest(missingRows), /dataset\.rows/u);

    const missingCoverage = validManifest();
    delete missingCoverage.coverage.catalogIds;
    assert.throws(() => validateCatalogDatasetReleaseManifest(missingCoverage), /coverage\.catalogIds/u);
});

test('고정 dataset profile 밖의 ID, 행 수, 필드와 embedding 선언은 차단한다', () => {
    assert.throws(
        () => validateCatalogDatasetReleaseManifest({ ...validManifest(), datasetId: 'other-dataset' }),
        /datasetId/u,
    );
    assert.throws(
        () => validateCatalogDatasetReleaseManifest({
            ...validManifest(),
            dataset: { ...validManifest().dataset, rows: CATALOG_DATASET_ROWS - 1 },
        }),
        /dataset\.rows/u,
    );
    assert.throws(
        () => validateCatalogDatasetReleaseManifest({
            ...validManifest(),
            approvedFields: [...validManifest().approvedFields, 'embedding'],
        }),
        /trusted profile/u,
    );
    assert.throws(
        () => validateCatalogDatasetReleaseManifest({ ...validManifest(), embedding: {} }),
        /unsupported fields.*embedding/u,
    );
});

test('approved field마다 trusted source provenance가 있어야 한다', () => {
    const missingProvenance = validManifest();
    delete missingProvenance.fieldProvenance.name;
    assert.throws(() => validateCatalogDatasetReleaseManifest(missingProvenance), /fieldProvenance keys/u);

    const untrustedSource = validManifest();
    untrustedSource.fieldProvenance.name.sourceColumn = 'untrusted';
    assert.throws(() => validateCatalogDatasetReleaseManifest(untrustedSource), /not trusted/u);
});

test('실제 SQL coverage를 계산한 값과 manifest 선언을 대조한다', () => {
    const manifest = validManifest();
    const actualCoverage = structuredClone(manifest.coverage);
    assert.doesNotThrow(() =>
        validateCatalogDatasetReleaseManifest(manifest, { actualCoverage }),
    );

    actualCoverage.themeRelations.sha256 = '0'.repeat(64);
    assert.throws(
        () => validateCatalogDatasetReleaseManifest(manifest, { actualCoverage }),
        /coverage\.themeRelations\.sha256/u,
    );
});

test('artifact path는 key에 맞는 상대 경로이고 서로 다른 파일이어야 한다', () => {
    const duplicatePath = validManifest();
    duplicatePath.artifacts['02'].path = duplicatePath.artifacts['01'].path;
    assert.throws(() => validateCatalogDatasetReleaseManifest(duplicatePath), /duplicates/u);

    const parentPath = validManifest();
    parentPath.artifacts['01'].path = `artifacts/../${ARTIFACT_BASENAMES['01']}`;
    assert.throws(() => validateCatalogDatasetReleaseManifest(parentPath), /parent traversal/u);

    const absolutePath = validManifest();
    absolutePath.artifacts['01'].path = `/tmp/${ARTIFACT_BASENAMES['01']}`;
    assert.throws(() => validateCatalogDatasetReleaseManifest(absolutePath), /relative path/u);

    const wrongBasename = validManifest();
    wrongBasename.artifacts['01'].path = `artifacts/${ARTIFACT_BASENAMES['02']}`;
    assert.throws(() => validateCatalogDatasetReleaseManifest(wrongBasename), /must end with/u);
});

test('execution manifest는 dataset release ID와 SHA를 함께 참조해야 한다', () => {
    const datasetManifest = validManifest();
    const reference = {
        manifestPath: 'catalog-dataset-release.json',
        releaseId: datasetManifest.releaseId,
        datasetId: datasetManifest.datasetId,
        manifestSha256: '1'.repeat(64),
    };
    assert.deepEqual(
        validateCatalogDatasetReleaseReference(reference, datasetManifest, reference.manifestSha256),
        reference,
    );
    assert.throws(
        () => validateCatalogDatasetReleaseReference(
            { ...reference, releaseId: 'different-release' },
            datasetManifest,
        ),
        /releaseId/u,
    );
    assert.throws(
        () => validateCatalogDatasetReleaseReference(
            reference,
            datasetManifest,
            '2'.repeat(64),
        ),
        /manifestSha256/u,
    );
});

function validManifest() {
    const artifact = (index) => ({
        status: 'approved',
        path: `artifacts/${ARTIFACT_BASENAMES[index]}`,
        sha256: String(index).padStart(64, 'a').slice(-64).replace(/[^a-f0-9]/gu, 'a'),
        bytes: 100 + index.length,
    });
    const coverage = (name, char) => ({
        rows: EXPECTED_COVERAGE_ROWS[name],
        sha256: char.repeat(64),
        serialization: COVERAGE_SERIALIZATIONS[name],
    });
    const approvedFields = [
        'bgg_id',
        'name',
        'english_name',
        'description',
        'detail_description',
        'min_players',
        'max_players',
        'min_age',
    ];
    const fieldProvenance = Object.fromEntries(
        approvedFields.map((field) => [field, {
            sourceColumn: {
                bgg_id: 'BGG XML item[@id]',
                name: 'BGG XML alternate name or primary name',
                english_name: 'BGG XML name[@type=primary]@value',
                description: 'BGG XML description derived first sentence',
                detail_description: 'BGG XML description',
                min_players: 'BGG XML minplayers',
                max_players: 'BGG XML maxplayers',
                min_age: 'BGG XML poll suggested_age',
            }[field],
            sourceVersion: CATALOG_SOURCE_BATCH_ID,
            public: true,
            processingAllowed: true,
            provenanceSha256: CATALOG_SOURCE_MANIFEST_SHA256,
        }]),
    );

    return {
        schemaVersion: 1,
        kind: CATALOG_DATASET_RELEASE_KIND,
        releaseId: 'bgg-catalog-2026-08-16-v1',
        datasetId: CATALOG_DATASET_ID,
        fieldVersion: CATALOG_FIELD_VERSION,
        approved: true,
        testOnly: false,
        approval: {
            reviewedBy: 'albam-mate-team',
            reviewedAt: '2026-08-16T00:00:00Z',
            references: ['https://github.com/bamsongi-club/albam-mate/issues/712'],
        },
        sourceSnapshot: {
            source: 'BGG XML',
            batchId: CATALOG_SOURCE_BATCH_ID,
            rows: CATALOG_DATASET_ROWS,
            manifestSha256: CATALOG_SOURCE_MANIFEST_SHA256,
        },
        approvedFields,
        fieldProvenance,
        dataset: {
            rows: CATALOG_DATASET_ROWS,
            sha256: 'b'.repeat(64),
            idSetSha256: 'c'.repeat(64),
        },
        artifacts: {
            '01': artifact('01'),
            '02': artifact('02'),
        },
        coverage: {
            catalogIds: coverage('catalogIds', 'd'),
            mechanismRelations: coverage('mechanismRelations', 'e'),
            themeRelations: coverage('themeRelations', 'f'),
            playerPreferences: coverage('playerPreferences', 'a'),
        },
    };
}
