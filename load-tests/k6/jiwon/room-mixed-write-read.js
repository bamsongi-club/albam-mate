import execution from 'k6/execution';
import { Counter, Trend } from 'k6/metrics';

import {
  classifyT1Cancel,
  classifyT2Waitlist,
  classifyT5Detail,
  evaluateResponse,
  getRoomDetail,
  loadRuntime,
  recordStartSkew,
  requestEmpty,
  scenarioTags,
  sessionFor,
  writeSetup,
} from './lib/room-k6.js';
import { START_SKEW_THRESHOLD } from './lib/start-skew.mjs';
import { outcomeDurationThresholds } from './lib/write-options.mjs';

const runtime = loadRuntime('mixed');
const profile = runtime.fixture.mixedProfile;
if (!profile?.selectionPlanDigest || !profile.selectionCounts) {
  throw new Error('mixed fixture의 selection profile이 없습니다. 새 fixture를 사용하세요.');
}

const mixedRequests = new Counter('room_mixed_requests');
const mixedRequestDuration = new Trend('room_mixed_request_duration', true);

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

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    room_mixed: constantArrivalOptions(),
  },
  thresholds: {
    ...outcomeDurationThresholds(),
    room_contract_failures: ['count==0'],
    room_unexpected_4xx: ['count==0'],
    room_server_failures: ['count==0'],
    room_start_skew_ms: [START_SKEW_THRESHOLD],
  },
};

export function setup() {
  const prepared = writeSetup(runtime);
  return {
    sessions: prepared.sessions,
    firstArrivalAt: Date.now(),
  };
}

function targetForArrival(arrivalIndex) {
  const target = runtime.fixture.targets[arrivalIndex];
  if (!target || target.arrivalIndex !== arrivalIndex) {
    throw new Error(`mixed constant-arrival-rate target을 찾지 못했습니다: ${arrivalIndex}`);
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
  const arrivalIndex = execution.scenario.iterationInTest;
  const target = targetForArrival(arrivalIndex);
  const room = runtime.fixture.rooms[target.roomKey];
  if (!room) {
    throw new Error(`mixed target ROOM을 찾지 못했습니다: ${target.roomKey}`);
  }
  const client = sessionFor(runtime, window.sessions, target.actorKey);
  const plannedArrivalAt = window.firstArrivalAt
    + Math.floor((arrivalIndex * 1_000) / runtime.fixture.options.arrivalRate);
  const tags = scenarioTags(runtime, target, {
    phase: 'measurement',
    operation: target.operation,
    tier: target.tier,
    fixture_partition: target.fixture,
    arrival_index: String(arrivalIndex),
    execution_model: 'constant-arrival-rate',
  });
  recordStartSkew(plannedArrivalAt, tags);

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
