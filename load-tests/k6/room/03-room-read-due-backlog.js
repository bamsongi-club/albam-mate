import { sleep } from 'k6';
import http from 'k6/http';

import {
  baseUrl,
  checkDueBacklogProbeResponse,
  checkPageResponse,
  correctnessThresholds,
  loadRuntime,
  readParams,
  recordResponse,
  sessionFor,
} from './common.js';

const runtime = loadRuntime('due-backlog-read');
const configuration = runtime.manifest.configuration;

export const options = {
  scenarios: {
    due_backlog_read: {
      executor: 'constant-vus',
      exec: 'readWithDueBacklog',
      vus: configuration.vus,
      duration: configuration.duration,
      gracefulStop: '5s',
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
  const session = configuration.userKey ? sessionFor(runtime, configuration.userKey) : null;
  const warmupResponse = http.get(
    `${baseUrl()}${measurementPath()}`,
    readParams('warmup', 'due-backlog-read', session),
  );
  checkPageResponse(warmupResponse, 'warmup');

  if (configuration.endpoint === 'room-list') {
    probePublicEffectiveStatuses(session);
  } else {
    probeMyRoomEffectiveStatuses(session);
  }

  sleep(runtime.manifest.globalStartDelaySeconds);
}

function measurementPath() {
  return configuration.endpoint === 'room-list'
    ? '/api/rooms?page=0&size=20'
    : '/api/users/me/rooms?role=all&page=0&size=20';
}

function probePublicEffectiveStatuses(session) {
  const measureKeyword = encodeURIComponent(`ROOM-K6:${runtime.manifest.fixtureId}:measure:`);
  const closedResponse = http.get(
    `${baseUrl()}/api/rooms?status=CLOSED&keyword=${measureKeyword}&page=0&size=20`,
    readParams('probe', 'due-backlog-effective-status', session),
  );
  checkDueBacklogProbeResponse(
    closedResponse,
    'probe',
    'CLOSED',
    configuration.recruitingDueRoomCount,
  );

  const controlKeyword = encodeURIComponent(`ROOM-K6:${runtime.manifest.fixtureId}:control:`);
  const recruitingResponse = http.get(
    `${baseUrl()}/api/rooms?status=RECRUITING&keyword=${controlKeyword}&page=0&size=20`,
    readParams('probe', 'due-backlog-effective-status', session),
  );
  checkDueBacklogProbeResponse(
    recruitingResponse,
    'probe',
    'RECRUITING',
    configuration.controlRoomCount,
  );
}

function probeMyRoomEffectiveStatuses(session) {
  const totalElements = configuration.controlRoomCount + configuration.dueRoomCount;
  const probes = [
    { page: 0, status: 'RECRUITING', chatAvailable: true },
    { page: configuration.controlRoomCount, status: 'CLOSED', chatAvailable: true },
  ];
  if (configuration.closedDueRoomCount > 0) {
    probes.push({
      page: configuration.controlRoomCount + configuration.recruitingDueRoomCount,
      status: 'FINISHED',
      chatAvailable: false,
    });
  }

  for (const probe of probes) {
    const response = http.get(
      `${baseUrl()}/api/users/me/rooms?role=all&page=${probe.page}&size=1`,
      readParams('probe', 'due-backlog-effective-status', session),
    );
    checkDueBacklogProbeResponse(
      response,
      'probe',
      probe.status,
      totalElements,
      probe.chatAvailable,
    );
  }
}

export function readWithDueBacklog() {
  const session = configuration.userKey ? sessionFor(runtime, configuration.userKey) : null;
  const tags = {
    phase: 'measure',
    operation: 'due-backlog-read',
    endpoint: configuration.endpoint,
    due_rooms: String(configuration.dueRoomCount),
    concurrency: String(configuration.vus),
  };
  const response = http.get(
    `${baseUrl()}${measurementPath()}`,
    readParams('measure', 'due-backlog-read', session),
  );
  recordResponse(response, 'measure', 200, tags);
  checkPageResponse(response, 'measure');
  sleep(configuration.thinkTimeSeconds);
}
