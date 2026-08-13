import {
  classifyT3Cancel,
  classifyT3Waitlist,
  evaluateResponse,
  loadRuntime,
  recordStartSkew,
  requestEmpty,
  scenarioTags,
  sessionFor,
  waitFor,
  writeOptions,
  writeSetup,
} from './lib/room-k6.js';
import execution from 'k6/execution';
import {
  t3ExecutionAssignment,
  t3ExecutionPlan,
  t3SequentialRequestOrder,
} from './lib/t3-execution-plan.mjs';

const runtime = loadRuntime('t3');
const executionPlan = t3ExecutionPlan(runtime.fixture);
const sequentialRequestOrder = t3SequentialRequestOrder(runtime.fixture);

export const options = writeOptions(runtime, executionPlan.vus, executionPlan.iterations);

export function setup() {
  return writeSetup(runtime);
}

function waitlistRequest(sessions, target, room, round, barrierAt = null) {
  const client = sessionFor(runtime, sessions, target.waitKey);
  const tags = scenarioTags(runtime, target, {
    phase: 'measurement',
    operation: 'waitlist-register',
    round: String(round),
  });
  if (barrierAt !== null) {
    recordStartSkew(barrierAt, tags);
  }
  const response = requestEmpty(client, runtime, 'POST', `/api/rooms/${room.id}/waitlist`, tags);
  evaluateResponse(
    response,
    (actual, value) => classifyT3Waitlist(actual, value, room.id),
    tags,
    'T3 waitlist registration',
  );
}

function cancelRequest(sessions, target, room, round, barrierAt = null) {
  const client = sessionFor(runtime, sessions, target.cancelKey);
  const tags = scenarioTags(runtime, target, {
    phase: 'measurement',
    operation: 'cancel-participation',
    round: String(round),
  });
  if (barrierAt !== null) {
    recordStartSkew(barrierAt, tags);
  }
  const response = requestEmpty(client, runtime, 'DELETE', `/api/rooms/${room.id}/participants/me`, tags);
  evaluateResponse(
    response,
    (actual, value) => classifyT3Cancel(actual, value, room, runtime.fixture.options.t3Mode),
    tags,
    'T3 cancel participation',
  );
}

export default function (barrier) {
  const assignment = t3ExecutionAssignment(
    runtime.fixture,
    execution.vu.idInTest,
    execution.vu.iterationInScenario,
  );
  const target = assignment.target;
  const room = runtime.fixture.rooms[target.roomKey];

  if (sequentialRequestOrder) {
    for (const operation of sequentialRequestOrder) {
      const userKey = operation === 'wait' ? target.waitKey : target.cancelKey;
      sessionFor(runtime, barrier.sessions, userKey);
    }
    const barrierAt = barrier.firstBarrierAt + (assignment.barrierRound * barrier.roundIntervalMilliseconds);
    waitFor(barrierAt);
    for (let index = 0; index < sequentialRequestOrder.length; index += 1) {
      const operation = sequentialRequestOrder[index];
      const requestBarrierAt = index === 0 ? barrierAt : null;
      if (operation === 'wait') {
        waitlistRequest(barrier.sessions, target, room, target.round, requestBarrierAt);
      } else {
        cancelRequest(barrier.sessions, target, room, target.round, requestBarrierAt);
      }
    }
    return;
  }

  const barrierAt = barrier.firstBarrierAt + (assignment.barrierRound * barrier.roundIntervalMilliseconds);
  if (assignment.role === 'wait') {
    sessionFor(runtime, barrier.sessions, target.waitKey);
    waitFor(barrierAt);
    waitlistRequest(barrier.sessions, target, room, target.round, barrierAt);
    return;
  }

  sessionFor(runtime, barrier.sessions, target.cancelKey);
  waitFor(barrierAt);
  cancelRequest(barrier.sessions, target, room, target.round, barrierAt);
}
