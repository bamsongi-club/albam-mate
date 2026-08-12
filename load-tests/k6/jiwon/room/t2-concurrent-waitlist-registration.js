import {
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
  writeOptions,
  writeSetup,
} from './lib/room-k6.js';
import execution from 'k6/execution';

const runtime = loadRuntime('t2');
const allowExisting = runtime.fixture.options.subcase === 'duplicate';

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
    operation: 'waitlist-register',
    round: String(round),
  });
  recordStartSkew(barrierAt, tags);
  const response = requestEmpty(client, runtime, 'POST', `/api/rooms/${room.id}/waitlist`, tags);
  const outcome = evaluateResponse(
    response,
    (actual, value) => classifyT2Waitlist(actual, value, allowExisting),
    tags,
    'T2 waitlist registration',
  );
  const position = outcome.contract && outcome.category === 'success'
    ? outcome.value.data.position
    : null;
  recordWaitlistPosition(position, tags);
}
