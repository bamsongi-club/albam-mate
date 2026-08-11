import { sleep } from 'k6';
import http from 'k6/http';

import {
  baseUrl,
  checkWaitlistPositionResponse,
  correctnessThresholds,
  loadRuntime,
  readParams,
  recordResponse,
  runVuLocalWarmup,
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
        load_profile: configuration.loadProfile,
        test_classification: runtime.manifest.classification.category,
      },
    },
  },
  thresholds: correctnessThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const session = sessionFor(runtime, configuration.userKey);
  const path = `/api/rooms/${configuration.roomId}/waitlist/me`;
  const warmupTags = responseTags('warmup');
  const warmupResponse = http.get(
    `${baseUrl()}${path}`,
    readParams('warmup', 'waitlist-position', session),
  );
  checkWaitlistPositionResponse(
    warmupResponse,
    'warmup',
    configuration.roomId,
    configuration.expectedPosition,
    warmupTags,
  );
}

function responseTags(phase) {
  return {
    phase,
    operation: 'waitlist-position',
    queue_length: String(configuration.queueLength),
    queue_position: configuration.position,
    load_profile: configuration.loadProfile,
    test_classification: runtime.manifest.classification.category,
    concurrency: String(configuration.vus),
  };
}

export function readWaitlistPosition() {
  if (runVuLocalWarmup('waitlist-position', () => {
    const warmupSession = sessionFor(runtime, configuration.userKey);
    const warmupPath = `/api/rooms/${configuration.roomId}/waitlist/me`;
    const warmupResponse = http.get(
      `${baseUrl()}${warmupPath}`,
      readParams('warmup', 'waitlist-position', warmupSession),
    );
    checkWaitlistPositionResponse(
      warmupResponse,
      'warmup',
      configuration.roomId,
      configuration.expectedPosition,
      responseTags('warmup'),
    );
  })) {
    return;
  }

  const session = sessionFor(runtime, configuration.userKey);
  const path = `/api/rooms/${configuration.roomId}/waitlist/me`;
  const tags = responseTags('measure');
  const response = http.get(`${baseUrl()}${path}`, readParams('measure', 'waitlist-position', session));
  recordResponse(response, 'measure', 200, tags);
  checkWaitlistPositionResponse(
    response,
    'measure',
    configuration.roomId,
    configuration.expectedPosition,
    tags,
  );
  sleep(configuration.thinkTimeSeconds);
}
