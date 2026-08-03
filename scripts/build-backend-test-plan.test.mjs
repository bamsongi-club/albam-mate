import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { test } from 'node:test';

import { buildBackendTestPlan } from './build-backend-test-plan.mjs';

function git(worktree, args) {
    return execFileSync('git', args, { cwd: worktree, encoding: 'utf8' }).trim();
}

function fixture(t) {
    const worktree = fs.mkdtempSync(path.join(os.tmpdir(), 'backend-test-plan-'));
    t.after(() => fs.rmSync(worktree, { recursive: true, force: true }));
    git(worktree, ['init', '-q']);
    git(worktree, ['config', 'core.autocrlf', 'false']);
    git(worktree, ['config', 'user.email', 'planner@example.com']);
    git(worktree, ['config', 'user.name', 'Backend Planner']);
    const source = 'src/test/java/example/ExampleTest.java';
    fs.mkdirSync(path.dirname(path.join(worktree, source)), { recursive: true });
    fs.writeFileSync(path.join(worktree, source), 'class ExampleTest {}\n', 'utf8');
    git(worktree, ['add', '.']);
    git(worktree, ['commit', '-qm', 'baseline']);
    const packet = {
        requiredTests: [{ id: 'T1' }, { id: 'T2' }],
        validation: {
            targetedTests: ['.\\gradlew.bat test --tests "example.ExampleTest"'],
            finalCommands: ['.\\gradlew.bat conventionCheck'],
        },
    };
    const plan = {
        schemaVersion: 1,
        executions: [
            {
                id: 'E1',
                command: '.\\gradlew.bat test --tests "example.ExampleTest"',
                junitTasks: ['test'],
            },
            { id: 'E2', command: '.\\gradlew.bat conventionCheck', junitTasks: [] },
        ],
        tests: [
            { id: 'T1', executionIds: ['E1'], testSources: [source] },
            { id: 'T2', executionIds: ['E1'], testSources: [source] },
        ],
    };
    return {
        worktree,
        source,
        packet,
        plan,
    };
}

test('packet, 최종 snapshot과 concrete mapping에서 expected 실행 그래프를 만든다', (t) => {
    const input = fixture(t);
    const expected = buildBackendTestPlan(input);
    assert.equal(expected.schemaVersion, 2);
    assert.deepEqual(expected.tests[1].executionIds, ['E1']);
});

test('targetedTests를 먼저, finalCommands를 마지막으로 재번호화한다', (t) => {
    const input = fixture(t);
    const targeted = input.plan.executions[0];
    const finalCommand = input.plan.executions[1];
    input.plan.executions = [
        { ...finalCommand, id: 'E1' },
        { ...targeted, id: 'E2' },
    ];
    input.plan.tests = input.plan.tests.map((mapping) => ({ ...mapping, executionIds: ['E2'] }));

    const expected = buildBackendTestPlan(input);

    assert.deepEqual(expected.executions.map(({ command }) => command), [
        input.packet.validation.targetedTests[0],
        input.packet.validation.finalCommands[0],
    ]);
    assert.deepEqual(expected.executions.map(({ id }) => id), ['E1', 'E2']);
    assert.deepEqual(expected.tests.map(({ executionIds }) => executionIds), [['E1'], ['E1']]);
});

test('존재하지 않는 testSources와 빠진 targetedTests·finalCommands를 거부한다', (t) => {
    const missingSource = fixture(t);
    missingSource.plan.tests[0].testSources = ['src/test/java/example/MissingTest.java'];
    assert.throws(() => buildBackendTestPlan(missingSource), /testSources/);

    const missingFinal = fixture(t);
    missingFinal.plan.executions.pop();
    assert.throws(() => buildBackendTestPlan(missingFinal), /finalCommands/);

    const missingTargeted = fixture(t);
    missingTargeted.plan.executions.shift();
    assert.throws(() => buildBackendTestPlan(missingTargeted), /targetedTests/);
});

test('targetedTests와 finalCommands의 같은 명령 및 잘못된 plan execution ID를 거부한다', (t) => {
    const overlap = fixture(t);
    overlap.packet.validation.finalCommands = [overlap.packet.validation.targetedTests[0]];
    assert.throws(() => buildBackendTestPlan(overlap), /같은 명령/);

    const wrongId = fixture(t);
    wrongId.plan.executions[0].id = 'E2';
    assert.throws(() => buildBackendTestPlan(wrongId), /executions\[0\]\.id/);
});

test('하나의 Gradle task graph에 든 모든 JUnit task를 선언해야 한다', (t) => {
    const input = fixture(t);
    const combinedCommand = '.\\gradlew.bat test postgresTest --no-daemon';
    input.plan.executions[0].command = combinedCommand;
    input.packet.validation.targetedTests = [combinedCommand];
    assert.throws(() => buildBackendTestPlan(input), /postgresTest.*junitTasks/);

    input.plan.executions[0].junitTasks.push('postgresTest');
    const expected = buildBackendTestPlan(input);
    assert.deepEqual(expected.executions[0].junitTasks, ['test', 'postgresTest']);
});

test('Gradle 절대·multi-project task path를 JUnit task identity로 선언해야 한다', (t) => {
    const input = fixture(t);
    const command = '.\\gradlew.bat :test :postgresTest :module:test :module:postgresTest --no-daemon';
    input.plan.executions[0].command = command;
    input.packet.validation.targetedTests = [command];
    input.plan.executions[0].junitTasks = ['test', 'postgresTest', ':module:test', ':module:postgresTest'];

    const expected = buildBackendTestPlan(input);
    assert.deepEqual(expected.executions[0].junitTasks, [
        'test',
        'postgresTest',
        ':module:test',
        ':module:postgresTest',
    ]);

    input.plan.executions[0].junitTasks = ['test', 'postgresTest'];
    assert.throws(() => buildBackendTestPlan(input), /:module:test.*junitTasks/);
});
