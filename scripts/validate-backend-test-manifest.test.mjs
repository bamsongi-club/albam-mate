import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

import {
    DEFAULT_MANIFEST_SCHEMA_PATH,
    validateBackendTestManifest,
} from './validate-backend-test-manifest.mjs';
import { DEFAULT_SCHEMA_PATH as DEFAULT_PACKET_SCHEMA_PATH } from './validate-packet.mjs';

const packetSchema = JSON.parse(fs.readFileSync(DEFAULT_PACKET_SCHEMA_PATH, 'utf8'));
const manifestSchema = JSON.parse(fs.readFileSync(DEFAULT_MANIFEST_SCHEMA_PATH, 'utf8'));
const scriptPath = fileURLToPath(new URL('./validate-backend-test-manifest.mjs', import.meta.url));
const approvedComment = 'https://github.com/bamsongi-club/albam-mate/issues/14#issuecomment-123456789';

function validPacket() {
    return {
        schemaVersion: 3,
        workItem: {
            kind: 'issue',
            id: '#14',
            summary: '알림 읽음 처리를 구현한다',
        },
        canonicalSources: [{ path: 'docs/P1-spec.md', section: 'NOTI-03' }],
        allowedPaths: ['src/main/java/cloud/bamsongi/albammate/notification/'],
        forbiddenPaths: ['frontend/'],
        completionCriteria: ['승인된 알림만 읽음 처리한다'],
        requiredTests: [
            { id: 'T1', intent: '알림을 읽음 처리한다', sourceRef: approvedComment },
            { id: 'T2', intent: 'PostgreSQL에서 읽음 시각을 보존한다', sourceRef: approvedComment },
        ],
        testContractApproval: { issueNumber: 14, commentUrl: approvedComment },
        confirmedDecisions: ['이슈 코멘트의 전체 T-ID를 사용한다'],
    };
}

function validManifest() {
    return {
        schemaVersion: 1,
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
                        task: 'postgresTest',
                        source: 'src/postgresTest/java/cloud/bamsongi/NotificationReadPostgresTest.java',
                        selector: 'cloud.bamsongi.NotificationReadPostgresTest.읽음_시각을_보존한다',
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
    const body = methods.map((method) => `    @Test\n    void ${method}() {\n    }\n`).join('\n');
    return `class ${className} {\n${body}}\n`;
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

function validate(packet, manifest, worktree) {
    return validateBackendTestManifest(packet, manifest, worktree, packetSchema, manifestSchema);
}

const keywords = (errors) => errors.map((error) => error.keyword);

test('H2와 PostgreSQL 및 T-ID별 복수 evidence를 검증한다', (t) => {
    const worktree = createWorktree(t);

    assert.deepEqual(validate(validPacket(), validManifest(), worktree), []);
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
    assert.ok(keywords(validate(validPacket(), outside, worktree)).includes('worktreePath'));
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

test('메서드명이 다른 선언의 접미사여도 거부한다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[0].evidence[0].selector = 'cloud.bamsongi.NotificationReadServiceTest.읽음_처리한다';

    assert.ok(keywords(validate(validPacket(), manifest, worktree)).includes('selectorMethod'));
});

test('중첩 클래스 selector는 클래스 일치까지만 확인한다', (t) => {
    const worktree = createWorktree(t);
    const manifest = validManifest();
    manifest.tests[0].evidence[0].selector =
        'cloud.bamsongi.NotificationReadServiceTest$읽음.아직_없는_메서드';

    assert.deepEqual(validate(validPacket(), manifest, worktree), []);
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
    const packetPath = path.join(worktree, 'packet.json');
    const manifestPath = path.join(worktree, 'manifest.json');
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
