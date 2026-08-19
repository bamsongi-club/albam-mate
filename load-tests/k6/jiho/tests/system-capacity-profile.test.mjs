import assert from 'node:assert/strict';
import test from 'node:test';

import {
  OFFICIAL_PROFILE_ACK,
  allocateRoles,
  buildFixturePlan,
  notificationEventsPerMinute,
  resolveProfile,
  safeActiveCcu,
} from '../lib/system-capacity-profile.mjs';

test('T1 역할 배분은 작은 값과 홀수에서도 합계가 활성 동접과 같다', () => {
  assert.deepEqual(allocateRoles(1), {
    browsing: 0,
    chat: 0,
    waitlist: 0,
    notificationPanel: 1,
  });
  assert.deepEqual(allocateRoles(25), {
    browsing: 15,
    chat: 5,
    waitlist: 2,
    notificationPanel: 3,
  });
  assert.equal(Object.values(allocateRoles(1_199)).reduce((sum, value) => sum + value, 0), 1_199);
});

test('T2 참가 취소 유입은 300 CCU당 알림 이벤트 25건/분으로 증가한다', () => {
  assert.equal(notificationEventsPerMinute(300), 25);
  assert.equal(notificationEventsPerMinute(600), 50);
  assert.equal(notificationEventsPerMinute(1_200), 100);
});

test('T1 fixture 계획은 활성 사용자와 모든 helper 계정을 겹치지 않게 배정한다', () => {
  const plan = buildFixturePlan(25);
  const indexes = [
    ...plan.active,
    ...plan.chatHosts,
    ...plan.waitlistHosts,
    ...plan.waitlistParticipants,
    ...plan.eventHosts,
    ...plan.eventParticipants,
  ];

  assert.equal(plan.active.length, 25);
  assert.equal(plan.chatHosts.length, 5);
  assert.equal(plan.waitlistHosts.length, 2);
  assert.equal(plan.waitlistParticipants.length, 2);
  assert.equal(plan.eventHosts.length, plan.eventMaxVus);
  assert.equal(plan.eventParticipants.length, plan.eventMaxVus);
  assert.equal(new Set(indexes).size, indexes.length);
  assert.equal(plan.requiredUsers, Math.max(...indexes));
});

test('T1 공식 profile은 ACK와 25에서 1200 사이 활성 동접을 요구한다', () => {
  assert.throws(
    () => resolveProfile({ SYSTEM_ACTIVE_CCU: '25' }),
    /SYSTEM_CAPACITY_PROFILE_ACK/,
  );
  assert.throws(
    () => resolveProfile({ SYSTEM_CAPACITY_PROFILE_ACK: OFFICIAL_PROFILE_ACK, SYSTEM_ACTIVE_CCU: '24' }),
    /25 이상 1200 이하/,
  );
  assert.equal(resolveProfile({
    SYSTEM_CAPACITY_PROFILE_ACK: OFFICIAL_PROFILE_ACK,
    SYSTEM_ACTIVE_CCU: '1199',
  }).activeCcu, 1_199);
  assert.throws(
    () => resolveProfile({
      SYSTEM_CAPACITY_PROFILE_ACK: OFFICIAL_PROFILE_ACK,
      SYSTEM_ACTIVE_CCU: '25',
      SYSTEM_MEASUREMENT_SECONDS: '1',
    }),
    /지원하지 않는 SYSTEM_ 환경 변수/,
  );
});

test('T3 smoke는 입력 동접과 무관하게 10 CCU이며 공식 결과가 아니다', () => {
  const profile = resolveProfile({ SYSTEM_CAPACITY_SMOKE: '1', SYSTEM_ACTIVE_CCU: '1200' });
  assert.equal(profile.activeCcu, 10);
  assert.equal(profile.official, false);
  assert.equal(profile.runKind, 'smoke');
});

test('T5 안전 활성 동접은 반복 PASS의 70퍼센트를 10명 단위로 내린다', () => {
  assert.equal(safeActiveCcu(25), 10);
  assert.equal(safeActiveCcu(400), 280);
  assert.equal(safeActiveCcu(1_200), 840);
});
