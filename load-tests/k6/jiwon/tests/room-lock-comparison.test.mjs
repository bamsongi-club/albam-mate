import assert from 'node:assert/strict';
import test from 'node:test';

import {
  buildCampaignPlan,
  createComparisonFixturePlan,
  runtimeContractSource,
} from '../tools/room-lock-comparison.mjs';

const candidates = {
  A: '1111111111111111111111111111111111111111',
  B: '2222222222222222222222222222222222222222',
  C: '3333333333333333333333333333333333333333',
};

test('campaign plan은 A/B/C를 고정 순서가 아닌 paired/crossover로 core 10회·회귀 5회 배치한다', () => {
  const plan = buildCampaignPlan({
    campaignId: 'cmp786',
    candidates,
    seed: 'seed786',
  });

  assert.equal(plan.runs.length, 1080);
  assert.equal(plan.runs.filter((run) => run.runner === 'room-lock-comparison').length, 960);
  assert.equal(plan.runs.filter((run) => run.runner === 'portable').length, 120);
  assert.deepEqual(plan.contract.concurrencyLevels, [2, 4, 8, 16]);
  assert.equal(plan.contract.corePairedRuns, 10);
  assert.equal(plan.contract.regressionPairedRuns, 5);
  assert.equal(plan.contract.coreRunCount, 960);
  assert.equal(plan.contract.regressionRunCount, 120);
  assert.equal(plan.contract.totalRunCount, 1080);
  assert.equal(plan.contract.constantArrivalBaselineDurationSeconds, 60);
  assert.equal(plan.contract.constantArrivalTailRequestTarget, 5000);
  assert.deepEqual(plan.contract.constantArrivalTailConditions, ['constant-hot', 'constant-mixed']);
  assert.deepEqual(plan.contract.constantArrivalTailConcurrencyLevels, [8, 16]);

  const pairGroups = new Map();
  for (const run of plan.runs) {
    const group = pairGroups.get(run.pairId) || [];
    group.push(run);
    pairGroups.set(run.pairId, group);
    assert.equal(run.candidateSha, candidates[run.candidate]);
    assert.match(run.runId, /^[a-z0-9][a-z0-9._-]{0,79}$/);
  }
  assert.equal(pairGroups.size, 360);
  for (const group of pairGroups.values()) {
    assert.deepEqual(
      group.map((run) => run.candidate).sort(),
      ['A', 'B', 'C'].sort(),
    );
    assert.deepEqual(group.map((run) => run.sequence).sort(), [1, 2, 3]);
  }
  assert.ok(
    [...pairGroups.values()].some((group) => group.map((run) => run.candidate).join('') !== 'ABC'),
    '모든 paired run이 A→B→C 고정 순서이면 안 됩니다.',
  );
  const t5Cases = new Set(
    plan.runs
      .filter((run) => run.scenario === 't5')
      .map((run) => `${run.condition.options.t5Role}-${run.condition.options.t5Scale}`),
  );
  assert.deepEqual([...t5Cases].sort(), [
    'host-1', 'host-10', 'participant-1', 'participant-10', 'public-1', 'public-10',
  ]);
});

test('constant mixed c16 fixture는 5000회 tail 표본을 위해 313초·5008 target을 만든다', () => {
  const plan = createComparisonFixturePlan({
    scenario: 't1',
    runId: 'cmp786-t1-constant-mixed-c16-r1-a',
    candidate: 'A',
    candidateSha: candidates.A,
    conditionId: 'constant-mixed',
    executionModel: 'constant-arrival-rate',
    distribution: 'mixed',
    concurrency: 16,
  });

  assert.equal(plan.rounds, 313);
  assert.equal(plan.arrivalRate, 16);
  assert.equal(plan.durationSeconds, 313);
  assert.equal(plan.tailRequestTarget, 5000);
  assert.equal(plan.minimumValidSamples, 5008);
  assert.equal(plan.targets.length, 5008);
  assert.equal(plan.targets.filter((target) => target.distribution === 'hot').length, 2504);
  assert.equal(plan.targets.filter((target) => target.distribution === 'spread').length, 2504);
  assert.equal(new Set(plan.targets.map((target) => target.roomKey)).size, 2817);
  assert.match(runtimeContractSource(plan), /executor: 'constant-arrival-rate'/);
  assert.match(runtimeContractSource(plan), /iterationInTest/);
});

test('constant mixed c4 fixture는 기존 60초·240 요청 baseline을 유지한다', () => {
  const plan = createComparisonFixturePlan({
    scenario: 't1',
    runId: 'cmp1026-t1-constant-mixed-c4-r1-a',
    candidate: 'A',
    candidateSha: candidates.A,
    conditionId: 'constant-mixed',
    executionModel: 'constant-arrival-rate',
    distribution: 'mixed',
    concurrency: 4,
  });

  assert.equal(plan.rounds, 60);
  assert.equal(plan.durationSeconds, 60);
  assert.equal(plan.minimumValidSamples, 240);
  assert.equal(plan.targets.length, 240);
});

test('T1 barrier fixture는 T1 cancel/promotion fixture를 만든다', () => {
  const plan = createComparisonFixturePlan({
    scenario: 't1',
    runId: 'cmp786-t1-barrier-hot-c2-r1-a',
    candidate: 'A',
    candidateSha: candidates.A,
    conditionId: 'barrier-hot',
    executionModel: 'barrier',
    distribution: 'hot',
    concurrency: 2,
  });

  assert.equal(plan.scenario, 't1');
  assert.match(plan.users[0].key, /^t1-/);
  assert.match(plan.rooms[0].key, /^t1-/);
  assert.equal(plan.targets[0].actorKey.startsWith('t1-'), true);
});

test('T2 mixed c16은 hot 경합과 spread 대조군을 같은 fixture에 보존한다', () => {
  const plan = createComparisonFixturePlan({
    scenario: 't2',
    runId: 'cmp786-t2-constant-mixed-c16-r1-b',
    candidate: 'B',
    candidateSha: candidates.B,
    conditionId: 'constant-mixed',
    executionModel: 'constant-arrival-rate',
    distribution: 'mixed',
    concurrency: 16,
  });

  assert.equal(plan.options.subcase, 'distinct');
  assert.equal(plan.targets.length, 5008);
  assert.equal(plan.targets.filter((target) => target.distribution === 'hot').length, 2504);
  assert.equal(plan.targets.filter((target) => target.distribution === 'spread').length, 2504);
  const targetCountByRoom = new Map();
  for (const target of plan.targets) {
    targetCountByRoom.set(target.roomKey, (targetCountByRoom.get(target.roomKey) || 0) + 1);
  }
  assert.ok([...targetCountByRoom.values()].some((count) => count === 8));
  assert.ok([...targetCountByRoom.values()].some((count) => count === 1));
});

test('지원하지 않는 concurrency와 condition 조합은 bundle plan에서 거절한다', () => {
  assert.throws(
    () => createComparisonFixturePlan({
      scenario: 't1',
      runId: 'cmp786-invalid-c3',
      candidate: 'A',
      candidateSha: candidates.A,
      conditionId: 'barrier-hot',
      executionModel: 'barrier',
      distribution: 'hot',
      concurrency: 3,
    }),
    /2, 4, 8, 16 중 하나/,
  );
  assert.throws(
    () => createComparisonFixturePlan({
      scenario: 't1',
      runId: 'cmp786-invalid-condition',
      candidate: 'A',
      candidateSha: candidates.A,
      conditionId: 'barrier-hot',
      executionModel: 'constant-arrival-rate',
      distribution: 'hot',
      concurrency: 2,
    }),
    /실행 모델·분포와 입력이 다릅니다/,
  );
});
