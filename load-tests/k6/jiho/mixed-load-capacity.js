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
  loginFixture,
  requireCapacityProfile,
  responseCode,
  responseData,
  unreadNotificationCount,
  upstreamName,
} from './lib/albam.js';

// 로컬 예행용 스모크 모드다. 크기를 상수로 고정해 측정값으로 오인할 수 없게 하고, 로그인 수가 기본 인증
// 제한 안에 들어가므로 제한 상향 없이 돌 수 있다. 결과는 동작 확인용일 뿐 용량 근거가 아니다.
const SMOKE_FLAG = (__ENV.MIXED_LOAD_SMOKE || '').trim().toLowerCase();
if (!['', '0', '1', 'false'].includes(SMOKE_FLAG)) {
  throw new Error('MIXED_LOAD_SMOKE는 스모크 실행일 때만 1을 사용하고, 비활성화할 때는 비우거나 0 또는 false를 사용해야 합니다.');
}
const SMOKE = SMOKE_FLAG === '1';
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

const MULTIPLIER = capacityMultiplier();
const POLLING_INTERVAL_SECONDS = 10;
// 공식 Run은 조건 비교가 목적이므로 구간과 사용자 행동 비율을 환경 변수로 바꾸지 않는다.
const WARMUP_SECONDS = SMOKE ? 0 : 120;
const MEASUREMENT_SECONDS = SMOKE ? 60 : 600;
const OBSERVATION_SECONDS = SMOKE ? 0 : 180;
const ACTIVE_LOAD_SECONDS = WARMUP_SECONDS + MEASUREMENT_SECONDS;
const TOTAL_RUN_SECONDS = ACTIVE_LOAD_SECONDS + OBSERVATION_SECONDS;
// 스모크는 VU가 적어 10%로 나누면 목록 조회가 아예 실행되지 않을 수 있어 모든 VU가 두 알림 경로를 탄다.
const PANEL_OPEN_PERCENT = SMOKE ? 100 : 10;
const EVENT_MAX_VUS = SMOKE ? SMOKE_EVENT_MAX_VUS : integerEnv('MIXED_EVENT_MAX_VUS', 20, 1, 200);
const FIXTURE_USER_COUNT = integerEnv('LOAD_TEST_USER_COUNT', SMOKE ? 20 : 1000, 2, 20000);

const ONLINE_SESSIONS = SMOKE ? SMOKE_ONLINE_SESSIONS : BASELINE_ONLINE_SESSIONS * MULTIPLIER;
// 한 iteration이 참가·취소 알림 이벤트를 두 건 만든다. k6 arrival-rate의 rate는 정수여야 하므로
// timeUnit을 조절해 0.5×를 포함한 목표 알림 이벤트 유입률을 정확히 맞춘다.
const EVENT_ITERATION_RATE = SMOKE ? SMOKE_EVENTS_PER_MINUTE : BASELINE_EVENTS_PER_MINUTE * Math.max(1, MULTIPLIER);
const EVENT_TIME_UNIT = SMOKE ? '2m' : MULTIPLIER === 0.5 ? '4m' : '2m';
const RUN_KIND = SMOKE ? 'smoke' : 'capacity';

if (SMOKE) {
  console.warn('MIXED_LOAD_SMOKE: 동작 확인용 축소 실행이다. 이 결과를 용량 근거로 사용하지 않는다.');
}

// 모든 VU가 전역 고유한 VU 번호로 fixture 사용자를 고르고, 참가 이벤트 VU는 주최자와 참가자 두 명을 쓴다.
// 두 번째 사용자는 VU 번호 공간만큼 떨어뜨려 browsing 사용자와 겹치지 않게 한다.
const VU_ID_SPACE = ONLINE_SESSIONS + EVENT_MAX_VUS;
const REQUIRED_USER_COUNT = VU_ID_SPACE * 2;

if (FIXTURE_USER_COUNT < REQUIRED_USER_COUNT) {
  throw new Error(
    `LOAD_TEST_USER_COUNT(${FIXTURE_USER_COUNT})는 ${MULTIPLIER}× 부하에 필요한 ${REQUIRED_USER_COUNT} 이상이어야 합니다.`,
  );
}
export const options = {
  scenarios: {
    browsing: {
      executor: 'constant-vus',
      exec: 'browsingSession',
      vus: ONLINE_SESSIONS,
      duration: `${TOTAL_RUN_SECONDS}s`,
      gracefulStop: '30s',
    },
    participation: {
      executor: 'constant-arrival-rate',
      exec: 'participationEvent',
      rate: EVENT_ITERATION_RATE,
      timeUnit: EVENT_TIME_UNIT,
      duration: `${ACTIVE_LOAD_SECONDS}s`,
      preAllocatedVUs: Math.min(EVENT_MAX_VUS, 5),
      maxVUs: EVENT_MAX_VUS,
      gracefulStop: '30s',
    },
  },
  // 측정 구간의 작업별 오류율·p95와 이벤트 유실을 성능 임계로 판정한다. 실패율만 보면 세션을 확정조차
  // 못 한 VU가 분모에서 빠지므로, 모든 browsing VU가 결론을 남겼는지도 함께 요구해 실제로 설정한 만큼의
  // 세션으로 측정했음을 보장한다.
  thresholds: {
    mixed_setup_failures: ['rate==0'],
    mixed_resolved_browsing_vus: [`count==${ONLINE_SESSIONS}`],
    'mixed_request_errors{phase:measurement,operation:unread-count}': ['rate<0.01'],
    'mixed_request_errors{phase:measurement,operation:notification-list}': ['rate<0.01'],
    'mixed_request_errors{phase:measurement,operation:room-join}': ['rate<0.01'],
    'mixed_request_errors{phase:measurement,operation:room-cancel}': ['rate<0.01'],
    'mixed_unread_count_duration{phase:measurement}': ['p(95)<=1000'],
    'mixed_notification_list_duration{phase:measurement}': ['p(95)<=1000'],
    'mixed_participation_request_duration{phase:measurement,operation:room-join}': ['p(95)<=1000'],
    'mixed_participation_request_duration{phase:measurement,operation:room-cancel}': ['p(95)<=1000'],
    'dropped_iterations{scenario:participation}': ['count==0'],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const setupFailures = new Rate('mixed_setup_failures');
const resolvedBrowsingVus = new Counter('mixed_resolved_browsing_vus');
const loginRetries = new Counter('mixed_login_retries');
const requestErrors = new Rate('mixed_request_errors');
const unreadDuration = new Trend('mixed_unread_count_duration', true);
const notificationListDuration = new Trend('mixed_notification_list_duration', true);
const participationEvents = new Counter('mixed_participation_events');
const participationDuration = new Trend('mixed_participation_event_duration', true);
const participationRequestDuration = new Trend('mixed_participation_request_duration', true);

let staggered = false;

let browsingClient;
let browsingReady = false;
let browsingGaveUp = false;
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

function capacityMultiplier() {
  const value = (__ENV.MIXED_LOAD_MULTIPLIER || '1').trim();
  if (value === '0.5') {
    return 0.5;
  }
  if (/^(?:[1-9]|10)$/.test(value)) {
    return Number(value);
  }
  throw new Error('MIXED_LOAD_MULTIPLIER는 0.5 또는 1부터 10까지의 정수여야 합니다.');
}

function currentPhase() {
  if (SMOKE) {
    return 'measurement';
  }
  const elapsedSeconds = exec.instance.currentTestRunDuration / 1000;
  if (elapsedSeconds < WARMUP_SECONDS) {
    return 'warmup';
  }
  if (elapsedSeconds < ACTIVE_LOAD_SECONDS) {
    return 'measurement';
  }
  return 'observation';
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
    const phase = currentPhase();
    const tags = { test_kind: 'mixed', run_kind: RUN_KIND, role: 'browsing', phase };
    // VU 번호로 역할을 결정론적으로 나눠 같은 Run 조건을 재현할 수 있게 한다.
    const bucket = exec.vu.idInTest % 100;

    recordResponse(
      unreadNotificationCount(browsingClient, tags), 'unread-count', 200, unreadDuration, tags);
    if (bucket < PANEL_OPEN_PERCENT) {
      recordResponse(
        listNotifications(browsingClient, 0, 10, tags), 'notification-list', 200, notificationListDuration, tags);
    }
  }

  const elapsedSeconds = (Date.now() - iterationStartedAt) / 1000;
  sleep(Math.max(0, POLLING_INTERVAL_SECONDS - elapsedSeconds));
}

export function participationEvent() {
  if (!ensureEventFixture()) {
    return;
  }

  const tags = {
    test_kind: 'mixed',
    run_kind: RUN_KIND,
    role: 'participation',
    phase: currentPhase(),
  };
  const startedAt = Date.now();
  const join = joinRoom(eventParticipant, eventRoomId, { ...tags, event_type: 'PARTICIPANT_JOINED' });
  if (!recordResponse(join, 'room-join', 201, participationRequestDuration, tags)) {
    return;
  }
  const cancel = cancelParticipation(eventParticipant, eventRoomId, { ...tags, event_type: 'PARTICIPANT_CANCELED' });
  if (!recordResponse(cancel, 'room-cancel', 200, participationRequestDuration, tags)) {
    return;
  }

  participationDuration.add(Date.now() - startedAt, tags);
  // 한 iteration이 참가·취소 두 건의 알림 이벤트를 만든다.
  participationEvents.add(2, tags);
}
