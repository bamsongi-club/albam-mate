import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import {
  cpSync,
  existsSync,
  mkdirSync,
  mkdtempSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  aggregateBundle,
  cleanupBundle,
  cleanupSqlBundle,
  diagnoseBundle,
  hydrateBundle,
  recoverCleanupBundle,
  renderBundle,
  validateBundle,
} from '../tools/fixture.mjs';

function resourcesFor(plan) {
  const users = {};
  const rooms = {};
  let nextUserId = 100;
  let nextRoomId = 1000;
  for (const user of plan.users) {
    users[user.email] = nextUserId;
    nextUserId += 1;
  }
  for (const room of plan.rooms) {
    rooms[room.title] = nextRoomId;
    nextRoomId += 1;
  }
  return { users, rooms };
}

function initialSnapshot(fixture) {
  const rooms = [];
  const participations = [];
  const waitlists = [];
  let queueOrder = 1;

  for (const room of Object.values(fixture.rooms)) {
    rooms.push({
      id: room.id,
      hostUserId: fixture.users[room.hostKey].id,
      title: room.title,
      capacity: room.capacity,
      activeParticipantCount: room.activeKeys.length,
      status: room.status,
      version: 0,
      startAt: '2030-01-01T00:00:00Z',
      updatedAt: '2030-01-01T00:00:00Z',
    });
    room.activeKeys.forEach((userKey, index) => {
      participations.push({
        roomId: room.id,
        userId: fixture.users[userKey].id,
        status: 'ACTIVE',
        joinedAt: `2030-01-01T00:00:0${index}Z`,
        canceledAt: null,
      });
    });
    room.waiterKeys.forEach((userKey) => {
      waitlists.push({
        roomId: room.id,
        userId: fixture.users[userKey].id,
        status: 'WAITING',
        queueOrder,
        queuedAt: '2030-01-01T00:00:00Z',
      });
      queueOrder += 1;
    });
  }

  return { rooms, participations, waitlists };
}

function summaryWith(counts = {}) {
  const metricNames = [
    'room_success',
    'room_created',
    'room_requests',
    'room_business_failures',
    'room_concurrent_failures',
    'room_contract_failures',
    'room_unexpected_4xx',
    'room_server_failures',
  ];
  const metrics = {};
  metricNames.forEach((name) => {
    metrics[name] = { values: { count: counts[name] || 0 } };
  });
  return { metrics };
}

function writeJson(filePath, value) {
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function infraExecutionFor(rendered) {
  return {
    schemaVersion: 1,
    runId: rendered.options.runId,
    fixtureId: rendered.fixtureId,
    stackId: 'stack-room-k6-test',
    targetHttpsUrl: 'https://room-k6.test.invalid',
    applicationRevision: 'a'.repeat(40),
    startedAt: '2030-01-01T00:00:00.000Z',
    finishedAt: '2030-01-01T00:01:00.000Z',
    phases: {
      prepare: { exitCode: 0 },
      resourceQuery: { exitCode: 0 },
      beforeSnapshot: { exitCode: 0 },
      k6: { exitCode: 0 },
      afterSnapshot: { exitCode: 0 },
    },
    k6Version: '0.0.0-test',
    t5ReadOptions: rendered.scenario === 't5'
      ? { vus: 10, durationSeconds: 60, thinkTimeMilliseconds: 0 }
      : undefined,
  };
}

function sha256(text) {
  return createHash('sha256').update(text).digest('hex');
}

function withCleanupAcknowledgement(acknowledgement, callback) {
  const original = process.env.ROOM_K6_CLEANUP_ACK;
  if (acknowledgement === undefined) {
    delete process.env.ROOM_K6_CLEANUP_ACK;
  } else {
    process.env.ROOM_K6_CLEANUP_ACK = acknowledgement;
  }
  try {
    return callback();
  } finally {
    if (original === undefined) {
      delete process.env.ROOM_K6_CLEANUP_ACK;
    } else {
      process.env.ROOM_K6_CLEANUP_ACK = original;
    }
  }
}

function runBundleTool(bundlePath, command, workingDirectory, extraArguments = []) {
  return spawnSync(
    process.execPath,
    [path.join(bundlePath, 'tools', 'fixture.mjs'), command, '--bundle', bundlePath, ...extraArguments],
    { cwd: workingDirectory, encoding: 'utf8' },
  );
}

function runGit(workingDirectory, argumentsList) {
  const result = spawnSync('git', argumentsList, {
    cwd: workingDirectory,
    encoding: 'utf8',
  });
  assert.equal(result.status, 0, result.stderr || result.stdout);
  return String(result.stdout || '').trim();
}

function prepareBundleFromCheckout(checkoutPath, runId) {
  const toolPath = path.join(checkoutPath, 'load-tests', 'k6', 'jiwon', 'tools', 'fixture.mjs');
  const result = spawnSync(process.execPath, [
    toolPath,
    'prepare',
    '--scenario', 't1',
    '--run-id', runId,
    '--profile', 'spike',
    '--mode', 'hot',
    '--concurrency', '2',
  ], {
    cwd: checkoutPath,
    encoding: 'utf8',
    env: {
      ...process.env,
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem',
    },
  });
  assert.equal(result.status, 0, result.stderr || result.stdout);
  return JSON.parse(result.stdout);
}

test('ROOM bundle은 DB 없이 render하고 raw DB artifact로 hydrate와 diagnose한다', () => {
  const runId = `bundle-test-${process.pid}-${Date.now().toString(36)}`;
  const rendered = renderBundle({
    scenario: 't5',
    runId,
    profile: 'spike',
    t5Role: 'public',
    t5Scale: 1,
  }, '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem');
  const runDirectory = path.dirname(rendered.bundlePath);

  try {
    const manifest = JSON.parse(readFileSync(path.join(rendered.bundlePath, 'manifest.json'), 'utf8'));
    const plan = JSON.parse(readFileSync(path.join(rendered.bundlePath, 'fixture-plan.json'), 'utf8'));
    assert.equal(manifest.schemaVersion, 1);
    assert.equal(manifest.kind, 'albam-mate-room-k6-bundle');
    assert.equal(manifest.options.runId, runId);
    assert.equal(manifest.artifacts.entry, 'scenario.js');
    assert.equal(manifest.artifacts.runtimeTool, 'tools/fixture.mjs');
    assert.equal(manifest.artifacts.runtimeModel, 'tools/fixture-model.mjs');
    assert.equal(manifest.artifacts.prepareProvenance, 'private/prepare-provenance.json');
    assert.equal(manifest.artifacts.console, 'k6-console.log');
    assert.equal(manifest.artifacts.infraExecution, 'infra-execution.json');
    assert.equal(manifest.artifacts.cloudwatchDirectory, 'cloudwatch');
    assert.equal(manifest.artifacts.finalResult, 'final-result.json');
    assert.equal(
      manifest.rawSqlTransport.command,
      'psql -X --no-psqlrc -v ON_ERROR_STOP=1 -q -A -t -f <sql>',
    );
    assert.equal(manifest.rawSqlTransport.stdout.destinations.resourceQuerySql, 'resource-output.json');
    assert.deepEqual(manifest.rawSqlTransport.stdout.destinations.snapshotSql, [
      'before-snapshot.json',
      'after-snapshot.json',
    ]);
    assert.equal(manifest.rawTransport.infraExecution.schemaVersion, 1);
    assert.deepEqual(manifest.rawSqlTransport.stderr, {
      returned: false,
      handling: 'remote-temporary-no-log-delete',
    });
    assert.equal(typeof manifest.sourceDirty, 'boolean');
    if (manifest.sourceDirty) {
      assert.equal(manifest.sourceRevision, null);
    } else {
      assert.match(manifest.sourceRevision, /^[a-f0-9]{40}$/);
    }
    assert.deepEqual(Object.keys(manifest.sourceHashes).sort(), [
      'entry',
      'library',
      'readExecutionOptions',
      'runtimeModel',
      'runtimeTool',
      't3ExecutionPlan',
      'writeOptions',
    ]);
    assert.ok(Object.values(manifest.sourceHashes).every((value) => /^[a-f0-9]{64}$/.test(value)));
    assert.deepEqual(Object.keys(manifest.artifactHashes).sort(), [
      'fixturePlan',
      'prepareProvenance',
      'prepareSql',
      'resourceQuerySql',
    ]);
    assert.ok(Object.values(manifest.artifactHashes).every((value) => /^[a-f0-9]{64}$/.test(value)));
    assert.equal(
      manifest.executionProtocol.stages.find((stage) => stage.name === 'validate').requiredBefore,
      'fixture-prepare',
    );
    assert.equal(
      manifest.executionProtocol.stages.find((stage) => stage.name === 'diagnose-before').requiredBefore,
      'k6',
    );
    assert.equal(
      manifest.executionProtocol.stages.find((stage) => stage.name === 'k6').preserveRawArtifactsOnFailure,
      true,
    );
    assert.ok(existsSync(path.join(rendered.bundlePath, 'scenario.js')));
    assert.ok(existsSync(path.join(rendered.bundlePath, 'lib', 'room-k6.js')));
    assert.ok(existsSync(path.join(rendered.bundlePath, 'tools', 'fixture.mjs')));
    assert.ok(existsSync(path.join(rendered.bundlePath, 'tools', 'fixture-model.mjs')));
    assert.ok(existsSync(path.join(rendered.bundlePath, 'prepare.sql')));
    assert.ok(existsSync(path.join(rendered.bundlePath, 'resource-query.sql')));
    assert.ok(existsSync(path.join(rendered.bundlePath, 'private', 'prepare-provenance.json')));
    assert.equal(existsSync(path.join(rendered.bundlePath, 'fixture.json')), false);

    writeJson(path.join(rendered.bundlePath, 'resource-output.json'), resourcesFor(plan));
    const manifestPath = path.join(rendered.bundlePath, 'manifest.json');
    const originalManifest = readFileSync(manifestPath, 'utf8');
    const runtimeToolPath = path.join(rendered.bundlePath, 'tools', 'fixture.mjs');
    const originalRuntimeTool = readFileSync(runtimeToolPath, 'utf8');
    writeFileSync(runtimeToolPath, `${originalRuntimeTool}\n// tampered\n`, 'utf8');
    assert.throws(
      () => hydrateBundle(rendered.bundlePath),
      /bundle runtimeTool source hash가 manifest와 다릅니다/,
    );
    writeFileSync(runtimeToolPath, originalRuntimeTool, 'utf8');

    const wrongIdentity = {
      ...manifest,
      options: { ...manifest.options, runId: 'another-run' },
    };
    writeJson(manifestPath, wrongIdentity);
    assert.throws(
      () => hydrateBundle(rendered.bundlePath),
      /bundle 경로와 manifest runId·fixtureId가 일치하지 않습니다/,
    );
    writeFileSync(manifestPath, originalManifest, 'utf8');

    const hydrated = hydrateBundle(rendered.bundlePath);
    const fixture = JSON.parse(readFileSync(hydrated.fixturePath, 'utf8'));
    const snapshot = initialSnapshot(fixture);
    writeJson(path.join(rendered.bundlePath, 'before-snapshot.json'), snapshot);

    assert.deepEqual(diagnoseBundle({ bundle: rendered.bundlePath, stage: 'before' }), {
      fixtureId: fixture.fixtureId,
      scenario: 't5',
      stage: 'before',
      status: 'PASS',
      failures: [],
    });

    writeJson(path.join(rendered.bundlePath, 'after-snapshot.json'), snapshot);
    writeJson(path.join(rendered.bundlePath, 'k6-summary.json'), summaryWith({
      room_requests: 1,
      room_success: 1,
    }));

    assert.deepEqual(diagnoseBundle({ bundle: rendered.bundlePath, stage: 'after' }), {
      fixtureId: fixture.fixtureId,
      scenario: 't5',
      stage: 'after',
      status: 'PASS',
      failures: [],
    });
    assert.ok(existsSync(hydrated.snapshotSqlPath));
    assert.ok(existsSync(hydrated.cleanupSqlPath));

    const prepareSqlPath = path.join(rendered.bundlePath, 'prepare.sql');
    const originalPrepareSql = readFileSync(prepareSqlPath, 'utf8');
    writeFileSync(prepareSqlPath, `${originalPrepareSql}\n-- tampered before cleanup\n`, 'utf8');
    let preflightExecutionCount = 0;
    assert.throws(
      () => cleanupBundle(rendered.bundlePath, () => {
        preflightExecutionCount += 1;
      }),
      /bundle prepareSql artifact hash가 manifest와 다릅니다/,
    );
    assert.equal(preflightExecutionCount, 0);
    assert.throws(
      () => cleanupSqlBundle(rendered.bundlePath),
      /bundle prepareSql artifact hash가 manifest와 다릅니다/,
    );
    writeFileSync(prepareSqlPath, originalPrepareSql, 'utf8');

    const reviewedCleanupSql = '-- this artifact is for review only\nSELECT 1;\n';
    writeFileSync(hydrated.cleanupSqlPath, reviewedCleanupSql, 'utf8');
    const regeneratedCleanupSql = cleanupSqlBundle(rendered.bundlePath);
    assert.match(regeneratedCleanupSql, /room_k6_cleanup_users/);
    assert.doesNotMatch(regeneratedCleanupSql, /artifact is for review only/);
    const originalFixture = readFileSync(hydrated.fixturePath, 'utf8');
    writeJson(hydrated.fixturePath, { ...fixture, fixtureId: 'room-k6-t5-000000000000' });
    assert.throws(
      () => cleanupBundle(rendered.bundlePath, () => {}),
      /fixture와 bundle manifest가 일치하지 않습니다/,
    );
    writeFileSync(hydrated.fixturePath, originalFixture, 'utf8');
    const forgedFixture = structuredClone(fixture);
    forgedFixture.users[forgedFixture.sessionUserKeys[0]].id = 999999;
    writeJson(hydrated.fixturePath, forgedFixture);
    assert.throws(
      () => cleanupBundle(rendered.bundlePath, () => {}),
      /fixture JSON이 fixture plan과 raw resource output에 일치하지 않습니다/,
    );
    writeFileSync(hydrated.fixturePath, originalFixture, 'utf8');

    let executedArgs;
    let executedSql;
    assert.deepEqual(withCleanupAcknowledgement(
      fixture.fixtureId,
      () => cleanupBundle(rendered.bundlePath, (args, input) => {
        executedArgs = args;
        executedSql = input;
      }),
    ), { fixtureId: fixture.fixtureId, status: 'CLEANED' });
    assert.deepEqual(executedArgs, ['-q', '-f', '-']);
    assert.match(executedSql, /room_k6_cleanup_users/);
    assert.doesNotMatch(executedSql, /artifact is for review only/);
    assert.equal(executedSql, regeneratedCleanupSql);
  } finally {
    rmSync(runDirectory, { recursive: true, force: true });
  }
});

test('ROOM bundle aggregate는 진단과 raw infra artifact를 재판정 없이 묶는다', () => {
  const runId = `aggregate-bundle-${process.pid}-${Date.now().toString(36)}`;
  const rendered = renderBundle({
    scenario: 't5',
    runId,
    profile: 'spike',
    t5Role: 'public',
    t5Scale: 1,
  }, '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem');
  const runDirectory = path.dirname(rendered.bundlePath);

  try {
    const incomplete = aggregateBundle(rendered.bundlePath);
    assert.equal(incomplete.aggregationStatus, 'INCOMPLETE');
    assert.deepEqual(
      incomplete.inputIssues.map((issue) => issue.artifact).sort(),
      ['after-diagnosis.json', 'before-diagnosis.json', 'cloudwatch', 'infra-execution.json'],
    );
    assert.equal(existsSync(path.join(rendered.bundlePath, 'final-result.json')), true);

    const plan = JSON.parse(readFileSync(path.join(rendered.bundlePath, 'fixture-plan.json'), 'utf8'));
    writeJson(path.join(rendered.bundlePath, 'resource-output.json'), resourcesFor(plan));
    const hydrated = hydrateBundle(rendered.bundlePath);
    const fixture = JSON.parse(readFileSync(hydrated.fixturePath, 'utf8'));
    const snapshot = initialSnapshot(fixture);
    writeJson(path.join(rendered.bundlePath, 'before-snapshot.json'), snapshot);
    diagnoseBundle({ bundle: rendered.bundlePath, stage: 'before' });
    writeJson(path.join(rendered.bundlePath, 'after-snapshot.json'), snapshot);
    writeJson(path.join(rendered.bundlePath, 'k6-summary.json'), summaryWith({
      room_requests: 1,
      room_success: 1,
    }));
    const afterDiagnosis = diagnoseBundle({ bundle: rendered.bundlePath, stage: 'after' });
    writeJson(path.join(rendered.bundlePath, 'after-diagnosis.json'), {
      ...afterDiagnosis,
      status: 'FAIL',
      failures: ['진단 결과는 집계기가 다시 판정하지 않는다.'],
    });
    writeJson(path.join(rendered.bundlePath, 'infra-execution.json'), infraExecutionFor(rendered));
    const cloudwatchDirectory = path.join(rendered.bundlePath, 'cloudwatch', 'metrics');
    mkdirSync(cloudwatchDirectory, { recursive: true });
    writeFileSync(path.join(cloudwatchDirectory, 'cpu.json'), '{"raw":true}\n', 'utf8');

    const complete = aggregateBundle(rendered.bundlePath);
    assert.equal(complete.aggregationStatus, 'COMPLETE');
    assert.equal(complete.diagnoses.before.status, 'PASS');
    assert.equal(complete.diagnoses.after.status, 'FAIL');
    assert.equal(complete.infraExecution.phases.k6.exitCode, 0);
    assert.deepEqual(complete.cloudwatch, {
      presence: 'PRESENT',
      artifacts: [{
        path: 'metrics/cpu.json',
        sizeBytes: 13,
        modifiedAt: complete.cloudwatch.artifacts[0].modifiedAt,
      }],
    });
    assert.deepEqual(
      JSON.parse(readFileSync(path.join(rendered.bundlePath, 'final-result.json'), 'utf8')),
      complete,
    );

    const wrongIdentity = {
      ...infraExecutionFor(rendered),
      fixtureId: 'room-k6-t5-000000000000',
    };
    writeJson(path.join(rendered.bundlePath, 'infra-execution.json'), wrongIdentity);
    const invalid = aggregateBundle(rendered.bundlePath);
    assert.equal(invalid.aggregationStatus, 'INVALID_INPUT');
    assert.equal(invalid.infraExecution, null);
    assert.equal(invalid.inputIssues[0].artifact, 'infra-execution.json');
    assert.equal(invalid.inputIssues[0].type, 'INVALID');

    writeJson(path.join(rendered.bundlePath, 'infra-execution.json'), null);
    const invalidJsonValue = aggregateBundle(rendered.bundlePath);
    assert.equal(invalidJsonValue.aggregationStatus, 'INVALID_INPUT');
    assert.equal(invalidJsonValue.inputIssues[0].type, 'INVALID');

    writeJson(path.join(rendered.bundlePath, 'infra-execution.json'), {
      ...infraExecutionFor(rendered),
      startedAt: '2030-01-01 00:00:00Z',
    });
    const invalidTimestamp = aggregateBundle(rendered.bundlePath);
    assert.equal(invalidTimestamp.aggregationStatus, 'INVALID_INPUT');
    assert.equal(invalidTimestamp.infraExecution, null);
    assert.equal(invalidTimestamp.inputIssues[0].artifact, 'infra-execution.json');

    writeJson(path.join(rendered.bundlePath, 'infra-execution.json'), {
      ...infraExecutionFor(rendered),
      finishedAt: '2030-01-01T00:01:00+09:00',
    });
    const invalidFinishedTimestamp = aggregateBundle(rendered.bundlePath);
    assert.equal(invalidFinishedTimestamp.aggregationStatus, 'INVALID_INPUT');
    assert.equal(invalidFinishedTimestamp.infraExecution, null);
    assert.equal(invalidFinishedTimestamp.inputIssues[0].artifact, 'infra-execution.json');

    writeJson(path.join(rendered.bundlePath, 'infra-execution.json'), {
      ...infraExecutionFor(rendered),
      startedAt: '2030-01-01T00:01:00.000Z',
      finishedAt: '2030-01-01T00:00:00.000Z',
    });
    const reversedTimestamp = aggregateBundle(rendered.bundlePath);
    assert.equal(reversedTimestamp.aggregationStatus, 'INVALID_INPUT');
    assert.equal(reversedTimestamp.infraExecution, null);
    assert.equal(reversedTimestamp.inputIssues[0].artifact, 'infra-execution.json');

    writeJson(path.join(rendered.bundlePath, 'infra-execution.json'), infraExecutionFor(rendered));
    const beforeDiagnosisPath = path.join(rendered.bundlePath, 'before-diagnosis.json');
    const malformedDiagnosis = JSON.parse(readFileSync(beforeDiagnosisPath, 'utf8'));
    delete malformedDiagnosis.failures;
    writeJson(beforeDiagnosisPath, malformedDiagnosis);
    const invalidDiagnosis = aggregateBundle(rendered.bundlePath);
    assert.equal(invalidDiagnosis.aggregationStatus, 'INVALID_INPUT');
    assert.equal(invalidDiagnosis.diagnoses.before, null);
    assert.equal(invalidDiagnosis.inputIssues[0].artifact, 'before-diagnosis.json');

    writeJson(beforeDiagnosisPath, {
      ...malformedDiagnosis,
      status: 'PRESERVED_WITHOUT_REEVALUATION',
      failures: ['집계기는 diagnosis 상태와 failure의 의미를 다시 판정하지 않는다.'],
    });
    const copiedDiagnosis = aggregateBundle(rendered.bundlePath);
    assert.equal(copiedDiagnosis.aggregationStatus, 'COMPLETE');
    assert.equal(copiedDiagnosis.diagnoses.before.status, 'PRESERVED_WITHOUT_REEVALUATION');

    writeJson(beforeDiagnosisPath, {
      ...malformedDiagnosis,
      failures: ['valid failure', 1],
    });
    const invalidFailures = aggregateBundle(rendered.bundlePath);
    assert.equal(invalidFailures.aggregationStatus, 'INVALID_INPUT');
    assert.equal(invalidFailures.diagnoses.before, null);
    assert.equal(invalidFailures.inputIssues[0].artifact, 'before-diagnosis.json');
  } finally {
    rmSync(runDirectory, { recursive: true, force: true });
  }
});

test('ROOM bundle은 dirty source를 Git HEAD로 주장하지 않는다', () => {
  const temporaryRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-provenance-'));
  const checkoutPath = path.join(temporaryRoot, 'checkout');
  const currentRepositoryRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../../..');
  const currentScenarioDirectory = path.join(currentRepositoryRoot, 'load-tests', 'k6', 'jiwon');

  try {
    mkdirSync(checkoutPath, { recursive: true });
    cpSync(currentScenarioDirectory, path.join(checkoutPath, 'load-tests', 'k6', 'jiwon'), { recursive: true });
    writeFileSync(path.join(checkoutPath, 'package.json'), '{"type":"module"}\n', 'utf8');
    writeFileSync(path.join(checkoutPath, '.gitignore'), 'build/\n', 'utf8');
    runGit(checkoutPath, ['init', '-q']);
    runGit(checkoutPath, ['add', '.']);
    runGit(checkoutPath, [
      '-c', 'user.name=ROOM bundle test',
      '-c', 'user.email=room-bundle-test@example.invalid',
      'commit', '-qm', 'initial',
    ]);

    const clean = prepareBundleFromCheckout(checkoutPath, `provenance-clean-${process.pid}-${Date.now().toString(36)}`);
    const cleanManifest = JSON.parse(readFileSync(path.join(clean.bundlePath, 'manifest.json'), 'utf8'));
    assert.equal(cleanManifest.sourceDirty, false);
    assert.equal(cleanManifest.sourceRevision, runGit(checkoutPath, ['rev-parse', 'HEAD']));

    const sourcePath = path.join(checkoutPath, 'load-tests', 'k6', 'jiwon', 'lib', 'room-k6.js');
    writeFileSync(sourcePath, `${readFileSync(sourcePath, 'utf8')}\n// local dirty source\n`, 'utf8');
    const dirty = prepareBundleFromCheckout(checkoutPath, `provenance-dirty-${process.pid}-${Date.now().toString(36)}`);
    const dirtyManifest = JSON.parse(readFileSync(path.join(dirty.bundlePath, 'manifest.json'), 'utf8'));
    assert.equal(dirtyManifest.sourceDirty, true);
    assert.equal(dirtyManifest.sourceRevision, null);
    assert.ok(Object.values(dirtyManifest.sourceHashes).every((value) => /^[a-f0-9]{64}$/.test(value)));
  } finally {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
});

test('ROOM bundle validate는 DB 실행 전 artifact와 fixture plan을 검증한다', () => {
  const runId = `preflight-bundle-${process.pid}-${Date.now().toString(36)}`;
  const rendered = renderBundle({
    scenario: 't1',
    runId,
    profile: 'spike',
    mode: 'hot',
    concurrency: 2,
  }, '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem');
  const runDirectory = path.dirname(rendered.bundlePath);
  const manifestPath = path.join(rendered.bundlePath, 'manifest.json');

  try {
    const originalManifest = readFileSync(manifestPath, 'utf8');
    assert.deepEqual(validateBundle(rendered.bundlePath), {
      bundlePath: rendered.bundlePath,
      runId,
      fixtureId: rendered.fixtureId,
    });
    assert.equal(existsSync(path.join(rendered.bundlePath, 'fixture.json')), false);

    const artifacts = [
      ['fixture-plan.json', 'fixturePlan'],
      ['prepare.sql', 'prepareSql'],
      ['resource-query.sql', 'resourceQuerySql'],
    ];
    for (const [fileName, hashName] of artifacts) {
      const filePath = path.join(rendered.bundlePath, fileName);
      const original = readFileSync(filePath, 'utf8');
      rmSync(filePath);
      assert.throws(() => validateBundle(rendered.bundlePath));
      writeFileSync(filePath, original, 'utf8');

      const altered = fileName === 'fixture-plan.json'
        ? `${original}\n`
        : `${original}\n-- altered\n`;
      writeFileSync(filePath, altered, 'utf8');
      assert.throws(
        () => validateBundle(rendered.bundlePath),
        new RegExp(`bundle ${hashName} artifact hash가 manifest와 다릅니다`),
      );
      writeFileSync(filePath, original, 'utf8');
    }

    const manifest = JSON.parse(originalManifest);
    writeJson(manifestPath, { ...manifest, sourceHashes: {} });
    assert.throws(
      () => validateBundle(rendered.bundlePath),
      /ROOM k6 bundle source hash 계약이 없습니다/,
    );
    writeFileSync(manifestPath, originalManifest, 'utf8');

    const planPath = path.join(rendered.bundlePath, 'fixture-plan.json');
    const originalPlan = readFileSync(planPath, 'utf8');
    const malformedPlan = {
      ...JSON.parse(originalPlan),
      users: [],
    };
    const malformedPlanText = `${JSON.stringify(malformedPlan, null, 2)}\n`;
    writeFileSync(planPath, malformedPlanText, 'utf8');
    writeJson(manifestPath, {
      ...manifest,
      artifactHashes: {
        ...manifest.artifactHashes,
        fixturePlan: sha256(malformedPlanText),
      },
    });
    assert.throws(
      () => validateBundle(rendered.bundlePath),
      /fixture plan은 앱 생성 계획과 일치하지 않습니다/,
    );
    writeFileSync(planPath, originalPlan, 'utf8');
    writeFileSync(manifestPath, originalManifest, 'utf8');
  } finally {
    rmSync(runDirectory, { recursive: true, force: true });
  }
});

test('ROOM bundle recovery cleanup은 운영자의 명시적 호출에서만 app plan으로 실행된다', () => {
  const runId = `recovery-bundle-${process.pid}-${Date.now().toString(36)}`;
  const rendered = renderBundle({
    scenario: 't1',
    runId,
    profile: 'spike',
    mode: 'hot',
    concurrency: 2,
  }, '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem');
  const runDirectory = path.dirname(rendered.bundlePath);

  try {
    const plan = JSON.parse(readFileSync(path.join(rendered.bundlePath, 'fixture-plan.json'), 'utf8'));
    const calls = [];
    assert.deepEqual(withCleanupAcknowledgement(
      rendered.fixtureId,
      () => recoverCleanupBundle(rendered.bundlePath, (args, input) => {
        calls.push({ args, input });
        return calls.length === 1 ? JSON.stringify(resourcesFor(plan)) : '';
      }),
    ), { fixtureId: rendered.fixtureId, status: 'RECOVERED_CLEANED' });
    assert.deepEqual(calls[0].args, ['-q', '-A', '-t', '-f', '-']);
    assert.match(calls[0].input, /SELECT jsonb_build_object/);
    assert.deepEqual(calls[1].args, ['-q', '-f', '-']);
    assert.match(calls[1].input, /room_k6_cleanup_users/);
    assert.equal(existsSync(path.join(rendered.bundlePath, 'resource-output.json')), false);
    assert.equal(existsSync(path.join(rendered.bundlePath, 'fixture.json')), false);

    let executionCount = 0;
    assert.throws(
      () => withCleanupAcknowledgement(rendered.fixtureId, () => recoverCleanupBundle(rendered.bundlePath, (_args, _input) => {
        executionCount += 1;
        if (executionCount > 1) {
          throw new Error('cleanup must not run without complete resources');
        }
        return JSON.stringify({ users: {}, rooms: {} });
      })),
      /fixture 사용자 ID를 찾지 못했습니다/,
    );
    assert.equal(executionCount, 1);
  } finally {
    rmSync(runDirectory, { recursive: true, force: true });
  }
});

test('bundle 안의 CLI는 앱 checkout 밖에서 hydrate와 diagnose를 실행한다', () => {
  const runId = `external-bundle-${process.pid}-${Date.now().toString(36)}`;
  const rendered = renderBundle({
    scenario: 't5',
    runId,
    profile: 'spike',
    t5Role: 'public',
    t5Scale: 1,
  }, '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem');
  const sourceRunDirectory = path.dirname(rendered.bundlePath);
  const externalRoot = mkdtempSync(path.join(os.tmpdir(), 'room-k6-bundle-'));
  const externalBundlePath = path.join(externalRoot, runId, rendered.fixtureId);

  try {
    const plan = JSON.parse(readFileSync(path.join(rendered.bundlePath, 'fixture-plan.json'), 'utf8'));
    writeJson(path.join(rendered.bundlePath, 'resource-output.json'), resourcesFor(plan));
    mkdirSync(path.dirname(externalBundlePath), { recursive: true });
    cpSync(rendered.bundlePath, externalBundlePath, { recursive: true });

    const validated = runBundleTool(externalBundlePath, 'validate', externalRoot);
    assert.equal(validated.status, 0, validated.stderr);
    assert.deepEqual(JSON.parse(validated.stdout), {
      bundlePath: externalBundlePath,
      runId,
      fixtureId: rendered.fixtureId,
    });

    const incompleteAggregate = runBundleTool(externalBundlePath, 'aggregate', externalRoot);
    assert.equal(incompleteAggregate.status, 2, incompleteAggregate.stderr);
    assert.equal(JSON.parse(incompleteAggregate.stdout).aggregationStatus, 'INCOMPLETE');
    assert.ok(existsSync(path.join(externalBundlePath, 'final-result.json')));

    const hydrated = runBundleTool(externalBundlePath, 'hydrate', externalRoot);
    assert.equal(hydrated.status, 0, hydrated.stderr);
    const hydratedResult = JSON.parse(hydrated.stdout);
    assert.equal(hydratedResult.bundlePath, externalBundlePath);
    assert.ok(existsSync(path.join(externalBundlePath, 'fixture.json')));
    assert.ok(existsSync(path.join(externalBundlePath, 'snapshot.sql')));

    writeFileSync(path.join(externalBundlePath, 'cleanup.sql'), '-- artifact is not executable input\nSELECT 1;\n', 'utf8');
    const cleanupSql = runBundleTool(externalBundlePath, 'cleanup-sql', externalRoot);
    assert.equal(cleanupSql.status, 0, cleanupSql.stderr);
    assert.match(cleanupSql.stdout, /room_k6_cleanup_users/);
    assert.doesNotMatch(cleanupSql.stdout, /artifact is not executable input/);
    assert.doesNotMatch(cleanupSql.stdout, /^\s*\{/);

    const fixture = JSON.parse(readFileSync(path.join(externalBundlePath, 'fixture.json'), 'utf8'));
    const snapshot = initialSnapshot(fixture);
    writeJson(path.join(externalBundlePath, 'before-snapshot.json'), snapshot);
    const before = runBundleTool(externalBundlePath, 'diagnose', externalRoot, ['--stage', 'before']);
    assert.equal(before.status, 0, before.stderr);
    assert.equal(JSON.parse(before.stdout).status, 'PASS');

    writeJson(path.join(externalBundlePath, 'after-snapshot.json'), snapshot);
    writeJson(path.join(externalBundlePath, 'k6-summary.json'), summaryWith({
      room_requests: 1,
      room_success: 1,
    }));
    const after = runBundleTool(externalBundlePath, 'diagnose', externalRoot, ['--stage', 'after']);
    assert.equal(after.status, 0, after.stderr);
    assert.equal(JSON.parse(after.stdout).status, 'PASS');

    writeJson(path.join(externalBundlePath, 'infra-execution.json'), infraExecutionFor(rendered));
    const cloudwatchDirectory = path.join(externalBundlePath, 'cloudwatch');
    mkdirSync(cloudwatchDirectory, { recursive: true });
    writeFileSync(path.join(cloudwatchDirectory, 'raw.json'), '{"cpu":42}\n', 'utf8');
    const aggregate = runBundleTool(externalBundlePath, 'aggregate', externalRoot);
    assert.equal(aggregate.status, 0, aggregate.stderr);
    const aggregateResult = JSON.parse(aggregate.stdout);
    assert.equal(aggregateResult.aggregationStatus, 'COMPLETE');
    assert.equal(aggregateResult.diagnoses.after.status, 'PASS');
    assert.equal(aggregateResult.infraExecution.fixtureId, rendered.fixtureId);
    assert.equal(aggregateResult.cloudwatch.artifacts[0].path, 'raw.json');
    assert.ok(existsSync(path.join(externalBundlePath, 'final-result.json')));
  } finally {
    rmSync(sourceRunDirectory, { recursive: true, force: true });
    rmSync(externalRoot, { recursive: true, force: true });
  }
});
