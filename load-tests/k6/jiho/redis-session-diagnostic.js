import { check, sleep } from 'k6';
import exec from 'k6/execution';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  createClient,
  loginFixture,
  publicProbe,
  requireCapacityProfile,
  responseCode,
  unreadNotificationCount,
  upstreamName,
} from './lib/albam.js';

const MODE = (__ENV.REDIS_DIAGNOSTIC_MODE || '').trim();
if (!['public-control', 'authenticated-session'].includes(MODE)) {
  throw new Error('REDIS_DIAGNOSTIC_MODE는 public-control 또는 authenticated-session이어야 합니다.');
}
requireCapacityProfile();

const VUS = 150;
const INTERVAL_SECONDS = 10;
const WARMUP_SECONDS = 60;
const MEASUREMENT_SECONDS = 300;
const OBSERVATION_SECONDS = 60;
const TOTAL_SECONDS = WARMUP_SECONDS + MEASUREMENT_SECONDS + OBSERVATION_SECONDS;
const LOGIN_MAX_ATTEMPTS = 5;

export const options = {
  scenarios: {
    redis_session_diagnostic: {
      executor: 'constant-vus',
      vus: VUS,
      duration: `${TOTAL_SECONDS}s`,
      gracefulStop: '30s',
    },
  },
  thresholds: {
    diagnostic_setup_failures: ['rate==0'],
    diagnostic_resolved_vus: [`count==${VUS}`],
    'diagnostic_request_errors{phase:measurement}': ['rate<0.01'],
    'diagnostic_request_duration{phase:measurement}': ['p(95)<=1000'],
  },
  summaryTrendStats: ['min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const setupFailures = new Rate('diagnostic_setup_failures');
const resolvedVus = new Counter('diagnostic_resolved_vus');
const loginRetries = new Counter('diagnostic_login_retries');
const requestErrors = new Rate('diagnostic_request_errors');
const requestDuration = new Trend('diagnostic_request_duration', true);

let initialized = false;
let gaveUp = false;
let staggered = false;
let client;

function currentPhase() {
  const elapsedSeconds = exec.instance.currentTestRunDuration / 1000;
  if (elapsedSeconds < WARMUP_SECONDS) {
    return 'warmup';
  }
  if (elapsedSeconds < WARMUP_SECONDS + MEASUREMENT_SECONDS) {
    return 'measurement';
  }
  return 'observation';
}

function initializeVu() {
  if (initialized) {
    return true;
  }
  if (gaveUp) {
    return false;
  }

  client = createClient();
  if (MODE === 'public-control') {
    initialized = true;
    setupFailures.add(false, { mode: MODE });
    resolvedVus.add(1, { mode: MODE, outcome: 'succeeded' });
    return true;
  }

  let status = 0;
  for (let attempt = 1; attempt <= LOGIN_MAX_ATTEMPTS; attempt += 1) {
    client = createClient();
    const result = loginFixture(client, exec.vu.idInTest, {
      test_kind: 'redis-session-diagnostic',
      mode: MODE,
    });
    if (result.csrf.status === 200 && result.login && result.login.status === 200) {
      initialized = true;
      setupFailures.add(false, { mode: MODE });
      resolvedVus.add(1, { mode: MODE, outcome: 'succeeded' });
      check(result.login, { '진단 사용자가 로그인한다': () => true });
      return true;
    }
    status = result.login ? result.login.status : result.csrf.status;
    if (status !== 429) {
      break;
    }
    loginRetries.add(1, { mode: MODE, status: String(status) });
    sleep(attempt);
  }

  gaveUp = true;
  setupFailures.add(true, { mode: MODE });
  resolvedVus.add(1, { mode: MODE, outcome: 'gave-up' });
  check(null, { '진단 사용자가 로그인한다': () => false });
  console.error(`진단 로그인 실패: vu=${exec.vu.idInTest} status=${status}`);
  return false;
}

export default function () {
  if (!staggered) {
    staggered = true;
    sleep(INTERVAL_SECONDS * (exec.vu.idInTest - 1) / VUS);
  }

  const iterationStartedAt = Date.now();
  if (initializeVu()) {
    const phase = currentPhase();
    const tags = { test_kind: 'redis-session-diagnostic', mode: MODE, phase };
    const response = MODE === 'public-control'
      ? publicProbe(client, tags)
      : unreadNotificationCount(client, tags);
    const accepted = response.status === 200;
    const metricTags = {
      ...tags,
      status: String(response.status),
      upstream: upstreamName(response),
    };
    requestErrors.add(!accepted, metricTags);
    requestDuration.add(response.timings.duration, metricTags);
    check(response, { '진단 요청이 200을 반환한다': () => accepted });
    if (!accepted) {
      console.warn(`Redis 세션 진단 오류: mode=${MODE} status=${response.status} code=${responseCode(response) || 'none'}`);
    }
  }

  const elapsedSeconds = (Date.now() - iterationStartedAt) / 1000;
  sleep(Math.max(0, INTERVAL_SECONDS - elapsedSeconds));
}
