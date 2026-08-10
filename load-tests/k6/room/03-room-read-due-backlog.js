import http from 'k6/http';

import {
  baseUrl,
  checkPageResponse,
  correctnessThresholds,
  loadRuntime,
  readParams,
  recordResponse,
  sessionFor,
  waitUntil,
} from './common.js';

const runtime = loadRuntime('due-backlog-read');
const configuration = runtime.manifest.configuration;

export const options = {
  scenarios: {
    due_backlog_read: {
      executor: 'per-vu-iterations',
      exec: 'readWithDueBacklog',
      vus: configuration.vus,
      iterations: 1,
      maxDuration: '1m',
      tags: {
        endpoint: configuration.endpoint,
        due_rooms: String(configuration.dueRoomCount),
      },
    },
  },
  thresholds: correctnessThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { epochMillis: Date.now() + runtime.manifest.globalStartDelaySeconds * 1000 };
}

export function readWithDueBacklog(data) {
  const session = configuration.userKey ? sessionFor(runtime, configuration.userKey) : null;
  waitUntil(data.epochMillis);

  const path = configuration.endpoint === 'room-list'
    ? '/api/rooms?page=0&size=20'
    : '/api/users/me/rooms?role=all&page=0&size=20';
  const tags = {
    phase: 'measure',
    operation: 'due-backlog-read',
    endpoint: configuration.endpoint,
    due_rooms: String(configuration.dueRoomCount),
    concurrency: String(configuration.vus),
  };
  const response = http.get(
    `${baseUrl()}${path}`,
    readParams('measure', 'due-backlog-read', session, true),
  );
  recordResponse(response, 'measure', 200, tags, true);
  checkPageResponse(response, 'measure');
}
