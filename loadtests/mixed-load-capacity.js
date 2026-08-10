import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  RUN_ID,
  cancelParticipation,
  createClient,
  createRoom,
  integerEnv,
  joinRoom,
  listNotifications,
  listRooms,
  loginFixture,
  requireCapacityProfile,
  responseCode,
  responseData,
  unreadNotificationCount,
  upstreamName,
} from './lib/albam.js';

// 로컬 예행용 스모크 모드다. 크기를 상수로 고정해 측정값으로 오인할 수 없게 하고, 로그인 수가 기본 인증
// 제한 안에 들어가므로 제한 상향 없이 돌 수 있다. 결과는 동작 확인용일 뿐 용량 근거가 아니다.
const SMOKE = (__ENV.MIXED_LOAD_SMOKE || '').trim() !== '';
const SMOKE_ONLINE_SESSIONS = 5;
const SMOKE_EVENTS_PER_MINUTE = 6;
const SMOKE_EVENT_MAX_VUS = 2;

if (!SMOKE) {
  requireCapacityProfile();
}

// 1× 기준선은 README의 부하 기준선 표를 따른다. 배수만 올려 같은 사용 흐름을 확대하고, 어느 역할이 먼저
// 한계에 닿는지 기록한다. 처리량 숫자 하나로 합격을 판정하지 않는다.
const BASELINE_ONLINE_SESSIONS = 300;
const BASELINE_EVENTS_PER_MINUTE = 25;
// bcrypt 슬롯은 대기 없이 거절하므로 시작 시점의 로그인 몰림은 다음 주기에 다시 시도해 해소한다.
const LOGIN_MAX_ATTEMPTS = 5;

const MULTIPLIER = integerEnv('MIXED_LOAD_MULTIPLIER', 1, 1, 10);
const DURATION_SECONDS = integerEnv('MIXED_LOAD_DURATION_SECONDS', SMOKE ? 60 : 300, 60, 3600);
const POLLING_INTERVAL_SECONDS = integerEnv('MIXED_POLLING_INTERVAL_SECONDS', 10, 1, 60);
// 스모크는 VU가 적어 비율로 나누면 조회 경로 일부가 아예 실행되지 않는다. 모든 VU가 모든 경로를 타게 해
// 동작 확인의 목적을 지킨다.
const PANEL_OPEN_PERCENT = SMOKE ? 100 : integerEnv('MIXED_PANEL_OPEN_PERCENT', 10, 0, 100);
const ROOM_BROWSE_PERCENT = SMOKE ? 100 : integerEnv('MIXED_ROOM_BROWSE_PERCENT', 20, 0, 100);
const EVENT_MAX_VUS = SMOKE ? SMOKE_EVENT_MAX_VUS : integerEnv('MIXED_EVENT_MAX_VUS', 20, 1, 200);
const FIXTURE_USER_COUNT = integerEnv('LOAD_TEST_USER_COUNT', SMOKE ? 20 : 1000, 2, 20000);

const ONLINE_SESSIONS = SMOKE ? SMOKE_ONLINE_SESSIONS : BASELINE_ONLINE_SESSIONS * MULTIPLIER;
const EVENTS_PER_MINUTE = SMOKE ? SMOKE_EVENTS_PER_MINUTE : BASELINE_EVENTS_PER_MINUTE * MULTIPLIER;
const RUN_KIND = SMOKE ? 'smoke' : 'capacity';

if (SMOKE) {
  console.warn('MIXED_LOAD_SMOKE: 동작 확인용 축소 실행이다. 이 결과를 용량 근거로 사용하지 않는다.');
}

// 모든 VU가 전역 고유한 VU 번호로 fixture 사용자를 고르고, 참가 이벤트 VU는 주최자와 참가자 두 명을 쓴다.
// 두 번째 사용자는 VU 번호 공간만큼 떨어뜨려 browsing 사용자와 겹치지 않게 한다.
const VU_ID_SPACE = ONLINE_SESSIONS + EVENT_MAX_VUS;
const REQUIRED_USER_COUNT = VU_ID_SPACE * 2;

// 두 비율은 VU 번호 구간의 앞뒤를 나눠 쓰므로 합이 100을 넘으면 구간이 겹친다.
if (!SMOKE && PANEL_OPEN_PERCENT + ROOM_BROWSE_PERCENT > 100) {
  throw new Error('MIXED_PANEL_OPEN_PERCENT와 MIXED_ROOM_BROWSE_PERCENT의 합은 100 이하여야 합니다.');
}
if (FIXTURE_USER_COUNT < REQUIRED_USER_COUNT) {
  throw new Error(
    `LOAD_TEST_USER_COUNT(${FIXTURE_USER_COUNT})는 ${MULTIPLIER}× 부하에 필요한 ${REQUIRED_USER_COUNT} 이상이어야 합니다.`,
  );
}
// stagger는 마지막 VU를 최대 한 주기 뒤까지 미루고, 그 뒤에 로그인 재시도 backoff가 더 붙는다. Run이 짧으면
// 일부 VU가 세션을 확정하기도 전에 끝나 성공도 실패도 남기지 않으므로, 남은 VU 표본만으로 임계가 통과한다.
if (DURATION_SECONDS < POLLING_INTERVAL_SECONDS * 2) {
  throw new Error(
    `MIXED_LOAD_DURATION_SECONDS(${DURATION_SECONDS})는 polling 주기(${POLLING_INTERVAL_SECONDS}초)의 2배 이상이어야 합니다.`,
  );
}

export const options = {
  scenarios: {
    browsing: {
      executor: 'constant-vus',
      exec: 'browsingSession',
      vus: ONLINE_SESSIONS,
      duration: `${DURATION_SECONDS}s`,
      gracefulStop: '30s',
    },
    participation: {
      executor: 'constant-arrival-rate',
      exec: 'participationEvent',
      rate: EVENTS_PER_MINUTE,
      timeUnit: '1m',
      duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: Math.min(EVENT_MAX_VUS, 5),
      maxVUs: EVENT_MAX_VUS,
      gracefulStop: '30s',
    },
  },
  // 부하를 견디는지는 임계로 판정하지 않는다. 측정을 시작조차 못 한 Run만 실패시킨다.
  // 실패율만 보면 세션을 확정조차 못 한 VU가 분모에서 빠지므로, 모든 browsing VU가 결론을 남겼는지
  // 함께 요구해 실제로 설정한 만큼의 세션으로 측정했음을 보장한다.
  thresholds: {
    mixed_setup_failures: ['rate==0'],
    mixed_resolved_browsing_vus: [`count==${ONLINE_SESSIONS}`],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const setupFailures = new Rate('mixed_setup_failures');
const resolvedBrowsingVus = new Counter('mixed_resolved_browsing_vus');
const loginRetries = new Counter('mixed_login_retries');
const requestErrors = new Rate('mixed_request_errors');
const unreadDuration = new Trend('mixed_unread_count_duration', true);
const notificationListDuration = new Trend('mixed_notification_list_duration', true);
const roomListDuration = new Trend('mixed_room_list_duration', true);
const participationEvents = new Counter('mixed_participation_events');
const participationDuration = new Trend('mixed_participation_event_duration', true);

let staggered = false;

let browsingClient;
let browsingReady = false;
let browsingGaveUp = false;
let firstPoll = true;

let eventHost;
let eventParticipant;
let eventRoomId = null;
let eventSetupFailed = false;

function recordResponse(response, operation, expectedStatus, trend, tags) {
  const accepted = response.status === expectedStatus;
  const responseTags = {
    ...tags,
    operation,
    status: String(response.status),
    upstream: upstreamName(response),
  };
  requestErrors.add(!accepted, responseTags);
  if (trend) {
    trend.add(response.timings.duration, responseTags);
  }
  if (!accepted) {
    console.warn(`${operation} 오류: status=${response.status} code=${responseCode(response) || 'none'}`);
  }
  return accepted;
}

function tryLogin(fixtureIndex, role) {
  const client = createClient();
  const result = loginFixture(client, fixtureIndex, { test_kind: 'mixed', actor_role: role });
  if (result.csrf.status === 200 && result.login && result.login.status === 200) {
    return { client, status: 200 };
  }
  return { client: null, status: result.login ? result.login.status : result.csrf.status };
}

/**
 * 로그인을 이 iteration 안에서 끝까지 확정한다.
 *
 * <p>재시도를 다음 iteration으로 미루면 Run이 재시도 도중에 끝났을 때 그 VU가 성공도 실패도 기록하지 않아,
 * 실제보다 적은 세션으로 측정하고도 임계를 통과한다.
 */
function loginWithRetry(fixtureIndex, role, retryTags) {
  let status = 0;
  for (let attempt = 1; attempt <= LOGIN_MAX_ATTEMPTS; attempt += 1) {
    const result = tryLogin(fixtureIndex, role);
    if (result.client) {
      return result;
    }
    status = result.status;
    // bcrypt 슬롯 포화만 재시도로 회복된다. 그 밖의 실패는 기다려도 달라지지 않는다.
    if (status !== 429) {
      break;
    }
    loginRetries.add(1, { ...retryTags, status: String(status) });
    sleep(attempt);
  }
  return { client: null, status };
}

/**
 * 세션을 이 iteration 안에서 끝까지 확정한다.
 *
 * <p>재시도를 다음 iteration으로 미루면 Run이 재시도 도중에 끝났을 때 그 VU가 성공도 실패도 기록하지 않아,
 * 실제보다 적은 세션으로 측정하고도 임계를 통과한다. 한 iteration 안에서 성공하거나 실패를 남기고 끝낸다.
 */
function ensureBrowsingSession() {
  if (browsingReady) {
    return true;
  }
  if (browsingGaveUp) {
    return false;
  }

  const result = loginWithRetry(exec.vu.idInTest, 'browsing', { role: 'browsing' });
  if (result.client) {
    browsingClient = result.client;
    browsingReady = true;
    setupFailures.add(false, { role: 'browsing' });
    resolvedBrowsingVus.add(1, { role: 'browsing', outcome: 'succeeded' });
    return true;
  }

  browsingGaveUp = true;
  setupFailures.add(true, { role: 'browsing' });
  resolvedBrowsingVus.add(1, { role: 'browsing', outcome: 'gave-up' });
  check(null, { 'browsing 세션이 로그인한다': () => false });
  console.error(`browsing 로그인 실패: vu=${exec.vu.idInTest} status=${result.status}`);
  return false;
}

function failEventSetup(stage, status) {
  eventSetupFailed = true;
  setupFailures.add(true, { role: 'participation', stage });
  check(null, { '참가 이벤트 fixture가 준비된다': () => false });
  console.error(`참가 이벤트 fixture 준비 실패: stage=${stage} vu=${exec.vu.idInTest} status=${status}`);
  return false;
}

/** 참가 이벤트 VU마다 주최자·참가자와 전용 방 하나를 만들어 다른 VU와 정원을 다투지 않게 한다. */
function ensureEventFixture() {
  if (eventRoomId !== null) {
    return true;
  }
  if (eventSetupFailed) {
    return false;
  }

  if (!eventHost) {
    const attempt = loginWithRetry(exec.vu.idInTest, 'event-host',
      { role: 'participation', stage: 'event-host' });
    if (!attempt.client) {
      return failEventSetup('event-host', attempt.status);
    }
    eventHost = attempt.client;
  }
  if (!eventParticipant) {
    const attempt = loginWithRetry(exec.vu.idInTest + VU_ID_SPACE, 'event-participant',
      { role: 'participation', stage: 'event-participant' });
    if (!attempt.client) {
      return failEventSetup('event-participant', attempt.status);
    }
    eventParticipant = attempt.client;
  }

  const title = `k6-mixed-${RUN_ID}-${exec.vu.idInTest}`.slice(0, 100);
  const response = createRoom(eventHost, title, { test_kind: 'mixed', actor_role: 'event-host' }, 1);
  const data = responseData(response);
  if (response.status !== 201 || !data || !data.id) {
    return failEventSetup('room-create', response.status);
  }
  eventRoomId = data.id;
  setupFailures.add(false, { role: 'participation' });
  return true;
}

export function browsingSession() {
  // 주기 안에서 VU를 균등하게 흩어 로그인 몰림을 없애고, 실제 브라우저처럼 polling 위상을 분산한다.
  // 이 대기는 주기 보정 밖에서 한 번만 수행해야 위상 차이가 Run 내내 유지된다.
  if (!staggered) {
    staggered = true;
    sleep(POLLING_INTERVAL_SECONDS * (exec.vu.idInTest % ONLINE_SESSIONS) / ONLINE_SESSIONS);
  }

  const iterationStartedAt = Date.now();
  if (ensureBrowsingSession()) {
    const phase = firstPoll ? 'initial' : 'steady';
    const tags = { test_kind: 'mixed', run_kind: RUN_KIND, role: 'browsing', phase };
    // VU 번호로 역할을 결정론적으로 나눠 같은 Run 조건을 재현할 수 있게 한다.
    const bucket = exec.vu.idInTest % 100;

    recordResponse(
      unreadNotificationCount(browsingClient, tags), 'unread-count', 200, unreadDuration, tags);
    // 세션에 처음 들어온 순간에는 모든 사용자가 목록 첫 페이지를 조회한다. 이후에는 알림함을 열어 둔
    // 비율만 계속 조회한다.
    if (firstPoll || bucket < PANEL_OPEN_PERCENT) {
      recordResponse(
        listNotifications(browsingClient, 0, 10, tags), 'notification-list', 200, notificationListDuration, tags);
    }
    if (bucket >= 100 - ROOM_BROWSE_PERCENT) {
      recordResponse(
        listRooms(browsingClient, 0, 10, tags), 'room-list', 200, roomListDuration, tags);
    }
    firstPoll = false;
  }

  const elapsedSeconds = (Date.now() - iterationStartedAt) / 1000;
  sleep(Math.max(0, POLLING_INTERVAL_SECONDS - elapsedSeconds));
}

export function participationEvent() {
  if (!ensureEventFixture()) {
    return;
  }

  const tags = { test_kind: 'mixed', run_kind: RUN_KIND, role: 'participation' };
  const startedAt = Date.now();
  const join = joinRoom(eventParticipant, eventRoomId, { ...tags, event_type: 'PARTICIPANT_JOINED' });
  if (!recordResponse(join, 'room-join', 201, null, tags)) {
    return;
  }
  const cancel = cancelParticipation(eventParticipant, eventRoomId, { ...tags, event_type: 'PARTICIPANT_CANCELED' });
  if (!recordResponse(cancel, 'room-cancel', 200, null, tags)) {
    return;
  }

  participationDuration.add(Date.now() - startedAt, tags);
  // 한 iteration이 참가·취소 두 건의 알림 이벤트를 만든다.
  participationEvents.add(2, tags);
}
