import { sleep } from 'k6';
import http from 'k6/http';

import {
  baseUrl,
  checkWaitlistPositionResponse,
  correctnessThresholds,
  loadRuntime,
  readParams,
  recordResponse,
  sessionFor,
} from './common.js';

const runtime = loadRuntime('waitlist-position');
const configuration = runtime.manifest.configuration;

export const options = {
  scenarios: {
    waitlist_position: {
      executor: 'constant-vus',
      exec: 'readWaitlistPosition',
      vus: configuration.vus,
      duration: configuration.duration,
      gracefulStop: '5s',
      tags: {
        queue_length: String(configuration.queueLength),
        queue_position: configuration.position,
      },
    },
  },
  thresholds: correctnessThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const session = sessionFor(runtime, configuration.userKey);
  const path = `/api/rooms/${configuration.roomId}/waitlist/me`;
  const warmupResponse = http.get(
    `${baseUrl()}${path}`,
    readParams('warmup', 'waitlist-position', session),
  );
  checkWaitlistPositionResponse(
    warmupResponse,
    'warmup',
    configuration.roomId,
    configuration.expectedPosition,
  );
}

export function readWaitlistPosition() {
  const session = sessionFor(runtime, configuration.userKey);
  const path = `/api/rooms/${configuration.roomId}/waitlist/me`;
  const tags = {
    phase: 'measure',
    operation: 'waitlist-position',
    queue_length: String(configuration.queueLength),
    queue_position: configuration.position,
  };
  const response = http.get(`${baseUrl()}${path}`, readParams('measure', 'waitlist-position', session));
  recordResponse(response, 'measure', 200, tags);
  checkWaitlistPositionResponse(
    response,
    'measure',
    configuration.roomId,
    configuration.expectedPosition,
  );
  sleep(configuration.thinkTimeSeconds);
}
