import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
    ALWAYS_READ_ONLY_PATTERNS,
    DEFAULT_MANIFEST_SCHEMA_PATH,
    auditChangedPaths,
    changedPathsIn,
    matchesPathPattern,
    validateBackendTestManifest,
} from './validate-backend-test-manifest.mjs';
import { POSTGRES_DECISIONS } from './classify-postgres-requirement.mjs';
import { DEFAULT_SCHEMA_PATH as DEFAULT_PACKET_SCHEMA_PATH } from './validate-packet.mjs';

const packetSchema = JSON.parse(fs.readFileSync(DEFAULT_PACKET_SCHEMA_PATH, 'utf8'));
const manifestSchema = JSON.parse(fs.readFileSync(DEFAULT_MANIFEST_SCHEMA_PATH, 'utf8'));
const scriptPath = fileURLToPath(new URL('./validate-backend-test-manifest.mjs', import.meta.url));
const approvedComment = 'https://github.com/bamsongi-club/albam-mate/issues/14#issuecomment-123456789';

function validPacket() {
    return {
        schemaVersion: 4,
        workItem: {
            kind: 'issue',
            id: '#14',
            summary: '알림 읽음 처리를 구현한다',
        },
        canonicalSources: [{ path: 'docs/P1-spec.md', section: 'NOTI-03' }],
        allowedPaths: ['src/main/java/cloud/bamsongi/albammate/notification/'],
        forbiddenPaths: ['frontend/'],
        completionCriteria: ['승인된 알림만 읽음 처리한다'],
        postgresRequired: false,
        postgresRequirementReasons: ['DTO와 일반 서비스 계약만 변경해 H2 경계로 검증한다'],
        requiredTests: [
            { id: 'T1', intent: '알림을 읽음 처리한다', sourceRef: approvedComment },
            { id: 'T2', intent: '소유자만 알림을 읽을 수 있다', sourceRef: approvedComment },
        ],
        testContractApproval: { issueNumber: 14, commentUrl: approvedComment },
        confirmedDecisions: ['이슈 코멘트의 전체 T-ID를 사용한다'],
    };
}

function validManifest() {
    return {
        schemaVersion: 2,
        postgresRequired: false,
        postgresRequirementReasons: ['DTO와 일반 서비스 계약만 변경해 H2 경계로 검증한다'],
        tests: [
            {
                id: 'T1',
                evidence: [
                    {
                        task: 'test',
                        source: 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java',
                        selector: 'cloud.bamsongi.NotificationReadServiceTest.알림을_읽음_처리한다',
                    },
                    {
                        task: 'test',
                        source: 'src/test/java/cloud/bamsongi/NotificationPolicyTest.java',
                        selector: 'cloud.bamsongi.NotificationPolicyTest.소유자만_읽을_수_있다',
                    },
                ],
            },
            {
                id: 'T2',
                evidence: [
                    {
                        task: 'test',
                        source: 'src/test/java/cloud/bamsongi/NotificationPolicyTest.java',
                        selector: 'cloud.bamsongi.NotificationPolicyTest.소유자만_읽을_수_있다',
                    },
                ],
            },
        ],
    };
}

// selector 메서드 검사를 위해 fixture source에 실제 테스트 메서드 선언을 둔다.
const sourceMethods = {
    'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java': ['알림을_읽음_처리한다'],
    'src/test/java/cloud/bamsongi/NotificationPolicyTest.java': ['소유자만_읽을_수_있다'],
    'src/postgresTest/java/cloud/bamsongi/NotificationReadPostgresTest.java': ['읽음_시각을_보존한다'],
};

function javaSource(source, methods) {
    const className = path.basename(source, '.java');
    const normalizedSource = source.replaceAll('\\', '/');
    const packageName = path.posix
        .dirname(normalizedSource.split('/java/').at(-1))
        .replaceAll('/', '.');
    const body = methods.map((method) => `    @Test\n    void ${method}() {\n    }\n`).join('\n');
    return `package ${packageName};\n\nimport org.junit.jupiter.api.Test;\n\nclass ${className} {\n${body}}\n`;
}

function createWorktree(t) {
    const worktree = fs.mkdtempSync(path.join(os.tmpdir(), 'backend-test-manifest-'));
    t.after(() => fs.rmSync(worktree, { recursive: true, force: true }));
    for (const [source, methods] of Object.entries(sourceMethods)) {
        const sourcePath = path.join(worktree, source);
        fs.mkdirSync(path.dirname(sourcePath), { recursive: true });
        fs.writeFileSync(sourcePath, javaSource(source, methods), 'utf8');
    }
    return worktree;
}

function classification(decision) {
    return {
        decision,
        reasons: [{ code: 'test-fixture', path: 'fixture', message: '테스트 분류 fixture' }],
    };
}

function validate(
    packet,
    manifest,
    worktree,
    postgresClassification = classification(POSTGRES_DECISIONS.NOT_REQUIRED),
) {
    return validateBackendTestManifest(
        packet,
        manifest,
        worktree,
        packetSchema,
        manifestSchema,
        null,
        postgresClassification,
    );
}

function requirePostgres(packet, manifest) {
    const reasons = ['Flyway 또는 데이터베이스 의미 변경으로 실제 PostgreSQL 검증이 필요하다'];
    packet.postgresRequired = true;
    packet.postgresRequirementReasons = reasons;
    manifest.postgresRequired = true;
    manifest.postgresRequirementReasons = reasons;
    manifest.tests[1].evidence = [
        {
            task: 'postgresTest',
            source: 'src/postgresTest/java/cloud/bamsongi/NotificationReadPostgresTest.java',
            selector: 'cloud.bamsongi.NotificationReadPostgresTest.읽음_시각을_보존한다',
        },
    ];
}

// 실제 게이트는 git worktree에서 돌고 packet·manifest는 저장소 밖에 둔다. CLI 검증도 같은
// 조건을 만들어야 경로 감사가 임시 파일을 범위 밖 변경으로 보고하지 않는다.
function initGitRepo(worktree) {
    const git = (...args) =>
        spawnSync('git', ['-C', worktree, ...args], { encoding: 'utf8', windowsHide: true });
    git('init', '--quiet');
    git('add', '--all');
    git(
        '-c',
        'user.name=test',
        '-c',
        'user.email=test@example.com',
        'commit',
        '--quiet',
        '--message',
        'baseline',
    );
}

function writeSafeDtoChange(worktree) {
    const source = path.join(
        worktree,
        'src/main/java/cloud/bamsongi/albammate/notification/dto/NotificationSummary.java',
    );
    fs.mkdirSync(path.dirname(source), { recursive: true });
    fs.writeFileSync(source, 'public record NotificationSummary(long id) {}\n', 'utf8');
}

function createOutsideDirectory(t) {
    const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'backend-test-manifest-outside-'));
    t.after(() => fs.rmSync(directory, { recursive: true, force: true }));
    return directory;
}

const keywords = (errors) => errors.map((error) => error.keyword);

test('PostgreSQL 불필요 변경의 H2 및 T-ID별 복수 evidence를 검증한다', (t) => {
    const worktree = createWorktree(t);

    assert.deepEqual(validate(validPacket(), validManifest(), worktree), []);
});

test('PostgreSQL 필수 변경은 postgresTest exact selector가 있으면 통과한다', (t) => {
    const worktree = createWorktree(t);
    const packet = validPacket();
    const manifest = validManifest();
    requirePostgres(packet, manifest);

    assert.deepEqual(
        validate(packet, manifest, worktree, classification(POSTGRES_DECISIONS.REQUIRED)),
        [],
    );
});

test('required와 needs-review를 postgresRequired false로 제출하면 거부한다', (t) => {
    const worktree = createWorktree(t);

    assert.ok(
        keywords(
            validate(
                validPacket(),
                validManifest(),
                worktree,
                classification(POSTGRES_DECISIONS.REQUIRED),
            ),
        ).includes('postgresRequired'),
    );
    assert.ok(
        keywords(
            validate(
                validPacket(),
                validManifest(),
                worktree,
                classification(POSTGRES_DECISIONS.NEEDS_REVIEW),
            ),
        ).includes('postgresNeedsReview'),
    );
});

test('needs-review는 PostgreSQL evidence를 포함한 안전한 true 결정으로만 해소한다', (t) => {
    const worktree = createWorktree(t);
    const packet = validPacket();
    const manifest = validManifest();
    requirePostgres(packet, manifest);

    assert.deepEqual(
        validate(packet, manifest, worktree, classification(POSTGRES_DECISIONS.NEEDS_REVIEW)),
        [],
    );
});

test('postgresRequired와 selector evidence의 모순을 거부한다', (t) => {
    const worktree = createWorktree(t);
    const missingEvidencePacket = validPacket();
    const missingEvidenceManifest = validManifest();
    requirePostgres(missingEvidencePacket, missingEvidenceManifest);
    missingEvidenceManifest.tests[1].evidence = [
        { ...missingEvidenceManifest.tests[0].evidence[0] },
    ];
    assert.ok(
        keywords(
            validate(
                missingEvidencePacket,
                missingEvidenceManifest,
                worktree,
                classification(POSTGRES_DECISIONS.REQUIRED),
            ),
        ).includes('postgresEvidence'),
    );

    const unexpectedEvidence = validManifest();
    unexpectedEvidence.tests[1].evidence = [
        {
            task: 'postgresTest',
            source: 'src/postgresTest/java/cloud/bamsongi/NotificationReadPostgresTest.java',
            selector: 'cloud.bamsongi.NotificationReadPostgresTest.읽음_시각을_보존한다',
        },
    ];
    assert.ok(
        keywords(validate(validPacket(), unexpectedEvidence, worktree)).includes(
            'unexpectedPostgresEvidence',
        ),
    );
});

test('packet과 manifest의 PostgreSQL 결정 및 근거가 다르면 거부한다', (t) => {
    const worktree = createWorktree(t);
    const decisionMismatch = validManifest();
    decisionMismatch.postgresRequired = true;
    assert.ok(
        keywords(validate(validPacket(), decisionMismatch, worktree)).includes(
            'postgresDecisionMismatch',
        ),
    );

    const reasonMismatch = validManifest();
    reasonMismatch.postgresRequirementReasons = ['서로 다른 근거'];
    assert.ok(
        keywords(validate(validPacket(), reasonMismatch, worktree)).includes(
            'postgresReasonMismatch',
        ),
    );
});

test('서로 다른 T-ID가 같은 evidence를 공유할 수 있다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[1].evidence = [{ ...manifest.tests[0].evidence[0] }];

    assert.deepEqual(validate(validPacket(), manifest, worktree), []);
});

test('T-ID 누락, 재정렬과 중복을 거부한다', (t) => {
    const worktree = createWorktree(t);

    const missing = validManifest();
    missing.tests.pop();
    assert.ok(keywords(validate(validPacket(), missing, worktree)).includes('tIdCount'));

    const reordered = validManifest();
    reordered.tests.reverse();
    assert.ok(keywords(validate(validPacket(), reordered, worktree)).includes('tIdOrder'));

    const duplicated = validManifest();
    duplicated.tests[1].id = 'T1';
    assert.ok(keywords(validate(validPacket(), duplicated, worktree)).includes('uniqueTId'));
});

test('wildcard 또는 클래스·메서드가 불명확한 selector를 거부한다', (t) => {
    const worktree = createWorktree(t);
    const invalidSelectors = [
        'cloud.bamsongi.*Test.알림을_읽음_처리한다',
        'NotificationReadServiceTest.알림을_읽음_처리한다',
        'cloud.bamsongi.NotificationReadServiceTest',
    ];

    for (const selector of invalidSelectors) {
        const manifest = validManifest();
        manifest.tests[0].evidence[0].selector = selector;
        assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('exactSelector'), selector);
    }
});

test('task와 source set이 다르면 거부한다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[0].evidence[0].task = 'postgresTest';

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('sourceSet'));
});

test('상위 경로로 다른 source set에 진입하는 source를 거부한다', (t) => {
    const worktree = createWorktree(t);
    const packet = validPacket();
    const manifest = validManifest();
    requirePostgres(packet, manifest);
    manifest.tests[1].evidence[0].source =
        'src/postgresTest/java/../../test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    manifest.tests[1].evidence[0].selector =
        'cloud.bamsongi.NotificationReadServiceTest.알림을_읽음_처리한다';

    assert.ok(keywords(validate(packet, manifest, worktree)).includes('sourcePath'));
});

test('test와 postgresTest 이외의 task를 거부한다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[0].evidence[0].task = 'integrationTest';

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('enum'));
});

test('미존재 source와 worktree 밖 source를 거부한다', (t) => {
    const worktree = createWorktree(t);

    const missing = validManifest();
    missing.tests[0].evidence[0].source = 'src/test/java/cloud/bamsongi/MissingTest.java';
    assert.ok(keywords(validate(validPacket(), missing, worktree)).includes('sourceExists'));

    const outside = validManifest();
    outside.tests[0].evidence[0].source = '../OutsideTest.java';
    assert.ok(keywords(validate(validPacket(), outside, worktree)).includes('sourcePath'));
});

test('같은 T-ID 안의 중복 task와 selector를 거부한다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[0].evidence.push({
        ...manifest.tests[0].evidence[0],
        source: 'src/test/java/cloud/bamsongi/NotificationPolicyTest.java',
    });

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('duplicateEvidence'));
});

test('source에 선언되지 않은 selector 메서드를 거부한다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[0].evidence[0].selector = 'cloud.bamsongi.NotificationReadServiceTest.없는_메서드';

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('selectorMethod'));
});

test('source package와 다른 selector 클래스 FQCN을 거부한다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        javaSource(source, ['알림을_읽음_처리한다']).replace(
            'package cloud.bamsongi;',
            'package cloud.other;',
        ),
        'utf8',
    );

    assert.ok(
        keywords(validate(validPacket(), validManifest(), worktree)).includes('selectorClass'),
    );
});

test('source에 selector의 최상위 클래스 선언이 없으면 거부한다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        'package cloud.bamsongi;\n\nimport org.junit.jupiter.api.Test;\n\nclass OtherTest {\n    @Test\n    void 알림을_읽음_처리한다() {\n    }\n}\n',
        'utf8',
    );

    assert.ok(
        keywords(validate(validPacket(), validManifest(), worktree)).includes('selectorClass'),
    );
});

test('테스트 어노테이션이 없는 void helper selector를 거부한다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        'package cloud.bamsongi;\n\nclass NotificationReadServiceTest {\n    void 보조_메서드() {\n    }\n}\n',
        'utf8',
    );
    const manifest = validManifest();
    manifest.tests[0].evidence[0].selector =
        'cloud.bamsongi.NotificationReadServiceTest.보조_메서드';

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('selectorMethod'));
});

test('JUnit이 아닌 같은 이름의 Test 어노테이션은 selector evidence로 허용하지 않는다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        'package cloud.bamsongi;\n\n@interface Test {\n}\n\nclass NotificationReadServiceTest {\n    @Test\n    void 알림을_읽음_처리한다() {\n    }\n}\n',
        'utf8',
    );

    assert.ok(
        keywords(validate(validPacket(), validManifest(), worktree)).includes('selectorMethod'),
    );
});

test('JUnit import를 가리는 중첩 동명 Test 어노테이션을 거부한다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        'package cloud.bamsongi;\n\nimport org.junit.jupiter.api.Test;\n\nclass NotificationReadServiceTest {\n    @interface Test {\n    }\n\n    @Test\n    void 알림을_읽음_처리한다() {\n    }\n}\n',
        'utf8',
    );

    assert.ok(
        keywords(validate(validPacket(), validManifest(), worktree)).includes('selectorMethod'),
    );
});

test('JUnit이 발견하지 않는 test method modifier를 거부한다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';

    for (const modifier of ['private', 'static', 'abstract']) {
        const terminator = modifier === 'abstract' ? ';' : ' {\n    }';
        fs.writeFileSync(
            path.join(worktree, source),
            `package cloud.bamsongi;\n\nimport org.junit.jupiter.api.Test;\n\nclass NotificationReadServiceTest {\n    @Test\n    ${modifier} void 알림을_읽음_처리한다()${terminator}\n}\n`,
            'utf8',
        );

        assert.ok(
            keywords(validate(validPacket(), validManifest(), worktree)).includes(
                'selectorMethod',
            ),
            modifier,
        );
    }
});

test('abstract 최상위 테스트 클래스의 selector를 거부한다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        'package cloud.bamsongi;\n\nimport org.junit.jupiter.api.Test;\n\nabstract class NotificationReadServiceTest {\n    @Test\n    void 알림을_읽음_처리한다() {\n    }\n}\n',
        'utf8',
    );

    assert.ok(
        keywords(validate(validPacket(), validManifest(), worktree)).includes('selectorClass'),
    );
});

test('text block의 escaped delimiter 뒤 가짜 테스트 선언을 허용하지 않는다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        'package cloud.bamsongi;\n\nimport org.junit.jupiter.api.Test;\n\nclass NotificationReadServiceTest {\n    String value = """\n        \\"""\n        @Test\n        void 가짜_테스트() {\n        }\n        \\"""\n        """;\n}\n',
        'utf8',
    );
    const manifest = validManifest();
    manifest.tests[0].evidence[0].selector =
        'cloud.bamsongi.NotificationReadServiceTest.가짜_테스트';

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('selectorMethod'));
});

test('Java Unicode escape 전처리가 필요한 source는 fail-closed 한다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        'package cloud.bamsongi;\n\nimport org.junit.jupiter.api.Test;\n\nclass NotificationReadServiceTest {\n    String marker = "\\u0022";\n    @Test\n    void 알림을_읽음_처리한다() {\n    }\n}\n',
        'utf8',
    );

    assert.ok(
        keywords(validate(validPacket(), validManifest(), worktree)).includes('sourceSyntax'),
    );
});

test('중첩 클래스에만 선언된 테스트 메서드를 바깥 클래스 selector로 허용하지 않는다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        'package cloud.bamsongi;\n\nimport org.junit.jupiter.api.Test;\n\nclass NotificationReadServiceTest {\n    class Inner {\n        @Test\n        void 내부_테스트() {\n        }\n    }\n}\n',
        'utf8',
    );
    const manifest = validManifest();
    manifest.tests[0].evidence[0].selector =
        'cloud.bamsongi.NotificationReadServiceTest.내부_테스트';

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('selectorMethod'));
});

test('ParameterizedTest 메서드는 실행 가능한 selector로 허용한다', (t) => {
    const worktree = createWorktree(t);
    const source = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(
        path.join(worktree, source),
        'package cloud.bamsongi;\n\nimport org.junit.jupiter.params.ParameterizedTest;\n\nclass NotificationReadServiceTest {\n    @ParameterizedTest\n    @EnumSource\n    void 알림을_읽음_처리한다(String value) {\n    }\n}\n',
        'utf8',
    );

    assert.deepEqual(validate(validPacket(), validManifest(), worktree), []);
});

test('메서드명이 다른 선언의 접미사여도 거부한다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[0].evidence[0].selector = 'cloud.bamsongi.NotificationReadServiceTest.읽음_처리한다';

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('selectorMethod'));
});

test('검증하지 않는 중첩 클래스 selector를 거부한다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[0].evidence[0].selector =
        'cloud.bamsongi.NotificationReadServiceTest$읽음.아직_없는_메서드';

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('exactSelector'));
});

test('Red 상태, 명령과 실행 결과 필드를 schema에서 거부한다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[0].status = 'observed';
    manifest.tests[0].evidence[0].command = '.\\gradlew.bat test';

    const errors = validate(validPacket(), manifest, worktree);

    assert.ok(errors.filter((error) => error.keyword === 'additionalProperties').length >= 2);
});

test('CLI는 유효 manifest는 0, 무효 manifest는 1로 종료한다', (t) => {
    const worktree = createWorktree(t);
    initGitRepo(worktree);
    writeSafeDtoChange(worktree);
    const outside = createOutsideDirectory(t);
    const packetPath = path.join(outside, 'packet.json');
    const manifestPath = path.join(outside, 'manifest.json');
    fs.writeFileSync(packetPath, JSON.stringify(validPacket()), 'utf8');
    fs.writeFileSync(manifestPath, JSON.stringify(validManifest()), 'utf8');

    const valid = spawnSync(
        process.execPath,
        [scriptPath, '--packet', packetPath, '--manifest', manifestPath, '--worktree', worktree],
        { encoding: 'utf8' },
    );
    assert.equal(valid.status, 0, valid.stderr);
    assert.match(valid.stdout, /manifest 검증 통과/);

    const invalidManifest = validManifest();
    invalidManifest.tests[0].evidence[0].selector = '*';
    fs.writeFileSync(manifestPath, JSON.stringify(invalidManifest), 'utf8');
    const invalid = spawnSync(
        process.execPath,
        [scriptPath, '--packet', packetPath, '--manifest', manifestPath, '--worktree', worktree],
        { encoding: 'utf8' },
    );
    assert.equal(invalid.status, 1);
    assert.match(invalid.stderr, /exactSelector/);
});

test('CLI는 Flyway 변경의 PostgreSQL selector 누락을 차단한다', (t) => {
    const worktree = createWorktree(t);
    initGitRepo(worktree);
    const migration = path.join(worktree, 'src/main/resources/db/migration/V42__notification.sql');
    fs.mkdirSync(path.dirname(migration), { recursive: true });
    fs.writeFileSync(migration, 'alter table notifications add column memo text;\n', 'utf8');

    const outside = createOutsideDirectory(t);
    const packetPath = path.join(outside, 'packet.json');
    const manifestPath = path.join(outside, 'manifest.json');
    const packet = validPacket();
    const manifest = validManifest();
    packet.allowedPaths = ['src/main/resources/db/migration/'];
    fs.writeFileSync(packetPath, JSON.stringify(packet), 'utf8');
    fs.writeFileSync(manifestPath, JSON.stringify(manifest), 'utf8');

    const args = [scriptPath, '--packet', packetPath, '--manifest', manifestPath, '--worktree', worktree];
    const missing = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.equal(missing.status, 1);
    assert.match(missing.stderr, /postgresRequired/);
    assert.match(missing.stderr, /flyway-or-sql/);

    requirePostgres(packet, manifest);
    fs.writeFileSync(packetPath, JSON.stringify(packet), 'utf8');
    fs.writeFileSync(manifestPath, JSON.stringify(manifest), 'utf8');
    const covered = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.equal(covered.status, 0, covered.stderr);
    assert.match(covered.stdout, /PostgreSQL required/);
});

test('지원하는 경로 패턴만 매칭한다', () => {
    assert.ok(matchesPathPattern('build.gradle', 'build.gradle'));
    assert.ok(!matchesPathPattern('build.gradle', 'frontend/build.gradle'));
    assert.ok(matchesPathPattern('src/main/java/a/**', 'src/main/java/a/B.java'));
    assert.ok(!matchesPathPattern('src/main/java/a/**', 'src/main/java/ab/B.java'));
    assert.ok(matchesPathPattern('src/test/', 'src/test/java/a/BTest.java'));
    assert.ok(matchesPathPattern('**/AGENTS.md', 'AGENTS.md'));
    assert.ok(matchesPathPattern('**/AGENTS.md', 'src/main/resources/db/migration/AGENTS.md'));
    assert.ok(!matchesPathPattern('**/AGENTS.md', 'docs/AGENTS.md.bak'));
});

test('지원하지 않는 와일드카드 패턴을 통과시키지 않는다', () => {
    const packet = validPacket();
    packet.allowedPaths = ['src/main/java/**/notification/**'];
    const errors = auditChangedPaths(packet, ['src/main/java/cloud/notification/A.java']);

    assert.deepEqual(keywords(errors), ['pathPattern']);
});

test('allowedPaths 안의 변경만 허용한다', () => {
    const packet = validPacket();

    assert.deepEqual(
        auditChangedPaths(packet, [
            'src/main/java/cloud/bamsongi/albammate/notification/Notification.java',
        ]),
        [],
    );
    assert.deepEqual(
        keywords(auditChangedPaths(packet, ['src/main/java/cloud/bamsongi/albammate/room/Room.java'])),
        ['allowedPath'],
    );
});

test('forbiddenPaths 변경을 거부한다', () => {
    const packet = validPacket();
    packet.allowedPaths = ['frontend/'];
    packet.forbiddenPaths = ['frontend/src/generated/'];
    const errors = auditChangedPaths(packet, ['frontend/src/generated/api.ts']);

    assert.deepEqual(keywords(errors), ['forbiddenPath']);
});

test('allowedPaths에 있어도 항상 read-only인 경로 변경을 거부한다', () => {
    const packet = validPacket();
    packet.allowedPaths = [...ALWAYS_READ_ONLY_PATTERNS, 'docs/API.md'];

    for (const changed of [
        'AGENTS.md',
        'src/main/resources/db/migration/AGENTS.md',
        'CLAUDE.md',
        'docs/PRD.md',
        'docs/P0-spec.md',
        'docs/CONVENTIONS.md',
        'docs/adr/chat/0049-chat-message-retention.md',
    ]) {
        assert.deepEqual(keywords(auditChangedPaths(packet, [changed])), ['alwaysReadOnly'], changed);
    }

    // 조건부로 허용되는 정본 문서는 allowedPaths에 있으면 통과한다.
    assert.deepEqual(auditChangedPaths(packet, ['docs/API.md']), []);
});

test('추적되지 않은 새 파일도 변경 경로로 모은다', (t) => {
    const worktree = createWorktree(t);
    initGitRepo(worktree);
    const tracked = 'src/test/java/cloud/bamsongi/NotificationReadServiceTest.java';
    fs.writeFileSync(path.join(worktree, 'untracked.md'), '새 파일', 'utf8');
    fs.appendFileSync(path.join(worktree, tracked), '\n// 변경\n', 'utf8');

    const changed = changedPathsIn(worktree);

    assert.deepEqual(changed, [tracked, 'untracked.md'].sort());
});

test('CLI는 allowedPaths 밖 변경을 감사에서 차단한다', (t) => {
    const worktree = createWorktree(t);
    initGitRepo(worktree);
    const outside = createOutsideDirectory(t);
    const packetPath = path.join(outside, 'packet.json');
    const manifestPath = path.join(outside, 'manifest.json');
    fs.writeFileSync(packetPath, JSON.stringify(validPacket()), 'utf8');
    fs.writeFileSync(manifestPath, JSON.stringify(validManifest()), 'utf8');
    fs.writeFileSync(path.join(worktree, 'AGENTS.md'), '규약을 바꾼다', 'utf8');

    const result = spawnSync(
        process.execPath,
        [scriptPath, '--packet', packetPath, '--manifest', manifestPath, '--worktree', worktree],
        { encoding: 'utf8' },
    );

    assert.equal(result.status, 1);
    assert.match(result.stderr, /alwaysReadOnly/);
});

test('rename의 원본 경로도 변경 경로로 감사한다', (t) => {
    const worktree = createWorktree(t);
    const protectedPath = path.join(worktree, 'docs/adr/platform');
    fs.mkdirSync(protectedPath, { recursive: true });
    fs.writeFileSync(path.join(protectedPath, '0008-flyway.md'), '# ADR\n', 'utf8');
    initGitRepo(worktree);

    // 항상 read-only인 ADR을 허용 경로로 옮긴다. rename을 감지하면 원본 경로가 사라진다.
    const moved = 'src/test/java/cloud/bamsongi/moved.md';
    fs.renameSync(path.join(protectedPath, '0008-flyway.md'), path.join(worktree, moved));
    spawnSync('git', ['-C', worktree, 'add', '--all'], { encoding: 'utf8', windowsHide: true });

    const changed = changedPathsIn(worktree);

    assert.ok(changed.includes('docs/adr/platform/0008-flyway.md'), JSON.stringify(changed));
    assert.ok(changed.includes(moved));

    const packet = validPacket();
    packet.allowedPaths = ['src/test/'];
    assert.deepEqual(keywords(auditChangedPaths(packet, changed)), ['alwaysReadOnly']);
});

test('커밋된 head의 범위 밖 변경을 base 비교로 감사한다', (t) => {
    const worktree = createWorktree(t);
    fs.mkdirSync(path.join(worktree, 'docs/adr/platform'), { recursive: true });
    fs.writeFileSync(path.join(worktree, 'docs/adr/platform/0008-flyway.md'), '# ADR\n', 'utf8');
    initGitRepo(worktree);

    const git = (...args) =>
        spawnSync('git', ['-C', worktree, ...args], { encoding: 'utf8', windowsHide: true });
    fs.appendFileSync(path.join(worktree, 'docs/adr/platform/0008-flyway.md'), '변경\n', 'utf8');
    git('add', '--all');
    git('-c', 'user.name=t', '-c', 'user.email=t@e.com', 'commit', '--quiet', '-m', 'ADR 변경');

    // 커밋 뒤 worktree가 깨끗하면 base 없이는 감사 대상이 비어 분류도 needs-review가 된다.
    assert.deepEqual(changedPathsIn(worktree), []);

    const withBase = changedPathsIn(worktree, 'HEAD~1');
    assert.deepEqual(withBase, ['docs/adr/platform/0008-flyway.md']);
    assert.deepEqual(keywords(auditChangedPaths(validPacket(), withBase)), ['alwaysReadOnly']);
});

test('CLI가 --base로 커밋된 범위 밖 변경을 차단한다', (t) => {
    const worktree = createWorktree(t);
    initGitRepo(worktree);
    const outside = createOutsideDirectory(t);
    const packetPath = path.join(outside, 'packet.json');
    const manifestPath = path.join(outside, 'manifest.json');
    fs.writeFileSync(packetPath, JSON.stringify(validPacket()), 'utf8');
    fs.writeFileSync(manifestPath, JSON.stringify(validManifest()), 'utf8');

    const git = (...args) =>
        spawnSync('git', ['-C', worktree, ...args], { encoding: 'utf8', windowsHide: true });
    fs.writeFileSync(path.join(worktree, 'AGENTS.md'), '규약을 바꾼다\n', 'utf8');
    git('add', '--all');
    git('-c', 'user.name=t', '-c', 'user.email=t@e.com', 'commit', '--quiet', '-m', 'AGENTS 변경');

    const args = [scriptPath, '--packet', packetPath, '--manifest', manifestPath, '--worktree', worktree];
    const withoutBase = spawnSync(process.execPath, args, { encoding: 'utf8' });
    assert.equal(withoutBase.status, 1);
    assert.match(withoutBase.stderr, /postgresNeedsReview/);

    const withBase = spawnSync(process.execPath, [...args, '--base', 'HEAD~1'], { encoding: 'utf8' });
    assert.equal(withBase.status, 1);
    assert.match(withBase.stderr, /alwaysReadOnly/);
});
