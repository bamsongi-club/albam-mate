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
        approved: true,
        testOnly: false,
        approval: {
            reviewedBy: 'albam-mate-team',
            reviewedAt: '2026-08-13T00:00:00Z',
            references: ['https://github.com/bamsongi-club/albam-mate/issues/680'],
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
    };
}
