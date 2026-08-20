import execution from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

import {
  classifyT1Cancel,
  classifyT2Waitlist,
  classifyT5Detail,
  evaluateResponse,
  getRoomDetail,
  loadRuntime,
  requestEmpty,
  scenarioTags,
  sessionFor,
  writeSetup,
} from './lib/room-k6.js';
import { outcomeDurationThresholds } from './lib/write-options.mjs';

const runtime = loadRuntime('mixed');
const profile = runtime.fixture.mixedProfile;
if (!profile?.selectionPlanDigest || !profile.selectionCounts) {
  throw new Error('mixed fixture의 selection profile이 없습니다. 새 fixture를 사용하세요.');
}

const mixedRequests = new Counter('room_mixed_requests');
const mixedRequestDuration = new Trend('room_mixed_request_duration', true);
const MIXED_TIERS = ['hot', 'spread'];
const MIXED_OPERATIONS = ['t1', 't2', 't5'];
const MIXED_OUTCOMES = ['success', 'business', 'concurrency', 'unexpected'];

function constantArrivalOptions() {
  const options = runtime.fixture.options;
  return {
    executor: 'constant-arrival-rate',
    rate: options.arrivalRate,
    timeUnit: options.arrivalTimeUnit,
    duration: `${options.durationSeconds}s`,
    preAllocatedVUs: options.preAllocatedVUs,
    maxVUs: options.maxVUs,
  };
}

function mixedAggregateThresholds() {
  const thresholds = {};
  for (const tier of MIXED_TIERS) {
    for (const operation of MIXED_OPERATIONS) {
      for (const outcome of MIXED_OUTCOMES) {
        const tags = `tier:${tier},operation:${operation},outcome:${outcome}`;
        thresholds[`room_mixed_requests{${tags}}`] = ['count>=0'];
        thresholds[`room_mixed_request_duration{${tags}}`] = ['p(99)>=0'];
      }
    }
  }
  return thresholds;
}

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    room_mixed: constantArrivalOptions(),
  },
  thresholds: {
    ...outcomeDurationThresholds(),
    ...mixedAggregateThresholds(),
    room_contract_failures: ['count==0'],
    room_unexpected_4xx: ['count==0'],
    room_server_failures: ['count==0'],
  },
};

export function setup() {
  const prepared = writeSetup(runtime);
  return {
    sessions: prepared.sessions,
  };
}

function targetForExecutedArrival(actualArrivalIndex) {
  const target = runtime.fixture.targets[actualArrivalIndex];
  if (!target) {
    throw new Error(`mixed constant-arrival-rate 실행 target을 찾지 못했습니다: ${actualArrivalIndex}`);
  }
  return target;
}

function recordMixedAggregate(response, result, target) {
  const outcome = result.contract ? result.category : 'unexpected';
  const tags = {
    tier: target.tier,
    operation: target.operation,
    outcome,
  };
  mixedRequests.add(1, tags);
  mixedRequestDuration.add(response.timings.duration, tags);
}

function executeT1(client, target, room, tags) {
  const response = requestEmpty(
    client,
    runtime,
    'DELETE',
    `/api/rooms/${room.id}/participants/me`,
    tags,
  );
  const result = evaluateResponse(
    response,
    (actual, value) => classifyT1Cancel(actual, value, room),
    tags,
    'mixed T1 cancel participation',
  );
  recordMixedAggregate(response, result, target);
}

function executeT2(client, target, room, tags) {
  const response = requestEmpty(client, runtime, 'POST', `/api/rooms/${room.id}/waitlist`, tags);
  const result = evaluateResponse(
    response,
    (actual, value) => classifyT2Waitlist(actual, value, room.id, false, null),
    tags,
    'mixed T2 waitlist registration',
  );
  recordMixedAggregate(response, result, target);
}

function executeT5(client, target, room, tags) {
  const response = getRoomDetail(client, runtime, room.id, tags);
  const result = evaluateResponse(
    response,
    (actual, value) => classifyT5Detail(actual, value, runtime.fixture, target),
    tags,
    'mixed T5 room detail',
  );
  recordMixedAggregate(response, result, target);
}

export default function (window) {
  // dropped iteration은 이 함수에 진입하지 않는다. iterationInTest는 scheduled slot이 아니라 실제 실행 순번이다.
  const actualArrivalIndex = execution.scenario.iterationInTest;
  const target = targetForExecutedArrival(actualArrivalIndex);
  const room = runtime.fixture.rooms[target.roomKey];
  if (!room) {
    throw new Error(`mixed target ROOM을 찾지 못했습니다: ${target.roomKey}`);
  }
  const client = sessionFor(runtime, window.sessions, target.actorKey);
  const tags = scenarioTags(runtime, target, {
    phase: 'measurement',
    operation: target.operation,
    tier: target.tier,
    fixture_partition: target.fixture,
    actual_arrival_index: String(actualArrivalIndex),
    execution_model: 'constant-arrival-rate',
  });

  if (target.operation === 't1') {
    executeT1(client, target, room, tags);
    return;
  }
  if (target.operation === 't2') {
    executeT2(client, target, room, tags);
    return;
  }
  if (target.operation === 't5') {
    executeT5(client, target, room, tags);
    return;
  }
  throw new Error(`지원하지 않는 mixed operation: ${target.operation}`);
}
