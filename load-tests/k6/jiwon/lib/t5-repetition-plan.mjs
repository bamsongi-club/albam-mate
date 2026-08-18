import {
  createFixturePlan,
  RUN_ID_PATTERN,
} from '../tools/fixture-model.mjs';

export const T5_ROLES = Object.freeze(['public', 'host', 'participant']);
export const T5_SCALES = Object.freeze([1, 10]);
export const T5_REPEAT_COUNT = 3;
export const T5_READ_PROFILE = Object.freeze({
  vus: 10,
  durationSeconds: 60,
  thinkTimeMilliseconds: 0,
});

const CAMPAIGN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,75}$/;

function fail(message) {
  throw new Error(message);
}
function requiredCampaignId(value) {
  const campaignId = String(value ?? '').trim();
  if (!CAMPAIGN_ID_PATTERN.test(campaignId)) {
    fail('T5 campaignId는 영문 소문자 또는 숫자로 시작하는 76자 이하의 안전한 값이어야 합니다.');
  }
  return campaignId;
}

function repeatRunId(campaignId, repeat) {
  const runId = `${campaignId}-r${repeat}`;
  if (!RUN_ID_PATTERN.test(runId)) {
    fail(`T5 repeat runId가 안전한 형식이 아닙니다: ${runId}`);
  }
  return runId;
}

export function t5ConditionKey(role, scale) {
  if (!T5_ROLES.includes(role)) {
    fail(`지원하지 않는 T5 role입니다: ${role}`);
  }
  if (!T5_SCALES.includes(scale)) {
    fail(`지원하지 않는 T5 scale입니다: ${scale}`);
  }
  return `${role}-${scale}`;
}

export function createT5RepetitionPlan(inputCampaignId) {
  const campaignId = requiredCampaignId(inputCampaignId);
  const runs = [];

  for (let repeat = 1; repeat <= T5_REPEAT_COUNT; repeat += 1) {
    const runId = repeatRunId(campaignId, repeat);
    for (const role of T5_ROLES) {
      for (const scale of T5_SCALES) {
        const options = {
          scenario: 't5',
          runId,
          profile: 'stress',
          rounds: 1,
          t5Role: role,
          t5Scale: scale,
        };
        const fixturePlan = createFixturePlan(options);
        const conditionKey = t5ConditionKey(role, scale);
        runs.push({
          repeat,
          repeatId: `r${repeat}`,
          conditionKey,
          runId,
          fixtureId: fixturePlan.fixtureId,
          options,
          readProfile: { ...T5_READ_PROFILE },
        });
      }
    }
  }

  return {
    schemaVersion: 1,
    scenario: 't5',
    campaignId,
    repeatCount: T5_REPEAT_COUNT,
    conditionCount: T5_ROLES.length * T5_SCALES.length,
    runCount: runs.length,
    readProfile: { ...T5_READ_PROFILE },
    runs,
  };
}
