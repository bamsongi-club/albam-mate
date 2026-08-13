import assert from 'node:assert/strict';
import {
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  aggregateBundle,
  compareT5Bundles,
  diagnoseBundle,
  executionOptionsBundle,
  hydrateBundle,
  renderBundle,
  validateBundle,
} from '../tools/fixture.mjs';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(testDirectory, '../../../..');
const bundleBuildRoot = path.join(repositoryRoot, 'build', 'k6', 'room');
const passwordHash = '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem';

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
        joinedAt: '2030-01-01T00:00:0' + index + 'Z',
        canceledAt: null,
      });
    });
    room.waiterKeys.forEach((userKey, index) => {
      waitlists.push({
        roomId: room.id,
        userId: fixture.users[userKey].id,
        status: 'WAITING',
        queueOrder: index + 1,
        queuedAt: '2030-01-01T00:00:00Z',
      });
    });
  }

  return { rooms, participations, waitlists };
}

function summaryWith(counts = {}) {
  const names = [
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
  names.forEach((name) => {
    metrics[name] = { values: { count: counts[name] || 0 } };
  });
  return { metrics };
}

function writeJson(filePath, value) {
  writeFileSync(filePath, JSON.stringify(value, null, 2) + '\n', 'utf8');
}

function infraExecutionFor(rendered, t5ReadOptions, k6ExitCode = 0) {
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
      k6: { exitCode: k6ExitCode },
      afterSnapshot: { exitCode: 0 },
    },
    k6Version: '0.0.0-test',
    t5ReadOptions,
  };
}

function completeT5Bundle(runId, role, scale, t5ReadOptions, k6ExitCode = 0) {
  const rendered = renderBundle({
    scenario: 't5',
    runId,
    profile: 'spike',
    t5Role: role,
    t5Scale: scale,
  }, passwordHash);
  const plan = JSON.parse(readFileSync(path.join(rendered.bundlePath, 'fixture-plan.json'), 'utf8'));
  writeJson(path.join(rendered.bundlePath, 'resource-output.json'), resourcesFor(plan));
  const hydrated = hydrateBundle(rendered.bundlePath);
  const fixture = JSON.parse(readFileSync(hydrated.fixturePath, 'utf8'));
  const snapshot = initialSnapshot(fixture);
  writeJson(path.join(rendered.bundlePath, 'before-snapshot.json'), snapshot);
  assert.equal(diagnoseBundle({ bundle: rendered.bundlePath, stage: 'before' }).status, 'PASS');
  writeJson(path.join(rendered.bundlePath, 'after-snapshot.json'), snapshot);
  writeJson(path.join(rendered.bundlePath, 'k6-summary.json'), summaryWith({
    room_requests: 1,
    room_success: 1,
  }));
  assert.equal(diagnoseBundle({ bundle: rendered.bundlePath, stage: 'after' }).status, 'PASS');
  writeJson(
    path.join(rendered.bundlePath, 'infra-execution.json'),
    infraExecutionFor(rendered, t5ReadOptions, k6ExitCode),
  );
  mkdirSync(path.join(rendered.bundlePath, 'cloudwatch'), { recursive: true });
  writeFileSync(path.join(rendered.bundlePath, 'cloudwatch', 'raw.json'), '{"cpu":42}\n', 'utf8');
  return { rendered, finalResult: aggregateBundle(rendered.bundlePath) };
}

test('bundle은 현재 T3 실행 소스의 import closure를 함께 담고 실행 전 상태만 검증한다', () => {
  const runId = 'runner-t3-closure-' + process.pid + '-' + Date.now().toString(36);
  const rendered = renderBundle({
    scenario: 't3',
    runId,
    profile: 'spike',
    t3Mode: 'race',
  }, passwordHash);
  const runDirectory = path.dirname(rendered.bundlePath);

  try {
    assert.deepEqual(validateBundle(rendered.bundlePath, { forExecution: true }), {
      bundlePath: rendered.bundlePath,
      runId,
      fixtureId: rendered.fixtureId,
    });
    const requiredFiles = [
      'scenario.js',
      'lib/room-k6.js',
      'lib/read-execution-options.mjs',
      'lib/write-options.mjs',
      'lib/t3-execution-plan.mjs',
      'tools/fixture.mjs',
      'tools/fixture-model.mjs',
    ];
    requiredFiles.forEach((relativePath) => {
      assert.equal(existsSync(path.join(rendered.bundlePath, relativePath)), true, relativePath);
    });
    assert.match(
      readFileSync(path.join(rendered.bundlePath, 'scenario.js'), 'utf8'),
      /t3-execution-plan\.mjs/,
    );
    assert.match(
      readFileSync(path.join(rendered.bundlePath, 'lib', 'room-k6.js'), 'utf8'),
      /read-execution-options\.mjs/,
    );
  } finally {
    rmSync(runDirectory, { recursive: true, force: true });
  }
});

test('T5 실행 옵션은 bundle 앱 코드가 정규화하고 infra는 그대로 전달할 수 있다', () => {
  const runId = 'runner-t5-options-' + process.pid + '-' + Date.now().toString(36);
  const rendered = renderBundle({
    scenario: 't5',
    runId,
    profile: 'spike',
    t5Role: 'public',
    t5Scale: 1,
  }, passwordHash);
  const runDirectory = path.dirname(rendered.bundlePath);

  try {
    const options = executionOptionsBundle(rendered.bundlePath, {
      ROOM_K6_READ_VUS: '7',
      ROOM_K6_READ_DURATION_SECONDS: '75',
      ROOM_K6_READ_THINK_TIME_MS: '25',
    });
    assert.deepEqual(options, {
      bundlePath: rendered.bundlePath,
      scenario: 't5',
      t5ReadOptions: {
        vus: 7,
        durationSeconds: 75,
        thinkTimeMilliseconds: 25,
      },
      k6Environment: {
        ROOM_K6_READ_VUS: '7',
        ROOM_K6_READ_DURATION_SECONDS: '75',
        ROOM_K6_READ_THINK_TIME_MS: '25',
      },
    });
  } finally {
    rmSync(runDirectory, { recursive: true, force: true });
  }
});

test('T5 비교는 infra가 반환한 raw 실행 provenance와 앱 final-result를 사용한다', () => {
  const runId = 'runner-t5-compare-' + process.pid + '-' + Date.now().toString(36);
  const t5ReadOptions = { vus: 7, durationSeconds: 75, thinkTimeMilliseconds: 25 };
  const runDirectory = path.join(bundleBuildRoot, runId);
  let participantTenBundlePath;

  try {
    for (const role of ['public', 'host', 'participant']) {
      for (const scale of [1, 10]) {
        const completed = completeT5Bundle(runId, role, scale, t5ReadOptions);
        assert.equal(completed.finalResult.aggregationStatus, 'COMPLETE');
        if (role === 'participant' && scale === 10) {
          participantTenBundlePath = completed.rendered.bundlePath;
        }
      }
    }

    const matched = compareT5Bundles(runId);
    assert.equal(matched.status, 'PASS');
    assert.deepEqual(matched.t5ReadOptions, t5ReadOptions);

    const mismatchPath = path.join(participantTenBundlePath, 'infra-execution.json');
    const mismatch = JSON.parse(readFileSync(mismatchPath, 'utf8'));
    mismatch.t5ReadOptions.vus = 8;
    writeJson(mismatchPath, mismatch);
    const mismatched = compareT5Bundles(runId);
    assert.equal(mismatched.status, 'FAIL');
    assert.match(mismatched.failures[0], /read profile/);

    rmSync(path.join(participantTenBundlePath, 'final-result.json'));
    const invalid = compareT5Bundles(runId);
    assert.equal(invalid.status, 'INVALID');
    assert.match(invalid.failures[0], /final-result/);
  } finally {
    rmSync(runDirectory, { recursive: true, force: true });
  }
});

test('T5 비교는 deployment provenance 누락, 형식 오류, 불일치를 INVALID로 거절한다', () => {
  const runId = 'runner-t5-provenance-' + process.pid + '-' + Date.now().toString(36);
  const t5ReadOptions = { vus: 7, durationSeconds: 75, thinkTimeMilliseconds: 25 };
  const runDirectory = path.join(bundleBuildRoot, runId);
  let participantTenBundlePath;

  try {
    for (const role of ['public', 'host', 'participant']) {
      for (const scale of [1, 10]) {
        const completed = completeT5Bundle(runId, role, scale, t5ReadOptions);
        if (role === 'participant' && scale === 10) {
          participantTenBundlePath = completed.rendered.bundlePath;
        }
      }
    }

    assert.equal(compareT5Bundles(runId).status, 'PASS');

    const executionPath = path.join(participantTenBundlePath, 'infra-execution.json');
    const execution = JSON.parse(readFileSync(executionPath, 'utf8'));
    execution.applicationRevision = 'b'.repeat(40);
    writeJson(executionPath, execution);
    const mismatched = compareT5Bundles(runId);
    assert.equal(mismatched.status, 'INVALID');
    assert.match(mismatched.failures.join('\n'), /applicationRevision/);

    execution.applicationRevision = 'a'.repeat(40);
    delete execution.targetHttpsUrl;
    writeJson(executionPath, execution);
    const missing = compareT5Bundles(runId);
    assert.equal(missing.status, 'INVALID');
    assert.match(missing.failures.join('\n'), /infra metadata/);

    execution.targetHttpsUrl = 'https://room-k6.test.invalid';
    execution.stackId = '   ';
    writeJson(executionPath, execution);
    const malformed = compareT5Bundles(runId);
    assert.equal(malformed.status, 'INVALID');
    assert.match(malformed.failures.join('\n'), /infra metadata/);
  } finally {
    rmSync(runDirectory, { recursive: true, force: true });
  }
});

test('k6 비정상 종료도 infra raw metadata와 final-result에 보존하고 재판정하지 않는다', () => {
  const runId = 'runner-k6-exit-' + process.pid + '-' + Date.now().toString(36);
  let runDirectory;

  try {
    const completed = completeT5Bundle(
      runId,
      'public',
      1,
      { vus: 10, durationSeconds: 60, thinkTimeMilliseconds: 0 },
      23,
    );
    runDirectory = path.dirname(completed.rendered.bundlePath);
    assert.equal(completed.finalResult.aggregationStatus, 'COMPLETE');
    assert.equal(completed.finalResult.infraExecution.phases.k6.exitCode, 23);
    assert.equal(completed.finalResult.diagnoses.after.status, 'PASS');
  } finally {
    if (runDirectory) {
      rmSync(runDirectory, { recursive: true, force: true });
    }
  }
});
