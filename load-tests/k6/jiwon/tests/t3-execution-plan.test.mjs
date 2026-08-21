import assert from 'node:assert/strict';
import test from 'node:test';

import { createFixturePlan, hydrateFixture } from '../tools/fixture-model.mjs';
import {
  t3ExecutionAssignment,
  t3ExecutionPlan,
  t3SequentialRequestOrder,
} from '../lib/t3-execution-plan.mjs';
import { outcomeDurationThresholds, writeOptions } from '../lib/write-options.mjs';

const PREPARE_OWNERSHIP = 'a'.repeat(32);

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

function fixtureFor(values) {
  const plan = createFixturePlan(values);
  return hydrateFixture(plan, resourcesFor(plan), PREPARE_OWNERSHIP);
}

test('T3 race는 각 독립 ROOM pair를 같은 barrier에 한 번씩 배치한다', () => {
  const fixture = fixtureFor({
    scenario: 't3',
    runId: 't3-race-parallel',
    profile: 'stress',
    t3Mode: 'race',
  });

  assert.deepEqual(t3ExecutionPlan(fixture), { vus: 10, iterations: 1 });

  const assignments = Array.from({ length: 10 }, (_, index) => (
    t3ExecutionAssignment(fixture, index + 1, 0)
  ));
  assert.deepEqual(assignments.map((assignment) => assignment.role), [
    'wait', 'cancel', 'wait', 'cancel', 'wait', 'cancel', 'wait', 'cancel', 'wait', 'cancel',
  ]);
  assert.deepEqual(assignments.map((assignment) => assignment.barrierRound), [0, 0, 0, 0, 0, 0, 0, 0, 0, 0]);
  assert.deepEqual(assignments.map((assignment) => assignment.target.round), [0, 0, 1, 1, 2, 2, 3, 3, 4, 4]);
  assert.deepEqual(
    assignments.filter((_, index) => index % 2 === 0).map((assignment) => assignment.target.roomKey),
    assignments.filter((_, index) => index % 2 === 1).map((assignment) => assignment.target.roomKey),
  );
  assert.equal(new Set(assignments.map((assignment) => assignment.target.roomKey)).size, 5);
});

test('T3 순차 검증은 하나의 VU가 round 순서대로 같은 요청 쌍을 실행한다', () => {
  const fixture = fixtureFor({
    scenario: 't3',
    runId: 't3-wait-first-sequence',
    profile: 'stress',
    t3Mode: 'wait-first',
  });

  assert.deepEqual(t3ExecutionPlan(fixture), { vus: 1, iterations: 5 });
  const assignments = Array.from({ length: 5 }, (_, round) => (
    t3ExecutionAssignment(fixture, 1, round)
  ));
  assert.deepEqual(assignments.map((assignment) => assignment.role), [
    'sequence', 'sequence', 'sequence', 'sequence', 'sequence',
  ]);
  assert.deepEqual(assignments.map((assignment) => assignment.barrierRound), [0, 1, 2, 3, 4]);
  assert.equal(new Set(assignments.map((assignment) => assignment.target.roomKey)).size, 5);
});

test('T3 순차 검증은 mode별로 정해진 요청 순서를 유지한다', () => {
  const waitFirst = fixtureFor({
    scenario: 't3',
    runId: 't3-wait-first-order',
    profile: 'stress',
    t3Mode: 'wait-first',
  });
  const cancelFirst = fixtureFor({
    scenario: 't3',
    runId: 't3-cancel-first-order',
    profile: 'stress',
    t3Mode: 'cancel-first',
  });

  assert.deepEqual(t3SequentialRequestOrder(waitFirst), ['wait', 'cancel']);
  assert.deepEqual(t3SequentialRequestOrder(cancelFirst), ['cancel', 'wait']);
});

test('T3 실행 계획은 병렬과 순차 mode별 maxDuration을 충분히 계산한다', () => {
  const race = fixtureFor({
    scenario: 't3',
    runId: 't3-race-duration',
    profile: 'stress',
    t3Mode: 'race',
  });
  const sequence = fixtureFor({
    scenario: 't3',
    runId: 't3-sequence-duration',
    profile: 'stress',
    t3Mode: 'cancel-first',
  });

  const runtimeFor = (fixture) => ({
    fixture,
    sessionWarmupSeconds: 15,
    roundIntervalSeconds: 20,
  });
  const racePlan = t3ExecutionPlan(race);
  const sequencePlan = t3ExecutionPlan(sequence);

  assert.deepEqual(writeOptions(runtimeFor(race), racePlan.vus, racePlan.iterations).scenarios.room_write, {
    executor: 'per-vu-iterations',
    vus: 10,
    iterations: 1,
    maxDuration: '65s',
  });
  assert.deepEqual(writeOptions(runtimeFor(sequence), sequencePlan.vus, sequencePlan.iterations).scenarios.room_write, {
    executor: 'per-vu-iterations',
    vus: 1,
    iterations: 5,
    maxDuration: '145s',
  });
});

test('쓰기 옵션은 네 outcome duration submetric을 threshold로 생성한다', () => {
  const expectedThresholds = Object.fromEntries(
    ['success', 'business', 'concurrency', 'unexpected']
      .map((category) => [`room_request_duration{outcome:${category}}`, ['p(99)>=0']]),
  );

  assert.deepEqual(outcomeDurationThresholds(), expectedThresholds);

  const options = writeOptions({
    fixture: fixtureFor({
      scenario: 't1',
      runId: 't1-outcome-thresholds',
      profile: 'spike',
      mode: 'hot',
      concurrency: 2,
    }),
    sessionWarmupSeconds: 15,
    roundIntervalSeconds: 20,
  }, 2, 1);

  assert.deepEqual(
    Object.fromEntries(
      Object.entries(options.thresholds)
        .filter(([name]) => name.startsWith('room_request_duration{outcome:')),
    ),
    expectedThresholds,
  );
});

test('쓰기 실행은 barrier 시작 편차가 1초를 넘으면 실패한다', () => {
  const fixture = fixtureFor({
    scenario: 't1',
    runId: 't1-start-skew-threshold',
    profile: 'spike',
    mode: 'hot',
    concurrency: 2,
  });
  const options = writeOptions({
    fixture,
    sessionWarmupSeconds: 15,
    roundIntervalSeconds: 20,
  }, 2, 1);

  assert.deepEqual(options.thresholds.room_start_skew_ms, ['max<1000']);
});

test('쓰기 실행은 dropped iteration이 있으면 실패한다', () => {
  const options = writeOptions({
    fixture: fixtureFor({
      scenario: 't1',
      runId: 't1-dropped-iterations-threshold',
      profile: 'stress',
      mode: 'hot',
      concurrency: 10,
    }),
    sessionWarmupSeconds: 15,
    roundIntervalSeconds: 20,
  });

  assert.deepEqual(options.thresholds.dropped_iterations, ['count==0']);
});
