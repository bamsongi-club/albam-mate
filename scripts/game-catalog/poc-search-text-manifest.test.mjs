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
};
const DATASET_MANIFEST_SHA256 = 'a'.repeat(64);
const CORPUS_SHA256 = 'b'.repeat(64);

function validManifest(overrides = {}) {
    return {
        schemaVersion: 1,
        kind: 'poc-search-text-execution',
        approved: true,
        testOnly: false,
        datasetRelease: {
            manifestPath: 'catalog-dataset-release.json',
            releaseId: DATASET_MANIFEST.releaseId,
            datasetId: DATASET_MANIFEST.datasetId,
            manifestSha256: DATASET_MANIFEST_SHA256,
        },
        approvedFields: ['name', 'englishName', 'alias', 'description', 'detailDescription', 'category', 'theme', 'mechanism'],
        approvedProcessingScopes: [PROCESSING_SCOPE_SEARCH_TEXT_ASSEMBLY],
        corpus: { path: 'quality-corpus.json', sha256: CORPUS_SHA256 },
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

test('T1: 승인된 PoC manifest는 공유 FIELD_PROFILES의 search_text allowlist와 release·corpus 참조를 검증한다', () => {
    const manifest = validManifest();
    assert.deepEqual(validate(manifest), manifest);
    for (const field of manifest.approvedFields) assert.ok(field in FIELD_PROFILES);

    assert.throws(
        () => validate(validManifest({ approvedFields: [...manifest.approvedFields, 'minPlayers'] })),
        /trusted profile/u,
    );
    assert.throws(
        () => validate(validManifest({ approved: false })),
        /approved/u,
    );
    assert.throws(
        () => validate(validManifest({ corpus: { path: '../quality-corpus.json', sha256: CORPUS_SHA256 } })),
        /corpus\.path/u,
    );
    assert.throws(
        () => validate(validManifest({ approvedProcessingScopes: [] })),
        /search-text-assembly/u,
    );
});
