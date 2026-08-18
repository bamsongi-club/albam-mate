#!/usr/bin/env node

import { createHash } from 'node:crypto';
import {
  existsSync,
  lstatSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

import { createT5RepetitionPlan } from '../lib/t5-repetition-plan.mjs';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '../../../..');
const defaultBuildRoot = path.join(repositoryRoot, 'build', 'k6', 'room');
const COMPARISON_FILE = 't5-comparison-verification.json';
const RUN_MANIFEST_FILE = 'run-manifest.json';
const RESOURCE_SIGNALS_FILE = 'resource-signals.json';
const SOURCE_SHA_PATTERN = /^[0-9a-f]{40}$/i;
const TARGET_ENVIRONMENT_PATTERN = /^[a-z0-9][a-z0-9._-]{0,79}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/i;
const UTC_TIMESTAMP_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{3})?Z$/;
const SUCCESS_DURATION_METRIC = 'room_request_duration{outcome:success}';
const OUTCOME_METRICS = [
  'success',
  'business',
  'concurrency',
  'unexpected',
].map((outcome) => `room_request_duration{outcome:${outcome}}`);
const RESOURCE_GROUPS = ['http', 'tomcat', 'hikari', 'jvm', 'postgresql'];
const RESOURCE_GROUP_FIELDS = Object.freeze({
  http: ['requestCount', 'failedRequestCount', 'rps'],
  tomcat: ['activeThreads', 'busyThreads', 'maxThreads'],
  hikari: ['activeConnections', 'idleConnections', 'pendingThreads', 'maxPoolSize'],
  jvm: ['heapUsedBytes', 'heapMaxBytes', 'cpuPercent'],
  postgresql: ['cpuPercent', 'activeConnections', 'lockWaitCount'],
});
const RESOURCE_REQUIRED_FIELDS = Object.freeze({
  http: ['requestCount', 'failedRequestCount'],
  tomcat: ['activeThreads'],
  hikari: ['activeConnections'],
  jvm: ['heapUsedBytes'],
  postgresql: ['activeConnections'],
});
const QUERY_SIGNAL_FIELDS = [
  'callCount',
  'totalTimeMilliseconds',
  'sharedBuffersHit',
  'sharedBuffersRead',
];

function fail(message) {
  throw new Error(message);
}

function isObject(value) {
  return value && typeof value === 'object' && !Array.isArray(value);
}

function readJson(filePath, label) {
  if (!existsSync(filePath)) {
    return { error: `${label}이 없습니다.` };
  }
  try {
    return { value: JSON.parse(readFileSync(filePath, 'utf8')) };
  } catch (_) {
    return { error: `${label}을 읽을 수 없습니다.` };
  }
}

function sha256(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function metricValues(summary, name) {
  const metric = summary?.metrics?.[name];
  if (!isObject(metric)) {
    return null;
  }
  const values = isObject(metric.values) ? metric.values : metric;
  return isObject(values) ? values : null;
}

function metricCount(summary, name) {
  const values = metricValues(summary, name);
  const count = values?.count;
  return Number.isSafeInteger(count) && count >= 0 ? count : null;
}

function isUtcTimestamp(value) {
  return UTC_TIMESTAMP_PATTERN.test(value || '') && !Number.isNaN(Date.parse(value));
}

function sameReadProfile(left, right) {
  return isDeepStrictEqual(left, right);
}

function invalidRun(run, message) {
  return {
    kind: 'INVALID',
    run,
    failure: `${run.repeatId}/${run.conditionKey}: ${message}`,
  };
}

function failedRun(run, message) {
  return {
    kind: 'FAIL',
    run,
    failure: `${run.repeatId}/${run.conditionKey}: ${message}`,
  };
}

function requireRunDirectory(buildRoot, run) {
  const directory = path.resolve(buildRoot, run.runId, run.fixtureId);
  const root = path.resolve(buildRoot);
  const relative = path.relative(root, directory);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    return { error: 'T5 fixture 경로가 build root 밖에 있습니다.' };
  }
  if (!existsSync(directory) || !lstatSync(directory).isDirectory()) {
    return { error: `T5 fixture directory가 없습니다: ${run.runId}/${run.fixtureId}` };
  }
  return { directory };
}

function validatePortableManifest(directory, run) {
  const result = readJson(path.join(directory, 'manifest.json'), 'portable manifest.json');
  if (result.error) {
    return result;
  }
  const manifest = result.value;
  if (!isObject(manifest)
    || manifest.schemaVersion !== 2
    || manifest.fixtureSchemaVersion !== 2
    || manifest.fixtureId !== run.fixtureId
    || !isDeepStrictEqual(manifest.options, run.options)
    || !SOURCE_SHA_PATTERN.test(manifest.sourceRevision || '')
    || manifest.sourceDirty !== false) {
    return { error: 'portable manifest.json이 계획된 T5 fixture와 맞지 않습니다.' };
  }
  return { value: manifest };
}

function validateFixturePlan(directory, run) {
  const result = readJson(path.join(directory, 'fixture-plan.json'), 'fixture-plan.json');
  if (result.error) {
    return result;
  }
  const plan = result.value;
  if (!isObject(plan)
    || plan.schemaVersion !== 2
    || plan.fixtureId !== run.fixtureId
    || !isDeepStrictEqual(plan.options, run.options)) {
    return { error: 'fixture-plan.json이 계획된 T5 repeat identity와 맞지 않습니다.' };
  }
  return { value: plan };
}

function validateExecutionOptions(directory, run) {
  const result = readJson(path.join(directory, 'execution-options.json'), 'execution-options.json');
  if (result.error) {
    return result;
  }
  if (!isObject(result.value)
    || !sameReadProfile(result.value.t5ReadOptions, run.readProfile)) {
    return { error: 'execution-options.json이 고정 T5 read profile과 다릅니다.' };
  }
  return { value: result.value };
}

function validateCompletionManifest(directory, run, portableManifest, summaryPath, fixturePath) {
  const result = readJson(path.join(directory, RUN_MANIFEST_FILE), RUN_MANIFEST_FILE);
  if (result.error) {
    return result;
  }
  const manifest = result.value;
  if (!isObject(manifest)
    || manifest.schemaVersion !== 2
    || manifest.fixtureId !== run.fixtureId
    || manifest.runId !== run.runId
    || manifest.scenario !== 't5'
    || manifest.runState !== 'COMPLETED'
    || manifest.completed !== true
    || !Number.isInteger(manifest.k6ExitCode)
    || !SOURCE_SHA_PATTERN.test(manifest.sourceSha || '')
    || manifest.sourceSha.toLowerCase() !== portableManifest.sourceRevision.toLowerCase()
    || !TARGET_ENVIRONMENT_PATTERN.test(manifest.targetEnvironment || '')
    || typeof manifest.k6Version !== 'string'
    || !manifest.k6Version
    || !isUtcTimestamp(manifest.startedAtUtc)
    || !isUtcTimestamp(manifest.finishedAtUtc)
    || Date.parse(manifest.finishedAtUtc) < Date.parse(manifest.startedAtUtc)
    || manifest.summaryFile !== 'k6-summary.json'
    || !SHA256_PATTERN.test(manifest.summarySha256 || '')
    || !SHA256_PATTERN.test(manifest.fixtureSha256 || '')
    || !sameReadProfile(manifest.t5ReadOptions, run.readProfile)) {
    return { error: `${RUN_MANIFEST_FILE}가 원격 T5 완료 계약과 맞지 않습니다.` };
  }
  if (sha256(summaryPath) !== manifest.summarySha256) {
    return { error: 'run-manifest.json의 k6-summary.json SHA-256이 실제 artifact와 다릅니다.' };
  }
  if (sha256(fixturePath) !== manifest.fixtureSha256) {
    return { error: 'run-manifest.json의 fixture.json SHA-256이 실제 artifact와 다릅니다.' };
  }
  if (manifest.k6ExitCode !== 0) {
    return { failure: `원본 k6 실행이 exit=${manifest.k6ExitCode}로 끝났습니다.` };
  }
  return { value: manifest };
}

function validateDiagnosis(directory, fileName, run, stage) {
  const result = readJson(path.join(directory, fileName), fileName);
  if (result.error) {
    return result;
  }
  const diagnosis = result.value;
  if (!isObject(diagnosis)
    || diagnosis.fixtureId !== run.fixtureId
    || diagnosis.scenario !== 't5'
    || diagnosis.stage !== stage
    || !['PASS', 'FAIL'].includes(diagnosis.status)
    || !Array.isArray(diagnosis.failures)) {
    return { error: `${fileName}가 PASS 완료 artifact가 아닙니다.` };
  }
  if (diagnosis.status === 'PASS' && diagnosis.failures.length !== 0) {
    return { error: `${fileName}가 PASS인데 failures가 비어 있지 않습니다.` };
  }
  if (diagnosis.status === 'FAIL') {
    return { failure: `${fileName}가 FAIL로 끝났습니다.` };
  }
  return { value: diagnosis };
}

function validateFinalResult(directory, run) {
  const result = readJson(path.join(directory, 'final-result.json'), 'final-result.json');
  if (result.error) {
    return result;
  }
  const finalResult = result.value;
  if (!isObject(finalResult)
    || finalResult.schemaVersion !== 1
    || finalResult.fixtureId !== run.fixtureId
    || finalResult.runId !== run.runId
    || finalResult.scenario !== 't5'
    || !['PASS', 'FAIL'].includes(finalResult.status)
    || !Array.isArray(finalResult.issues)) {
    return { error: 'final-result.json이 현재 T5 실행과 맞지 않습니다.' };
  }
  if (finalResult.status === 'PASS' && finalResult.issues.length !== 0) {
    return { error: 'final-result.json이 PASS인데 issues가 비어 있지 않습니다.' };
  }
  if (finalResult.status === 'FAIL') {
    return { failure: 'final-result.json이 FAIL로 끝났습니다.' };
  }
  return { value: finalResult };
}

function validateInfraExecution(directory, run) {
  const result = readJson(path.join(directory, 'infra-execution.json'), 'infra-execution.json');
  if (result.error) {
    return result;
  }
  const execution = result.value;
  const phaseNames = ['prepare', 'resourceQuery', 'beforeSnapshot', 'k6', 'afterSnapshot'];
  if (!isObject(execution)
    || execution.schemaVersion !== 1
    || execution.runId !== run.runId
    || execution.fixtureId !== run.fixtureId
    || !isObject(execution.phases)
    || !phaseNames.every((name) => Number.isInteger(execution.phases[name]?.exitCode))) {
    return { error: 'infra-execution.json에 누락되거나 완료되지 않은 phase가 있습니다.' };
  }
  if (phaseNames.some((name) => execution.phases[name].exitCode !== 0)) {
    return { failure: 'infra-execution.json에 실패한 phase가 있습니다.' };
  }
  return { value: execution };
}

function validateSummary(summary, run) {
  if (!isObject(summary) || !isObject(summary.metrics)) {
    return { error: 'k6-summary.json에 metrics가 없습니다.' };
  }

  const requestCount = metricCount(summary, 'room_requests');
  const successCount = metricCount(summary, SUCCESS_DURATION_METRIC);
  if (!Number.isSafeInteger(requestCount) || requestCount <= 0
    || !Number.isSafeInteger(successCount) || successCount <= 0
    || requestCount !== successCount) {
    return { error: 'T5 전체 요청 수와 성공 응답 count를 확인할 수 없습니다.' };
  }

  for (const metricName of OUTCOME_METRICS) {
    if (metricCount(summary, metricName) === null) {
      return { error: `${metricName} count가 없습니다.` };
    }
  }
  const outcomeCount = OUTCOME_METRICS.reduce((total, metricName) => total + metricCount(summary, metricName), 0);
  if (outcomeCount !== requestCount) {
    return { error: 'outcome별 count 합이 room_requests와 다릅니다.' };
  }

  const counterPairs = [
    ['room_success', 'room_request_duration{outcome:success}'],
    ['room_business_failures', 'room_request_duration{outcome:business}'],
    ['room_concurrent_failures', 'room_request_duration{outcome:concurrency}'],
  ];
  for (const [counterName, durationMetricName] of counterPairs) {
    const counter = metricCount(summary, counterName);
    const durationCount = metricCount(summary, durationMetricName);
    if (counter === null) {
      return { error: `${counterName} count가 없습니다.` };
    }
    if (counter !== durationCount) {
      return { failure: `${counterName}과 ${durationMetricName} count가 다릅니다.` };
    }
  }
  for (const counterName of ['room_contract_failures', 'room_unexpected_4xx', 'room_server_failures']) {
    const counter = metricCount(summary, counterName);
    if (counter === null) {
      return { error: `${counterName} count가 없습니다.` };
    }
    if (counter !== 0) {
      return { failure: `${counterName}가 ${counter}건 관측되었습니다.` };
    }
  }

  const startSkewCount = metricCount(summary, 'room_start_skew_ms');
  if (startSkewCount !== run.readProfile.vus) {
    return { error: `room_start_skew_ms 관측 수 ${startSkewCount}가 VU 수 ${run.readProfile.vus}와 다릅니다.` };
  }

  const successValues = metricValues(summary, SUCCESS_DURATION_METRIC);
  const latency = {};
  const successP50 = successValues?.p50 ?? successValues?.med;
  const successP95 = successValues?.p95 ?? successValues?.['p(95)'];
  const successP99 = successValues?.p99 ?? successValues?.['p(99)'];
  const successMax = successValues?.max;
  const successStatistics = { p50: successP50, p95: successP95, p99: successP99, max: successMax };
  for (const name of ['p50', 'p95', 'p99', 'max']) {
    if (!Number.isFinite(successStatistics[name])) {
      return { error: `성공 응답 ${name}가 없어 T5 비교를 할 수 없습니다.` };
    }
    latency[name] = successStatistics[name];
  }

  const rpsValues = metricValues(summary, 'http_reqs');
  const rps = rpsValues?.rate;
  if (!Number.isFinite(rps) || rps <= 0) {
    return { error: 'http_reqs rate가 없어 성공 응답 RPS를 연결할 수 없습니다.' };
  }

  return {
    value: {
      count: successCount,
      successCount,
      p50: latency.p50,
      p95: latency.p95,
      p99: latency.p99,
      max: latency.max,
      rps,
      readProfile: { ...run.readProfile },
    },
  };
}

function numericSignalGroup(value, allowedFields) {
  if (!isObject(value)) {
    return null;
  }
  const entries = Object.entries(value)
    .filter(([name]) => allowedFields.includes(name));
  if (entries.length === 0 || entries.some(([, signal]) => !Number.isFinite(signal) || signal < 0)) {
    return null;
  }
  return Object.fromEntries(entries);
}

function validateResourceSignals(directory, run, completionManifest) {
  const result = readJson(path.join(directory, RESOURCE_SIGNALS_FILE), RESOURCE_SIGNALS_FILE);
  if (result.error) {
    return result;
  }
  const signals = result.value;
  if (!isObject(signals)
    || signals.schemaVersion !== 1
    || signals.runId !== run.runId
    || signals.fixtureId !== run.fixtureId
    || !isObject(signals.window)
    || signals.window.startedAtUtc !== completionManifest.startedAtUtc
    || signals.window.finishedAtUtc !== completionManifest.finishedAtUtc) {
    return { error: `${RESOURCE_SIGNALS_FILE}가 T5 completion window와 연결되지 않았습니다.` };
  }

  const normalized = {
    schemaVersion: 1,
    runId: run.runId,
    fixtureId: run.fixtureId,
    window: { ...signals.window },
  };
  for (const group of RESOURCE_GROUPS) {
    const values = numericSignalGroup(signals[group], RESOURCE_GROUP_FIELDS[group]);
    if (!values || RESOURCE_REQUIRED_FIELDS[group].some((field) => !Object.hasOwn(values, field))) {
      return { error: `${RESOURCE_SIGNALS_FILE}에 ${group} 자원 신호가 없습니다.` };
    }
    normalized[group] = values;
  }

  const querySignals = {};
  for (const field of QUERY_SIGNAL_FIELDS) {
    const value = signals.query?.[field];
    if (!Number.isFinite(value) || value < 0) {
      return { error: `${RESOURCE_SIGNALS_FILE}에 query ${field} 신호가 없습니다.` };
    }
    querySignals[field] = value;
  }
  normalized.query = querySignals;
  return { value: normalized };
}

function readT5RunArtifact(buildRoot, run) {
  const directoryResult = requireRunDirectory(buildRoot, run);
  if (directoryResult.error) {
    return invalidRun(run, directoryResult.error);
  }
  const directory = directoryResult.directory;
  const portableManifest = validatePortableManifest(directory, run);
  if (portableManifest.error) {
    return invalidRun(run, portableManifest.error);
  }
  const fixturePlan = validateFixturePlan(directory, run);
  if (fixturePlan.error) {
    return invalidRun(run, fixturePlan.error);
  }
  const executionOptions = validateExecutionOptions(directory, run);
  if (executionOptions.error) {
    return invalidRun(run, executionOptions.error);
  }

  const summaryPath = path.join(directory, 'k6-summary.json');
  const fixturePath = path.join(directory, 'fixture.json');
  const summaryResult = readJson(summaryPath, 'k6-summary.json');
  if (summaryResult.error) {
    return invalidRun(run, summaryResult.error);
  }
  if (!existsSync(fixturePath)) {
    return invalidRun(run, 'fixture.json이 없습니다.');
  }

  const completion = validateCompletionManifest(
    directory,
    run,
    portableManifest.value,
    summaryPath,
    fixturePath,
  );
  if (completion.error) {
    return invalidRun(run, completion.error);
  }
  if (completion.failure) {
    return failedRun(run, completion.failure);
  }
  if (!sameReadProfile(executionOptions.value.t5ReadOptions, completion.value.t5ReadOptions)) {
    return invalidRun(run, 'execution-options.json과 run-manifest.json의 read profile이 다릅니다.');
  }

  const beforeDiagnosis = validateDiagnosis(directory, 'before-diagnosis.json', run, 'before');
  if (beforeDiagnosis.error) {
    return invalidRun(run, beforeDiagnosis.error);
  }
  if (beforeDiagnosis.failure) {
    return failedRun(run, beforeDiagnosis.failure);
  }
  const afterDiagnosis = validateDiagnosis(directory, 'after-diagnosis.json', run, 'after');
  if (afterDiagnosis.error) {
    return invalidRun(run, afterDiagnosis.error);
  }
  if (afterDiagnosis.failure) {
    return failedRun(run, afterDiagnosis.failure);
  }
  const finalResult = validateFinalResult(directory, run);
  if (finalResult.error) {
    return invalidRun(run, finalResult.error);
  }
  if (finalResult.failure) {
    return failedRun(run, finalResult.failure);
  }
  const infraExecution = validateInfraExecution(directory, run);
  if (infraExecution.error) {
    return invalidRun(run, infraExecution.error);
  }
  if (infraExecution.failure) {
    return failedRun(run, infraExecution.failure);
  }

  const summary = validateSummary(summaryResult.value, run);
  if (summary.error) {
    return invalidRun(run, summary.error);
  }
  if (summary.failure) {
    return failedRun(run, summary.failure);
  }
  const resourceSignals = validateResourceSignals(directory, run, completion.value);
  if (resourceSignals.error) {
    return invalidRun(run, resourceSignals.error);
  }

  return {
    kind: 'PASS',
    run,
    directory,
    window: {
      startedAtUtc: completion.value.startedAtUtc,
      finishedAtUtc: completion.value.finishedAtUtc,
    },
    provenance: {
      sourceSha: completion.value.sourceSha.toLowerCase(),
      deployedRelease: portableManifest.value.sourceRevision.toLowerCase(),
      targetEnvironment: completion.value.targetEnvironment,
      k6Version: completion.value.k6Version,
    },
    metrics: summary.value,
    resourceSignals: resourceSignals.value,
    artifactSha256: {
      summary: sha256(summaryPath),
      completion: sha256(path.join(directory, RUN_MANIFEST_FILE)),
      resourceSignals: sha256(path.join(directory, RESOURCE_SIGNALS_FILE)),
    },
  };
}

function comparisonOutputPath(buildRoot, campaignId) {
  return path.join(buildRoot, 't5-campaign', campaignId, COMPARISON_FILE);
}

const CAMPAIGN_PROVENANCE_FIELDS = [
  'sourceSha',
  'deployedRelease',
  'targetEnvironment',
  'k6Version',
];

function provenanceDifferences(expected, actual) {
  return CAMPAIGN_PROVENANCE_FIELDS.filter((field) => expected[field] !== actual[field]);
}

export function compareT5RepetitionCampaign({ campaignId, buildRoot = defaultBuildRoot, outputPath } = {}) {
  const plan = createT5RepetitionPlan(campaignId);
  const failures = [];
  const invalidArtifact = [];
  const failedArtifact = [];
  const runsByCondition = new Map();

  for (const run of plan.runs) {
    const result = readT5RunArtifact(buildRoot, run);
    if (!runsByCondition.has(run.conditionKey)) {
      runsByCondition.set(run.conditionKey, []);
    }
    runsByCondition.get(run.conditionKey).push(result);
    if (result.kind === 'INVALID') {
      invalidArtifact.push(result);
      failures.push(result.failure);
    } else if (result.kind === 'FAIL') {
      failedArtifact.push(result);
      failures.push(result.failure);
    }
  }

  const allResults = [...runsByCondition.values()].flat();
  const passingResults = allResults.filter((result) => result.kind === 'PASS');
  const expectedProvenance = passingResults[0]?.provenance || null;
  if (expectedProvenance) {
    for (const result of passingResults.slice(1)) {
      const differences = provenanceDifferences(expectedProvenance, result.provenance);
      if (differences.length === 0) {
        continue;
      }
      result.kind = 'INVALID';
      result.failure = `${result.run.repeatId}/${result.run.conditionKey}: T5 campaign provenance 불일치 (${differences.join(', ')})`;
      invalidArtifact.push(result);
      failures.push(result.failure);
    }
  }

  const conditions = [];
  let acceptedCount = 0;
  let acceptedRunCount = 0;
  for (const conditionKey of [...runsByCondition.keys()]) {
    const runResults = runsByCondition.get(conditionKey);
    const accepted = runResults.length === 3 && runResults.every((result) => result.kind === 'PASS');
    if (accepted) {
      acceptedCount += 1;
      acceptedRunCount += runResults.length;
    }
    conditions.push({
      conditionKey,
      accepted,
      repeatCount: runResults.length,
      runs: runResults.map((result) => ({
        repeat: result.run.repeat,
        repeatId: result.run.repeatId,
        runId: result.run.runId,
        fixtureId: result.run.fixtureId,
        status: result.kind,
        provenance: result.provenance || null,
        window: result.window || null,
        metrics: result.metrics || null,
        resourceSignals: result.resourceSignals || null,
        artifactSha256: result.artifactSha256 || null,
        failure: result.failure || null,
      })),
    });
  }

  let status = 'INVALID';
  if (invalidArtifact.length === 0 && failedArtifact.length > 0) {
    status = 'FAIL';
  }
  if (invalidArtifact.length === 0
    && failedArtifact.length === 0
    && acceptedCount === plan.conditionCount
    && acceptedRunCount === plan.runCount) {
    status = 'PASS';
  }
  const result = {
    schemaVersion: 1,
    campaignId: plan.campaignId,
    scenario: plan.scenario,
    status,
    readProfile: plan.readProfile,
    repeatCount: plan.repeatCount,
    expectedRunCount: plan.runCount,
    runCount: plan.runs.length,
    conditionCount: plan.conditionCount,
    acceptedCount,
    acceptedRunCount,
    failures,
    provenance: expectedProvenance,
    conditions,
  };
  const targetPath = outputPath || comparisonOutputPath(buildRoot, plan.campaignId);
  mkdirSync(path.dirname(targetPath), { recursive: true });
  writeFileSync(targetPath, `${JSON.stringify(result, null, 2)}\n`, 'utf8');
  return { ...result, outputPath: targetPath };
}

function parseArguments(argv) {
  const [command, ...rest] = argv;
  const values = {};
  for (let index = 0; index < rest.length; index += 1) {
    const option = rest[index];
    if (!option.startsWith('--')) {
      fail(`알 수 없는 인수: ${option}`);
    }
    const name = option.slice(2);
    const value = rest[index + 1];
    if (!value || value.startsWith('--')) {
      fail(`${option} 값이 필요합니다.`);
    }
    values[name] = value;
    index += 1;
  }
  return { command, values };
}

function usage() {
  return `사용법:
  node load-tests/k6/jiwon/tools/t5-repetition.mjs plan --campaign-id <campaign-id>
  node load-tests/k6/jiwon/tools/t5-repetition.mjs compare --campaign-id <campaign-id>
`;
}

function main() {
  const { command, values } = parseArguments(process.argv.slice(2));
  if (!command || command === 'help' || command === '--help' || command === '-h') {
    process.stdout.write(usage());
    return;
  }
  if (command === 'plan') {
    process.stdout.write(`${JSON.stringify(createT5RepetitionPlan(values['campaign-id']), null, 2)}\n`);
    return;
  }
  if (command === 'compare') {
    const result = compareT5RepetitionCampaign({ campaignId: values['campaign-id'] });
    process.stdout.write(`${JSON.stringify(result)}\n`);
    if (result.status === 'INVALID') {
      process.exitCode = 2;
    } else if (result.status === 'FAIL') {
      process.exitCode = 1;
    }
    return;
  }
  fail(`지원하지 않는 command입니다: ${command}`);
}

if (process.argv[1] && path.resolve(process.argv[1]) === path.resolve(fileURLToPath(import.meta.url))) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 2;
  }
}

export {
  COMPARISON_FILE,
  RESOURCE_SIGNALS_FILE,
  RUN_MANIFEST_FILE,
  defaultBuildRoot,
  readT5RunArtifact,
};
