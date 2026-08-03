#!/usr/bin/env node

import { spawnSync } from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { pathToFileURL } from 'node:url';

import {
    DEFAULT_SCHEMA_PATH,
    DEFAULT_TEST_TIMEOUT_MS,
    computeWorktreeSnapshot,
    sha256,
    validateBackendTestResult,
    validateExpected,
} from './validate-backend-test-result.mjs';

const MAX_BUFFER_BYTES = 64 * 1024 * 1024;

function fail(message) {
    throw new Error(message);
}

function readJson(filePath, label) {
    try {
        return JSON.parse(fs.readFileSync(filePath, 'utf8'));
    } catch (error) {
        fail(`${label} JSON을 읽을 수 없습니다 (${filePath}): ${error.message}`);
    }
}

export function atomicWriteJson(filePath, value) {
    const resolved = path.resolve(filePath);
    const directory = path.dirname(resolved);
    fs.mkdirSync(directory, { recursive: true });
    const temporary = path.join(
        directory,
        `.${path.basename(resolved)}.${process.pid}.${Date.now()}.tmp`,
    );
    fs.writeFileSync(temporary, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
    fs.renameSync(temporary, resolved);
}

export function commandHash(command) {
    return sha256(Buffer.from(command, 'utf8'));
}

function shellTokens(command) {
    return [...command.matchAll(/"([^"\\]*(?:\\.[^"\\]*)*)"|'([^'\\]*(?:\\.[^'\\]*)*)'|([^\s]+)/gu)]
        .map((match) => match[1] ?? match[2] ?? match[3]);
}

function normalizeGradleTestTask(token, index) {
    const segments = token.startsWith(':')
        ? token.slice(1).split(':')
        : [token];
    if (segments.length === 0 || segments.some((segment) => segment.length === 0)) return null;
    const taskName = segments.at(-1);
    if (!['test', 'postgresTest'].includes(taskName)) return null;
    const projectSegments = segments.slice(0, -1);
    return {
        index,
        taskName,
        taskIdentity: projectSegments.length === 0
            ? taskName
            : `:${[...projectSegments, taskName].join(':')}`,
    };
}

export function parseGradleTestCommand(command) {
    const tokens = shellTokens(command);
    if (tokens.length === 0 || !/(?:^|[\\/])gradlew(?:\.bat)?$/iu.test(tokens[0])) return null;
    return {
        tokens,
        tasks: tokens
            .map((token, index) => normalizeGradleTestTask(token, index))
            .filter((task) => task !== null),
    };
}

export function commandNeedsDocker(command) {
    const gradle = parseGradleTestCommand(command);
    return gradle?.tasks.some(({ taskName }) => taskName === 'postgresTest') === true ||
        /(?:^|\s)(?:postgresTest|docker|testcontainers?)(?:\s|$)/iu.test(command);
}

function isInside(child, parent) {
    const relative = path.relative(path.resolve(parent), path.resolve(child));
    return relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative));
}

function probeWritableDirectory(directory, label) {
    try {
        fs.mkdirSync(directory, { recursive: true });
        const probe = path.join(directory, `.backend-test-probe-${process.pid}-${Date.now()}`);
        const moved = `${probe}.moved`;
        fs.writeFileSync(probe, 'probe', 'utf8');
        fs.renameSync(probe, moved);
        fs.unlinkSync(moved);
        return { name: label, verdict: 'pass', detail: `${label} 쓰기와 원자 rename이 가능하다.` };
    } catch (error) {
        return { name: label, verdict: 'unverified', detail: `${label}을 준비할 수 없다: ${error.message}` };
    }
}

function gradleWrapperCheck(expected, worktree) {
    const usesWrapper = expected.executions.some(({ command }) => /(?:^|\s)[.\\/]*gradlew(?:\.bat)?(?:\s|$)/iu.test(command));
    if (!usesWrapper) {
        return { name: 'gradle-wrapper', verdict: 'pass', detail: '승인 명령이 Gradle Wrapper를 사용하지 않는다.' };
    }
    const candidates = process.platform === 'win32' ? ['gradlew.bat', 'gradlew'] : ['gradlew', 'gradlew.bat'];
    const found = candidates.find((candidate) => fs.existsSync(path.join(worktree, candidate)));
    return found
        ? { name: 'gradle-wrapper', verdict: 'pass', detail: `${found} 파일을 확인했다.` }
        : { name: 'gradle-wrapper', verdict: 'unverified', detail: 'worktree에서 Gradle Wrapper를 찾을 수 없다.' };
}

function dockerCheck(expected, worktree, spawn = spawnSync) {
    const requiresDocker = expected.executions.some(({ command }) => commandNeedsDocker(command));
    if (!requiresDocker) {
        return { name: 'docker', verdict: 'pass', detail: 'Docker가 필요한 승인 명령이 없다.' };
    }
    const outcome = spawn('docker', ['version', '--format', '{{.Server.Version}}'], {
        cwd: worktree,
        encoding: 'utf8',
        timeout: 15_000,
        windowsHide: true,
    });
    if (outcome.status === 0 && /\S/u.test(outcome.stdout ?? '')) {
        return { name: 'docker', verdict: 'pass', detail: `Docker daemon ${outcome.stdout.trim()}에 접근할 수 있다.` };
    }
    const reason = outcome.error?.message ?? outcome.stderr?.trim() ?? `exit code ${outcome.status}`;
    return {
        name: 'docker',
        verdict: 'unverified',
        detail: `Docker daemon에 접근할 수 없다: ${reason}`,
    };
}

export function runPreflight({ expected, worktree, resultPath, spawn = spawnSync }) {
    const resolvedResult = path.resolve(resultPath);
    if (isInside(resolvedResult, worktree)) {
        fail('결과와 로그는 snapshot을 바꾸지 않도록 worktree 밖의 경로에 저장해야 합니다.');
    }
    const resultDirectory = path.dirname(resolvedResult);
    const logDirectory = path.join(resultDirectory, `${path.basename(resolvedResult, path.extname(resolvedResult))}.logs`);
    const jnaDirectory = path.join(resultDirectory, `${path.basename(resolvedResult, path.extname(resolvedResult))}.jna`);
    const checks = [
        probeWritableDirectory(resultDirectory, 'result-directory'),
        probeWritableDirectory(logDirectory, 'log-directory'),
        probeWritableDirectory(jnaDirectory, 'jna-directory'),
        gradleWrapperCheck(expected, worktree),
        dockerCheck(expected, worktree, spawn),
    ];
    return {
        preflight: {
            verdict: checks.some(({ verdict }) => verdict === 'unverified') ? 'unverified' : 'pass',
            checks,
        },
        logDirectory,
        jnaDirectory,
    };
}

function executionSnapshot(snapshot, packetHash) {
    return {
        baseCommit: snapshot.baseCommit,
        implementationDiffHash: snapshot.implementationDiffHash,
        packetHash,
        trackedDiffHash: snapshot.trackedDiffHash,
    };
}

function approvedSnapshot(expected) {
    return {
        baseCommit: expected.baseCommit,
        implementationDiffHash: expected.implementationDiffHash,
        packetHash: expected.packetHash,
    };
}

function assertExpectedSnapshot(expected, actual) {
    const mismatches = [
        ['baseCommit', actual.baseCommit],
        ['implementationDiffHash', actual.implementationDiffHash],
        ['trackedDiffHash', actual.trackedDiffHash],
    ].filter(([field, value]) => expected[field] !== value);
    if (mismatches.length > 0) {
        fail(`현재 worktree snapshot이 expected와 다릅니다: ${mismatches.map(([field]) => field).join(', ')}`);
    }
}

function snapshotChanged(started, current) {
    return started.baseCommit !== current.baseCommit ||
        started.implementationDiffHash !== current.implementationDiffHash ||
        started.trackedDiffHash !== current.trackedDiffHash;
}

function pendingExecution(execution, reason) {
    return {
        executionId: execution.id,
        command: execution.command,
        commandHash: commandHash(execution.command),
        durationMs: 0,
        exitCode: null,
        verdict: 'unverified',
        evidenceHash: null,
        junitEvidence: [],
        notRunReason: reason,
    };
}

function calculateTestResults(expected, executionResults) {
    const byId = new Map(executionResults.map((execution) => [execution.executionId, execution]));
    return expected.tests.map((test) => {
        const executions = test.executionIds.map((executionId) => byId.get(executionId));
        const verdict = executions.some((execution) => execution?.verdict === 'fail')
            ? 'fail'
            : executions.some((execution) => execution?.verdict !== 'pass')
                ? 'unverified'
                : 'pass';
        const reasons = executions
            .filter((execution) => execution?.verdict === 'unverified')
            .map((execution) => `${execution?.executionId}: ${execution?.notRunReason ?? '실행 결과 없음'}`);
        return {
            testId: test.id,
            executionIds: test.executionIds,
            verdict,
            notRunReason: verdict === 'unverified' ? [...new Set(reasons)].join(' ') : null,
        };
    });
}

function aggregateResult(result, expected, started, finished) {
    result.finishedSnapshot = executionSnapshot(finished, result.snapshot.packetHash);
    result.testResults = calculateTestResults(expected, result.executionResults);
    const verdicts = result.executionResults.map(({ verdict }) => verdict);
    const changed = snapshotChanged(started, finished);
    result.overallVerdict = verdicts.includes('fail')
        ? 'fail'
        : result.preflight.verdict === 'unverified' || changed || verdicts.includes('unverified')
            ? 'unverified'
            : 'pass';
    if (result.overallVerdict === 'unverified') {
        const reasons = result.executionResults
            .filter(({ verdict }) => verdict === 'unverified')
            .map(({ executionId, notRunReason }) => `${executionId}: ${notRunReason}`);
        if (result.preflight.verdict === 'unverified') reasons.unshift('preflight가 완료되지 않았다.');
        if (changed) reasons.unshift('실행 중 구현 snapshot이 바뀌었다.');
        result.overallReason = [...new Set(reasons)].join(' ');
    } else {
        result.overallReason = null;
    }
    return result;
}

function shellInvocation(command) {
    return process.platform === 'win32'
        ? { executable: 'powershell.exe', args: ['-NoProfile', '-NonInteractive', '-Command', command] }
        : { executable: '/bin/sh', args: ['-lc', command] };
}

function junitReportFiles(worktree, task) {
    const segments = task.split(':').filter(Boolean);
    const taskName = segments.pop() ?? task;
    const projectSegments = task.startsWith(':') ? segments : [];
    const directory = path.join(worktree, ...projectSegments, 'build', 'test-results', taskName);
    if (!fs.existsSync(directory)) return [];
    return fs.readdirSync(directory, { withFileTypes: true })
        .filter((entry) => entry.isFile() && entry.name.endsWith('.xml'))
        .map((entry) => path.join(directory, entry.name))
        .sort();
}

function captureJUnitReports(worktree, tasks) {
    const captured = new Map();
    for (const task of tasks) {
        const reports = new Map();
        for (const filePath of junitReportFiles(worktree, task)) {
            const stats = fs.statSync(filePath);
            reports.set(filePath, {
                mtimeMs: stats.mtimeMs,
                size: stats.size,
                hash: sha256(fs.readFileSync(filePath)),
            });
        }
        captured.set(task, reports);
    }
    return captured;
}

function xmlTestCount(bytes) {
    const xml = bytes.toString('utf8');
    return [...xml.matchAll(/<testsuite\b[^>]*\btests="([0-9]+)"/gu)]
        .reduce((sum, match) => sum + Number(match[1]), 0);
}

function collectJUnitEvidence(worktree, tasks, before) {
    const evidence = [];
    const issues = [];
    for (const task of tasks) {
        const changed = [];
        for (const filePath of junitReportFiles(worktree, task)) {
            const bytes = fs.readFileSync(filePath);
            const stats = fs.statSync(filePath);
            const previous = before.get(task)?.get(filePath);
            const hash = sha256(bytes);
            if (!previous || previous.mtimeMs !== stats.mtimeMs || previous.size !== stats.size || previous.hash !== hash) {
                changed.push({ filePath, bytes, hash });
            }
        }
        if (changed.length === 0) {
            issues.push(`${task} JUnit XML이 새로 생성되거나 갱신되지 않았다.`);
            continue;
        }
        const testCount = changed.reduce((sum, report) => sum + xmlTestCount(report.bytes), 0);
        if (testCount === 0) {
            issues.push(`${task} JUnit XML의 실행 테스트 수가 0이다.`);
            continue;
        }
        const reportSeed = changed
            .map(({ filePath, hash }) => `${path.relative(worktree, filePath).replaceAll('\\', '/')}\0${hash}`)
            .join('\n');
        evidence.push({
            task,
            reportCount: changed.length,
            testCount,
            reportHash: sha256(Buffer.from(reportSeed, 'utf8')),
        });
    }
    return { evidence, issues };
}

function skippedJUnitTasks(tasks, output) {
    const text = output.toString('utf8');
    return tasks.filter((task) => {
        const escaped = task.replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
        const outputTask = task.startsWith(':') ? escaped : `:?${escaped}`;
        return new RegExp(`> Task ${outputTask} (?:UP-TO-DATE|FROM-CACHE|NO-SOURCE)`, 'u').test(text);
    });
}

function executeExecution(execution, { worktree, logDirectory, jnaDirectory, spawn = spawnSync }) {
    const invocation = shellInvocation(execution.command);
    const beforeReports = captureJUnitReports(worktree, execution.junitTasks);
    const startedAt = Date.now();
    const javaToolOptions = [
        process.env.JAVA_TOOL_OPTIONS,
        `-Djna.tmpdir="${jnaDirectory}"`,
    ].filter(Boolean).join(' ');
    const outcome = spawn(invocation.executable, invocation.args, {
        cwd: worktree,
        encoding: null,
        env: { ...process.env, JAVA_TOOL_OPTIONS: javaToolOptions },
        maxBuffer: MAX_BUFFER_BYTES,
        timeout: execution.timeoutMs ?? DEFAULT_TEST_TIMEOUT_MS,
        windowsHide: true,
    });
    const stdout = Buffer.isBuffer(outcome.stdout) ? outcome.stdout : Buffer.from(outcome.stdout ?? '', 'utf8');
    const stderr = Buffer.isBuffer(outcome.stderr) ? outcome.stderr : Buffer.from(outcome.stderr ?? '', 'utf8');
    const evidence = Buffer.concat([stdout, stderr]);
    const logPath = path.join(logDirectory, `${execution.id}.log`);
    fs.writeFileSync(logPath, evidence);

    if (outcome.error) {
        const timeout = outcome.error.code === 'ETIMEDOUT';
        return {
            durationMs: Date.now() - startedAt,
            exitCode: null,
            verdict: 'unverified',
            evidenceHash: evidence.length > 0 ? sha256(evidence) : null,
            junitEvidence: [],
            notRunReason: timeout
                ? `승인 명령이 ${execution.timeoutMs ?? DEFAULT_TEST_TIMEOUT_MS}ms 제한을 초과했다.`
                : `승인 명령을 실행할 수 없다: ${outcome.error.message}`,
        };
    }
    if (!Number.isInteger(outcome.status)) {
        return {
            durationMs: Date.now() - startedAt,
            exitCode: null,
            verdict: 'unverified',
            evidenceHash: evidence.length > 0 ? sha256(evidence) : null,
            junitEvidence: [],
            notRunReason: `승인 명령이 signal ${outcome.signal ?? 'unknown'}로 종료되었다.`,
        };
    }
    if (outcome.status === 0 && execution.junitTasks.length > 0) {
        const junit = collectJUnitEvidence(worktree, execution.junitTasks, beforeReports);
        const skipped = skippedJUnitTasks(execution.junitTasks, evidence);
        const issues = [
            ...junit.issues,
            ...skipped.map((task) => `${task} Gradle task가 실제 실행되지 않았다.`),
        ];
        if (issues.length > 0) {
            return {
                durationMs: Date.now() - startedAt,
                exitCode: 0,
                verdict: 'unverified',
                evidenceHash: sha256(evidence),
                junitEvidence: junit.evidence,
                notRunReason: [...new Set(issues)].join(' '),
            };
        }
        return {
            durationMs: Date.now() - startedAt,
            exitCode: 0,
            verdict: 'pass',
            evidenceHash: sha256(evidence),
            junitEvidence: junit.evidence,
            notRunReason: null,
        };
    }
    const junit = collectJUnitEvidence(worktree, execution.junitTasks, beforeReports);
    return {
        durationMs: Date.now() - startedAt,
        exitCode: outcome.status,
        verdict: outcome.status === 0 ? 'pass' : 'fail',
        evidenceHash: sha256(evidence),
        junitEvidence: junit.evidence,
        notRunReason: null,
    };
}

export function runBackendTestContract({
    expectedPath,
    resultPath,
    worktree = process.cwd(),
    spawn = spawnSync,
}) {
    const resolvedWorktree = path.resolve(worktree);
    const expected = readJson(path.resolve(expectedPath), 'expected');
    validateExpected(expected);
    const schema = readJson(DEFAULT_SCHEMA_PATH, '결과 스키마');
    const started = computeWorktreeSnapshot(resolvedWorktree);
    assertExpectedSnapshot(expected, started);
    const { preflight, logDirectory, jnaDirectory } = runPreflight({
        expected,
        worktree: resolvedWorktree,
        resultPath,
        spawn,
    });
    const result = {
        schemaVersion: 2,
        snapshot: approvedSnapshot(expected),
        startedSnapshot: executionSnapshot(started, expected.packetHash),
        finishedSnapshot: executionSnapshot(started, expected.packetHash),
        preflight,
        executionResults: expected.executions.map((execution) =>
            pendingExecution(execution, '아직 실행되지 않았다.')),
        testResults: [],
        overallVerdict: 'unverified',
        overallReason: '승인 명령 실행 전이다.',
    };
    aggregateResult(result, expected, started, started);
    atomicWriteJson(resultPath, result);

    if (preflight.verdict === 'pass') {
        for (const approvedExecution of expected.executions) {
            const beforeExecution = computeWorktreeSnapshot(resolvedWorktree);
            if (snapshotChanged(started, beforeExecution)) {
                result.executionResults = result.executionResults.map((item) =>
                    item.notRunReason === '아직 실행되지 않았다.'
                        ? { ...item, notRunReason: '실행 직전 snapshot이 바뀌어 실행하지 않았다.' }
                        : item);
                aggregateResult(result, expected, started, beforeExecution);
                atomicWriteJson(resultPath, result);
                break;
            }
            const execution = executeExecution(approvedExecution, {
                worktree: resolvedWorktree,
                logDirectory,
                jnaDirectory,
                spawn,
            });
            result.executionResults = result.executionResults.map((item) => item.executionId === approvedExecution.id
                ? {
                    executionId: approvedExecution.id,
                    command: approvedExecution.command,
                    commandHash: commandHash(approvedExecution.command),
                    ...execution,
                }
                : item);
            const current = computeWorktreeSnapshot(resolvedWorktree);
            aggregateResult(result, expected, started, current);
            atomicWriteJson(resultPath, result);
            if (snapshotChanged(started, current)) break;
        }
    } else {
        const reason = preflight.checks
            .filter(({ verdict }) => verdict === 'unverified')
            .map(({ detail }) => detail)
            .join(' ');
        result.executionResults = result.executionResults.map((item) => ({ ...item, notRunReason: reason }));
    }

    const finished = computeWorktreeSnapshot(resolvedWorktree);
    aggregateResult(result, expected, started, finished);
    atomicWriteJson(resultPath, result);
    const validationErrors = validateBackendTestResult(result, schema, expected);
    if (validationErrors.length > 0) {
        fail(`runner 결과가 계약을 통과하지 못했습니다: ${validationErrors.map(({ instancePath, message }) => `${instancePath} ${message}`).join(' ')}`);
    }
    return result;
}

function parseArgs(argv) {
    const options = {};
    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        if (!['--expected', '--result', '--worktree'].includes(argument)) {
            fail(`알 수 없는 인자입니다: ${argument}`);
        }
        const value = argv[++index];
        if (!value) fail(`${argument} 값이 필요합니다.`);
        options[argument.slice(2)] = value;
    }
    if (!options.expected || !options.result) {
        fail('--expected와 --result가 필요합니다.');
    }
    return options;
}

function runCli() {
    try {
        const options = parseArgs(process.argv.slice(2));
        const result = runBackendTestContract({
            expectedPath: options.expected,
            resultPath: options.result,
            worktree: options.worktree,
        });
        console.log(`backend test contract: ${result.overallVerdict} (${result.testResults.length}개 T-ID, ${result.executionResults.length}개 고유 실행)`);
        process.exitCode = result.overallVerdict === 'pass' ? 0 : result.overallVerdict === 'fail' ? 1 : 2;
    } catch (error) {
        console.error(`backend test contract 실행 실패: ${error.message}`);
        process.exitCode = 2;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) runCli();
