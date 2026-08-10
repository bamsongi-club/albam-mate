import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  createClient,
  fetchCsrf,
  fixtureAccount,
  integerEnv,
  loginRequest,
  logoutRequest,
  missingAccount,
  requireCapacityProfile,
  responseCode,
  retryAfterSeconds,
  upstreamName,
} from './lib/albam.js';

requireCapacityProfile();

const AUTH_CASE = (__ENV.AUTH_CAPACITY_CASE || 'correct').trim();
const RATE = integerEnv('AUTH_CAPACITY_RATE', 1, 1, 1000);
const DURATION_SECONDS = integerEnv('AUTH_CAPACITY_DURATION_SECONDS', 120, 30, 3600);
const PRE_ALLOCATED_VUS = integerEnv('AUTH_CAPACITY_PRE_ALLOCATED_VUS', 20, 1, 500);
const MAX_VUS = integerEnv('AUTH_CAPACITY_MAX_VUS', 100, PRE_ALLOCATED_VUS, 500);
const FIXTURE_USER_COUNT = integerEnv('LOAD_TEST_USER_COUNT', 100, 1, 500);
const ALLOWED_CASES = ['correct', 'wrong', 'missing'];

if (!ALLOWED_CASES.includes(AUTH_CASE)) {
  throw new Error(`AUTH_CAPACITY_CASE는 ${ALLOWED_CASES.join(', ')} 중 하나여야 합니다.`);
}
if (FIXTURE_USER_COUNT < MAX_VUS) {
  throw new Error(`LOAD_TEST_USER_COUNT(${FIXTURE_USER_COUNT})는 AUTH_CAPACITY_MAX_VUS(${MAX_VUS}) 이상이어야 합니다.`);
}

export const options = {
  scenarios: {
    auth_capacity: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '15s',
    },
  },
  // 측정 조건이 깨진 Run만 실패시킨다. 무릎을 넘긴 뒤의 오류 응답은 측정 대상이므로 임계를 두지 않는다.
  thresholds: {
    auth_capacity_profile_violations: ['rate==0'],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const profileViolations = new Rate('auth_capacity_profile_violations');
const unexpectedResponses = new Rate('auth_capacity_unexpected_responses');
// 429의 원인을 응답만으로 구분할 수 없으므로 지표 이름도 원인을 단정하지 않는다. 아래 분류 주석의 전제가
// 성립한 Run에서만 이 값을 bcrypt 슬롯 거절로 읽는다.
const oneSecondRejectionRate = new Rate('auth_capacity_one_second_rejection_rate');
const completedLogins = new Counter('auth_capacity_completed_logins');
const oneSecondRejections = new Counter('auth_capacity_one_second_rejections');
const completedDuration = new Trend('auth_capacity_completed_duration', true);
const oneSecondRejectionDuration = new Trend('auth_capacity_one_second_rejection_duration', true);
const sessionReleaseFailures = new Counter('auth_capacity_session_release_failures');
const retryAfterSecondsTrend = new Trend('auth_capacity_retry_after_seconds');

/**
 * 측정이 끝난 세션을 즉시 반납한다.
 *
 * <p>세션은 기본 30분 뒤에야 만료되므로, 도착률을 올려가며 Run을 반복하면 뒤쪽 Run이 앞선 Run의 세션이 쌓인
 * Redis 위에서 측정된다. 반납 자체는 bcrypt 슬롯을 쓰지 않아 무릎 위치를 바꾸지 않지만, iteration이 길어져
 * 같은 도착률에 더 많은 VU가 필요하다.
 */
function releaseSession(client, tags) {
  const response = logoutRequest(client, tags);
  if (response.status !== 200) {
    sessionReleaseFailures.add(1, { ...tags, status: String(response.status) });
  }
}

export default function () {
  const client = createClient();
  const fixtureIndex = exec.vu.idInTest;
  const tags = {
    auth_case: AUTH_CASE,
    offered_rate: String(RATE),
    test_kind: 'capacity',
  };
  const csrf = fetchCsrf(client, tags);
  if (csrf.status !== 200) {
    const csrfTags = { ...tags, operation: 'csrf', status: String(csrf.status) };
    profileViolations.add(false, csrfTags);
    unexpectedResponses.add(true, csrfTags);
    console.warn(`용량 측정 CSRF 응답이 200이 아닙니다: status=${csrf.status}`);
    return;
  }

  let account;
  if (AUTH_CASE === 'missing') {
    account = missingAccount(fixtureIndex);
  } else {
    account = fixtureAccount(fixtureIndex);
    if (AUTH_CASE === 'wrong') {
      account.password = `${account.password}-wrong`;
    }
  }

  const response = loginRequest(client, account, tags);
  const expectedStatus = AUTH_CASE === 'correct' ? 200 : 401;
  const completed = response.status === expectedStatus
    && (expectedStatus === 200 || responseCode(response) === 'INVALID_CREDENTIALS');

  // 애플리케이션의 429는 항상 Retry-After를 붙이지만 응답 코드는 슬롯 거절과 이동창 제한이 똑같다.
  // 슬롯 거절은 항상 1초, 이동창 제한은 남은 창 길이를 올림한 값이라 1보다 큰 값은 제한 미상향의 확실한
  // 증거다. 반대로 1초는 창의 마지막 1초에서 이동창 제한도 낼 수 있어 응답만으로는 구분되지 않는다.
  // 서버가 원인을 구분해 주기 전까지 이 스크립트는 1초 429를 "원인 미상의 1초 거절"로만 기록한다.
  // 이를 슬롯 거절로 읽으려면 Run 시작 전에 제한 상태를 비웠고(README의 실행 전 조건) Retry-After 분포가
  // 전부 1이라는 두 전제를 함께 확인해야 한다. 그래서 분포를 따로 남긴다.
  const retryAfter = retryAfterSeconds(response);
  const oneSecondRejected = response.status === 429 && retryAfter === 1;
  const profileViolated = response.status === 429 && Number.isFinite(retryAfter) && retryAfter > 1;
  if (response.status === 429 && Number.isFinite(retryAfter)) {
    retryAfterSecondsTrend.add(retryAfter, { ...tags, upstream: upstreamName(response) });
  }
  const metricTags = { ...tags, upstream: upstreamName(response), status: String(response.status) };

  check(response, { '인증 요청 제한 상향이 유지된다': () => !profileViolated });
  profileViolations.add(profileViolated, metricTags);
  unexpectedResponses.add(!completed && !oneSecondRejected && !profileViolated, metricTags);
  oneSecondRejectionRate.add(oneSecondRejected, metricTags);

  if (completed) {
    completedLogins.add(1, metricTags);
    completedDuration.add(response.timings.duration, metricTags);
    if (response.status === 200) {
      releaseSession(client, tags);
    }
  } else if (oneSecondRejected) {
    oneSecondRejections.add(1, metricTags);
    oneSecondRejectionDuration.add(response.timings.duration, metricTags);
  } else if (profileViolated) {
    console.error(`인증 요청 제한이 상향되지 않았습니다: status=429 retryAfter=${retryAfter}`);
  } else {
    console.warn(`분류되지 않은 인증 용량 응답: status=${response.status} code=${responseCode(response) || 'none'}`);
  }
}
