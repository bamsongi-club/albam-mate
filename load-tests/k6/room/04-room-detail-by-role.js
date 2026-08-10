import { sleep } from 'k6';
import http from 'k6/http';

import {
  baseUrl,
  checkRoomDetailResponse,
  correctnessThresholds,
  loadRuntime,
  readParams,
  recordResponse,
  sessionFor,
} from './common.js';

const runtime = loadRuntime('room-detail');
const configuration = runtime.manifest.configuration;

export const options = {
  scenarios: {
    room_detail: {
      executor: 'constant-vus',
      exec: 'readRoomDetail',
      vus: configuration.vus,
      duration: configuration.duration,
      gracefulStop: '5s',
      tags: {
        requester_role: configuration.role,
        active_participants: String(configuration.activeParticipantCount),
      },
    },
  },
  thresholds: correctnessThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const session = configuration.userKey ? sessionFor(runtime, configuration.userKey) : null;
  const warmupResponse = http.get(
    `${baseUrl()}/api/rooms/${configuration.roomId}`,
    readParams('warmup', 'room-detail', session),
  );
  checkRoomDetailResponse(warmupResponse, 'warmup', configuration.roomId);
}

export function readRoomDetail() {
  const session = configuration.userKey ? sessionFor(runtime, configuration.userKey) : null;
  const tags = {
    phase: 'measure',
    operation: 'room-detail',
    requester_role: configuration.role,
    active_participants: String(configuration.activeParticipantCount),
  };
  const response = http.get(
    `${baseUrl()}/api/rooms/${configuration.roomId}`,
    readParams('measure', 'room-detail', session),
  );
  recordResponse(response, 'measure', 200, tags);
  checkRoomDetailResponse(response, 'measure', configuration.roomId);
  sleep(configuration.thinkTimeSeconds);
}
