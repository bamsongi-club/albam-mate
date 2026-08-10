import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  createClient,
  integerEnv,
  listNotifications,
  loginFixture,
  requireCapacityProfile,
  responseCode,
  unreadNotificationCount,
  upstreamName,
} from './lib/albam.js';

requireCapacityProfile();

const POLLING_VUS = integerEnv('NOTIFICATION_POLLING_VUS', 20, 1, 500);
const DURATION_SECONDS = integerEnv('NOTIFICATION_POLLING_DURATION_SECONDS', 120, 30, 3600);
const INTERVAL_SECONDS = integerEnv('NOTIFICATION_POLLING_INTERVAL_SECONDS', 10, 1, 60);
const PANEL_OPEN_PERCENT = integerEnv('NOTIFICATION_PANEL_OPEN_PERCENT', 10, 0, 100);
const FIXTURE_USER_COUNT = integerEnv('LOAD_TEST_USER_COUNT', 100, 1, 500);

if (FIXTURE_USER_COUNT < POLLING_VUS) {
  throw new Error(`LOAD_TEST_USER_COUNT(${FIXTURE_USER_COUNT})는 NOTIFICATION_POLLING_VUS(${POLLING_VUS}) 이상이어야 합니다.`);
}
// stagger는 마지막 VU를 최대 한 주기 뒤까지 미루고, 그 뒤에 로그인 재시도 backoff가 더 붙는다. Run이 짧으면
// 일부 VU가 세션을 확정하기도 전에 끝나 성공도 실패도 남기지 않으므로, 남은 VU 표본만으로 임계가 통과한다.
if (DURATION_SECONDS < INTERVAL_SECONDS * 2) {
  throw new Error(
    `NOTIFICATION_POLLING_DURATION_SECONDS(${DURATION_SECONDS})는 polling 주기(${INTERVAL_SECONDS}초)의 2배 이상이어야 합니다.`,
  );
}

// bcrypt 슬롯은 대기 없이 거절하므로 시작 시점의 로그인 몰림은 짧은 backoff 뒤 다시 시도해 해소한다.
const LOGIN_MAX_ATTEMPTS = 5;

export const options = {
  scenarios: {
    notification_polling_capacity: {
      executor: 'constant-vus',
      vus: POLLING_VUS,
      duration: `${DURATION_SECONDS}s`,
      gracefulStop: '15s',
    },
  },
  // 세션 실패율만 보면 세션을 확정조차 못 한 VU가 분모에서 빠져 임계가 통과한다. 모든 VU가 성공이든
  // 실패든 결론을 남겼는지 함께 요구해야 측정에 참여한 VU 수가 설정과 같다는 것을 보장한다.
  thresholds: {
    polling_setup_failures: ['rate==0'],
    polling_resolved_vus: [`count==${POLLING_VUS}`],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const setupFailures = new Rate('polling_setup_failures');
const resolvedVus = new Counter('polling_resolved_vus');
const loginRetries = new Counter('polling_login_retries');
const requestErrors = new Rate('notification_polling_request_errors');
const unreadRequests = new Counter('notification_unread_count_requests');
const listRequests = new Counter('notification_list_requests');
const unreadDuration = new Trend('notification_unread_count_duration', true);
const listDuration = new Trend('notification_list_duration', true);

let client;
let loginSucceeded = false;
let loginGaveUp = false;
let firstPoll = true;
let staggered = false;

/**
 * 세션을 이 iteration 안에서 끝까지 확정한다.
 *
 * <p>재시도를 다음 iteration으로 미루면 Run이 재시도 도중에 끝났을 때 그 VU가 성공도 실패도 기록하지 않아,
 * 실제보다 적은 VU로 측정하고도 임계를 통과한다. 한 iteration 안에서 성공하거나 실패를 남기고 끝낸다.
 */
function initializeSession() {
  if (loginSucceeded) {
    return true;
  }
  if (loginGaveUp) {
    return false;
  }

  let status = 0;
  for (let attempt = 1; attempt <= LOGIN_MAX_ATTEMPTS; attempt += 1) {
    client = createClient();
    const result = loginFixture(client, exec.vu.idInTest, {
      test_kind: 'capacity',
      actor_role: 'polling-user',
    });
    if (result.csrf.status === 200 && !!result.login && result.login.status === 200) {
      loginSucceeded = true;
      check(result.login, { 'polling 사용자가 로그인한다': () => true });
      setupFailures.add(false, { test_kind: 'capacity' });
      resolvedVus.add(1, { test_kind: 'capacity', outcome: 'succeeded' });
      return true;
    }
    status = result.login ? result.login.status : result.csrf.status;
    // bcrypt 슬롯 포화만 재시도로 회복된다. 그 밖의 실패는 기다려도 달라지지 않는다.
    if (status !== 429) {
      break;
    }
    loginRetries.add(1, { test_kind: 'capacity', status: String(status) });
    sleep(attempt);
  }

  loginGaveUp = true;
  check(null, { 'polling 사용자가 로그인한다': () => false });
  setupFailures.add(true, { test_kind: 'capacity' });
  resolvedVus.add(1, { test_kind: 'capacity', outcome: 'gave-up' });
  console.error(`polling 사용자 로그인 실패: vu=${exec.vu.idInTest} status=${status}`);
  return false;
}

function recordPolling(response, operation, phase) {
  const accepted = response.status === 200;
  const tags = { operation, phase, upstream: upstreamName(response), status: String(response.status) };
  check(response, { [`${operation} 응답을 기록한다`]: () => accepted });
  requestErrors.add(!accepted, tags);
  if (operation === 'unread-count') {
    unreadRequests.add(1, tags);
    unreadDuration.add(response.timings.duration, tags);
  } else {
    listRequests.add(1, tags);
    listDuration.add(response.timings.duration, tags);
  }
  if (!accepted) {
    console.warn(`${operation} polling 오류: status=${response.status} code=${responseCode(response) || 'none'}`);
  }
}

export default function () {
  // 주기 안에서 VU를 균등하게 흩어 로그인 몰림을 없애고, 실제 브라우저처럼 polling 위상을 분산한다.
  // 이 대기는 주기 보정 밖에서 한 번만 수행해야 위상 차이가 Run 내내 유지된다.
  if (!staggered) {
    staggered = true;
    sleep(INTERVAL_SECONDS * (exec.vu.idInTest - 1) / POLLING_VUS);
  }

  const iterationStartedAt = Date.now();
  if (initializeSession()) {
    const phase = firstPoll ? 'initial' : 'steady';
    recordPolling(unreadNotificationCount(client, { test_kind: 'capacity', phase }), 'unread-count', phase);

    // 세션에 처음 들어온 순간에는 모든 사용자가 목록 첫 페이지를 조회한다. 이후에는 알림함을 열어 둔
    // 비율만 계속 조회한다.
    const openPanelUsers = Math.ceil(POLLING_VUS * PANEL_OPEN_PERCENT / 100);
    if (firstPoll || exec.vu.idInTest <= openPanelUsers) {
      recordPolling(
        listNotifications(client, 0, 10, { test_kind: 'capacity', phase }),
        'notification-list',
        phase,
      );
    }
    firstPoll = false;
  }

  const elapsedSeconds = (Date.now() - iterationStartedAt) / 1000;
  sleep(Math.max(0, INTERVAL_SECONDS - elapsedSeconds));
}
