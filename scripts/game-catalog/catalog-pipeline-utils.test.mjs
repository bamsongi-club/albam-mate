import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import test from 'node:test';

import { validateCatalogRowsForDatabase } from './catalog-analysis.mjs';
import {
    commitZipArtifacts,
    escapeCsvField,
    parseCsvLine,
    parseDescriptionUpdates,
    parseNameUpdates,
    parseApprovedRelationTuples,
    readZipJsonEntry,
    readZipTextEntry,
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

test('적재 직전 행은 DB 타입·길이·URL·수치 범위와 쌍 제약을 모두 만족해야 한다', () => {
    const valid = buildCatalogRowForTest();
    assert.deepEqual(validateCatalogRowsForDatabase([valid]), []);

    const cases = [
        ['문자열 타입', { min_players: 'two' }, 'INVALID_DATABASE_FIELD_TYPE'],
        ['DB 문자열 길이', { name: '가'.repeat(256) }, 'DATABASE_FIELD_LENGTH_EXCEEDED'],
        ['NUL 문자', { description: '설명\u0000손상' }, 'NUL_CHARACTER_IN_DATABASE_TEXT'],
        ['HTTPS URL', { image_url: 'http://example.com/game.jpg' }, 'INVALID_DATABASE_IMAGE_URL'],
        ['인원 범위', { min_players: 4, max_players: 2 }, 'INVALID_DATABASE_PLAYER_RANGE'],
        ['시간 범위', { min_play_time_minutes: 60, max_play_time_minutes: 30 }, 'INVALID_DATABASE_PLAY_TIME_RANGE'],
        ['복잡도 범위', { complexity: 5.01 }, 'INVALID_DATABASE_COMPLEXITY'],
        ['출시연도 타입', { release_year: '2020' }, 'INVALID_DATABASE_RELEASE_YEAR'],
    ];
    for (const [label, override, expectedCode] of cases) {
        const errors = validateCatalogRowsForDatabase([{ ...valid, ...override }]);
        assert.ok(errors.some(({ code }) => code === expectedCode), `${label}: ${JSON.stringify(errors)}`);
    }
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

test('관계 export는 지정된 INSERT source만 파싱하고 중복·사전 밖 ID를 차단한다', () => {
    const themeSql = [
        'with desired(bgg_id,min_age) as (values (10,14)) update games set min_age=14;',
        'with desired(bgg_id,bgg_theme_id) as (values (10,1016),(11,1017)) delete from game_theme_relations;',
        'with desired(bgg_id,bgg_theme_id) as (values (10,1016),(11,1017)) insert into game_theme_relations(game_id,theme_id) select 1,1;',
    ].join('\n');
    assert.deepEqual(
        parseApprovedRelationTuples(themeSql, 'theme', new Set([1016, 1017])),
        [{ gameBggId: 10, relatedBggId: 1016 }, { gameBggId: 11, relatedBggId: 1017 }],
    );
    assert.throws(
        () => parseApprovedRelationTuples(themeSql, 'theme', new Set([1016])),
        /dictionary|사전/i,
    );
    const duplicated = themeSql.replace('(10,1016),(11,1017)) insert', '(10,1016),(10,1016)) insert');
    assert.throws(() => parseApprovedRelationTuples(duplicated, 'theme', new Set([1016])), /duplicate|중복/i);
});

test('최종 CSV는 checksum이 고정된 전량 승인 한글화만 export하고 실패를 non-zero로 보고한다', async () => {
    const blocked = await createFinalExportFixture({ includeApprovedName: false });
    const blockedResult = runFinalExport(blocked.root);
    assert.equal(blockedResult.status, 1, blockedResult.stderr);
    assert.equal(existsSync(blocked.outputPath), false);

    const ready = await createFinalExportFixture({ includeApprovedName: true });
    const readyResult = runFinalExport(ready.root);
    assert.equal(readyResult.status, 0, readyResult.stderr);
    const csv = readFileSync(ready.outputPath, 'utf8');
    assert.match(csv, /한글 게임/);
    assert.match(csv, /한글 설명/);

    const blankName = await createFinalExportFixture({ includeApprovedName: true, approvedName: '' });
    writeFileSync(blankName.outputPath, 'stale');
    const blankNameResult = runFinalExport(blankName.root);
    assert.equal(blankNameResult.status, 1, blankNameResult.stderr);
    assert.equal(existsSync(blankName.outputPath), false);

    const blankDescription = await createFinalExportFixture({
        includeApprovedName: true,
        approvedDescription: '',
    });
    writeFileSync(blankDescription.outputPath, 'stale');
    const blankDescriptionResult = runFinalExport(blankDescription.root);
    assert.equal(blankDescriptionResult.status, 1, blankDescriptionResult.stderr);
    assert.equal(existsSync(blankDescription.outputPath), false);

    const blankDetailDescription = await createFinalExportFixture({
        includeApprovedName: true,
        approvedDetailDescription: '',
    });
    const blankDetailResult = runFinalExport(blankDetailDescription.root);
    assert.equal(blankDetailResult.status, 1, blankDetailResult.stderr);
    assert.equal(existsSync(blankDetailDescription.outputPath), false);

    const missing = mkdtempSync(join(tmpdir(), 'albam-final-export-missing-'));
    const missingResult = runFinalExport(missing);
    assert.equal(missingResult.status, 1, missingResult.stderr);
});

test('카테고리·테마 export는 metadata의 최소 연령 튜플을 테마 관계로 내보내지 않는다', async () => {
    const fixture = await createFinalExportFixture({ includeApprovedName: true });
    const finalResult = runFinalExport(fixture.root);
    assert.equal(finalResult.status, 0, finalResult.stderr);
    const result = spawnSync(process.execPath, [
        'scripts/game-catalog/export-categories-and-themes.mjs',
        fixture.root,
    ], { cwd: process.cwd(), encoding: 'utf8', env: { ...process.env, PATH: '' } });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(
        readFileSync(join(fixture.root, 'game_theme_mappings.csv'), 'utf8'),
        'game_bgg_id,theme_bgg_id\n10,1016',
    );
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

test('전량 이름 생성기는 multiline 승인 이름을 ZIP과 새 SQL에 보존한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-name-approved-'));
    const localization = join(root, 'reference/02-localization');
    mkdirSyncForTest(localization);
    const zipBase64 = 'UEsDBBQAAAAIAPtaDV0mlUUdNAAAAEQAAABQAAAAMDYtY29tcGxldGUtbG9jYWwtaW1wb3J0L3NlcnZpY2UtY2F0YWxvZy5sb2NhbC1pbXBvcnQtd2l0aC1iZ2ctZGVzY3JpcHRpb25zLmpzb26LrlZKSk+Pz0xRslKwtLQ0MDDUUVDKS8xNBfKV/Isy0zPzEnOUgGKpeek5mcUZ8RhytbEAUEsBAhQDFAAAAAgA+1oNXSaVRR00AAAARAAAAFAAAAAAAAAAAAAAAIABAAAAADA2LWNvbXBsZXRlLWxvY2FsLWltcG9ydC9zZXJ2aWNlLWNhdGFsb2cubG9jYWwtaW1wb3J0LXdpdGgtYmdnLWRlc2NyaXB0aW9ucy5qc29uUEsFBgAAAAABAAEAfgAAAKIAAAAAAA==';
    writeFileSync(join(root, '01-team-handoff-local.zip'), Buffer.from(zipBase64, 'base64'));
    const sqlPath = join(localization, '04-upsert-korean-names-supplement.sql');
    writeFileSync(sqlPath, "BEGIN;\nUPDATE games SET name = '첫\n이름' WHERE bgg_id = 999001;\nCOMMIT;\n");

    const result = spawnSync(process.execPath, [
        'scripts/game-catalog/expand-all-korean-names.mjs',
        root,
    ], { cwd: process.cwd(), encoding: 'utf8', env: { ...process.env, PATH: '' } });

    assert.equal(result.status, 0, result.stderr);
    assert.deepEqual(parseNameUpdates(readFileSync(sqlPath, 'utf8')), [{ bggId: 999001, value: '첫\n이름' }]);
});

test('BoardLife 보완 행은 승인 BGG snapshot에 실제 ID와 영문명이 있을 때만 SQL을 만든다', async () => {
    const empty = createBoardlifeFixture({ inputId: null, sourceId: null });
    const emptyResult = runBoardlifeCollector(empty);
    assert.equal(emptyResult.status, 1);
    assert.match(emptyResult.stderr, /최소 1행/);
    assert.equal(existsSync(empty.sqlPath), false);

    const rejected = createBoardlifeFixture({ inputId: 990001, sourceId: 10 });
    const rejectedResult = runBoardlifeCollector(rejected);
    assert.equal(rejectedResult.status, 1);
    assert.match(rejectedResult.stderr, /BGG.*snapshot|snapshot.*BGG/i);
    assert.equal(existsSync(rejected.sqlPath), false);

    const accepted = createBoardlifeFixture({ inputId: 10, sourceId: 10 });
    const acceptedResult = runBoardlifeCollector(accepted);
    assert.equal(acceptedResult.status, 0, acceptedResult.stderr);
    const sql = readFileSync(accepted.sqlPath, 'utf8');
    assert.match(sql, /VALUES\s+\(10,/);
    for (const field of [
        'name', 'english_name', 'supported_player_count', 'tag', 'estimated_play_time',
        'min_players', 'max_players', 'min_play_time_minutes', 'max_play_time_minutes',
        'complexity', 'release_year', 'description', 'detail_description',
    ]) {
        assert.match(sql, new RegExp(`${field} = EXCLUDED\\.${field}`));
    }
    assert.equal(
        await readZipTextEntry(accepted.zipPath, '06-complete-local-import/06-upsert-boardlife-new-games.sql'),
        sql,
    );
});

test('자동 설명 생성기는 검수 후보만 만들고 승인 SQL과 ZIP을 변경하지 않는다', async () => {
    for (const script of ['expand-korean-descriptions.mjs', 'expand-all-korean-descriptions.mjs']) {
        const root = createDescriptionGeneratorFixture();
        const localization = join(root, 'reference/02-localization');
        const sqlPath = join(localization, '05-upsert-korean-descriptions-supplement.sql');
        const candidatePath = join(localization, '05-upsert-korean-descriptions-supplement.needs-review.json');
        const zipPath = join(root, '01-team-handoff-local.zip');
        const approvedSql = 'BEGIN;\n-- 사람이 승인한 기존 SQL\nCOMMIT;\n';

        execFileSync(process.execPath, [`scripts/game-catalog/${script}`, root], {
            cwd: process.cwd(),
            env: { ...process.env, PATH: '' },
        });

        assert.equal(readFileSync(sqlPath, 'utf8'), approvedSql);
        assert.equal(
            await readZipTextEntry(zipPath, '06-complete-local-import/05-upsert-korean-descriptions-supplement.sql'),
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

test('ZIP 산출물은 외부 zip 명령 없이 대용량 entry를 교체하고 읽는다', async () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-zip-atomic-'));
    const zipPath = join(root, 'handoff.zip');
    const target = join(root, 'output.json');
    const originalZipBase64 = 'UEsDBBQAAAAIAIJaDV1v1ymCEwAAABEAAAALAAAAb3V0cHV0Lmpzb26LrlYqS8wpTVWyUsrPSVGqjQUAUEsBAhQDFAAAAAgAgloNXW/XKYITAAAAEQAAAAsAAAAAAAAAAAAAAIABAAAAAG91dHB1dC5qc29uUEsFBgAAAAABAAEAOQAAADwAAAAAAA==';
    const contents = JSON.stringify([{ value: 'x'.repeat(2_500_000) }]);
    writeFileSync(zipPath, Buffer.from(originalZipBase64, 'base64'));
    writeFileSync(target, 'old');
    const originalPath = process.env.PATH;
    process.env.PATH = '';
    try {
        await commitZipArtifacts({
            zipPath,
            zipEntry: 'output.json',
            zipFileTarget: target,
            files: [{ target, contents }],
        });
        assert.equal(readFileSync(target, 'utf8'), contents);
        assert.deepEqual(await readZipJsonEntry(zipPath, 'output.json'), JSON.parse(contents));
    } finally {
        process.env.PATH = originalPath;
    }
});

test('ZIP 갱신은 archive comment를 보존하고 comment 내부의 EOCD signature를 무시한다', async () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-zip-comment-'));
    const zipPath = join(root, 'handoff.zip');
    const target = join(root, 'output.json');
    const originalZipBase64 = 'UEsDBBQAAAAIAIJaDV1v1ymCEwAAABEAAAALAAAAb3V0cHV0Lmpzb26LrlYqS8wpTVWyUsrPSVGqjQUAUEsBAhQDFAAAAAgAgloNXW/XKYITAAAAEQAAAAsAAAAAAAAAAAAAAIABAAAAAG91dHB1dC5qc29uUEsFBgAAAAABAAEAOQAAADwAAAAAAA==';
    const comment = Buffer.concat([
        Buffer.from('approved-source:'),
        Buffer.from([0x50, 0x4b, 0x05, 0x06]),
        Buffer.alloc(30, 0x41),
    ]);
    writeFileSync(zipPath, withZipArchiveComment(Buffer.from(originalZipBase64, 'base64'), comment));

    await commitZipArtifacts({
        zipPath,
        zipEntry: 'output.json',
        zipFileTarget: target,
        files: [{ target, contents: '[{"value":"updated"}]' }],
    });

    assert.deepEqual(await readZipJsonEntry(zipPath, 'output.json'), [{ value: 'updated' }]);
    assert.deepEqual(readFileSync(zipPath).subarray(-comment.length), comment);
});

test('ZIP 동시 갱신은 서로 다른 entry를 유실하지 않는다', async () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-zip-concurrent-'));
    const zipPath = join(root, 'handoff.zip');
    const originalZipBase64 = 'UEsDBBQAAAAIAIJaDV1v1ymCEwAAABEAAAALAAAAb3V0cHV0Lmpzb26LrlYqS8wpTVWyUsrPSVGqjQUAUEsBAhQDFAAAAAgAgloNXW/XKYITAAAAEQAAAAsAAAAAAAAAAAAAAIABAAAAAG91dHB1dC5qc29uUEsFBgAAAAABAAEAOQAAADwAAAAAAA==';
    writeFileSync(zipPath, Buffer.from(originalZipBase64, 'base64'));
    const firstTarget = join(root, 'first.txt');
    const secondTarget = join(root, 'second.txt');
    const firstContents = 'first-' + 'a'.repeat(500_000);
    const secondContents = 'second-' + 'b'.repeat(500_000);

    await Promise.all([
        commitZipArtifacts({
            zipPath,
            zipEntry: 'first.txt',
            zipFileTarget: firstTarget,
            files: [{ target: firstTarget, contents: firstContents }],
        }),
        commitZipArtifacts({
            zipPath,
            zipEntry: 'second.txt',
            zipFileTarget: secondTarget,
            files: [{ target: secondTarget, contents: secondContents }],
        }),
    ]);

    assert.equal(await readZipTextEntry(zipPath, 'first.txt'), firstContents);
    assert.equal(await readZipTextEntry(zipPath, 'second.txt'), secondContents);
});

test('주인 PID가 재사용된 stale ZIP lock은 heartbeat 만료 후 회수한다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-zip-stale-lock-'));
    const zipPath = join(root, 'handoff.zip');
    const target = join(root, 'output.json');
    const lockPath = `${zipPath}.lock`;
    const originalZipBase64 = 'UEsDBBQAAAAIAIJaDV1v1ymCEwAAABEAAAALAAAAb3V0cHV0Lmpzb26LrlYqS8wpTVWyUsrPSVGqjQUAUEsBAhQDFAAAAAgAgloNXW/XKYITAAAAEQAAAAsAAAAAAAAAAAAAAIABAAAAAG91dHB1dC5qc29uUEsFBgAAAAABAAEAOQAAADwAAAAAAA==';
    writeFileSync(zipPath, Buffer.from(originalZipBase64, 'base64'));
    mkdirSync(lockPath);
    const ownerPath = join(lockPath, 'owner.json');
    writeJsonForTest(ownerPath, {
        pid: process.pid,
        token: 'abandoned-owner',
    });
    const expiredAt = new Date('2000-01-01T00:00:00.000Z');
    utimesSync(ownerPath, expiredAt, expiredAt);
    const moduleUrl = new URL('./catalog-pipeline-utils.mjs', import.meta.url).href;
    const script = [
        `import { commitZipArtifacts } from ${JSON.stringify(moduleUrl)};`,
        'const [zipPath, target] = process.argv.slice(1);',
        "await commitZipArtifacts({ zipPath, zipEntry: 'output.json', zipFileTarget: target, files: [{ target, contents: '[{\"value\":\"recovered\"}]' }] });",
    ].join('\n');

    const result = spawnSync(process.execPath, ['--input-type=module', '-e', script, zipPath, target], {
        cwd: process.cwd(),
        encoding: 'utf8',
        timeout: 1_000,
    });

    assert.equal(result.status, 0, result.stderr || result.error?.message);
    assert.equal(existsSync(lockPath), false);
});

test('전량 검수기는 multiline SQL·0건·영어 잔존을 실패 report로 남긴다', () => {
    const root = mkdtempSync(join(tmpdir(), 'albam-validation-'));
    const localization = join(root, 'reference/02-localization');
    mkdirSyncForTest(localization);
    const zipBase64 = 'UEsDBBQAAAAIAPtaDV0mlUUdNAAAAEQAAABQAAAAMDYtY29tcGxldGUtbG9jYWwtaW1wb3J0L3NlcnZpY2UtY2F0YWxvZy5sb2NhbC1pbXBvcnQtd2l0aC1iZ2ctZGVzY3JpcHRpb25zLmpzb26LrlZKSk+Pz0xRslKwtLQ0MDDUUVDKS8xNBfKV/Isy0zPzEnOUgGKpeek5mcUZ8RhytbEAUEsBAhQDFAAAAAgA+1oNXSaVRR00AAAARAAAAFAAAAAAAAAAAAAAAIABAAAAADA2LWNvbXBsZXRlLWxvY2FsLWltcG9ydC9zZXJ2aWNlLWNhdGFsb2cubG9jYWwtaW1wb3J0LXdpdGgtYmdnLWRlc2NyaXB0aW9ucy5qc29uUEsFBgAAAAABAAEAfgAAAKIAAAAAAA==';
    writeFileSync(join(root, '01-team-handoff-local.zip'), Buffer.from(zipBase64, 'base64'));
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

test('전량 검수기는 빈 이름과 catalog 밖 BGG ID 집합을 ready로 승인하지 않는다', async () => {
    const blankName = await createLocalizationValidationFixture({
        nameId: 10,
        approvedName: '',
        descriptionId: 10,
    });
    const blankResult = runFullLocalizationValidator(blankName.root);
    assert.equal(blankResult.status, 1, blankResult.stderr);
    const blankReport = JSON.parse(readFileSync(blankName.reportPath, 'utf8'));
    assert.equal(blankReport.status, 'blocked');
    assert.equal(blankReport.checks.blankNameCount, 1);

    const wrongIds = await createLocalizationValidationFixture({
        nameId: 11,
        approvedName: '한글 게임',
        descriptionId: 11,
    });
    const wrongIdsResult = runFullLocalizationValidator(wrongIds.root);
    assert.equal(wrongIdsResult.status, 1, wrongIdsResult.stderr);
    const wrongIdsReport = JSON.parse(readFileSync(wrongIds.reportPath, 'utf8'));
    assert.equal(wrongIdsReport.status, 'blocked');
    assert.equal(wrongIdsReport.checks.nameCoverageErrorCount, 1);
    assert.equal(wrongIdsReport.checks.descriptionCoverageErrorCount, 1);
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
    const approvedSql = 'BEGIN;\n-- 사람이 승인한 기존 SQL\nCOMMIT;\n';
    mkdirSyncForTest(localization);
    writeFileSync(join(root, 'README.md'), '# 테스트\n');
    writeFileSync(join(localization, '05-upsert-korean-descriptions-supplement.sql'), approvedSql);
    const zipBase64 = 'UEsDBBQAAAAIADhcDV0SglsRkgAAAKoAAABQAAAAMDYtY29tcGxldGUtbG9jYWwtaW1wb3J0L3NlcnZpY2UtY2F0YWxvZy5sb2NhbC1pbXBvcnQtd2l0aC1iZ2ctZGVzY3JpcHRpb25zLmpzb26LrlZKSk+Pz0xRslIwNNBRUEpJLU4uyiwoyczPAwopvdm55fXkOa+X7lF407TmzayVb6duUXjTtfPNgglv5rYovJm2+03X1tfdHa+7l+gpgXWXJGbmxKMZ8nZKz+sFHW/mbnkzbcvrrikKCENf7534avMCkImvl61ReLNww6uNW0AGv5059c3cHW+nroSZXRsLAFBLAwQUAAAACAA4XA1dNRKj4TYAAAAxAAAARQAAADA2LWNvbXBsZXRlLWxvY2FsLWltcG9ydC8wNS11cHNlcnQta29yZWFuLWRlc2NyaXB0aW9ucy1zdXBwbGVtZW50LnNxbAExAM7/QkVHSU47Ci0tIOyCrOuejOydtCDsirnsnbjtlZwg6riw7KG0IFNRTApDT01NSVQ7ClBLAQIUAxQAAAAIADhcDV0SglsRkgAAAKoAAABQAAAAAAAAAAAAAACAAQAAAAAwNi1jb21wbGV0ZS1sb2NhbC1pbXBvcnQvc2VydmljZS1jYXRhbG9nLmxvY2FsLWltcG9ydC13aXRoLWJnZy1kZXNjcmlwdGlvbnMuanNvblBLAQIUAxQAAAAIADhcDV01EqPhNgAAADEAAABFAAAAAAAAAAAAAACAAQABAAAwNi1jb21wbGV0ZS1sb2NhbC1pbXBvcnQvMDUtdXBzZXJ0LWtvcmVhbi1kZXNjcmlwdGlvbnMtc3VwcGxlbWVudC5zcWxQSwUGAAAAAAIAAgDxAAAAmQEAAAAA';
    writeFileSync(join(root, '01-team-handoff-local.zip'), Buffer.from(zipBase64, 'base64'));
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

function buildCatalogRowForTest() {
    return {
        bgg_id: 10,
        name: '게임',
        english_name: 'Game',
        alias: null,
        image_url: 'https://example.com/game.jpg',
        supported_player_count: '2~4명',
        tag: '전략',
        estimated_play_time: '30분',
        min_players: 2,
        max_players: 4,
        min_play_time_minutes: 30,
        max_play_time_minutes: 30,
        complexity: 2.5,
        release_year: 2020,
        description: '설명',
        detail_description: '상세 설명',
    };
}

function createBoardlifeFixture({ inputId, sourceId }) {
    const root = mkdtempSync(join(tmpdir(), 'albam-boardlife-'));
    const localization = join(root, 'reference/02-localization');
    mkdirSyncForTest(localization);
    const zipPath = join(root, '01-team-handoff-local.zip');
    const zipBase64 = 'UEsDBBQAAAAIAIJaDV1v1ymCEwAAABEAAAALAAAAb3V0cHV0Lmpzb26LrlYqS8wpTVWyUsrPSVGqjQUAUEsBAhQDFAAAAAgAgloNXW/XKYITAAAAEQAAAAsAAAAAAAAAAAAAAIABAAAAAG91dHB1dC5qc29uUEsFBgAAAAABAAEAOQAAADwAAAAAAA==';
    writeFileSync(zipPath, Buffer.from(zipBase64, 'base64'));
    const input = inputId === null ? [] : [{
        ...buildCatalogRowForTest(),
        bgg_id: inputId,
        english_name: 'Game',
    }];
    const bggSource = sourceId === null ? [] : [{ bgg_id: sourceId, english_name: 'Game' }];
    const inputPath = join(root, 'boardlife-input.json');
    const bggSourcePath = join(root, 'bgg-source.json');
    const manifestPath = join(root, 'boardlife-manifest.json');
    writeJsonForTest(inputPath, input);
    writeJsonForTest(bggSourcePath, bggSource);
    const inputBytes = readFileSync(inputPath);
    const bggSourceBytes = readFileSync(bggSourcePath);
    writeJsonForTest(manifestPath, {
        approved: true,
        datasetKind: 'boardlife-bgg-overlays',
        grain: '1 row per bgg_id',
        rows: input.length,
        bggIds: input.map(({ bgg_id: bggId }) => bggId),
        inputSha256: createHash('sha256').update(inputBytes).digest('hex'),
        bggSourceSha256: createHash('sha256').update(bggSourceBytes).digest('hex'),
        boardlifeSourceReference: 'BoardLife approved fixture',
        boardlifeSourceAcquiredAt: '2026-08-13T00:00:00Z',
        boardlifeSourceUsageTerms: 'test fixture',
        bggSourceReference: 'BGG approved snapshot fixture',
        bggSourceAcquiredAt: '2026-08-13T00:00:00Z',
        bggSourceUsageTerms: 'test fixture',
    });
    return {
        root,
        zipPath,
        inputPath,
        bggSourcePath,
        manifestPath,
        sqlPath: join(localization, '06-upsert-boardlife-new-games.sql'),
    };
}

function runBoardlifeCollector({ root, inputPath, bggSourcePath, manifestPath }) {
    return spawnSync(process.execPath, [
        'scripts/game-catalog/boardlife-collector.mjs',
        root,
        '--input', inputPath,
        '--input-manifest', manifestPath,
        '--bgg-source', bggSourcePath,
    ], { cwd: process.cwd(), encoding: 'utf8', env: { ...process.env, PATH: '' } });
}

async function createFinalExportFixture({
    includeApprovedName,
    approvedName = '한글 게임',
    approvedDescription = '한글 설명',
    approvedDetailDescription = '한글 상세 설명',
}) {
    const root = mkdtempSync(join(tmpdir(), 'albam-final-export-'));
    const localization = join(root, 'reference/02-localization');
    mkdirSyncForTest(localization);
    const zipPath = join(root, '01-team-handoff-local.zip');
    const zipBase64 = 'UEsDBBQAAAAIAIJaDV1v1ymCEwAAABEAAAALAAAAb3V0cHV0Lmpzb26LrlYqS8wpTVWyUsrPSVGqjQUAUEsBAhQDFAAAAAgAgloNXW/XKYITAAAAEQAAAAsAAAAAAAAAAAAAAIABAAAAAG91dHB1dC5qc29uUEsFBgAAAAABAAEAOQAAADwAAAAAAA==';
    writeFileSync(zipPath, Buffer.from(zipBase64, 'base64'));
    const catalog = JSON.stringify([buildCatalogRowForTest()]) + '\n';
    const mechanismSql = 'INSERT INTO game_mechanism_relation_source (bgg_id, bgg_mechanism_id) VALUES (10, 2040);\n';
    const themeSql = [
        'with desired(bgg_id,min_age) as (values (10,14)) update games set min_age=14;',
        'with desired(bgg_id,bgg_theme_id) as (values (10,1016)) insert into game_theme_relations(game_id,theme_id) select 1,1;',
    ].join('\n');
    await addZipFixtureEntry(zipPath, root, '06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json', catalog);
    await addZipFixtureEntry(zipPath, root, '06-complete-local-import/02-upsert-game-mechanisms.sql', mechanismSql);
    await addZipFixtureEntry(zipPath, root, '06-complete-local-import/03-upsert-game-metadata.sql', themeSql);

    writeJsonForTest(join(localization, 'bgg-mechanism-ko-map.review-draft.json'), {
        entries: [{ bgg_id: 2040, name: 'Hand Management', name_ko: '핸드 관리' }],
    });
    writeJsonForTest(join(localization, 'bgg-theme-ko-map.review-draft.json'), {
        entries: [{ bggThemeId: 1016, nameEn: 'Science Fiction', nameKo: 'SF' }],
    });
    const namesSql = includeApprovedName
        ? `UPDATE games SET name = '${approvedName}' WHERE bgg_id = 10;\n`
        : 'BEGIN;\nCOMMIT;\n';
    const descriptionsSql = `UPDATE games SET description = '${approvedDescription}', detail_description = '${approvedDetailDescription}' WHERE bgg_id = 10;\n`;
    const namesPath = join(localization, '04-upsert-korean-names-supplement.sql');
    const descriptionsPath = join(localization, '05-upsert-korean-descriptions-supplement.sql');
    writeFileSync(namesPath, namesSql);
    writeFileSync(descriptionsPath, descriptionsSql);
    writeJsonForTest(join(localization, 'validation-full-localization.report.json'), {
        schemaVersion: 1,
        datasetKind: 'approved-full-localization',
        grain: '1 row per bgg_id',
        status: 'ready',
        inputs: {
            catalog: { sha256: createHash('sha256').update(catalog).digest('hex'), rows: 1 },
            namesSql: { sha256: createHash('sha256').update(namesSql).digest('hex'), rows: includeApprovedName ? 1 : 0 },
            descriptionsSql: { sha256: createHash('sha256').update(descriptionsSql).digest('hex'), rows: 1 },
        },
    });
    return { root, outputPath: join(root, 'albam-mate-games-170k-final.csv') };
}

async function createLocalizationValidationFixture({ nameId, approvedName, descriptionId }) {
    const root = mkdtempSync(join(tmpdir(), 'albam-localization-validation-'));
    const localization = join(root, 'reference/02-localization');
    mkdirSyncForTest(localization);
    const zipPath = join(root, '01-team-handoff-local.zip');
    const originalZipBase64 = 'UEsDBBQAAAAIAIJaDV1v1ymCEwAAABEAAAALAAAAb3V0cHV0Lmpzb26LrlYqS8wpTVWyUsrPSVGqjQUAUEsBAhQDFAAAAAgAgloNXW/XKYITAAAAEQAAAAsAAAAAAAAAAAAAAIABAAAAAG91dHB1dC5qc29uUEsFBgAAAAABAAEAOQAAADwAAAAAAA==';
    writeFileSync(zipPath, Buffer.from(originalZipBase64, 'base64'));
    const catalog = JSON.stringify([{ bgg_id: 10, name: 'Original', english_name: 'Original' }]) + '\n';
    const catalogTarget = join(root, 'catalog.json');
    await commitZipArtifacts({
        zipPath,
        zipEntry: '06-complete-local-import/service-catalog.local-import-with-bgg-descriptions.json',
        zipFileTarget: catalogTarget,
        files: [{ target: catalogTarget, contents: catalog }],
    });
    writeFileSync(
        join(localization, '04-upsert-korean-names-supplement.sql'),
        `UPDATE games SET name = '${approvedName}' WHERE bgg_id = ${nameId};\n`,
    );
    writeFileSync(
        join(localization, '05-upsert-korean-descriptions-supplement.sql'),
        `UPDATE games SET description = '한글 설명', detail_description = '한글 상세 설명' WHERE bgg_id = ${descriptionId};\n`,
    );
    return {
        root,
        reportPath: join(localization, 'validation-full-localization.report.json'),
    };
}

function runFullLocalizationValidator(root) {
    return spawnSync(process.execPath, [
        'scripts/game-catalog/validate-full-localization.mjs',
        root,
    ], { cwd: process.cwd(), encoding: 'utf8' });
}

function withZipArchiveComment(zip, comment) {
    const endOffset = zip.lastIndexOf(Buffer.from([0x50, 0x4b, 0x05, 0x06]));
    assert.notEqual(endOffset, -1);
    assert.ok(comment.length <= 0xffff);
    const endRecord = Buffer.from(zip.subarray(0, endOffset + 22));
    endRecord.writeUInt16LE(comment.length, endOffset + 20);
    return Buffer.concat([endRecord, comment]);
}

async function addZipFixtureEntry(zipPath, root, zipEntry, contents) {
    const target = join(root, 'fixture-entries', String(Math.random()).slice(2));
    await commitZipArtifacts({
        zipPath,
        zipEntry,
        zipFileTarget: target,
        files: [{ target, contents }],
    });
}

function runFinalExport(root) {
    return spawnSync(process.execPath, [
        'scripts/game-catalog/export-final-csv.mjs',
        root,
    ], { cwd: process.cwd(), encoding: 'utf8', env: { ...process.env, PATH: '' } });
}
