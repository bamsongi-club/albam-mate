import {
  classifyT3Cancel,
  classifyT3Waitlist,
  evaluateResponse,
  loadRuntime,
  recordStartSkew,
  requestEmpty,
  scenarioTags,
  sessionFor,
  targetForRound,
  waitFor,
  writeOptions,
  writeSetup,
} from './lib/room-k6.js';
import execution from 'k6/execution';

const runtime = loadRuntime('t3');
const t3Mode = runtime.fixture.options.t3Mode;

export const options = writeOptions(runtime, t3Mode === 'race' ? 2 : 1);

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
  evaluateResponse(response, classifyT3Waitlist, tags, 'T3 waitlist registration');
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
  evaluateResponse(response, classifyT3Cancel, tags, 'T3 cancel participation');
}

export default function (barrier) {
  const round = execution.vu.iterationInScenario;
  const target = targetForRound(runtime.fixture, round);
  const room = runtime.fixture.rooms[target.roomKey];

  if (t3Mode === 'wait-first') {
    sessionFor(runtime, barrier.sessions, target.waitKey);
    sessionFor(runtime, barrier.sessions, target.cancelKey);
    waitFor(barrier.firstBarrierAt + (round * barrier.roundIntervalMilliseconds));
    waitlistRequest(barrier.sessions, target, room, round, barrier.firstBarrierAt + (round * barrier.roundIntervalMilliseconds));
    cancelRequest(barrier.sessions, target, room, round);
    return;
  }

  if (t3Mode === 'cancel-first') {
    sessionFor(runtime, barrier.sessions, target.cancelKey);
    sessionFor(runtime, barrier.sessions, target.waitKey);
    waitFor(barrier.firstBarrierAt + (round * barrier.roundIntervalMilliseconds));
    cancelRequest(barrier.sessions, target, room, round, barrier.firstBarrierAt + (round * barrier.roundIntervalMilliseconds));
    waitlistRequest(barrier.sessions, target, room, round);
    return;
  }

  const barrierAt = barrier.firstBarrierAt + (round * barrier.roundIntervalMilliseconds);
  if (execution.vu.idInTest === 1) {
    sessionFor(runtime, barrier.sessions, target.waitKey);
    waitFor(barrierAt);
    waitlistRequest(barrier.sessions, target, room, round, barrierAt);
    return;
  }

  sessionFor(runtime, barrier.sessions, target.cancelKey);
  waitFor(barrierAt);
  cancelRequest(barrier.sessions, target, room, round, barrierAt);
}
