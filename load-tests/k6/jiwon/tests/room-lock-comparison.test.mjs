import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import test from 'node:test';
import {
  mkdtempSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';

import {
  aggregateCampaign,
  buildCampaignPlan,
  createComparisonFixturePlan,
  renderBundle,
  renderRegressionBundle,
  runtimeContractSource,
} from '../tools/room-lock-comparison.mjs';

const candidates = {
  A: '1111111111111111111111111111111111111111',
  B: '2222222222222222222222222222222222222222',
  C: '3333333333333333333333333333333333333333',
};

function createCleanCandidateCheckout() {
  const root = mkdtempSync(path.join(tmpdir(), 'room-lock-candidate-'));
  writeFileSync(path.join(root, 'README.md'), 'candidate checkout\n');
  execFileSync('git', ['init', '--quiet'], { cwd: root });
  execFileSync('git', ['add', 'README.md'], { cwd: root });
  execFileSync(
    'git',
    [
      '-c', 'user.email=room-lock-test@example.invalid',
      '-c', 'user.name=ROOM lock test',
      'commit', '--quiet', '-m', 'test: create candidate checkout',
    ],
    { cwd: root },
  );
  const sha = execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, encoding: 'utf8' }).trim();
  return { root, sha };
}

function withFixturePasswordHash(callback) {
  const previous = process.env.ROOM_K6_FIXTURE_PASSWORD_HASH;
  process.env.ROOM_K6_FIXTURE_PASSWORD_HASH = '{bcrypt}$2a$10$provenance-test';
  try {
    return callback();
  } finally {
    if (previous === undefined) {
      delete process.env.ROOM_K6_FIXTURE_PASSWORD_HASH;
    } else {
      process.env.ROOM_K6_FIXTURE_PASSWORD_HASH = previous;
    }
  }
}

function validateBundleThroughCopiedTool(bundlePath) {
  return execFileSync(
    process.execPath,
    [
      path.join(bundlePath, 'tools', 'fixture.mjs'),
      'validate',
      '--for-execution',
      '--bundle',
      bundlePath,
    ],
    { cwd: bundlePath, encoding: 'utf8' },
  );
}

function aggregateReportFor({ contract = { corePairedRuns: 1 }, roomRequests = 2, droppedIterations = 0, httpRequests = 99, finalStatus = 'PASS' }) {
  const root = mkdtempSync(path.join(tmpdir(), 'room-lock-aggregate-'));
  const bundle = path.join(root, 'bundle');
  mkdirSync(bundle, { recursive: true });
  const run = {
    runId: 'aggregate-test-t1-constant-mixed-c8-r1-a',
    pairId: 'aggregate-test-t1-constant-mixed-c8-r1',
    sequence: 1,
    scenario: 't1',
    candidate: 'A',
    candidateSha: candidates.A,
    repetition: 1,
    condition: {
      id: 'constant-mixed',
      executionModel: 'constant-arrival-rate',
      concurrency: 8,
      minimumValidSamples: 2,
    },
    runner: 'room-lock-comparison',
    bundlePath: bundle,
  };
  const planPath = path.join(root, 'plan.json');
  writeFileSync(path.join(bundle, 'final-result.json'), JSON.stringify({ status: finalStatus, issues: [] }));
  writeFileSync(path.join(bundle, 'k6-summary.json'), JSON.stringify({
    metrics: {
      room_requests: { values: { count: roomRequests } },
      room_success: { values: { count: roomRequests } },
      http_reqs: { values: { count: httpRequests } },
      dropped_iterations: { values: { count: droppedIterations } },
    },
  }));
  writeFileSync(planPath, JSON.stringify({
    schemaVersion: 1,
    campaignId: 'aggregate-test',
    candidates,
    ...(contract === undefined ? {} : { contract }),
    runs: [run],
  }));

  try {
    return aggregateCampaign({ plan: planPath });
  } finally {
    rmSync(root, { recursive: true, force: true });
  }
}

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
  assert.equal('constantArrivalDurationSeconds' in plan.contract, false);

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

test('constant hot c8 fixture는 625초·5000개 hot target을 만든다', () => {
  const plan = createComparisonFixturePlan({
    scenario: 't1',
    runId: 'cmp1026-t1-constant-hot-c8-r1-a',
    candidate: 'A',
    candidateSha: candidates.A,
    conditionId: 'constant-hot',
    executionModel: 'constant-arrival-rate',
    distribution: 'hot',
    concurrency: 8,
  });

  assert.equal(plan.rounds, 625);
  assert.equal(plan.durationSeconds, 625);
  assert.equal(plan.minimumValidSamples, 5000);
  assert.equal(plan.tailRequestTarget, 5000);
  assert.equal(plan.targets.length, 5000);
  assert.ok(plan.targets.every((target) => target.distribution === 'hot'));
});

test('constant mixed c8 fixture는 정확히 625초·5000개 표본을 고정한다', () => {
  const plan = createComparisonFixturePlan({
    scenario: 't1',
    runId: 'cmp1026-t1-constant-mixed-c8-r1-a',
    candidate: 'A',
    candidateSha: candidates.A,
    conditionId: 'constant-mixed',
    executionModel: 'constant-arrival-rate',
    distribution: 'mixed',
    concurrency: 8,
  });

  assert.equal(plan.durationSeconds, 625);
  assert.equal(plan.minimumValidSamples, 5000);
  assert.equal(plan.tailRequestTarget, 5000);
  assert.equal(plan.targets.length, 5000);
  assert.equal(plan.targets.filter((target) => target.distribution === 'hot').length, 2500);
  assert.equal(plan.targets.filter((target) => target.distribution === 'spread').length, 2500);
});

test('constant-arrival sample gate는 setup http_reqs가 아니라 measurement room_requests를 사용한다', () => {
  const report = aggregateReportFor({ roomRequests: 2, droppedIterations: 0, httpRequests: 99 });
  const metric = report.candidates.A.conditions['t1/constant-mixed/c8'].runs[0];

  assert.equal(report.status, 'PASS');
  assert.equal(metric.requestCount, 2);
  assert.equal(metric.droppedIterations, 0);
  assert.equal(metric.validSampleGate, true);
  assert.deepEqual(metric.sampleGateIssues, []);
});

test('constant-arrival sample mismatch와 dropped iteration은 run·campaign을 INVALID로 만든다', () => {
  const report = aggregateReportFor({ roomRequests: 1, droppedIterations: 1 });
  const condition = report.candidates.A.conditions['t1/constant-mixed/c8'];
  const metric = condition.runs[0];
  const failedRunReport = aggregateReportFor({
    roomRequests: 1,
    droppedIterations: 1,
    finalStatus: 'FAIL',
  });
  const failedRunMetric = failedRunReport.candidates.A.conditions['t1/constant-mixed/c8'].runs[0];

  assert.equal(report.status, 'INVALID');
  assert.equal(metric.status, 'INVALID');
  assert.equal(metric.validSampleGate, false);
  assert.equal(metric.sampleGateIssues.length, 2);
  assert.equal(report.excludedRuns[0].reason, 'INVALID');
  assert.equal(failedRunMetric.status, 'INVALID');
});

test('aggregate requiredCoreRuns는 신규·구형·누락 contract fallback을 각각 적용한다', () => {
  const current = aggregateReportFor({ contract: { corePairedRuns: 1 } });
  const legacy = aggregateReportFor({ contract: { minPairedRuns: 1 } });
  const missing = aggregateReportFor({ contract: {} });

  assert.equal(current.status, 'PASS');
  assert.equal(legacy.status, 'PASS');
  assert.equal(missing.status, 'INVALID');
  assert.equal(missing.candidates.A.conditions['t1/constant-mixed/c8'].requiredRuns, 10);
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

test('comparison bundle은 후보 checkout이 없어도 공통 runner로 self-validation한다', () => {
  const candidate = createCleanCandidateCheckout();
  try {
    withFixturePasswordHash(() => {
      const result = renderBundle({
        scenario: 't1',
        runId: 'cmp1032-t1-barrier-hot-c2-r1-a',
        candidate: 'A',
        candidateSha: candidate.sha,
        condition: 'barrier-hot',
        concurrency: 2,
        sourceSha: candidate.sha,
        appRoot: candidate.root,
      });

      const validation = validateBundleThroughCopiedTool(result.bundlePath);
      assert.match(validation, /cmp1032-t1-barrier-hot-c2-r1-a/);
      assert.equal(
        readFileSync(path.join(result.bundlePath, 'tools', 'fixture-model.mjs'), 'utf8'),
        readFileSync(path.resolve('load-tests/k6/jiwon/tools/fixture-model.mjs'), 'utf8'),
      );
    });
  } finally {
    rmSync(candidate.root, { recursive: true, force: true });
  }
});

test('portable regression bundle은 공통 runner runtime으로 self-validation한다', () => {
  const candidate = createCleanCandidateCheckout();
  try {
    withFixturePasswordHash(() => {
      const result = renderRegressionBundle({
        regression: 'regression-t3-race',
        runId: 'cmp1032-regression-t3-race-r1-a',
        candidate: 'A',
        candidateSha: candidate.sha,
        sourceSha: candidate.sha,
        appRoot: candidate.root,
      });

      const validation = validateBundleThroughCopiedTool(result.bundlePath);
      assert.match(validation, /cmp1032-regression-t3-race-r1-a/);
      assert.equal(
        readFileSync(path.join(result.bundlePath, 'tools', 'portable-bundle.mjs'), 'utf8'),
        readFileSync(path.resolve('load-tests/k6/jiwon/tools/portable-bundle.mjs'), 'utf8'),
      );
    });
  } finally {
    rmSync(candidate.root, { recursive: true, force: true });
  }
});
