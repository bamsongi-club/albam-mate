import { check, sleep } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  RUN_ID,
  cancelRoom,
  createClient,
  createRoom,
  integerEnv,
  joinRoom,
  listNotifications,
  loginFixture,
  requireCapacityProfile,
  responseCode,
  responseData,
} from './lib/albam.js';

requireCapacityProfile();

const RECIPIENTS = integerEnv('NOTIFICATION_FANOUT_RECIPIENTS', 5, 1, 10);
const EVENT_COUNT = integerEnv('NOTIFICATION_FANOUT_EVENT_COUNT', 100, 1, 100);
const POLL_INTERVAL_MS = integerEnv('NOTIFICATION_FANOUT_POLL_INTERVAL_MS', 1000, 100, 10000);
const TIMEOUT_SECONDS = integerEnv('NOTIFICATION_FANOUT_TIMEOUT_SECONDS', 300, 30, 1200);
const FIXTURE_USER_COUNT = integerEnv('LOAD_TEST_USER_COUNT', 12, 2, 500);
const EXPECTED_SAMPLES = RECIPIENTS * EVENT_COUNT;
const FINAL_RELAY_OBSERVATION_SECONDS = 6;

if (FIXTURE_USER_COUNT < RECIPIENTS + 1) {
  throw new Error(`LOAD_TEST_USER_COUNT(${FIXTURE_USER_COUNT})는 주최자와 수신자 수(${RECIPIENTS + 1}) 이상이어야 합니다.`);
}

export const options = {
  scenarios: {
    notification_fanout_capacity: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: `${TIMEOUT_SECONDS * 2 + 600}s`,
    },
  },
  thresholds: {
    fanout_contract_failures: ['rate==0'],
    fanout_delivery_samples: [`count==${EXPECTED_SAMPLES}`],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const contractFailures = new Rate('fanout_contract_failures');
const deliverySamples = new Counter('fanout_delivery_samples');
const generationDuration = new Trend('fanout_event_generation_duration', true);
const observedDelay = new Trend('fanout_delivery_observed_delay', true);

function record(response, label, accepted) {
  check(response, { [label]: () => accepted });
  contractFailures.add(!accepted, {
    fanout_recipients: String(RECIPIENTS),
    fanout_events: String(EVENT_COUNT),
  });
  if (!accepted) {
    console.error(`${label}: status=${response ? response.status : 'none'} code=${response ? responseCode(response) || 'none' : 'none'}`);
  }
  return accepted;
}

function loginClient(fixtureIndex, role) {
  const client = createClient();
  const result = loginFixture(client, fixtureIndex, { actor_role: role, test_kind: 'capacity' });
  const accepted = result.csrf.status === 200 && result.login && result.login.status === 200;
  record(result.login || result.csrf, `${role} fixture 로그인이 성공한다`, accepted);
  return accepted ? client : null;
}

function readAllNotifications(client, role) {
  const items = [];
  for (let page = 0; page < 20; page += 1) {
    const response = listNotifications(client, page, 100, { actor_role: role, test_kind: 'capacity' });
    if (!record(response, `${role} 알림 목록 조회가 성공한다`, response.status === 200)) {
      return null;
    }
    const data = responseData(response);
    if (!data || !Array.isArray(data.content)) {
      record(response, `${role} 알림 목록 응답 형식이 맞다`, false);
      return null;
    }
    items.push(...data.content);
    if (!data.hasNext) {
      return items;
    }
  }
  record(null, `${role} 알림 목록이 20페이지 안에 끝난다`, false);
  return null;
}

function waitForHostSetupDrain(host, roomIds) {
  const expected = EVENT_COUNT * RECIPIENTS;
  const roomIdSet = new Set(roomIds.map(String));
  const deadline = Date.now() + TIMEOUT_SECONDS * 1000;
  while (Date.now() < deadline) {
    const items = readAllNotifications(host, 'host');
    if (items === null) {
      return false;
    }
    const joinedCount = items.filter((item) => item.type === 'PARTICIPANT_JOINED'
      && roomIdSet.has(String(item.roomId))).length;
    if (joinedCount === expected) {
      return true;
    }
    sleep(POLL_INTERVAL_MS / 1000);
  }
  record(null, 'fan-out 측정 전 참가 알림 backlog가 비워진다', false);
  return false;
}

function verifyFinalRecipientCounts(recipients, roomIds) {
  const roomIdSet = new Set(roomIds.map(String));
  for (let recipientIndex = 0; recipientIndex < recipients.length; recipientIndex += 1) {
    const role = `recipient-${recipientIndex + 1}`;
    const items = readAllNotifications(recipients[recipientIndex], role);
    if (items === null) {
      return false;
    }

    const countsByRoomId = new Map(roomIds.map((roomId) => [String(roomId), 0]));
    for (const item of items) {
      const roomId = String(item.roomId);
      if (item.type === 'ROOM_CANCELED' && roomIdSet.has(roomId)) {
        countsByRoomId.set(roomId, countsByRoomId.get(roomId) + 1);
      }
    }

    const invalidCounts = [...countsByRoomId.entries()]
      .filter(([, count]) => count !== 1);
    if (invalidCounts.length > 0) {
      const detail = invalidCounts
        .map(([roomId, count]) => `${roomId}:${count}`)
        .join(',');
      console.error(`${role} ROOM_CANCELED 최종 건수 불일치: ${detail}`);
    }
    record(null, '수신자·방별 ROOM_CANCELED 알림이 최종적으로 정확히 1개다', invalidCounts.length === 0);
  }
  return true;
}

export default function () {
  const host = loginClient(1, 'host');
  if (!host) {
    return;
  }
  const recipients = [];
  for (let index = 0; index < RECIPIENTS; index += 1) {
    const recipient = loginClient(index + 2, `recipient-${index + 1}`);
    if (!recipient) {
      return;
    }
    recipients.push(recipient);
  }

  const roomIds = [];
  for (let eventIndex = 0; eventIndex < EVENT_COUNT; eventIndex += 1) {
    const title = `k6-fanout-${RUN_ID}-${eventIndex + 1}`.slice(0, 100);
    const roomResponse = createRoom(host, title, { test_kind: 'capacity' }, RECIPIENTS);
    const roomData = responseData(roomResponse);
    if (!record(roomResponse, 'fan-out fixture 방 생성이 성공한다',
      roomResponse.status === 201 && roomData && roomData.id)) {
      return;
    }
    roomIds.push(roomData.id);
    for (let recipientIndex = 0; recipientIndex < RECIPIENTS; recipientIndex += 1) {
      const joinResponse = joinRoom(recipients[recipientIndex], roomData.id, { test_kind: 'capacity' });
      if (!record(joinResponse, 'fan-out fixture 참가가 성공한다', joinResponse.status === 201)) {
        return;
      }
    }
  }

  if (!waitForHostSetupDrain(host, roomIds)) {
    return;
  }

  const canceledAtByRoomId = new Map();
  for (const roomId of roomIds) {
    const startedAt = Date.now();
    const response = cancelRoom(host, roomId, { test_kind: 'capacity' });
    if (!record(response, 'fan-out 방 취소 이벤트 생성이 성공한다', response.status === 200)) {
      return;
    }
    const completedAt = Date.now();
    canceledAtByRoomId.set(String(roomId), completedAt);
    generationDuration.add(completedAt - startedAt, { fanout_recipients: String(RECIPIENTS) });
  }

  const observedKeys = new Set();
  const roomIdSet = new Set(roomIds.map(String));
  const deadline = Date.now() + TIMEOUT_SECONDS * 1000;
  let round = 0;
  while (observedKeys.size < EXPECTED_SAMPLES && Date.now() < deadline) {
    // 한 VU가 수신자를 차례로 훑으므로 뒤에 조회하는 수신자일수록 관찰이 늦다. 라운드마다 시작 수신자를
    // 옮겨 이 편향이 특정 수신자에 고정되지 않게 한다. 수신자별 비교는 App 로그의 deliveryDelayMs로 한다.
    for (let offset = 0; offset < recipients.length; offset += 1) {
      const recipientIndex = (round + offset) % recipients.length;
      const items = readAllNotifications(recipients[recipientIndex], `recipient-${recipientIndex + 1}`);
      if (items === null) {
        return;
      }
      const observedAt = Date.now();
      const roomsInResponse = new Set();
      const sampleTags = {
        fanout_recipients: String(RECIPIENTS),
        fanout_recipient: String(recipientIndex + 1),
      };
      for (const item of items) {
        const roomId = String(item.roomId);
        if (item.type !== 'ROOM_CANCELED' || !roomIdSet.has(roomId)) {
          continue;
        }
        if (roomsInResponse.has(roomId)) {
          record(null, '같은 수신자·방의 ROOM_CANCELED 알림이 중복되지 않는다', false);
          continue;
        }
        roomsInResponse.add(roomId);
        const key = `${recipientIndex + 1}:${roomId}`;
        if (observedKeys.has(key)) {
          continue;
        }
        observedKeys.add(key);
        deliverySamples.add(1, sampleTags);
        observedDelay.add(observedAt - canceledAtByRoomId.get(roomId), sampleTags);
      }
    }
    round += 1;
    if (observedKeys.size < EXPECTED_SAMPLES) {
      sleep(POLL_INTERVAL_MS / 1000);
    }
  }

  const deliveredInTime = observedKeys.size === EXPECTED_SAMPLES;
  record(null, 'fan-out 알림이 제한 시간 안에 모두 전달된다', deliveredInTime);

  // 첫 전달을 모두 본 직후 종료하면 다음 relay 주기에 늦게 나타난 중복을 놓칠 수 있다.
  // 기본 relay 5초보다 길게 관찰한 뒤 수신자·방별 최종 건수를 다시 확인한다.
  sleep(FINAL_RELAY_OBSERVATION_SECONDS);
  verifyFinalRecipientCounts(recipients, roomIds);
}
