import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import {
    existsSync,
    linkSync,
    mkdtempSync,
    readFileSync,
    rmSync,
    writeFileSync,
} from 'node:fs';
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
    writeOutputAtomically,
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
const FIXTURE_ROOT = path.join(REPOSITORY_ROOT, 'docs/p2/search-evaluation/lexical-sparse/fixtures');
const POC_MANIFEST_PATH = path.join(FIXTURE_ROOT, 'poc-search-text-manifest.json');
const SEARCH_TEXT_PATH = path.join(FIXTURE_ROOT, 'search-text-top1000.json');
const OUTPUT_ROOT = path.join(REPOSITORY_ROOT, 'docs/p2/search-evaluation/lexical-sparse/outputs');
const EVIDENCE_PATH = path.join(REPOSITORY_ROOT, 'docs/p2/search-evaluation/lexical-sparse/baseline-evidence.json');
const TRUSTED_POC_MANIFEST_SHA256 = 'b9793fa757c953c3bbc7724f665160874274111b65fbec7bba9d06f63c854f40';
const TRUSTED_SEARCH_TEXT_SHA256 = 'ec364be3a34268d1bb6d27e3c41e2cdd31852565eec79fa31faaacda17af4ece';
const TRUSTED_SEARCH_TEXT_GAMES_SHA256 = '03aa685a5828208f53912a0507d45f1b4db191eeb4e63d76d9e2cde9b890049f';
const TRUSTED_EVIDENCE_SHA256 = 'c18613b86b2788a123ced8a4e3fd37bdf437721526e8a297d88e34e3662da987';
const TRUSTED_RESULT_SHA256 = Object.freeze({
    lexical: 'b20965faf427ad56584323831b6a8588e2ce3576621abc739bbae06360c4ea07',
    sparse: '5bbf261860cf9141065111b5713bc1365691d4518c9169d75f7a8dc249c92d67',
});
const SCRIPT_PATH = path.join(path.dirname(TEST_FILE), 'lexical-sparse-baseline.mjs');

function fixture() {
    const manifest = loadManifest(MANIFEST_PATH);
    const inputDescriptorBytes = readFileSync(INPUT_DESCRIPTOR_PATH);
    const inputDescriptor = JSON.parse(inputDescriptorBytes.toString('utf8'));
    const pocManifestBytes = readFileSync(POC_MANIFEST_PATH);
    const pocManifest = JSON.parse(pocManifestBytes.toString('utf8'));
    const searchTextBytes = readFileSync(SEARCH_TEXT_PATH);
    const searchTextArtifact = JSON.parse(searchTextBytes.toString('utf8'));
    return {
        manifest,
        inputDescriptor,
        inputDescriptorBytes,
        pocManifest,
        pocManifestBytes,
        searchTextArtifact,
        searchTextBytes,
    };
}

function expectedResults(mode) {
    return JSON.parse(readFileSync(path.join(OUTPUT_ROOT, `${mode}-results.json`), 'utf8'));
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

test('T1: 같은 pinned 입력의 lexical baseline은 결정적 ranked ID와 checksum을 만든다', () => {
    const data = fixture();
    const first = runBaseline(data, 'lexical');
    const second = runBaseline(data, 'lexical');

    assert.deepEqual(first, second);
    assert.deepEqual(first, expectedResults('lexical'));
    assert.equal(Object.keys(first).length, 15);
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

test('명시한 semantic query fixture로 baseline 결과를 만들 수 있다', () => {
    const data = fixture();
    const customQuery = {
        ...data.manifest.queries[0],
        id: 'Q-CUSTOM',
        query: '의미 기반 협력 게임',
        hardFilters: {},
    };
    const results = buildBaselineResults({
        ...data,
        mode: 'lexical',
        manifestPath: MANIFEST_PATH,
        queries: [customQuery],
    });

    assert.deepEqual(Object.keys(results), ['Q-CUSTOM']);
});

test('T2: Sparse baseline은 메커니즘·카테고리·테마 field만 신호로 사용한다', () => {
    const data = fixture();
    const results = runBaseline(data, 'sparse');

    assert.deepEqual(results, expectedResults('sparse'));

    const descriptionOnly = parseSearchText('게임명: 무관한 게임\n설명: 트릭테이킹 협력');
    assert.equal(scoreCandidate({ mode: 'sparse', query: { query: '트릭테이킹 협력' }, fields: descriptionOnly }), 0);

    const nameOnly = parseSearchText('게임명: 트릭테이킹 협력');
    assert.equal(scoreCandidate({ mode: 'sparse', query: { query: '트릭테이킹 협력' }, fields: nameOnly }), 0);
    const metadataOnly = parseSearchText('메커니즘: 트릭테이킹, 협력');
    assert.ok(scoreCandidate({ mode: 'sparse', query: { query: '트릭테이킹 협력' }, fields: metadataOnly }) > 0);

    const metadataSequence = parseSearchText('메커니즘: 협력 게임');
    assert.equal(scoreCandidate({ mode: 'sparse', query: { query: '협력' }, fields: metadataSequence }), 0);
    assert.ok(scoreCandidate({ mode: 'sparse', query: { query: '협력 게임' }, fields: metadataSequence }) > 0);

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
    assert.deepEqual(lexical, expectedResults('lexical'));
    assert.deepEqual(sparse, expectedResults('sparse'));
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

    const unknownTimeResults = rankQuery({
        mode: 'lexical',
        query: { query: '스페이스크루 30분 이하', hardFilters: { maxPlayTimeMinutes: 30 } },
        games: [
            { gameId: 1, searchText: '게임명: 스페이스크루' },
        ],
        corpusById: new Map([
            [1, { gameId: 1, minPlayers: 1, maxPlayers: 4, maxPlayTimeMinutes: null }],
        ]),
    });
    assert.deepEqual(unknownTimeResults.rankedGameIds, []);

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
    const data = fixture();
    const descriptor = data.inputDescriptor;

    assert.equal(sha256(data.inputDescriptorBytes), TRUSTED_INPUT_DESCRIPTOR_SHA256);
    assert.equal(descriptor.kind, 'search-04-baseline-input');
    assert.equal(descriptor.upstreamPullRequest, 861);
    assert.equal(descriptor.searchTextArtifact.gameCount, 1000);
    assert.equal(sha256(data.pocManifestBytes), TRUSTED_POC_MANIFEST_SHA256);
    assert.equal(sha256(data.searchTextBytes), TRUSTED_SEARCH_TEXT_SHA256);
    assert.equal(
        sha256(Buffer.from(`${JSON.stringify(data.searchTextArtifact.games)}\n`, 'utf8')),
        TRUSTED_SEARCH_TEXT_GAMES_SHA256,
    );
    assert.equal(descriptor.pocManifest.sha256, TRUSTED_POC_MANIFEST_SHA256);
    assert.equal(descriptor.searchTextArtifact.sha256, TRUSTED_SEARCH_TEXT_SHA256);
    assert.equal(descriptor.searchTextArtifact.gamesSha256, TRUSTED_SEARCH_TEXT_GAMES_SHA256);
});

test('커밋된 evaluation manifest가 trust anchor checksum으로 고정되어 있다', () => {
    const manifestBytes = readFileSync(MANIFEST_PATH);

    assert.equal(
        sha256(manifestBytes),
        'e604e12740730aa9cb713e4b3db34f5ce311bcfff0db651da463a81f997329d4',
    );
    assert.equal(TRUSTED_EVALUATION_MANIFEST_SHA256, 'e604e12740730aa9cb713e4b3db34f5ce311bcfff0db651da463a81f997329d4');
});

test('baseline evidence receipt가 실제 15-query 결과 artifact와 일치한다', () => {
    const evidenceBytes = readFileSync(EVIDENCE_PATH);
    const evidence = JSON.parse(evidenceBytes.toString('utf8'));

    assert.equal(sha256(evidenceBytes), TRUSTED_EVIDENCE_SHA256);
    for (const mode of ['lexical', 'sparse']) {
        const resultBytes = readFileSync(path.join(OUTPUT_ROOT, `${mode}-results.json`));
        assert.equal(sha256(resultBytes), TRUSTED_RESULT_SHA256[mode]);
        assert.equal(evidence.outputs[mode].sha256, TRUSTED_RESULT_SHA256[mode]);
        assert.equal(evidence.outputs[mode].queryCount, Object.keys(JSON.parse(resultBytes)).length);
    }
});

test('CLI는 실제 승인 artifact로 15개 query의 커밋 결과와 evidence checksum을 재현한다', () => {
    const directory = mkdtempSync(path.join(tmpdir(), 'search-04-baseline-cli-'));
    const evidence = JSON.parse(readFileSync(EVIDENCE_PATH, 'utf8'));
    try {
        for (const mode of ['lexical', 'sparse']) {
            const outputPath = path.join(directory, `${mode}-results.json`);
            const stdout = execFileSync(process.execPath, [
                SCRIPT_PATH,
                '--mode', mode,
                '--manifest', MANIFEST_PATH,
                '--input-descriptor', INPUT_DESCRIPTOR_PATH,
                '--poc-manifest', POC_MANIFEST_PATH,
                '--search-text', SEARCH_TEXT_PATH,
                '--out', outputPath,
            ], { cwd: REPOSITORY_ROOT, encoding: 'utf8' });
            const envelope = JSON.parse(stdout);
            const resultBytes = readFileSync(outputPath);
            const committedResultBytes = readFileSync(path.join(OUTPUT_ROOT, `${mode}-results.json`));
            const results = JSON.parse(resultBytes.toString('utf8'));
            const resultSha256 = sha256(resultBytes);

            assert.equal(envelope.ok, true);
            assert.equal(envelope.queryCount, 15);
            assert.equal(envelope.resultSha256, resultSha256);
            assert.equal(resultSha256, TRUSTED_RESULT_SHA256[mode]);
            assert.equal(resultSha256, evidence.outputs[mode].sha256);
            assert.deepEqual(resultBytes, committedResultBytes);
            assert.equal(envelope.output, outputPath);
            assert.deepEqual(results, expectedResults(mode));
        }
    } finally {
        rmSync(directory, { recursive: true, force: true });
    }
});

test('atomic output writer는 write·rename 실패에서 기존 결과와 임시 파일을 보존한다', () => {
    const directory = mkdtempSync(path.join(tmpdir(), 'search-04-baseline-atomic-'));
    const outputPath = path.join(directory, 'results.json');
    writeFileSync(outputPath, 'previous\n', 'utf8');
    try {
        assert.throws(
            () => writeOutputAtomically(outputPath, 'next\n', {
                randomId: () => 'write-failure',
                writeFile: () => { throw new Error('write failed'); },
            }),
            /write failed/u,
        );
        assert.equal(readFileSync(outputPath, 'utf8'), 'previous\n');
        assert.equal(existsSync(path.join(directory, '.results.json.write-failure.tmp')), false);

        assert.throws(
            () => writeOutputAtomically(outputPath, 'next\n', {
                randomId: () => 'rename-failure',
                rename: () => { throw new Error('rename failed'); },
            }),
            /rename failed/u,
        );
        assert.equal(readFileSync(outputPath, 'utf8'), 'previous\n');
        assert.equal(existsSync(path.join(directory, '.results.json.rename-failure.tmp')), false);
    } finally {
        rmSync(directory, { recursive: true, force: true });
    }
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
