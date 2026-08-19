import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { linkSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
    buildBaselineResults,
    canonicalJson,
    parseSearchText,
    rankQuery,
    scoreCandidate,
    sha256,
    TRUSTED_EVALUATION_MANIFEST_SHA256,
    TRUSTED_INPUT_DESCRIPTOR_SHA256,
} from './lexical-sparse-baseline.mjs';
import { loadManifest } from '../p2-search-evaluation.mjs';

const TEST_FILE = fileURLToPath(import.meta.url);
const REPOSITORY_ROOT = path.resolve(path.dirname(TEST_FILE), '../..');
const MANIFEST_PATH = path.join(REPOSITORY_ROOT, 'docs/p2/search-evaluation/manifest.json');
const DATASET_MANIFEST_PATH = path.join(REPOSITORY_ROOT, 'docs/game-catalog/catalog-dataset-release.json');
const INPUT_DESCRIPTOR_PATH = path.join(
    REPOSITORY_ROOT,
    'docs/p2/search-evaluation/lexical-sparse-baseline-input.json',
);
const SCRIPT_PATH = path.join(path.dirname(TEST_FILE), 'lexical-sparse-baseline.mjs');

function fixture() {
    const manifest = loadManifest(MANIFEST_PATH);
    const datasetManifestBytes = readFileSync(DATASET_MANIFEST_PATH);
    const qualityCorpusBytes = readFileSync(path.join(
        REPOSITORY_ROOT,
        'docs/p2/search-evaluation/quality-corpus.json',
    ));
    const games = manifest.qualityCorpus.members.map((member) => ({
        gameId: member.gameId,
        searchText: searchTextFor(member.gameId),
    }));
    const searchTextArtifact = {
            schemaVersion: 1,
            kind: 'poc-search-text',
            datasetRelease: {
                releaseId: manifest.catalog.releaseId,
                datasetId: manifest.catalog.datasetId,
                manifestSha256: sha256(datasetManifestBytes),
            },
            corpus: {
                releaseId: manifest.qualityCorpus.releaseId,
                corpusVersion: manifest.qualityCorpus.corpusVersion,
            },
            approvedFields: ['name', 'englishName', 'alias', 'mechanism', 'category', 'theme', 'description', 'detailDescription'],
            gameCount: games.length,
            searchTextSha256: sha256(Buffer.from(`${JSON.stringify(games)}\n`, 'utf8')),
            games,
    };
    const pocManifest = {
        schemaVersion: 1,
        kind: 'poc-search-text-execution',
        approved: true,
        testOnly: false,
        datasetRelease: {
            manifestPath: 'catalog-dataset-release.json',
            releaseId: manifest.catalog.releaseId,
            datasetId: manifest.catalog.datasetId,
            manifestSha256: sha256(datasetManifestBytes),
        },
        approvedFields: ['name', 'englishName', 'alias', 'mechanism', 'category', 'theme', 'description', 'detailDescription'],
        approvedProcessingScopes: ['search-text-assembly'],
        corpus: {
            path: 'quality-corpus.json',
            sha256: sha256(qualityCorpusBytes),
        },
    };
    const pocManifestBytes = Buffer.from(`${JSON.stringify(pocManifest, null, 2)}\n`, 'utf8');
    const searchTextBytes = Buffer.from(`${JSON.stringify(searchTextArtifact, null, 2)}\n`, 'utf8');
    const inputDescriptor = {
        schemaVersion: 1,
        kind: 'search-04-baseline-input',
        upstreamPullRequest: 861,
        datasetRelease: {
            releaseId: manifest.catalog.releaseId,
            datasetId: manifest.catalog.datasetId,
            manifestSha256: sha256(datasetManifestBytes),
        },
        qualityCorpus: {
            releaseId: manifest.qualityCorpus.releaseId,
            corpusVersion: manifest.qualityCorpus.corpusVersion,
            sha256: manifest.qualityCorpusSha256,
        },
        approvedFields: pocManifest.approvedFields,
        pocManifest: {
            reference: 'test://poc-search-text-manifest.json',
            sha256: sha256(pocManifestBytes),
        },
        searchTextArtifact: {
            reference: 'test://search-text.json',
            sha256: sha256(searchTextBytes),
            gamesSha256: searchTextArtifact.searchTextSha256,
            gameCount: searchTextArtifact.gameCount,
        },
    };
    return { manifest, searchTextArtifact, inputDescriptor, pocManifest, pocManifestBytes, searchTextBytes };
}

function runBaseline(data, mode) {
    return buildBaselineResults({
        mode,
        manifest: data.manifest,
        searchTextArtifact: data.searchTextArtifact,
        manifestPath: MANIFEST_PATH,
        inputDescriptor: data.inputDescriptor,
        searchTextBytes: data.searchTextBytes,
        pocManifest: data.pocManifest,
        pocManifestBytes: data.pocManifestBytes,
    });
}

function searchTextFor(gameId) {
    if (gameId === 284083) return '게임명: 스페이스크루\n메커니즘: 트릭테이킹, 협력';
    if (gameId === 324856) return '영문명: Deep Sea Crew';
    if (gameId === 36811) return '게임명: 빠른 일꾼\n메커니즘: 일꾼 놓기';
    if (gameId === 999999) return '게임명: 테스트';
    return `게임명: 게임 ${gameId}`;
}

test('T1: 같은 pinned 입력의 lexical baseline은 결정적 ranked ID와 checksum을 만든다', () => {
    const data = fixture();
    const first = runBaseline(data, 'lexical');
    const second = runBaseline(data, 'lexical');

    assert.deepEqual(first, second);
    assert.equal(first['Q-004'].rankedGameIds[0], 284083);
    assert.equal(first['Q-005'].rankedGameIds[0], 324856);
    assert.equal(
        sha256(Buffer.from(canonicalJson(first), 'utf8')),
        sha256(Buffer.from(canonicalJson(second), 'utf8')),
    );

    const tieBreak = rankQuery({
        mode: 'lexical',
        query: { query: '동점' },
        games: [
            { gameId: 2, searchText: '게임명: 동점' },
            { gameId: 1, searchText: '게임명: 동점' },
        ],
        corpusById: new Map([
            [1, { gameId: 1, minPlayers: 1, maxPlayers: 2, maxPlayTimeMinutes: 30 }],
            [2, { gameId: 2, minPlayers: 1, maxPlayers: 2, maxPlayTimeMinutes: 30 }],
        ]),
    });
    assert.deepEqual(tieBreak.rankedGameIds, [1, 2]);
});

test('T2: Sparse baseline은 메커니즘·카테고리·테마 field만 신호로 사용한다', () => {
    const data = fixture();
    const results = runBaseline(data, 'sparse');

    assert.equal(results['Q-001'].rankedGameIds[0], 284083);
    assert.equal(results['Q-003'].rankedGameIds[0], 36811);

    const descriptionOnly = parseSearchText('게임명: 무관한 게임\n설명: 트릭테이킹 협력');
    assert.equal(scoreCandidate({ mode: 'sparse', query: { query: '트릭테이킹 협력' }, fields: descriptionOnly }), 0);

    const nameOnly = parseSearchText('게임명: 트릭테이킹 협력');
    assert.equal(scoreCandidate({ mode: 'sparse', query: { query: '트릭테이킹 협력' }, fields: nameOnly }), 0);
    const metadataOnly = parseSearchText('메커니즘: 트릭테이킹, 협력');
    assert.ok(scoreCandidate({ mode: 'sparse', query: { query: '트릭테이킹 협력' }, fields: metadataOnly }) > 0);

    const multilineDescription = parseSearchText('설명: 첫 문단입니다.\n두 번째 줄입니다.\n\nHistory: 세 번째 줄입니다.');
    assert.equal(multilineDescription.description[0], '첫 문단입니다.\n두 번째 줄입니다.\nHistory: 세 번째 줄입니다.');

    assert.throws(
        () => parseSearchText('설명: 승인되지 않은 값', {
            approvedFields: data.inputDescriptor.approvedFields.filter(
                (field) => !['description', 'detailDescription'].includes(field),
            ),
        }),
        /approvedFields/u,
    );
    assert.throws(
        () => parseSearchText('게임명: 허용값\n비공개필드: 값'),
        /승인되지 않은/u,
    );
    assert.throws(() => parseSearchText('계속되는 값'), /승인되지 않은/u);
});

test('T3: lexical과 Sparse 결과는 동일 query ID·ranked game ID 중심 형식과 byte 결과를 사용한다', () => {
    const data = fixture();
    const lexical = runBaseline(data, 'lexical');
    const sparse = runBaseline(data, 'sparse');
    const { manifest } = data;
    const expectedQueryIds = manifest.queries.map((query) => query.id).sort();

    assert.deepEqual(Object.keys(lexical), expectedQueryIds);
    assert.deepEqual(Object.keys(sparse), expectedQueryIds);
    for (const results of [lexical, sparse]) {
        for (const result of Object.values(results)) {
            assert.deepEqual(Object.keys(result).sort(), ['hardFilterViolationGameIds', 'rankedGameIds']);
            assert.equal(new Set(result.rankedGameIds).size, result.rankedGameIds.length);
        }
    }
    assert.equal(canonicalJson(lexical), canonicalJson(runBaseline(data, 'lexical')));
    assert.equal(canonicalJson(sparse), canonicalJson(runBaseline(data, 'sparse')));
    assert.equal(
        sha256(Buffer.from(canonicalJson(sparse), 'utf8')),
        sha256(Buffer.from(canonicalJson(runBaseline(data, 'sparse')), 'utf8')),
    );
});

test('T4: 숫자·시간 hard filter는 점수 신호와 분리되고 결과에서만 적용된다', () => {
    const fields = parseSearchText('게임명: 스페이스크루\n메커니즘: 협력');
    const withoutFilter = scoreCandidate({ mode: 'lexical', query: { query: '스페이스크루' }, fields });
    const withFilter = scoreCandidate({
        mode: 'lexical',
        query: { query: '스페이스크루 4인 30분 이하', hardFilters: { minPlayers: 4, maxPlayTimeMinutes: 30 } },
        fields,
    });
    assert.equal(withFilter, withoutFilter);

    const hardFilterWords = parseSearchText('게임명: 최대');
    assert.equal(
        scoreCandidate({ mode: 'lexical', query: { query: '최대 4인' }, fields: hardFilterWords }),
        0,
    );
    const complexityWords = parseSearchText('게임명: 쉽게');
    assert.equal(
        scoreCandidate({ mode: 'lexical', query: { query: '초보여도 쉽게' }, fields: complexityWords }),
        0,
    );

    const results = rankQuery({
        mode: 'lexical',
        query: { query: '스페이스크루 4인', hardFilters: { minPlayers: 4 } },
        games: [
            { gameId: 1, searchText: '게임명: 스페이스크루' },
            { gameId: 2, searchText: '게임명: 다른 게임' },
        ],
        corpusById: new Map([
            [1, { gameId: 1, minPlayers: 1, maxPlayers: 3, maxPlayTimeMinutes: 45 }],
            [2, { gameId: 2, minPlayers: 4, maxPlayers: 4, maxPlayTimeMinutes: 20 }],
        ]),
    });
    assert.deepEqual(results.rankedGameIds, [2]);
    assert.deepEqual(results.hardFilterViolationGameIds, []);

    const timeResults = rankQuery({
        mode: 'lexical',
        query: { query: '스페이스크루 30분 이하', hardFilters: { maxPlayTimeMinutes: 30 } },
        games: [
            { gameId: 1, searchText: '게임명: 스페이스크루' },
            { gameId: 2, searchText: '게임명: 다른 게임' },
        ],
        corpusById: new Map([
            [1, { gameId: 1, minPlayers: 1, maxPlayers: 4, maxPlayTimeMinutes: 31 }],
            [2, { gameId: 2, minPlayers: 1, maxPlayers: 4, maxPlayTimeMinutes: 30 }],
        ]),
    });
    assert.deepEqual(timeResults.rankedGameIds, [2]);

    const maxPlayersResults = rankQuery({
        mode: 'lexical',
        query: { query: '스페이스크루 4인', hardFilters: { maxPlayers: 4 } },
        games: [
            { gameId: 1, searchText: '게임명: 스페이스크루' },
            { gameId: 2, searchText: '게임명: 다른 게임' },
        ],
        corpusById: new Map([
            [1, { gameId: 1, minPlayers: 5, maxPlayers: 6, maxPlayTimeMinutes: 30 }],
            [2, { gameId: 2, minPlayers: 4, maxPlayers: 4, maxPlayTimeMinutes: 30 }],
        ]),
    });
    assert.deepEqual(maxPlayersResults.rankedGameIds, [2]);
});

test('승인 release·corpus·search_text checksum이 바뀌면 baseline 실행을 거절한다', () => {
    const data = fixture();
    const { manifest, searchTextArtifact } = data;
    const tampered = { ...searchTextArtifact, searchTextSha256: '0'.repeat(64) };

    assert.throws(
        () => runBaseline({ ...data, searchTextArtifact: tampered }, 'lexical'),
        /searchTextSha256/u,
    );

    const games = searchTextArtifact.games.map((game, index) => index === 0
        ? { ...game, searchText: '게임명: 변조된 게임' }
        : game);
    const tamperedGames = {
        ...searchTextArtifact,
        games,
        searchTextSha256: sha256(Buffer.from(`${JSON.stringify(games)}\n`, 'utf8')),
    };
    const tamperedBytes = Buffer.from(`${JSON.stringify(tamperedGames, null, 2)}\n`, 'utf8');
    assert.throws(
        () => runBaseline({
            ...data,
            searchTextArtifact: tamperedGames,
            searchTextBytes: tamperedBytes,
        }, 'lexical'),
        /입력 descriptor/u,
    );
});

test('커밋된 baseline input descriptor가 trust anchor checksum으로 고정되어 있다', () => {
    const descriptorBytes = readFileSync(INPUT_DESCRIPTOR_PATH);
    const descriptor = JSON.parse(descriptorBytes.toString('utf8'));

    assert.equal(sha256(descriptorBytes), TRUSTED_INPUT_DESCRIPTOR_SHA256);
    assert.equal(descriptor.kind, 'search-04-baseline-input');
    assert.equal(descriptor.upstreamPullRequest, 861);
    assert.equal(descriptor.searchTextArtifact.gameCount, 1000);
});

test('커밋된 evaluation manifest가 trust anchor checksum으로 고정되어 있다', () => {
    const manifestBytes = readFileSync(MANIFEST_PATH);

    assert.equal(
        sha256(manifestBytes),
        'e604e12740730aa9cb713e4b3db34f5ce311bcfff0db651da463a81f997329d4',
    );
    assert.equal(TRUSTED_EVALUATION_MANIFEST_SHA256, 'e604e12740730aa9cb713e4b3db34f5ce311bcfff0db651da463a81f997329d4');
});

test('CLI는 커밋된 고정 descriptor 외의 입력을 거절한다', () => {
    const { inputDescriptor, pocManifest, searchTextArtifact } = fixture();
    const directory = mkdtempSync(path.join(tmpdir(), 'search-04-baseline-'));
    const inputDescriptorPath = path.join(directory, 'input-descriptor.json');
    const pocManifestPath = path.join(directory, 'poc-search-text-manifest.json');
    const searchTextPath = path.join(directory, 'search-text.json');
    const outputPath = path.join(directory, 'lexical-results.json');
    try {
        writeFileSync(inputDescriptorPath, `${JSON.stringify(inputDescriptor, null, 2)}\n`, 'utf8');
        writeFileSync(pocManifestPath, `${JSON.stringify(pocManifest, null, 2)}\n`, 'utf8');
        writeFileSync(searchTextPath, `${JSON.stringify(searchTextArtifact, null, 2)}\n`, 'utf8');
        assert.throws(
            () => execFileSync(process.execPath, [
                SCRIPT_PATH,
                '--mode', 'lexical',
                '--manifest', MANIFEST_PATH,
                '--input-descriptor', inputDescriptorPath,
                '--poc-manifest', pocManifestPath,
                '--search-text', searchTextPath,
                '--out', outputPath,
            ], { cwd: REPOSITORY_ROOT, encoding: 'utf8' }),
            (error) => error.status === 1 && error.stderr.includes('고정 경로'),
        );
    } finally {
        rmSync(directory, { recursive: true, force: true });
    }
});

test('CLI는 manifest의 간접 입력을 --out으로 덮어쓰지 않는다', () => {
    const directory = mkdtempSync(path.join(tmpdir(), 'search-04-baseline-output-'));
    const pocManifestPath = path.join(directory, 'poc-search-text-manifest.json');
    const searchTextPath = path.join(directory, 'search-text.json');
    const hardLinkOutputPath = path.join(directory, 'catalog-hard-link.json');
    try {
        writeFileSync(pocManifestPath, '{}\n', 'utf8');
        writeFileSync(searchTextPath, '{}\n', 'utf8');
        linkSync(DATASET_MANIFEST_PATH, hardLinkOutputPath);
        assert.throws(
            () => execFileSync(process.execPath, [
                SCRIPT_PATH,
                '--mode', 'lexical',
                '--manifest', MANIFEST_PATH,
                '--input-descriptor', INPUT_DESCRIPTOR_PATH,
                '--poc-manifest', pocManifestPath,
                '--search-text', searchTextPath,
                '--out', hardLinkOutputPath,
            ], { cwd: REPOSITORY_ROOT, encoding: 'utf8' }),
            (error) => error.status === 1 && error.stderr.includes('입력 파일을 덮어쓸 수 없습니다'),
        );
    } finally {
        rmSync(directory, { recursive: true, force: true });
    }
});
