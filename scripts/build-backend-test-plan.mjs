#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

import { atomicWriteJson, parseGradleTestCommand } from './run-backend-test-contract.mjs';
import {
    canonicalJson,
    computeWorktreeSnapshot,
    sha256,
    validateExpected,
} from './validate-backend-test-result.mjs';

function fail(message) {
    throw new Error(message);
}

function readJson(filePath, label) {
    try {
        return JSON.parse(fs.readFileSync(path.resolve(filePath), 'utf8'));
    } catch (error) {
        fail(`${label} JSON을 읽을 수 없습니다 (${filePath}): ${error.message}`);
    }
}

function isInside(child, parent) {
    const relative = path.relative(path.resolve(parent), path.resolve(child));
    return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function gradleJUnitTasks(command) {
    const parsed = parseGradleTestCommand(command);
    return parsed === null
        ? null
        : [...new Set(parsed.tasks.map(({ taskIdentity }) => taskIdentity))];
}

function validatePlanInputs({ packet, plan, worktree }) {
    const errors = [];
    if (plan?.schemaVersion !== 1) errors.push('plan schemaVersion은 1이어야 합니다.');
    const requiredIds = (packet?.requiredTests ?? []).map(({ id }) => id);
    const planIds = (plan?.tests ?? []).map(({ id }) => id);
    if (requiredIds.join('\0') !== planIds.join('\0')) {
        errors.push('plan tests가 packet requiredTests의 T-ID와 순서대로 일치하지 않습니다.');
    }
    const commands = new Set((plan?.executions ?? []).map(({ command }) => command));
    for (const field of ['targetedTests', 'finalCommands']) {
        for (const command of packet?.validation?.[field] ?? []) {
            if (!commands.has(command)) errors.push(`${field}가 최종 실행 plan에서 빠졌습니다: ${command}`);
        }
    }
    const mappedExecutions = new Set((plan?.tests ?? []).flatMap(({ executionIds }) => executionIds ?? []));
    const finalCommands = new Set(packet?.validation?.finalCommands ?? []);
    for (const execution of plan?.executions ?? []) {
        if (!mappedExecutions.has(execution.id) && !finalCommands.has(execution.command)) {
            errors.push(`${execution.id}은 T-ID 증거나 finalCommands gate로 사용되지 않습니다.`);
        }
        const commandJUnitTasks = gradleJUnitTasks(execution.command);
        if (commandJUnitTasks === null && (execution.junitTasks?.length ?? 0) > 0) {
            errors.push(`${execution.id}은 Gradle JUnit task를 실행하지 않지만 junitTasks를 선언했습니다.`);
        }
        for (const task of commandJUnitTasks ?? []) {
            if (!execution.junitTasks?.includes(task)) {
                errors.push(`${execution.id}은 ${task}를 실행하지만 junitTasks에 선언하지 않았습니다.`);
            }
        }
        for (const task of execution.junitTasks ?? []) {
            if (!(commandJUnitTasks ?? []).includes(task)) {
                errors.push(`${execution.id}의 junitTasks ${task}가 실행 command에 없습니다.`);
            }
        }
    }
    for (const test of plan?.tests ?? []) {
        for (const source of test.testSources ?? []) {
            const absolute = path.resolve(worktree, source);
            if (!isInside(absolute, worktree) || !fs.existsSync(absolute) || !fs.statSync(absolute).isFile()) {
                errors.push(`${test.id} testSources 파일을 찾을 수 없습니다: ${source}`);
            }
        }
    }
    if (errors.length > 0) fail(`backend test plan 입력이 올바르지 않습니다: ${errors.join(' ')}`);
}

export function buildBackendTestPlan({ packet, plan, worktree }) {
    validatePlanInputs({ packet, plan, worktree });
    const snapshot = computeWorktreeSnapshot(worktree);
    const expected = {
        schemaVersion: 2,
        baseCommit: snapshot.baseCommit,
        implementationDiffHash: snapshot.implementationDiffHash,
        trackedDiffHash: snapshot.trackedDiffHash,
        packetHash: sha256(Buffer.from(canonicalJson(packet), 'utf8')),
        executions: plan.executions,
        tests: plan.tests,
    };
    validateExpected(expected);
    return expected;
}

function parseArgs(argv) {
    const options = {};
    const allowed = new Set(['--packet', '--plan', '--output', '--worktree']);
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        if (!allowed.has(argument)) fail(`알 수 없는 인자입니다: ${argument}`);
        const value = argv[++index];
        if (!value) fail(`${argument} 값이 필요합니다.`);
        options[argument.slice(2)] = value;
    }
    for (const required of ['packet', 'plan', 'output', 'worktree']) {
        if (!options[required]) fail(`--${required} 값이 필요합니다.`);
    }
    return options;
}

function runCli() {
    try {
        const options = parseArgs(process.argv.slice(2));
        const worktree = path.resolve(options.worktree);
        const output = path.resolve(options.output);
        if (isInside(output, worktree)) fail('expected plan은 snapshot을 바꾸지 않도록 worktree 밖에 저장해야 합니다.');
        const expected = buildBackendTestPlan({
            packet: readJson(options.packet, 'packet'),
            plan: readJson(options.plan, 'plan'),
            worktree,
        });
        atomicWriteJson(output, expected);
        console.log(`backend test plan 생성: ${expected.executions.length}개 실행, ${expected.tests.length}개 T-ID`);
    } catch (error) {
        console.error(`backend test plan 생성 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) runCli();
