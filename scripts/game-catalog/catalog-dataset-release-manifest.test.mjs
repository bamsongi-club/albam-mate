import assert from 'node:assert/strict';
import test from 'node:test';
import { validateCatalogDatasetReleaseManifest } from './catalog-dataset-release-manifest.mjs';

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

test('01~07 승인 artifact와 실제 checksum/bytes가 모두 일치해야 한다', () => {
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
                '01b': { ...actualArtifacts['01b'], sha256: '0'.repeat(64) },
            },
        }),
        /01b.*sha256/u,
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

function validManifest() {
    const artifact = (index) => ({
        status: 'approved',
        path: `${index}-artifact.sql`,
        sha256: String(index).padStart(64, 'a').slice(-64).replace(/[^a-f0-9]/gu, 'a'),
        bytes: 100 + index.length,
    });
    const coverage = (rows, char) => ({ rows, sha256: char.repeat(64) });

    return {
        schemaVersion: 1,
        kind: 'catalog-dataset-release',
        releaseId: 'bgg-catalog-2026-08-16-v1',
        datasetId: 'bgg-catalog-170k',
        fieldVersion: 'catalog-fields-v1',
        approved: true,
        testOnly: false,
        approval: {
            reviewedBy: 'albam-mate-team',
            reviewedAt: '2026-08-16T00:00:00Z',
            references: ['https://github.com/bamsongi-club/albam-mate/issues/712'],
        },
        approvedFields: [
            'bgg_id',
            'name',
            'english_name',
            'description',
            'detail_description',
            'min_players',
            'max_players',
            'min_age',
        ],
        dataset: {
            rows: 170000,
            sha256: 'b'.repeat(64),
            idSetSha256: 'c'.repeat(64),
        },
        artifacts: {
            '01': artifact('01'),
            '01b': artifact('01b'),
            '02': artifact('02'),
            '03': artifact('03'),
            '04': artifact('04'),
            '05': artifact('05'),
            '06': artifact('06'),
            '07': artifact('07'),
        },
        coverage: {
            catalogIds: coverage(170000, 'd'),
            mechanismRelations: coverage(428488, 'e'),
            themeRelations: coverage(461973, 'f'),
            playerPreferences: coverage(263463, 'a'),
        },
    };
}
