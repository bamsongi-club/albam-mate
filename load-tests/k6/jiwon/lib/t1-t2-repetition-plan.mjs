import { isDeepStrictEqual } from 'node:util';

import {
  createFixturePlan,
  RUN_ID_PATTERN,
} from '../tools/fixture-model.mjs';

export const T1_T2_REPEAT_COUNT = 3;
export const T1_T2_WRITE_EXECUTION_PROFILE = Object.freeze({
  ROOM_K6_SESSION_WARMUP_SECONDS: '15',
  ROOM_K6_ROUND_INTERVAL_SECONDS: '20',
});
export const T1_T2_READ_PROFILE = null;

export const T1_T2_CONDITIONS = Object.freeze([
  Object.freeze({ conditionKey: 't1-hot-c4', scenario: 't1', mode: 'hot', concurrency: 4 }),
  Object.freeze({ conditionKey: 't1-hot-c8', scenario: 't1', mode: 'hot', concurrency: 8 }),
  Object.freeze({ conditionKey: 't1-spread-c4', scenario: 't1', mode: 'spread', concurrency: 4 }),
  Object.freeze({ conditionKey: 't1-spread-c8', scenario: 't1', mode: 'spread', concurrency: 8 }),
  Object.freeze({
    conditionKey: 't2-distinct-hot-c4',
    scenario: 't2',
    mode: 'hot',
    concurrency: 4,
    subcase: 'distinct',
  }),
  Object.freeze({
    conditionKey: 't2-distinct-hot-c8',
    scenario: 't2',
    mode: 'hot',
    concurrency: 8,
    subcase: 'distinct',
  }),
  Object.freeze({
    conditionKey: 't2-distinct-spread-c4',
    scenario: 't2',
    mode: 'spread',
    concurrency: 4,
    subcase: 'distinct',
  }),
  Object.freeze({
    conditionKey: 't2-distinct-spread-c8',
    scenario: 't2',
    mode: 'spread',
    concurrency: 8,
    subcase: 'distinct',
  }),
]);

const CAMPAIGN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,55}$/;
const SOURCE_SHA_PATTERN = /^[0-9a-f]{40}$/i;
const TARGET_ENVIRONMENT_PATTERN = /^[a-z0-9][a-z0-9._-]{0,79}$/;

function fail(message) {
  throw new Error(message);
}

function requiredCampaignId(value) {
  const campaignId = String(value ?? '').trim();
  if (!CAMPAIGN_ID_PATTERN.test(campaignId)) {
    fail('T1/T2 campaignId는 영문 소문자 또는 숫자로 시작하는 56자 이하의 안전한 값이어야 합니다.');
  }
  return campaignId;
}

function requiredSourceSha(value) {
  const sourceSha = String(value ?? '').trim().toLowerCase();
  if (!SOURCE_SHA_PATTERN.test(sourceSha)) {
    fail('T1/T2 sourceSha는 40자리 Git SHA여야 합니다.');
  }
  return sourceSha;
}

function requiredTargetEnvironment(value) {
  const targetEnvironment = String(value ?? '').trim();
  if (!TARGET_ENVIRONMENT_PATTERN.test(targetEnvironment)) {
    fail('T1/T2 targetEnvironment는 영문 소문자 또는 숫자로 시작하는 80자 이하의 안전한 값이어야 합니다.');
  }
  return targetEnvironment;
}

function repeatRunId(campaignId, conditionKey, repeat) {
  const runId = `${campaignId}-${conditionKey}-r${repeat}`;
  if (!RUN_ID_PATTERN.test(runId)) {
    fail(`T1/T2 repeat runId가 안전한 형식이 아닙니다: ${runId}`);
  }
  return runId;
}

function conditionOptions(condition, runId) {
  const options = {
    scenario: condition.scenario,
    runId,
    profile: 'stress',
    rounds: 5,
    mode: condition.mode,
    concurrency: condition.concurrency,
  };
  if (condition.scenario === 't2') {
    options.subcase = 'distinct';
  }
  return options;
}

export function createT1T2RepetitionPlan({ campaignId, sourceSha, targetEnvironment } = {}) {
  const normalizedCampaignId = requiredCampaignId(campaignId);
  const normalizedSourceSha = requiredSourceSha(sourceSha);
  const normalizedTargetEnvironment = requiredTargetEnvironment(targetEnvironment);
  const runs = [];

  for (const condition of T1_T2_CONDITIONS) {
    for (let repeat = 1; repeat <= T1_T2_REPEAT_COUNT; repeat += 1) {
      const runId = repeatRunId(normalizedCampaignId, condition.conditionKey, repeat);
      const inputOptions = conditionOptions(condition, runId);
      const fixturePlan = createFixturePlan(inputOptions);
      runs.push({
        repeat,
        repeatId: `r${repeat}`,
        conditionKey: condition.conditionKey,
        runId,
        fixtureId: fixturePlan.fixtureId,
        options: fixturePlan.options,
        readProfile: T1_T2_READ_PROFILE,
        writeExecutionProfile: { ...T1_T2_WRITE_EXECUTION_PROFILE },
        sourceSha: normalizedSourceSha,
        targetEnvironment: normalizedTargetEnvironment,
      });
    }
  }

  return {
    schemaVersion: 1,
    scenario: 't1-t2',
    campaignId: normalizedCampaignId,
    sourceSha: normalizedSourceSha,
    targetEnvironment: normalizedTargetEnvironment,
    repeatCount: T1_T2_REPEAT_COUNT,
    conditionCount: T1_T2_CONDITIONS.length,
    runCount: runs.length,
    readProfile: T1_T2_READ_PROFILE,
    writeExecutionProfile: { ...T1_T2_WRITE_EXECUTION_PROFILE },
    conditions: T1_T2_CONDITIONS.map((condition) => ({ ...condition })),
    runs,
  };
}

export function validateT1T2RepetitionPlan(plan) {
  if (!plan || typeof plan !== 'object' || Array.isArray(plan)) {
    fail('T1/T2 repetition plan은 JSON object여야 합니다.');
  }
  const expected = createT1T2RepetitionPlan({
    campaignId: plan.campaignId,
    sourceSha: plan.sourceSha,
    targetEnvironment: plan.targetEnvironment,
  });
  if (!isDeepStrictEqual(plan, expected)) {
    fail('T1/T2 repetition plan이 결정적인 조건·repeat identity와 일치하지 않습니다.');
  }
  return expected;
}
