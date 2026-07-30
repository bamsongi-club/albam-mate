#!/usr/bin/env node

import { createHash } from 'node:crypto';
import { execFileSync, spawnSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = execFileSync('git', ['rev-parse', '--show-toplevel'], {
    encoding: 'utf8',
}).trim();
const HARNESS_DIR = '.agents/evals/test-contract-harness';
const CASES_PATH = `${HARNESS_DIR}/cases.json`;
const FIXTURE_PATH = `${HARNESS_DIR}/fixtures/invalid-trace-packet.json`;
const SUMMARY_PATH = `${HARNESS_DIR}/results-summary.json`;
const IGNORE_PROBE = `${HARNESS_DIR}/runs/private-probe.json`;
const TARGET_INPUTS = [
    '.agents/skills/issue-writer/SKILL.md',
    '.agents/skills/backend-delivery/SKILL.md',
    '.agents/skills/review-code/SKILL.md',
    '.codex/agents/review-code-reviewer.toml',
    '.codex/contracts/backend-implementation-packet.schema.json',
    'scripts/validate-packet.mjs',
    'scripts/validate-packet.test.mjs',
];
const CANDIDATE_BLOB_PATHS = {
    issueWriterBlob: '.agents/skills/issue-writer/SKILL.md',
    backendDeliveryBlob: '.agents/skills/backend-delivery/SKILL.md',
    reviewCodeBlob: '.agents/skills/review-code/SKILL.md',
    reviewCodeReviewerBlob: '.codex/agents/review-code-reviewer.toml',
    validatorBlob: 'scripts/validate-packet.mjs',
    testFileBlob: 'scripts/validate-packet.test.mjs',
    schemaBlob: '.codex/contracts/backend-implementation-packet.schema.json',
};
const FORBIDDEN_KEYS = new Set([
    'privateBrainPath',
    'rawOutput',
    'rawPrompt',
    'rolloutPath',
    'sessionId',
    'sessionMetadata',
    'threadId',
]);
const ARCHIVE_ID =
    /^\d{8}T\d{6}Z-[0-9a-f]{7,12}-[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function fail(message) {
    throw new Error(message);
}

function sha256(value) {
    return createHash('sha256').update(value).digest('hex');
}

function canonical(value) {
    if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
    if (value !== null && typeof value === 'object') {
        const entries = Object.keys(value)
            .sort()
            .map((key) => `${JSON.stringify(key)}:${canonical(value[key])}`);
        return `{${entries.join(',')}}`;
    }
    return JSON.stringify(value);
}

function readJson(relativeOrAbsolutePath) {
    const resolved = path.isAbsolute(relativeOrAbsolutePath)
        ? relativeOrAbsolutePath
        : path.join(ROOT, relativeOrAbsolutePath);
    try {
        return JSON.parse(fs.readFileSync(resolved, 'utf8'));
    } catch (error) {
        fail(`JSON을 읽을 수 없습니다: ${relativeOrAbsolutePath}: ${error.message}`);
    }
}

function readBytes(relativeOrAbsolutePath) {
    const resolved = path.isAbsolute(relativeOrAbsolutePath)
        ? relativeOrAbsolutePath
        : path.join(ROOT, relativeOrAbsolutePath);
    return fs.readFileSync(resolved);
}

function parseArgs(argv) {
    const options = {
        mode: 'check',
        privateRuns: [],
        targetCommit: null,
        generatedOn: null,
        archiveReceipt: null,
        member: process.env.BAMSONGI_MEMBER ?? null,
    };

    for (let index = 0; index < argv.length; index += 1) {
        const argument = argv[index];
        if (argument === '--check') options.mode = 'check';
        else if (argument === '--write') options.mode = 'write';
        else if (argument === '--receipt-seed') options.mode = 'receipt-seed';
        else if (argument === '--private-run') options.privateRuns.push(argv[++index]);
        else if (argument === '--target-commit') options.targetCommit = argv[++index];
        else if (argument === '--generated-on') options.generatedOn = argv[++index];
        else if (argument === '--archive-receipt') options.archiveReceipt = argv[++index];
        else if (argument === '--member') options.member = argv[++index];
        else fail(`알 수 없는 인자입니다: ${argument}`);
    }

    if (options.privateRuns.some((value) => value === undefined)) fail('--private-run 값이 필요합니다.');
    return options;
}

function resolveMember(value) {
    if (!/^[a-z][a-z0-9-]{1,31}$/.test(value ?? '')) {
        fail('BAMSONGI_MEMBER 또는 --member는 소문자 영문으로 시작하는 안전한 식별자여야 합니다.');
    }
    return value;
}

function resolveCommit(value) {
    if (!value) fail('--target-commit이 필요합니다.');
    const commit = execFileSync('git', ['rev-parse', `${value}^{commit}`], {
        cwd: ROOT,
        encoding: 'utf8',
    }).trim();
    if (!/^[0-9a-f]{40}$/.test(commit)) fail(`40자리 commit SHA가 아닙니다: ${commit}`);
    return commit;
}

function gitBlob(commit, relativePath) {
    return execFileSync('git', ['rev-parse', `${commit}:${relativePath}`], {
        cwd: ROOT,
        encoding: 'utf8',
    }).trim();
}

function targetFile(commit, relativePath) {
    return execFileSync('git', ['show', `${commit}:${relativePath}`], {
        cwd: ROOT,
        encoding: null,
        maxBuffer: 16 * 1024 * 1024,
    });
}

function validateCases(cases) {
    if (cases?.schemaVersion !== 1 || typeof cases.suiteId !== 'string') {
        fail('cases.json의 schemaVersion 또는 suiteId가 올바르지 않습니다.');
    }
    if (!Array.isArray(cases.cases) || cases.cases.length === 0) fail('cases가 비어 있습니다.');

    const caseIds = new Set();
    const checkIds = new Set();
    for (const testCase of cases.cases) {
        if (typeof testCase.id !== 'string' || caseIds.has(testCase.id)) {
            fail(`case id가 없거나 중복되었습니다: ${testCase.id}`);
        }
        caseIds.add(testCase.id);
        if (!Array.isArray(testCase.checks) || testCase.checks.length === 0) {
            fail(`${testCase.id}의 checks가 비어 있습니다.`);
        }
        for (const check of testCase.checks) {
            if (typeof check.id !== 'string' || checkIds.has(check.id)) {
                fail(`check id가 없거나 중복되었습니다: ${check.id}`);
            }
            checkIds.add(check.id);
        }
    }
    return { caseIds: [...caseIds], checkIds: [...checkIds] };
}

function aggregateVerdicts(verdicts) {
    if (verdicts.some(({ verdict }) => verdict === 'fail')) return 'Changes Requested';
    if (verdicts.some(({ verdict }) => verdict === 'unverified')) return 'Incomplete';
    if (verdicts.length > 0 && verdicts.every(({ verdict }) => verdict === 'pass')) return 'Approve';
    fail('허용되지 않은 verdict가 있습니다.');
}

function validateVerdictCases(cases) {
    let inputSetCount = 0;
    for (const testCase of cases.cases) {
        for (const inputSet of testCase.inputSets ?? []) {
            const verdicts = inputSet.expectedVerdicts ?? inputSet.verdicts;
            const expected = inputSet.expectedOverallVerdict ?? inputSet.overallVerdict;
            if (!verdicts || !expected) continue;
            if (aggregateVerdicts(verdicts) !== expected) {
                fail(`${testCase.id}/${inputSet.id}의 verdict 집계가 일치하지 않습니다.`);
            }
            inputSetCount += 1;
        }
    }
    return inputSetCount;
}

function scanPublicJson(value, location = '$') {
    if (Array.isArray(value)) {
        value.forEach((item, index) => scanPublicJson(item, `${location}[${index}]`));
        return;
    }
    if (value !== null && typeof value === 'object') {
        for (const [key, child] of Object.entries(value)) {
            if (FORBIDDEN_KEYS.has(key)) fail(`${location}.${key}에 공개 금지 키가 있습니다.`);
            scanPublicJson(child, `${location}.${key}`);
        }
        return;
    }
    if (typeof value === 'string') {
        if (/(?:^|\s)[A-Za-z]:[\\/]/.test(value) || /\/(Users|home)\//.test(value)) {
            fail(`${location}에 로컬 절대 경로가 있습니다.`);
        }
        if (/knowledge[\\/]private|prompts[\\/][^/\\]+[\\/]\d{4}-\d{2}-\d{2}/i.test(value)) {
            fail(`${location}에 Private Brain 내부 경로가 있습니다.`);
        }
    }
}

function runMechanicalChecks(cases, fixture) {
    const verdictInputSets = validateVerdictCases(cases);
    scanPublicJson(cases);
    scanPublicJson(fixture);

    const validator = spawnSync(
        process.execPath,
        ['scripts/validate-packet.mjs', FIXTURE_PATH],
        { cwd: ROOT, encoding: 'utf8' },
    );
    const diagnostics = `${validator.stdout ?? ''}\n${validator.stderr ?? ''}`;
    const requiredDiagnostics = ['continuousTId', 'testSourceApproval', 'approvalIssueNumber'];
    if (validator.status !== 1 || requiredDiagnostics.some((value) => !diagnostics.includes(value))) {
        fail('PV-01이 종료 코드 1과 필수 진단을 재현하지 못했습니다.');
    }

    const ignore = spawnSync('git', ['check-ignore', '-q', IGNORE_PROBE], { cwd: ROOT });
    if (ignore.status !== 0) fail(`${IGNORE_PROBE}가 .gitignore 대상이 아닙니다.`);

    return {
        jsonIntegrity: 'pass',
        caseContract: 'pass',
        packetFixture: 'pass',
        publicBoundary: 'pass',
        verdictAggregation: 'pass',
        verdictInputSetCount: verdictInputSets,
    };
}

function buildInputs(targetCommit, cases, fixture) {
    const files = [
        {
            path: CASES_PATH,
            source: 'worktree',
            sha256: sha256(canonical(cases)),
        },
        {
            path: FIXTURE_PATH,
            source: 'worktree',
            sha256: sha256(canonical(fixture)),
        },
        ...TARGET_INPUTS.map((relativePath) => ({
            path: relativePath,
            source: `commit:${targetCommit}`,
            sha256: sha256(targetFile(targetCommit, relativePath)),
        })),
    ].sort((left, right) => left.path.localeCompare(right.path));
    const inputHash = sha256(files.map((file) => `${file.path}\0${file.sha256}`).join('\n'));
    return { algorithm: 'sha256', files, inputHash };
}

function asArray(value) {
    return Array.isArray(value) ? value : [value];
}

function summarizePrivateRun(runPath, targetCommit) {
    const bytes = readBytes(runPath);
    const run = readJson(runPath);
    if (run?.schemaVersion !== 1 || typeof run.runId !== 'string' || typeof run.recordedAt !== 'string') {
        fail(`상세 run 형식이 올바르지 않습니다: ${runPath}`);
    }

    const arms = {};
    const caseIds = new Set();
    for (const arm of ['baseline', 'candidate']) {
        const results = asArray(run.armResults?.[arm] ?? []);
        let passed = 0;
        let total = 0;
        const checkIds = [];
        for (const result of results) {
            if (typeof result.caseId !== 'string' || !Array.isArray(result.checks)) {
                fail(`${run.runId}/${arm} 결과 형식이 올바르지 않습니다.`);
            }
            caseIds.add(result.caseId);
            for (const check of result.checks) {
                if (typeof check.id !== 'string' || checkIds.includes(check.id)) {
                    fail(`${run.runId}/${arm} check id가 없거나 중복되었습니다: ${check.id}`);
                }
                if (!['pass', 'fail'].includes(check.result)) {
                    fail(`${run.runId}/${arm}/${check.id} 결과가 올바르지 않습니다.`);
                }
                checkIds.push(check.id);
                total += 1;
                if (check.result === 'pass') passed += 1;
            }
        }
        if (run.scores?.[arm] &&
            (run.scores[arm].passed !== passed || run.scores[arm].total !== total)) {
            fail(`${run.runId}/${arm} 점수와 check 결과가 일치하지 않습니다.`);
        }
        arms[arm] = { passed, total, checkIds: checkIds.sort() };
    }

    const candidate = run.instructionArms?.candidate ?? {};
    const comparedBlobs = [];
    for (const [field, relativePath] of Object.entries(CANDIDATE_BLOB_PATHS)) {
        if (typeof candidate[field] !== 'string') continue;
        const targetBlob = gitBlob(targetCommit, relativePath);
        comparedBlobs.push({ field, matches: candidate[field] === targetBlob });
    }
    const candidateInputMatch = comparedBlobs.length > 0 && comparedBlobs.every(({ matches }) => matches);

    return {
        runId: run.runId,
        suiteId: run.suiteId,
        recordedAt: run.recordedAt,
        caseIds: [...caseIds].sort(),
        sha256: sha256(bytes),
        candidateInputMatch,
        comparedBlobCount: comparedBlobs.length,
        scores: arms,
    };
}

function summarizeRuns(runPaths, targetCommit, expectedCheckIds) {
    const runs = runPaths.map((runPath) => summarizePrivateRun(runPath, targetCommit))
        .sort((left, right) => left.runId.localeCompare(right.runId));
    const runIds = new Set();
    for (const run of runs) {
        if (runIds.has(run.runId)) fail(`상세 run id가 중복되었습니다: ${run.runId}`);
        runIds.add(run.runId);
    }

    const historicalScores = {
        baseline: { passed: 0, total: 0 },
        candidate: { passed: 0, total: 0 },
    };
    for (const run of runs) {
        for (const arm of ['baseline', 'candidate']) {
            historicalScores[arm].passed += run.scores[arm].passed;
            historicalScores[arm].total += run.scores[arm].total;
        }
    }

    const exactRuns = runs.filter(({ candidateInputMatch }) => candidateInputMatch);
    const exactCandidateIds = exactRuns.flatMap((run) => run.scores.candidate.checkIds).sort();
    const expectedIds = [...expectedCheckIds].sort();
    const exactCandidatePassed = exactRuns.reduce((sum, run) => sum + run.scores.candidate.passed, 0);
    let targetStatus = 'not-run';
    if (exactRuns.length > 0 && canonical(exactCandidateIds) === canonical(expectedIds)) {
        targetStatus = exactCandidatePassed === expectedIds.length ? 'pass' : 'fail';
    }

    const resultSetHash = runs.length === 0
        ? null
        : sha256(runs.map((run) => `${run.runId}\0${run.sha256}`).join('\n'));
    const publicRuns = runs.map((run) => ({
        ...run,
        scores: {
            baseline: {
                passed: run.scores.baseline.passed,
                total: run.scores.baseline.total,
            },
            candidate: {
                passed: run.scores.candidate.passed,
                total: run.scores.candidate.total,
            },
        },
    }));
    return { runs: publicRuns, historicalScores, resultSetHash, targetStatus };
}

function coreLinkHash(suiteId, targetCommit, inputHash, resultSetHash) {
    return sha256(canonical({ suiteId, targetCommit, inputHash, resultSetHash }));
}

function receiptSeed(suiteId, targetCommit, inputs, runSummary, member) {
    return {
        schemaVersion: 1,
        member,
        suiteId,
        targetCommit,
        inputHash: inputs.inputHash,
        resultSetHash: runSummary.resultSetHash,
        coreLinkHash: coreLinkHash(
            suiteId,
            targetCommit,
            inputs.inputHash,
            runSummary.resultSetHash,
        ),
        runs: runSummary.runs.map(({ runId, recordedAt, sha256: digest }) => ({
            runId,
            recordedAt,
            sha256: digest,
        })),
    };
}

function validateReceipt(receiptPath, seed) {
    const receipt = readJson(receiptPath);
    if (!ARCHIVE_ID.test(receipt.archiveId ?? '')) {
        fail('Private Brain receipt의 archiveId가 UTC 시각-commit-UUID 형식이 아닙니다.');
    }
    for (const field of ['member', 'suiteId', 'targetCommit', 'inputHash', 'resultSetHash', 'coreLinkHash']) {
        if (receipt[field] !== seed[field]) fail(`Private Brain receipt의 ${field}가 현재 입력과 다릅니다.`);
    }
    if (!/^\d{4}-\d{2}-\d{2}T/.test(receipt.recordedAt ?? '')) {
        fail('Private Brain receipt에 recordedAt이 없습니다.');
    }
    if (canonical(receipt.runs) !== canonical(seed.runs)) {
        fail('Private Brain receipt의 run 해시가 현재 상세 결과와 다릅니다.');
    }
    return { receiptHash: sha256(readBytes(receiptPath)), recordedAt: receipt.recordedAt };
}

function buildSummary(options, cases, fixture, validation) {
    const targetCommit = resolveCommit(options.targetCommit);
    const inputs = buildInputs(targetCommit, cases, fixture);
    const runSummary = summarizeRuns(options.privateRuns, targetCommit, validation.checkIds);
    const member = options.archiveReceipt ? resolveMember(options.member) : null;
    const seed = receiptSeed(cases.suiteId, targetCommit, inputs, runSummary, member);
    const receipt = options.archiveReceipt
        ? validateReceipt(options.archiveReceipt, seed)
        : { receiptHash: null, recordedAt: null };
    if (!/^\d{4}-\d{2}-\d{2}$/.test(options.generatedOn ?? '')) {
        fail('--generated-on은 YYYY-MM-DD 형식이어야 합니다.');
    }

    return {
        schemaVersion: 1,
        suiteId: cases.suiteId,
        generatedOn: options.generatedOn,
        target: {
            commit: targetCommit,
            evaluationStatus: runSummary.targetStatus,
        },
        inputs,
        results: {
            algorithm: 'sha256',
            resultSetHash: runSummary.resultSetHash,
            historicalRuns: runSummary.runs,
            historicalScores: runSummary.historicalScores,
        },
        archive: {
            status: options.archiveReceipt ? 'archived' : 'pending',
            receiptHash: receipt.receiptHash,
            recordedAt: receipt.recordedAt,
            coreLinkHash: seed.coreLinkHash,
        },
        validation: {
            caseCount: validation.caseIds.length,
            checkCount: validation.checkIds.length,
            ...runMechanicalChecks(cases, fixture),
        },
        limitations: [
            '고정된 case와 단일 회차의 상세 실행을 요약하므로 일반적인 모델 성능을 확정하지 않는다.',
            'runner는 모델을 호출하지 않으며 candidate 입력이 대상 commit과 일치하지 않는 실행은 대상 평가 통과 근거로 사용하지 않는다.',
            '개별 agent 원문과 상세 run JSON은 팀 Private Brain에만 있고 공개 요약에는 해시와 집계만 남긴다.',
            'fresh-agent 입력은 런타임 동작이나 실제 backend-developer 위임을 수행하지 않는 dry-run이다.',
        ],
    };
}

function checkSummary(cases, fixture, validation, privateRuns) {
    const summary = readJson(SUMMARY_PATH);
    const targetCommit = resolveCommit(summary.target?.commit);
    const inputs = buildInputs(targetCommit, cases, fixture);
    if (summary.suiteId !== cases.suiteId || canonical(summary.inputs) !== canonical(inputs)) {
        fail('results-summary.json의 suite 또는 입력 해시가 현재 파일과 다릅니다.');
    }
    if (summary.validation?.caseCount !== validation.caseIds.length ||
        summary.validation?.checkCount !== validation.checkIds.length) {
        fail('results-summary.json의 case/check 수가 현재 정의와 다릅니다.');
    }
    const mechanical = runMechanicalChecks(cases, fixture);
    for (const [key, value] of Object.entries(mechanical)) {
        if (summary.validation[key] !== value) fail(`results-summary.json의 ${key}가 현재 검사와 다릅니다.`);
    }

    const storedRuns = summary.results?.historicalRuns ?? [];
    const storedResultHash = storedRuns.length === 0
        ? null
        : sha256(storedRuns.map((run) => `${run.runId}\0${run.sha256}`).sort().join('\n'));
    if (summary.results?.resultSetHash !== storedResultHash) fail('상세 결과 묶음 해시가 일치하지 않습니다.');
    const expectedCore = coreLinkHash(cases.suiteId, targetCommit, inputs.inputHash, storedResultHash);
    if (summary.archive?.coreLinkHash !== expectedCore) fail('Private Brain 연결 해시가 일치하지 않습니다.');
    if (summary.target?.evaluationStatus === 'pass' &&
        !storedRuns.some(({ candidateInputMatch }) => candidateInputMatch)) {
        fail('대상 입력과 일치하는 모델 실행 없이 pass로 표시했습니다.');
    }

    if (privateRuns.length > 0) {
        const actual = summarizeRuns(privateRuns, targetCommit, validation.checkIds);
        if (canonical(actual.runs) !== canonical(storedRuns) ||
            actual.resultSetHash !== storedResultHash ||
            actual.targetStatus !== summary.target.evaluationStatus) {
            fail('Private Brain 상세 run과 공개 결과 요약이 다릅니다.');
        }
    }
    scanPublicJson(summary);
    const serialized = `${JSON.stringify(summary, null, 2)}\n`;
    if (fs.readFileSync(path.join(ROOT, SUMMARY_PATH), 'utf8').replace(/\r\n/g, '\n') !== serialized) {
        fail('results-summary.json이 결정적 2-space JSON 형식이 아닙니다.');
    }
    return summary;
}

function main() {
    const options = parseArgs(process.argv.slice(2));
    const cases = readJson(CASES_PATH);
    const fixture = readJson(FIXTURE_PATH);
    const validation = validateCases(cases);

    if (options.mode === 'receipt-seed') {
        const targetCommit = resolveCommit(options.targetCommit);
        const member = resolveMember(options.member);
        const inputs = buildInputs(targetCommit, cases, fixture);
        const runs = summarizeRuns(options.privateRuns, targetCommit, validation.checkIds);
        process.stdout.write(
            `${JSON.stringify(receiptSeed(cases.suiteId, targetCommit, inputs, runs, member), null, 2)}\n`,
        );
        return;
    }
    if (options.mode === 'write') {
        const summary = buildSummary(options, cases, fixture, validation);
        fs.writeFileSync(path.join(ROOT, SUMMARY_PATH), `${JSON.stringify(summary, null, 2)}\n`, 'utf8');
        process.stdout.write(`핵심 결과 요약 생성 완료: ${SUMMARY_PATH}\n`);
        return;
    }

    const summary = checkSummary(cases, fixture, validation, options.privateRuns);
    process.stdout.write(
        `테스트 계약 eval 검증 성공: ${summary.validation.caseCount} cases, ` +
        `${summary.validation.checkCount} checks, target=${summary.target.evaluationStatus}\n`,
    );
}

try {
    main();
} catch (error) {
    process.stderr.write(`테스트 계약 eval 검증 실패: ${error.message}\n`);
    process.exitCode = 1;
}
