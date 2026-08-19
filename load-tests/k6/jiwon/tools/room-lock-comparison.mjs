#!/usr/bin/env node

import { createHash, randomUUID } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import {
  copyFileSync,
  existsSync,
  mkdirSync,
  readFileSync,
  writeFileSync,
} from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isDeepStrictEqual } from 'node:util';

import {
  buildCleanupSql,
  buildPrepareSql,
  buildResourceQuery,
  buildSnapshotQuery,
  evaluateFixture,
  hydrateFixture,
  normalizeRoomSummary,
  normalizePrepareOwnership,
  RUN_ID_PATTERN,
} from './fixture-model.mjs';

const toolPath = fileURLToPath(import.meta.url);
const toolDirectory = path.dirname(toolPath);
const sourceRepositoryRoot = path.resolve(toolDirectory, '../../../..');
const sourceBuildRoot = path.join(sourceRepositoryRoot, 'build', 'k6', 'room');

const BUNDLE_KIND = 'albam-mate-room-lock-comparison-bundle';
const BUNDLE_SCHEMA_VERSION = 2;
const FIXTURE_SCHEMA_VERSION = 2;
const SOURCE_SHA_PATTERN = /^[a-f0-9]{40}$/;
const SHA256_PATTERN = /^[a-f0-9]{64}$/;
const SAFE_IDENTIFIER_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/;
const CANDIDATE_LABELS = ['A', 'B', 'C'];
const CONCURRENCY_LEVELS = [2, 4, 8, 16];
const MIN_PAIRED_RUNS = 5;
const BARRIER_ROUNDS = 5;
const CONSTANT_DURATION_SECONDS = 60;
const CORE_SCENARIOS = ['t1', 't2'];
const DISTRIBUTIONS = ['hot', 'spread', 'mixed'];
const EXECUTION_MODELS = ['barrier', 'constant-arrival-rate'];

const RUNTIME_FILES = [
  'lib/room-k6.js',
  'lib/read-execution-options.mjs',
  'lib/write-options.mjs',
  'lib/t3-execution-plan.mjs',
  'lib/start-skew.mjs',
  'lib/write-response-contract.mjs',
  'tools/fixture-model.mjs',
  'tools/portable-bundle.mjs',
];

const BUNDLE_STATE_FILES = [
  'resource-output.json',
  'fixture.json',
  'snapshot.sql',
  'cleanup.sql',
  'before-snapshot.json',
  'after-snapshot.json',
  'k6-summary.json',
  'k6-console.log',
  'run-manifest.json',
  'infra-execution.json',
  'resource-signals.json',
  'before-diagnosis.json',
  'after-diagnosis.json',
  'final-result.json',
];

const CONDITION_TEMPLATES = [
  {
    id: 'barrier-hot',
    executionModel: 'barrier',
    distribution: 'hot',
    rounds: BARRIER_ROUNDS,
  },
  {
    id: 'barrier-spread',
    executionModel: 'barrier',
    distribution: 'spread',
    rounds: BARRIER_ROUNDS,
  },
  {
    id: 'constant-hot',
    executionModel: 'constant-arrival-rate',
    distribution: 'hot',
    durationSeconds: CONSTANT_DURATION_SECONDS,
  },
  {
    id: 'constant-mixed',
    executionModel: 'constant-arrival-rate',
    distribution: 'mixed',
    durationSeconds: CONSTANT_DURATION_SECONDS,
  },
];

const REGRESSION_TEMPLATES = [
  {
    id: 'regression-t3-race',
    scenario: 't3',
    executionModel: 'barrier',
    options: { profile: 'stress', rounds: 5, t3Mode: 'race' },
  },
  {
    id: 'regression-t4-c8',
    scenario: 't4',
    executionModel: 'barrier',
    concurrency: 8,
    options: { profile: 'spike', rounds: 1, concurrency: 8 },
  },
  {
    id: 'regression-t5-background',
    scenario: 't5',
    executionModel: 'read-background',
    options: {
      profile: 'spike',
      rounds: 1,
      cases: ['public-1', 'public-10', 'host-1', 'host-10', 'participant-1', 'participant-10'],
    },
  },
];

const ARTIFACTS = Object.freeze({
  fixturePlan: 'fixture-plan.json',
  prepareProvenance: 'private/prepare-provenance.json',
  prepareSql: 'prepare.sql',
  resourceQuerySql: 'resource-query.sql',
  executionOptions: 'execution-options.json',
  resourceOutput: 'resource-output.json',
  fixture: 'fixture.json',
  snapshotSql: 'snapshot.sql',
  cleanupSql: 'cleanup.sql',
  beforeSnapshot: 'before-snapshot.json',
  afterSnapshot: 'after-snapshot.json',
  summary: 'k6-summary.json',
  console: 'k6-console.log',
  runManifest: 'run-manifest.json',
  infraExecution: 'infra-execution.json',
  resourceSignals: 'resource-signals.json',
  beforeDiagnosis: 'before-diagnosis.json',
  afterDiagnosis: 'after-diagnosis.json',
  finalResult: 'final-result.json',
});

function fail(message) {
  throw new Error(message);
}

function text(value, name) {
  const result = String(value ?? '').trim();
  if (!result) {
    fail(`${name} 값이 필요합니다.`);
  }
  return result;
}

function integer(value, name, minimum, maximum) {
  const result = Number(value);
  if (!Number.isInteger(result) || result < minimum || result > maximum) {
    fail(`${name}은(는) ${minimum} 이상 ${maximum} 이하의 정수여야 합니다.`);
  }
  return result;
}

function oneOf(value, name, allowed) {
  const result = text(value, name);
  if (!allowed.includes(result)) {
    fail(`${name}은(는) ${allowed.join(', ')} 중 하나여야 합니다.`);
  }
  return result;
}

function sha256(value) {
  return createHash('sha256').update(value).digest('hex');
}

function digest(value, length = 12) {
  return sha256(value).slice(0, length);
}

function writeNewJson(filePath, value) {
  if (existsSync(filePath)) {
    fail(`기존 artifact를 덮어쓰지 않습니다: ${filePath}`);
  }
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function writeJson(filePath, value) {
  writeFileSync(filePath, `${JSON.stringify(value, null, 2)}\n`, 'utf8');
}

function writeNewText(filePath, value) {
  if (existsSync(filePath)) {
    fail(`기존 artifact를 덮어쓰지 않습니다: ${filePath}`);
  }
  writeFileSync(filePath, value, 'utf8');
}

function readJson(filePath, label) {
  try {
    return JSON.parse(readFileSync(filePath, 'utf8'));
  } catch (error) {
    fail(`${label} JSON을 읽을 수 없습니다: ${filePath} (${error.message})`);
  }
}

function parseArguments(argv) {
  const [command, ...rest] = argv;
  if (!command || command === '--help' || command === '-h') {
    return { command: 'help', values: {} };
  }

  const values = {};
  for (let index = 0; index < rest.length; index += 1) {
    const token = rest[index];
    if (!token.startsWith('--')) {
      fail(`알 수 없는 인수: ${token}`);
    }
    const key = token.slice(2).replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
    if (Object.hasOwn(values, key)) {
      fail(`${token} 옵션이 중복되었습니다.`);
    }
    if (key === 'forExecution') {
      values[key] = true;
      continue;
    }
    const value = rest[index + 1];
    if (!value || value.startsWith('--')) {
      fail(`${token} 값이 필요합니다.`);
    }
    values[key] = value;
    index += 1;
  }
  return { command, values };
}

function usage() {
  return `사용법:
  node load-tests/k6/jiwon/tools/room-lock-comparison.mjs plan \
    --campaign-id <id> --candidates-file <json> [--seed <seed>] [--output <json>]
  node load-tests/k6/jiwon/tools/room-lock-comparison.mjs render-bundle \
    --scenario t1|t2 --run-id <id> --candidate A|B|C --candidate-sha <sha> \
    --condition <condition-id> --concurrency 2|4|8|16 \
    --source-sha <sha> [--output-root <dir>]

실행 bundle 명령:
  validate --bundle <dir> [--for-execution]
  execution-options --bundle <dir>
  hydrate --bundle <dir>
  diagnose --bundle <dir> --stage before|after
  aggregate --bundle <dir>
`;
}

function safeIdentifier(value, name) {
  const result = text(value, name);
  if (!SAFE_IDENTIFIER_PATTERN.test(result)) {
    fail(`${name}은 안전한 identifier여야 합니다.`);
  }
  return result;
}

function validateSourceSha(value) {
  const result = text(value, 'source SHA').toLowerCase();
  if (!SOURCE_SHA_PATTERN.test(result)) {
    fail('source SHA는 40자리 소문자 Git SHA여야 합니다.');
  }
  return result;
}

function sourceProvenance(requestedSha) {
  const sourceRevision = validateSourceSha(requestedSha || process.env.ALBAM_MATE_SOURCE_SHA);
  const revision = spawnSync('git', ['rev-parse', 'HEAD'], {
    cwd: sourceRepositoryRoot,
    encoding: 'utf8',
  });
  const status = spawnSync('git', ['status', '--porcelain', '--untracked-files=all'], {
    cwd: sourceRepositoryRoot,
    encoding: 'utf8',
  });
  const actualRevision = String(revision.stdout || '').trim().toLowerCase();
  if (revision.status !== 0 || !SOURCE_SHA_PATTERN.test(actualRevision)) {
    fail('앱 checkout의 현재 HEAD를 확인하지 못했습니다.');
  }
  if (status.status !== 0 || String(status.stdout || '').trim()) {
    fail('변경된 앱 소스에서는 비교 bundle을 만들 수 없습니다. 커밋된 release checkout에서 다시 생성하세요.');
  }
  if (actualRevision !== sourceRevision) {
    fail('요청한 source SHA와 현재 앱 checkout HEAD가 다릅니다.');
  }
  return { sourceRevision, sourceDirty: false };
}

function candidateMap(input) {
  let value = input;
  if (typeof input === 'string') {
    value = JSON.parse(readFileSync(input, 'utf8'));
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    fail('candidate map은 A/B/C 키를 가진 JSON object여야 합니다.');
  }
  const result = {};
  for (const label of CANDIDATE_LABELS) {
    const sha = String(value[label] || '').trim().toLowerCase();
    if (!SOURCE_SHA_PATTERN.test(sha)) {
      fail(`candidate ${label}의 SHA가 40자리 소문자 SHA가 아닙니다.`);
    }
    result[label] = sha;
  }
  return result;
}

function seededOrder(seed, values) {
  return [...values].sort((left, right) => {
    const leftDigest = sha256(`${seed}:${left}`);
    const rightDigest = sha256(`${seed}:${right}`);
    return leftDigest.localeCompare(rightDigest);
  });
}

function conditionFor(id, concurrency) {
  const template = CONDITION_TEMPLATES.find((condition) => condition.id === id);
  if (!template) {
    fail(`지원하지 않는 비교 condition: ${id}`);
  }
  const arrivalRate = concurrency;
  return {
    ...template,
    concurrency,
    arrivalRate,
    durationSeconds: template.durationSeconds || null,
    rounds: template.rounds || template.durationSeconds,
    minimumValidSamples: template.executionModel === 'constant-arrival-rate'
      ? arrivalRate * template.durationSeconds
      : null,
  };
}

export function buildCampaignPlan({ campaignId, candidates, seed = campaignId }) {
  const normalizedCampaignId = safeIdentifier(campaignId, 'campaignId');
  const normalizedCandidates = candidateMap(candidates);
  const runs = [];

  for (const scenario of CORE_SCENARIOS) {
    for (const conditionTemplate of CONDITION_TEMPLATES) {
      for (const concurrency of CONCURRENCY_LEVELS) {
        const condition = conditionFor(conditionTemplate.id, concurrency);
        for (let repetition = 1; repetition <= MIN_PAIRED_RUNS; repetition += 1) {
          const pairId = `${normalizedCampaignId}-${scenario}-${condition.id}-c${concurrency}-r${repetition}`;
          const order = seededOrder(`${seed}:${pairId}`, CANDIDATE_LABELS);
          order.forEach((candidate, sequenceIndex) => {
            const runId = `${pairId}-${candidate.toLowerCase()}`;
            runs.push({
              runId,
              pairId,
              sequence: sequenceIndex + 1,
              scenario,
              candidate,
              candidateSha: normalizedCandidates[candidate],
              repetition,
              condition,
              runner: 'room-lock-comparison',
              fixtureId: createComparisonFixturePlan({
                scenario,
                runId,
                candidate,
                candidateSha: normalizedCandidates[candidate],
                conditionId: condition.id,
                executionModel: condition.executionModel,
                distribution: condition.distribution,
                concurrency,
              }).fixtureId,
            });
            if (!RUN_ID_PATTERN.test(runId)) {
              fail(`campaign runId가 80자 안전 형식을 벗어났습니다: ${runId}`);
            }
          });
        }
      }
    }
  }

  for (const regression of REGRESSION_TEMPLATES) {
    for (let repetition = 1; repetition <= MIN_PAIRED_RUNS; repetition += 1) {
      const pairId = `${normalizedCampaignId}-${regression.id}-r${repetition}`;
      const order = seededOrder(`${seed}:${pairId}`, CANDIDATE_LABELS);
      order.forEach((candidate, sequenceIndex) => {
        const runId = `${pairId}-${candidate.toLowerCase()}`;
        if (!RUN_ID_PATTERN.test(runId)) {
          fail(`campaign runId가 80자 안전 형식을 벗어났습니다: ${runId}`);
        }
        runs.push({
          runId,
          pairId,
          sequence: sequenceIndex + 1,
          scenario: regression.scenario,
          candidate,
          candidateSha: normalizedCandidates[candidate],
          repetition,
          condition: {
            id: regression.id,
            executionModel: regression.executionModel,
            concurrency: regression.concurrency || null,
            options: regression.options,
            minimumValidSamples: null,
          },
          runner: 'portable',
        });
      });
    }
  }

  return {
    schemaVersion: 1,
    campaignId: normalizedCampaignId,
    seed: safeIdentifier(seed, 'seed'),
    candidates: normalizedCandidates,
    contract: {
      minPairedRuns: MIN_PAIRED_RUNS,
      concurrencyLevels: CONCURRENCY_LEVELS,
      barrierRounds: BARRIER_ROUNDS,
      constantArrivalDurationSeconds: CONSTANT_DURATION_SECONDS,
      constantArrivalRate: 'concurrency-per-second',
      mixedDistribution: 'hot-50-percent-spread-50-percent',
      p95P99WinnerGate: 'constant-arrival-rate-only',
      regressionRunner: 'existing-portable-bundle-read-only',
    },
    runs,
  };
}

function userEmail(fixtureId, userKey) {
  return `room-k6.${fixtureId}.${userKey}@example.invalid`;
}

function nickname(fixtureId, userKey) {
  return `rk6-${fixtureId.slice(-12)}-${digest(userKey, 10)}`;
}

function roomTitle(fixtureId, roomKey) {
  return `ROOM-K6 ${fixtureId} ${roomKey}`;
}

function createPlanner(options) {
  const users = [];
  const rooms = [];
  const userKeys = new Set();
  const roomKeys = new Set();

  const user = (key) => {
    if (!userKeys.has(key)) {
      userKeys.add(key);
      users.push({
        key,
        email: userEmail(options.fixtureId, key),
        nickname: nickname(options.fixtureId, key),
      });
    }
    return key;
  };

  const room = (key, configuration) => {
    if (roomKeys.has(key)) {
      fail(`중복 ROOM fixture key: ${key}`);
    }
    roomKeys.add(key);
    rooms.push({ key, title: roomTitle(options.fixtureId, key), ...configuration });
    return key;
  };

  return { users, rooms, user, room };
}

function normalizedComparisonInput(input) {
  const scenario = oneOf(input.scenario, 'scenario', CORE_SCENARIOS);
  const runId = text(input.runId, 'runId');
  if (!RUN_ID_PATTERN.test(runId)) {
    fail('runId 형식이 안전하지 않습니다.');
  }
  const candidate = oneOf(input.candidate, 'candidate', CANDIDATE_LABELS);
  const candidateSha = validateSourceSha(input.candidateSha);
  const conditionId = text(input.conditionId, 'conditionId');
  const concurrency = integer(input.concurrency, 'concurrency', 2, 16);
  if (!CONCURRENCY_LEVELS.includes(concurrency)) {
    fail('concurrency는 2, 4, 8, 16 중 하나여야 합니다.');
  }
  const condition = conditionFor(conditionId, concurrency);
  const executionModel = oneOf(input.executionModel || condition.executionModel, 'executionModel', EXECUTION_MODELS);
  const distribution = oneOf(input.distribution || condition.distribution, 'distribution', DISTRIBUTIONS);
  if (executionModel !== condition.executionModel || distribution !== condition.distribution) {
    fail(`condition=${conditionId}의 실행 모델·분포와 입력이 다릅니다.`);
  }
  const rounds = condition.rounds;
  const arrivalRate = condition.arrivalRate;
  const durationSeconds = condition.durationSeconds;
  const subcase = scenario === 't2' ? 'distinct' : null;
  const effectiveMode = distribution === 'spread' ? 'spread' : 'hot';
  const comparison = {
    schemaVersion: 1,
    candidate,
    candidateSha,
    conditionId,
    executionModel,
    distribution,
    concurrency,
    arrivalRate,
    durationSeconds,
    rounds,
    minimumValidSamples: condition.minimumValidSamples,
  };
  const options = {
    scenario,
    runId,
    profile: 'stress',
    rounds,
    mode: effectiveMode,
    concurrency,
    ...(subcase ? { subcase } : {}),
    comparison,
  };
  const fixtureId = `room-k6-${scenario}-${digest(JSON.stringify(options))}`;
  return {
    ...comparison,
    runId,
    subcase,
    fixtureId,
    options: { ...options, fixtureId },
  };
}

function addUserTarget(targets, planner, target) {
  planner.user(target.actorKey);
  targets.push(target);
}

function addT1HotRound(options, planner, targets, round, groupSize, distribution) {
  const hostKey = planner.user(`t1-${distribution}-host`);
  const cancelKeys = Array.from(
    { length: groupSize },
    (_, index) => planner.user(`t1-${distribution}-cancel-${index}`),
  );
  const waiterKeys = Array.from(
    { length: groupSize + 1 },
    (_, index) => planner.user(`t1-${distribution}-waiter-${index}`),
  );
  const roomKey = planner.room(`t1-r${round}-${distribution}-hot`, {
    hostKey,
    capacity: groupSize,
    status: 'CLOSED',
    activeKeys: cancelKeys,
    waiterKeys,
    cancelKeys,
  });
  cancelKeys.forEach((actorKey, slot) => addUserTarget(targets, planner, {
    round,
    slot,
    roomKey,
    actorKey,
    distribution: distribution === 'mixed' ? 'hot' : distribution,
  }));
}

function addT1SpreadRound(options, planner, targets, round, startSlot, count) {
  for (let offset = 0; offset < count; offset += 1) {
    const slot = startSlot + offset;
    const hostKey = planner.user(`t1-spread-s${slot}-host`);
    const actorKey = planner.user(`t1-spread-s${slot}-cancel`);
    const waiterKeys = [
      planner.user(`t1-spread-s${slot}-waiter-0`),
      planner.user(`t1-spread-s${slot}-waiter-1`),
    ];
    const roomKey = planner.room(`t1-r${round}-spread-s${slot}`, {
      hostKey,
      capacity: 1,
      status: 'CLOSED',
      activeKeys: [actorKey],
      waiterKeys,
      cancelKeys: [actorKey],
    });
    addUserTarget(targets, planner, { round, slot, roomKey, actorKey, distribution: 'spread' });
  }
}

function addT2HotRound(options, planner, targets, round, groupSize, distribution) {
  const hostKey = planner.user(`t2-${distribution}-host`);
  const activeKey = planner.user(`t2-${distribution}-active`);
  const actorKeys = Array.from(
    { length: groupSize },
    (_, index) => planner.user(`t2-${distribution}-actor-${index}`),
  );
  const roomKey = planner.room(`t2-r${round}-${distribution}-hot`, {
    hostKey,
    capacity: 1,
    status: 'CLOSED',
    activeKeys: [activeKey],
    waiterKeys: [],
  });
  actorKeys.forEach((actorKey, slot) => addUserTarget(targets, planner, {
    round,
    slot,
    roomKey,
    actorKey,
    distribution: distribution === 'mixed' ? 'hot' : distribution,
  }));
}

function addT2SpreadRound(options, planner, targets, round, startSlot, count) {
  for (let offset = 0; offset < count; offset += 1) {
    const slot = startSlot + offset;
    const hostKey = planner.user(`t2-spread-s${slot}-host`);
    const activeKey = planner.user(`t2-spread-s${slot}-active`);
    const actorKey = planner.user(`t2-spread-s${slot}-actor`);
    const roomKey = planner.room(`t2-r${round}-spread-s${slot}`, {
      hostKey,
      capacity: 1,
      status: 'CLOSED',
      activeKeys: [activeKey],
      waiterKeys: [],
    });
    addUserTarget(targets, planner, { round, slot, roomKey, actorKey, distribution: 'spread' });
  }
}

function createComparisonFixturePlan(input) {
  const options = normalizedComparisonInput(input);
  const fixtureOptions = options.options;
  const planner = createPlanner(options);
  const targets = [];
  const groupSize = options.distribution === 'mixed' ? options.concurrency / 2 : options.concurrency;

  for (let round = 0; round < options.rounds; round += 1) {
    if (options.scenario === 't1') {
      if (options.distribution === 'spread') {
        addT1SpreadRound(options, planner, targets, round, 0, options.concurrency);
      } else if (options.distribution === 'mixed') {
        addT1HotRound(options, planner, targets, round, groupSize, 'mixed');
        addT1SpreadRound(options, planner, targets, round, groupSize, groupSize);
      } else {
        addT1HotRound(options, planner, targets, round, options.concurrency, 'hot');
      }
    } else if (options.distribution === 'spread') {
      addT2SpreadRound(options, planner, targets, round, 0, options.concurrency);
    } else if (options.distribution === 'mixed') {
      addT2HotRound(options, planner, targets, round, groupSize, 'mixed');
      addT2SpreadRound(options, planner, targets, round, groupSize, groupSize);
    } else {
      addT2HotRound(options, planner, targets, round, options.concurrency, 'hot');
    }
  }

  return {
    schemaVersion: FIXTURE_SCHEMA_VERSION,
    fixtureId: fixtureOptions.fixtureId,
    scenario: fixtureOptions.scenario,
    runId: options.runId,
    candidate: options.candidate,
    candidateSha: options.candidateSha,
    conditionId: options.conditionId,
    executionModel: options.executionModel,
    distribution: options.distribution,
    concurrency: options.concurrency,
    arrivalRate: options.arrivalRate,
    durationSeconds: options.durationSeconds,
    rounds: options.rounds,
    minimumValidSamples: options.minimumValidSamples,
    options: fixtureOptions,
    comparison: fixtureOptions.comparison,
    users: planner.users,
    rooms: planner.rooms,
    targets,
    sessionUserKeys: [...new Set(targets.map((target) => target.actorKey))],
  };
}

function runtimeContractSource(plan) {
  const contract = {
    scenario: plan.options.scenario,
    executionModel: plan.executionModel,
    distribution: plan.distribution,
    concurrency: plan.concurrency,
    rounds: plan.rounds,
    arrivalRate: plan.arrivalRate,
    durationSeconds: plan.durationSeconds,
    targetCount: plan.targets.length,
  };
  const contractJson = JSON.stringify(contract);
  return `import execution from 'k6/execution';
import {
  classifyT1Cancel,
  classifyT2Waitlist,
  evaluateResponse,
  loadRuntime,
  recordStartSkew,
  recordWaitlistPosition,
  requestEmpty,
  scenarioTags,
  sessionFor,
  targetForRoundAndSlot,
  waitFor,
  writeSetup,
} from './lib/room-k6.js';

const contract = ${contractJson};
const runtime = loadRuntime(contract.scenario);
const allowExisting = false;
const expectedPosition = null;
const outcomeCategories = ['success', 'business', 'concurrency', 'unexpected'];

export const options = {
  summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
  scenarios: {
    room_write: contract.executionModel === 'barrier'
      ? {
        executor: 'per-vu-iterations',
        vus: contract.concurrency,
        iterations: contract.rounds,
        maxDuration: '900s',
      }
      : {
        executor: 'constant-arrival-rate',
        rate: contract.arrivalRate,
        timeUnit: '1s',
        duration: contract.durationSeconds + 's',
        preAllocatedVUs: Math.max(contract.concurrency * 2, contract.arrivalRate * 2),
        maxVUs: Math.max(contract.concurrency * 4, contract.arrivalRate * 4),
      },
  },
  thresholds: {
    ...Object.fromEntries(outcomeCategories.map((category) => [
      'room_request_duration{outcome:' + category + '}', ['p(99)>=0'],
    ])),
    room_contract_failures: ['count==0'],
    room_unexpected_4xx: ['count==0'],
    room_server_failures: ['count==0'],
    room_start_skew_ms: ['max<1000'],
  },
};

export function setup() {
  return writeSetup(runtime);
}

function targetForConstantArrival(index) {
  const target = runtime.fixture.targets[index];
  if (!target) {
    throw new Error('constant-arrival-rate iteration에 대응하는 fixture target이 없습니다: ' + index);
  }
  return target;
}

function measurementTarget(barrier) {
  if (contract.executionModel === 'barrier') {
    const round = execution.vu.iterationInScenario;
    return {
      target: targetForRoundAndSlot(runtime.fixture, round, execution.vu.idInTest - 1),
      round,
      index: (round * contract.concurrency) + execution.vu.idInTest - 1,
      barrierAt: barrier.firstBarrierAt + (round * barrier.roundIntervalMilliseconds),
    };
  }
  const index = execution.scenario.iterationInTest;
  const round = Math.floor(index / contract.concurrency);
  return {
    target: targetForConstantArrival(index),
    round,
    index,
    barrierAt: barrier.firstBarrierAt + (round * 1000),
  };
}

export default function (barrier) {
  const assignment = measurementTarget(barrier);
  const target = assignment.target;
  const room = runtime.fixture.rooms[target.roomKey];
  const client = sessionFor(runtime, barrier.sessions, target.actorKey);
  const tags = scenarioTags(runtime, target, {
    phase: 'measurement',
    operation: runtime.fixture.options.scenario === 't1' ? 'cancel-participation' : 'waitlist-register',
    round: String(assignment.round),
    arrival_index: String(assignment.index),
    distribution: target.distribution || contract.distribution,
    execution_model: contract.executionModel,
  });
  if (contract.executionModel === 'barrier') {
    waitFor(assignment.barrierAt);
  }
  recordStartSkew(assignment.barrierAt, tags);

  if (runtime.fixture.options.scenario === 't1') {
    const response = requestEmpty(
      client,
      runtime,
      'DELETE',
      '/api/rooms/' + room.id + '/participants/me',
      tags,
    );
    evaluateResponse(
      response,
      (actual, value) => classifyT1Cancel(actual, value, room),
      tags,
      'ROOM-LOCK-CMP T1 cancel participation',
    );
    return;
  }

  const response = requestEmpty(
    client,
    runtime,
    'POST',
    '/api/rooms/' + room.id + '/waitlist',
    tags,
  );
  const outcome = evaluateResponse(
    response,
    (actual, value) => classifyT2Waitlist(actual, value, room.id, allowExisting, expectedPosition),
    tags,
    'ROOM-LOCK-CMP T2 waitlist registration',
  );
  const position = outcome.contract && outcome.category === 'success'
    ? outcome.value.data.position
    : null;
  recordWaitlistPosition(position, tags);
}
`;
}

function executionOptions(plan) {
  const k6Environment = {
    ROOM_K6_SESSION_WARMUP_SECONDS: '15',
    ROOM_K6_ROUND_INTERVAL_SECONDS: '20',
    ROOM_LOCK_COMPARISON_CAMPAIGN: plan.runId.split('-').slice(0, 1)[0],
    ROOM_LOCK_COMPARISON_CANDIDATE: plan.candidate,
    ROOM_LOCK_COMPARISON_CONDITION: plan.conditionId,
    ROOM_LOCK_COMPARISON_EXECUTION_MODEL: plan.executionModel,
    ROOM_LOCK_COMPARISON_DISTRIBUTION: plan.distribution,
    ROOM_LOCK_COMPARISON_ARRIVAL_RATE: String(plan.arrivalRate),
    ROOM_LOCK_COMPARISON_DURATION_SECONDS: String(plan.durationSeconds || 0),
    ROOM_LOCK_COMPARISON_EXPECTED_SAMPLES: String(plan.minimumValidSamples || plan.targets?.length || 0),
  };
  return { schemaVersion: 1, k6Environment, t5ReadOptions: null };
}

function artifactPath(bundleDirectory, relativePath) {
  return path.join(bundleDirectory, relativePath);
}

function immutablePaths() {
  return [
    'scenario.js',
    'tools/fixture.mjs',
    ...RUNTIME_FILES,
    ARTIFACTS.fixturePlan,
    ARTIFACTS.prepareProvenance,
    ARTIFACTS.prepareSql,
    ARTIFACTS.resourceQuerySql,
    ARTIFACTS.executionOptions,
  ];
}

function hashArtifacts(bundleDirectory) {
  return Object.fromEntries(immutablePaths().map((relativePath) => [
    relativePath,
    sha256(readFileSync(artifactPath(bundleDirectory, relativePath))),
  ]));
}

function renderBundle(values) {
  const source = sourceProvenance(values.sourceSha);
  const scenario = oneOf(values.scenario, 'scenario', CORE_SCENARIOS);
  const candidate = oneOf(values.candidate, 'candidate', CANDIDATE_LABELS);
  const candidateSha = validateSourceSha(values.candidateSha);
  if (source.sourceRevision !== candidateSha) {
    fail('candidate SHA와 comparison bundle을 생성하는 앱 source SHA가 다릅니다.');
  }
  const concurrency = integer(values.concurrency, 'concurrency', 2, 16);
  const conditionId = text(values.condition, 'condition');
  const condition = conditionFor(conditionId, concurrency);
  const input = {
    scenario,
    runId: safeIdentifier(values.runId, 'runId'),
    candidate,
    candidateSha,
    conditionId,
    executionModel: condition.executionModel,
    distribution: condition.distribution,
    concurrency,
  };
  const plan = createComparisonFixturePlan(input);
  const outputRoot = path.resolve(values.outputRoot || sourceBuildRoot);
  const outputDirectory = path.join(outputRoot, plan.options.runId, plan.fixtureId);
  if (existsSync(outputDirectory)) {
    fail(`같은 comparison bundle이 이미 있습니다: ${outputDirectory}`);
  }
  const passwordHash = text(process.env.ROOM_K6_FIXTURE_PASSWORD_HASH, 'ROOM_K6_FIXTURE_PASSWORD_HASH');
  if (!passwordHash.startsWith('{bcrypt}$')) {
    fail('ROOM_K6_FIXTURE_PASSWORD_HASH는 {bcrypt}$로 시작해야 합니다.');
  }
  mkdirSync(path.join(outputDirectory, 'private'), { recursive: true });
  mkdirSync(path.join(outputDirectory, 'lib'), { recursive: true });
  mkdirSync(path.join(outputDirectory, 'tools'), { recursive: true });

  const ownership = randomUUID().replaceAll('-', '');
  const provenance = {
    schemaVersion: 1,
    fixtureId: plan.fixtureId,
    options: plan.options,
    comparison: plan,
    prepareOwnership: normalizePrepareOwnership(ownership),
    passwordHash,
  };
  const manifest = {
    schemaVersion: BUNDLE_SCHEMA_VERSION,
    kind: BUNDLE_KIND,
    fixtureSchemaVersion: FIXTURE_SCHEMA_VERSION,
    fixtureId: plan.fixtureId,
    options: plan.options,
    comparison: plan,
    sourceRevision: source.sourceRevision,
    sourceDirty: false,
    artifacts: ARTIFACTS,
  };

  writeNewJson(artifactPath(outputDirectory, ARTIFACTS.fixturePlan), plan);
  writeNewJson(artifactPath(outputDirectory, ARTIFACTS.prepareProvenance), provenance);
  writeNewText(artifactPath(outputDirectory, ARTIFACTS.prepareSql), buildPrepareSql(plan, passwordHash, ownership));
  writeNewText(artifactPath(outputDirectory, ARTIFACTS.resourceQuerySql), buildResourceQuery(plan, ownership));
  writeNewJson(artifactPath(outputDirectory, ARTIFACTS.executionOptions), executionOptions(plan));
  writeNewText(artifactPath(outputDirectory, 'scenario.js'), runtimeContractSource(plan));

  const sourceRuntime = (relativePath) => path.join(sourceRepositoryRoot, 'load-tests', 'k6', 'jiwon', relativePath);
  for (const relativePath of RUNTIME_FILES) {
    copyFileSync(sourceRuntime(relativePath), artifactPath(outputDirectory, relativePath));
  }
  writeNewText(
    artifactPath(outputDirectory, 'tools/fixture.mjs'),
    readFileSync(toolPath, 'utf8'),
  );
  manifest.immutableSha256 = hashArtifacts(outputDirectory);
  writeNewJson(path.join(outputDirectory, 'manifest.json'), manifest);
  return {
    bundlePath: outputDirectory,
    fixtureId: plan.fixtureId,
    runId: plan.options.runId,
    candidate,
    condition: conditionId,
  };
}

function readBundle(bundleValue) {
  const bundleDirectory = path.resolve(text(bundleValue, '--bundle'));
  const manifest = readJson(path.join(bundleDirectory, 'manifest.json'), 'bundle manifest');
  if (manifest.kind !== BUNDLE_KIND
    || manifest.schemaVersion !== BUNDLE_SCHEMA_VERSION
    || manifest.fixtureSchemaVersion !== FIXTURE_SCHEMA_VERSION
    || !SOURCE_SHA_PATTERN.test(manifest.sourceRevision || '')
    || manifest.sourceDirty !== false) {
    fail('ROOM-LOCK-CMP bundle manifest 계약이 올바르지 않습니다.');
  }
  const plan = readJson(path.join(bundleDirectory, ARTIFACTS.fixturePlan), 'fixture plan');
  const expected = createComparisonFixturePlan({
    scenario: plan.scenario,
    runId: plan.runId,
    candidate: plan.candidate,
    candidateSha: plan.candidateSha,
    conditionId: plan.conditionId,
    executionModel: plan.executionModel,
    distribution: plan.distribution,
    concurrency: plan.concurrency,
  });
  if (!isDeepStrictEqual(plan, expected)
    || manifest.fixtureId !== plan.fixtureId
    || !isDeepStrictEqual(manifest.options, plan.options)
    || !isDeepStrictEqual(manifest.comparison, plan)) {
    fail('fixture plan과 manifest가 결정적 comparison plan과 다릅니다.');
  }
  const provenance = readJson(path.join(bundleDirectory, ARTIFACTS.prepareProvenance), 'prepare provenance');
  const ownership = normalizePrepareOwnership(provenance.prepareOwnership);
  if (provenance.fixtureId !== plan.fixtureId
    || !isDeepStrictEqual(provenance.options, plan.options)
    || !isDeepStrictEqual(provenance.comparison, plan)
    || typeof provenance.passwordHash !== 'string'
    || provenance.passwordHash !== provenance.passwordHash.trim()
    || !provenance.passwordHash.startsWith('{bcrypt}$')) {
    fail('prepare provenance가 comparison plan과 맞지 않습니다.');
  }
  if (readFileSync(path.join(bundleDirectory, ARTIFACTS.prepareSql), 'utf8')
    !== buildPrepareSql(plan, provenance.passwordHash, ownership)) {
    fail('prepare.sql이 comparison plan과 맞지 않습니다.');
  }
  if (readFileSync(path.join(bundleDirectory, ARTIFACTS.resourceQuerySql), 'utf8')
    !== buildResourceQuery(plan, ownership)) {
    fail('resource-query.sql이 comparison plan과 맞지 않습니다.');
  }
  const execution = readJson(path.join(bundleDirectory, ARTIFACTS.executionOptions), 'execution options');
  if (execution.schemaVersion !== 1 || !execution.k6Environment || execution.t5ReadOptions !== null) {
    fail('execution-options.json 계약이 올바르지 않습니다.');
  }
  for (const [name, value] of Object.entries(execution.k6Environment)) {
    if (!/^[A-Za-z_][A-Za-z0-9_]*$/.test(name) || typeof value !== 'string') {
      fail('execution-options.json의 환경 변수 형식이 올바르지 않습니다.');
    }
  }
  const hashes = manifest.immutableSha256;
  for (const relativePath of immutablePaths()) {
    if (!SHA256_PATTERN.test(hashes?.[relativePath] || '')
      || sha256(readFileSync(artifactPath(bundleDirectory, relativePath))) !== hashes[relativePath]) {
      fail(`immutable comparison artifact가 변조되었습니다: ${relativePath}`);
    }
  }
  return { bundleDirectory, manifest, plan, ownership, execution };
}

function assertPristine(bundleDirectory) {
  const existing = BUNDLE_STATE_FILES.filter((relativePath) => existsSync(artifactPath(bundleDirectory, relativePath)));
  if (existing.length > 0) {
    fail(`실행 전 comparison bundle에 상태 artifact가 있습니다: ${existing.join(', ')}`);
  }
}

function validateBundle(values) {
  const bundle = readBundle(values.bundle);
  if (values.forExecution) {
    assertPristine(bundle.bundleDirectory);
  }
  return {
    bundlePath: bundle.bundleDirectory,
    runId: bundle.plan.options.runId,
    fixtureId: bundle.plan.fixtureId,
    candidate: bundle.plan.candidate,
    condition: bundle.plan.conditionId,
  };
}

function executionOptionsForBundle(values) {
  const bundle = readBundle(values.bundle);
  return bundle.execution;
}

function hydrateBundle(values) {
  const bundle = readBundle(values.bundle);
  const fixturePath = artifactPath(bundle.bundleDirectory, ARTIFACTS.fixture);
  if (existsSync(fixturePath)) {
    fail('이미 hydrate된 comparison bundle입니다.');
  }
  const resources = readJson(artifactPath(bundle.bundleDirectory, ARTIFACTS.resourceOutput), 'resource output');
  const fixture = hydrateFixture(bundle.plan, resources, bundle.ownership);
  writeNewJson(fixturePath, fixture);
  writeNewText(
    artifactPath(bundle.bundleDirectory, ARTIFACTS.snapshotSql),
    buildSnapshotQuery(fixture),
  );
  writeNewText(
    artifactPath(bundle.bundleDirectory, ARTIFACTS.cleanupSql),
    buildCleanupSql(fixture),
  );
  return { fixturePath, fixtureId: fixture.fixtureId };
}

function readHydratedFixture(bundle) {
  const fixture = readJson(artifactPath(bundle.bundleDirectory, ARTIFACTS.fixture), 'fixture');
  const resources = readJson(artifactPath(bundle.bundleDirectory, ARTIFACTS.resourceOutput), 'resource output');
  const expected = hydrateFixture(bundle.plan, resources, bundle.ownership);
  if (!isDeepStrictEqual({ ...fixture, baselineSnapshot: undefined }, { ...expected, baselineSnapshot: undefined })) {
    fail('fixture.json이 comparison plan·resource output과 맞지 않습니다.');
  }
  return fixture;
}

function readSnapshot(bundle, relativePath) {
  const snapshot = readJson(artifactPath(bundle.bundleDirectory, relativePath), relativePath);
  if (!snapshot || typeof snapshot !== 'object'
    || !Array.isArray(snapshot.rooms)
    || !Array.isArray(snapshot.participations)
    || !Array.isArray(snapshot.waitlists)) {
    fail(`${relativePath}이 ROOM snapshot 형식이 아닙니다.`);
  }
  return snapshot;
}

function diagnoseBundle(values) {
  const stage = oneOf(values.stage, 'stage', ['before', 'after']);
  const bundle = readBundle(values.bundle);
  const fixture = readHydratedFixture(bundle);
  const snapshot = readSnapshot(bundle, stage === 'before' ? ARTIFACTS.beforeSnapshot : ARTIFACTS.afterSnapshot);
  const summary = stage === 'after'
    ? normalizeRoomSummary(readJson(artifactPath(bundle.bundleDirectory, ARTIFACTS.summary), 'k6 summary'))
    : null;
  const evaluation = evaluateFixture(fixture, snapshot, stage, summary);
  const result = {
    fixtureId: fixture.fixtureId,
    scenario: fixture.options.scenario,
    stage,
    ...evaluation,
  };
  writeNewJson(
    artifactPath(bundle.bundleDirectory, stage === 'before' ? ARTIFACTS.beforeDiagnosis : ARTIFACTS.afterDiagnosis),
    result,
  );
  return result;
}

function hasRequiredResourceSignalShape(value) {
  const requiredObjects = [
    'http', 'hikari', 'postgresql', 'query', 'database', 'retry', 'sources', 'window',
  ];
  return value && value.schemaVersion === 1
    && typeof value.runId === 'string'
    && typeof value.fixtureId === 'string'
    && requiredObjects.every((key) => value[key] && typeof value[key] === 'object')
    && Number.isFinite(value.http.requestCount)
    && Number.isFinite(value.http.rps)
    && Number.isFinite(value.hikari.pendingThreads)
    && Number.isFinite(value.postgresql.lockWaitCount)
    && Number.isFinite(value.query.callCount)
    && Number.isFinite(value.query.totalTimeMilliseconds)
    && Number.isFinite(value.database.transactionCount)
    && Number.isFinite(value.database.transactionDurationMs)
    && Number.isFinite(value.postgresql.cpuPercent)
    && Number.isFinite(value.hikari.cpuPercent || value.jvm?.cpuPercent);
}

function readInfraExecution(bundleDirectory, plan) {
  const execution = readJson(artifactPath(bundleDirectory, ARTIFACTS.infraExecution), 'infra execution');
  const phaseNames = ['prepare', 'resourceQuery', 'beforeSnapshot', 'k6', 'afterSnapshot'];
  if (execution.schemaVersion !== 1
    || execution.runId !== plan.options.runId
    || execution.fixtureId !== plan.fixtureId
    || !execution.phases
    || !phaseNames.every((name) => Object.hasOwn(execution.phases, name)
      && Number.isInteger(execution.phases[name].exitCode))) {
    fail('infra-execution.json이 현재 comparison bundle과 맞지 않습니다.');
  }
  return execution;
}

function aggregateBundle(values) {
  const bundle = readBundle(values.bundle);
  const issues = [];
  let before = null;
  let after = null;
  let execution = null;
  let signals = null;
  let invalidEvidence = false;

  try {
    before = readJson(artifactPath(bundle.bundleDirectory, ARTIFACTS.beforeDiagnosis), 'before diagnosis');
    after = readJson(artifactPath(bundle.bundleDirectory, ARTIFACTS.afterDiagnosis), 'after diagnosis');
    if (before.status === 'INVALID' || after.status === 'INVALID') {
      invalidEvidence = true;
      issues.push('correctness·불변식·contract diagnosis가 INVALID입니다.');
    } else if (before.status !== 'PASS' || after.status !== 'PASS') {
      issues.push('correctness·불변식·contract gate가 PASS가 아닙니다.');
    }
  } catch (error) {
    issues.push(`diagnosis artifact가 없습니다: ${error.message}`);
  }

  try {
    execution = readInfraExecution(bundle.bundleDirectory, bundle.plan);
    if (Object.values(execution.phases).some((phase) => phase.exitCode !== 0)) {
      issues.push('infra 또는 k6 phase가 0이 아닌 exit code로 끝났습니다.');
    }
  } catch (error) {
    issues.push(`infra provenance가 없습니다: ${error.message}`);
  }

  try {
    signals = readJson(artifactPath(bundle.bundleDirectory, ARTIFACTS.resourceSignals), 'resource signals');
    if (signals.runId !== bundle.plan.options.runId
      || signals.fixtureId !== bundle.plan.fixtureId
      || !hasRequiredResourceSignalShape(signals)) {
      issues.push('required resource·retry·lock·Hikari·query·CPU signal이 malformed 또는 부족합니다.');
    }
  } catch (error) {
    issues.push(`resource signal provenance가 없습니다: ${error.message}`);
  }

  let status = 'PASS';
  if (invalidEvidence) {
    status = 'INVALID';
  } else if (issues.some((issue) => issue.includes('correctness') || issue.includes('phase'))) {
    status = 'FAIL';
  }
  if (issues.some((issue) => issue.includes('없습니다') || issue.includes('malformed') || issue.includes('부족합니다'))) {
    status = 'INVALID';
  }
  const result = {
    schemaVersion: 1,
    fixtureId: bundle.plan.fixtureId,
    runId: bundle.plan.options.runId,
    candidate: bundle.plan.candidate,
    candidateSha: bundle.plan.candidateSha,
    condition: bundle.plan.condition,
    status,
    issues,
    beforeDiagnosis: before,
    afterDiagnosis: after,
    infraExecution: execution,
    resourceSignals: signals,
  };
  writeNewJson(artifactPath(bundle.bundleDirectory, ARTIFACTS.finalResult), result);
  return result;
}

function metricValues(summary, name) {
  const metric = summary?.metrics?.[name];
  return metric?.values || metric || null;
}

function countMetric(summary, name) {
  const value = metricValues(summary, name)?.count;
  return Number.isSafeInteger(value) ? value : null;
}

function aggregateCampaign(values) {
  const plan = readJson(path.resolve(text(values.plan, '--plan')), 'campaign plan');
  if (plan.schemaVersion !== 1 || !Array.isArray(plan.runs)) {
    fail('campaign plan 형식이 올바르지 않습니다.');
  }
  const report = {
    schemaVersion: 1,
    campaignId: plan.campaignId,
    status: 'PASS',
    winner: null,
    winnerDecision: '786은 증거를 만들며 최종 전략 선택·ADR을 자동 생성하지 않습니다.',
    candidates: {},
    regressions: [],
    excludedRuns: [],
  };
  for (const candidate of CANDIDATE_LABELS) {
    report.candidates[candidate] = { candidateSha: plan.candidates[candidate], conditions: {} };
  }
  for (const run of plan.runs) {
    if (run.runner !== 'room-lock-comparison') {
      report.regressions.push({
        runId: run.runId,
        pairId: run.pairId,
        candidate: run.candidate,
        candidateSha: run.candidateSha,
        scenario: run.scenario,
        condition: run.condition,
        status: 'PORTABLE_ARTIFACT_REQUIRED',
      });
      continue;
    }
    const bundlePath = run.bundlePath
      || path.join(sourceBuildRoot, run.runId, run.fixtureId || `room-k6-${run.scenario}-${digest(JSON.stringify({
        ...run.condition,
        scenario: run.scenario,
        runId: run.runId,
        candidate: run.candidate,
        candidateSha: run.candidateSha,
      }))}`);
    const finalPath = path.join(bundlePath, ARTIFACTS.finalResult);
    if (!existsSync(finalPath)) {
      report.status = 'INVALID';
      report.excludedRuns.push({ runId: run.runId, reason: 'final-result.json 누락' });
      continue;
    }
    const finalResult = readJson(finalPath, 'final result');
    const conditionId = `${run.scenario}/${run.condition.id}/c${run.condition.concurrency}`;
    const conditionReport = report.candidates[run.candidate].conditions[conditionId]
      || { requiredRuns: MIN_PAIRED_RUNS, runs: [], eligibleForTailRanking: false, metrics: [] };
    let summary = null;
    const summaryPath = path.join(bundlePath, ARTIFACTS.summary);
    if (existsSync(summaryPath)) {
      summary = readJson(summaryPath, 'k6 summary');
    }
    const metric = {
      runId: run.runId,
      pairId: run.pairId,
      sequence: run.sequence,
      status: finalResult.status,
      requestCount: countMetric(summary, 'room_requests'),
      successCount: countMetric(summary, 'room_success'),
      p95: metricValues(summary, 'http_req_duration')?.['p(95)'] ?? null,
      p99: metricValues(summary, 'http_req_duration')?.['p(99)'] ?? null,
      rps: finalResult.resourceSignals?.http?.rps ?? null,
      validSampleGate: run.condition.executionModel === 'constant-arrival-rate'
        ? countMetric(summary, 'http_reqs') === run.condition.minimumValidSamples
        : false,
    };
    conditionReport.runs.push(metric);
    const eligibleStatuses = conditionReport.runs.length === MIN_PAIRED_RUNS
      && conditionReport.runs.every((item) => item.status === 'PASS');
    conditionReport.eligibleForTailRanking = eligibleStatuses
      && run.condition.executionModel === 'constant-arrival-rate'
      && conditionReport.runs.every((item) => item.validSampleGate);
    conditionReport.metrics = conditionReport.runs;
    report.candidates[run.candidate].conditions[conditionId] = conditionReport;
    if (finalResult.status !== 'PASS') {
      report.excludedRuns.push({ runId: run.runId, reason: finalResult.status, issues: finalResult.issues });
    }
  }
  if (Object.values(report.candidates).some((candidate) => Object.values(candidate.conditions)
    .some((condition) => condition.runs.length < MIN_PAIRED_RUNS))) {
    report.status = 'INVALID';
  }
  if (report.regressions.some((regression) => regression.status !== 'PASS')) {
    report.status = 'INVALID';
  }
  if (values.output) {
    writeNewJson(path.resolve(values.output), report);
  }
  process.stdout.write(`${JSON.stringify(report)}\n`);
  return report;
}

function main() {
  const { command, values } = parseArguments(process.argv.slice(2));
  switch (command) {
    case 'help':
      process.stdout.write(usage());
      return;
    case 'plan': {
      const plan = buildCampaignPlan({
        campaignId: values.campaignId,
        candidates: values.candidatesFile,
        seed: values.seed || values.campaignId,
      });
      if (values.output) {
        writeNewJson(path.resolve(values.output), plan);
      }
      process.stdout.write(`${JSON.stringify(plan)}\n`);
      return;
    }
    case 'render-bundle':
      process.stdout.write(`${JSON.stringify(renderBundle(values))}\n`);
      return;
    case 'validate':
      process.stdout.write(`${JSON.stringify(validateBundle(values))}\n`);
      return;
    case 'execution-options':
      process.stdout.write(`${JSON.stringify(executionOptionsForBundle(values))}\n`);
      return;
    case 'hydrate':
      process.stdout.write(`${JSON.stringify(hydrateBundle(values))}\n`);
      return;
    case 'diagnose':
      process.stdout.write(`${JSON.stringify(diagnoseBundle(values))}\n`);
      return;
    case 'aggregate':
      process.stdout.write(`${JSON.stringify(aggregateBundle(values))}\n`);
      return;
    case 'aggregate-campaign':
      aggregateCampaign(values);
      return;
    default:
      fail(`지원하지 않는 명령: ${command}\n\n${usage()}`);
  }
}

const isMainModule = process.argv[1]
  && path.resolve(process.argv[1]) === path.resolve(toolPath);

if (isMainModule) {
  try {
    main();
  } catch (error) {
    process.stderr.write(`${error.message}\n`);
    process.exitCode = 1;
  }
}

export {
  CONDITION_TEMPLATES,
  createComparisonFixturePlan,
  conditionFor,
  normalizedComparisonInput,
  renderBundle,
  runtimeContractSource,
};
