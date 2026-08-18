import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  unlinkSync,
  writeFileSync,
} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {
  createT5RepetitionPlan,
  T5_READ_PROFILE,
} from '../lib/t5-repetition-plan.mjs';
import {
  compareT5RepetitionCampaign,
  RESOURCE_SIGNALS_FILE,
  RUN_MANIFEST_FILE,
} from '../tools/t5-repetition.mjs';

const SOURCE_SHA = 'a'.repeat(40);

function sha256(filePath) {
  return createHash('sha256').update(readFileSync(filePath)).digest('hex');
}

function writeJson(filePath, value) {
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function t5Summary() {
  const counter = (count) => ({ values: { count } });
  const duration = (count) => ({
    values: {
      p50: count > 0 ? 10 : null,
      p95: count > 0 ? 20 : null,
      p99: count > 0 ? 30 : null,
      max: count > 0 ? 40 : null,
      count,
    },
  });
  return {
    metrics: {
      room_requests: counter(10),
      room_success: counter(10),
      room_business_failures: counter(0),
      room_concurrent_failures: counter(0),
      room_contract_failures: counter(0),
      room_unexpected_4xx: counter(0),
      room_server_failures: counter(0),
      room_start_skew_ms: counter(T5_READ_PROFILE.vus),
      'room_request_duration{outcome:success}': duration(10),
      'room_request_duration{outcome:business}': duration(0),
      'room_request_duration{outcome:concurrency}': duration(0),
      'room_request_duration{outcome:unexpected}': duration(0),
      http_reqs: { values: { rate: 10 } },
    },
  };
}

function resourceSignals(run, startedAtUtc, finishedAtUtc) {
  return {
    schemaVersion: 1,
    runId: run.runId,
    fixtureId: run.fixtureId,
    window: { startedAtUtc, finishedAtUtc },
    http: { requestCount: 10, failedRequestCount: 0 },
    tomcat: { activeThreads: 2, busyThreads: 1, maxThreads: 20 },
    hikari: { activeConnections: 1, idleConnections: 4, pendingThreads: 0, maxPoolSize: 5 },
    jvm: { heapUsedBytes: 100, heapMaxBytes: 200, cpuPercent: 10 },
    postgresql: { cpuPercent: 12, activeConnections: 2, lockWaitCount: 0 },
    query: {
      callCount: 10,
      totalTimeMilliseconds: 20,
      sharedBuffersHit: 100,
      sharedBuffersRead: 2,
    },
  };
}

function writePassRun(buildRoot, run) {
  const directory = path.join(buildRoot, run.runId, run.fixtureId);
  mkdirSync(directory, { recursive: true });
  const startedAtUtc = `2026-08-${20 + run.repeat}T00:00:00.000Z`;
  const finishedAtUtc = `2026-08-${20 + run.repeat}T00:01:00.000Z`;
  const fixturePath = path.join(directory, 'fixture.json');
  const summaryPath = path.join(directory, 'k6-summary.json');
  const runManifestPath = path.join(directory, RUN_MANIFEST_FILE);
  const resourceSignalsPath = path.join(directory, RESOURCE_SIGNALS_FILE);
  const snapshot = {
    rooms: [{ roomId: run.fixtureId }],
    participations: [],
    waitlists: [],
  };
  writeJson(fixturePath, { fixtureId: run.fixtureId });
  writeJson(summaryPath, t5Summary());

  writeJson(path.join(directory, 'manifest.json'), {
    schemaVersion: 2,
    fixtureSchemaVersion: 2,
    fixtureId: run.fixtureId,
    options: run.options,
    sourceRevision: SOURCE_SHA,
    sourceDirty: false,
  });
  writeJson(path.join(directory, 'fixture-plan.json'), {
    schemaVersion: 2,
    fixtureId: run.fixtureId,
    options: run.options,
  });
  writeJson(path.join(directory, 'execution-options.json'), {
    schemaVersion: 1,
    t5ReadOptions: run.readProfile,
  });
  const runManifest = {
    schemaVersion: 2,
    fixtureId: run.fixtureId,
    runId: run.runId,
    scenario: 't5',
    condition: run.options,
    sourceSha: SOURCE_SHA,
    targetEnvironment: 'private-loadtest',
    k6Version: 'v1.3.0',
    startedAtUtc,
    finishedAtUtc,
    runState: 'COMPLETED',
    completed: true,
    k6ExitCode: 0,
    fixtureSha256: sha256(fixturePath),
    summaryFile: 'k6-summary.json',
    summarySha256: sha256(summaryPath),
    t5ReadOptions: run.readProfile,
  };
  writeJson(runManifestPath, runManifest);
  writeJson(path.join(directory, 'before-snapshot.json'), snapshot);
  writeJson(path.join(directory, 'after-snapshot.json'), snapshot);
  const beforeDiagnosis = {
    fixtureId: run.fixtureId,
    scenario: 't5',
    stage: 'before',
    status: 'PASS',
    failures: [],
    baselineSnapshot: snapshot,
  };
  const afterDiagnosis = {
    fixtureId: run.fixtureId,
    scenario: 't5',
    stage: 'after',
    status: 'PASS',
    failures: [],
  };
  writeJson(path.join(directory, 'before-diagnosis.json'), beforeDiagnosis);
  writeJson(path.join(directory, 'after-diagnosis.json'), afterDiagnosis);
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
  writeJson(path.join(directory, 'infra-execution.json'), infraExecution);
  const signals = resourceSignals(run, startedAtUtc, finishedAtUtc);
  writeJson(resourceSignalsPath, signals);
  const normalizedSignals = {
    schemaVersion: 1,
    runId: run.runId,
    fixtureId: run.fixtureId,
    window: { startedAtUtc, finishedAtUtc },
    http: { requestCount: signals.http.requestCount, failedRequestCount: signals.http.failedRequestCount },
    tomcat: { activeThreads: signals.tomcat.activeThreads },
    hikari: { activeConnections: signals.hikari.activeConnections },
    jvm: { heapUsedBytes: signals.jvm.heapUsedBytes },
    postgresql: { activeConnections: signals.postgresql.activeConnections },
    query: { ...signals.query },
  };
  writeJson(path.join(directory, 'final-result.json'), {
    schemaVersion: 1,
    fixtureId: run.fixtureId,
    runId: run.runId,
    scenario: 't5',
    status: 'PASS',
    issues: [],
    beforeDiagnosis,
    afterDiagnosis,
    infraExecution,
    completion: {
      runManifest,
      resourceSignals: normalizedSignals,
      artifactSha256: {
        fixture: sha256(fixturePath),
        summary: sha256(summaryPath),
        runManifest: sha256(runManifestPath),
        resourceSignals: sha256(resourceSignalsPath),
      },
    },
  });
  return directory;
}

function writeCampaignArtifacts(buildRoot, campaignId) {
  const plan = createT5RepetitionPlan(campaignId);
  for (const run of plan.runs) {
    writePassRun(buildRoot, run);
  }
  return plan;
}

function refreshSummaryHash(directory) {
  const manifestPath = path.join(directory, RUN_MANIFEST_FILE);
  const summaryPath = path.join(directory, 'k6-summary.json');
  const manifest = JSON.parse(readFileSync(manifestPath, 'utf8'));
  manifest.summarySha256 = sha256(summaryPath);
  writeJson(manifestPath, manifest);
}

test('T5 반복 계획은 6조건 × 3회와 독립 repeat identity를 만든다', () => {
  const plan = createT5RepetitionPlan('room-t5-repeat-test');

  assert.equal(plan.runCount, 18);
  assert.equal(plan.repeatCount, 3);
  assert.equal(plan.conditionCount, 6);
  assert.deepEqual(plan.readProfile, {
    vus: 10,
    durationSeconds: 60,
    thinkTimeMilliseconds: 0,
  });
  assert.equal(new Set(plan.runs.map((run) => run.runId)).size, 3);
  assert.equal(new Set(plan.runs.map((run) => run.fixtureId)).size, 18);
  assert.deepEqual(
    [...new Set(plan.runs.map((run) => run.conditionKey))].sort(),
    ['host-1', 'host-10', 'participant-1', 'participant-10', 'public-1', 'public-10'],
  );
  for (const run of plan.runs) {
    assert.match(run.runId, /-r[123]$/);
    assert.equal(run.options.scenario, 't5');
    assert.equal(run.options.runId, run.runId);
    assert.equal(run.options.t5Role, run.conditionKey.split('-')[0]);
    assert.equal(String(run.options.t5Scale), run.conditionKey.split('-')[1]);
    assert.deepEqual(run.readProfile, T5_READ_PROFILE);
  }
});

test('T5 반복 비교는 18개 완료 artifact를 role·scale별 6/6 accepted로 묶는다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-repetition-'));
  const outputPath = path.join(buildRoot, 'comparison.json');
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-pass-test');
    const result = compareT5RepetitionCampaign({
      campaignId: plan.campaignId,
      buildRoot,
      outputPath,
    });

    assert.equal(result.status, 'PASS');
    assert.equal(result.runCount, 18);
    assert.equal(result.acceptedRunCount, 18);
    assert.equal(result.acceptedCount, 6);
    assert.equal(result.failures.length, 0);
    assert.deepEqual(result.provenance, {
      sourceSha: SOURCE_SHA,
      deployedRelease: SOURCE_SHA,
      targetEnvironment: 'private-loadtest',
      k6Version: 'v1.3.0',
    });
    assert.equal(existsSync(outputPath), true);

    const publicScale10 = result.conditions.find((condition) => condition.conditionKey === 'public-10');
    assert.equal(publicScale10.accepted, true);
    assert.equal(publicScale10.runs.length, 3);
    assert.equal(publicScale10.runs[0].metrics.count, 10);
    assert.equal(publicScale10.runs[0].metrics.successCount, 10);
    assert.equal(publicScale10.runs[0].metrics.p50, 10);
    assert.equal(publicScale10.runs[0].metrics.p95, 20);
    assert.equal(publicScale10.runs[0].metrics.p99, 30);
    assert.equal(publicScale10.runs[0].metrics.max, 40);
    assert.equal(publicScale10.runs[0].metrics.rps, 10);
    assert.equal(publicScale10.runs[0].resourceSignals.query.callCount, 10);
    assert.equal(publicScale10.runs[0].resourceSignals.postgresql.activeConnections, 2);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 run 사이 provenance가 다르면 INVALID다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-provenance-mismatch-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-provenance-mismatch-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'public-10' && candidate.repeat === 2);
    const directory = path.join(buildRoot, run.runId, run.fixtureId);
    const portableManifestPath = path.join(directory, 'manifest.json');
    const portableManifest = JSON.parse(readFileSync(portableManifestPath, 'utf8'));
    portableManifest.sourceRevision = 'b'.repeat(40);
    writeJson(portableManifestPath, portableManifest);

    const runManifestPath = path.join(directory, RUN_MANIFEST_FILE);
    const runManifest = JSON.parse(readFileSync(runManifestPath, 'utf8'));
    runManifest.sourceSha = 'b'.repeat(40);
    runManifest.targetEnvironment = 'private-loadtest-other';
    writeJson(runManifestPath, runManifest);
    const finalResultPath = path.join(directory, 'final-result.json');
    const finalResult = JSON.parse(readFileSync(finalResultPath, 'utf8'));
    finalResult.completion.runManifest = runManifest;
    finalResult.completion.artifactSha256.runManifest = sha256(runManifestPath);
    writeJson(finalResultPath, finalResult);

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'INVALID');
    assert.equal(result.acceptedCount, 5);
    assert.equal(result.acceptedRunCount, 15);
    assert.match(result.failures.join('\n'), /provenance.*불일치/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 원본 run-manifest completion이 없으면 INVALID로 차단한다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-missing-manifest-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-missing-manifest-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'public-1' && candidate.repeat === 2);
    unlinkSync(path.join(buildRoot, run.runId, run.fixtureId, RUN_MANIFEST_FILE));

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'INVALID');
    assert.equal(result.acceptedCount, 5);
    assert.match(result.failures.join('\n'), /run-manifest\.json/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 before snapshot이 없으면 INVALID로 차단한다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-missing-before-snapshot-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-missing-before-snapshot-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'public-1' && candidate.repeat === 2);
    unlinkSync(path.join(buildRoot, run.runId, run.fixtureId, 'before-snapshot.json'));

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'INVALID');
    assert.equal(result.acceptedCount, 5);
    assert.match(result.failures.join('\n'), /before-snapshot\.json/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 before·after snapshot이 다르면 FAIL로 남긴다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-changed-snapshot-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-changed-snapshot-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'host-1' && candidate.repeat === 3);
    const afterSnapshotPath = path.join(buildRoot, run.runId, run.fixtureId, 'after-snapshot.json');
    const afterSnapshot = JSON.parse(readFileSync(afterSnapshotPath, 'utf8'));
    afterSnapshot.rooms.push({ roomId: 'unexpected-room' });
    writeJson(afterSnapshotPath, afterSnapshot);

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'FAIL');
    assert.equal(result.acceptedCount, 5);
    assert.match(result.failures.join('\n'), /before-snapshot\.json과 after-snapshot\.json/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 before diagnosis의 stale baselineSnapshot을 INVALID로 차단한다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-stale-diagnosis-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-stale-diagnosis-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'participant-1' && candidate.repeat === 1);
    const diagnosisPath = path.join(buildRoot, run.runId, run.fixtureId, 'before-diagnosis.json');
    const diagnosis = JSON.parse(readFileSync(diagnosisPath, 'utf8'));
    diagnosis.baselineSnapshot.rooms[0].roomId = 'stale-room';
    writeJson(diagnosisPath, diagnosis);

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'INVALID');
    assert.equal(result.acceptedCount, 5);
    assert.match(result.failures.join('\n'), /before-diagnosis\.json.*baselineSnapshot/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 final result의 nested diagnosis가 현재 artifact와 다르면 INVALID로 차단한다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-final-evidence-mismatch-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-final-evidence-mismatch-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'participant-10' && candidate.repeat === 2);
    const finalResultPath = path.join(buildRoot, run.runId, run.fixtureId, 'final-result.json');
    const finalResult = JSON.parse(readFileSync(finalResultPath, 'utf8'));
    finalResult.afterDiagnosis.failures = ['stale diagnosis'];
    writeJson(finalResultPath, finalResult);

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'INVALID');
    assert.equal(result.acceptedCount, 5);
    assert.match(result.failures.join('\n'), /final-result\.json.*afterDiagnosis/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 HTTP·Tomcat·Hikari·JVM·PostgreSQL 또는 query 신호가 없으면 INVALID다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-missing-signals-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-missing-signals-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'host-10' && candidate.repeat === 3);
    const signalsPath = path.join(buildRoot, run.runId, run.fixtureId, RESOURCE_SIGNALS_FILE);
    const signals = JSON.parse(readFileSync(signalsPath, 'utf8'));
    delete signals.postgresql;
    writeJson(signalsPath, signals);

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'INVALID');
    assert.match(result.failures.join('\n'), /postgresql 자원 신호/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 query call/time/buffer 신호가 없으면 INVALID다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-missing-query-signals-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-missing-query-signals-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'public-10' && candidate.repeat === 1);
    const signalsPath = path.join(buildRoot, run.runId, run.fixtureId, RESOURCE_SIGNALS_FILE);
    const signals = JSON.parse(readFileSync(signalsPath, 'utf8'));
    delete signals.query.sharedBuffersRead;
    writeJson(signalsPath, signals);

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'INVALID');
    assert.match(result.failures.join('\n'), /query sharedBuffersRead/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 성공 응답 p99가 없으면 INVALID다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-missing-p99-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-missing-p99-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'participant-1' && candidate.repeat === 1);
    const directory = path.join(buildRoot, run.runId, run.fixtureId);
    const summaryPath = path.join(directory, 'k6-summary.json');
    const summary = JSON.parse(readFileSync(summaryPath, 'utf8'));
    delete summary.metrics['room_request_duration{outcome:success}'].values.p99;
    writeJson(summaryPath, summary);
    refreshSummaryHash(directory);

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'INVALID');
    assert.match(result.failures.join('\n'), /성공 응답 p99/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 실제 PASS가 아닌 final result를 FAIL로 남긴다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-final-fail-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-final-fail-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'participant-10' && candidate.repeat === 2);
    const finalResultPath = path.join(buildRoot, run.runId, run.fixtureId, 'final-result.json');
    const finalResult = JSON.parse(readFileSync(finalResultPath, 'utf8'));
    finalResult.status = 'FAIL';
    finalResult.issues = ['simulated correctness failure'];
    writeJson(finalResultPath, finalResult);

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'FAIL');
    assert.equal(result.acceptedCount, 5);
    assert.match(result.failures.join('\n'), /final-result\.json이 FAIL/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});

test('T5 반복 비교는 summary의 contract failure를 FAIL로 남긴다', () => {
  const buildRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-t5-summary-fail-'));
  try {
    const plan = writeCampaignArtifacts(buildRoot, 'room-t5-summary-fail-test');
    const run = plan.runs.find((candidate) => candidate.conditionKey === 'host-1' && candidate.repeat === 2);
    const directory = path.join(buildRoot, run.runId, run.fixtureId);
    const summaryPath = path.join(directory, 'k6-summary.json');
    const summary = JSON.parse(readFileSync(summaryPath, 'utf8'));
    summary.metrics.room_contract_failures.values.count = 1;
    writeJson(summaryPath, summary);
    refreshSummaryHash(directory);

    const result = compareT5RepetitionCampaign({ campaignId: plan.campaignId, buildRoot });

    assert.equal(result.status, 'FAIL');
    assert.equal(result.acceptedCount, 5);
    assert.match(result.failures.join('\n'), /room_contract_failures/);
  } finally {
    rmSync(buildRoot, { recursive: true, force: true });
  }
});
