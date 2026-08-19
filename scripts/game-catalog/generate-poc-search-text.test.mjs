import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import {
    mkdtempSync,
    mkdirSync,
    readFileSync,
    rmSync,
    writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

const SCRIPT = new URL('./generate-poc-search-text.mjs', import.meta.url);
const DATASET_SOURCE = new URL('../../docs/game-catalog/catalog-dataset-release.json', import.meta.url);
const TEMPLATE = '게임명: {name}\n영문명: {englishName}\n메커니즘: {mechanisms}\n카테고리: {categories}\n테마: {themes}\n설명: {description}';

function sha256(contents) {
    return createHash('sha256').update(contents).digest('hex');
}

function withFixture(run) {
    const root = mkdtempSync(join(tmpdir(), 'poc-search-text-'));
    try {
        const artifactsRoot = join(root, 'artifacts');
        mkdirSync(artifactsRoot);
        const gamesSql = buildGamesSql();
        const metadataSql = buildMetadataSql();
        writeFileSync(join(artifactsRoot, '01-games-full.sql'), gamesSql);
        writeFileSync(join(artifactsRoot, '02-metadata-full.sql'), metadataSql);

        const corpus = buildCorpus();
        const corpusPath = join(root, 'quality-corpus.json');
        const corpusContents = Buffer.from(`${JSON.stringify(corpus, null, 2)}\n`, 'utf8');
        writeFileSync(corpusPath, corpusContents);

        const datasetManifest = JSON.parse(readFileSync(DATASET_SOURCE, 'utf8'));
        datasetManifest.artifacts = {
            '01': artifactEntry('01-games-full.sql', gamesSql),
            '02': artifactEntry('02-metadata-full.sql', metadataSql),
        };
        const datasetPath = join(root, 'catalog-dataset-release.json');
        const datasetContents = Buffer.from(`${JSON.stringify(datasetManifest, null, 2)}\n`, 'utf8');
        writeFileSync(datasetPath, datasetContents);

        const manifest = {
            schemaVersion: 1,
            kind: 'poc-search-text-execution',
            approved: true,
            testOnly: false,
            datasetRelease: {
                manifestPath: 'catalog-dataset-release.json',
                releaseId: datasetManifest.releaseId,
                datasetId: datasetManifest.datasetId,
                manifestSha256: sha256(datasetContents),
            },
            approvedFields: ['name', 'englishName', 'alias', 'description', 'detailDescription', 'category', 'theme', 'mechanism'],
            approvedProcessingScopes: ['search-text-assembly'],
            corpus: { path: 'quality-corpus.json', sha256: sha256(corpusContents) },
            searchTextTemplate: TEMPLATE,
        };
        const manifestPath = join(root, 'poc-manifest.json');
        writeFileSync(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`);
        return run({ root, artifactsRoot, corpusPath, datasetPath, datasetContents, manifestPath, manifest, corpus });
    } finally {
        rmSync(root, { recursive: true, force: true });
    }
}

function artifactEntry(path, contents) {
    const bytes = Buffer.byteLength(contents);
    return { status: 'approved', path, sha256: sha256(contents), bytes };
}

function buildCorpus() {
    return {
        schemaVersion: 1,
        corpusId: 'boardlife-quality-top1000',
        corpusVersion: 'boardlife-quality-top1000-v2',
        status: 'approved',
        releaseStatus: 'approved',
        rankCutoff: 1000,
        selection: { targetSize: 1000, memberCount: 1000 },
        members: Array.from({ length: 1000 }, (_, index) => ({ gameId: index + 1, boardlifeRank: index + 1, minPlayers: 99 })),
    };
}

function buildGamesSql() {
    const rows = Array.from({ length: 999 }, (_, index) => {
        const id = index + 1;
        if (id === 1) {
            return "(1, '디 마허', 'Die Macher', '디 마허', '3~5명', '240분', '짧은 설명', '자세한 설명')";
        }
        return `(${id}, '게임 ${id}', NULL, NULL, '2~4명', '30분', NULL, NULL)`;
    });
    return [
        `INSERT INTO games (bgg_id, name, english_name, alias, supported_player_count, estimated_play_time, description, detail_description) VALUES\n${rows.join(',\n')};`,
        "INSERT INTO games (detail_description, name, bgg_id, description) VALUES ('두번째 상세 설명', '두번째 INSERT 게임', 1000, '두번째 설명');",
    ].join('\n');
}

function buildMetadataSql() {
    return [
        "INSERT INTO game_mechanisms (bgg_mechanism_id, name_ko) VALUES (10, '액션 포인트');",
        'INSERT INTO game_mechanism_relation_source (bgg_id, bgg_mechanism_id) VALUES (1, 10);',
        "insert into game_themes(bgg_theme_id,name_ko) values (20,'정치');",
        'with desired(bgg_id,bgg_theme_id) as (values (1,20)) insert into game_theme_relations select * from desired;',
        "insert into game_categories(code,name_ko) values ('STRATEGY','전략');",
        "with desired(bgg_id,code) as (values (1,'STRATEGY')) insert into game_category_relations select * from desired;",
    ].join('\n');
}

function runCli({ manifestPath, artifactsRoot, corpusPath, out }) {
    return execFileSync(process.execPath, [SCRIPT.pathname, '--manifest', manifestPath, '--artifacts-root', artifactsRoot, '--corpus', corpusPath, '--out', out], {
        encoding: 'utf8',
        stdio: ['ignore', 'pipe', 'pipe'],
    });
}

test('T2: CLI는 승인 v4 release·01/02 artifact·Top 1000 corpus를 검증한 뒤 허용 필드만 조립한다', () => withFixture((fixture) => {
    const out = join(fixture.root, 'search-text.json');
    runCli({ ...fixture, out });
    const result = JSON.parse(readFileSync(out, 'utf8'));
    assert.equal(result.gameCount, 1000);
    assert.equal(result.datasetRelease.releaseId, 'bgg-catalog-170k-v4-2026-08-19');
    assert.equal(result.games[0].searchText, ['게임명: 디 마허', '영문명: Die Macher', '메커니즘: 액션 포인트', '카테고리: 전략', '테마: 정치', '설명: 짧은 설명, 자세한 설명'].join('\n'));
    assert.ok(!result.games[0].searchText.includes('3~5명'));
    assert.ok(!result.games[0].searchText.includes('240분'));
    assert.equal(result.games.at(-1).searchText, '게임명: 두번째 INSERT 게임\n설명: 두번째 설명, 두번째 상세 설명');
}));

test('T3: 동일 입력은 바이트 동일한 search-text.json과 checksum을 만든다', () => withFixture((fixture) => {
    const first = join(fixture.root, 'first.json');
    const second = join(fixture.root, 'second.json');
    runCli({ ...fixture, out: first });
    runCli({ ...fixture, out: second });
    const firstContents = readFileSync(first);
    const secondContents = readFileSync(second);
    assert.deepEqual(firstContents, secondContents);
    assert.equal(JSON.parse(firstContents).searchTextSha256, JSON.parse(secondContents).searchTextSha256);
}));

test('T4: allowlist 밖 minPlayers와 변조된 release·artifact·corpus는 생성 전에 거절한다', () => withFixture((fixture) => {
    const out = join(fixture.root, 'search-text.json');
    fixture.manifest.approvedFields.push('minPlayers');
    writeFileSync(fixture.manifestPath, `${JSON.stringify(fixture.manifest, null, 2)}\n`);
    assert.throws(() => runCli({ ...fixture, out }), /trusted profile/u);
    assert.equal(false, readFileExists(out));

    fixture.manifest.approvedFields.pop();
    const tamperedRelease = JSON.parse(fixture.datasetContents);
    tamperedRelease.approved = false;
    const tamperedReleaseContents = Buffer.from(`${JSON.stringify(tamperedRelease, null, 2)}\n`, 'utf8');
    writeFileSync(fixture.datasetPath, tamperedReleaseContents);
    fixture.manifest.datasetRelease.manifestSha256 = sha256(tamperedReleaseContents);
    writeFileSync(fixture.manifestPath, `${JSON.stringify(fixture.manifest, null, 2)}\n`);
    assert.throws(() => runCli({ ...fixture, out }), /approved/u);

    writeFileSync(fixture.datasetPath, fixture.datasetContents);
    fixture.manifest.datasetRelease.manifestSha256 = sha256(fixture.datasetContents);
    writeFileSync(fixture.manifestPath, `${JSON.stringify(fixture.manifest, null, 2)}\n`);
    writeFileSync(join(fixture.artifactsRoot, '01-games-full.sql'), 'tampered');
    assert.throws(() => runCli({ ...fixture, out }), /sha256|bytes/u);

    writeFileSync(join(fixture.artifactsRoot, '01-games-full.sql'), buildGamesSql());
    writeFileSync(fixture.corpusPath, '{}\n');
    assert.throws(() => runCli({ ...fixture, out }), /corpus\.sha256/u);
}));

function readFileExists(path) {
    try {
        readFileSync(path);
        return true;
    } catch {
        return false;
    }
}
