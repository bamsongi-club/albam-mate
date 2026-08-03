import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { fileURLToPath, pathToFileURL } from 'node:url';

export const DEFAULT_SCHEMA_PATH = fileURLToPath(
    new URL('../.codex/contracts/backend-test-result.schema.json', import.meta.url),
);
export const DEFAULT_TEST_TIMEOUT_MS = 15 * 60 * 1000;
const REQUIRED_PREFLIGHT_CHECK_NAMES = [
    'result-directory',
    'log-directory',
    'jna-directory',
    'gradle-wrapper',
    'docker',
];

export function canonicalJson(value) {
    if (Array.isArray(value)) return `[${value.map(canonicalJson).join(',')}]`;
    if (value !== null && typeof value === 'object') {
        return `{${Object.keys(value)
            .sort()
            .map((key) => `${JSON.stringify(key)}:${canonicalJson(value[key])}`)
            .join(',')}}`;
    }
    return JSON.stringify(value);
}

export function sha256(value) {
    return createHash('sha256').update(value).digest('hex');
}

function gitBytes(worktree, args) {
    return execFileSync('git', ['-c', `safe.directory=${worktree}`, ...args], {
        cwd: worktree,
        encoding: 'buffer',
    });
}

function snapshotUntrackedFile(root, relativePath) {
    const filePath = path.join(root, relativePath);
    const stats = fs.lstatSync(filePath);
    if (stats.isSymbolicLink()) {
        const target = fs.readlinkSync(filePath, 'utf8');
        return {
            path: relativePath,
            mode: '120000',
            sha256: sha256(Buffer.from(target, 'utf8')),
        };
    }
    if (!stats.isFile()) {
        throw new Error(`지원하지 않는 untracked 항목입니다: ${relativePath}`);
    }
    return {
        path: relativePath,
        mode: (stats.mode & 0o111) === 0 ? '100644' : '100755',
        sha256: sha256(fs.readFileSync(filePath)),
    };
}

export function computeWorktreeSnapshot(worktree = process.cwd()) {
    const root = path.resolve(worktree);
    const baseCommit = gitBytes(root, ['rev-parse', 'HEAD']).toString('utf8').trim();
    const stagedBinaryDiffHash = sha256(gitBytes(root, ['diff', '--cached', '--binary']));
    const unstagedBinaryDiffHash = sha256(gitBytes(root, ['diff', '--binary']));
    const untrackedFiles = gitBytes(root, ['ls-files', '--others', '--exclude-standard', '-z'])
        .toString('utf8')
        .split('\0')
        .filter(Boolean)
        .sort()
        .map((relativePath) => snapshotUntrackedFile(root, relativePath));
    const trackedSeed = { stagedBinaryDiffHash, unstagedBinaryDiffHash };
    const implementationSeed = { ...trackedSeed, untrackedFiles };
    return {
        baseCommit,
        implementationDiffHash: sha256(Buffer.from(canonicalJson(implementationSeed), 'utf8')),
        trackedDiffHash: sha256(Buffer.from(canonicalJson(trackedSeed), 'utf8')),
        canonicalSeed: implementationSeed,
    };
}

function addError(errors, instancePath, keyword, message) {
    errors.push({ instancePath, keyword, message });
}

function childPath(parent, property) {
    return /^[A-Za-z_$][A-Za-z0-9_$]*$/.test(property)
        ? `${parent}.${property}`
        : `${parent}[${JSON.stringify(property)}]`;
}

function resolveRef(rootSchema, ref) {
    if (!ref.startsWith('#/')) throw new Error(`지원하지 않는 JSON Schema 참조입니다: ${ref}`);
    return ref
        .slice(2)
        .split('/')
        .map((token) => token.replaceAll('~1', '/').replaceAll('~0', '~'))
        .reduce((current, token) => current?.[token], rootSchema);
}

function matchesType(value, type) {
    if (type === 'null') return value === null;
    if (type === 'array') return Array.isArray(value);
    if (type === 'object') return value !== null && typeof value === 'object' && !Array.isArray(value);
    if (type === 'integer') return Number.isInteger(value);
    if (type === 'number') return typeof value === 'number' && Number.isFinite(value);
    return typeof value === type;
}

function validateSchemaNode(rootSchema, schema, value, instancePath, errors) {
    if (schema.$ref !== undefined) {
        const referenced = resolveRef(rootSchema, schema.$ref);
        if (referenced === undefined) throw new Error(`JSON Schema 참조를 찾을 수 없습니다: ${schema.$ref}`);
        validateSchemaNode(rootSchema, referenced, value, instancePath, errors);
        return;
    }

    if (schema.const !== undefined && !isDeepStrictEqual(value, schema.const)) {
        addError(errors, instancePath, 'const', `${JSON.stringify(schema.const)}이어야 합니다.`);
    }
    if (schema.enum !== undefined && !schema.enum.some((candidate) => isDeepStrictEqual(value, candidate))) {
        addError(errors, instancePath, 'enum', '허용되지 않는 값입니다.');
    }
    if (schema.type !== undefined) {
        const expectedTypes = Array.isArray(schema.type) ? schema.type : [schema.type];
        if (!expectedTypes.some((type) => matchesType(value, type))) {
            addError(errors, instancePath, 'type', `${expectedTypes.join(' 또는 ')}이어야 합니다.`);
            return;
        }
    }
    if (typeof value === 'string') {
        if (schema.minLength !== undefined && [...value].length < schema.minLength) {
            addError(errors, instancePath, 'minLength', `길이가 ${schema.minLength} 이상이어야 합니다.`);
        }
        if (schema.pattern !== undefined && !(new RegExp(schema.pattern, 'u')).test(value)) {
            addError(errors, instancePath, 'pattern', `패턴 ${schema.pattern}에 맞아야 합니다.`);
        }
    }
    if (typeof value === 'number' && schema.minimum !== undefined && value < schema.minimum) {
        addError(errors, instancePath, 'minimum', `${schema.minimum} 이상이어야 합니다.`);
    }
    if (Array.isArray(value)) {
        if (schema.minItems !== undefined && value.length < schema.minItems) {
            addError(errors, instancePath, 'minItems', `항목이 ${schema.minItems}개 이상이어야 합니다.`);
        }
        value.forEach((item, index) =>
            validateSchemaNode(rootSchema, schema.items, item, `${instancePath}[${index}]`, errors),
        );
    }
    if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
        const properties = schema.properties ?? {};
        for (const required of schema.required ?? []) {
            if (!Object.hasOwn(value, required)) {
                addError(errors, childPath(instancePath, required), 'required', '필수 속성이 없습니다.');
            }
        }
        for (const [property, propertyValue] of Object.entries(value)) {
            if (Object.hasOwn(properties, property)) {
                validateSchemaNode(rootSchema, properties[property], propertyValue, childPath(instancePath, property), errors);
            } else if (schema.additionalProperties === false) {
                addError(errors, childPath(instancePath, property), 'additionalProperties', '선언되지 않은 속성입니다.');
            }
        }
    }
}

function snapshotMatches(snapshot, expected, instancePath, errors, fields) {
    for (const field of fields) {
        if (snapshot?.[field] !== expected[field]) {
            addError(errors, `${instancePath}.${field}`, 'snapshotMatch', `expected의 ${field}와 다릅니다.`);
        }
    }
}

export function validateExpected(expected) {
    const errors = [];
    const commit = /^[0-9a-f]{40}$/;
    const hash = /^[0-9a-f]{64}$/;
    if (expected?.schemaVersion !== 2) errors.push('schemaVersion은 2여야 합니다.');
    const allowedTopLevelKeys = new Set([
        'schemaVersion',
        'baseCommit',
        'implementationDiffHash',
        'trackedDiffHash',
        'packetHash',
        'executions',
        'tests',
    ]);
    for (const key of Object.keys(expected ?? {})) {
        if (!allowedTopLevelKeys.has(key)) errors.push(`${key}는 expected에 허용되지 않습니다.`);
    }
    if (!commit.test(expected?.baseCommit ?? '')) errors.push('baseCommit은 40자리 소문자 commit SHA여야 합니다.');
    for (const field of ['implementationDiffHash', 'packetHash', 'trackedDiffHash']) {
        if (!hash.test(expected?.[field] ?? '')) errors.push(`${field}는 64자리 소문자 SHA-256이어야 합니다.`);
    }
    const executionIds = new Set();
    const commands = new Set();
    if (!Array.isArray(expected?.executions) || expected.executions.length === 0) {
        errors.push('executions는 하나 이상의 고유 실행을 포함해야 합니다.');
    } else {
        expected.executions.forEach((execution, index) => {
            if (execution?.id !== `E${index + 1}`) errors.push(`executions[${index}].id는 E${index + 1}이어야 합니다.`);
            executionIds.add(execution?.id);
            if (typeof execution?.command !== 'string' || !/\S/u.test(execution.command)) {
                errors.push(`executions[${index}].command가 비어 있습니다.`);
            } else if (commands.has(execution.command)) {
                errors.push(`executions[${index}].command가 중복되었습니다.`);
            }
            commands.add(execution?.command);
            if (execution?.timeoutMs !== undefined &&
                (!Number.isInteger(execution.timeoutMs) || execution.timeoutMs < 1000)) {
                errors.push(`executions[${index}].timeoutMs는 1000 이상의 정수여야 합니다.`);
            }
            if (!Array.isArray(execution?.junitTasks) ||
                execution.junitTasks.some((task) => typeof task !== 'string' || !/^[A-Za-z0-9:_-]+$/u.test(task)) ||
                new Set(execution.junitTasks).size !== execution.junitTasks.length) {
                errors.push(`executions[${index}].junitTasks가 올바르지 않습니다.`);
            }
            const allowedKeys = new Set(['id', 'command', 'timeoutMs', 'junitTasks']);
            for (const key of Object.keys(execution ?? {})) {
                if (!allowedKeys.has(key)) errors.push(`executions[${index}].${key}는 허용되지 않습니다.`);
            }
        });
    }
    if (!Array.isArray(expected?.tests) || expected.tests.length === 0) {
        errors.push('tests는 하나 이상의 승인 T-ID 증거 매핑을 포함해야 합니다.');
    } else {
        expected.tests.forEach((test, index) => {
            if (test?.id !== `T${index + 1}`) errors.push(`tests[${index}].id는 T${index + 1}이어야 합니다.`);
            if (!Array.isArray(test?.executionIds) || test.executionIds.length === 0 ||
                new Set(test.executionIds).size !== test.executionIds.length) {
                errors.push(`tests[${index}].executionIds가 비어 있거나 중복되었습니다.`);
            } else {
                test.executionIds.forEach((executionId) => {
                    if (!executionIds.has(executionId)) {
                        errors.push(`tests[${index}].executionIds에 존재하지 않는 ${executionId}가 있습니다.`);
                    }
                });
            }
            if (!Array.isArray(test?.testSources) || test.testSources.length === 0 ||
                new Set(test.testSources).size !== test.testSources.length ||
                test.testSources.some((source) =>
                    typeof source !== 'string' || !/^src\/(?:test|postgresTest)\/java\/.+\.java$/u.test(source))) {
                errors.push(`tests[${index}].testSources가 올바르지 않습니다.`);
            }
            const allowedKeys = new Set(['id', 'executionIds', 'testSources']);
            for (const key of Object.keys(test ?? {})) {
                if (!allowedKeys.has(key)) errors.push(`tests[${index}].${key}는 허용되지 않습니다.`);
            }
        });
    }
    if (errors.length > 0) throw new Error(`expected 입력이 올바르지 않습니다: ${errors.join(' ')}`);
}

function calculatedTestVerdict(executionIds, executionById) {
    const executions = executionIds.map((executionId) => executionById.get(executionId));
    if (executions.some((execution) => execution?.verdict === 'fail')) return 'fail';
    if (executions.some((execution) => execution?.verdict !== 'pass')) return 'unverified';
    return 'pass';
}

function validatePreflightRelations(preflight, errors) {
    const checks = Array.isArray(preflight?.checks) ? preflight.checks : [];
    if (checks.length !== REQUIRED_PREFLIGHT_CHECK_NAMES.length) {
        addError(
            errors,
            '$.preflight.checks',
            'preflightCheckCount',
            `필수 preflight check ${REQUIRED_PREFLIGHT_CHECK_NAMES.length}개가 정확히 있어야 합니다.`,
        );
    }

    const requiredNames = new Set(REQUIRED_PREFLIGHT_CHECK_NAMES);
    const seenNames = new Set();
    checks.forEach((check, index) => {
        if (!requiredNames.has(check?.name)) {
            addError(
                errors,
                `$.preflight.checks[${index}].name`,
                'unexpectedPreflightCheck',
                '허용되지 않는 preflight check입니다.',
            );
        }
        if (seenNames.has(check?.name)) {
            addError(
                errors,
                `$.preflight.checks[${index}].name`,
                'duplicatePreflightCheck',
                'preflight check 이름이 중복되었습니다.',
            );
        }
        seenNames.add(check?.name);
    });
    for (const name of REQUIRED_PREFLIGHT_CHECK_NAMES) {
        if (!seenNames.has(name)) {
            addError(
                errors,
                '$.preflight.checks',
                'missingPreflightCheck',
                `필수 preflight check ${name}이(가) 없습니다.`,
            );
        }
    }

    const calculatedVerdict = checks.some(({ verdict }) => verdict !== 'pass') ? 'unverified' : 'pass';
    if (preflight?.verdict !== calculatedVerdict) {
        addError(
            errors,
            '$.preflight.verdict',
            'preflightVerdict',
            `개별 check 결과에 따른 ${calculatedVerdict}여야 합니다.`,
        );
    }
    return calculatedVerdict;
}

function validateResultRelations(result, expected) {
    const errors = [];
    snapshotMatches(result?.snapshot, expected, '$.snapshot', errors, [
        'baseCommit', 'implementationDiffHash', 'packetHash',
    ]);
    snapshotMatches(result?.startedSnapshot, expected, '$.startedSnapshot', errors, [
        'baseCommit', 'implementationDiffHash', 'packetHash', 'trackedDiffHash',
    ]);
    snapshotMatches(result?.finishedSnapshot, expected, '$.finishedSnapshot', errors, [
        'packetHash',
    ]);
    const calculatedPreflightVerdict = validatePreflightRelations(result?.preflight, errors);

    const executionResults = Array.isArray(result?.executionResults) ? result.executionResults : [];
    if (executionResults.length !== expected.executions.length) {
        addError(errors, '$.executionResults', 'executionCount', '승인 실행 개수와 다릅니다.');
    }
    const executionById = new Map();
    executionResults.forEach((executionResult, index) => {
        const expectedExecution = expected.executions[index];
        if (executionById.has(executionResult?.executionId)) {
            addError(errors, `$.executionResults[${index}].executionId`, 'duplicateExecution', 'executionId가 중복되었습니다.');
        }
        executionById.set(executionResult?.executionId, executionResult);
        if (executionResult?.executionId !== expectedExecution?.id) {
            addError(errors, `$.executionResults[${index}].executionId`, 'executionOrder', '승인 실행 순서 또는 ID와 다릅니다.');
        }
        if (executionResult?.command !== expectedExecution?.command) {
            addError(errors, `$.executionResults[${index}].command`, 'approvedCommand', '승인된 명령 원문과 다릅니다.');
        }
        if (executionResult?.commandHash !== sha256(Buffer.from(expectedExecution?.command ?? '', 'utf8'))) {
            addError(errors, `$.executionResults[${index}].commandHash`, 'commandHash', '승인 명령 SHA-256과 다릅니다.');
        }
        const executed = typeof executionResult?.evidenceHash === 'string';
        const junitEvidence = Array.isArray(executionResult?.junitEvidence) ? executionResult.junitEvidence : [];
        if (executionResult?.verdict === 'pass' &&
            !(executionResult.exitCode === 0 && executed && executionResult.notRunReason === null)) {
            addError(errors, `$.executionResults[${index}]`, 'passEvidence', 'pass는 exitCode 0, evidenceHash와 null notRunReason이 필요합니다.');
        }
        if (executionResult?.verdict === 'pass') {
            const actualTasks = junitEvidence.map(({ task }) => task);
            if (!isDeepStrictEqual(actualTasks, expectedExecution?.junitTasks ?? [])) {
                addError(errors, `$.executionResults[${index}].junitEvidence`, 'junitTasks', '승인 실행의 JUnit task 증거와 다릅니다.');
            }
        }
        if (executionResult?.verdict === 'fail' &&
            !(Number.isInteger(executionResult.exitCode) && executionResult.exitCode !== 0 && executed && executionResult.notRunReason === null)) {
            addError(errors, `$.executionResults[${index}]`, 'failEvidence', 'fail은 non-zero exitCode, evidenceHash와 null notRunReason이 필요합니다.');
        }
        if (executionResult?.verdict === 'unverified' &&
            !(typeof executionResult.notRunReason === 'string' && /\S/u.test(executionResult.notRunReason))) {
            addError(errors, `$.executionResults[${index}]`, 'unverifiedReason', 'unverified는 구체적 notRunReason이 필요합니다.');
        }
    });

    const testResults = Array.isArray(result?.testResults) ? result.testResults : [];
    if (testResults.length !== expected.tests.length) {
        addError(errors, '$.testResults', 'testCount', '승인 T-ID 개수와 다릅니다.');
    }
    const seenTestIds = new Set();
    testResults.forEach((testResult, index) => {
        const expectedTest = expected.tests[index];
        if (seenTestIds.has(testResult?.testId)) {
            addError(errors, `$.testResults[${index}].testId`, 'duplicateTId', 'T-ID가 중복되었습니다.');
        }
        seenTestIds.add(testResult?.testId);
        if (testResult?.testId !== expectedTest?.id) {
            addError(errors, `$.testResults[${index}].testId`, 'testOrder', '승인 T-ID 순서 또는 값이 다릅니다.');
        }
        if (!isDeepStrictEqual(testResult?.executionIds, expectedTest?.executionIds)) {
            addError(errors, `$.testResults[${index}].executionIds`, 'executionMapping', '승인 T-ID 실행 매핑과 다릅니다.');
        }
        const calculated = calculatedTestVerdict(expectedTest?.executionIds ?? [], executionById);
        if (testResult?.verdict !== calculated) {
            addError(errors, `$.testResults[${index}].verdict`, 'testVerdict', `연결 실행 결과에 따른 ${calculated}여야 합니다.`);
        }
        const hasReason = typeof testResult?.notRunReason === 'string' && /\S/u.test(testResult.notRunReason);
        if (calculated === 'unverified' && !hasReason) {
            addError(errors, `$.testResults[${index}].notRunReason`, 'unverifiedReason', '미검증 T-ID에는 구체적 사유가 필요합니다.');
        }
        if (calculated !== 'unverified' && testResult?.notRunReason !== null) {
            addError(errors, `$.testResults[${index}].notRunReason`, 'notRunReason', 'pass 또는 fail T-ID의 notRunReason은 null이어야 합니다.');
        }
    });

    const snapshotChanged = [
        'baseCommit',
        'implementationDiffHash',
        'trackedDiffHash',
    ].some((field) => result?.startedSnapshot?.[field] !== result?.finishedSnapshot?.[field]);
    const verdicts = executionResults.map((executionResult) => executionResult?.verdict);
    const calculatedOverall = verdicts.includes('fail')
        ? 'fail'
        : snapshotChanged || calculatedPreflightVerdict === 'unverified' || verdicts.includes('unverified')
            ? 'unverified'
            : 'pass';
    if (result?.overallVerdict !== calculatedOverall) {
        addError(errors, '$.overallVerdict', 'overallVerdict', `세부 결과와 snapshot에 따른 ${calculatedOverall}이어야 합니다.`);
    }
    const hasOverallReason = typeof result?.overallReason === 'string' && /\S/.test(result.overallReason);
    if (calculatedOverall === 'unverified' && !hasOverallReason) {
        addError(errors, '$.overallReason', 'overallReason', 'unverified에는 구체적 overallReason이 필요합니다.');
    }
    if (calculatedOverall !== 'unverified' && result?.overallReason !== null) {
        addError(errors, '$.overallReason', 'overallReason', 'pass 또는 fail의 overallReason은 null이어야 합니다.');
    }
    return errors;
}

export function validateBackendTestResult(result, schema, expected) {
    validateExpected(expected);
    const errors = [];
    validateSchemaNode(schema, schema, result, '$', errors);
    return errors.length > 0 ? errors : validateResultRelations(result, expected);
}

function readJson(filePath, label) {
    try {
        return JSON.parse(fs.readFileSync(filePath, 'utf8'));
    } catch (error) {
        throw new Error(`${label} JSON을 읽을 수 없습니다 (${filePath}): ${error.message}`);
    }
}

export function validateBackendTestResultFile(resultPath, expectedPath, schemaPath = DEFAULT_SCHEMA_PATH) {
    const result = readJson(path.resolve(resultPath), '결과');
    const expected = readJson(path.resolve(expectedPath), 'expected');
    const schema = readJson(path.resolve(schemaPath), '스키마');
    return { result, expected, errors: validateBackendTestResult(result, schema, expected) };
}

function runCli() {
    if (process.argv[2] === '--snapshot' && process.argv.length <= 4) {
        try {
            const worktree = process.argv[3] ?? process.cwd();
            process.stdout.write(`${JSON.stringify(computeWorktreeSnapshot(worktree), null, 2)}\n`);
        } catch (error) {
            console.error(`snapshot 계산 실패: ${error.message}`);
            process.exitCode = 1;
        }
        return;
    }
    if (process.argv.length !== 6 || process.argv[2] !== '--result' || process.argv[4] !== '--expected') {
        console.error('사용법: node scripts/validate-backend-test-result.mjs --snapshot [worktree] | --result <result.json> --expected <expected.json>');
        process.exitCode = 2;
        return;
    }
    try {
        const outcome = validateBackendTestResultFile(process.argv[3], process.argv[5]);
        if (outcome.errors.length > 0) {
            for (const error of outcome.errors) {
                console.error(`- ${error.instancePath}: ${error.message} [${error.keyword}]`);
            }
            process.exitCode = 1;
            return;
        }
        console.log(`backend-tester 결과 검증 통과: ${outcome.result.testResults.length}개 T-ID`);
    } catch (error) {
        console.error(`backend-tester 결과 검증 실패: ${error.message}`);
        process.exitCode = 1;
    }
}

const entryPoint = process.argv[1] ? pathToFileURL(path.resolve(process.argv[1])).href : null;
if (entryPoint === import.meta.url) runCli();
