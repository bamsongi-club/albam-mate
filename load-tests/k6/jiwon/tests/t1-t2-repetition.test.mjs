import { createHash } from 'node:crypto';
import {
  appendFileSync,
  existsSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import assert from 'node:assert/strict';

import {
  createT1T2RepetitionPlan,
  T1_T2_CONDITIONS,
  T1_T2_REPEAT_COUNT,
} from '../lib/t1-t2-repetition-plan.mjs';
import { renderBundle } from '../tools/portable-bundle.mjs';
import {
  compareT1T2RepetitionCampaign,
  readT1T2RunArtifact,
  validateResourceSignals,
  validateSummary,
} from '../tools/t1-t2-repetition.mjs';

const SOURCE_SHA = 'a'.repeat(40);
const repositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');

function summaryWithOneSuccess(run) {
  const duration = (count) => ({
      values: {
        p50: count > 0 ? 10 : 0,
        p95: count > 0 ? 20 : 0,
        p99: count > 0 ? 30 : 0,
        max: count > 0 ? 40 : 0,
      count,
    },
  });
  return {
    metrics: {
      room_requests: { values: { count: 1 } },
      room_contract_failures: { values: { count: 0 } },
      room_unexpected_4xx: { values: { count: 0 } },
      room_server_failures: { values: { count: 0 } },
      room_start_skew_ms: {
        values: {
          count: run.options.concurrency * run.options.rounds,
          max: 5,
        },
      },
      'room_request_duration{outcome:success}': duration(1),
      'room_request_duration{outcome:business}': duration(0),
      'room_request_duration{outcome:concurrency}': duration(0),
      'room_request_duration{outcome:unexpected}': duration(0),
    },
  };
}

function writeJson(filePath, value) {
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function sha256(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function portableBundleContext(buildRoot) {
  return {
    repositoryRoot,
    scenarioDirectory: path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'),
    buildRoot,
    isBundleRuntime: false,
    bundleRoot: null,
    environment: {
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$test',
      ROOM_K6_SESSION_WARMUP_SECONDS: '15',
      ROOM_K6_ROUND_INTERVAL_SECONDS: '20',
    },
  };
}

function renderRunBundle(run, buildRoot) {
  const { fixtureId: _fixtureId, ...inputOptions } = run.options;
  return renderBundle(
    inputOptions,
    portableBundleContext(buildRoot),
    { sourceRevision: SOURCE_SHA, sourceDirty: false },
  );
}

function resourceSignalsFor(run, summaryResult, completionManifest) {
  return {
    schemaVersion: 1,
    status: 'PASS',
    runId: run.runId,
    fixtureId: run.fixtureId,
    scenario: run.options.scenario,
    sourceSha: run.sourceSha,
    targetEnvironment: run.targetEnvironment,
    condition: run.options,
    window: {
      startedAtUtc: completionManifest.startedAtUtc,
      finishedAtUtc: completionManifest.finishedAtUtc,
    },
    outcomeCoverage: summaryResult.outcomes,
    http: { requestCount: 1, failedRequestCount: 0, rps: 1 },
    tomcat: { activeThreads: 1, busyThreads: 0, maxThreads: 64 },
    hikari: { activeConnections: 1, idleConnections: 0, pendingThreads: 0, maxPoolSize: 10 },
    jvm: { heapUsedBytes: 1, heapMaxBytes: 10, cpuPercent: 0 },
    postgresql: {
      cpuPercent: 0,
      activeConnections: 1,
      lockWaitCount: 0,
      transactionDurationMilliseconds: 5,
    },
    query: {
      callCount: 1,
      totalTimeMilliseconds: 1,
      sharedBuffersHit: 0,
      sharedBuffersRead: 0,
    },
    retry: {
      commonRetrier: { attempts: { '1': 0 }, retries: 0, exhausted: 0 },
      coordinator: { attempts: { '1': 0 }, retries: 0, exhausted: 0 },
    },
    observedStructuredRetryEvents: 0,
  };
}

function writeValidRunArtifacts(run, buildRoot, { failing = false } = {}) {
  const rendered = renderRunBundle(run, buildRoot);
  const directory = rendered.bundlePath;
  const fixturePath = path.join(directory, 'fixture.json');
  const summaryPath = path.join(directory, 'k6-summary.json');
  const fixture = {
    schemaVersion: 2,
    fixtureId: run.fixtureId,
    options: run.options,
  };
  const summary = summaryWithOneSuccess(run);
  const summaryResult = validateSummary(summary, run).value;
  if (failing) {
    summary.metrics.room_server_failures.values.count = 1;
  }
  writeJson(fixturePath, fixture);
  writeJson(summaryPath, summary);

  const completionManifest = {
    schemaVersion: 2,
    fixtureId: run.fixtureId,
    runId: run.runId,
    scenario: run.options.scenario,
    condition: run.options,
    runState: 'COMPLETED',
    completed: true,
    k6ExitCode: 0,
    sourceSha: run.sourceSha,
    targetEnvironment: run.targetEnvironment,
    k6Version: '1.3.0',
    startedAtUtc: '2026-08-19T00:00:00.000Z',
    finishedAtUtc: '2026-08-19T00:01:00.000Z',
    summaryFile: 'k6-summary.json',
    summarySha256: sha256(summaryPath),
    fixtureSha256: sha256(fixturePath),
    t5ReadOptions: null,
  };
  writeJson(path.join(directory, 'run-manifest.json'), completionManifest);

  const beforeDiagnosis = {
    fixtureId: run.fixtureId,
    scenario: run.options.scenario,
    stage: 'before',
    status: 'PASS',
    failures: [],
  };
  const afterDiagnosis = {
    fixtureId: run.fixtureId,
    scenario: run.options.scenario,
    stage: 'after',
    status: 'PASS',
    failures: [],
  };
  const infraExecution = {
    schemaVersion: 1,
    runId: run.runId,
    fixtureId: run.fixtureId,
    phases: {
      prepare: { exitCode: 0 },
      resourceQuery: { exitCode: 0 },
      beforeSnapshot: { exitCode: 0 },
      k6: { exitCode: 0 },
      afterSnapshot: { exitCode: 0 },
    },
  };
  writeJson(path.join(directory, 'before-diagnosis.json'), beforeDiagnosis);
  writeJson(path.join(directory, 'after-diagnosis.json'), afterDiagnosis);
  writeJson(path.join(directory, 'infra-execution.json'), infraExecution);
  writeJson(path.join(directory, 'final-result.json'), {
    schemaVersion: 1,
    fixtureId: run.fixtureId,
    runId: run.runId,
    scenario: run.options.scenario,
    status: 'PASS',
    issues: [],
    beforeDiagnosis,
    afterDiagnosis,
    infraExecution,
    runManifest: completionManifest,
  });
  writeJson(
    path.join(directory, 'resource-signals.json'),
    resourceSignalsFor(run, summaryResult, completionManifest),
  );
  return directory;
}

test('T1/T2 반복 계획은 8조건 × 3회와 독립 fixture/run identity를 만든다', () => {
  const plan = createT1T2RepetitionPlan({
    campaignId: 'room-t1-t2-repeat-test',
    sourceSha: SOURCE_SHA,
    targetEnvironment: 'private-loadtest',
  });

  assert.equal(plan.conditionCount, 8);
  assert.equal(plan.repeatCount, T1_T2_REPEAT_COUNT);
  assert.equal(plan.runCount, 24);
  assert.deepEqual(
    plan.conditions.map((condition) => condition.conditionKey),
    T1_T2_CONDITIONS.map((condition) => condition.conditionKey),
  );
  assert.equal(new Set(plan.runs.map((run) => run.runId)).size, 24);
  assert.equal(new Set(plan.runs.map((run) => run.fixtureId)).size, 24);
  assert.deepEqual(plan.readProfile, null);
  assert.deepEqual(plan.writeExecutionProfile, {
    ROOM_K6_SESSION_WARMUP_SECONDS: '15',
    ROOM_K6_ROUND_INTERVAL_SECONDS: '20',
  });

  for (const run of plan.runs) {
    assert.match(run.runId, /-(?:t1|t2)-.*-r[123]$/);
    assert.equal(run.options.runId, run.runId);
    assert.equal(run.options.profile, 'stress');
    assert.equal(run.options.rounds, 5);
    assert.equal(run.sourceSha, SOURCE_SHA);
    assert.equal(run.targetEnvironment, 'private-loadtest');
    assert.equal(run.readProfile, null);
    if (run.options.scenario === 't2') {
      assert.equal(run.options.subcase, 'distinct');
    } else {
      assert.equal(Object.hasOwn(run.options, 'subcase'), false);
    }
  }
});

test('T1/T2 비교기는 원본 run artifact가 없으면 campaign을 INVALID로 기록한다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t1-t2-missing-'));
  try {
    const plan = createT1T2RepetitionPlan({
      campaignId: 'room-t1-t2-missing-test',
      sourceSha: SOURCE_SHA,
      targetEnvironment: 'private-loadtest',
    });
    const result = compareT1T2RepetitionCampaign({ plan, buildRoot });

    assert.equal(result.status, 'INVALID');
    assert.equal(result.runCount, 24);
    assert.equal(result.acceptedCount, 0);
    assert.equal(result.acceptedRunCount, 0);
    assert.equal(result.conditions.length, 8);
    assert.equal(existsSync(result.outputPath), true);
    assert.match(result.failures.join('\n'), /fixture directory가 없습니다/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T1/T2 summary 계약은 표본이 있는 outcome의 p99 누락을 INVALID로 차단한다', () => {
  const plan = createT1T2RepetitionPlan({
    campaignId: 'room-t1-t2-summary-test',
    sourceSha: SOURCE_SHA,
    targetEnvironment: 'private-loadtest',
  });
  const run = plan.runs[0];
  const summary = summaryWithOneSuccess(run);
  delete summary.metrics['room_request_duration{outcome:success}'].values.p99;

  const result = validateSummary(summary, run);

  assert.equal(result.error.includes('p50·p95·p99·max'), true);
});

test('T1/T2 summary 계약은 k6 1.3 raw med·p(95)·p(99)를 normalized p50·p95·p99로 읽는다', () => {
  const plan = createT1T2RepetitionPlan({
    campaignId: 'room-t1-t2-summary-alias-test',
    sourceSha: SOURCE_SHA,
    targetEnvironment: 'private-loadtest',
  });
  const run = plan.runs[0];
  const summary = summaryWithOneSuccess(run);
  const successValues = summary.metrics['room_request_duration{outcome:success}'].values;
  delete successValues.p50;
  delete successValues.p95;
  delete successValues.p99;
  successValues.med = 10;
  successValues['p(95)'] = 20;
  successValues['p(99)'] = 30;

  const result = validateSummary(summary, run);

  assert.equal(result.error, undefined);
  assert.deepEqual(result.value.outcomes.success, {
    count: 1,
    p50: 10,
    p95: 20,
    p99: 30,
    max: 40,
  });
});

test('T1/T2 resource signal 계약은 retry 분포와 필수 자원 신호 누락을 INVALID로 차단한다', () => {
  const plan = createT1T2RepetitionPlan({
    campaignId: 'room-t1-t2-signal-test',
    sourceSha: SOURCE_SHA,
    targetEnvironment: 'private-loadtest',
  });
  const run = plan.runs[0];
  const summary = summaryWithOneSuccess(run);
  const completionManifest = {
    startedAtUtc: '2026-08-18T00:00:00.000Z',
    finishedAtUtc: '2026-08-18T00:01:00.000Z',
  };
  const result = validateResourceSignals({}, run, completionManifest, {
    requestCount: 1,
    outcomes: {
      success: { count: 1, p50: 10, p95: 20, p99: 30, max: 40 },
      business: { count: 0, p50: null, p95: null, p99: null, max: null },
      concurrency: { count: 0, p50: null, p95: null, p99: null, max: null },
      unexpected: { count: 0, p50: null, p95: null, p99: null, max: null },
    },
  });

  assert.match(result.error, /run·condition·source·UTC window/);
  assert.equal(validateSummary(summary, run).value.requestCount, 1);
});

test('T1/T2 resource signal 계약은 T3 DB·connection 필수 신호 누락을 INVALID로 차단한다', () => {
  const plan = createT1T2RepetitionPlan({
    campaignId: 'room-t1-t2-required-signal-test',
    sourceSha: SOURCE_SHA,
    targetEnvironment: 'private-loadtest',
  });
  const run = plan.runs[0];
  const summary = summaryWithOneSuccess(run);
  const summaryResult = validateSummary(summary, run).value;
  const completionManifest = {
    startedAtUtc: '2026-08-18T00:00:00.000Z',
    finishedAtUtc: '2026-08-18T00:01:00.000Z',
  };

  for (const [group, field] of [
    ['hikari', 'pendingThreads'],
    ['postgresql', 'lockWaitCount'],
    ['postgresql', 'transactionDurationMilliseconds'],
  ]) {
    const signals = resourceSignalsFor(run, summaryResult, completionManifest);
    delete signals[group][field];

    const result = validateResourceSignals(signals, run, completionManifest, summaryResult);

    assert.ok(result.error, `${group}.${field} 누락이 통과했습니다.`);
  }
});

test('T1/T2 비교기는 portable bundle immutable artifact 변경을 INVALID로 차단한다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t1-t2-immutable-'));
  try {
    const plan = createT1T2RepetitionPlan({
      campaignId: 'room-t1-t2-immutable-test',
      sourceSha: SOURCE_SHA,
      targetEnvironment: 'private-loadtest',
    });
    const run = plan.runs[0];
    const directory = renderRunBundle(run, buildRoot).bundlePath;
    appendFileSync(path.join(directory, 'scenario.js'), '\n// tampered\n');

    const result = readT1T2RunArtifact(buildRoot, run);

    assert.equal(result.kind, 'INVALID');
    assert.match(result.failure, /immutable artifact가 변조되었습니다/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T1/T2 비교 campaign은 유효 artifact의 실행 실패를 INVALID가 아닌 FAIL로 기록한다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t1-t2-fail-'));
  try {
    const plan = createT1T2RepetitionPlan({
      campaignId: 'room-t1-t2-fail-test',
      sourceSha: SOURCE_SHA,
      targetEnvironment: 'private-loadtest',
    });
    for (const [index, run] of plan.runs.entries()) {
      writeValidRunArtifacts(run, buildRoot, { failing: index === 0 });
    }

    const result = compareT1T2RepetitionCampaign({ plan, buildRoot });

    assert.equal(result.status, 'FAIL');
    assert.equal(result.conditions[0].runs[0].status, 'FAIL');
    assert.match(result.failures.join('\n'), /room_server_failures/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T1/T2 repetition tool은 fixture.mjs를 재실행하거나 원본 summary·final-result를 쓰지 않는다', () => {
  const source = readFileSync(
    path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon', 'tools', 't1-t2-repetition.mjs'),
    'utf8',
  );

  assert.doesNotMatch(source, /fixture\.mjs/);
  assert.doesNotMatch(source, /writeFileSync\([^\n]*(?:k6-summary|final-result)/);
  assert.match(source, /readJson\(path\.join\(directory, RESOURCE_SIGNALS_FILE\)/);
});

test('T1/T2 comparison output은 원본 run directory 안의 artifact를 덮어쓸 수 없다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t1-t2-output-'));
  try {
    const plan = createT1T2RepetitionPlan({
      campaignId: 'room-t1-t2-output-test',
      sourceSha: SOURCE_SHA,
      targetEnvironment: 'private-loadtest',
    });
    const run = plan.runs[0];
    const outputPath = path.join(buildRoot, run.runId, run.fixtureId, 'k6-summary.json');

    assert.throws(
      () => compareT1T2RepetitionCampaign({ plan, buildRoot, outputPath }),
      /원본 run artifact directory 밖/,
    );
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});
