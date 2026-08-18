import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

import {
  createClient,
  integerEnv,
  publicProbe,
  requireCapacityProfile,
  upstreamName,
} from './lib/albam.js';

requireCapacityProfile();

const ALLOWED_PHASES = ['baseline', 'slow-request', 'db-pool-wait', 'recovery'];
const PHASE = (__ENV.OPS02_PHASE || '').trim();
const RELEASE = (__ENV.ALBAM_MATE_RELEASE || '').trim();
const RATE = integerEnv('OPS02_RATE', 1, 1, 1000);
const DURATION_SECONDS = integerEnv('OPS02_DURATION_SECONDS', 60, 30, 3600);
const PRE_ALLOCATED_VUS = integerEnv('OPS02_PRE_ALLOCATED_VUS', 10, 1, 500);
const MAX_VUS = integerEnv('OPS02_MAX_VUS', 50, PRE_ALLOCATED_VUS, 500);
const RELEASE_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._:@+-]{0,127}$/;

if (!ALLOWED_PHASES.includes(PHASE)) {
  throw new Error(`OPS02_PHASE는 ${ALLOWED_PHASES.join(', ')} 중 하나여야 합니다.`);
}
if (!RELEASE_PATTERN.test(RELEASE)) {
  throw new Error('ALBAM_MATE_RELEASE는 배포 manifest와 일치하는 128자 이하의 안전한 식별자여야 합니다.');
}

export const options = {
  scenarios: {
    ops02_latency_saturation: {
      executor: 'constant-arrival-rate',
      rate: RATE,
      timeUnit: '1s',
      duration: `${DURATION_SECONDS}s`,
      preAllocatedVUs: PRE_ALLOCATED_VUS,
      maxVUs: MAX_VUS,
      gracefulStop: '15s',
    },
  },
  thresholds: {
    ops02_request_errors: ['rate==0'],
    dropped_iterations: ['count==0'],
  },
  summaryTrendStats: ['min', 'med', 'p(50)', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

const requests = new Counter('ops02_requests');
const requestErrors = new Rate('ops02_request_errors');
const requestDuration = new Trend('ops02_request_duration', true);

export default function () {
  const client = createClient();
  const response = publicProbe(client, {
    test_kind: 'ops02-controlled',
    phase: PHASE,
    release: RELEASE,
  });
  const succeeded = response.status === 200;
  const tags = {
    phase: PHASE,
    release: RELEASE,
    status: String(response.status),
    upstream: upstreamName(response),
  };

  check(response, { 'OPS-02 public probe가 성공한다': () => succeeded });
  requests.add(1, tags);
  requestErrors.add(!succeeded, tags);
  requestDuration.add(response.timings.duration, tags);
}
