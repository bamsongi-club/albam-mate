import { check } from 'k6';
import { Gauge, Rate } from 'k6/metrics';

import {
  RUN_ID,
  createClient,
  fetchCsrf,
  fixtureAccount,
  loginRequest,
  publicProbe,
  responseCode,
  retryAfterSeconds,
  signupRequest,
  upstreamName,
} from './lib/albam.js';

const RATE_LIMIT_CASE = (__ENV.RATE_LIMIT_CASE || 'signup').trim();
const ALLOWED_CASES = ['signup', 'login-ip', 'login-failure-reset', 'xff'];

if (!ALLOWED_CASES.includes(RATE_LIMIT_CASE)) {
  throw new Error(`RATE_LIMIT_CASE는 ${ALLOWED_CASES.join(', ')} 중 하나여야 합니다.`);
}

export const options = {
  scenarios: {
    rate_limit_contract: {
      executor: 'shared-iterations',
      vus: 1,
      iterations: 1,
      maxDuration: '3m',
    },
  },
  thresholds: {
    contract_failures: ['rate==0'],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const contractFailures = new Rate('contract_failures');
const upstreamCoverage = new Gauge('auth_contract_upstream_coverage');

function record(response, label, accepted, authRequestUpstreams = null) {
  check(response, { [label]: () => accepted });
  contractFailures.add(!accepted, { rate_limit_case: RATE_LIMIT_CASE });
  const upstream = upstreamName(response);
  if (authRequestUpstreams && upstream !== 'missing') {
    authRequestUpstreams.add(upstream);
  }
  if (!accepted) {
    console.error(`${label}: status=${response.status} code=${responseCode(response) || 'none'}`);
  }
}

function requireCsrf(client) {
  const response = fetchCsrf(client, { rate_limit_case: RATE_LIMIT_CASE });
  record(response, 'CSRF 조회가 성공한다', response.status === 200);
  return response.status === 200;
}

function signupLimit(upstreams) {
  const client = createClient();
  if (!requireCsrf(client)) {
    return;
  }
  for (let index = 1; index <= 6; index += 1) {
    const account = {
      email: `k6.${RUN_ID}.signup.${index}@example.com`,
      password: 'LoadTest-Password-2026!',
    };
    const response = signupRequest(client, account, `k6-${RUN_ID}-s${index}`.slice(0, 50), {
      rate_limit_case: RATE_LIMIT_CASE,
    });
    const accepted = index <= 5
      ? response.status === 201
      : response.status === 429 && Number.isFinite(retryAfterSeconds(response)) && retryAfterSeconds(response) > 0;
    record(response, index <= 5 ? `회원가입 ${index}회가 성공한다` : '회원가입 6회째가 제한된다', accepted, upstreams);
  }
}

function loginIpLimit(upstreams) {
  const client = createClient();
  const account = fixtureAccount(1);
  for (let index = 1; index <= 31; index += 1) {
    if (!requireCsrf(client)) {
      return;
    }
    const probe = publicProbe(client, { rate_limit_case: RATE_LIMIT_CASE });
    record(probe, 'upstream 교차용 공개 조회가 성공한다', probe.status === 200);
    const response = loginRequest(client, account, { rate_limit_case: RATE_LIMIT_CASE });
    const accepted = index <= 30
      ? response.status === 200
      : response.status === 429 && Number.isFinite(retryAfterSeconds(response)) && retryAfterSeconds(response) > 0;
    record(response, index <= 30 ? `로그인 ${index}회가 성공한다` : '로그인 31회째가 제한된다', accepted, upstreams);
  }
}

function loginFailureReset(upstreams) {
  const client = createClient();
  const account = fixtureAccount(1);
  const wrongAccount = { ...account, password: `${account.password}-wrong` };
  if (!requireCsrf(client)) {
    return;
  }

  for (let index = 1; index <= 4; index += 1) {
    const response = loginRequest(client, wrongAccount, { rate_limit_case: RATE_LIMIT_CASE });
    record(response, `초기 로그인 실패 ${index}회가 401이다`, response.status === 401
      && responseCode(response) === 'INVALID_CREDENTIALS', upstreams);
  }

  const success = loginRequest(client, account, { rate_limit_case: RATE_LIMIT_CASE });
  record(success, '정상 로그인이 실패 횟수를 초기화한다', success.status === 200, upstreams);
  if (success.status !== 200 || !requireCsrf(client)) {
    return;
  }

  for (let index = 1; index <= 6; index += 1) {
    const response = loginRequest(client, wrongAccount, { rate_limit_case: RATE_LIMIT_CASE });
    const accepted = index <= 5
      ? response.status === 401 && responseCode(response) === 'INVALID_CREDENTIALS'
      : response.status === 429 && Number.isFinite(retryAfterSeconds(response)) && retryAfterSeconds(response) > 0;
    record(response, index <= 5 ? `초기화 후 로그인 실패 ${index}회가 401이다` : '초기화 후 6회째 실패가 제한된다', accepted, upstreams);
  }
}

function spoofedForwardedFor(upstreams) {
  const client = createClient();
  const account = fixtureAccount(1);
  const wrongAccount = { ...account, password: `${account.password}-wrong` };
  if (!requireCsrf(client)) {
    return;
  }
  for (let index = 1; index <= 6; index += 1) {
    const response = loginRequest(client, wrongAccount, { rate_limit_case: RATE_LIMIT_CASE }, {
      'X-Forwarded-For': `198.51.100.${index}`,
    });
    const accepted = index <= 5
      ? response.status === 401 && responseCode(response) === 'INVALID_CREDENTIALS'
      : response.status === 429 && Number.isFinite(retryAfterSeconds(response)) && retryAfterSeconds(response) > 0;
    record(response, index <= 5 ? `위조 XFF 로그인 실패 ${index}회가 401이다` : '위조 XFF로도 6회째 실패가 제한된다', accepted, upstreams);
  }
}

export default function () {
  const upstreams = new Set();
  if (RATE_LIMIT_CASE === 'signup') {
    signupLimit(upstreams);
  } else if (RATE_LIMIT_CASE === 'login-ip') {
    loginIpLimit(upstreams);
  } else if (RATE_LIMIT_CASE === 'login-failure-reset') {
    loginFailureReset(upstreams);
  } else {
    spoofedForwardedFor(upstreams);
  }

  upstreamCoverage.add(upstreams.size, { rate_limit_case: RATE_LIMIT_CASE });
  if (upstreams.size < 2) {
    console.warn(`인증 요청의 upstream 관찰이 부족합니다: count=${upstreams.size}. 계약 실패가 아니라 분산 판정 불충분입니다.`);
  }
}
