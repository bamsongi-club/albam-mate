import assert from 'node:assert/strict';
import test from 'node:test';

import { FIELD_PROFILES } from './catalog-dataset-release-manifest.mjs';
import {
    PROCESSING_SCOPE_SEARCH_TEXT_ASSEMBLY,
    SEARCH_TEXT_TEMPLATE,
    validatePocSearchTextManifest,
} from './poc-search-text-manifest.mjs';

const DATASET_MANIFEST = {
    releaseId: 'bgg-catalog-170k-v4-2026-08-19',
    datasetId: 'bgg-catalog-170k',
    approvedFields: ['name', 'english_name', 'description', 'detail_description'],
    fieldProvenance: {
        name: { public: true, processingAllowed: true },
        english_name: { public: true, processingAllowed: true },
        description: { public: true, processingAllowed: true },
        detail_description: { public: true, processingAllowed: true },
    },
};
const DATASET_MANIFEST_SHA256 = 'a'.repeat(64);
const CORPUS_SHA256 = 'b'.repeat(64);
const SEARCH_TEXT_SHA256 = 'c'.repeat(64);

function validManifest(overrides = {}) {
    return {
        schemaVersion: 1,
        kind: 'poc-search-text-execution',
        approved: true,
        testOnly: false,
        approval: {
            reviewedBy: 'catalog-reviewer',
            reviewedAt: '2026-08-19T00:00:00Z',
            references: ['https://github.com/bamsongi-club/albam-mate/issues/833'],
        },
        datasetRelease: {
            manifestPath: 'catalog-dataset-release.json',
            releaseId: DATASET_MANIFEST.releaseId,
            datasetId: DATASET_MANIFEST.datasetId,
            manifestSha256: DATASET_MANIFEST_SHA256,
        },
        approvedFields: ['name', 'englishName', 'description', 'detailDescription'],
        approvedProcessingScopes: [PROCESSING_SCOPE_SEARCH_TEXT_ASSEMBLY],
        sources: { corpus: { path: 'quality-corpus.json', sha256: CORPUS_SHA256, rows: 1000 } },
        outputs: { searchText: { path: 'search-text.json', sha256: SEARCH_TEXT_SHA256, rows: 1000 } },
        searchTextTemplate: SEARCH_TEXT_TEMPLATE,
        ...overrides,
    };
}

function validate(manifest, overrides = {}) {
    return validatePocSearchTextManifest(manifest, {
        datasetManifest: DATASET_MANIFEST,
        actualManifestSha256: DATASET_MANIFEST_SHA256,
        actualCorpusSha256: CORPUS_SHA256,
        ...overrides,
    });
}

test('T1: 승인된 PoC manifest는 release provenance에 결속된 필드와 승인·입출력 descriptor를 검증한다', () => {
    const manifest = validManifest();
    assert.deepEqual(validate(manifest), manifest);
    for (const field of manifest.approvedFields) assert.ok(field in FIELD_PROFILES);

    for (const field of ['alias', 'category', 'theme', 'mechanism']) {
        assert.throws(
            () => validate(validManifest({ approvedFields: [...manifest.approvedFields, field] })),
            new RegExp(`does not map.*${field}`, 'u'),
        );
    }
    assert.throws(
        () => validate(manifest, { datasetManifest: { ...DATASET_MANIFEST, approvedFields: ['name', 'english_name', 'description'] } }),
        /not approved by dataset release/u,
    );
    assert.throws(
        () => validate(validManifest({ approved: false })),
        /approved/u,
    );
    assert.throws(
        () => validate(validManifest({ approval: { reviewedBy: '', reviewedAt: 'not-an-instant', references: ['not-a-url'] } })),
        /approval\.reviewedBy/u,
    );
    assert.throws(
        () => validate(validManifest({ approval: undefined })),
        /approval must be an object/u,
    );
    assert.throws(
        () => validate(validManifest({ sources: { corpus: { path: '../quality-corpus.json', sha256: CORPUS_SHA256, rows: 1000 } } })),
        /sources\.corpus\.path/u,
    );
    assert.throws(
        () => validate(validManifest({ outputs: { searchText: { path: '../search-text.json', sha256: SEARCH_TEXT_SHA256, rows: 1000 } } })),
        /outputs\.searchText\.path/u,
    );
    assert.throws(
        () => validate(manifest, { datasetManifest: { ...DATASET_MANIFEST, fieldProvenance: { ...DATASET_MANIFEST.fieldProvenance, description: { public: true, processingAllowed: false } } } }),
        /processingAllowed/u,
    );
    assert.throws(
        () => validate(validManifest({ approvedProcessingScopes: [] })),
        /search-text-assembly/u,
    );
});
