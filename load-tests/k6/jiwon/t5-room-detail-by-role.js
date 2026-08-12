import {
  classifyT5Detail,
  evaluateResponse,
  getRoomDetail,
  loadRuntime,
  readOptions,
  readSetup,
  scenarioTags,
  sessionFor,
  waitFor,
} from './lib/room-k6.js';

const runtime = loadRuntime('t5');
const target = runtime.fixture.targets[0];
const room = runtime.fixture.rooms[target.roomKey];

export const options = readOptions(runtime);

export function setup() {
  return readSetup(runtime);
}

export default function (window) {
  const client = sessionFor(runtime, window.sessions, target.actorKey);
  if (Date.now() < window.firstBarrierAt) {
    const warmupResponse = getRoomDetail(
      client,
      runtime,
      room.id,
      scenarioTags(runtime, target, { phase: 'warmup', operation: 'room-detail' }),
    );
    if (warmupResponse.status !== 200) {
      throw new Error(`T5 warm-up 상세 조회가 실패했습니다(status=${warmupResponse.status}).`);
    }
  }

  waitFor(window.firstBarrierAt);
  if (Date.now() >= window.measurementEndsAt) {
    throw new Error('T5 측정 시작 전에 측정 창이 끝났습니다. runner 부하와 시간 설정을 확인하세요.');
  }
  while (Date.now() < window.measurementEndsAt) {
    const tags = scenarioTags(runtime, target, { phase: 'measurement', operation: 'room-detail' });
    const response = getRoomDetail(client, runtime, room.id, tags);
    evaluateResponse(
      response,
      (actual, value) => classifyT5Detail(actual, value, runtime.fixture, target),
      tags,
      'T5 room detail',
    );
    if (runtime.readThinkTimeMilliseconds > 0) {
      waitFor(Date.now() + runtime.readThinkTimeMilliseconds);
    }
  }
}
