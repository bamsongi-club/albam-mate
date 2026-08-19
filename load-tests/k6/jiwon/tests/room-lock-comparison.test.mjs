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

test('campaign plan은 A/B/C를 고정 순서가 아닌 paired/crossover로 5회씩 배치한다', () => {
  const plan = buildCampaignPlan({
    campaignId: 'cmp786',
    candidates,
    seed: 'seed786',
  });

  assert.equal(plan.runs.length, 525);
  assert.equal(plan.runs.filter((run) => run.runner === 'room-lock-comparison').length, 480);
  assert.equal(plan.runs.filter((run) => run.runner === 'portable').length, 45);
  assert.deepEqual(plan.contract.concurrencyLevels, [2, 4, 8, 16]);
  assert.equal(plan.contract.minPairedRuns, 5);

  const pairGroups = new Map();
  for (const run of plan.runs) {
    const group = pairGroups.get(run.pairId) || [];
    group.push(run);
    pairGroups.set(run.pairId, group);
    assert.equal(run.candidateSha, candidates[run.candidate]);
    assert.match(run.runId, /^[a-z0-9][a-z0-9._-]{0,79}$/);
  }
  assert.equal(pairGroups.size, 175);
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
});

test('constant mixed c16 fixture는 60초·16 req/s의 50:50 hot/spread target을 만든다', () => {
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

  assert.equal(plan.rounds, 60);
  assert.equal(plan.arrivalRate, 16);
  assert.equal(plan.durationSeconds, 60);
  assert.equal(plan.minimumValidSamples, 960);
  assert.equal(plan.targets.length, 960);
  assert.equal(plan.targets.filter((target) => target.distribution === 'hot').length, 480);
  assert.equal(plan.targets.filter((target) => target.distribution === 'spread').length, 480);
  assert.equal(new Set(plan.targets.map((target) => target.roomKey)).size, 540);
  assert.match(runtimeContractSource(plan), /executor: 'constant-arrival-rate'/);
  assert.match(runtimeContractSource(plan), /iterationInTest/);
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
  assert.equal(plan.targets.length, 960);
  assert.equal(plan.targets.filter((target) => target.distribution === 'hot').length, 480);
  assert.equal(plan.targets.filter((target) => target.distribution === 'spread').length, 480);
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
