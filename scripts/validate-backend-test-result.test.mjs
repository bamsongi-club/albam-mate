import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

import {
    DEFAULT_SCHEMA_PATH,
    canonicalJson,
    computeWorktreeSnapshot,
    sha256,
    validateBackendTestResult,
} from './validate-backend-test-result.mjs';

const schema = JSON.parse(fs.readFileSync(DEFAULT_SCHEMA_PATH, 'utf8'));
const scriptPath = fileURLToPath(new URL('./validate-backend-test-result.mjs', import.meta.url));
const baseCommit = 'a'.repeat(40);
const implementationDiffHash = 'b'.repeat(64);
const packetHash = 'c'.repeat(64);
const trackedDiffHash = 'd'.repeat(64);
const evidenceHash = 'e'.repeat(64);

function expected() {
    return {
        baseCommit,
        implementationDiffHash,
        packetHash,
        trackedDiffHash,
        tests: [
            { id: 'T1', command: 'node --test scripts/example.test.mjs' },
            { id: 'T2', command: 'node scripts/check-example.mjs' },
        ],
    };
}

function snapshots() {
    const snapshot = { baseCommit, implementationDiffHash, packetHash };
    return {
        snapshot,
        startedSnapshot: { ...snapshot, trackedDiffHash },
        finishedSnapshot: { ...snapshot, trackedDiffHash },
    };
}

function audit() {
    return {
        productionSourceModified: false,
        testSourceModified: false,
        stagePerformed: false,
        commitPerformed: false,
        pushPerformed: false,
        pullRequestCreated: false,
    };
}

function result(verdicts = ['pass', 'pass']) {
    const testResults = expected().tests.map((item, index) => ({
        testId: item.id,
        command: item.command,
        durationMs: index + 1,
        exitCode: verdicts[index] === 'pass' ? 0 : verdicts[index] === 'fail' ? 1 : null,
        verdict: verdicts[index],
        evidenceHash: verdicts[index] === 'unverified' ? null : evidenceHash,
        notRunReason: verdicts[index] === 'unverified' ? 'Docker daemon is unavailable.' : null,
    }));
    return {
        schemaVersion: 1,
        ...snapshots(),
        audit: audit(),
        testResults,
        overallVerdict: verdicts.includes('fail') ? 'fail' : verdicts.includes('unverified') ? 'unverified' : 'pass',
        overallReason: verdicts.includes('unverified') ? 'One or more approved commands were not run.' : null,
    };
}

const keywords = (errors) => errors.map((error) => error.keyword);

function git(worktree, args, encoding = 'utf8') {
    return execFileSync('git', args, { cwd: worktree, encoding });
}

test('정상 실행 결과는 세 snapshot과 승인 명령을 대조해 통과한다', () => {
    assert.deepEqual(validateBackendTestResult(result(), schema, expected()), []);
});

test('공통 snapshot CLI는 staged, unstaged, 정렬된 untracked bytes hash의 canonical seed를 출력한다', (t) => {
    const worktree = fs.mkdtempSync(path.join(os.tmpdir(), 'backend-snapshot-'));
    t.after(() => fs.rmSync(worktree, { recursive: true, force: true }));
    git(worktree, ['init', '-q']);
    git(worktree, ['config', 'core.autocrlf', 'false']);
    git(worktree, ['config', 'user.email', 'tester@example.com']);
    git(worktree, ['config', 'user.name', 'Backend Tester']);
    fs.writeFileSync(path.join(worktree, 'staged.txt'), 'before\n', 'utf8');
    fs.writeFileSync(path.join(worktree, 'unstaged.txt'), 'before\n', 'utf8');
    git(worktree, ['add', 'staged.txt', 'unstaged.txt']);
    git(worktree, ['commit', '-qm', 'baseline']);
    fs.writeFileSync(path.join(worktree, 'staged.txt'), 'after\n', 'utf8');
    git(worktree, ['add', 'staged.txt']);
    fs.writeFileSync(path.join(worktree, 'unstaged.txt'), 'after\n', 'utf8');
    fs.writeFileSync(path.join(worktree, 'z-untracked.txt'), 'z\n', 'utf8');
    fs.writeFileSync(path.join(worktree, 'a-untracked.txt'), 'a\n', 'utf8');

    const stagedBinaryDiffHash = sha256(git(worktree, ['diff', '--cached', '--binary'], 'buffer'));
    const unstagedBinaryDiffHash = sha256(git(worktree, ['diff', '--binary'], 'buffer'));
    const untrackedFiles = ['a-untracked.txt', 'z-untracked.txt'].map((relativePath) => ({
        path: relativePath,
        sha256: createHash('sha256').update(fs.readFileSync(path.join(worktree, relativePath))).digest('hex'),
    }));
    const canonicalSeed = { stagedBinaryDiffHash, unstagedBinaryDiffHash, untrackedFiles };
    const expectedSnapshot = {
        baseCommit: git(worktree, ['rev-parse', 'HEAD']).trim(),
        implementationDiffHash: sha256(Buffer.from(canonicalJson(canonicalSeed), 'utf8')),
        trackedDiffHash: sha256(Buffer.from(canonicalJson({ stagedBinaryDiffHash, unstagedBinaryDiffHash }), 'utf8')),
        canonicalSeed,
    };

    assert.deepEqual(computeWorktreeSnapshot(worktree), expectedSnapshot);
    const cli = spawnSync(process.execPath, [scriptPath, '--snapshot', worktree], { encoding: 'utf8' });
    assert.equal(cli.status, 0, cli.stderr);
    assert.deepEqual(JSON.parse(cli.stdout), expectedSnapshot);
});

test('non-zero 명령은 관련 T-ID와 종합 fail이어야 한다', () => {
    assert.deepEqual(validateBackendTestResult(result(['pass', 'fail']), schema, expected()), []);
    const invalid = result(['pass', 'fail']);
    invalid.overallVerdict = 'pass';

    assert.ok(keywords(validateBackendTestResult(invalid, schema, expected())).includes('overallVerdict'));
});

test('timeout, 환경 제약 또는 미실행은 구체적 사유가 있는 unverified만 허용한다', () => {
    assert.deepEqual(validateBackendTestResult(result(['unverified', 'unverified']), schema, expected()), []);
    const timeout = result(['unverified', 'pass']);
    timeout.testResults[0].evidenceHash = evidenceHash;
    timeout.testResults[0].notRunReason = 'The command exceeded its approved timeout.';
    assert.deepEqual(validateBackendTestResult(timeout, schema, expected()), []);

    const invalid = result(['unverified', 'pass']);
    invalid.testResults[0].notRunReason = null;

    assert.ok(keywords(validateBackendTestResult(invalid, schema, expected())).includes('unverifiedReason'));
});

test('snapshot hash, T-ID 개수·순서·명령 불일치는 거부한다', () => {
    const snapshotMismatch = result();
    snapshotMismatch.snapshot.implementationDiffHash = 'f'.repeat(64);
    assert.ok(keywords(validateBackendTestResult(snapshotMismatch, schema, expected())).includes('snapshotMatch'));

    const missing = result();
    missing.testResults.pop();
    assert.ok(keywords(validateBackendTestResult(missing, schema, expected())).includes('testCount'));

    const reordered = result();
    [reordered.testResults[0], reordered.testResults[1]] = [reordered.testResults[1], reordered.testResults[0]];
    assert.ok(keywords(validateBackendTestResult(reordered, schema, expected())).includes('testOrder'));

    const duplicate = result();
    duplicate.testResults[1].testId = 'T1';
    assert.ok(keywords(validateBackendTestResult(duplicate, schema, expected())).includes('duplicateTId'));

    const changedCommand = result();
    changedCommand.testResults[0].command = 'node --test other.test.mjs';
    assert.ok(keywords(validateBackendTestResult(changedCommand, schema, expected())).includes('approvedCommand'));
});

test('초기 fresh tester의 서로 다른 implementation diff hash는 공통 snapshot 검증에서 거부한다', () => {
    const expectedSnapshot = expected();
    expectedSnapshot.implementationDiffHash = 'dfed4010e841258cfd2bfa94dd2027943c5c1b3a72f51c256667dee007e99219';
    const actual = result();
    for (const snapshot of [actual.snapshot, actual.startedSnapshot, actual.finishedSnapshot]) {
        snapshot.implementationDiffHash = 'd30d8de911f65b90212243d2225be023c964b284100553bfd212638e4d7cdc12';
    }

    assert.ok(keywords(validateBackendTestResult(actual, schema, expectedSnapshot)).includes('snapshotMatch'));
});

test('tester 금지 행위 audit true와 누락된 audit 필드는 모두 거부한다', () => {
    for (const field of Object.keys(audit())) {
        const invalid = result();
        invalid.audit[field] = true;
        assert.ok(
            keywords(validateBackendTestResult(invalid, schema, expected())).includes('forbiddenTesterAction'),
            `${field} true가 거부되지 않았습니다.`,
        );
    }

    const missing = result();
    delete missing.audit.pushPerformed;
    assert.ok(keywords(validateBackendTestResult(missing, schema, expected())).includes('required'));
});

test('tracked diff가 실행 중 변경되면 종합 pass와 unverified 결과 모두 expected snapshot 불일치로 거부한다', () => {
    const changed = result();
    changed.finishedSnapshot.trackedDiffHash = 'f'.repeat(64);
    assert.ok(keywords(validateBackendTestResult(changed, schema, expected())).includes('overallVerdict'));

    changed.overallVerdict = 'unverified';
    changed.overallReason = 'Tracked diff changed while executing the approved commands.';
    assert.ok(keywords(validateBackendTestResult(changed, schema, expected())).includes('snapshotMatch'));
});

test('필수 필드 누락과 exit code/verdict 모순은 schema 또는 관계 검사에서 거부한다', () => {
    const malformed = result();
    delete malformed.testResults[0].command;
    assert.ok(keywords(validateBackendTestResult(malformed, schema, expected())).includes('required'));

    const contradictory = result();
    contradictory.testResults[0].exitCode = 0;
    contradictory.testResults[0].verdict = 'fail';
    contradictory.overallVerdict = 'fail';
    assert.ok(keywords(validateBackendTestResult(contradictory, schema, expected())).includes('failEvidence'));
});

test('CLI는 실제 JSON fixture를 검증하고 형식 오류는 non-zero로 종료한다', (t) => {
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'backend-test-result-'));
    t.after(() => fs.rmSync(tempDir, { recursive: true, force: true }));
    const resultPath = path.join(tempDir, 'result.json');
    const expectedPath = path.join(tempDir, 'expected.json');
    fs.writeFileSync(resultPath, JSON.stringify(result()), 'utf8');
    fs.writeFileSync(expectedPath, JSON.stringify(expected()), 'utf8');

    const valid = spawnSync(process.execPath, [scriptPath, '--result', resultPath, '--expected', expectedPath], {
        encoding: 'utf8',
    });
    assert.equal(valid.status, 0, valid.stderr);

    const malformed = result();
    delete malformed.testResults[0].command;
    fs.writeFileSync(resultPath, JSON.stringify(malformed), 'utf8');
    const invalid = spawnSync(process.execPath, [scriptPath, '--result', resultPath, '--expected', expectedPath], {
        encoding: 'utf8',
    });
    assert.equal(invalid.status, 1);
    assert.match(invalid.stderr, /required/);
});
