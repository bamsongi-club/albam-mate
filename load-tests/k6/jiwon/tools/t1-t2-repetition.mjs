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

import {
  createT1T2RepetitionPlan,
  validateT1T2RepetitionPlan,
} from '../lib/t1-t2-repetition-plan.mjs';
import { validateBundle } from './portable-bundle.mjs';

const toolDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(toolDirectory, '../../../..');
const defaultBuildRoot = path.join(repositoryRoot, 'build', 'k6', 'room');
const COMPARISON_FILE = 't1-t2-comparison-verification.json';
const RUN_MANIFEST_FILE = 'run-manifest.json';
const RESOURCE_SIGNALS_FILE = 'resource-signals.json';
const SOURCE_SHA_PATTERN = /^[0-9a-f]{40}$/i;
const TARGET_ENVIRONMENT_PATTERN = /^[a-z0-9][a-z0-9._-]{0,79}$/;
const SHA256_PATTERN = /^[0-9a-f]{64}$/i;
const OUTCOME_CATEGORIES = ['success', 'business', 'concurrency', 'unexpected'];
const PHASE_NAMES = ['prepare', 'resourceQuery', 'beforeSnapshot', 'k6', 'afterSnapshot'];
const START_SKEW_MAX_MILLISECONDS = 1000;
const RESOURCE_GROUPS = ['http', 'tomcat', 'hikari', 'jvm', 'postgresql'];
const RESOURCE_GROUP_FIELDS = Object.freeze({
  http: ['requestCount', 'failedRequestCount', 'rps'],
  tomcat: ['activeThreads', 'busyThreads', 'maxThreads'],
  hikari: ['activeConnections', 'idleConnections', 'pendingThreads', 'maxPoolSize'],
  jvm: ['heapUsedBytes', 'heapMaxBytes', 'cpuPercent'],
  postgresql: [
    'cpuPercent',
    'activeConnections',
    'lockWaitCount',
    'transactionDurationMilliseconds',
  ],
});
const RESOURCE_REQUIRED_FIELDS = Object.freeze({
  http: ['requestCount', 'failedRequestCount'],
  tomcat: ['activeThreads'],
  hikari: ['activeConnections', 'pendingThreads'],
  jvm: ['heapUsedBytes'],
  postgresql: [
    'activeConnections',
    'lockWaitCount',
    'transactionDurationMilliseconds',
  ],
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

function isNonNegativeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0;
}

function isNonNegativeNumber(value) {
  return Number.isFinite(value) && value >= 0;
}

function readRegularFile(filePath, label) {
  if (!existsSync(filePath)) {
    return { error: `${label}이 없습니다.` };
  }
  try {
    const stat = lstatSync(filePath);
    if (!stat.isFile() || stat.isSymbolicLink()) {
      return { error: `${label}은 symbolic link가 아닌 일반 파일이어야 합니다.` };
    }
    return { value: readFileSync(filePath) };
  } catch (_) {
    return { error: `${label}을 읽을 수 없습니다.` };
  }
}

function readJson(filePath, label) {
  const result = readRegularFile(filePath, label);
  if (result.error) {
    return result;
  }
  try {
    return { value: JSON.parse(result.value.toString('utf8')) };
  } catch (_) {
    return { error: `${label} JSON을 읽을 수 없습니다.` };
  }
}

function sha256(filePath, label) {
  const result = readRegularFile(filePath, label);
  if (result.error) {
    return { error: result.error };
  }
  return { value: createHash('sha256').update(result.value).digest('hex') };
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
  return isNonNegativeInteger(values?.count) ? values.count : null;
}

function readDurationStatistic(values, canonicalName, aliases) {
  const candidateNames = [canonicalName, ...aliases];
  for (const candidateName of candidateNames) {
    if (Object.hasOwn(values, candidateName)) {
      return values[candidateName];
    }
  }
  return undefined;
}

function isUtcTimestamp(value) {
  return typeof value === 'string'
    && value.endsWith('Z')
    && !Number.isNaN(Date.parse(value));
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
  const root = path.resolve(buildRoot);
  const directory = path.resolve(root, run.runId, run.fixtureId);
  const relative = path.relative(root, directory);
  if (relative.startsWith('..') || path.isAbsolute(relative)) {
    return { error: 'T1/T2 fixture 경로가 build root 밖에 있습니다.' };
  }
  try {
    const stat = lstatSync(directory);
    if (!stat.isDirectory() || stat.isSymbolicLink()) {
      return { error: 'T1/T2 fixture directory는 symbolic link가 아닌 디렉터리여야 합니다.' };
    }
  } catch (_) {
    return { error: `T1/T2 fixture directory가 없습니다: ${run.runId}/${run.fixtureId}` };
  }
  return { directory };
}

function validatePortableManifest(directory, run, buildRoot) {
  const result = readJson(path.join(directory, 'manifest.json'), 'portable manifest.json');
  if (result.error) {
    return result;
  }
  const manifest = result.value;
  if (!isObject(manifest)
    || manifest.kind !== 'albam-mate-room-k6-bundle'
    || manifest.schemaVersion !== 2
    || manifest.fixtureSchemaVersion !== 2
    || manifest.fixtureId !== run.fixtureId
    || !isDeepStrictEqual(manifest.options, run.options)
    || manifest.sourceRevision !== run.sourceSha
    || manifest.sourceDirty !== false
    || !isObject(manifest.artifacts)
    || manifest.artifacts.summary !== 'k6-summary.json'
    || manifest.artifacts.finalResult !== 'final-result.json') {
    return { error: 'portable manifest.json이 계획된 T1/T2 fixture와 맞지 않습니다.' };
  }

  try {
    validateBundle(directory, {
      repositoryRoot,
      buildRoot,
      isBundleRuntime: false,
      bundleRoot: null,
    });
  } catch (error) {
    return { error: `portable bundle 검증 실패: ${error.message}` };
  }

  return { value: manifest };
}

function validateFixture(directory, run) {
  const result = readJson(path.join(directory, 'fixture.json'), 'fixture.json');
  if (result.error) {
    return result;
  }
  const fixture = result.value;
  if (!isObject(fixture)
    || fixture.schemaVersion !== 2
    || fixture.fixtureId !== run.fixtureId
    || !isDeepStrictEqual(fixture.options, run.options)) {
    return { error: 'fixture.json이 계획된 T1/T2 fixture identity와 맞지 않습니다.' };
  }
  return { value: fixture };
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
    return { error: 'fixture-plan.json이 계획된 T1/T2 repeat identity와 맞지 않습니다.' };
  }
  return { value: plan };
}

function validateExecutionOptions(directory, run) {
  const result = readJson(path.join(directory, 'execution-options.json'), 'execution-options.json');
  if (result.error) {
    return result;
  }
  const options = result.value;
  if (!isObject(options)
    || options.schemaVersion !== 1
    || !isObject(options.k6Environment)
    || !isDeepStrictEqual(options.k6Environment, run.writeExecutionProfile)
    || !sameReadProfile(options.t5ReadOptions, run.readProfile)) {
    return { error: 'execution-options.json이 고정 T1/T2 write·read profile과 다릅니다.' };
  }
  return { value: options };
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
    || manifest.scenario !== run.options.scenario
    || !isDeepStrictEqual(manifest.condition, run.options)
    || manifest.runState !== 'COMPLETED'
    || manifest.completed !== true
    || !Number.isInteger(manifest.k6ExitCode)
    || manifest.sourceSha !== run.sourceSha
    || manifest.sourceSha !== portableManifest.sourceRevision
    || manifest.targetEnvironment !== run.targetEnvironment
    || !SOURCE_SHA_PATTERN.test(manifest.sourceSha || '')
    || !TARGET_ENVIRONMENT_PATTERN.test(manifest.targetEnvironment || '')
    || typeof manifest.k6Version !== 'string'
    || !manifest.k6Version.trim()
    || !isUtcTimestamp(manifest.startedAtUtc)
    || !isUtcTimestamp(manifest.finishedAtUtc)
    || Date.parse(manifest.finishedAtUtc) < Date.parse(manifest.startedAtUtc)
    || manifest.summaryFile !== 'k6-summary.json'
    || !SHA256_PATTERN.test(manifest.summarySha256 || '')
    || !SHA256_PATTERN.test(manifest.fixtureSha256 || '')
    || !(manifest.t5ReadOptions === undefined || manifest.t5ReadOptions === null)) {
    return { error: `${RUN_MANIFEST_FILE}가 원격 T1/T2 완료 provenance 계약과 맞지 않습니다.` };
  }

  const summaryDigest = sha256(summaryPath, 'k6-summary.json');
  if (summaryDigest.error || summaryDigest.value !== manifest.summarySha256) {
    return { error: 'run-manifest.json의 k6-summary.json SHA-256이 실제 artifact와 다릅니다.' };
  }
  const fixtureDigest = sha256(fixturePath, 'fixture.json');
  if (fixtureDigest.error || fixtureDigest.value !== manifest.fixtureSha256) {
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
    || diagnosis.scenario !== run.options.scenario
    || diagnosis.stage !== stage
    || !['PASS', 'FAIL', 'INVALID'].includes(diagnosis.status)
    || !Array.isArray(diagnosis.failures)) {
    return { error: `${fileName}가 현재 T1/T2 diagnosis 계약과 맞지 않습니다.` };
  }
  if (diagnosis.status === 'INVALID') {
    return { error: `${fileName}가 INVALID로 끝났습니다.` };
  }
  if (diagnosis.status === 'FAIL') {
    return { failure: `${fileName}가 ${diagnosis.status}로 끝났습니다.` };
  }
  if (diagnosis.failures.length !== 0) {
    return { error: `${fileName}가 PASS인데 failures가 비어 있지 않습니다.` };
  }
  return { value: diagnosis };
}

function validateInfraExecution(directory, run) {
  const result = readJson(path.join(directory, 'infra-execution.json'), 'infra-execution.json');
  if (result.error) {
    return result;
  }
  const execution = result.value;
  if (!isObject(execution)
    || execution.schemaVersion !== 1
    || execution.runId !== run.runId
    || execution.fixtureId !== run.fixtureId
    || !isObject(execution.phases)
    || !PHASE_NAMES.every((name) => Number.isInteger(execution.phases[name]?.exitCode))) {
    return { error: 'infra-execution.json에 누락되거나 완료되지 않은 phase가 있습니다.' };
  }
  if (PHASE_NAMES.some((name) => execution.phases[name].exitCode !== 0)) {
    return { failure: 'infra-execution.json에 실패한 phase가 있습니다.' };
  }
  return { value: execution };
}

function validateFinalResult(directory, run, beforeDiagnosis, afterDiagnosis, infraExecution, completionManifest) {
  const result = readJson(path.join(directory, 'final-result.json'), 'final-result.json');
  if (result.error) {
    return result;
  }
  const finalResult = result.value;
  if (!isObject(finalResult)
    || finalResult.schemaVersion !== 1
    || finalResult.fixtureId !== run.fixtureId
    || finalResult.runId !== run.runId
    || finalResult.scenario !== run.options.scenario
    || !['PASS', 'FAIL', 'INVALID'].includes(finalResult.status)
    || !Array.isArray(finalResult.issues)) {
    return { error: 'final-result.json이 현재 T1/T2 실행과 맞지 않습니다.' };
  }
  if (finalResult.status === 'INVALID') {
    return { error: 'final-result.json이 INVALID로 끝났습니다.' };
  }
  if (finalResult.status === 'FAIL') {
    return { failure: `final-result.json이 ${finalResult.status}로 끝났습니다.` };
  }
  if (finalResult.issues.length !== 0) {
    return { error: 'final-result.json이 PASS인데 issues가 비어 있지 않습니다.' };
  }
  if (!isDeepStrictEqual(finalResult.beforeDiagnosis, beforeDiagnosis)
    || !isDeepStrictEqual(finalResult.afterDiagnosis, afterDiagnosis)
    || !isDeepStrictEqual(finalResult.infraExecution, infraExecution)
    || !isDeepStrictEqual(finalResult.runManifest, completionManifest)) {
    return { error: 'final-result.json이 원본 diagnosis·phase·manifest를 보존하지 않습니다.' };
  }
  return { value: finalResult };
}

function validateDurationMetric(summary, metricName) {
  const values = metricValues(summary, metricName);
  if (!values || !isNonNegativeInteger(values.count)) {
    return { error: `${metricName} count가 없습니다.` };
  }
  const normalizedValues = {
    count: values.count,
    p50: readDurationStatistic(values, 'p50', ['med']),
    p95: readDurationStatistic(values, 'p95', ['p(95)']),
    p99: readDurationStatistic(values, 'p99', ['p(99)']),
    max: readDurationStatistic(values, 'max', []),
  };
  const statisticNames = ['p50', 'p95', 'p99', 'max'];
  if (values.count === 0) {
    for (const name of statisticNames) {
      if (normalizedValues[name] === 0) {
        normalizedValues[name] = null;
      }
    }
    if (!statisticNames.every((name) => normalizedValues[name] === null)) {
      return { error: `${metricName} 무표본 통계는 null이어야 합니다.` };
    }
  } else if (!statisticNames.every((name) => isNonNegativeNumber(normalizedValues[name]))) {
    return { error: `${metricName} count가 있는데 p50·p95·p99·max가 모두 없습니다.` };
  }
  return {
    value: {
      count: normalizedValues.count,
      p50: normalizedValues.p50,
      p95: normalizedValues.p95,
      p99: normalizedValues.p99,
      max: normalizedValues.max,
    },
  };
}

export function validateSummary(summary, run) {
  if (!isObject(summary) || !isObject(summary.metrics)) {
    return { error: 'k6-summary.json에 metrics가 없습니다.' };
  }

  const requestCount = metricCount(summary, 'room_requests');
  if (!isNonNegativeInteger(requestCount) || requestCount <= 0) {
    return { error: 'room_requests count가 없습니다.' };
  }

  const outcomes = {};
  for (const category of OUTCOME_CATEGORIES) {
    const metricName = `room_request_duration{outcome:${category}}`;
    const metric = validateDurationMetric(summary, metricName);
    if (metric.error) {
      return metric;
    }
    outcomes[category] = metric.value;
  }
  const outcomeCount = Object.values(outcomes).reduce((total, metric) => total + metric.count, 0);
  if (outcomeCount !== requestCount) {
    return { error: 'outcome별 count 합이 room_requests와 다릅니다.' };
  }

  for (const counterName of ['room_contract_failures', 'room_unexpected_4xx', 'room_server_failures']) {
    const count = metricCount(summary, counterName);
    if (!isNonNegativeInteger(count)) {
      return { error: `${counterName} count가 없습니다.` };
    }
    if (count !== 0) {
      return { failure: `${counterName}가 ${count}건 관측되었습니다.` };
    }
  }

  const expectedStartSkewCount = run.options.concurrency * run.options.rounds;
  const startSkewValues = metricValues(summary, 'room_start_skew_ms');
  if (!startSkewValues
    || startSkewValues.count !== expectedStartSkewCount
    || !isNonNegativeNumber(startSkewValues.max)) {
    return { error: `room_start_skew_ms 관측 수가 ${expectedStartSkewCount}건이 아니거나 max가 없습니다.` };
  }
  if (startSkewValues.max >= START_SKEW_MAX_MILLISECONDS) {
    return { failure: `room_start_skew_ms max가 ${START_SKEW_MAX_MILLISECONDS}ms 이상입니다.` };
  }

  return {
    value: {
      requestCount,
      outcomes,
      startSkew: {
        count: startSkewValues.count,
        max: startSkewValues.max,
      },
    },
  };
}

function numericSignalGroup(value, allowedFields) {
  if (!isObject(value)) {
    return null;
  }
  const entries = Object.entries(value).filter(([name]) => allowedFields.includes(name));
  if (entries.length === 0 || entries.some(([, signal]) => !isNonNegativeNumber(signal))) {
    return null;
  }
  return Object.fromEntries(entries);
}

function validateRetryDistribution(value, label) {
  if (!isObject(value)
    || !isObject(value.attempts)
    || !isNonNegativeInteger(value.retries)
    || !isNonNegativeInteger(value.exhausted)) {
    return { error: `${label} retry distribution이 없습니다.` };
  }
  for (const [attempt, count] of Object.entries(value.attempts)) {
    if (!/^\d+$/.test(attempt) || !isNonNegativeInteger(count)) {
      return { error: `${label} attempt distribution이 올바르지 않습니다.` };
    }
  }
  return {
    value: {
      attempts: { ...value.attempts },
      retries: value.retries,
      exhausted: value.exhausted,
    },
  };
}

export function validateResourceSignals(signals, run, completionManifest, summaryResult) {
  if (!isObject(signals)
    || signals.schemaVersion !== 1
    || signals.status !== 'PASS'
    || signals.runId !== run.runId
    || signals.fixtureId !== run.fixtureId
    || signals.scenario !== run.options.scenario
    || signals.sourceSha !== run.sourceSha
    || signals.targetEnvironment !== run.targetEnvironment
    || !isDeepStrictEqual(signals.condition, run.options)
    || !isObject(signals.window)
    || signals.window.startedAtUtc !== completionManifest.startedAtUtc
    || signals.window.finishedAtUtc !== completionManifest.finishedAtUtc) {
    return { error: `${RESOURCE_SIGNALS_FILE}가 계획된 run·condition·source·UTC window와 맞지 않습니다.` };
  }

  const expectedOutcomeCoverage = Object.fromEntries(
    OUTCOME_CATEGORIES.map((category) => [category, summaryResult.outcomes[category]]),
  );
  if (!isDeepStrictEqual(signals.outcomeCoverage, expectedOutcomeCoverage)) {
    return { error: `${RESOURCE_SIGNALS_FILE}의 outcome coverage가 k6 summary와 다릅니다.` };
  }

  const normalized = {
    schemaVersion: 1,
    status: 'PASS',
    runId: run.runId,
    fixtureId: run.fixtureId,
    scenario: run.options.scenario,
    sourceSha: run.sourceSha,
    targetEnvironment: run.targetEnvironment,
    condition: run.options,
    window: { ...signals.window },
    outcomeCoverage: expectedOutcomeCoverage,
  };
  for (const group of RESOURCE_GROUPS) {
    const values = numericSignalGroup(signals[group], RESOURCE_GROUP_FIELDS[group]);
    if (!values || RESOURCE_REQUIRED_FIELDS[group].some((field) => !Object.hasOwn(values, field))) {
      return { error: `${RESOURCE_SIGNALS_FILE}에 ${group} 자원 신호가 없습니다.` };
    }
    normalized[group] = values;
  }

  const query = {};
  for (const field of QUERY_SIGNAL_FIELDS) {
    const value = signals.query?.[field];
    if (!isNonNegativeNumber(value)) {
      return { error: `${RESOURCE_SIGNALS_FILE}에 query ${field} 신호가 없습니다.` };
    }
    query[field] = value;
  }
  normalized.query = query;

  const commonRetrier = validateRetryDistribution(signals.retry?.commonRetrier, 'commonRetrier');
  const coordinator = validateRetryDistribution(signals.retry?.coordinator, 'coordinator');
  if (commonRetrier.error || coordinator.error || !isNonNegativeInteger(signals.observedStructuredRetryEvents)) {
    return { error: `${RESOURCE_SIGNALS_FILE}에 commonRetrier/coordinator retry 분포가 없습니다.` };
  }
  const expectedRetryEvents = commonRetrier.value.retries
    + commonRetrier.value.exhausted
    + coordinator.value.retries
    + coordinator.value.exhausted;
  if (signals.observedStructuredRetryEvents !== expectedRetryEvents) {
    return { error: `${RESOURCE_SIGNALS_FILE}의 retry event count가 분포와 다릅니다.` };
  }
  normalized.retry = {
    commonRetrier: commonRetrier.value,
    coordinator: coordinator.value,
  };
  normalized.observedStructuredRetryEvents = signals.observedStructuredRetryEvents;

  if (!isNonNegativeNumber(normalized.http.requestCount)
    || normalized.http.requestCount !== summaryResult.requestCount) {
    return { error: `${RESOURCE_SIGNALS_FILE}의 HTTP requestCount가 k6 요청 수와 다릅니다.` };
  }
  return { value: normalized };
}

function readT1T2RunArtifact(buildRoot, run) {
  const directoryResult = requireRunDirectory(buildRoot, run);
  if (directoryResult.error) {
    return invalidRun(run, directoryResult.error);
  }
  const directory = directoryResult.directory;
  const portableManifest = validatePortableManifest(directory, run, buildRoot);
  if (portableManifest.error) {
    return invalidRun(run, portableManifest.error);
  }
  const fixture = validateFixture(directory, run);
  if (fixture.error) {
    return invalidRun(run, fixture.error);
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
  const infraExecution = validateInfraExecution(directory, run);
  if (infraExecution.error) {
    return invalidRun(run, infraExecution.error);
  }
  if (infraExecution.failure) {
    return failedRun(run, infraExecution.failure);
  }
  const finalResult = validateFinalResult(
    directory,
    run,
    beforeDiagnosis.value,
    afterDiagnosis.value,
    infraExecution.value,
    completion.value,
  );
  if (finalResult.error) {
    return invalidRun(run, finalResult.error);
  }
  if (finalResult.failure) {
    return failedRun(run, finalResult.failure);
  }

  const summary = validateSummary(summaryResult.value, run);
  if (summary.error) {
    return invalidRun(run, summary.error);
  }
  if (summary.failure) {
    return failedRun(run, summary.failure);
  }

  const resourceResult = readJson(path.join(directory, RESOURCE_SIGNALS_FILE), RESOURCE_SIGNALS_FILE);
  if (resourceResult.error) {
    return invalidRun(run, resourceResult.error);
  }
  const resourceSignals = validateResourceSignals(
    resourceResult.value,
    run,
    completion.value,
    summary.value,
  );
  if (resourceSignals.error) {
    return invalidRun(run, resourceSignals.error);
  }

  const artifactSha256 = {};
  const artifactPaths = {
    portableManifest: path.join(directory, 'manifest.json'),
    fixturePlan: path.join(directory, 'fixture-plan.json'),
    executionOptions: path.join(directory, 'execution-options.json'),
    fixture: fixturePath,
    summary: summaryPath,
    runManifest: path.join(directory, RUN_MANIFEST_FILE),
    beforeDiagnosis: path.join(directory, 'before-diagnosis.json'),
    afterDiagnosis: path.join(directory, 'after-diagnosis.json'),
    infraExecution: path.join(directory, 'infra-execution.json'),
    finalResult: path.join(directory, 'final-result.json'),
    resourceSignals: path.join(directory, RESOURCE_SIGNALS_FILE),
  };
  for (const [name, filePath] of Object.entries(artifactPaths)) {
    const digest = sha256(filePath, name);
    if (digest.error) {
      return invalidRun(run, digest.error);
    }
    artifactSha256[name] = digest.value;
  }

  return {
    kind: 'PASS',
    run,
    directory,
    window: {
      startedAtUtc: completion.value.startedAtUtc,
      finishedAtUtc: completion.value.finishedAtUtc,
    },
    metrics: summary.value,
    resourceSignals: resourceSignals.value,
    artifactSha256,
  };
}

function comparisonOutputPath(buildRoot, campaignId) {
  return path.join(buildRoot, 't1-t2-campaign', campaignId, COMPARISON_FILE);
}

function assertOutputOutsideRunDirectories(outputPath, buildRoot, plan) {
  const resolvedOutput = path.resolve(outputPath);
  for (const run of plan.runs) {
    const runDirectory = path.resolve(buildRoot, run.runId, run.fixtureId);
    const relative = path.relative(runDirectory, resolvedOutput);
    if (relative === '' || (!relative.startsWith('..') && !path.isAbsolute(relative))) {
      fail('campaign verification output은 원본 run artifact directory 밖에 있어야 합니다.');
    }
  }
}

export function compareT1T2RepetitionCampaign({
  plan,
  buildRoot = defaultBuildRoot,
  outputPath,
} = {}) {
  const validatedPlan = validateT1T2RepetitionPlan(plan);
  const failures = [];
  const invalidArtifact = [];
  const runsByCondition = new Map(validatedPlan.conditions.map((condition) => [condition.conditionKey, []]));

  for (const run of validatedPlan.runs) {
    const result = readT1T2RunArtifact(buildRoot, run);
    runsByCondition.get(run.conditionKey).push(result);
    if (result.kind === 'INVALID') {
      invalidArtifact.push(result);
      failures.push(result.failure);
    } else if (result.kind === 'FAIL') {
      failures.push(result.failure);
    }
  }

  const conditions = [];
  let acceptedCount = 0;
  let acceptedRunCount = 0;
  for (const condition of validatedPlan.conditions) {
    const runResults = runsByCondition.get(condition.conditionKey);
    const accepted = runResults.length === validatedPlan.repeatCount
      && runResults.every((result) => result.kind === 'PASS');
    if (accepted) {
      acceptedCount += 1;
      acceptedRunCount += runResults.length;
    }
    conditions.push({
      ...condition,
      accepted,
      repeatCount: runResults.length,
      runs: runResults.map((result) => ({
        repeat: result.run.repeat,
        repeatId: result.run.repeatId,
        runId: result.run.runId,
        fixtureId: result.run.fixtureId,
        status: result.kind,
        window: result.window || null,
        metrics: result.metrics || null,
        resourceSignals: result.resourceSignals || null,
        artifactSha256: result.artifactSha256 || null,
        failure: result.failure || null,
      })),
    });
  }

  let status = 'PASS';
  if (invalidArtifact.length > 0) {
    status = 'INVALID';
  } else if (failures.length > 0) {
    status = 'FAIL';
  } else if (acceptedCount !== validatedPlan.conditionCount
    || acceptedRunCount !== validatedPlan.runCount) {
    status = 'INVALID';
  }
  const result = {
    schemaVersion: 1,
    campaignId: validatedPlan.campaignId,
    scenario: validatedPlan.scenario,
    status,
    sourceSha: validatedPlan.sourceSha,
    targetEnvironment: validatedPlan.targetEnvironment,
    readProfile: validatedPlan.readProfile,
    writeExecutionProfile: validatedPlan.writeExecutionProfile,
    repeatCount: validatedPlan.repeatCount,
    expectedRunCount: validatedPlan.runCount,
    runCount: validatedPlan.runs.length,
    conditionCount: validatedPlan.conditionCount,
    acceptedCount,
    acceptedRunCount,
    failures,
    conditions,
  };
  const targetPath = outputPath || comparisonOutputPath(buildRoot, validatedPlan.campaignId);
  assertOutputOutsideRunDirectories(targetPath, buildRoot, validatedPlan);
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

function requiredValue(values, name) {
  const value = values[name];
  if (!value) {
    fail(`--${name} 값이 필요합니다.`);
  }
  return value;
}

function readPlanFile(planPath) {
  const planResult = readJson(path.resolve(planPath), 'T1/T2 repetition plan');
  if (planResult.error) {
    fail(planResult.error);
  }
  return validateT1T2RepetitionPlan(planResult.value);
}

function usage() {
  return `사용법:
  node load-tests/k6/jiwon/tools/t1-t2-repetition.mjs plan --campaign-id <campaign-id> --source-sha <sha> --target-environment <environment>
  node load-tests/k6/jiwon/tools/t1-t2-repetition.mjs compare --plan <plan.json> [--build-root <directory>] [--output <file>]
  node load-tests/k6/jiwon/tools/t1-t2-repetition.mjs compare --campaign-id <campaign-id> --source-sha <sha> --target-environment <environment> [--build-root <directory>] [--output <file>]
`;
}

function main() {
  const { command, values } = parseArguments(process.argv.slice(2));
  if (!command || command === 'help' || command === '--help' || command === '-h') {
    process.stdout.write(usage());
    return;
  }
  if (command === 'plan') {
    const plan = createT1T2RepetitionPlan({
      campaignId: requiredValue(values, 'campaign-id'),
      sourceSha: requiredValue(values, 'source-sha'),
      targetEnvironment: requiredValue(values, 'target-environment'),
    });
    process.stdout.write(`${JSON.stringify(plan, null, 2)}\n`);
    return;
  }
  if (command === 'compare') {
    const plan = values.plan
      ? readPlanFile(values.plan)
      : createT1T2RepetitionPlan({
        campaignId: requiredValue(values, 'campaign-id'),
        sourceSha: requiredValue(values, 'source-sha'),
        targetEnvironment: requiredValue(values, 'target-environment'),
      });
    const result = compareT1T2RepetitionCampaign({
      plan,
      buildRoot: values['build-root'] || defaultBuildRoot,
      outputPath: values.output,
    });
    process.stdout.write(`${JSON.stringify(result)}\n`);
    if (result.status === 'INVALID') {
      process.exitCode = 2;
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
  readT1T2RunArtifact,
};
