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
    validateExpected,
} from './validate-backend-test-result.mjs';

const schema = JSON.parse(fs.readFileSync(DEFAULT_SCHEMA_PATH, 'utf8'));
const scriptPath = fileURLToPath(new URL('./validate-backend-test-result.mjs', import.meta.url));
const baseCommit = 'a'.repeat(40);
const implementationDiffHash = 'b'.repeat(64);
const packetHash = 'c'.repeat(64);
const trackedDiffHash = 'd'.repeat(64);
const evidenceHash = 'f'.repeat(64);
const preflightCheckNames = [
    'result-directory',
    'log-directory',
    'jna-directory',
    'gradle-wrapper',
    'docker',
];

function expected() {
    return {
        schemaVersion: 2,
        baseCommit,
        implementationDiffHash,
        packetHash,
        trackedDiffHash,
        executions: [
            { id: 'E1', command: 'node --test scripts/example.test.mjs', junitTasks: [] },
            { id: 'E2', command: 'node scripts/check-example.mjs', junitTasks: [] },
        ],
        tests: [
            { id: 'T1', executionIds: ['E1'], testSources: ['src/test/java/example/FirstTest.java'] },
            { id: 'T2', executionIds: ['E1', 'E2'], testSources: ['src/postgresTest/java/example/SecondTest.java'] },
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

function preflight(verdict = 'pass') {
    return {
        verdict,
        checks: preflightCheckNames.map((name) => {
            const checkVerdict = verdict === 'unverified' && name === 'docker' ? 'unverified' : 'pass';
            return {
                name,
                verdict: checkVerdict,
                detail: checkVerdict === 'pass' ? `${name} 확인 완료` : 'Docker daemon에 접근할 수 없다.',
            };
        }),
    };
}

function executionResult(execution, verdict = 'pass') {
    return {
        executionId: execution.id,
        command: execution.command,
        commandHash: sha256(Buffer.from(execution.command, 'utf8')),
        durationMs: 1,
        exitCode: verdict === 'pass' ? 0 : verdict === 'fail' ? 1 : null,
        verdict,
        evidenceHash: verdict === 'unverified' ? null : evidenceHash,
        junitEvidence: execution.junitTasks.map((task) => ({
            task,
            reportCount: 1,
            testCount: 1,
            reportHash: '1'.repeat(64),
        })),
        notRunReason: verdict === 'unverified' ? '실행 환경을 확인할 수 없다.' : null,
    };
}

function result(verdicts = ['pass', 'pass']) {
    const approved = expected();
    const executionResults = approved.executions.map((execution, index) => executionResult(execution, verdicts[index]));
    const byId = new Map(executionResults.map((execution) => [execution.executionId, execution]));
    const testResults = approved.tests.map((mapping) => {
        const executions = mapping.executionIds.map((id) => byId.get(id));
        const verdict = executions.some((execution) => execution.verdict === 'fail')
            ? 'fail'
            : executions.some((execution) => execution.verdict !== 'pass') ? 'unverified' : 'pass';
        return {
            testId: mapping.id,
            executionIds: mapping.executionIds,
            verdict,
            notRunReason: verdict === 'unverified' ? '연결 실행이 미검증이다.' : null,
        };
    });
    const overallVerdict = verdicts.includes('fail') ? 'fail' : verdicts.includes('unverified') ? 'unverified' : 'pass';
    return {
        schemaVersion: 2,
        ...snapshots(),
        preflight: preflight(),
        executionResults,
        testResults,
        overallVerdict,
        overallReason: overallVerdict === 'unverified' ? '하나 이상의 실행이 미검증이다.' : null,
    };
}

const keywords = (errors) => errors.map((error) => error.keyword);

function git(worktree, args, encoding = 'utf8') {
    return execFileSync('git', args, { cwd: worktree, encoding });
}

test('정상 실행 그래프 결과는 snapshot, 실행과 T-ID 매핑을 대조해 통과한다', () => {
    assert.deepEqual(validateBackendTestResult(result(), schema, expected()), []);
});

test('expected는 고유 실행과 T-ID 다대다 매핑을 강제한다', () => {
    const duplicate = expected();
    duplicate.executions[1].command = duplicate.executions[0].command;
    assert.throws(() => validateExpected(duplicate), /중복/);

    const dangling = expected();
    dangling.tests[0].executionIds = ['E9'];
    assert.throws(() => validateExpected(dangling), /존재하지 않는/);

    const missingSource = expected();
    missingSource.tests[0].testSources = [];
    assert.throws(() => validateExpected(missingSource), /testSources/);
});

test('공통 snapshot CLI는 staged, unstaged, 정렬된 untracked mode와 bytes hash의 canonical seed를 출력한다', (t) => {
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
        mode: '100644',
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

test('untracked mode와 symlink target은 같은 파일 bytes와 구분되어 snapshot에 반영된다', (t) => {
    const worktree = fs.mkdtempSync(path.join(os.tmpdir(), 'backend-snapshot-mode-'));
    t.after(() => fs.rmSync(worktree, { recursive: true, force: true }));
    git(worktree, ['init', '-q']);
    git(worktree, ['config', 'core.autocrlf', 'false']);
    git(worktree, ['config', 'user.email', 'tester@example.com']);
    git(worktree, ['config', 'user.name', 'Backend Tester']);
    fs.writeFileSync(path.join(worktree, 'baseline.txt'), 'baseline\n', 'utf8');
    git(worktree, ['add', 'baseline.txt']);
    git(worktree, ['commit', '-qm', 'baseline']);
    const regularPath = path.join(worktree, 'regular.txt');
    const symlinkPath = path.join(worktree, 'link.txt');
    fs.writeFileSync(regularPath, 'same bytes\n', 'utf8');
    fs.writeFileSync(symlinkPath, 'same bytes\n', 'utf8');
    const originalLstatSync = fs.lstatSync;
    const originalReadlinkSync = fs.readlinkSync;
    t.after(() => {
        fs.lstatSync = originalLstatSync;
        fs.readlinkSync = originalReadlinkSync;
    });
    let linkIsSymbolic = true;
    let regularExecutable = false;
    fs.lstatSync = (filePath, options) => {
        const stats = originalLstatSync(filePath, options);
        if (path.resolve(filePath) === symlinkPath && linkIsSymbolic) {
            return { mode: stats.mode, isFile: () => false, isSymbolicLink: () => true };
        }
        if (path.resolve(filePath) !== regularPath) return stats;
        return {
            mode: stats.mode | (regularExecutable ? 0o111 : 0),
            isFile: () => true,
            isSymbolicLink: () => false,
        };
    };
    fs.readlinkSync = (filePath, options) =>
        path.resolve(filePath) === symlinkPath ? 'same bytes\n' : originalReadlinkSync(filePath, options);

    const initial = computeWorktreeSnapshot(worktree);
    assert.deepEqual(initial.canonicalSeed.untrackedFiles.map(({ mode }) => mode), ['120000', '100644']);
    linkIsSymbolic = false;
    assert.notEqual(computeWorktreeSnapshot(worktree).implementationDiffHash, initial.implementationDiffHash);
    linkIsSymbolic = true;
    regularExecutable = true;
    assert.equal(computeWorktreeSnapshot(worktree).canonicalSeed.untrackedFiles[1].mode, '100755');
});

test('execution fail과 unverified를 관련 T-ID와 종합 verdict로 계산한다', () => {
    assert.deepEqual(validateBackendTestResult(result(['pass', 'fail']), schema, expected()), []);
    assert.deepEqual(validateBackendTestResult(result(['unverified', 'pass']), schema, expected()), []);

    const wrong = result(['pass', 'fail']);
    wrong.testResults[1].verdict = 'pass';
    assert.ok(keywords(validateBackendTestResult(wrong, schema, expected())).includes('testVerdict'));
});

test('JUnit task를 선언한 pass execution은 실제 report 증거를 가져야 한다', () => {
    const approved = expected();
    approved.executions[0].junitTasks = ['test'];
    const valid = result();
    valid.executionResults[0].junitEvidence = [{
        task: 'test', reportCount: 1, testCount: 2, reportHash: '1'.repeat(64),
    }];
    assert.deepEqual(validateBackendTestResult(valid, schema, approved), []);

    valid.executionResults[0].junitEvidence = [];
    assert.ok(keywords(validateBackendTestResult(valid, schema, approved)).includes('junitTasks'));
});

test('snapshot hash 불일치, 실행·T-ID 순서 변경을 거부한다', () => {
    const snapshotMismatch = result();
    snapshotMismatch.snapshot.packetHash = '0'.repeat(64);
    assert.ok(keywords(validateBackendTestResult(snapshotMismatch, schema, expected())).includes('snapshotMatch'));

    const finishedPacketMismatch = result();
    finishedPacketMismatch.finishedSnapshot.packetHash = '0'.repeat(64);
    assert.ok(keywords(validateBackendTestResult(finishedPacketMismatch, schema, expected())).includes('snapshotMatch'));

    const reorderedExecution = result();
    [reorderedExecution.executionResults[0], reorderedExecution.executionResults[1]] =
        [reorderedExecution.executionResults[1], reorderedExecution.executionResults[0]];
    assert.ok(keywords(validateBackendTestResult(reorderedExecution, schema, expected())).includes('executionOrder'));

    const changedMapping = result();
    changedMapping.testResults[0].executionIds = ['E2'];
    assert.ok(keywords(validateBackendTestResult(changedMapping, schema, expected())).includes('executionMapping'));
});

test('preflight 미검증과 실행 중 snapshot 변경은 pass를 막되 유효한 unverified다', () => {
    const preflightUnknown = result();
    preflightUnknown.preflight = preflight('unverified');
    assert.ok(keywords(validateBackendTestResult(preflightUnknown, schema, expected())).includes('overallVerdict'));

    const changed = result();
    changed.finishedSnapshot.trackedDiffHash = '0'.repeat(64);
    changed.overallVerdict = 'unverified';
    changed.overallReason = '실행 중 snapshot이 바뀌었다.';
    assert.deepEqual(validateBackendTestResult(changed, schema, expected()), []);

    const changedBase = result();
    changedBase.finishedSnapshot.baseCommit = '0'.repeat(40);
    changedBase.overallVerdict = 'unverified';
    changedBase.overallReason = '실행 중 base가 바뀌었다.';
    assert.deepEqual(validateBackendTestResult(changedBase, schema, expected()), []);
});

test('preflight 필수 Docker check 누락과 이름 중복·변조를 거부한다', () => {
    const missingDocker = result();
    missingDocker.preflight.checks = missingDocker.preflight.checks.filter(({ name }) => name !== 'docker');
    assert.notDeepEqual(validateBackendTestResult(missingDocker, schema, expected()), []);

    const duplicateName = result();
    duplicateName.preflight.checks[4].name = 'result-directory';
    const duplicateKeywords = keywords(validateBackendTestResult(duplicateName, schema, expected()));
    assert.ok(duplicateKeywords.includes('duplicatePreflightCheck'));
    assert.ok(duplicateKeywords.includes('missingPreflightCheck'));

    const unexpectedName = result();
    unexpectedName.preflight.checks[4].name = 'network';
    assert.ok(keywords(validateBackendTestResult(unexpectedName, schema, expected())).includes('enum'));
});

test('개별 preflight check가 unverified이면 top-level pass와 overall pass를 거부한다', () => {
    const forged = result();
    const docker = forged.preflight.checks.find(({ name }) => name === 'docker');
    docker.verdict = 'unverified';
    docker.detail = 'Docker daemon에 접근할 수 없다.';

    const forgedKeywords = keywords(validateBackendTestResult(forged, schema, expected()));
    assert.ok(forgedKeywords.includes('preflightVerdict'));
    assert.ok(forgedKeywords.includes('overallVerdict'));
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
    delete malformed.executionResults[0].command;
    fs.writeFileSync(resultPath, JSON.stringify(malformed), 'utf8');
    const invalid = spawnSync(process.execPath, [scriptPath, '--result', resultPath, '--expected', expectedPath], {
        encoding: 'utf8',
    });
    assert.equal(invalid.status, 1);
    assert.match(invalid.stderr, /required/);
});
