import { sleep } from 'k6';
import http from 'k6/http';

import {
  baseUrl,
  checkRoomDetailResponse,
  correctnessThresholds,
  loadRuntime,
  readParams,
  readScenarioOptions,
  recordResponse,
  runVuLocalWarmup,
  sessionFor,
} from './common.js';

const runtime = loadRuntime('room-detail');
const configuration = runtime.manifest.configuration;

export const options = {
  scenarios: {
    room_detail: readScenarioOptions(runtime.manifest, 'readRoomDetail', {
      requester_role: configuration.role,
      active_participants: String(configuration.activeParticipantCount),
    }),
  },
  thresholds: correctnessThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  const session = configuration.userKey ? sessionFor(runtime, configuration.userKey) : null;
  const warmupTags = responseTags('warmup');
  const warmupResponse = http.get(
    `${baseUrl()}/api/rooms/${configuration.roomId}`,
    readParams('warmup', 'room-detail', session),
  );
  checkRoomDetailResponse(warmupResponse, 'warmup', configuration, warmupTags);
}

function responseTags(phase) {
  return {
    phase,
    operation: 'room-detail',
    requester_role: configuration.role,
    active_participants: String(configuration.activeParticipantCount),
    load_profile: configuration.loadProfile,
    test_classification: runtime.manifest.classification.category,
    concurrency: String(configuration.vus),
  };
}

export function readRoomDetail() {
  if (runVuLocalWarmup('room-detail', () => {
    const warmupSession = configuration.userKey ? sessionFor(runtime, configuration.userKey) : null;
    const warmupResponse = http.get(
      `${baseUrl()}/api/rooms/${configuration.roomId}`,
      readParams('warmup', 'room-detail', warmupSession),
    );
    checkRoomDetailResponse(warmupResponse, 'warmup', configuration, responseTags('warmup'));
  })) {
    return;
  }

  const session = configuration.userKey ? sessionFor(runtime, configuration.userKey) : null;
  const tags = responseTags('measure');
  const response = http.get(
    `${baseUrl()}/api/rooms/${configuration.roomId}`,
    readParams('measure', 'room-detail', session),
  );
  recordResponse(response, 'measure', 200, tags);
  checkRoomDetailResponse(response, 'measure', configuration, tags);
  sleep(configuration.thinkTimeSeconds);
}
