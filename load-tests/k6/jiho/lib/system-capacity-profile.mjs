export const OFFICIAL_PROFILE_ACK = 'system-active-ccu-v1';
export const OFFICIAL_EVENT_MAX_VUS = 20;
export const SMOKE_EVENT_MAX_VUS = 2;
export const OFFICIAL_WARMUP_SECONDS = 120;
export const OFFICIAL_MEASUREMENT_SECONDS = 600;
export const OFFICIAL_OBSERVATION_SECONDS = 180;
export const POLLING_INTERVAL_SECONDS = 10;
export const CHAT_MESSAGE_INTERVAL_SECONDS = 30;
const SUPPORTED_SYSTEM_ENVIRONMENT = new Set([
  'SYSTEM_ACTIVE_CCU',
  'SYSTEM_CAPACITY_PROFILE_ACK',
  'SYSTEM_CAPACITY_SMOKE',
]);

function positiveInteger(value, label) {
  if (!Number.isInteger(value) || value < 1) {
    throw new Error(`${label}은 1 이상의 정수여야 합니다.`);
  }
  return value;
}

function integerEnvironment(environment, name, minimum, maximum) {
  const raw = String(environment[name] ?? '').trim();
  const value = Number(raw);
  if (!raw || !Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name}은 ${minimum} 이상 ${maximum} 이하의 정수여야 합니다.`);
  }
  return value;
}

function sequence(start, count) {
  return Array.from({ length: count }, (_, index) => start + index);
}

export function allocateRoles(activeCcu) {
  positiveInteger(activeCcu, '활성 동접');
  const browsing = Math.floor(activeCcu * 0.6);
  const chat = Math.floor(activeCcu * 0.2);
  const waitlist = Math.floor(activeCcu * 0.1);
  return {
    browsing,
    chat,
    waitlist,
    notificationPanel: activeCcu - browsing - chat - waitlist,
  };
}

export function buildFixturePlan(activeCcu, eventMaxVus = OFFICIAL_EVENT_MAX_VUS) {
  positiveInteger(activeCcu, '활성 동접');
  positiveInteger(eventMaxVus, '이벤트 최대 VU');
  const roles = allocateRoles(activeCcu);
  let cursor = 1;
  const take = (count) => {
    const values = sequence(cursor, count);
    cursor += count;
    return values;
  };
  const plan = {
    active: take(activeCcu),
    chatHosts: take(roles.chat),
    waitlistHosts: take(roles.waitlist),
    waitlistParticipants: take(roles.waitlist),
    eventHosts: take(eventMaxVus),
    eventParticipants: take(eventMaxVus),
    eventMaxVus,
  };
  return { ...plan, requiredUsers: cursor - 1 };
}

function smokeEnabled(environment) {
  const value = String(environment.SYSTEM_CAPACITY_SMOKE ?? '').trim().toLowerCase();
  if (!['', '0', '1', 'false'].includes(value)) {
    throw new Error('SYSTEM_CAPACITY_SMOKE는 1, 0, false 또는 빈 값만 허용합니다.');
  }
  return value === '1';
}

export function resolveProfile(environment) {
  const unsupported = Object.keys(environment)
    .filter((name) => name.startsWith('SYSTEM_') && !SUPPORTED_SYSTEM_ENVIRONMENT.has(name));
  if (unsupported.length > 0) {
    throw new Error(`지원하지 않는 SYSTEM_ 환경 변수입니다: ${unsupported.sort().join(', ')}`);
  }
  const smoke = smokeEnabled(environment);
  const activeCcu = smoke ? 10 : integerEnvironment(environment, 'SYSTEM_ACTIVE_CCU', 25, 1_200);
  if (!smoke && String(environment.SYSTEM_CAPACITY_PROFILE_ACK ?? '').trim() !== OFFICIAL_PROFILE_ACK) {
    throw new Error(`공식 Run에는 SYSTEM_CAPACITY_PROFILE_ACK=${OFFICIAL_PROFILE_ACK}가 필요합니다.`);
  }
  const eventMaxVus = smoke ? SMOKE_EVENT_MAX_VUS : OFFICIAL_EVENT_MAX_VUS;
  return {
    activeCcu,
    eventMaxVus,
    fixturePlan: buildFixturePlan(activeCcu, eventMaxVus),
    measurementSeconds: smoke ? 60 : OFFICIAL_MEASUREMENT_SECONDS,
    observationSeconds: smoke ? 30 : OFFICIAL_OBSERVATION_SECONDS,
    official: !smoke,
    roles: allocateRoles(activeCcu),
    runKind: smoke ? 'smoke' : 'capacity',
    warmupSeconds: smoke ? 0 : OFFICIAL_WARMUP_SECONDS,
  };
}

export function safeActiveCcu(highestRepeatedPassCcu) {
  positiveInteger(highestRepeatedPassCcu, '반복 PASS 활성 동접');
  return Math.floor((highestRepeatedPassCcu * 0.7) / 10) * 10;
}

export function notificationEventsPerMinute(activeCcu) {
  positiveInteger(activeCcu, '활성 동접');
  return activeCcu / 12;
}
