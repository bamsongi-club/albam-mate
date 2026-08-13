import assert from 'node:assert/strict';
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
        assert.match(outputManifest.outputs['games.csv'].sha256, /^[a-f0-9]{64}$/u);
        assert.deepEqual(readdirSync(join(root, 'releases')), ['release-001']);
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
