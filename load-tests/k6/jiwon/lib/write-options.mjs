import { START_SKEW_THRESHOLD } from './start-skew.mjs';

export function writeOptions(runtime, vus, iterations = runtime.fixture.options.rounds) {
  const maxDuration = runtime.sessionWarmupSeconds + (runtime.roundIntervalSeconds * iterations) + 30;
  return {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
    scenarios: {
      room_write: {
        executor: 'per-vu-iterations',
        vus,
        iterations,
        maxDuration: `${maxDuration}s`,
      },
    },
    thresholds: {
      room_contract_failures: ['count==0'],
      room_unexpected_4xx: ['count==0'],
      room_server_failures: ['count==0'],
      room_start_skew_ms: [START_SKEW_THRESHOLD],
    },
  };
}
