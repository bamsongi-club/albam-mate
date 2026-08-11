import http from 'k6/http';

import {
  actorIndex,
  baseUrl,
  checkMutationResponse,
  correctnessThresholds,
  currentConfiguration,
  loadRuntime,
  mutationParams,
  recordResponse,
  sessionFor,
  waitUntil,
  waveScenarioOptions,
  waveTarget,
} from './common.js';

const runtime = loadRuntime('cancel-promotion');

export const options = {
  scenarios: waveScenarioOptions(runtime.manifest, 'cancelPromotion'),
  thresholds: correctnessThresholds,
  summaryTrendStats: ['avg', 'min', 'med', 'p(90)', 'p(95)', 'p(99)', 'max'],
};

export function setup() {
  return { epochMillis: Date.now() + runtime.manifest.globalStartDelaySeconds * 1000 };
}

export function cancelPromotion(data) {
  const configuration = currentConfiguration(runtime.manifest);
  const currentActorIndex = actorIndex();

  for (let wave = 0; wave < configuration.waveCount; wave += 1) {
    const target = waveTarget(configuration, wave, currentActorIndex);
    const phase = wave < configuration.warmupWaves ? 'warmup' : 'measure';
    const session = sessionFor(runtime, target.userKey);
    const scheduledAt = data.epochMillis
      + configuration.startOffsetSeconds * 1000
      + configuration.startDelaySeconds * 1000
      + wave * configuration.waveIntervalSeconds * 1000;
    waitUntil(scheduledAt);

    const tags = {
      phase,
      operation: 'cancel-promotion',
      load_shape: configuration.mode,
      load_profile: configuration.loadProfile,
      test_classification: runtime.manifest.classification.category,
      concurrency: String(configuration.vus),
    };
    const response = http.del(
      `${baseUrl()}/api/rooms/${target.roomId}/participants/me`,
      null,
      mutationParams(session, phase, 'cancel-promotion', 200),
    );
    recordResponse(response, phase, 200, tags, true);
    checkMutationResponse(response, phase, 200, (body) => Number(body?.data?.roomId) === target.roomId
      && body?.data?.participationStatus === 'CANCELED', tags);
  }
}
