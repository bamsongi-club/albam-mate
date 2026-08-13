import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
    existsSync,
    readFileSync,
    readdirSync,
    renameSync,
    rmSync,
    writeFileSync,
} from 'node:fs';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';
import { publishCatalogRelease } from './catalog-release-publisher.mjs';

test('모든 artifact와 output manifest를 versioned release로 publish한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-'));
    try {
        const result = publishCatalogRelease({
            outputRoot: root,
            manifest: validManifest('release-001'),
            artifacts: {
                'games.csv': { contents: 'bgg_id\n1\n', rows: 1 },
                'themes.csv': { contents: 'bgg_id,name_ko\n10,테마\n', rows: 1 },
            },
        });

        const releaseRoot = join(root, 'releases', 'release-001');
        assert.equal(result.releaseId, 'release-001');
        assert.equal(readFileSync(join(releaseRoot, 'games.csv'), 'utf8'), 'bgg_id\n1\n');
        assert.equal(readFileSync(join(root, 'current-release.json'), 'utf8').includes('release-001'), true);
        const outputManifest = JSON.parse(readFileSync(join(releaseRoot, 'release-manifest.json'), 'utf8'));
        assert.deepEqual(outputManifest.outputs['games.csv'].rows, 1);
        assert.equal(
            outputManifest.outputs['games.csv'].sha256,
            createHash('sha256').update('bgg_id\n1\n').digest('hex'),
        );
        assert.equal(outputManifest.outputs['games.csv'].bytes, Buffer.byteLength('bgg_id\n1\n'));
        assert.equal(outputManifest.inputs.catalog.sha256, manifestSha256());
        assert.equal(outputManifest.inputs.catalog.rows, 1);
        assert.deepEqual(readdirSync(join(root, 'releases')), ['release-001']);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('release 승격 직후 중단되어도 같은 승인 입력으로 publish를 재개한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-resume-'));
    try {
        const manifest = validManifest('release-001');
        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest,
                artifacts: { 'games.csv': { contents: 'interrupted', rows: 1 } },
                rename(source, target) {
                    renameSync(source, target);
                    if (target.endsWith('release-001')) throw new Error('simulated interruption');
                },
            }),
            /simulated interruption/u,
        );

        assert.equal(existsSync(join(root, 'releases', 'release-001')), true);
        const result = publishCatalogRelease({
            outputRoot: root,
            manifest,
            artifacts: { 'games.csv': { contents: 'interrupted', rows: 1 } },
        });

        assert.equal(result.releaseId, 'release-001');
        assert.equal(JSON.parse(readFileSync(join(root, 'current-release.json'), 'utf8')).releaseId, 'release-001');
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('완료된 current release는 멱등 재실행하고 과거 release는 pointer 롤백을 거부한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-pointer-order-'));
    try {
        const firstManifest = validManifest('release-001');
        const firstArtifacts = { 'games.csv': { contents: 'first', rows: 1 } };
        publishCatalogRelease({ outputRoot: root, manifest: firstManifest, artifacts: firstArtifacts });
        const currentAfterFirstPublish = readFileSync(join(root, 'current-release.json'), 'utf8');

        const retryResult = publishCatalogRelease({
            outputRoot: root,
            manifest: firstManifest,
            artifacts: firstArtifacts,
        });
        assert.equal(retryResult.releaseId, 'release-001');
        assert.equal(readFileSync(join(root, 'current-release.json'), 'utf8'), currentAfterFirstPublish);

        publishCatalogRelease({
            outputRoot: root,
            manifest: validManifest('release-002'),
            artifacts: { 'games.csv': { contents: 'second', rows: 1 } },
        });
        const currentBeforeHistoricalRetry = readFileSync(join(root, 'current-release.json'), 'utf8');

        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest: firstManifest,
                artifacts: firstArtifacts,
            }),
            /current release is release-002/u,
        );
        assert.equal(readFileSync(join(root, 'current-release.json'), 'utf8'), currentBeforeHistoricalRetry);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('손상된 중단 release는 artifact 삭제·변조 후 pointer 복구를 거부한다', () => {
    for (const [suffix, corrupt] of [
        ['deleted', (root) => rmSync(join(root, 'releases', 'release-001', 'games.csv'))],
        ['tampered', (root) => writeFileSync(join(root, 'releases', 'release-001', 'games.csv'), 'tampered')],
    ]) {
        const root = mkdtempSync(join(tmpdir(), `albam-catalog-release-corrupt-${suffix}-`));
        try {
            const manifest = validManifest('release-001');
            assert.throws(
                () => publishCatalogRelease({
                    outputRoot: root,
                    manifest,
                    artifacts: { 'games.csv': { contents: 'original', rows: 1 } },
                    rename(source, target) {
                        renameSync(source, target);
                        if (target.endsWith('release-001')) throw new Error('simulated interruption');
                    },
                }),
                /simulated interruption/u,
            );
            corrupt(root);

            assert.throws(
                () => publishCatalogRelease({
                    outputRoot: root,
                    manifest,
                    artifacts: { 'games.csv': { contents: 'original', rows: 1 } },
                }),
                /checksum mismatch|ENOENT/u,
            );
            assert.equal(existsSync(join(root, 'current-release.json')), false);
        } finally {
            rmSync(root, { recursive: true, force: true });
        }
    }
});

test('Windows 파일명 정규화 충돌과 예약 장치명을 차단한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-names-'));
    try {
        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest: validManifest('release-001'),
                artifacts: {
                    'games.csv': { contents: 'first', rows: 1 },
                    'GAMES.CSV': { contents: 'second', rows: 1 },
                },
            }),
            /collide/u,
        );
        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest: validManifest('release-002'),
                artifacts: { 'CON.txt': { contents: 'reserved', rows: 1 } },
            }),
            /Windows|reserved/u,
        );
        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest: validManifest('release-003'),
                artifacts: { 'games.csv ': { contents: 'trailing', rows: 1 } },
            }),
            /Windows|trailing/u,
        );
        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest: validManifest('release-004'),
                artifacts: { 'games.csv:payload': { contents: 'ads', rows: 1 } },
            }),
            /Windows|forbidden/u,
        );
        assert.equal(existsSync(join(root, 'releases', 'release-004')), false);
        assert.equal(existsSync(join(root, 'current-release.json')), false);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('__proto__ artifact도 output manifest에 무결성 정보가 기록된다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-prototype-'));
    try {
        const artifacts = Object.create(null);
        artifacts.__proto__ = { contents: 'prototype', rows: 1 };
        publishCatalogRelease({
            outputRoot: root,
            manifest: validManifest('release-001'),
            artifacts,
        });

        const outputManifest = JSON.parse(readFileSync(
            join(root, 'releases', 'release-001', 'release-manifest.json'),
            'utf8',
        ));
        assert.equal(outputManifest.outputs.__proto__.bytes, Buffer.byteLength('prototype'));
        assert.equal(existsSync(join(root, 'releases', 'release-001', '__proto__')), true);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('새 release 작성이 실패하면 기존 current release를 유지하고 staging을 남기지 않는다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-failure-'));
    try {
        publishCatalogRelease({
            outputRoot: root,
            manifest: validManifest('release-001'),
            artifacts: { 'games.csv': { contents: 'old', rows: 1 } },
        });
        const currentBefore = readFileSync(join(root, 'current-release.json'), 'utf8');

        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest: validManifest('release-002'),
                artifacts: {
                    'games.csv': { contents: 'new', rows: 1 },
                    'themes.csv': { contents: 'new', rows: 1 },
                },
                writeFile(path, contents, options) {
                    if (path.endsWith('themes.csv')) throw new Error('simulated artifact failure');
                    return writeFileSync(path, contents, options);
                },
            }),
            /simulated artifact failure/u,
        );

        assert.equal(readFileSync(join(root, 'current-release.json'), 'utf8'), currentBefore);
        assert.equal(existsSync(join(root, 'releases', 'release-002')), false);
        assert.deepEqual(readdirSync(join(root, 'releases')), ['release-001']);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('같은 release ID를 덮어쓰지 않는다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-immutable-'));
    try {
        const manifest = validManifest('release-001');
        publishCatalogRelease({
            outputRoot: root,
            manifest,
            artifacts: { 'games.csv': { contents: 'original', rows: 1 } },
        });

        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest,
                artifacts: { 'games.csv': { contents: 'replacement', rows: 1 } },
            }),
            /already exists/u,
        );
        assert.equal(
            readFileSync(join(root, 'releases', 'release-001', 'games.csv'), 'utf8'),
            'original',
        );
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('pointer 교체가 실패하면 새 release도 제거하고 기존 pointer를 유지한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-pointer-failure-'));
    try {
        publishCatalogRelease({
            outputRoot: root,
            manifest: validManifest('release-001'),
            artifacts: { 'games.csv': { contents: 'original', rows: 1 } },
        });
        const currentBefore = readFileSync(join(root, 'current-release.json'), 'utf8');

        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest: validManifest('release-002'),
                artifacts: { 'games.csv': { contents: 'new', rows: 1 } },
                rename(source, target) {
                    if (target.endsWith('current-release.json')) {
                        throw new Error('simulated pointer failure');
                    }
                    return renameSync(source, target);
                },
            }),
            /simulated pointer failure/u,
        );

        assert.equal(readFileSync(join(root, 'current-release.json'), 'utf8'), currentBefore);
        assert.equal(existsSync(join(root, 'releases', 'release-002')), false);
        assert.deepEqual(readdirSync(join(root, 'releases')), ['release-001']);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('pointer 임시 파일 쓰기가 실패하면 새 release와 임시 디렉터리를 제거한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-pointer-write-failure-'));
    try {
        publishCatalogRelease({
            outputRoot: root,
            manifest: validManifest('release-001'),
            artifacts: { 'games.csv': { contents: 'original', rows: 1 } },
        });
        const currentBefore = readFileSync(join(root, 'current-release.json'), 'utf8');

        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest: validManifest('release-002'),
                artifacts: { 'games.csv': { contents: 'new', rows: 1 } },
                writeFile(path, contents, options) {
                    if (path.endsWith('current-release.json')) throw new Error('simulated pointer write failure');
                    return writeFileSync(path, contents, options);
                },
            }),
            /simulated pointer write failure/u,
        );

        assert.equal(readFileSync(join(root, 'current-release.json'), 'utf8'), currentBefore);
        assert.equal(existsSync(join(root, 'releases', 'release-002')), false);
        assert.equal(readdirSync(root).some((name) => name.startsWith('.current-release-')), false);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

test('미승인 manifest는 release와 current pointer를 만들지 않는다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-catalog-release-unapproved-'));
    try {
        assert.throws(
            () => publishCatalogRelease({
                outputRoot: root,
                manifest: { ...validManifest('release-001'), approved: false },
                artifacts: { 'games.csv': { contents: 'blocked', rows: 1 } },
            }),
            /approved/u,
        );
        assert.equal(existsSync(join(root, 'releases')), false);
        assert.equal(existsSync(join(root, 'current-release.json')), false);
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
});

function validManifest(releaseId) {
    const artifact = { status: 'approved', path: 'input.json', sha256: 'a'.repeat(64), rows: 1 };
    const coverage = { rows: 1, sha256: 'b'.repeat(64) };
    return {
        schemaVersion: 1,
        releaseId,
        approved: true,
        testOnly: false,
        approval: {
            reviewedBy: 'albam-mate-team',
            reviewedAt: '2026-08-13T00:00:00Z',
            references: ['https://github.com/bamsongi-club/albam-mate/issues/680'],
        },
        inputs: {
            catalog: artifact,
            names: artifact,
            descriptions: artifact,
            mechanismDictionary: artifact,
            themeDictionary: artifact,
            relations: artifact,
        },
        coverage: {
            catalogIds: coverage,
            relationGameIds: coverage,
            mechanismIds: coverage,
            themeIds: coverage,
        },
    };
}

function manifestSha256() {
    return 'a'.repeat(64);
}
