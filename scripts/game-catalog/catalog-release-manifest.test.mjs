import assert from 'node:assert/strict';
import test from 'node:test';
import { validateApprovedReleaseManifest } from './catalog-release-manifest.mjs';

test('승인 release manifest는 필수 입력과 coverage를 보존한다', () => {
    const manifest = validManifest();

    assert.deepEqual(validateApprovedReleaseManifest(manifest), manifest);
});

test('승인되지 않았거나 test-only인 manifest는 차단한다', () => {
    assert.throws(
        () => validateApprovedReleaseManifest({ ...validManifest(), approved: false }),
        /approved/u,
    );
    assert.throws(
        () => validateApprovedReleaseManifest({ ...validManifest(), testOnly: true }),
        /testOnly/u,
    );
});

test('releaseId는 Windows 예약 장치명과 확장자 변형을 차단한다', () => {
    for (const releaseId of ['con', 'aux.zip', 'com1.release', 'lpt9.v1']) {
        assert.throws(
            () => validateApprovedReleaseManifest({ ...validManifest(), releaseId }),
            /Windows reserved/u,
        );
    }
});

test('approval reviewedAt은 실제 UTC instant만 허용한다', () => {
    const manifest = validManifest();
    manifest.approval.reviewedAt = '2026-02-29T00:00:00Z';

    assert.throws(
        () => validateApprovedReleaseManifest(manifest),
        /reviewedAt/u,
    );
});

test('입력 artifact 하나라도 승인 상태가 아니면 차단한다', () => {
    const manifest = validManifest();
    manifest.inputs.themeDictionary.status = 'review-draft';

    assert.throws(
        () => validateApprovedReleaseManifest(manifest),
        /themeDictionary.*approved/u,
    );
});

test('artifact checksum·행 수와 catalog ID coverage가 없으면 차단한다', () => {
    const missingChecksum = validManifest();
    delete missingChecksum.inputs.catalog.sha256;
    assert.throws(
        () => validateApprovedReleaseManifest(missingChecksum),
        /catalog.*sha256/u,
    );

    const missingCoverage = validManifest();
    delete missingCoverage.coverage.relationGameIds;
    assert.throws(
        () => validateApprovedReleaseManifest(missingCoverage),
        /relationGameIds/u,
    );
});

test('dataset·AI allowlist·search_text·embedding provenance가 없으면 차단한다', () => {
    const missingDataset = validManifest();
    delete missingDataset.datasetId;
    assert.throws(
        () => validateApprovedReleaseManifest(missingDataset),
        /datasetId/u,
    );

    const missingScope = validManifest();
    missingScope.approvedProcessingScopes = ['service-load'];
    assert.throws(
        () => validateApprovedReleaseManifest(missingScope),
        /approvedProcessingScopes/u,
    );

    const missingSearchText = validManifest();
    delete missingSearchText.search_text.assemblyRuleVersion;
    assert.throws(
        () => validateApprovedReleaseManifest(missingSearchText),
        /assemblyRuleVersion/u,
    );

    const missingEmbeddingOutput = validManifest();
    delete missingEmbeddingOutput.embedding.output.sha256;
    assert.throws(
        () => validateApprovedReleaseManifest(missingEmbeddingOutput),
        /embedding\.output\.sha256/u,
    );
});

test('renderer가 출력하는 설명 필드는 approvedFields allowlist에 포함되어야 한다', () => {
    const manifest = validManifest();
    manifest.approvedFields = ['name', 'english_name', 'alias', 'tag'];
    manifest.search_text.fields = ['name', 'english_name'];

    assert.throws(
        () => validateApprovedReleaseManifest(manifest),
        /approvedFields.*description.*detail_description/u,
    );
});

test('runner 입력·산출물의 실제 checksum과 행 수가 manifest와 다르면 차단한다', () => {
    const manifest = validManifest();
    const actualInputs = {
        games: { fileName: 'games.json', sha256: 'd'.repeat(64), rows: 2 },
        ranks: { fileName: 'ranks.csv', sha256: 'e'.repeat(64), rows: 2 },
    };
    const actualOutputs = {
        serviceCatalog: {
            fileName: 'service-catalog.json',
            sha256: 'f'.repeat(64),
            rows: 2,
        },
        upsertSql: { fileName: 'upsert-games.sql', sha256: 'f'.repeat(64), rows: 2 },
    };

    assert.doesNotThrow(() =>
        validateApprovedReleaseManifest(manifest, { actualInputs, actualOutputs }),
    );

    assert.throws(
        () => validateApprovedReleaseManifest(manifest, {
            actualInputs: {
                ...actualInputs,
                games: { ...actualInputs.games, sha256: '0'.repeat(64) },
            },
        }),
        /sources\.games.*sha256/u,
    );
    assert.throws(
        () => validateApprovedReleaseManifest(manifest, {
            actualInputs: {
                ...actualInputs,
                ranks: { ...actualInputs.ranks, rows: 1 },
            },
        }),
        /sources\.ranks.*rows/u,
    );
    assert.throws(
        () => validateApprovedReleaseManifest(manifest, {
            actualOutputs: {
                ...actualOutputs,
                serviceCatalog: { ...actualOutputs.serviceCatalog, sha256: '0'.repeat(64) },
            },
        }),
        /outputs\.serviceCatalog.*sha256/u,
    );
});

function validManifest() {
    const artifact = (status = 'approved') => ({
        status,
        path: 'input/artifact.json',
        sha256: 'a'.repeat(64),
        rows: 1,
    });
    const coverage = (rows) => ({ rows, sha256: 'b'.repeat(64) });

    return {
        schemaVersion: 1,
        releaseId: 'catalog-2026-08-13-001',
        datasetId: 'bgg-catalog-2026-08-13',
        approved: true,
        testOnly: false,
        approval: {
            reviewedBy: 'albam-mate-team',
            reviewedAt: '2026-08-13T00:00:00Z',
            references: ['https://github.com/bamsongi-club/albam-mate/issues/680'],
        },
        approvedFields: [
            'name',
            'english_name',
            'alias',
            'tag',
            'description',
            'detail_description',
        ],
        approvedProcessingScopes: [
            'service-load',
            'search-text-assembly',
            'embedding-generation',
        ],
        search_text: {
            fields: ['name', 'english_name', 'description', 'detail_description'],
            sourceFieldVersion: 'catalog-fields-v1',
            assemblyRuleVersion: 'search-text-v1',
        },
        embedding: {
            provider: 'test-provider',
            model: 'test-model',
            modelVersion: 'test-model-v1',
            dimensions: 3,
            indexVersion: 'search-04-test-v1',
            output: {
                path: 'output/catalog-embeddings.json',
                sha256: '7'.repeat(64),
                rows: 2,
            },
        },
        inputs: {
            catalog: artifact(),
            names: artifact(),
            descriptions: artifact(),
            mechanismDictionary: artifact(),
            themeDictionary: artifact(),
            relations: artifact(),
        },
        coverage: {
            catalogIds: coverage(170000),
            relationGameIds: coverage(170000),
            mechanismIds: coverage(189),
            themeIds: coverage(100),
        },
        sources: {
            games: {
                fileName: 'games.json',
                sha256: 'd'.repeat(64),
                rows: 2,
            },
            ranks: {
                fileName: 'ranks.csv',
                sha256: 'e'.repeat(64),
                rows: 2,
            },
        },
        provenance: {
            descriptionFields: {
                description: descriptionProvenance(),
                detail_description: descriptionProvenance(),
            },
        },
        outputs: {
            serviceCatalog: {
                path: 'service-catalog.json',
                sha256: 'f'.repeat(64),
                rows: 2,
            },
            upsertSql: {
                path: 'upsert-games.sql',
                sha256: 'f'.repeat(64),
                rows: 2,
            },
        },
    };
}

function descriptionProvenance() {
    return {
        source: 'approved test catalog',
        sourceVersion: 'test-description-v1',
        processing: 'human-reviewed',
        status: 'approved',
        reviewedBy: 'albam-mate-team',
        reviewedAt: '2026-08-13T00:00:00Z',
    };
}
