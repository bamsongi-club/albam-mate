export function writeOptions(runtime, vus, iterations = runtime.fixture.options.rounds) {
  const maxDuration = runtime.sessionWarmupSeconds + (runtime.roundIntervalSeconds * iterations) + 30;
  return {
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
    },
  };
}
