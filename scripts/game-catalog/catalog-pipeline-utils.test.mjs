import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import {
    commitZipArtifacts,
    escapeCsvField,
    parseCsvLine,
    parseDescriptionUpdates,
    parseNameUpdates,
    resolveInputRoot,
    validateApprovedInputReport,
    validatePositiveUniqueIds,
} from './catalog-pipeline-utils.mjs';
import { validateDescription } from './korean-description-validator.mjs';

test('BGG ID는 양의 safe integer이고 base/source 모두 중복을 차단한다', () => {
    assert.deepEqual(validatePositiveUniqueIds([{ bgg_id: '10' }, { bgg_id: 20 }], 'base'), [10, 20]);
    assert.throws(
        () => validatePositiveUniqueIds([{ bgg_id: 10 }, { bgg_id: 10 }], 'source'),
        /source.*duplicate.*10/i,
    );
    assert.throws(() => validatePositiveUniqueIds([{ bgg_id: 'not-a-number' }], 'source'), /source.*bgg_id/i);
});

test('승인 report의 status·dataset·grain·checksum·행 수가 실제 입력과 일치해야 한다', () => {
    const input = Buffer.from('[{"bgg_id":10}]\n');
    const report = {
        status: 'ready',
        datasetKind: 'approved-base',
        grain: '1 row per bgg_id',
        inputs: { approvedBase: { sha256: 'e7a4c5f3f5b2b3e8f4ed4b67f4db7e6c7d2f1c4c0e6eaf0f2db0f2f3b0d2f8d1', rows: 1 } },
    };
    report.inputs.approvedBase.sha256 = createHash('sha256').update(input).digest('hex');
    assert.doesNotThrow(() => validateApprovedInputReport({
        report,
        inputBytes: input,
        inputRows: 1,
        inputKeys: ['approvedBase'],
        datasetKind: 'approved-base',
        grain: '1 row per bgg_id',
    }));
    assert.throws(() => validateApprovedInputReport({
        report: { ...report, status: 'blocked' },
        inputBytes: input,
        inputRows: 1,
        inputKeys: ['approvedBase'],
        datasetKind: 'approved-base',
        grain: '1 row per bgg_id',
    }), /status/i);
    assert.throws(() => validateApprovedInputReport({
        report: { ...report, inputs: { approvedBase: { ...report.inputs.approvedBase, rows: 2 } } },
        inputBytes: input,
        inputRows: 1,
        inputKeys: ['approvedBase'],
        datasetKind: 'approved-base',
        grain: '1 row per bgg_id',
    }), /rows/i);
});

test('설명 validator는 한글 한 글자만 있는 영어 잔존 설명을 차단한다', () => {
    assert.equal(validateDescription(10, '게임은 cards를 사용하지만 the player draws cards.', '이 설명은 cards만 일부 치환되었습니다.').valid, false);
    assert.equal(validateDescription(10, '카드를 사용해 승점을 얻습니다.', '플레이어는 카드를 뽑고 승리 조건을 확인합니다.').valid, true);
});

test('CSV는 따옴표·개행이 있는 값도 단일 필드로 왕복한다', () => {
    const field = '이름, "테스트"\n다음 줄';
    assert.deepEqual(parseCsvLine(`1,${escapeCsvField(field)},N`), ['1', '이름, "테스트" 다음 줄', 'N']);
});

test('설명·이름 SQL은 multiline statement와 중복 ID를 파싱한다', () => {
    const names = parseNameUpdates("UPDATE games SET name = '첫\n줄' WHERE bgg_id = 10;\nUPDATE games SET name = '둘' WHERE bgg_id = 10;");
    assert.equal(names.length, 2);
    assert.equal(names[0].value, '첫\n줄');
    assert.deepEqual(parseDescriptionUpdates("UPDATE games SET description = '요약\n문장', detail_description = '상세' WHERE bgg_id = 20;"), [
        { bggId: 20, description: '요약\n문장', detailDescription: '상세' },
    ]);
});

test('입력 루트는 positional argument 또는 환경변수로 재현한다', () => {
    assert.equal(resolveInputRoot(['/tmp/catalog']), '/tmp/catalog');
    assert.equal(resolveInputRoot(['--input-root', '/tmp/catalog']), '/tmp/catalog');
    assert.equal(resolveInputRoot([], { ALBAM_MATE_170K_DIR: '/tmp/catalog' }), '/tmp/catalog');
    assert.throws(() => resolveInputRoot([]), /input-root/i);
});

test('자동 음차 후보 생성기는 승인 SQL을 변경하지 않는다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-name-candidate-'));
    const localization = join(root, 'reference/02-localization');
    const inputs = join(root, 'reference/04-inputs');
    const sqlPath = join(localization, '04-upsert-korean-names-supplement.sql');
    const candidatePath = join(localization, 'bgg-game-name-ko-candidates-7001-15000.csv');
    mkdirSyncForTest(localization);
    mkdirSyncForTest(inputs);
    writeFileSync(sqlPath, 'BEGIN;\nCOMMIT;\n');
    writeFileSync(join(inputs, 'boardgames_ranks07-24.csv'), 'id,name,x,rank\n999001,Airlines,,7001\n');
    execFileSync(process.execPath, ['scripts/game-catalog/expand-korean-names.mjs', root], { cwd: process.cwd() });
    assert.equal(readFileSync(sqlPath, 'utf8'), 'BEGIN;\nCOMMIT;\n');
    assert.match(readFileSync(candidatePath, 'utf8'), /추정번역\(자동음차\)/);
});

test('BoardLife UPSERT는 충돌 시 승인된 업무 필드 전체를 갱신한다', () => {
    const source = readFileSync('scripts/game-catalog/boardlife-collector.mjs', 'utf8');
    const approvedFields = [
        'name', 'english_name', 'supported_player_count', 'tag', 'estimated_play_time',
        'min_players', 'max_players', 'min_play_time_minutes', 'max_play_time_minutes',
        'complexity', 'release_year', 'description', 'detail_description',
    ];
    for (const field of approvedFields) {
        assert.match(source, new RegExp(`${field} = EXCLUDED\\.${field}`));
    }
});

test('자동 설명 생성기는 검수 후보만 만들고 승인 SQL과 ZIP을 변경하지 않는다', () => {
    for (const script of ['expand-korean-descriptions.mjs', 'expand-all-korean-descriptions.mjs']) {
        const root = createDescriptionGeneratorFixture();
        const localization = join(root, 'reference/02-localization');
        const sqlPath = join(localization, '05-upsert-korean-descriptions-supplement.sql');
        const candidatePath = join(localization, '05-upsert-korean-descriptions-supplement.needs-review.json');
        const zipPath = join(root, '01-team-handoff-local.zip');
        const approvedSql = 'BEGIN;\n-- 사람이 승인한 기존 SQL\nCOMMIT;\n';

        execFileSync(process.execPath, [`scripts/game-catalog/${script}`, root], { cwd: process.cwd() });

        assert.equal(readFileSync(sqlPath, 'utf8'), approvedSql);
        assert.equal(
            execFileSync('unzip', ['-p', zipPath, '06-complete-local-import/05-upsert-korean-descriptions-supplement.sql'], { encoding: 'utf8' }),
            approvedSql,
        );
        assert.deepEqual(JSON.parse(readFileSync(candidatePath, 'utf8')), [{
            bgg_id: 10,
            source_description: '카드를 사용해 승점을 얻습니다.',
            source_detail_description: '플레이어는 카드를 뽑고 승리 조건을 확인합니다.',
            description_ko: '카드를 사용해 승점을 얻습니다.',
            detail_description_ko: '플레이어는 카드를 뽑고 승리 조건을 확인합니다.',
            reviewed: false,
        }]);
    }
});

test('설명 생성기는 운영체제 임시 디렉터리 기반 추출기를 사용한다', () => {
    const source = readFileSync('scripts/game-catalog/expand-korean-descriptions.mjs', 'utf8');
    assert.doesNotMatch(source, /['"]\/tmp\//);
    assert.match(source, /readZipJsonEntry/);
});

test('카탈로그 build가 source 중복이면 품질 report만 남긴다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-build-gate-'));
    const inputs = join(root, 'inputs');
    const out = join(root, 'out');
    mkdirSyncForTest(inputs);
    const base = [{
        bgg_id: 10,
        name: '게임',
        english_name: 'Game',
        supported_player_count: '2명',
        tag: '전략',
        estimated_play_time: '30분',
        description: '기본 설명',
        detail_description: '기본 상세 설명',
    }];
    const source = [{ bgg_id: 10, description: '설명', detail_description: '상세' }, { bgg_id: 10, description: '중복', detail_description: '중복' }];
    const basePath = join(inputs, 'base.json');
    const sourcePath = join(inputs, 'source.json');
    const baseReportPath = join(inputs, 'base-report.json');
    const sourceReportPath = join(inputs, 'source-report.json');
    writeJsonForTest(basePath, base);
    writeJsonForTest(sourcePath, source);
    writeJsonForTest(baseReportPath, buildReportForTest(basePath, base, 'approved-local-import-base'));
    writeJsonForTest(sourceReportPath, buildReportForTest(sourcePath, source, 'bgg-xml-description-catalog'));
    const staleSql = join(out, 'upsert-games.local-import-with-bgg-descriptions.sql');
    mkdirSyncForTest(out);
    writeFileSync(staleSql, 'stale');
    const result = spawnSync(process.execPath, [
        'scripts/game-catalog/build-complete-local-import-catalog.mjs',
        '--base', basePath,
        '--base-report', baseReportPath,
        '--source', sourcePath,
        '--source-report', sourceReportPath,
        '--out', out,
    ], { cwd: process.cwd(), encoding: 'utf8' });
    assert.equal(result.status, 1);
    assert.equal(existsSync(staleSql), false);
    assert.equal(JSON.parse(readFileSync(join(out, 'quality-report.json'), 'utf8')).status, 'blocked_for_local_import');
});

test('승인된 base/source report는 5000행 이하 UPSERT 산출물을 만든다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-build-ready-'));
    const inputs = join(root, 'inputs');
    const out = join(root, 'out');
    mkdirSyncForTest(inputs);
    const rows = [{
        bgg_id: 10,
        name: '게임',
        english_name: 'Game',
        supported_player_count: '2명',
        tag: '전략',
        estimated_play_time: '30분',
        description: '기본 설명',
        detail_description: '기본 상세 설명',
    }];
    const sourceRows = [{ bgg_id: 10, description: '설명', detail_description: '상세' }];
    const basePath = join(inputs, 'base.json');
    const sourcePath = join(inputs, 'source.json');
    const baseReportPath = join(inputs, 'base-report.json');
    const sourceReportPath = join(inputs, 'source-report.json');
    writeJsonForTest(basePath, rows);
    writeJsonForTest(sourcePath, sourceRows);
    writeJsonForTest(baseReportPath, buildReportForTest(basePath, rows, 'approved-local-import-base'));
    writeJsonForTest(sourceReportPath, buildReportForTest(sourcePath, sourceRows, 'bgg-xml-description-catalog'));
    const result = spawnSync(process.execPath, [
        'scripts/game-catalog/build-complete-local-import-catalog.mjs',
        '--base', basePath,
        '--base-report', baseReportPath,
        '--source', sourcePath,
        '--source-report', sourceReportPath,
        '--out', out,
    ], { cwd: process.cwd(), encoding: 'utf8' });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(JSON.parse(readFileSync(join(out, 'quality-report.json'), 'utf8')).status, 'ready_for_local_import');
    assert.match(readFileSync(join(out, 'upsert-games.local-import-with-bgg-descriptions.sql'), 'utf8'), /INSERT INTO games/);
});

test('ZIP 산출물은 외부 파일과 함께 성공 시에만 교체한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-zip-atomic-'));
    const zipPath = join(root, 'handoff.zip');
    const target = join(root, 'output.sql');
    writeFileSync(target, 'old');
    execFileSync('zip', ['-q', zipPath, 'output.sql'], { cwd: root });
    commitZipArtifacts({
        zipPath,
        zipEntry: 'output.sql',
        zipFileTarget: target,
        files: [{ target, contents: 'new' }],
    });
    assert.equal(readFileSync(target, 'utf8'), 'new');
    assert.equal(execFileSync('unzip', ['-p', zipPath, 'output.sql'], { encoding: 'utf8' }), 'new');
});

test('전량 검수기는 multiline SQL·0건·영어 잔존을 실패 report로 남긴다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-validation-'));
    const localization = join(root, 'reference/02-localization');
    mkdirSyncForTest(localization);
    writeFileSync(join(localization, '04-upsert-korean-names-supplement.sql'), "UPDATE games SET name = '한글\n이름' WHERE bgg_id = 10;\n");
    writeFileSync(join(localization, '05-upsert-korean-descriptions-supplement.sql'), "UPDATE games SET description = '게임은 cards를 사용합니다.', detail_description = 'the player draws cards.' WHERE bgg_id = 10;\n");
    const result = spawnSync(process.execPath, ['scripts/game-catalog/validate-full-localization.mjs', root], { cwd: process.cwd(), encoding: 'utf8' });
    assert.equal(result.status, 1);
    const report = JSON.parse(readFileSync(join(localization, 'validation-full-localization.report.json'), 'utf8'));
    assert.equal(report.status, 'blocked');
    assert.equal(report.checks.totalNames, 1);
    assert.equal(report.checks.totalDescs, 1);
    assert.equal(report.checks.descriptionValidationErrorCount, 1);
});

function mkdirSyncForTest(path) {
    mkdirSync(path, { recursive: true });
}

function writeJsonForTest(path, value) {
    writeFileSync(path, JSON.stringify(value));
}

function createDescriptionGeneratorFixture() {
    const root = mkdtempSync(join(tmpdir(), 'albam-description-candidate-'));
    const localization = join(root, 'reference/02-localization');
    const zipRoot = join(root, 'zip-root/06-complete-local-import');
    const approvedSql = 'BEGIN;\n-- 사람이 승인한 기존 SQL\nCOMMIT;\n';
    mkdirSyncForTest(localization);
    mkdirSyncForTest(zipRoot);
    writeFileSync(join(root, 'README.md'), '# 테스트\n');
    writeFileSync(join(localization, '05-upsert-korean-descriptions-supplement.sql'), approvedSql);
    writeJsonForTest(join(zipRoot, 'service-catalog.local-import-with-bgg-descriptions.json'), [{
        bgg_id: 10,
        description: '카드를 사용해 승점을 얻습니다.',
        detail_description: '플레이어는 카드를 뽑고 승리 조건을 확인합니다.',
    }]);
    writeFileSync(join(zipRoot, '05-upsert-korean-descriptions-supplement.sql'), approvedSql);
    execFileSync('zip', [
        '-q',
        join(root, '01-team-handoff-local.zip'),
        '06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json',
        '06-complete-local-import/05-upsert-korean-descriptions-supplement.sql',
    ], { cwd: join(root, 'zip-root') });
    return root;
}

function buildReportForTest(path, rows, datasetKind) {
    const bytes = Buffer.from(JSON.stringify(rows));
    return {
        status: 'ready',
        datasetKind,
        grain: '1 row per bgg_id',
        inputs: {
            ...(datasetKind === 'approved-local-import-base'
                ? { approvedBase: { sha256: createHash('sha256').update(bytes).digest('hex'), rows: rows.length } }
                : { bggXmlCatalog: { sha256: createHash('sha256').update(bytes).digest('hex'), rows: rows.length } }),
        },
    };
}
