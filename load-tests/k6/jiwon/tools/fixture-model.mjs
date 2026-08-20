import { createHash } from 'node:crypto';

import {
  buildMixedAggregate,
  createMixedSelectionPlan,
  MIXED_OUTCOMES,
  MIXED_OPERATIONS,
  MIXED_TIERS,
  normalizeMixedProfileOptions,
} from '../lib/room-mixed-options.mjs';

export const RUN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,79}$/;
export const CONCURRENCY_LEVELS = new Set([2, 4, 8]);
export const PREPARE_OWNERSHIP_PATTERN = /^[0-9a-f]{32}$/;
export const ROOM_OUTCOME_CATEGORIES = Object.freeze([
  'success',
  'business',
  'concurrency',
  'unexpected',
]);

const SCENARIOS = new Set(['t1', 't2', 't3', 't4', 't5', 'mixed']);
const PROFILES = new Set(['stress', 'spike']);
const T3_MODES = new Set(['race', 'wait-first', 'cancel-first']);
const T5_ROLES = new Set(['public', 'host', 'participant']);
const T1_HOT_CONCURRENCY_LEVELS = new Set([2, 4, 8, 10]);
const T1_SPREAD_CONCURRENCY_LEVELS = new Set([2, 4, 8, 16]);
const T2_DISTINCT_CONCURRENCY_LEVELS = new Set([2, 4, 8, 16]);
const COMMON_OPTION_KEYS = new Set(['scenario', 'runId', 'profile', 'rounds']);
const MIXED_COMMON_OPTION_KEYS = new Set(['scenario', 'runId', 'profile']);
const SCENARIO_OPTION_KEYS = {
  t1: ['mode', 'concurrency'],
  t2: ['mode', 'concurrency', 'subcase'],
  t3: ['t3Mode'],
  t4: ['concurrency'],
  t5: ['t5Role', 't5Scale'],
  mixed: [
    'hotRoomCount', 'spreadRoomCount', 'hotRequestPercent', 'spreadRequestPercent',
    't1Percent', 't2Percent', 't5Percent', 'arrivalRate', 'arrivalTimeUnit',
    'durationSeconds', 'preAllocatedVUs', 'maxVUs', 'seed',
  ],
};

export function roomRequestDurationMetricName(category) {
  if (!ROOM_OUTCOME_CATEGORIES.includes(category)) {
    fail(`지원하지 않는 ROOM outcome: ${category}`);
  }
  return `room_request_duration{outcome:${category}}`;
}

function outcomeMetricValues(metric) {
  if (!metric || typeof metric !== 'object' || Array.isArray(metric)) {
    return null;
  }
  if (metric.values && typeof metric.values === 'object' && !Array.isArray(metric.values)) {
    return metric.values;
  }
  return metric;
}

function outcomeCount(metric) {
  const values = outcomeMetricValues(metric);
  if (!values) {
    return metric === undefined ? 0 : null;
  }
  const count = values.count;
  if (count === undefined) {
    return null;
  }
  return Number.isSafeInteger(count) && count >= 0 ? count : null;
}

function outcomeStatistic(values, ...names) {
  for (const name of names) {
    if (values && values[name] !== undefined) {
      return values[name];
    }
  }
  return null;
}

function normalizedOutcomeMetric(metric) {
  const values = outcomeMetricValues(metric);
  const count = outcomeCount(metric);
  const normalizedValues = {
    p50: null,
    p95: null,
    p99: null,
    max: null,
    count,
  };
  if (count === 0 || count === null) {
    return {
      ...(metric && typeof metric === 'object' && !Array.isArray(metric) ? metric : {}),
      type: metric?.type || 'trend',
      contains: metric?.contains || 'time',
      values: normalizedValues,
    };
  }

  normalizedValues.p50 = outcomeStatistic(values, 'p50', 'med');
  normalizedValues.p95 = outcomeStatistic(values, 'p95', 'p(95)');
  normalizedValues.p99 = outcomeStatistic(values, 'p99', 'p(99)');
  normalizedValues.max = outcomeStatistic(values, 'max');
  return {
    ...(metric && typeof metric === 'object' && !Array.isArray(metric) ? metric : {}),
    type: metric?.type || 'trend',
    contains: metric?.contains || 'time',
    values: normalizedValues,
  };
}

export function normalizeRoomSummary(summary) {
  if (!summary || typeof summary !== 'object' || Array.isArray(summary)
    || !summary.metrics || typeof summary.metrics !== 'object' || Array.isArray(summary.metrics)) {
    return summary;
  }

  const metrics = { ...summary.metrics };
  for (const category of ROOM_OUTCOME_CATEGORIES) {
    const metricName = roomRequestDurationMetricName(category);
    metrics[metricName] = normalizedOutcomeMetric(summary.metrics[metricName]);
  }
  return { ...summary, metrics };
}

function fail(message) {
  throw new Error(message);
}

function requiredText(value, name) {
  const text = String(value ?? '').trim();
  if (!text) {
    fail(`${name} 값이 필요합니다.`);
  }
  return text;
}

function integer(value, name, minimum, maximum) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < minimum || parsed > maximum) {
    fail(`${name}은(는) ${minimum} 이상 ${maximum} 이하의 정수여야 합니다.`);
  }
  return parsed;
}

function oneOf(value, name, allowed) {
  const text = requiredText(value, name);
  if (!allowed.has(text)) {
    fail(`${name}은(는) ${[...allowed].join(', ')} 중 하나여야 합니다.`);
  }
  return text;
}

function concurrencyLevelsFor(scenario, mode) {
  if (scenario === 't1') {
    return mode === 'hot' ? T1_HOT_CONCURRENCY_LEVELS : T1_SPREAD_CONCURRENCY_LEVELS;
  }
  if (scenario === 't2') {
    return T2_DISTINCT_CONCURRENCY_LEVELS;
  }
  return CONCURRENCY_LEVELS;
}

function assertAllowedOptionKeys(input, scenario) {
  const commonOptionKeys = scenario === 'mixed' ? MIXED_COMMON_OPTION_KEYS : COMMON_OPTION_KEYS;
  const allowed = new Set([...commonOptionKeys, ...SCENARIO_OPTION_KEYS[scenario]]);
  const unexpected = Object.keys(input).filter((key) => !allowed.has(key));
  if (unexpected.length > 0) {
    fail(`scenario=${scenario}에서 허용되지 않는 옵션: ${unexpected.join(', ')}`);
  }
}

function digest(value, length = 12) {
  return createHash('sha256').update(value).digest('hex').slice(0, length);
}

function sqlLiteral(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function sqlIds(values) {
  if (values.length === 0) {
    return 'NULL';
  }
  return values.join(', ');
}

export function normalizePrepareOwnership(value) {
  const ownership = String(value ?? '').toLowerCase();
  if (!PREPARE_OWNERSHIP_PATTERN.test(ownership)) {
    fail('prepare ownership은 32자리 16진수여야 합니다.');
  }
  return ownership;
}

function prepareOwnershipDescription(value) {
  return `ROOM k6 fixture ${normalizePrepareOwnership(value)}`;
}

function roomTitle(fixtureId, roomKey) {
  return `ROOM-K6 ${fixtureId} ${roomKey}`;
}

function userEmail(fixtureId, userKey) {
  return `room-k6.${fixtureId}.${userKey}@example.invalid`;
}

function nickname(fixtureId, userKey) {
  return `rk6-${fixtureId.slice(-12)}-${digest(userKey, 10)}`;
}

function createPlanner(options) {
  const users = [];
  const rooms = [];
  const userKeys = new Set();
  const roomKeys = new Set();

  function user(key) {
    if (userKeys.has(key)) {
      return key;
    }
    userKeys.add(key);
    users.push({
      key,
      email: userEmail(options.fixtureId, key),
      nickname: nickname(options.fixtureId, key),
    });
    return key;
  }

  function room(key, configuration) {
    if (roomKeys.has(key)) {
      fail(`중복 ROOM fixture key: ${key}`);
    }
    roomKeys.add(key);
    rooms.push({
      key,
      title: roomTitle(options.fixtureId, key),
      ...configuration,
    });
    return key;
  }

  return { users, rooms, user, room };
}

export function normalizeFixtureOptions(input) {
  const scenario = oneOf(input.scenario, 'scenario', SCENARIOS);
  assertAllowedOptionKeys(input, scenario);
  const runId = requiredText(input.runId, 'runId');
  if (!RUN_ID_PATTERN.test(runId)) {
    fail('runId는 영문 소문자 또는 숫자로 시작하는 80자 이하의 안전한 값이어야 합니다.');
  }

  if (scenario === 'mixed') {
    const profile = oneOf(input.profile || 'mixed', 'profile', new Set(['mixed']));
    const { targetArrivalCount, ...mixedProfile } = normalizeMixedProfileOptions(input);
    const normalized = { scenario, runId, profile, ...mixedProfile };
    const canonical = JSON.stringify(normalized);
    normalized.fixtureId = `room-k6-${scenario}-${digest(canonical)}`;
    return normalized;
  }

  const profile = oneOf(input.profile || 'stress', 'profile', PROFILES);
  const defaultRounds = profile === 'stress' ? 5 : 1;
  const rounds = integer(input.rounds || defaultRounds, 'rounds', 1, 20);
  const normalized = { scenario, runId, profile, rounds };

  if (scenario === 't1' || scenario === 't2') {
    normalized.mode = oneOf(input.mode || 'hot', 'mode', new Set(['hot', 'spread']));
  }

  if (scenario === 't1' || scenario === 't2' || scenario === 't4') {
    const concurrency = integer(input.concurrency || 8, 'concurrency', 2, 16);
    const allowedLevels = concurrencyLevelsFor(scenario, normalized.mode);
    if (!allowedLevels.has(concurrency)) {
      const mode = normalized.mode ? ` ${normalized.mode}` : '';
      fail(`${scenario.toUpperCase()}${mode} concurrency는 ${Array.from(allowedLevels).join(', ')} 중 하나여야 합니다.`);
    }
    normalized.concurrency = concurrency;
  }

  if (scenario === 't2') {
    normalized.subcase = oneOf(input.subcase || 'distinct', 'subcase', new Set(['distinct', 'duplicate']));
    if (normalized.subcase === 'duplicate' && normalized.mode !== 'hot') {
      fail('T2 duplicate subcase는 mode=hot이어야 합니다.');
    }
    if (normalized.subcase === 'duplicate' && normalized.concurrency !== 2) {
      fail('T2 duplicate subcase는 같은 사용자 요청 두 건만 비교하므로 concurrency=2여야 합니다.');
    }
  }

  if (scenario === 't3') {
    normalized.t3Mode = oneOf(input.t3Mode || 'race', 't3Mode', T3_MODES);
  }

  if (scenario === 't5') {
    normalized.t5Role = oneOf(input.t5Role || 'public', 't5Role', T5_ROLES);
    normalized.t5Scale = integer(input.t5Scale || 1, 't5Scale', 1, 10);
    if (normalized.t5Scale !== 1 && normalized.t5Scale !== 10) {
      fail('T5 t5Scale은 1 또는 10이어야 합니다.');
    }
  }

  const canonical = JSON.stringify(normalized);
  normalized.fixtureId = `room-k6-${scenario}-${digest(canonical)}`;
  return normalized;
}

function addT1Plan(options, planner) {
  const targets = [];

  const hotHostKey = options.mode === 'hot' ? planner.user('t1-hot-host') : null;
  const hotCancelKeys = options.mode === 'hot'
    ? Array.from({ length: options.concurrency }, (_, index) => planner.user(`t1-hot-cancel-${index}`))
    : [];
  const hotWaiterKeys = options.mode === 'hot'
    ? Array.from({ length: options.concurrency + 1 }, (_, index) => planner.user(`t1-hot-waiter-${index}`))
    : [];

  for (let round = 0; round < options.rounds; round += 1) {
    if (options.mode === 'hot') {
      const roomKey = planner.room(`t1-r${round}-hot`, {
        hostKey: hotHostKey,
        capacity: options.concurrency,
        status: 'CLOSED',
        activeKeys: hotCancelKeys,
        waiterKeys: hotWaiterKeys,
        cancelKeys: hotCancelKeys,
      });
      hotCancelKeys.forEach((actorKey, slot) => {
        targets.push({ round, slot, roomKey, actorKey });
      });
      continue;
    }

    for (let slot = 0; slot < options.concurrency; slot += 1) {
      const hostKey = planner.user(`t1-s${slot}-host`);
      const actorKey = planner.user(`t1-s${slot}-cancel`);
      const waiterKeys = [
        planner.user(`t1-s${slot}-waiter-0`),
        planner.user(`t1-s${slot}-waiter-1`),
      ];
      const roomKey = planner.room(`t1-r${round}-s${slot}`, {
        hostKey,
        capacity: 1,
        status: 'CLOSED',
        activeKeys: [actorKey],
        waiterKeys,
        cancelKeys: [actorKey],
      });
      targets.push({ round, slot, roomKey, actorKey });
    }
  }

  return { targets, sessionUserKeys: targets.map((target) => target.actorKey) };
}

function addT2Plan(options, planner) {
  const targets = [];

  const hotHostKey = options.mode === 'hot' || options.subcase === 'duplicate'
    ? planner.user('t2-hot-host')
    : null;
  const hotActiveKey = options.mode === 'hot' || options.subcase === 'duplicate'
    ? planner.user('t2-hot-active')
    : null;
  const hotActorKeys = options.subcase === 'distinct' && options.mode === 'hot'
    ? Array.from({ length: options.concurrency }, (_, index) => planner.user(`t2-hot-actor-${index}`))
    : [];
  const duplicateActorKey = options.subcase === 'duplicate' ? planner.user('t2-duplicate-actor') : null;

  for (let round = 0; round < options.rounds; round += 1) {
    if (options.subcase === 'duplicate') {
      const roomKey = planner.room(`t2-r${round}-duplicate`, {
        hostKey: hotHostKey,
        capacity: 1,
        status: 'CLOSED',
        activeKeys: [hotActiveKey],
        waiterKeys: [],
      });
      targets.push({ round, slot: 0, roomKey, actorKey: duplicateActorKey });
      targets.push({ round, slot: 1, roomKey, actorKey: duplicateActorKey });
      continue;
    }

    if (options.mode === 'hot') {
      const roomKey = planner.room(`t2-r${round}-hot`, {
        hostKey: hotHostKey,
        capacity: 1,
        status: 'CLOSED',
        activeKeys: [hotActiveKey],
        waiterKeys: [],
      });
      for (let slot = 0; slot < options.concurrency; slot += 1) {
        targets.push({ round, slot, roomKey, actorKey: hotActorKeys[slot] });
      }
      continue;
    }

    for (let slot = 0; slot < options.concurrency; slot += 1) {
      const hostKey = planner.user(`t2-s${slot}-host`);
      const activeKey = planner.user(`t2-s${slot}-active`);
      const actorKey = planner.user(`t2-s${slot}-actor`);
      const roomKey = planner.room(`t2-r${round}-s${slot}`, {
        hostKey,
        capacity: 1,
        status: 'CLOSED',
        activeKeys: [activeKey],
        waiterKeys: [],
      });
      targets.push({ round, slot, roomKey, actorKey });
    }
  }

  return { targets, sessionUserKeys: targets.map((target) => target.actorKey) };
}

function addT3Plan(options, planner) {
  const targets = [];
  const hostKey = planner.user('t3-host');
  const cancelKey = planner.user('t3-cancel');
  const waitKey = planner.user('t3-wait');
  for (let round = 0; round < options.rounds; round += 1) {
    const roomKey = planner.room(`t3-r${round}`, {
      hostKey,
      capacity: 1,
      status: 'CLOSED',
      activeKeys: [cancelKey],
      waiterKeys: [],
      cancelKeys: [cancelKey],
      raceWaitKey: waitKey,
    });
    targets.push({ round, roomKey, cancelKey, waitKey });
  }
  return {
    targets,
    sessionUserKeys: targets.flatMap((target) => [target.cancelKey, target.waitKey]),
  };
}

function addT4Plan(options, planner) {
  const targets = [];
  const hostKey = planner.user('t4-host');
  const candidateKeys = Array.from(
    { length: options.concurrency },
    (_, slot) => planner.user(`t4-candidate-${slot}`),
  );
  for (let round = 0; round < options.rounds; round += 1) {
    const roomKey = planner.room(`t4-r${round}`, {
      hostKey,
      capacity: 1,
      status: 'RECRUITING',
      activeKeys: [],
      waiterKeys: [],
      candidateKeys,
    });
    candidateKeys.forEach((actorKey, slot) => {
      targets.push({ round, slot, roomKey, actorKey });
    });
  }
  return { targets, sessionUserKeys: targets.map((target) => target.actorKey) };
}

function addT5Plan(options, planner) {
  const hostKey = planner.user('t5-host');
  const activeKeys = [];
  for (let index = 0; index < options.t5Scale; index += 1) {
    activeKeys.push(planner.user(`t5-active-${index}`));
  }
  const publicKey = planner.user('t5-public');
  const roomKey = planner.room(`t5-${options.t5Scale}`, {
    hostKey,
    capacity: options.t5Scale,
    status: 'CLOSED',
    activeKeys,
    waiterKeys: [],
  });
  const actorKey = options.t5Role === 'host'
    ? hostKey
    : options.t5Role === 'participant'
      ? activeKeys[0]
      : publicKey;
  return {
    targets: [{ roomKey, actorKey, role: options.t5Role, scale: options.t5Scale }],
    sessionUserKeys: [actorKey],
  };
}

function addMixedReadRoom(planner, partitions, tier, roomSlot) {
  const roomKey = `mixed-read-${tier}-r${roomSlot}`;
  const hostKey = `mixed-read-${tier}-r${roomSlot}-host`;
  const activeKey = `mixed-read-${tier}-r${roomSlot}-active`;
  const actorKey = `mixed-read-${tier}-r${roomSlot}-public`;
  planner.user(hostKey);
  planner.user(activeKey);
  planner.user(actorKey);
  planner.room(roomKey, {
    hostKey,
    capacity: 1,
    status: 'CLOSED',
    activeKeys: [activeKey],
    waiterKeys: [],
  });
  partitions.read.roomKeys.push(roomKey);
  partitions.read.userKeys.push(hostKey, activeKey, actorKey);
  return { roomKey, actorKey };
}

function addMixedWriteGroups(options, planner, selectionPlan, partitions, targetByArrival) {
  const groups = new Map();
  for (const selection of selectionPlan.selections.filter((candidate) => candidate.operation !== 't5')) {
    const key = `${selection.operation}:${selection.tier}:${selection.wave}:${selection.roomSlot}`;
    const group = groups.get(key) || {
      operation: selection.operation,
      tier: selection.tier,
      wave: selection.wave,
      roomSlot: selection.roomSlot,
      selections: [],
    };
    group.selections.push(selection);
    groups.set(key, group);
  }

  for (const group of groups.values()) {
    const prefix = `mixed-write-${group.operation}-${group.tier}-w${group.wave}-r${group.roomSlot}`;
    const hostKey = `${prefix}-host`;
    planner.user(hostKey);
    partitions.write.userKeys.push(hostKey);

    if (group.operation === 't1') {
      const cancelKeys = group.selections.map((selection) => `${prefix}-cancel-${selection.arrivalIndex}`);
      const waiterKeys = Array.from(
        { length: cancelKeys.length + 1 },
        (_, index) => `${prefix}-waiter-${index}`,
      );
      cancelKeys.forEach((key) => {
        planner.user(key);
        partitions.write.userKeys.push(key);
      });
      waiterKeys.forEach((key) => {
        planner.user(key);
        partitions.write.userKeys.push(key);
      });
      const roomKey = planner.room(prefix, {
        hostKey,
        capacity: cancelKeys.length,
        status: 'CLOSED',
        activeKeys: cancelKeys,
        waiterKeys,
        cancelKeys,
      });
      partitions.write.roomKeys.push(roomKey);
      group.selections.forEach((selection, index) => {
        targetByArrival.set(selection.arrivalIndex, {
          ...selection,
          fixture: 'write',
          roomKey,
          actorKey: cancelKeys[index],
        });
      });
      continue;
    }

    const activeKey = `${prefix}-active`;
    planner.user(activeKey);
    partitions.write.userKeys.push(activeKey);
    const actorKeys = group.selections.map((selection) => `${prefix}-actor-${selection.arrivalIndex}`);
    actorKeys.forEach((key) => {
      planner.user(key);
      partitions.write.userKeys.push(key);
    });
    const roomKey = planner.room(prefix, {
      hostKey,
      capacity: 1,
      status: 'CLOSED',
      activeKeys: [activeKey],
      waiterKeys: [],
    });
    partitions.write.roomKeys.push(roomKey);
    group.selections.forEach((selection, index) => {
      targetByArrival.set(selection.arrivalIndex, {
        ...selection,
        fixture: 'write',
        roomKey,
        actorKey: actorKeys[index],
      });
    });
  }
}

function addMixedPlan(options, planner) {
  const selectionPlan = createMixedSelectionPlan(options);
  const partitions = {
    write: { roomKeys: [], userKeys: [] },
    read: { roomKeys: [], userKeys: [] },
  };
  const targetByArrival = new Map();
  addMixedWriteGroups(options, planner, selectionPlan, partitions, targetByArrival);

  const readTargets = new Map();
  for (const tier of MIXED_TIERS) {
    const roomCount = tier === 'hot' ? options.hotRoomCount : options.spreadRoomCount;
    for (let roomSlot = 0; roomSlot < roomCount; roomSlot += 1) {
      readTargets.set(`${tier}:${roomSlot}`, addMixedReadRoom(planner, partitions, tier, roomSlot));
    }
  }
  for (const selection of selectionPlan.selections.filter((candidate) => candidate.operation === 't5')) {
    const readTarget = readTargets.get(`${selection.tier}:${selection.roomSlot}`);
    targetByArrival.set(selection.arrivalIndex, {
      ...selection,
      fixture: 'read',
      roomKey: readTarget.roomKey,
      actorKey: readTarget.actorKey,
      role: 'public',
      scale: 1,
    });
  }

  const targets = selectionPlan.selections.map((selection) => targetByArrival.get(selection.arrivalIndex));
  if (targets.some((target) => !target)) {
    fail('mixed selection에 대응하는 fixture target을 만들지 못했습니다.');
  }
  return {
    targets,
    sessionUserKeys: [...new Set(targets.map((target) => target.actorKey))],
    fixturePartitions: partitions,
    mixedProfile: {
      options: selectionPlan.options,
      selectionPlanDigest: selectionPlan.selectionPlanDigest,
      selectionCounts: selectionPlan.counts,
    },
  };
}

export function createFixturePlan(input) {
  const options = normalizeFixtureOptions(input);
  const planner = createPlanner(options);
  let execution;

  switch (options.scenario) {
    case 't1':
      execution = addT1Plan(options, planner);
      break;
    case 't2':
      execution = addT2Plan(options, planner);
      break;
    case 't3':
      execution = addT3Plan(options, planner);
      break;
    case 't4':
      execution = addT4Plan(options, planner);
      break;
    case 't5':
      execution = addT5Plan(options, planner);
      break;
    case 'mixed':
      execution = addMixedPlan(options, planner);
      break;
    default:
      fail(`지원하지 않는 scenario: ${options.scenario}`);
  }

  return {
    schemaVersion: 2,
    fixtureId: options.fixtureId,
    options,
    users: planner.users,
    rooms: planner.rooms,
    ...execution,
  };
}

function roomIdSql(room) {
  return `(SELECT id FROM rooms WHERE title = ${sqlLiteral(room.title)})`;
}

function userIdSql(user) {
  return `(SELECT id FROM users WHERE email = ${sqlLiteral(user.email)})`;
}

export function buildPrepareSql(plan, passwordHash, prepareOwnership) {
  if (!String(passwordHash).startsWith('{bcrypt}$')) {
    fail('ROOM_K6_FIXTURE_PASSWORD_HASH는 {bcrypt}$로 시작해야 합니다.');
  }

  const ownershipDescription = prepareOwnershipDescription(prepareOwnership);
  const usersByKey = new Map(plan.users.map((user) => [user.key, user]));
  const roomsByKey = new Map(plan.rooms.map((room) => [room.key, room]));
  const userRows = plan.users.map((user) => `(
        ${sqlLiteral(user.email)},
        ${sqlLiteral(passwordHash)},
        ${sqlLiteral(user.nickname)},
        clock_timestamp(),
        clock_timestamp()
    )`);
  const roomRows = plan.rooms.map((room) => {
    const host = usersByKey.get(room.hostKey);
    return [
      room.title,
      host.email,
      room.capacity,
      room.activeKeys.length,
      room.status,
    ];
  });

  const participationStatements = [];
  let participationOrder = 0;
  for (const room of plan.rooms) {
    for (const userKey of room.activeKeys) {
      participationOrder += 1;
      participationStatements.push(`INSERT INTO participations (
    room_id, user_id, status, joined_at, canceled_at, created_at, updated_at
) VALUES (
    ${roomIdSql(room)},
    ${userIdSql(usersByKey.get(userKey))},
    'ACTIVE',
    clock_timestamp() + interval '${participationOrder} milliseconds',
    NULL,
    clock_timestamp(),
    clock_timestamp()
);`);
    }
  }

  const waitlistStatements = [];
  for (const room of plan.rooms) {
    for (const userKey of room.waiterKeys) {
      waitlistStatements.push(`INSERT INTO room_waitlists (
    room_id, user_id, status, queue_order, queued_at, created_at, updated_at
) VALUES (
    ${roomIdSql(room)},
    ${userIdSql(usersByKey.get(userKey))},
    'WAITING',
    nextval('room_waitlist_queue_order_seq'),
    clock_timestamp(),
    clock_timestamp(),
    clock_timestamp()
);`);
    }
  }

  return `\\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext(${sqlLiteral(plan.fixtureId)}));

INSERT INTO users (email, password_hash, nickname, created_at, updated_at)
VALUES
    ${userRows.join(',\n    ')};

INSERT INTO rooms (
    game_id, host_user_id, room_type, title, description, experience_level,
    is_rulemaster_led, region, capacity, active_participant_count, start_at,
    place, status, version, created_at, updated_at
)
VALUES
    ${roomRows.map(([title, hostEmail, capacity, activeCount, status]) => `(
        NULL,
        (SELECT id FROM users WHERE email = ${sqlLiteral(hostEmail)}),
        'PERSON_FOCUSED',
        ${sqlLiteral(title)},
        ${sqlLiteral(ownershipDescription)},
        'ALL_LEVELS',
        false,
        'ROOM-K6',
        ${capacity},
        ${activeCount},
        clock_timestamp() + interval '6 hours',
        'ROOM-K6 fixture',
        ${sqlLiteral(status)},
        0,
        clock_timestamp(),
        clock_timestamp()
    )`).join(',\n    ')};

${participationStatements.join('\n\n')}

${waitlistStatements.join('\n\n')}

COMMIT;
`;
}

export function buildResourceQuery(plan, prepareOwnership) {
  const emails = plan.users.map((user) => sqlLiteral(user.email)).join(', ');
  const titles = plan.rooms.map((room) => sqlLiteral(room.title)).join(', ');
  const ownershipDescription = prepareOwnershipDescription(prepareOwnership);
  return `SELECT jsonb_build_object(
  'users', COALESCE((
    SELECT jsonb_object_agg(email, id)
    FROM users
    WHERE email IN (${emails})
  ), '{}'::jsonb),
  'rooms', COALESCE((
    SELECT jsonb_object_agg(title, id)
    FROM rooms
    WHERE title IN (${titles})
      AND description = ${sqlLiteral(ownershipDescription)}
  ), '{}'::jsonb)
);`;
}

export function hydrateFixture(plan, resources, prepareOwnership) {
  const normalizedPrepareOwnership = normalizePrepareOwnership(prepareOwnership);
  const users = {};
  const rooms = {};
  for (const user of plan.users) {
    const id = resources.users?.[user.email];
    if (!Number.isInteger(id)) {
      fail(`fixture 사용자 ID를 찾지 못했습니다: ${user.key}`);
    }
    users[user.key] = { id, email: user.email, nickname: user.nickname };
  }
  for (const room of plan.rooms) {
    const id = resources.rooms?.[room.title];
    if (!Number.isInteger(id)) {
      fail(`fixture ROOM ID를 찾지 못했습니다: ${room.key}`);
    }
    rooms[room.key] = {
      id,
      title: room.title,
      hostKey: room.hostKey,
      capacity: room.capacity,
      status: room.status,
      activeKeys: room.activeKeys,
      waiterKeys: room.waiterKeys,
      cancelKeys: room.cancelKeys || [],
      candidateKeys: room.candidateKeys || [],
      raceWaitKey: room.raceWaitKey || null,
    };
  }
  return {
    schemaVersion: plan.schemaVersion,
    fixtureId: plan.fixtureId,
    options: plan.options,
    prepareOwnership: normalizedPrepareOwnership,
    users,
    rooms,
    targets: plan.targets,
    sessionUserKeys: [...new Set(plan.sessionUserKeys)],
    ...(plan.fixturePartitions ? { fixturePartitions: plan.fixturePartitions } : {}),
    ...(plan.mixedProfile ? { mixedProfile: plan.mixedProfile } : {}),
  };
}

export function buildSnapshotQuery(fixture) {
  const roomIds = Object.values(fixture.rooms).map((room) => room.id);
  return `SELECT jsonb_build_object(
  'rooms', COALESCE((
    SELECT jsonb_agg(row_to_json(room_row) ORDER BY room_row.id)
    FROM (
      SELECT id, host_user_id AS "hostUserId", title, capacity,
             active_participant_count AS "activeParticipantCount", status,
             version, start_at AS "startAt", updated_at AS "updatedAt"
      FROM rooms
      WHERE id IN (${sqlIds(roomIds)})
    ) AS room_row
  ), '[]'::jsonb),
  'participations', COALESCE((
    SELECT jsonb_agg(row_to_json(participation_row) ORDER BY participation_row."roomId", participation_row."userId")
    FROM (
      SELECT room_id AS "roomId", user_id AS "userId", status,
             joined_at AS "joinedAt", canceled_at AS "canceledAt"
      FROM participations
      WHERE room_id IN (${sqlIds(roomIds)})
    ) AS participation_row
  ), '[]'::jsonb),
  'waitlists', COALESCE((
    SELECT jsonb_agg(row_to_json(waitlist_row) ORDER BY waitlist_row."roomId", waitlist_row."queueOrder", waitlist_row."userId")
    FROM (
      SELECT room_id AS "roomId", user_id AS "userId", status,
             queue_order AS "queueOrder", queued_at AS "queuedAt"
      FROM room_waitlists
      WHERE room_id IN (${sqlIds(roomIds)})
    ) AS waitlist_row
  ), '[]'::jsonb)
);`;
}

function fixtureIdentityRows(fixture, type) {
  if (type === 'users') {
    return Object.values(fixture.users).map((user) => [user.id, user.email]);
  }
  const ownershipDescription = prepareOwnershipDescription(fixture.prepareOwnership);
  return Object.values(fixture.rooms).map((room) => [room.id, room.title, ownershipDescription]);
}

export function buildCleanupSql(fixture) {
  const userRows = fixtureIdentityRows(fixture, 'users');
  const roomRows = fixtureIdentityRows(fixture, 'rooms');
  const userIds = userRows.map(([id]) => id);
  const roomIds = roomRows.map(([id]) => id);

  return `\\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext(${sqlLiteral(fixture.fixtureId)}));

CREATE TEMP TABLE room_k6_cleanup_users (
    id bigint PRIMARY KEY,
    email text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_users (id, email) VALUES
    ${userRows.map(([id, email]) => `(${id}, ${sqlLiteral(email)})`).join(',\n    ')};

CREATE TEMP TABLE room_k6_cleanup_rooms (
    id bigint PRIMARY KEY,
    title text NOT NULL,
    description text NOT NULL
) ON COMMIT DROP;
INSERT INTO room_k6_cleanup_rooms (id, title, description) VALUES
    ${roomRows.map(([id, title, description]) => `(${id}, ${sqlLiteral(title)}, ${sqlLiteral(description)})`).join(',\n    ')};

DO $$
BEGIN
    -- 검증 뒤 새 파생 행이 삽입되어 삭제되지 않도록 FK 부모 행을 먼저 잠근다.
    PERFORM 1
    FROM rooms room
    JOIN room_k6_cleanup_rooms fixture ON fixture.id = room.id
    ORDER BY room.id
    FOR UPDATE OF room;
    PERFORM 1
    FROM notification_outbox_events event
    JOIN room_k6_cleanup_rooms fixture ON fixture.id = event.room_id
    ORDER BY event.id
    FOR UPDATE OF event;
    PERFORM 1
    FROM chat_rooms chat_room
    JOIN room_k6_cleanup_rooms fixture ON fixture.id = chat_room.room_id
    ORDER BY chat_room.id
    FOR UPDATE OF chat_room;

    IF (SELECT count(*) FROM users u JOIN room_k6_cleanup_users f ON f.id = u.id AND f.email = u.email)
        <> (SELECT count(*) FROM room_k6_cleanup_users) THEN
        RAISE EXCEPTION 'ROOM k6 fixture user identity mismatch';
    END IF;
    IF (SELECT count(*) FROM rooms r JOIN room_k6_cleanup_rooms f
        ON f.id = r.id AND f.title = r.title AND f.description = r.description)
        <> (SELECT count(*) FROM room_k6_cleanup_rooms) THEN
        RAISE EXCEPTION 'ROOM k6 fixture ROOM identity mismatch';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM participations participation
        JOIN room_k6_cleanup_rooms r ON r.id = participation.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = participation.user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has participation by non-fixture user';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM room_waitlists waitlist
        JOIN room_k6_cleanup_rooms r ON r.id = waitlist.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = waitlist.user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has waitlist by non-fixture user';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notifications n
        JOIN room_k6_cleanup_rooms r ON r.id = n.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = n.recipient_user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has notification for non-fixture user';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notifications n
        JOIN room_k6_cleanup_rooms fixture_room ON fixture_room.id = n.room_id
        JOIN notification_outbox_events source_event ON source_event.id = n.source_event_id
        WHERE source_event.room_id <> n.room_id
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has notification from another ROOM outbox event';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notification_outbox_recipients recipient
        JOIN notification_outbox_events event ON event.id = recipient.outbox_event_id
        JOIN room_k6_cleanup_rooms r ON r.id = event.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = recipient.recipient_user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has outbox recipient outside fixture users';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM chat_messages message
        JOIN chat_rooms chat_room ON chat_room.id = message.chat_room_id
        JOIN room_k6_cleanup_rooms r ON r.id = chat_room.room_id
        LEFT JOIN room_k6_cleanup_users u ON u.id = message.sender_user_id
        WHERE u.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture ROOM has chat message by non-fixture user';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM rooms room
        JOIN room_k6_cleanup_users u ON u.id = room.host_user_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = room.id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user hosts ROOM outside fixture';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM participations participation
        JOIN room_k6_cleanup_users u ON u.id = participation.user_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = participation.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has participation outside fixture ROOM';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM room_waitlists waitlist
        JOIN room_k6_cleanup_users u ON u.id = waitlist.user_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = waitlist.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has waitlist outside fixture ROOM';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notifications n
        JOIN room_k6_cleanup_users u ON u.id = n.recipient_user_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = n.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has notification outside fixture ROOM';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM notification_outbox_recipients recipient
        JOIN room_k6_cleanup_users u ON u.id = recipient.recipient_user_id
        JOIN notification_outbox_events event ON event.id = recipient.outbox_event_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = event.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has outbox recipient outside fixture ROOM';
    END IF;
    IF EXISTS (
        SELECT 1
        FROM chat_messages message
        JOIN room_k6_cleanup_users u ON u.id = message.sender_user_id
        JOIN chat_rooms chat_room ON chat_room.id = message.chat_room_id
        LEFT JOIN room_k6_cleanup_rooms r ON r.id = chat_room.room_id
        WHERE r.id IS NULL
    ) THEN
        RAISE EXCEPTION 'fixture user has chat message outside fixture ROOM';
    END IF;
    IF EXISTS (SELECT 1 FROM social_accounts account JOIN room_k6_cleanup_users u ON u.id = account.user_id) THEN
        RAISE EXCEPTION 'fixture user has social account';
    END IF;
    IF EXISTS (SELECT 1 FROM user_played_games played JOIN room_k6_cleanup_users u ON u.id = played.user_id) THEN
        RAISE EXCEPTION 'fixture user has played game';
    END IF;
END $$;

DELETE FROM notifications WHERE room_id IN (${sqlIds(roomIds)});
DELETE FROM notification_outbox_events WHERE room_id IN (${sqlIds(roomIds)});
DELETE FROM chat_messages WHERE chat_room_id IN (
    SELECT id FROM chat_rooms WHERE room_id IN (${sqlIds(roomIds)})
);
DELETE FROM chat_rooms WHERE room_id IN (${sqlIds(roomIds)});
DELETE FROM room_waitlists WHERE room_id IN (${sqlIds(roomIds)});
DELETE FROM participations WHERE room_id IN (${sqlIds(roomIds)});
DELETE FROM rooms WHERE id IN (${sqlIds(roomIds)});
DELETE FROM users WHERE id IN (${sqlIds(userIds)});

COMMIT;
`;
}

function roomSnapshot(snapshot, roomId) {
  return snapshot.rooms.find((room) => room.id === roomId);
}

function participationsFor(snapshot, roomId) {
  return snapshot.participations.filter((participation) => participation.roomId === roomId);
}

function waitlistsFor(snapshot, roomId) {
  return snapshot.waitlists.filter((waitlist) => waitlist.roomId === roomId);
}

function participationStatus(snapshot, roomId, userId) {
  return participationsFor(snapshot, roomId).find((entry) => entry.userId === userId)?.status || null;
}

function waitlistStatus(snapshot, roomId, userId) {
  return waitlistsFor(snapshot, roomId).find((entry) => entry.userId === userId)?.status || null;
}

function metricCount(summary, name) {
  const metric = summary?.metrics?.[name];
  const nestedCount = metric?.values?.count;
  if (nestedCount !== undefined) {
    return Number.isSafeInteger(nestedCount) && nestedCount >= 0 ? nestedCount : null;
  }
  const directCount = metric?.count;
  return Number.isSafeInteger(directCount) && directCount >= 0 ? directCount : null;
}

function metricValues(summary, name) {
  const metric = summary?.metrics?.[name];
  if (metric?.values && typeof metric.values === 'object' && !Array.isArray(metric.values)) {
    return metric.values;
  }
  if (metric && typeof metric === 'object' && !Array.isArray(metric)) {
    return metric;
  }
  return null;
}

function addFailure(failures, condition, message) {
  if (!condition) {
    failures.push(message);
  }
}

function evaluateCommonInvariants(fixture, snapshot, failures) {
  const roomIds = new Set(Object.values(fixture.rooms).map((room) => room.id));
  addFailure(failures, snapshot.rooms.length === roomIds.size, 'fixture ROOM 일부를 찾지 못했습니다.');

  for (const room of snapshot.rooms) {
    const active = participationsFor(snapshot, room.id).filter((entry) => entry.status === 'ACTIVE');
    addFailure(
      failures,
      room.activeParticipantCount === active.length,
      `ROOM ${room.id}: activeParticipantCount와 ACTIVE 행 수가 다릅니다.`,
    );
    addFailure(
      failures,
      room.activeParticipantCount >= 0 && room.activeParticipantCount <= room.capacity,
      `ROOM ${room.id}: 정원 범위를 벗어났습니다.`,
    );

    const waiting = waitlistsFor(snapshot, room.id).filter((entry) => entry.status === 'WAITING');
    const queueOrders = new Set(waiting.map((entry) => entry.queueOrder));
    addFailure(
      failures,
      queueOrders.size === waiting.length && waiting.every((entry) => entry.queueOrder > 0),
      `ROOM ${room.id}: WAITING queue_order가 양수·유일하지 않습니다.`,
    );
    const activeUserIds = new Set(active.map((entry) => entry.userId));
    addFailure(
      failures,
      waiting.every((entry) => !activeUserIds.has(entry.userId)),
      `ROOM ${room.id}: 같은 사용자가 ACTIVE와 WAITING에 동시에 있습니다.`,
    );
  }
}

function evaluateT1(fixture, snapshot, failures, summary) {
  let canceledCount = 0;
  let promotedCount = 0;
  for (const room of Object.values(fixture.rooms)) {
    const canceled = room.cancelKeys.filter(
      (key) => participationStatus(snapshot, room.id, fixture.users[key].id) === 'CANCELED',
    );
    const promoted = room.waiterKeys.filter(
      (key) => waitlistStatus(snapshot, room.id, fixture.users[key].id) === 'PROMOTED',
    );
    canceledCount += canceled.length;
    promotedCount += promoted.length;
    addFailure(failures, promoted.length === canceled.length, `ROOM ${room.id}: 취소 수와 PROMOTED 수가 다릅니다.`);

    room.waiterKeys.forEach((key, index) => {
      const userId = fixture.users[key].id;
      const expectedPromoted = index < canceled.length;
      const actualWaitlistStatus = waitlistStatus(snapshot, room.id, userId);
      const actualParticipationStatus = participationStatus(snapshot, room.id, userId);
      addFailure(
        failures,
        expectedPromoted
          ? actualWaitlistStatus === 'PROMOTED' && actualParticipationStatus === 'ACTIVE'
          : actualWaitlistStatus === 'WAITING' && actualParticipationStatus === null,
        `ROOM ${room.id}: FIFO 승격 상태가 기대와 다릅니다.`,
      );
    });

    const current = roomSnapshot(snapshot, room.id);
    addFailure(failures, current?.status === 'CLOSED', `ROOM ${room.id}: 대기자가 충분한데 CLOSED가 아닙니다.`);
    addFailure(failures, current?.activeParticipantCount === room.capacity, `ROOM ${room.id}: 승격 뒤 정원이 유지되지 않았습니다.`);
  }

  const success = metricCount(summary, 'room_success');
  if (success !== null) {
    addFailure(failures, success === canceledCount, 'T1 HTTP 성공 수와 CANCELED 행 수가 다릅니다.');
  }
  addFailure(failures, promotedCount === canceledCount, 'T1 전체 PROMOTED 수와 CANCELED 수가 다릅니다.');
}

function evaluateT2(fixture, snapshot, failures, summary) {
  const waitingByActor = fixture.targets.filter((target, index, targets) => (
    targets.findIndex((candidate) => candidate.actorKey === target.actorKey && candidate.roomKey === target.roomKey) === index
  )).filter((target) => waitlistStatus(snapshot, fixture.rooms[target.roomKey].id, fixture.users[target.actorKey].id) === 'WAITING');

  for (const room of Object.values(fixture.rooms)) {
    const current = roomSnapshot(snapshot, room.id);
    addFailure(failures, current?.status === 'CLOSED', `ROOM ${room.id}: T2 뒤 CLOSED가 아닙니다.`);
    addFailure(failures, current?.activeParticipantCount === room.capacity, `ROOM ${room.id}: T2 뒤 ACTIVE 수가 변했습니다.`);
  }

  for (const target of fixture.targets) {
    const room = fixture.rooms[target.roomKey];
    addFailure(
      failures,
      participationStatus(snapshot, room.id, fixture.users[target.actorKey].id) === null,
      `ROOM ${room.id}: 대기 신청자가 ACTIVE가 되었습니다.`,
    );
  }

  const created = metricCount(summary, 'room_created');
  addFailure(failures, created !== null, 'T2 room_created metric이 부족합니다.');
  if (fixture.options.subcase === 'distinct') {
    if (created !== null) {
      addFailure(failures, created === waitingByActor.length, 'T2 201 신규 성공 수와 WAITING 행 수가 다릅니다.');
    }
    if (summary && created !== null) {
      const expectedPositionCounts = Array.from({ length: fixture.options.concurrency }, () => 0);
      for (const room of Object.values(fixture.rooms)) {
        const waitingCount = waitlistsFor(snapshot, room.id)
          .filter((entry) => entry.status === 'WAITING').length;
        if (fixture.options.mode === 'hot') {
          for (let position = 1; position <= waitingCount; position += 1) {
            expectedPositionCounts[position - 1] += 1;
          }
        } else {
          expectedPositionCounts[0] += waitingCount;
        }
      }
      for (let position = 1; position <= fixture.options.concurrency; position += 1) {
        const observed = metricCount(summary, `room_waitlist_position_${position}`);
        const expected = expectedPositionCounts[position - 1];
        addFailure(
          failures,
          observed === expected,
          `T2 ${fixture.options.mode} 응답 position=${position} 관측 수가 ${expected}가 아닙니다.`,
        );
      }
    }
  } else {
    const duplicateTarget = fixture.targets[0];
    const duplicateRoom = fixture.rooms[duplicateTarget.roomKey];
    const duplicateUserId = fixture.users[duplicateTarget.actorKey].id;
    const duplicateWaitingCount = waitlistsFor(snapshot, duplicateRoom.id)
      .filter((entry) => entry.userId === duplicateUserId && entry.status === 'WAITING').length;
    addFailure(failures, duplicateWaitingCount === 1, 'T2 duplicate는 WAITING 한 행으로 수렴해야 합니다.');
    if (created !== null) {
      addFailure(failures, created === 1, 'T2 duplicate는 201 신규 성공이 정확히 한 건이어야 합니다.');
    }
  }
}

function isPromotionState(snapshot, fixture, room) {
  const cancelKey = room.cancelKeys[0];
  const waitKey = room.raceWaitKey;
  const current = roomSnapshot(snapshot, room.id);
  return current?.status === 'CLOSED'
    && current.activeParticipantCount === 1
    && participationStatus(snapshot, room.id, fixture.users[cancelKey].id) === 'CANCELED'
    && participationStatus(snapshot, room.id, fixture.users[waitKey].id) === 'ACTIVE'
    && waitlistStatus(snapshot, room.id, fixture.users[waitKey].id) === 'PROMOTED'
    && waitlistsFor(snapshot, room.id).filter((entry) => entry.status === 'WAITING').length === 0;
}

function isVacancyState(snapshot, fixture, room) {
  const cancelKey = room.cancelKeys[0];
  const waitKey = room.raceWaitKey;
  const current = roomSnapshot(snapshot, room.id);
  return current?.status === 'RECRUITING'
    && current.activeParticipantCount === 0
    && participationStatus(snapshot, room.id, fixture.users[cancelKey].id) === 'CANCELED'
    && participationStatus(snapshot, room.id, fixture.users[waitKey].id) === null
    && waitlistStatus(snapshot, room.id, fixture.users[waitKey].id) === null;
}

function isRetainedFullState(snapshot, fixture, room) {
  const cancelKey = room.cancelKeys[0];
  const waitKey = room.raceWaitKey;
  const current = roomSnapshot(snapshot, room.id);
  return current?.status === 'CLOSED'
    && current.activeParticipantCount === 1
    && participationStatus(snapshot, room.id, fixture.users[cancelKey].id) === 'ACTIVE'
    && (waitlistStatus(snapshot, room.id, fixture.users[waitKey].id) === 'WAITING'
      || waitlistStatus(snapshot, room.id, fixture.users[waitKey].id) === null);
}

function evaluateT3(fixture, snapshot, failures) {
  for (const room of Object.values(fixture.rooms)) {
    const waitingCount = waitlistsFor(snapshot, room.id).filter((entry) => entry.status === 'WAITING').length;
    const current = roomSnapshot(snapshot, room.id);
    addFailure(
      failures,
      !(current?.status === 'RECRUITING' && waitingCount > 0),
      `ROOM ${room.id}: RECRUITING + WAITING 불일치 상태입니다.`,
    );

    const validState = fixture.options.t3Mode === 'wait-first'
      ? isPromotionState(snapshot, fixture, room)
      : fixture.options.t3Mode === 'cancel-first'
        ? isVacancyState(snapshot, fixture, room)
        : isPromotionState(snapshot, fixture, room)
          || isVacancyState(snapshot, fixture, room)
          || isRetainedFullState(snapshot, fixture, room);
    addFailure(failures, validState, `ROOM ${room.id}: T3 허용 종단 또는 재시도 소진 보존 상태가 아닙니다.`);
  }
}

function evaluateT4(fixture, snapshot, failures, summary) {
  let activeCandidates = 0;
  for (const room of Object.values(fixture.rooms)) {
    const active = room.candidateKeys.filter(
      (key) => participationStatus(snapshot, room.id, fixture.users[key].id) === 'ACTIVE',
    );
    activeCandidates += active.length;
    const current = roomSnapshot(snapshot, room.id);
    addFailure(failures, active.length === 1, `ROOM ${room.id}: ACTIVE 후보가 정확히 한 명이 아닙니다.`);
    addFailure(failures, current?.status === 'CLOSED', `ROOM ${room.id}: T4 뒤 CLOSED가 아닙니다.`);
    addFailure(failures, current?.activeParticipantCount === 1, `ROOM ${room.id}: T4 activeParticipantCount가 1이 아닙니다.`);
    const candidateUserIds = new Set(room.candidateKeys.map((key) => fixture.users[key].id));
    const candidateWaitlists = waitlistsFor(snapshot, room.id)
      .filter((entry) => candidateUserIds.has(entry.userId));
    addFailure(failures, candidateWaitlists.length === 0, `ROOM ${room.id}: 직접 참가 실패 후보의 대기 행이 생겼습니다.`);
  }
  const success = metricCount(summary, 'room_success');
  if (success !== null) {
    addFailure(failures, success === activeCandidates, 'T4 201 성공 수와 ACTIVE 후보 수가 다릅니다.');
  }
}

function evaluateT5(fixture, snapshot, baselineSnapshot, failures) {
  addFailure(
    failures,
    JSON.stringify(snapshot) === JSON.stringify(baselineSnapshot),
    'T5 상세 조회 전후 ROOM·participation·waitlist snapshot이 달라졌습니다.',
  );
}

function mixedPartition(fixture, name) {
  const partition = fixture.fixturePartitions?.[name];
  if (!partition || !Array.isArray(partition.roomKeys) || !Array.isArray(partition.userKeys)) {
    return null;
  }
  return partition;
}

function mixedRooms(fixture, operation) {
  const roomKeys = new Set(
    fixture.targets
      .filter((target) => target.operation === operation)
      .map((target) => target.roomKey),
  );
  return [...roomKeys].map((roomKey) => fixture.rooms[roomKey]).filter(Boolean);
}

function mixedOutcomeCount(aggregate, operation, outcome) {
  if (!aggregate?.tiers) {
    return null;
  }
  return MIXED_TIERS.reduce((total, tier) => {
    const count = aggregate.tiers[tier]?.[operation]?.[outcome]?.count;
    return Number.isSafeInteger(count) ? total + count : total;
  }, 0);
}

function snapshotForRoomKeys(snapshot, fixture, roomKeys) {
  const roomIds = new Set(roomKeys.map((key) => fixture.rooms[key]?.id).filter(Number.isInteger));
  return {
    rooms: snapshot.rooms.filter((room) => roomIds.has(room.id)),
    participations: snapshot.participations.filter((entry) => roomIds.has(entry.roomId)),
    waitlists: snapshot.waitlists.filter((entry) => roomIds.has(entry.roomId)),
  };
}

function evaluateMixedPartitions(fixture, snapshot, failures) {
  const failureStart = failures.length;
  const write = mixedPartition(fixture, 'write');
  const read = mixedPartition(fixture, 'read');
  addFailure(failures, write !== null && read !== null, 'mixed write/read fixture partition이 없습니다.');
  if (!write || !read) {
    return { invalid: true, valid: false };
  }

  const writeRoomKeys = new Set(write.roomKeys);
  const writeUserKeys = new Set(write.userKeys);
  const readRoomKeys = new Set(read.roomKeys);
  const readUserKeys = new Set(read.userKeys);
  addFailure(
    failures,
    writeRoomKeys.size === write.roomKeys.length
      && readRoomKeys.size === read.roomKeys.length
      && writeUserKeys.size === write.userKeys.length
      && readUserKeys.size === read.userKeys.length,
    'mixed fixture partition에 중복 resource key가 있습니다.',
  );
  addFailure(
    failures,
    [...writeRoomKeys].every((key) => !readRoomKeys.has(key))
      && [...writeUserKeys].every((key) => !readUserKeys.has(key)),
    'mixed T1/T2 write fixture와 T5 read fixture가 격리되지 않았습니다.',
  );

  const partitionDefinitions = [
    { name: 'write', roomKeys: writeRoomKeys, userKeys: writeUserKeys },
    { name: 'read', roomKeys: readRoomKeys, userKeys: readUserKeys },
  ];
  for (const partition of partitionDefinitions) {
    const rooms = [...partition.roomKeys].map((key) => fixture.rooms[key]);
    const users = [...partition.userKeys].map((key) => fixture.users[key]);
    if (!rooms.every(Boolean) || !users.every(Boolean)) {
      addFailure(failures, false, `mixed ${partition.name} fixture partition의 resource key를 찾지 못했습니다.`);
      return { invalid: true, valid: false };
    }

    const roomIds = rooms.map((room) => room.id);
    const userIds = users.map((user) => user.id);
    if (!roomIds.every(Number.isInteger) || !userIds.every(Number.isInteger)) {
      addFailure(failures, false, `mixed ${partition.name} fixture partition의 DB resource identity가 올바르지 않습니다.`);
      return { invalid: true, valid: false };
    }
    addFailure(
      failures,
      new Set(roomIds).size === roomIds.length,
      `mixed ${partition.name} fixture partition의 DB ROOM ID가 유일하지 않습니다.`,
    );
    addFailure(
      failures,
      new Set(userIds).size === userIds.length,
      `mixed ${partition.name} fixture partition의 DB 사용자 ID가 유일하지 않습니다.`,
    );

    const partitionRoomIds = new Set(roomIds);
    const partitionUserIds = new Set(userIds);
    const partitionParticipations = snapshot.participations
      .filter((entry) => partitionRoomIds.has(entry.roomId));
    const partitionWaitlists = snapshot.waitlists
      .filter((entry) => partitionRoomIds.has(entry.roomId));
    addFailure(
      failures,
      partitionParticipations.every((entry) => partitionUserIds.has(entry.userId)),
      `mixed ${partition.name} snapshot participation의 사용자가 같은 partition에 속하지 않습니다.`,
    );
    addFailure(
      failures,
      partitionWaitlists.every((entry) => partitionUserIds.has(entry.userId)),
      `mixed ${partition.name} snapshot waitlist의 사용자가 같은 partition에 속하지 않습니다.`,
    );

    for (const room of rooms) {
      if (!room) {
        continue;
      }
      const roomUserKeys = [
        room.hostKey,
        ...(room.activeKeys || []),
        ...(room.waiterKeys || []),
        ...(room.cancelKeys || []),
        ...(room.candidateKeys || []),
        ...(room.raceWaitKey ? [room.raceWaitKey] : []),
      ];
      addFailure(
        failures,
        roomUserKeys.every((key) => partition.userKeys.has(key)),
        `mixed ${partition.name} fixture room의 사용자가 같은 partition에 속하지 않습니다.`,
      );
    }
  }

  const writeRoomIds = [...writeRoomKeys].map((key) => fixture.rooms[key]?.id);
  const readRoomIds = [...readRoomKeys].map((key) => fixture.rooms[key]?.id);
  const writeUserIds = [...writeUserKeys].map((key) => fixture.users[key]?.id);
  const readUserIds = [...readUserKeys].map((key) => fixture.users[key]?.id);
  addFailure(
    failures,
    writeRoomIds.every((id) => !readRoomIds.includes(id)),
    'mixed write/read fixture가 같은 DB ROOM ID를 공유합니다.',
  );
  addFailure(
    failures,
    writeUserIds.every((id) => !readUserIds.includes(id)),
    'mixed write/read fixture가 같은 DB 사용자 ID를 공유합니다.',
  );
  for (const target of fixture.targets) {
    const expectedPartition = target.operation === 't5' ? 'read' : 'write';
    const roomKeys = expectedPartition === 'read' ? readRoomKeys : writeRoomKeys;
    const userKeys = expectedPartition === 'read' ? readUserKeys : writeUserKeys;
    addFailure(
      failures,
      MIXED_OPERATIONS.includes(target.operation)
        && target.fixture === expectedPartition
        && roomKeys.has(target.roomKey)
        && userKeys.has(target.actorKey)
        && Number.isInteger(fixture.rooms[target.roomKey]?.id)
        && Number.isInteger(fixture.users[target.actorKey]?.id),
      `mixed ${target.operation} target의 fixture partition이 다릅니다.`,
    );
  }
  return { invalid: false, valid: failures.length === failureStart };
}

function evaluateMixedT1(fixture, snapshot, failures, aggregate) {
  let canceledCount = 0;
  let promotedCount = 0;
  for (const room of mixedRooms(fixture, 't1')) {
    const canceled = room.cancelKeys.filter(
      (key) => participationStatus(snapshot, room.id, fixture.users[key].id) === 'CANCELED',
    );
    const promoted = room.waiterKeys.filter(
      (key) => waitlistStatus(snapshot, room.id, fixture.users[key].id) === 'PROMOTED',
    );
    canceledCount += canceled.length;
    promotedCount += promoted.length;
    addFailure(failures, promoted.length === canceled.length, `ROOM ${room.id}: mixed T1 취소·승격 수가 다릅니다.`);
    room.waiterKeys.forEach((key, index) => {
      const userId = fixture.users[key].id;
      const promotedExpected = index < canceled.length;
      addFailure(
        failures,
        promotedExpected
          ? waitlistStatus(snapshot, room.id, userId) === 'PROMOTED'
            && participationStatus(snapshot, room.id, userId) === 'ACTIVE'
          : waitlistStatus(snapshot, room.id, userId) === 'WAITING'
            && participationStatus(snapshot, room.id, userId) === null,
        `ROOM ${room.id}: mixed T1 FIFO 승격 상태가 기대와 다릅니다.`,
      );
    });
    const current = roomSnapshot(snapshot, room.id);
    addFailure(failures, current?.status === 'CLOSED', `ROOM ${room.id}: mixed T1 뒤 CLOSED가 아닙니다.`);
    addFailure(failures, current?.activeParticipantCount === room.capacity, `ROOM ${room.id}: mixed T1 정원이 유지되지 않았습니다.`);
  }
  const successCount = mixedOutcomeCount(aggregate, 't1', 'success');
  if (successCount !== null) {
    addFailure(failures, successCount === canceledCount, 'mixed T1 성공 수와 CANCELED 행 수가 다릅니다.');
  }
  addFailure(failures, promotedCount === canceledCount, 'mixed T1 전체 PROMOTED 수와 CANCELED 수가 다릅니다.');
}

function evaluateMixedT2(fixture, snapshot, failures, aggregate) {
  const targets = fixture.targets.filter((target) => target.operation === 't2');
  const waitingByActor = targets.filter((target) => (
    waitlistStatus(snapshot, fixture.rooms[target.roomKey].id, fixture.users[target.actorKey].id) === 'WAITING'
  ));
  for (const room of mixedRooms(fixture, 't2')) {
    const current = roomSnapshot(snapshot, room.id);
    addFailure(failures, current?.status === 'CLOSED', `ROOM ${room.id}: mixed T2 뒤 CLOSED가 아닙니다.`);
    addFailure(failures, current?.activeParticipantCount === room.capacity, `ROOM ${room.id}: mixed T2 ACTIVE 수가 변했습니다.`);
  }
  for (const target of targets) {
    const room = fixture.rooms[target.roomKey];
    addFailure(
      failures,
      participationStatus(snapshot, room.id, fixture.users[target.actorKey].id) === null,
      `ROOM ${room.id}: mixed T2 대기 신청자가 ACTIVE가 되었습니다.`,
    );
  }
  const successCount = mixedOutcomeCount(aggregate, 't2', 'success');
  if (successCount !== null) {
    addFailure(failures, successCount === waitingByActor.length, 'mixed T2 201 성공 수와 WAITING 행 수가 다릅니다.');
  }
}

function evaluateMixedT5(fixture, snapshot, failures, aggregate) {
  const read = mixedPartition(fixture, 'read');
  if (!read || !fixture.baselineSnapshot) {
    addFailure(failures, false, 'mixed T5 read fixture의 baseline snapshot이 없습니다.');
    return;
  }
  const currentReadSnapshot = snapshotForRoomKeys(snapshot, fixture, read.roomKeys);
  const baselineReadSnapshot = snapshotForRoomKeys(fixture.baselineSnapshot, fixture, read.roomKeys);
  addFailure(
    failures,
    JSON.stringify(currentReadSnapshot) === JSON.stringify(baselineReadSnapshot),
    'mixed T5 상세 조회 전후 read fixture snapshot이 달라졌습니다.',
  );
  const requestCount = MIXED_TIERS.reduce(
    (total, tier) => total + MIXED_OUTCOMES.reduce(
      (outcomeTotal, outcome) => outcomeTotal + (aggregate?.tiers?.[tier]?.t5?.[outcome]?.count || 0),
      0,
    ),
    0,
  );
  const successCount = mixedOutcomeCount(aggregate, 't5', 'success');
  if (successCount !== null) {
    addFailure(failures, successCount === requestCount, 'mixed T5 조회 중 성공으로 분류되지 않은 응답이 있습니다.');
  }
}

function evaluateMixed(fixture, snapshot, failures, summary) {
  const partitions = evaluateMixedPartitions(fixture, snapshot, failures);
  if (partitions.invalid) {
    return { invalid: true };
  }
  const aggregate = summary ? buildMixedAggregate(summary, fixture.options) : null;
  if (aggregate?.status === 'INVALID') {
    aggregate.invalidReasons.forEach((reason) => {
      addFailure(failures, false, `mixed aggregate: ${reason}`);
    });
  }
  if (aggregate?.status === 'FAIL') {
    aggregate.failureReasons.forEach((reason) => {
      addFailure(failures, false, `mixed aggregate: ${reason}`);
    });
  }
  evaluateMixedT1(fixture, snapshot, failures, aggregate);
  evaluateMixedT2(fixture, snapshot, failures, aggregate);
  evaluateMixedT5(fixture, snapshot, failures, aggregate);
  return { invalid: aggregate?.status === 'INVALID' };
}

function expectedRequestCount(fixture) {
  return fixture.options.scenario === 't3'
    ? fixture.targets.length * 2
    : fixture.targets.length;
}

function evaluateMeasuredRequests(fixture, failures, summary) {
  if (!summary) {
    return;
  }

  const requestCount = metricCount(summary, 'room_requests');
  const successCount = metricCount(summary, 'room_success');
  const businessFailureCount = metricCount(summary, 'room_business_failures');
  const concurrentFailureCount = metricCount(summary, 'room_concurrent_failures');
  const outcomeCounts = ROOM_OUTCOME_CATEGORIES.map((category) => (
    metricCount(summary, roomRequestDurationMetricName(category))
  ));
  const counts = [requestCount, successCount, businessFailureCount, concurrentFailureCount, ...outcomeCounts];
  addFailure(
    failures,
    counts.every((count) => count !== null),
    'k6 요청·기존 outcome·outcome별 duration metric이 부족합니다.',
  );
  if (counts.some((count) => count === null)) {
    return;
  }

  const classifiedCount = outcomeCounts.reduce((total, count) => total + count, 0);
  addFailure(
    failures,
    classifiedCount === requestCount,
    'k6 outcome별 count 합과 실제 ROOM 요청 수가 다릅니다.',
  );

  const expectedOutcomeCounts = [successCount, businessFailureCount, concurrentFailureCount];
  for (const [index, category] of ['success', 'business', 'concurrency'].entries()) {
    addFailure(
      failures,
      outcomeCounts[index] === expectedOutcomeCounts[index],
      `${category} outcome count와 기존 counter가 다릅니다.`,
    );
  }

  for (const [index, category] of ROOM_OUTCOME_CATEGORIES.entries()) {
    const count = outcomeCounts[index];
    const values = metricValues(summary, roomRequestDurationMetricName(category));
    const statistics = ['p50', 'p95', 'p99', 'max'];
    const hasFiniteStatistics = statistics.every((name) => Number.isFinite(values?.[name]));
    if (count === 0) {
      addFailure(
        failures,
        statistics.every((name) => values?.[name] === null),
        `${category} outcome 무표본 통계는 null이어야 합니다.`,
      );
    } else {
      addFailure(
        failures,
        hasFiniteStatistics,
        `${category} outcome 표본의 p50·p95·p99·max가 부족합니다.`,
      );
    }
  }

  if (fixture.options.scenario === 't5') {
    addFailure(failures, requestCount > 0, 'T5 측정 요청이 한 건도 없습니다.');
    addFailure(failures, successCount === requestCount, 'T5 측정 요청 중 성공으로 분류되지 않은 응답이 있습니다.');
    return;
  }

  if (fixture.options.scenario === 'mixed') {
    return;
  }

  const expected = expectedRequestCount(fixture);
  addFailure(
    failures,
    requestCount === expected,
    `기대 ROOM 요청 수 ${expected}와 실제 관측 수 ${requestCount}가 다릅니다.`,
  );
}

export function evaluateFixture(fixture, snapshot, stage, summary = null) {
  const failures = [];
  let invalidAfterContract = false;
  evaluateCommonInvariants(fixture, snapshot, failures);

  if (stage === 'before') {
    if (fixture.options.scenario === 'mixed') {
      evaluateMixedPartitions(fixture, snapshot, failures);
    }
    for (const room of Object.values(fixture.rooms)) {
      const current = roomSnapshot(snapshot, room.id);
      addFailure(failures, current?.status === room.status, `ROOM ${room.id}: 사전 status가 fixture와 다릅니다.`);
      addFailure(failures, current?.activeParticipantCount === room.activeKeys.length, `ROOM ${room.id}: 사전 ACTIVE 수가 fixture와 다릅니다.`);
    }
    return { status: failures.length === 0 ? 'PASS' : 'INVALID', failures };
  }

  const normalizedSummary = summary ? normalizeRoomSummary(summary) : null;
  const contractFailures = metricCount(normalizedSummary, 'room_contract_failures');
  const unexpected4xx = metricCount(normalizedSummary, 'room_unexpected_4xx');
  const serverFailures = metricCount(normalizedSummary, 'room_server_failures');
  if (normalizedSummary) {
    addFailure(failures, contractFailures === 0, 'k6 response contract failure가 있습니다.');
    addFailure(failures, unexpected4xx === 0, '예상 밖 4xx가 있습니다.');
    addFailure(failures, serverFailures === 0, '5xx가 있습니다.');
    evaluateMeasuredRequests(fixture, failures, normalizedSummary);
  }

  switch (fixture.options.scenario) {
    case 't1':
      evaluateT1(fixture, snapshot, failures, normalizedSummary);
      break;
    case 't2':
      evaluateT2(fixture, snapshot, failures, normalizedSummary);
      break;
    case 't3':
      evaluateT3(fixture, snapshot, failures);
      break;
    case 't4':
      evaluateT4(fixture, snapshot, failures, normalizedSummary);
      break;
    case 't5':
      evaluateT5(fixture, snapshot, fixture.baselineSnapshot, failures);
      break;
    case 'mixed':
      invalidAfterContract = evaluateMixed(fixture, snapshot, failures, normalizedSummary).invalid;
      break;
    default:
      fail(`지원하지 않는 scenario: ${fixture.options.scenario}`);
  }

  return { status: invalidAfterContract ? 'INVALID' : failures.length === 0 ? 'PASS' : 'FAIL', failures };
}
