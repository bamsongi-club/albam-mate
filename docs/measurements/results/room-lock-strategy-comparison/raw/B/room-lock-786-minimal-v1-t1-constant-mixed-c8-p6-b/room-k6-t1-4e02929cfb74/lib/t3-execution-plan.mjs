function fail(message) {
  throw new Error(message);
}

function assertT3Fixture(fixture) {
  if (fixture?.options?.scenario !== 't3') {
    fail('T3 execution plan에는 t3 fixture가 필요합니다.');
  }
  if (!Array.isArray(fixture.targets) || fixture.targets.length === 0) {
    fail('T3 fixture target이 비어 있습니다.');
  }
}

function targetForRound(fixture, round) {
  const target = fixture.targets.find((candidate) => candidate.round === round);
  if (!target) {
    fail(`round=${round} T3 fixture target을 찾지 못했습니다.`);
  }
  return target;
}

function assertPositiveInteger(value, name) {
  if (!Number.isInteger(value) || value < 1) {
    fail(`${name}은(는) 1 이상의 정수여야 합니다.`);
  }
}

export function t3ExecutionPlan(fixture) {
  assertT3Fixture(fixture);
  if (fixture.options.t3Mode === 'race') {
    return { vus: fixture.targets.length * 2, iterations: 1 };
  }
  if (fixture.options.t3Mode === 'wait-first' || fixture.options.t3Mode === 'cancel-first') {
    return { vus: 1, iterations: fixture.options.rounds };
  }
  fail(`지원하지 않는 T3 mode: ${fixture.options.t3Mode}`);
}

export function t3SequentialRequestOrder(fixture) {
  assertT3Fixture(fixture);
  if (fixture.options.t3Mode === 'race') {
    return null;
  }
  if (fixture.options.t3Mode === 'wait-first') {
    return ['wait', 'cancel'];
  }
  if (fixture.options.t3Mode === 'cancel-first') {
    return ['cancel', 'wait'];
  }
  fail(`지원하지 않는 T3 mode: ${fixture.options.t3Mode}`);
}

export function t3ExecutionAssignment(fixture, vuId, iteration) {
  assertT3Fixture(fixture);
  assertPositiveInteger(vuId, 'VU ID');
  if (!Number.isInteger(iteration) || iteration < 0) {
    fail('iteration은 0 이상의 정수여야 합니다.');
  }

  if (fixture.options.t3Mode === 'wait-first' || fixture.options.t3Mode === 'cancel-first') {
    return {
      target: targetForRound(fixture, iteration),
      role: 'sequence',
      barrierRound: iteration,
    };
  }
  if (fixture.options.t3Mode !== 'race') {
    fail(`지원하지 않는 T3 mode: ${fixture.options.t3Mode}`);
  }

  const pairRound = Math.floor((vuId - 1) / 2);
  return {
    target: targetForRound(fixture, pairRound),
    role: vuId % 2 === 1 ? 'wait' : 'cancel',
    barrierRound: 0,
  };
}
