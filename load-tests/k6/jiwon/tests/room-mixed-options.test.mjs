import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync } from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

import {
  buildMixedAggregate,
  createMixedSelectionPlan,
  mixedExecutionOptions,
  normalizeMixedProfileOptions,
} from '../lib/room-mixed-options.mjs';
import {
  createFixturePlan,
  evaluateFixture,
  hydrateFixture,
} from '../tools/fixture-model.mjs';
import {
  bundleExecutionOptions,
  renderBundle,
  validateBundle,
} from '../tools/portable-bundle.mjs';

const testDirectory = path.dirname(fileURLToPath(import.meta.url));
const repositoryRoot = path.resolve(testDirectory, '../../../..');

function profile(overrides = {}) {
  return {
    hotRoomCount: 1,
    spreadRoomCount: 4,
    hotRequestPercent: 50,
    spreadRequestPercent: 50,
    t1Percent: 50,
    t2Percent: 25,
    t5Percent: 25,
    arrivalRate: 4,
    arrivalTimeUnit: '1s',
    durationSeconds: 3,
    preAllocatedVUs: 4,
    maxVUs: 8,
    seed: 783,
    ...overrides,
  };
}

function metric(count, values = {}) {
  return {
    values: {
      count,
      p50: count > 0 ? 10 : null,
      p95: count > 0 ? 20 : null,
      p99: count > 0 ? 30 : null,
      max: count > 0 ? 40 : null,
      ...values,
    },
  };
}

function aggregateSummary() {
  const metrics = {
    dropped_iterations: metric(8),
    'room_request_duration{outcome:success}': metric(4),
    'room_request_duration{outcome:business}': metric(0),
    'room_request_duration{outcome:concurrency}': metric(0),
    'room_request_duration{outcome:unexpected}': metric(0),
    'room_mixed_requests{tier:hot,operation:t1,outcome:success}': metric(2),
    'room_mixed_request_duration{tier:hot,operation:t1,outcome:success}': metric(2),
    'room_mixed_requests{tier:spread,operation:t2,outcome:success}': metric(1),
    'room_mixed_request_duration{tier:spread,operation:t2,outcome:success}': metric(1),
    'room_mixed_requests{tier:spread,operation:t5,outcome:success}': metric(1),
    'room_mixed_request_duration{tier:spread,operation:t5,outcome:success}': metric(1),
  };
  return { metrics };
}

function fixtureResources(plan) {
  return {
    users: Object.fromEntries(plan.users.map((user, index) => [user.email, index + 1])),
    rooms: Object.fromEntries(plan.rooms.map((room, index) => [room.title, index + 101])),
  };
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
    room.activeKeys.forEach((userKey) => {
      participations.push({
        roomId: room.id,
        userId: fixture.users[userKey].id,
        status: 'ACTIVE',
        joinedAt: '2030-01-01T00:00:00Z',
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

test('mixed profile은 필수 입력, 비율 합계, seed와 fixture 상한을 실행 전에 거절한다', () => {
  assert.throws(
    () => normalizeMixedProfileOptions({ ...profile(), seed: undefined }),
    /seed은\(는\)/,
  );
  assert.throws(
    () => normalizeMixedProfileOptions(profile({ hotRequestPercent: 60 })),
    /합은 100/,
  );
  assert.throws(
    () => normalizeMixedProfileOptions(profile({ t5Percent: 30 })),
    /합은 100/,
  );
  assert.throws(
    () => normalizeMixedProfileOptions(profile({ t5Percent: 0, t2Percent: 50 })),
    /모두 0보다 커야/,
  );
  assert.throws(
    () => normalizeMixedProfileOptions(profile({ spreadRoomCount: 3 })),
    /spreadRoomCount/,
  );
  assert.throws(
    () => normalizeMixedProfileOptions(profile({ maxVUs: 3 })),
    /maxVUs/,
  );
  for (const [name, value] of [
    ['seed', null],
    ['arrivalRate', true],
    ['durationSeconds', null],
    ['preAllocatedVUs', true],
  ]) {
    assert.throws(
      () => normalizeMixedProfileOptions(profile({ [name]: value })),
      /정수여야 합니다/,
    );
  }
});

test('같은 정규화 options와 seed는 순서와 무관하게 같은 selection·digest를 만든다', () => {
  const first = createMixedSelectionPlan(profile());
  const second = createMixedSelectionPlan(profile());
  const differentSeed = createMixedSelectionPlan(profile({ seed: 784 }));

  assert.deepEqual(first, second);
  assert.equal(first.selections.length, 12);
  assert.equal(first.selectionPlanDigest.length, 64);
  assert.notDeepEqual(first.selections, differentSeed.selections);
  assert.equal(first.counts.byOperation.t1 + first.counts.byOperation.t2 + first.counts.byOperation.t5, 12);
  assert.equal(first.counts.byTier.hot + first.counts.byTier.spread, 12);
  assert.ok(first.selections.every((selection) => Number.isInteger(selection.roomSlot)));
});

test('constant-arrival-rate 실행 option은 입력 rate·timeUnit·duration·VU 값을 그대로 보존한다', () => {
  assert.deepEqual(mixedExecutionOptions(profile()), {
    executor: 'constant-arrival-rate',
    rate: 4,
    timeUnit: '1s',
    duration: '3s',
    preAllocatedVUs: 4,
    maxVUs: 8,
  });
});

test('mixed aggregate는 빈 tier·operation·outcome 조합을 보존하고 arrival artifact 누락을 INVALID로 분리한다', () => {
  const summary = aggregateSummary();
  const aggregate = buildMixedAggregate(summary, profile());

  assert.equal(aggregate.status, 'PASS');
  assert.equal(aggregate.targetArrivals, 12);
  assert.equal(aggregate.actualArrivals, 4);
  assert.equal(aggregate.droppedIterations, 8);
  assert.deepEqual(aggregate.tiers.hot.t5.concurrency, {
    count: 0,
    p50: null,
    p95: null,
    p99: null,
    max: null,
  });

  delete summary.metrics.dropped_iterations;
  assert.equal(buildMixedAggregate(summary, profile()).status, 'INVALID');
});

test('k6 v1.3 empty Trend의 all-zero 통계는 최종 zero cell로 정규화한다', () => {
  const summary = aggregateSummary();
  summary.metrics['room_mixed_request_duration{tier:hot,operation:t1,outcome:business}'] = metric(0, {
    p50: 0,
    p95: 0,
    p99: 0,
    max: 0,
  });

  const aggregate = buildMixedAggregate(summary, profile());

  assert.equal(aggregate.status, 'PASS');
  assert.deepEqual(aggregate.tiers.hot.t1.business, {
    count: 0,
    p50: null,
    p95: null,
    p99: null,
    max: null,
  });
});

test('empty Trend의 일부 0/null 혼합과 nonzero 통계는 malformed INVALID다', () => {
  const partial = aggregateSummary();
  partial.metrics['room_mixed_request_duration{tier:hot,operation:t1,outcome:business}'] = metric(0, {
    p50: 0,
  });
  assert.equal(buildMixedAggregate(partial, profile()).status, 'INVALID');

  const nonzero = aggregateSummary();
  nonzero.metrics['room_mixed_request_duration{tier:hot,operation:t1,outcome:business}'] = metric(0, {
    p50: 1,
    p95: 1,
    p99: 1,
    max: 1,
  });
  assert.equal(buildMixedAggregate(nonzero, profile()).status, 'INVALID');
});

test('aggregate의 outcome latency count 불일치는 FAIL로 분리한다', () => {
  const summary = aggregateSummary();
  summary.metrics['room_mixed_request_duration{tier:hot,operation:t1,outcome:success}'] = metric(1);

  assert.equal(buildMixedAggregate(summary, profile()).status, 'FAIL');
});

test('양수 request count의 duration artifact 누락과 malformed는 INVALID다', () => {
  const missingDuration = aggregateSummary();
  delete missingDuration.metrics['room_mixed_request_duration{tier:hot,operation:t1,outcome:success}'];
  assert.equal(buildMixedAggregate(missingDuration, profile()).status, 'INVALID');

  const malformedDuration = aggregateSummary();
  malformedDuration.metrics['room_mixed_request_duration{tier:hot,operation:t1,outcome:success}'] = {
    values: { count: 2, p50: null, p95: null, p99: null, max: null },
  };
  assert.equal(buildMixedAggregate(malformedDuration, profile()).status, 'INVALID');
});

test('duration count 불일치와 malformed 통계가 함께 있으면 INVALID가 우선한다', () => {
  const summary = aggregateSummary();
  summary.metrics['room_mixed_request_duration{tier:hot,operation:t1,outcome:success}'] = {
    values: { count: 1, p50: null, p95: null, p99: null, max: null },
  };

  assert.equal(buildMixedAggregate(summary, profile()).status, 'INVALID');
});

test('mixed aggregate는 tag 없는 series를 유효한 표본으로 계산하지 않는다', () => {
  const summary = aggregateSummary();
  for (const name of Object.keys(summary.metrics)) {
    if (name.startsWith('room_mixed_')) {
      delete summary.metrics[name];
    }
  }
  summary.metrics.room_mixed_requests = metric(4);
  summary.metrics.room_mixed_request_duration = metric(4);

  const aggregate = buildMixedAggregate(summary, profile());

  assert.equal(aggregate.status, 'INVALID');
  assert.ok(aggregate.invalidReasons.some((reason) => reason.includes('tag schema')));
});

test('mixed의 필수 arrival artifact가 없으면 fixture 사후 판정도 INVALID다', () => {
  const plan = createFixturePlan(profile({ scenario: 'mixed', runId: 'mixed-invalid-arrival-artifact' }));
  const fixture = hydrateFixture(plan, fixtureResources(plan), 'a'.repeat(32));
  const snapshot = initialSnapshot(fixture);
  fixture.baselineSnapshot = structuredClone(snapshot);

  const result = evaluateFixture(fixture, snapshot, 'after', { metrics: {} });

  assert.equal(result.status, 'INVALID');
  assert.ok(result.failures.some((failure) => failure.includes('mixed aggregate')));
});

test('mixed fixture는 snapshot 참여·대기 사용자의 write/read partition 격리를 확인한다', () => {
  const plan = createFixturePlan(profile({ scenario: 'mixed', runId: 'mixed-snapshot-partition-identity' }));
  const fixture = hydrateFixture(plan, fixtureResources(plan), 'a'.repeat(32));
  const snapshot = initialSnapshot(fixture);
  fixture.baselineSnapshot = structuredClone(snapshot);

  const writeRoomIds = new Set(
    fixture.fixturePartitions.write.roomKeys.map((roomKey) => fixture.rooms[roomKey].id),
  );
  const readUserId = fixture.users[fixture.fixturePartitions.read.userKeys[0]].id;
  const participation = snapshot.participations.find((entry) => writeRoomIds.has(entry.roomId));
  const waitlist = snapshot.waitlists.find((entry) => writeRoomIds.has(entry.roomId));
  assert.ok(participation);
  assert.ok(waitlist);
  participation.userId = readUserId;
  waitlist.userId = readUserId;

  const before = evaluateFixture(fixture, snapshot, 'before');
  assert.equal(before.status, 'INVALID');
  assert.ok(before.failures.some((failure) => failure.includes('snapshot participation')));
  assert.ok(before.failures.some((failure) => failure.includes('snapshot waitlist')));

  const after = evaluateFixture(fixture, snapshot, 'after', aggregateSummary());
  assert.equal(after.status, 'FAIL');
  assert.ok(after.failures.some((failure) => failure.includes('snapshot participation')));
  assert.ok(after.failures.some((failure) => failure.includes('snapshot waitlist')));
});

test('mixed fixture는 key뿐 아니라 hydrate된 DB ROOM·사용자 ID까지 write/read 격리를 확인한다', () => {
  const plan = createFixturePlan(profile({ scenario: 'mixed', runId: 'mixed-invalid-partition-identity' }));
  const fixture = hydrateFixture(plan, fixtureResources(plan), 'a'.repeat(32));
  const writeRoomKey = fixture.fixturePartitions.write.roomKeys[0];
  const readRoomKey = fixture.fixturePartitions.read.roomKeys[0];
  const writeUserKey = fixture.fixturePartitions.write.userKeys[0];
  const readUserKey = fixture.fixturePartitions.read.userKeys[0];
  fixture.rooms[readRoomKey].id = fixture.rooms[writeRoomKey].id;
  fixture.users[readUserKey].id = fixture.users[writeUserKey].id;
  const snapshot = initialSnapshot(fixture);

  const result = evaluateFixture(fixture, snapshot, 'before');

  assert.equal(result.status, 'INVALID');
  assert.ok(result.failures.some((failure) => failure.includes('같은 DB ROOM ID')));
  assert.ok(result.failures.some((failure) => failure.includes('같은 DB 사용자 ID')));
});

test('유효한 mixed fixture의 사후 write/read DB ID 격리 위반은 FAIL이다', () => {
  const plan = createFixturePlan(profile({ scenario: 'mixed', runId: 'mixed-after-partition-identity' }));
  const fixture = hydrateFixture(plan, fixtureResources(plan), 'a'.repeat(32));
  const snapshot = initialSnapshot(fixture);
  fixture.baselineSnapshot = structuredClone(snapshot);
  const writeRoomKey = fixture.fixturePartitions.write.roomKeys[0];
  const readRoomKey = fixture.fixturePartitions.read.roomKeys[0];
  fixture.rooms[readRoomKey].id = fixture.rooms[writeRoomKey].id;

  const result = evaluateFixture(fixture, snapshot, 'after', aggregateSummary());

  assert.equal(result.status, 'FAIL');
  assert.ok(result.failures.some((failure) => failure.includes('같은 DB ROOM ID')));
});

test('mixed fixture plan과 portable bundle은 같은 options·selection digest·격리 partition을 보존한다', () => {
  const runId = `mixed-portable-${process.pid}-${Date.now()}`;
  const input = profile({ runId, scenario: 'mixed' });
  const plan = createFixturePlan(input);
  const writeRooms = new Set(plan.fixturePartitions.write.roomKeys);
  const readRooms = new Set(plan.fixturePartitions.read.roomKeys);
  const writeUsers = new Set(plan.fixturePartitions.write.userKeys);
  const readUsers = new Set(plan.fixturePartitions.read.userKeys);
  const root = mkdtempSync(path.join(os.tmpdir(), 'room-mixed-options-test-'));
  const context = {
    repositoryRoot,
    scenarioDirectory: path.join(repositoryRoot, 'load-tests', 'k6', 'jiwon'),
    buildRoot: path.join(root, 'build', 'k6', 'room'),
    bundleRoot: null,
    isBundleRuntime: false,
    environment: {
      ROOM_K6_FIXTURE_PASSWORD_HASH: '{bcrypt}$2a$10$room-mixed-options-test',
    },
  };

  try {
    assert.ok([...writeRooms].every((key) => !readRooms.has(key)));
    assert.ok([...writeUsers].every((key) => !readUsers.has(key)));
    assert.ok(plan.targets.every((target) => (
      target.operation === 't5'
        ? readRooms.has(target.roomKey) && readUsers.has(target.actorKey)
        : writeRooms.has(target.roomKey) && writeUsers.has(target.actorKey)
    )));

    const rendered = renderBundle(input, context, {
      sourceRevision: 'a'.repeat(40),
      sourceDirty: false,
    });
    const validated = validateBundle(rendered.bundlePath, context, { forExecution: true });
    const manifest = JSON.parse(readFileSync(path.join(rendered.bundlePath, 'manifest.json'), 'utf8'));
    const execution = bundleExecutionOptions(rendered.bundlePath, context);

    assert.equal(validated.fixtureId, plan.fixtureId);
    assert.deepEqual(manifest.options, plan.options);
    assert.equal(execution.mixedProfile.selectionPlanDigest, plan.mixedProfile.selectionPlanDigest);
    assert.deepEqual(execution.mixedProfile.options, plan.mixedProfile.options);
    assert.equal(execution.mixedProfile.options.arrivalRate, input.arrivalRate);
    assert.equal(execution.mixedProfile.options.durationSeconds, input.durationSeconds);
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
});
