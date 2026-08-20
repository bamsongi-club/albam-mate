import execution from 'k6/execution';
import {
  classifyT1Cancel,
  classifyT2Waitlist,
  evaluateResponse,
  loadRuntime,
  recordStartSkew,
  recordWaitlistPosition,
  requestEmpty,
  scenarioTags,
  sessionFor,
  targetForRoundAndSlot,
  waitFor,
  writeSetup,
} from './lib/room-k6.js';

const contract = {"scenario":"t1","executionModel":"constant-arrival-rate","distribution":"mixed","concurrency":8,"rounds":60,"arrivalRate":8,"durationSeconds":60,"targetCount":480};
const runtime = loadRuntime(contract.scenario);
const allowExisting = false;
const expectedPosition = null;
const outcomeCategories = ['success', 'business', 'concurrency', 'unexpected'];

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    room_write: contract.executionModel === 'barrier'
      ? {
        executor: 'per-vu-iterations',
        vus: contract.concurrency,
        iterations: contract.rounds,
        maxDuration: '900s',
      }
      : {
        executor: 'constant-arrival-rate',
        rate: contract.arrivalRate,
        timeUnit: '1s',
        duration: contract.durationSeconds + 's',
        preAllocatedVUs: Math.max(contract.concurrency * 2, contract.arrivalRate * 2),
        maxVUs: Math.max(contract.concurrency * 4, contract.arrivalRate * 4),
      },
  },
  thresholds: {
    ...Object.fromEntries(outcomeCategories.map((category) => [
      'room_request_duration{outcome:' + category + '}', ['p(99)>=0'],
    ])),
    room_contract_failures: ['count==0'],
    room_unexpected_4xx: ['count==0'],
    room_server_failures: ['count==0'],
    room_start_skew_ms: ['max<1000'],
  },
};

export function setup() {
  return writeSetup(runtime);
}

function targetForConstantArrival(index) {
  const target = runtime.fixture.targets[index];
  if (!target) {
    throw new Error('constant-arrival-rate iteration에 대응하는 fixture target이 없습니다: ' + index);
  }
  return target;
}

function measurementTarget(barrier) {
  if (contract.executionModel === 'barrier') {
    const round = execution.vu.iterationInScenario;
    return {
      target: targetForRoundAndSlot(runtime.fixture, round, execution.vu.idInTest - 1),
      round,
      index: (round * contract.concurrency) + execution.vu.idInTest - 1,
      barrierAt: barrier.firstBarrierAt + (round * barrier.roundIntervalMilliseconds),
    };
  }
  const index = execution.scenario.iterationInTest;
  const round = Math.floor(index / contract.concurrency);
  return {
    target: targetForConstantArrival(index),
    round,
    index,
    barrierAt: barrier.firstBarrierAt + (round * 1000),
  };
}

export default function (barrier) {
  const assignment = measurementTarget(barrier);
  const target = assignment.target;
  const room = runtime.fixture.rooms[target.roomKey];
  const client = sessionFor(runtime, barrier.sessions, target.actorKey);
  const tags = scenarioTags(runtime, target, {
    phase: 'measurement',
    operation: runtime.fixture.options.scenario === 't1' ? 'cancel-participation' : 'waitlist-register',
    round: String(assignment.round),
    arrival_index: String(assignment.index),
    distribution: target.distribution || contract.distribution,
    execution_model: contract.executionModel,
  });
  if (contract.executionModel === 'barrier') {
    waitFor(assignment.barrierAt);
  }
  recordStartSkew(assignment.barrierAt, tags);

  if (runtime.fixture.options.scenario === 't1') {
    const response = requestEmpty(
      client,
      runtime,
      'DELETE',
      '/api/rooms/' + room.id + '/participants/me',
      tags,
    );
    evaluateResponse(
      response,
      (actual, value) => classifyT1Cancel(actual, value, room),
      tags,
      'ROOM-LOCK-CMP T1 cancel participation',
    );
    return;
  }

  const response = requestEmpty(
    client,
    runtime,
    'POST',
    '/api/rooms/' + room.id + '/waitlist',
    tags,
  );
  const outcome = evaluateResponse(
    response,
    (actual, value) => classifyT2Waitlist(actual, value, room.id, allowExisting, expectedPosition),
    tags,
    'ROOM-LOCK-CMP T2 waitlist registration',
  );
  const position = outcome.contract && outcome.category === 'success'
    ? outcome.value.data.position
    : null;
  recordWaitlistPosition(position, tags);
}
