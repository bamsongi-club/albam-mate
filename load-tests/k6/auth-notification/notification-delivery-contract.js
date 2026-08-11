import { check, fail, sleep } from 'k6';
import { Counter, Rate } from 'k6/metrics';

import {
  RUN_ID,
  cancelParticipation,
  createClient,
  createRoom,
  integerEnv,
  joinRoom,
  listNotifications,
  loginFixture,
  responseCode,
  responseData,
} from './lib/albam.js';

const EVENT_COUNT = integerEnv('NOTIFICATION_CONTRACT_EVENT_COUNT', 10, 2, 100);
const POLL_INTERVAL_MS = integerEnv('NOTIFICATION_CONTRACT_POLL_INTERVAL_MS', 1000, 100, 10000);
const TIMEOUT_SECONDS = integerEnv('NOTIFICATION_CONTRACT_TIMEOUT_SECONDS', 120, 30, 600);

export const options = {
  setupTimeout: '2m',
  scenarios: {
    notification_delivery_contract: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: `${TIMEOUT_SECONDS + 180}s`,
    },
  },
  thresholds: {
    contract_failures: ['rate==0'],
    notification_samples: [`count==${EVENT_COUNT}`],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const contractFailures = new Rate('contract_failures');
const notificationSamples = new Counter('notification_samples');
const EXPECTED_NOTIFICATION_TYPES = new Set(['PARTICIPANT_JOINED', 'PARTICIPANT_CANCELED']);

function record(response, label, accepted) {
  check(response, { [label]: () => accepted });
  contractFailures.add(!accepted, { scenario: 'notification-delivery-contract' });
  if (!accepted) {
    console.error(`${label}: status=${response ? response.status : 'none'} code=${response ? responseCode(response) || 'none' : 'none'}`);
  }
  return accepted;
}

function loginOrFail(client, fixtureIndex, role) {
  const result = loginFixture(client, fixtureIndex, { actor_role: role });
  if (!record(result.csrf, `${role} CSRF 조회가 성공한다`, result.csrf.status === 200)
    || !result.login
    || !record(result.login, `${role} 로그인이 성공한다`, result.login.status === 200)) {
    fail(`${role} fixture 로그인에 실패했습니다.`);
  }
}

export function setup() {
  const host = createClient();
  const result = loginFixture(host, 1, { actor_role: 'setup-host' });
  if (result.csrf.status !== 200 || !result.login || result.login.status !== 200) {
    throw new Error(`알림 fixture 주최자 로그인 실패: csrf=${result.csrf.status} login=${result.login ? result.login.status : 'none'}`);
  }
  const response = createRoom(host, `k6-notification-${RUN_ID}`, { actor_role: 'setup-host' });
  const data = responseData(response);
  if (response.status !== 201 || !data || !data.id) {
    throw new Error(`알림 fixture 방 생성 실패: status=${response.status} code=${responseCode(response) || 'none'}`);
  }
  return { roomId: data.id, roomTitle: data.title };
}

function fetchRoomNotifications(client, roomId) {
  const items = [];
  for (let page = 0; page < 20; page += 1) {
    const response = listNotifications(client, page, 100, { actor_role: 'host' });
    if (!record(response, `알림 목록 ${page}페이지 조회가 성공한다`, response.status === 200)) {
      return null;
    }
    const data = responseData(response);
    if (!data || !Array.isArray(data.content)) {
      record(response, `알림 목록 ${page}페이지 응답 형식이 맞다`, false);
      return null;
    }
    for (const item of data.content) {
      if (String(item.roomId) === String(roomId)) {
        items.push(item);
      }
    }
    if (!data.hasNext) {
      return items;
    }
  }
  record(null, '알림 페이지가 20페이지 안에 끝난다', false);
  return null;
}

function observeNewNotifications(items, seenIds) {
  const newItems = items
    .filter((item) => !seenIds.has(String(item.id)))
    .sort((left, right) => Number(left.id) - Number(right.id));
  for (const item of newItems) {
    const id = String(item.id);
    seenIds.add(id);
    if (!EXPECTED_NOTIFICATION_TYPES.has(item.type)) {
      record(null, `예상하지 않은 알림 ${item.type}이 없다`, false);
      continue;
    }
    notificationSamples.add(1, { notification_type: item.type });
  }
}

export default function (fixture) {
  const host = createClient();
  const participant = createClient();
  loginOrFail(host, 1, 'host');
  loginOrFail(participant, 2, 'participant');

  for (let index = 0; index < EVENT_COUNT; index += 1) {
    const joined = index % 2 === 0;
    const response = joined
      ? joinRoom(participant, fixture.roomId, { event_type: 'PARTICIPANT_JOINED' })
      : cancelParticipation(participant, fixture.roomId, { event_type: 'PARTICIPANT_CANCELED' });
    const expectedStatus = joined ? 201 : 200;
    const type = joined ? 'PARTICIPANT_JOINED' : 'PARTICIPANT_CANCELED';
    if (!record(response, `${type} 이벤트 생성이 성공한다`, response.status === expectedStatus)) {
      return;
    }
  }

  const seenIds = new Set();
  const deadline = Date.now() + TIMEOUT_SECONDS * 1000;
  while (seenIds.size < EVENT_COUNT && Date.now() < deadline) {
    const items = fetchRoomNotifications(host, fixture.roomId);
    if (items === null) {
      return;
    }
    observeNewNotifications(items, seenIds);
    if (seenIds.size < EVENT_COUNT) {
      sleep(POLL_INTERVAL_MS / 1000);
    }
  }

  // 다음 relay 주기까지 기다린 뒤 예상 건수보다 더 생성된 중복 알림도 확인한다.
  sleep(6);
  const finalItems = fetchRoomNotifications(host, fixture.roomId);
  if (finalItems === null) {
    return;
  }
  observeNewNotifications(finalItems, seenIds);

  const uniqueIds = new Set(finalItems.map((item) => String(item.id)));
  const joinedCount = finalItems.filter((item) => item.type === 'PARTICIPANT_JOINED').length;
  const canceledCount = finalItems.filter((item) => item.type === 'PARTICIPANT_CANCELED').length;
  const expectedJoined = Math.ceil(EVENT_COUNT / 2);
  const expectedCanceled = Math.floor(EVENT_COUNT / 2);

  record(null, '기대한 알림을 제한 시간 안에 모두 관찰한다', seenIds.size === EVENT_COUNT);
  record(null, '알림 ID 중복이 없다', uniqueIds.size === finalItems.length);
  record(null, '방 알림 총수가 이벤트 수와 같다', finalItems.length === EVENT_COUNT);
  record(null, '참가 알림 수가 기대값과 같다', joinedCount === expectedJoined);
  record(null, '취소 알림 수가 기대값과 같다', canceledCount === expectedCanceled);
}
