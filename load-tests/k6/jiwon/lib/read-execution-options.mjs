function integerEnvironment(environment, name, fallback, minimum, maximum) {
  const raw = String(environment[name] ?? '').trim();
  const value = raw ? Number(raw) : fallback;
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name}은(는) ${minimum} 이상 ${maximum} 이하의 정수여야 합니다.`);
  }
  return value;
}

export function readExecutionOptions(environment) {
  return {
    vus: integerEnvironment(environment, 'ROOM_K6_READ_VUS', 10, 1, 500),
    durationSeconds: integerEnvironment(environment, 'ROOM_K6_READ_DURATION_SECONDS', 60, 5, 3600),
    thinkTimeMilliseconds: integerEnvironment(environment, 'ROOM_K6_READ_THINK_TIME_MS', 0, 0, 10000),
  };
}
