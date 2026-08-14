import {
  classifyT1Cancel,
  evaluateResponse,
  loadRuntime,
  recordStartSkew,
  requestEmpty,
  scenarioTags,
  sessionFor,
  targetForRoundAndSlot,
  waitFor,
  writeOptions,
  writeSetup,
} from './lib/room-k6.js';
import execution from 'k6/execution';

const runtime = loadRuntime('t1');

export const options = writeOptions(runtime, runtime.fixture.options.concurrency);

export function setup() {
  return writeSetup(runtime);
}

export default function (barrier) {
  const round = execution.vu.iterationInScenario;
  const target = targetForRoundAndSlot(runtime.fixture, round, execution.vu.idInTest - 1);
  const room = runtime.fixture.rooms[target.roomKey];
  const client = sessionFor(runtime, barrier.sessions, target.actorKey);
  const barrierAt = barrier.firstBarrierAt + (round * barrier.roundIntervalMilliseconds);
  waitFor(barrierAt);

  const tags = scenarioTags(runtime, target, {
    phase: 'measurement',
    operation: 'cancel-participation',
    round: String(round),
  });
  recordStartSkew(barrierAt, tags);
  const response = requestEmpty(
    client,
    runtime,
    'DELETE',
    `/api/rooms/${room.id}/participants/me`,
    tags,
  );
  evaluateResponse(
    response,
    (actual, value) => classifyT1Cancel(actual, value, room),
    tags,
    'T1 cancel participation',
  );
}
