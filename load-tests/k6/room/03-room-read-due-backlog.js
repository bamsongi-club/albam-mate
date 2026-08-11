import { sleep } from 'k6';
import http from 'k6/http';

import {
  baseUrl,
  checkPageResponse,
  correctnessThresholds,
  loadRuntime,
  readParams,
  readScenarioOptions,
  recordResponse,
  runVuLocalWarmup,
  sessionFor,
} from './common.js';

const runtime = loadRuntime('due-backlog-read');
const configuration = runtime.manifest.configuration;

export const options = {
  scenarios: {
    due_backlog_read: readScenarioOptions(runtime.manifest, 'readWithDueBacklog', {
      endpoint: configuration.endpoint,
      due_rooms: String(configuration.dueRoomCount),
    }),
  },
  thresholds: correctnessThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  sleep(runtime.manifest.globalStartDelaySeconds);
}

function measurementPath() {
  return configuration.endpoint === 'room-list'
    ? '/api/rooms?page=0&size=20'
    : '/api/users/me/rooms?role=all&page=0&size=20';
}

function responseTags(phase) {
  return {
    phase,
    operation: 'due-backlog-read',
    endpoint: configuration.endpoint,
    due_rooms: String(configuration.dueRoomCount),
    load_profile: configuration.loadProfile,
    test_classification: runtime.manifest.classification.category,
    concurrency: String(configuration.vus),
  };
}

function prepareSessionForCurrentVu() {
  if (configuration.userKey) {
    sessionFor(runtime, configuration.userKey);
  }
}

export function readWithDueBacklog() {
  if (runVuLocalWarmup('due-backlog-read', prepareSessionForCurrentVu)) {
    return;
  }

  const session = configuration.userKey ? sessionFor(runtime, configuration.userKey) : null;
  const tags = responseTags('measure');
  const response = http.get(
    `${baseUrl()}${measurementPath()}`,
    readParams('measure', 'due-backlog-read', session),
  );
  recordResponse(response, 'measure', 200, tags);
  checkPageResponse(response, 'measure', tags);
  sleep(configuration.thinkTimeSeconds);
}
