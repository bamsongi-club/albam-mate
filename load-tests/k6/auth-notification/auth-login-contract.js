import { check } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  createClient,
  fetchCsrf,
  fixtureAccount,
  loginRequest,
  missingAccount,
  responseCode,
  responseData,
  unreadNotificationCount,
  upstreamName,
} from './lib/albam.js';

const AUTH_CASE = (__ENV.AUTH_CASE || 'correct').trim();
const ALLOWED_CASES = ['correct', 'wrong', 'missing'];

if (!ALLOWED_CASES.includes(AUTH_CASE)) {
  throw new Error(`AUTH_CASE는 ${ALLOWED_CASES.join(', ')} 중 하나여야 합니다.`);
}

export const options = {
  scenarios: {
    login_contract: {
      executor: 'per-vu-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '2m',
    },
  },
  thresholds: {
    contract_failures: ['rate==0'],
    completed_logins: ['count==1'],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const contractFailures = new Rate('contract_failures');
const completedLogins = new Counter('completed_logins');
const loginDuration = new Trend('contract_login_duration', true);

function recordContract(response, label, accepted) {
  check(response, { [label]: () => accepted });
  contractFailures.add(!accepted, { auth_case: AUTH_CASE });
  if (!accepted) {
    console.error(`${label}: status=${response.status} code=${responseCode(response) || 'none'}`);
  }
}

export default function () {
  const client = createClient();
  const fixtureIndex = exec.vu.idInTest;
  const tags = { auth_case: AUTH_CASE, test_kind: 'contract' };
  const csrf = fetchCsrf(client, tags);
  recordContract(csrf, 'CSRF 조회가 성공한다', csrf.status === 200);
  if (csrf.status !== 200) {
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

  recordContract(
    response,
    '단일 로그인 결과가 계약과 일치한다',
    completed,
  );

  if (!completed) {
    return;
  }
  completedLogins.add(1, { auth_case: AUTH_CASE, upstream: upstreamName(response) });
  loginDuration.add(response.timings.duration, { auth_case: AUTH_CASE });

  if (AUTH_CASE !== 'correct') {
    return;
  }

  // 200만 보고 통과시키면 세션이 서지 않아도 계약이 통과한다. 응답 본문과 세션 쿠키를 확인하고, 실제 보호
  // 자원을 한 번 호출해 다중 인스턴스와 공용 Redis를 거친 세션이 실제로 인증되는지까지 본다.
  const summary = responseData(response);
  recordContract(
    response,
    '로그인 응답이 사용자 요약을 반환한다',
    !!summary && summary.id !== undefined && summary.id !== null && typeof summary.nickname === 'string',
  );

  // 세션 쿠키 이름 `JSESSIONID`는 docs/API.md의 공개 계약이다. Set-Cookie가 있기만 하면 통과시키면 쿠키
  // 이름이 바뀌어도 아래 보호 자원 호출은 그대로 성공하므로, 계약이 깨진 것을 이 스크립트가 놓친다.
  const sessionCookies = (response.cookies || {}).JSESSIONID;
  const issuedSessionId = Array.isArray(sessionCookies) && sessionCookies.length > 0
    ? sessionCookies[sessionCookies.length - 1].value
    : null;
  recordContract(
    response,
    '로그인 응답이 JSESSIONID를 발급한다',
    typeof issuedSessionId === 'string' && issuedSessionId.length > 0,
  );

  const authenticated = unreadNotificationCount(client, tags);
  recordContract(authenticated, '발급된 세션으로 보호 자원을 인증한다', authenticated.status === 200);
}
