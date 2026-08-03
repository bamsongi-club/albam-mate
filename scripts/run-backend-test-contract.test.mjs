import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';

import {
    commandHash,
    commandNeedsDocker,
    parseGradleTestCommand,
    runBackendTestContract,
    runPreflight,
} from './run-backend-test-contract.mjs';
import { computeWorktreeSnapshot } from './validate-backend-test-result.mjs';

function git(worktree, args) {
    return execFileSync('git', args, { cwd: worktree, encoding: 'utf8' }).trim();
}

function fixture(t) {
    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'backend-test-runner-'));
    const worktree = path.join(root, 'repo');
    fs.mkdirSync(worktree);
    t.after(() => fs.rmSync(root, { recursive: true, force: true }));
    git(worktree, ['init', '-q']);
    git(worktree, ['config', 'core.autocrlf', 'false']);
    git(worktree, ['config', 'user.email', 'tester@example.com']);
    git(worktree, ['config', 'user.name', 'Backend Runner']);
    const testSource = path.join(worktree, 'src/test/java/example/ExampleTest.java');
    fs.mkdirSync(path.dirname(testSource), { recursive: true });
    fs.writeFileSync(path.join(worktree, 'baseline.txt'), 'baseline\n', 'utf8');
    fs.writeFileSync(path.join(worktree, '.gitignore'), 'build/\n', 'utf8');
    fs.writeFileSync(testSource, 'class ExampleTest {}\n', 'utf8');
    git(worktree, ['add', '.']);
    git(worktree, ['commit', '-qm', 'baseline']);
    return { root, worktree, testSource: 'src/test/java/example/ExampleTest.java' };
}

function expectedFor(worktree, executions, tests) {
    const snapshot = computeWorktreeSnapshot(worktree);
    return {
        schemaVersion: 2,
        baseCommit: snapshot.baseCommit,
        implementationDiffHash: snapshot.implementationDiffHash,
        trackedDiffHash: snapshot.trackedDiffHash,
        packetHash: 'a'.repeat(64),
        executions,
        tests,
    };
}

function writeExpected(root, expected, name = 'expected.json') {
    const expectedPath = path.join(root, name);
    fs.writeFileSync(expectedPath, JSON.stringify(expected), 'utf8');
    return expectedPath;
}

function execution(id, command, junitTasks = []) {
    return { id, command, timeoutMs: 10_000, junitTasks };
}

function mapping(id, executionIds, testSource) {
    return { id, executionIds, testSources: [testSource] };
}

function reportWriter(root, options = {}) {
    const scriptPath = path.join(root, `report-${Math.random().toString(16).slice(2)}.mjs`);
    const output = options.output ?? 'executed';
    const taskSegments = (options.task ?? 'test').split(':').filter(Boolean);
    const taskName = taskSegments.pop();
    const projectSegments = options.task?.startsWith(':') ? taskSegments : [];
    const reportDirectory = [...projectSegments, 'build', 'test-results', taskName];
    fs.writeFileSync(
        scriptPath,
        [
            "import fs from 'node:fs';",
            "import path from 'node:path';",
            `const dir = path.join(process.cwd(), ...${JSON.stringify(reportDirectory)});`,
            'fs.mkdirSync(dir, { recursive: true });',
            `fs.writeFileSync(path.join(dir, 'TEST-example.xml'), '<testsuite tests="${options.tests ?? 1}" failures="0"></testsuite>');`,
            `console.log(${JSON.stringify(output)});`,
        ].join('\n'),
        'utf8',
    );
    return `node ${JSON.stringify(scriptPath.replaceAll('\\', '/'))}`;
}

test('명령 hash는 승인 command bytes에서 결정적으로 계산한다', () => {
    assert.equal(commandHash('node --version').length, 64);
    assert.equal(commandHash('node --version'), commandHash('node --version'));
});

test('PostgreSQL과 Docker 명령만 Docker preflight 대상으로 분류한다', () => {
    assert.equal(commandNeedsDocker('.\\gradlew.bat postgresTest --tests "example.Test"'), true);
    assert.equal(commandNeedsDocker('./gradlew :postgresTest'), true);
    assert.equal(commandNeedsDocker('./gradlew :module:postgresTest'), true);
    assert.equal(commandNeedsDocker('./gradlew test --tests "example.Test"'), false);
    assert.equal(commandNeedsDocker('./gradlew :test'), false);
    assert.equal(commandNeedsDocker('./gradlew :module:test'), false);
    assert.equal(commandNeedsDocker('docker version'), true);
    assert.equal(commandNeedsDocker('testcontainers run'), true);
});

test('Gradle JUnit task path를 root와 module identity로 정규화한다', () => {
    const parsed = parseGradleTestCommand(
        './gradlew test postgresTest :test :postgresTest :module:test :module:postgresTest',
    );
    assert.deepEqual(parsed.tasks, [
        { index: 1, taskName: 'test', taskIdentity: 'test' },
        { index: 2, taskName: 'postgresTest', taskIdentity: 'postgresTest' },
        { index: 3, taskName: 'test', taskIdentity: 'test' },
        { index: 4, taskName: 'postgresTest', taskIdentity: 'postgresTest' },
        { index: 5, taskName: 'test', taskIdentity: ':module:test' },
        { index: 6, taskName: 'postgresTest', taskIdentity: ':module:postgresTest' },
    ]);
});

test('한 execution을 여러 T-ID가 공유해도 실제 명령은 한 번만 실행한다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const command = 'node -e "console.log(\'shared-command\')"';
    const expected = expectedFor(
        worktree,
        [execution('E1', command)],
        [mapping('T1', ['E1'], testSource), mapping('T2', ['E1'], testSource)],
    );
    const resultPath = path.join(root, 'result.json');
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath,
        worktree,
    });

    assert.equal(result.overallVerdict, 'pass');
    assert.equal(result.executionResults.length, 1);
    assert.deepEqual(result.testResults.map(({ verdict }) => verdict), ['pass', 'pass']);
    assert.equal(fs.readdirSync(path.join(root, 'result.logs')).length, 1);
});

test('T-ID 하나는 H2와 PostgreSQL 같은 여러 execution 결과를 함께 집계한다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const expected = expectedFor(
        worktree,
        [execution('E1', 'node --version'), execution('E2', 'node -p "1 + 1"')],
        [mapping('T1', ['E1', 'E2'], testSource)],
    );
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'result.json'),
        worktree,
    });
    assert.equal(result.testResults[0].verdict, 'pass');
    assert.deepEqual(result.testResults[0].executionIds, ['E1', 'E2']);
});

test('execution 하나가 fail이면 남은 승인 명령을 실행하지 않는다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const skippedMarker = path.join(root, 'skipped.txt');
    const expected = expectedFor(
        worktree,
        [
            execution('E1', 'node -e "console.log(\'pass\')"'),
            execution('E2', 'node -e "process.exit(7)"'),
            execution('E3', `node -e "require('fs').writeFileSync(${JSON.stringify(skippedMarker)}, 'ran')"`),
        ],
        [
            mapping('T1', ['E1'], testSource),
            mapping('T2', ['E2'], testSource),
            mapping('T3', ['E3'], testSource),
        ],
    );
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'result.json'),
        worktree,
    });

    assert.equal(result.overallVerdict, 'fail');
    assert.deepEqual(result.executionResults.map(({ verdict }) => verdict), ['pass', 'fail', 'unverified']);
    assert.match(result.executionResults[2].notRunReason, /E2.*fail-fast/);
    assert.deepEqual(result.testResults.map(({ verdict }) => verdict), ['pass', 'fail', 'unverified']);
    assert.equal(fs.existsSync(skippedMarker), false);
});

test('exit 0이어도 JUnit XML이 갱신되지 않으면 unverified다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const expected = expectedFor(
        worktree,
        [execution('E1', 'node --version', ['test'])],
        [mapping('T1', ['E1'], testSource)],
    );
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'result.json'),
        worktree,
    });
    assert.equal(result.overallVerdict, 'unverified');
    assert.match(result.executionResults[0].notRunReason, /JUnit XML/);
});

test('갱신된 JUnit XML의 tests가 1 이상이면 실제 실행 증거로 pass한다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const command = reportWriter(root);
    const expected = expectedFor(
        worktree,
        [execution('E1', command, ['test'])],
        [mapping('T1', ['E1'], testSource)],
    );
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'result.json'),
        worktree,
    });
    assert.equal(result.overallVerdict, 'pass');
    assert.equal(result.executionResults[0].junitEvidence[0].testCount, 1);
});

test('multi-project JUnit task는 해당 project의 build 보고서를 증거로 사용한다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const command = reportWriter(root, { task: ':module:test' });
    const expected = expectedFor(
        worktree,
        [execution('E1', command, [':module:test'])],
        [mapping('T1', ['E1'], testSource)],
    );
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'result.json'),
        worktree,
    });
    assert.equal(result.overallVerdict, 'pass');
    assert.equal(result.executionResults[0].junitEvidence[0].task, ':module:test');
    assert.equal(result.executionResults[0].junitEvidence[0].testCount, 1);
});

test('갱신된 JUnit XML이어도 실행 테스트 수가 0이면 unverified다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const command = reportWriter(root, { tests: 0 });
    const expected = expectedFor(
        worktree,
        [execution('E1', command, ['test'])],
        [mapping('T1', ['E1'], testSource)],
    );
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'result.json'),
        worktree,
    });
    assert.equal(result.executionResults[0].verdict, 'unverified');
    assert.match(result.executionResults[0].notRunReason, /테스트 수가 0/);
});

test('JUnit XML이 갱신돼도 Gradle task가 UP-TO-DATE면 pass하지 않는다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const command = reportWriter(root, { output: '> Task :test UP-TO-DATE' });
    const expected = expectedFor(
        worktree,
        [execution('E1', command, ['test'])],
        [mapping('T1', ['E1'], testSource)],
    );
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'result.json'),
        worktree,
    });
    assert.equal(result.overallVerdict, 'unverified');
    assert.match(result.executionResults[0].notRunReason, /실제 실행되지 않았다/);
});

test('Docker preflight 미검증은 execution을 시작하지 않는다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const expected = expectedFor(
        worktree,
        [execution('E1', 'node --version # docker')],
        [mapping('T1', ['E1'], testSource)],
    );
    const unavailable = (executable) => executable === 'docker'
        ? { status: 1, stdout: '', stderr: 'daemon unavailable' }
        : assert.fail('preflight 실패 뒤 승인 명령을 실행하면 안 됩니다.');
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'result.json'),
        worktree,
        spawn: unavailable,
    });
    assert.equal(result.preflight.verdict, 'unverified');
    assert.equal(result.executionResults[0].verdict, 'unverified');
});

test('expected 고정 뒤 snapshot이 바뀌면 승인 명령 실행 전에 중단한다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const expected = expectedFor(
        worktree,
        [execution('E1', 'node --version')],
        [mapping('T1', ['E1'], testSource)],
    );
    const expectedPath = writeExpected(root, expected);
    fs.appendFileSync(path.join(worktree, 'baseline.txt'), 'changed\n', 'utf8');
    let spawnCalls = 0;
    assert.throws(
        () => runBackendTestContract({
            expectedPath,
            resultPath: path.join(root, 'mismatch.json'),
            worktree,
            spawn: () => {
                spawnCalls += 1;
                return { status: 0, stdout: Buffer.alloc(0), stderr: Buffer.alloc(0) };
            },
        }),
        /snapshot이 expected와 다릅니다/,
    );
    assert.equal(spawnCalls, 0);
});

test('preflight 중 snapshot이 바뀌면 첫 승인 명령을 실행하지 않고 unverified를 반환한다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const expected = expectedFor(
        worktree,
        [execution('E1', 'node --version # docker')],
        [mapping('T1', ['E1'], testSource)],
    );
    let calls = 0;
    const mutateDuringPreflight = (executable) => {
        calls += 1;
        assert.equal(executable, 'docker');
        fs.appendFileSync(path.join(worktree, 'baseline.txt'), 'changed\n', 'utf8');
        return { status: 0, stdout: '27.0.0', stderr: '' };
    };
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'preflight-drift.json'),
        worktree,
        spawn: mutateDuringPreflight,
    });

    assert.equal(calls, 1);
    assert.equal(result.overallVerdict, 'unverified');
    assert.match(result.executionResults[0].notRunReason, /실행 직전 snapshot/);
});

test('signal로 종료된 명령은 구체적 사유가 있는 유효한 unverified다', (t) => {
    const { root, worktree, testSource } = fixture(t);
    const expected = expectedFor(
        worktree,
        [execution('E1', 'node --version')],
        [mapping('T1', ['E1'], testSource)],
    );
    const result = runBackendTestContract({
        expectedPath: writeExpected(root, expected),
        resultPath: path.join(root, 'signal.json'),
        worktree,
        spawn: () => ({
            status: null,
            signal: 'SIGTERM',
            stdout: Buffer.alloc(0),
            stderr: Buffer.from('terminated'),
        }),
    });

    assert.equal(result.overallVerdict, 'unverified');
    assert.equal(result.executionResults[0].exitCode, null);
    assert.match(result.executionResults[0].notRunReason, /SIGTERM/);
});

test('result 경로가 worktree 안이면 snapshot 오염 전에 중단한다', (t) => {
    const { worktree, testSource } = fixture(t);
    const expected = expectedFor(
        worktree,
        [execution('E1', 'node --version')],
        [mapping('T1', ['E1'], testSource)],
    );
    assert.throws(
        () => runPreflight({ expected, worktree, resultPath: path.join(worktree, 'result.json') }),
        /worktree 밖/,
    );
});
