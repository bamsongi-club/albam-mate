import { createHash, randomBytes } from 'node:crypto';
import {
  chmodSync,
  cpSync,
  existsSync,
  mkdirSync,
  readFileSync,
  rmSync,
  writeFileSync,
} from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const TOOL_DIRECTORY = dirname(fileURLToPath(import.meta.url));
const REPOSITORY_ROOT = resolve(TOOL_DIRECTORY, '../../../..');
const SCRIPT_DIRECTORY = resolve(REPOSITORY_ROOT, 'load-tests/k6/room');
const BUNDLE_MARKER = '.room-k6-fixture-bundle';
const DEFAULT_LEVELS = [2, 4, 8];
const DEFAULT_MODES = ['hot', 'spread'];
const ROOM_09_WAITERS_PER_CLOSED_DUE_ROOM = 10;
const DURATION_PATTERN = /^[1-9]\d*(ms|s|m|h)$/;
const SCENARIO_SCRIPTS = {
  'cancel-promotion': '01-room-cancel-promotion.js',
  'waitlist-registration': '02-room-waitlist-registration.js',
  'due-backlog-read': '03-room-read-due-backlog.js',
  'room-detail': '04-room-detail-by-role.js',
  'waitlist-position': '05-room-waitlist-position.js',
};

function assert(condition, message) {
  if (!condition) {
    throw new Error(message);
  }
}

function parseInteger(value, name, minimum, maximum) {
  const parsed = Number(value);
  assert(Number.isInteger(parsed), `${name}은 정수여야 합니다: ${value}`);
  assert(parsed >= minimum && parsed <= maximum,
    `${name}은 ${minimum} 이상 ${maximum} 이하여야 합니다: ${value}`);
  return parsed;
}

function parseIntegerList(value, name, minimum, maximum) {
  const values = String(value).split(',').map((item) => parseInteger(item.trim(), name, minimum, maximum));
  assert(new Set(values).size === values.length, `${name}에 중복 값이 있습니다.`);
  return values;
}

function parseChoice(value, name, choices) {
  assert(choices.includes(value), `${name}은 ${choices.join(', ')} 중 하나여야 합니다: ${value}`);
  return value;
}

export function parseArguments(argv) {
  const [scenario, ...tokens] = argv;
  assert(SCENARIO_SCRIPTS[scenario], `지원하지 않는 scenario입니다: ${scenario || '<없음>'}`);

  const raw = {};
  for (let index = 0; index < tokens.length; index += 1) {
    const token = tokens[index];
    assert(token.startsWith('--'), `알 수 없는 인자입니다: ${token}`);
    const name = token.slice(2);
    const value = tokens[index + 1];
    assert(value !== undefined && !value.startsWith('--'), `${token} 값이 필요합니다.`);
    assert(raw[name] === undefined, `${token}이 중복되었습니다.`);
    raw[name] = value;
    index += 1;
  }

  const common = {
    scenario,
    output: raw.output || resolve(REPOSITORY_ROOT, `build/k6/room/${scenario}`),
    seed: raw.seed || `${scenario}-${new Date().toISOString()}`,
  };

  const allowedByScenario = {
    'cancel-promotion': ['output', 'seed', 'levels', 'modes', 'warmup-waves', 'measured-waves',
      'wave-interval-seconds', 'start-delay-seconds'],
    'waitlist-registration': ['output', 'seed', 'levels', 'modes', 'warmup-waves', 'measured-waves',
      'wave-interval-seconds', 'start-delay-seconds'],
    'due-backlog-read': ['output', 'seed', 'endpoint', 'due-room-count', 'vus',
      'start-delay-seconds'],
    'room-detail': ['output', 'seed', 'role', 'active-participant-count', 'vus', 'duration',
      'think-time-seconds'],
    'waitlist-position': ['output', 'seed', 'queue-length', 'position', 'vus', 'duration',
      'think-time-seconds'],
  };
  for (const name of Object.keys(raw)) {
    assert(allowedByScenario[scenario].includes(name), `${scenario}에서 지원하지 않는 옵션입니다: --${name}`);
  }

  if (scenario === 'cancel-promotion' || scenario === 'waitlist-registration') {
    const levels = parseIntegerList(raw.levels || DEFAULT_LEVELS.join(','), 'levels', 1,
      scenario === 'cancel-promotion' ? 10 : 32);
    const modes = String(raw.modes || DEFAULT_MODES.join(','))
      .split(',')
      .map((mode) => parseChoice(mode.trim(), 'modes', DEFAULT_MODES));
    assert(new Set(modes).size === modes.length, 'modes에 중복 값이 있습니다.');
    const maximumLoginAttempts = levels.reduce((sum, level) => sum + level, 0) * modes.length;
    assert(maximumLoginAttempts <= 30,
      `한 bundle의 최대 로그인 수(${maximumLoginAttempts})가 기본 10분 제한(30)을 넘습니다. levels 또는 modes를 나눠 실행하세요.`);
    return {
      ...common,
      levels,
      modes,
      warmupWaves: parseInteger(raw['warmup-waves'] || 1, 'warmup-waves', 0, 10),
      measuredWaves: parseInteger(raw['measured-waves'] || 10, 'measured-waves', 1, 100),
      waveIntervalSeconds: parseInteger(raw['wave-interval-seconds'] || 3,
        'wave-interval-seconds', 1, 60),
      startDelaySeconds: parseInteger(raw['start-delay-seconds'] || 10,
        'start-delay-seconds', 1, 60),
    };
  }

  if (scenario === 'due-backlog-read') {
    return {
      ...common,
      endpoint: parseChoice(raw.endpoint || 'room-list', 'endpoint', ['room-list', 'my-rooms']),
      dueRoomCount: parseInteger(raw['due-room-count'] || 20, 'due-room-count', 1, 50_000),
      vus: parseInteger(raw.vus || 2, 'vus', 1, 32),
      startDelaySeconds: parseInteger(raw['start-delay-seconds'] || 10,
        'start-delay-seconds', 1, 60),
    };
  }

  if (scenario === 'room-detail') {
    const duration = raw.duration || '1m';
    assert(DURATION_PATTERN.test(duration), `duration 형식이 올바르지 않습니다: ${duration}`);
    return {
      ...common,
      role: parseChoice(raw.role || 'public', 'role', ['public', 'host', 'participant']),
      activeParticipantCount: parseInteger(raw['active-participant-count'] || 1,
        'active-participant-count', 1, 10),
      vus: parseInteger(raw.vus || 10, 'vus', 1, 100),
      duration,
      thinkTimeSeconds: parseInteger(raw['think-time-seconds'] || 1, 'think-time-seconds', 0, 60),
    };
  }

  const duration = raw.duration || '1m';
  assert(DURATION_PATTERN.test(duration), `duration 형식이 올바르지 않습니다: ${duration}`);
  return {
    ...common,
    queueLength: parseInteger(raw['queue-length'] || 10, 'queue-length', 1, 10_000),
    position: parseChoice(raw.position || 'head', 'position', ['head', 'tail']),
    vus: parseInteger(raw.vus || 10, 'vus', 1, 100),
    duration,
    thinkTimeSeconds: parseInteger(raw['think-time-seconds'] || 1, 'think-time-seconds', 0, 60),
  };
}

function shortHash(value) {
  return createHash('sha256').update(value).digest('hex').slice(0, 12);
}

function idBase(seed) {
  const prefix = createHash('sha256').update(seed).digest('hex').slice(0, 8);
  return 1_000_000_000_000 + Number.parseInt(prefix, 16) * 1_000_000;
}

function randomPassword() {
  return `K6!${randomBytes(18).toString('base64url')}`;
}

function createModel(options) {
  const fixtureId = shortHash(options.seed);
  let nextId = idBase(options.seed);
  let nextQueueOrder = idBase(`${options.seed}-queue`);
  const usersByKey = new Map();
  const rooms = [];
  const participations = [];
  const waitlists = [];
  const chatRooms = [];

  function user(key) {
    if (!usersByKey.has(key)) {
      const index = usersByKey.size + 1;
      usersByKey.set(key, {
        key,
        id: nextId,
        email: `room-k6-${fixtureId}-${index}@example.invalid`,
        nickname: `room-k6-${index}`,
      });
      nextId += 1;
    }
    return usersByKey.get(key);
  }

  function room({ key, hostKey, capacity, activeParticipantCount, status, phase, startAtSql }) {
    const host = user(hostKey);
    const created = {
      key,
      id: nextId,
      hostUserId: host.id,
      capacity,
      activeParticipantCount,
      status,
      phase,
      title: `ROOM-K6:${fixtureId}:${phase}:${key}`.slice(0, 100),
      startAtSql: startAtSql || "CURRENT_TIMESTAMP + INTERVAL '7 days'",
    };
    nextId += 1;
    rooms.push(created);
    chatRooms.push({ id: nextId, roomId: created.id });
    nextId += 1;
    return created;
  }

  function participation(roomValue, userKey, status = 'ACTIVE') {
    const participant = user(userKey);
    const created = {
      id: nextId,
      roomId: roomValue.id,
      userId: participant.id,
      status,
    };
    nextId += 1;
    participations.push(created);
    return created;
  }

  function waitlist(roomValue, userKey) {
    const waitingUser = user(userKey);
    const created = {
      roomId: roomValue.id,
      userId: waitingUser.id,
      queueOrder: nextQueueOrder,
    };
    nextQueueOrder += 1;
    waitlists.push(created);
    return created;
  }

  return {
    fixtureId,
    usersByKey,
    rooms,
    participations,
    waitlists,
    chatRooms,
    user,
    room,
    participation,
    waitlist,
  };
}

function buildWaveFixture(options, model) {
  const waveCount = options.warmupWaves + options.measuredWaves;
  const configurations = [];
  const loginUserKeys = new Set();
  let startOffsetSeconds = 0;

  for (const level of options.levels) {
    for (const mode of options.modes) {
      const id = `${mode}-${level}`;
      const targets = [];
      for (let wave = 0; wave < waveCount; wave += 1) {
        const phase = wave < options.warmupWaves ? 'warmup' : 'measure';
        const waveTargets = [];
        if (mode === 'hot') {
          const currentRoom = model.room({
            key: `${id}-w${wave}`,
            hostKey: 'host',
            capacity: options.scenario === 'cancel-promotion' ? level : 1,
            activeParticipantCount: options.scenario === 'cancel-promotion' ? level : 1,
            status: 'CLOSED',
            phase,
          });
          seedWaveRoom(options, model, currentRoom, level, id, wave);
          for (let actor = 0; actor < level; actor += 1) {
            const userKey = `actor-${actor}`;
            loginUserKeys.add(userKey);
            waveTargets.push({ roomId: currentRoom.id, userKey });
          }
        } else {
          for (let actor = 0; actor < level; actor += 1) {
            const currentRoom = model.room({
              key: `${id}-w${wave}-a${actor}`,
              hostKey: 'host',
              capacity: 1,
              activeParticipantCount: 1,
              status: 'CLOSED',
              phase,
            });
            seedWaveRoom(options, model, currentRoom, 1, `${id}-a${actor}`, wave, actor);
            const userKey = `actor-${actor}`;
            loginUserKeys.add(userKey);
            waveTargets.push({ roomId: currentRoom.id, userKey });
          }
        }
        targets.push(waveTargets);
      }

      configurations.push({
        id,
        mode,
        vus: level,
        startOffsetSeconds,
        startDelaySeconds: options.startDelaySeconds,
        waveIntervalSeconds: options.waveIntervalSeconds,
        warmupWaves: options.warmupWaves,
        measuredWaves: options.measuredWaves,
        waveCount,
        targets,
      });
      startOffsetSeconds += options.startDelaySeconds
        + waveCount * options.waveIntervalSeconds
        + 5;
    }
  }

  return {
    schemaVersion: 1,
    scenario: options.scenario,
    fixtureId: model.fixtureId,
    globalStartDelaySeconds: 0,
    loginUserKeys: [...loginUserKeys],
    configurations,
  };
}

function seedWaveRoom(options, model, currentRoom, actorCount, label, wave, actorOffset = 0) {
  if (options.scenario === 'cancel-promotion') {
    for (let actor = 0; actor < actorCount; actor += 1) {
      const actorKey = `actor-${actorOffset + actor}`;
      model.participation(currentRoom, actorKey);
    }
    const waiterCount = actorCount === 1 && options.modes.includes('spread') ? 2 : actorCount + 1;
    for (let waiter = 0; waiter < waiterCount; waiter += 1) {
      model.waitlist(currentRoom, `waiter-${label}-w${wave}-${waiter}`);
    }
    return;
  }

  model.participation(currentRoom, `occupant-${label}-w${wave}`);
}

function buildDueBacklogFixture(options, model) {
  const reader = model.user('reader');
  let recruitingDueRoomCount = 0;
  let closedDueRoomCount = 0;
  for (let index = 0; index < options.dueRoomCount; index += 1) {
    const recruiting = index % 2 === 0;
    const currentRoom = model.room({
      key: `due-${index}`,
      hostKey: 'reader',
      capacity: 10,
      activeParticipantCount: 0,
      status: recruiting ? 'RECRUITING' : 'CLOSED',
      phase: 'measure',
      startAtSql: recruiting
        ? "CURRENT_TIMESTAMP - INTERVAL '1 minute'"
        : "CURRENT_TIMESTAMP - INTERVAL '25 hours'",
    });
    if (recruiting) {
      recruitingDueRoomCount += 1;
      continue;
    }

    closedDueRoomCount += 1;
    for (let waitingIndex = 0;
      waitingIndex < ROOM_09_WAITERS_PER_CLOSED_DUE_ROOM;
      waitingIndex += 1) {
      model.waitlist(currentRoom, `due-waiter-${index}-${waitingIndex}`);
    }
  }
  for (let index = 0; index < 10; index += 1) {
    model.room({
      key: `control-${index}`,
      hostKey: 'reader',
      capacity: 10,
      activeParticipantCount: 0,
      status: 'RECRUITING',
      phase: 'control',
    });
  }

  const userKey = options.endpoint === 'my-rooms' ? 'reader' : null;
  return {
    schemaVersion: 1,
    scenario: options.scenario,
    fixtureId: model.fixtureId,
    globalStartDelaySeconds: options.startDelaySeconds,
    loginUserKeys: userKey ? [userKey] : [],
    configuration: {
      endpoint: options.endpoint,
      dueRoomCount: options.dueRoomCount,
      recruitingDueRoomCount,
      closedDueRoomCount,
      waitingPerClosedDueRoom: ROOM_09_WAITERS_PER_CLOSED_DUE_ROOM,
      expectedExpiredWaitlistCount:
        closedDueRoomCount * ROOM_09_WAITERS_PER_CLOSED_DUE_ROOM,
      vus: options.vus,
      userKey,
      readerUserId: reader.id,
    },
  };
}

function buildRoomDetailFixture(options, model) {
  const currentRoom = model.room({
    key: `detail-${options.role}-${options.activeParticipantCount}`,
    hostKey: 'host',
    capacity: 10,
    activeParticipantCount: options.activeParticipantCount,
    status: options.activeParticipantCount === 10 ? 'CLOSED' : 'RECRUITING',
    phase: 'measure',
  });
  for (let index = 0; index < options.activeParticipantCount; index += 1) {
    model.participation(currentRoom, `participant-${index}`);
  }

  const userKey = options.role === 'public'
    ? null
    : options.role === 'host' ? 'host' : 'participant-0';
  return {
    schemaVersion: 1,
    scenario: options.scenario,
    fixtureId: model.fixtureId,
    loginUserKeys: userKey ? [userKey] : [],
    configuration: {
      role: options.role,
      activeParticipantCount: options.activeParticipantCount,
      vus: options.vus,
      duration: options.duration,
      thinkTimeSeconds: options.thinkTimeSeconds,
      roomId: currentRoom.id,
      userKey,
    },
  };
}

function buildWaitlistPositionFixture(options, model) {
  const currentRoom = model.room({
    key: `position-${options.queueLength}-${options.position}`,
    hostKey: 'host',
    capacity: 1,
    activeParticipantCount: 1,
    status: 'CLOSED',
    phase: 'measure',
  });
  model.participation(currentRoom, 'occupant');
  const waiters = [];
  for (let index = 0; index < options.queueLength; index += 1) {
    waiters.push(model.waitlist(currentRoom, `waiter-${index}`));
  }
  const targetIndex = options.position === 'head' ? 0 : waiters.length - 1;
  const userKey = `waiter-${targetIndex}`;

  return {
    schemaVersion: 1,
    scenario: options.scenario,
    fixtureId: model.fixtureId,
    loginUserKeys: [userKey],
    configuration: {
      queueLength: options.queueLength,
      position: options.position,
      expectedPosition: targetIndex + 1,
      vus: options.vus,
      duration: options.duration,
      thinkTimeSeconds: options.thinkTimeSeconds,
      roomId: currentRoom.id,
      userKey,
    },
  };
}

export function buildFixture(options) {
  const model = createModel(options);
  let manifest;
  if (options.scenario === 'cancel-promotion' || options.scenario === 'waitlist-registration') {
    manifest = buildWaveFixture(options, model);
  } else if (options.scenario === 'due-backlog-read') {
    manifest = buildDueBacklogFixture(options, model);
  } else if (options.scenario === 'room-detail') {
    manifest = buildRoomDetailFixture(options, model);
  } else {
    manifest = buildWaitlistPositionFixture(options, model);
  }

  const password = options.passwordOverride || randomPassword();
  const codePoints = [...password].length;
  const utf8Bytes = Buffer.byteLength(password, 'utf8');
  assert(codePoints >= 1 && codePoints <= 64 && utf8Bytes <= 72,
    'fixture password는 1~64 code point, UTF-8 72 byte 이하여야 합니다.');
  const credentials = {};
  for (const userKey of manifest.loginUserKeys) {
    const currentUser = model.user(userKey);
    credentials[userKey] = { email: currentUser.email, password };
  }

  return {
    model,
    manifest,
    users: { schemaVersion: 1, fixtureId: model.fixtureId, credentials },
    password,
  };
}

function sqlString(value) {
  return `'${String(value).replaceAll("'", "''")}'`;
}

function sqlBoolean(value) {
  return value ? 'TRUE' : 'FALSE';
}

function insertStatement(table, columns, rows) {
  if (rows.length === 0) {
    return `-- ${table}: 삽입할 fixture가 없습니다.`;
  }
  return `INSERT INTO ${table} (${columns.join(', ')})\nVALUES\n${rows.map((row) => `    (${row.join(', ')})`).join(',\n')};`;
}

function cleanupSql() {
  return `CREATE TEMP TABLE room_k6_old_rooms ON COMMIT DROP AS
SELECT id FROM rooms WHERE title LIKE 'ROOM-K6:%';

CREATE TEMP TABLE room_k6_old_users ON COMMIT DROP AS
SELECT id FROM users WHERE email LIKE 'room-k6-%@example.invalid';

DELETE FROM notifications
WHERE room_id IN (SELECT id FROM room_k6_old_rooms)
   OR recipient_user_id IN (SELECT id FROM room_k6_old_users);

DELETE FROM notification_outbox_recipients
WHERE outbox_event_id IN (
        SELECT id FROM notification_outbox_events
        WHERE room_id IN (SELECT id FROM room_k6_old_rooms)
    )
   OR recipient_user_id IN (SELECT id FROM room_k6_old_users);

DELETE FROM notification_outbox_events
WHERE room_id IN (SELECT id FROM room_k6_old_rooms);

DELETE FROM chat_messages
WHERE chat_room_id IN (
        SELECT id FROM chat_rooms
        WHERE room_id IN (SELECT id FROM room_k6_old_rooms)
    )
   OR sender_user_id IN (SELECT id FROM room_k6_old_users);

DELETE FROM chat_rooms WHERE room_id IN (SELECT id FROM room_k6_old_rooms);
DELETE FROM room_waitlists
WHERE room_id IN (SELECT id FROM room_k6_old_rooms)
   OR user_id IN (SELECT id FROM room_k6_old_users);
DELETE FROM participations
WHERE room_id IN (SELECT id FROM room_k6_old_rooms)
   OR user_id IN (SELECT id FROM room_k6_old_users);
DELETE FROM rooms WHERE id IN (SELECT id FROM room_k6_old_rooms);
DELETE FROM social_accounts WHERE user_id IN (SELECT id FROM room_k6_old_users);
DELETE FROM user_played_games WHERE user_id IN (SELECT id FROM room_k6_old_users);
DELETE FROM users WHERE id IN (SELECT id FROM room_k6_old_users);`;
}

function renderPrepareAssertions(fixture) {
  if (fixture.manifest.scenario !== 'due-backlog-read') {
    return '';
  }

  const prefix = `ROOM-K6:${fixture.model.fixtureId}:measure:%`;
  const configuration = fixture.manifest.configuration;
  return `DO $$
DECLARE
    due_room_count BIGINT;
    waiting_count BIGINT;
BEGIN
    SELECT count(*) INTO due_room_count
    FROM rooms
    WHERE title LIKE ${sqlString(prefix)}
      AND (
          (status = 'RECRUITING' AND start_at <= CURRENT_TIMESTAMP)
          OR (status = 'CLOSED' AND start_at <= CURRENT_TIMESTAMP - INTERVAL '24 hours')
      );
    IF due_room_count <> ${configuration.dueRoomCount} THEN
        RAISE EXCEPTION 'ROOM_K6_PREPARE_DUE_COUNT_MISMATCH expected=%, actual=%',
            ${configuration.dueRoomCount}, due_room_count;
    END IF;

    SELECT count(*) INTO waiting_count
    FROM room_waitlists waitlist
    JOIN rooms room ON room.id = waitlist.room_id
    WHERE room.title LIKE ${sqlString(prefix)}
      AND room.status = 'CLOSED'
      AND waitlist.status = 'WAITING';
    IF waiting_count <> ${configuration.expectedExpiredWaitlistCount} THEN
        RAISE EXCEPTION 'ROOM_K6_PREPARE_WAITING_COUNT_MISMATCH expected=%, actual=%',
            ${configuration.expectedExpiredWaitlistCount}, waiting_count;
    END IF;
END
$$;`;
}

export function renderPrepareSql(fixture) {
  const { model, password } = fixture;
  const users = [...model.usersByKey.values()];
  const userRows = users.map((user) => [
    user.id,
    sqlString(user.email),
    '(SELECT password_hash FROM room_k6_password)',
    sqlString(user.nickname),
    'CURRENT_TIMESTAMP',
    'CURRENT_TIMESTAMP',
  ]);
  const roomRows = model.rooms.map((room) => [
    room.id,
    'NULL',
    room.hostUserId,
    sqlString('PERSON_FOCUSED'),
    sqlString(room.title),
    sqlString('ROOM k6 fixture'),
    sqlString('ALL_LEVELS'),
    sqlBoolean(false),
    sqlString('홍대'),
    room.capacity,
    room.activeParticipantCount,
    room.startAtSql,
    sqlString('ROOM k6 fixture'),
    sqlString(room.status),
    0,
    'CURRENT_TIMESTAMP',
    'CURRENT_TIMESTAMP',
  ]);
  const participationRows = model.participations.map((participation) => [
    participation.id,
    participation.roomId,
    participation.userId,
    sqlString(participation.status),
    'CURRENT_TIMESTAMP',
    participation.status === 'CANCELED' ? 'CURRENT_TIMESTAMP' : 'NULL',
    'CURRENT_TIMESTAMP',
    'CURRENT_TIMESTAMP',
  ]);
  const waitlistRows = model.waitlists.map((waitlist) => [
    waitlist.roomId,
    waitlist.userId,
    sqlString('WAITING'),
    waitlist.queueOrder,
    'CURRENT_TIMESTAMP',
    'CURRENT_TIMESTAMP',
    'CURRENT_TIMESTAMP',
  ]);
  const chatRoomRows = model.chatRooms.map((chatRoom) => [
    chatRoom.id,
    chatRoom.roomId,
    'NULL',
    'NULL',
    'CURRENT_TIMESTAMP',
    'CURRENT_TIMESTAMP',
  ]);

  return `\\set ON_ERROR_STOP on

BEGIN;
SELECT pg_advisory_xact_lock(hashtext('albam-mate-room-k6-fixture'));
CREATE EXTENSION IF NOT EXISTS pgcrypto;

${cleanupSql()}

CREATE TEMP TABLE room_k6_password ON COMMIT DROP AS
SELECT '{bcrypt}' || crypt(${sqlString(password)}, gen_salt('bf', 10)) AS password_hash;

${insertStatement('users', ['id', 'email', 'password_hash', 'nickname', 'created_at', 'updated_at'], userRows)}

${insertStatement('rooms', [
    'id', 'game_id', 'host_user_id', 'room_type', 'title', 'description', 'experience_level',
    'is_rulemaster_led', 'region', 'capacity', 'active_participant_count', 'start_at', 'place',
    'status', 'version', 'created_at', 'updated_at',
  ], roomRows)}

${insertStatement('participations', [
    'id', 'room_id', 'user_id', 'status', 'joined_at', 'canceled_at', 'created_at', 'updated_at',
  ], participationRows)}

${insertStatement('room_waitlists', [
    'room_id', 'user_id', 'status', 'queue_order', 'queued_at', 'created_at', 'updated_at',
  ], waitlistRows)}

${insertStatement('chat_rooms', [
    'id', 'room_id', 'purge_after', 'messages_purged_at', 'created_at', 'updated_at',
  ], chatRoomRows)}

${renderPrepareAssertions(fixture)}

ANALYZE users;
ANALYZE rooms;
ANALYZE participations;
ANALYZE room_waitlists;
COMMIT;

SELECT json_build_object(
    'fixtureId', ${sqlString(model.fixtureId)},
    'users', ${users.length},
    'rooms', ${model.rooms.length},
    'participations', ${model.participations.length},
    'waitlists', ${model.waitlists.length}
) AS room_k6_fixture_prepared;
`;
}

function verifyPreamble(fixtureId) {
  return `\\set ON_ERROR_STOP on
\\if :{?room_k6_success_count}
\\else
\\set room_k6_success_count 0
\\endif

BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY;
SELECT set_config('room_k6.observed_success_count', :'room_k6_success_count', true);
`;
}

function verifyCommonInvariants(fixtureId) {
  const prefix = `ROOM-K6:${fixtureId}:%`;
  return `DO $$
DECLARE
    invalid_count BIGINT;
BEGIN
    SELECT count(*) INTO invalid_count
    FROM rooms room
    WHERE room.title LIKE ${sqlString(prefix)}
      AND room.active_participant_count <> (
          SELECT count(*)
          FROM participations participation
          WHERE participation.room_id = room.id
            AND participation.status = 'ACTIVE'
      );
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_ACTIVE_COUNT_MISMATCH count=%', invalid_count;
    END IF;

    SELECT count(*) INTO invalid_count
    FROM rooms
    WHERE title LIKE ${sqlString(prefix)}
      AND active_participant_count > capacity;
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_CAPACITY_EXCEEDED count=%', invalid_count;
    END IF;

    SELECT count(*) INTO invalid_count
    FROM (
        SELECT participation.room_id, participation.user_id
        FROM participations participation
        JOIN rooms room ON room.id = participation.room_id
        WHERE room.title LIKE ${sqlString(prefix)}
          AND participation.status = 'ACTIVE'
        GROUP BY participation.room_id, participation.user_id
        HAVING count(*) > 1
    ) duplicate_active;
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_DUPLICATE_ACTIVE_PARTICIPATION count=%', invalid_count;
    END IF;
END
$$;`;
}

function renderCancelPromotionVerify(fixture) {
  const prefix = `ROOM-K6:${fixture.model.fixtureId}:%`;
  const measuredPrefix = `ROOM-K6:${fixture.model.fixtureId}:measure:%`;
  return `${verifyPreamble(fixture.model.fixtureId)}
${verifyCommonInvariants(fixture.model.fixtureId)}

DO $$
DECLARE
    observed_success BIGINT := current_setting('room_k6.observed_success_count')::BIGINT;
    canceled_count BIGINT;
    promoted_count BIGINT;
    invalid_count BIGINT;
BEGIN
    SELECT count(*) INTO canceled_count
    FROM participations participation
    JOIN rooms room ON room.id = participation.room_id
    WHERE room.title LIKE ${sqlString(measuredPrefix)}
      AND participation.status = 'CANCELED';

    SELECT count(*) INTO promoted_count
    FROM room_waitlists waitlist
    JOIN rooms room ON room.id = waitlist.room_id
    WHERE room.title LIKE ${sqlString(measuredPrefix)}
      AND waitlist.status = 'PROMOTED';

    IF canceled_count <> observed_success THEN
        RAISE EXCEPTION 'ROOM_K6_CANCEL_SUCCESS_MISMATCH http_success=%, db_canceled=%',
            observed_success, canceled_count;
    END IF;
    IF promoted_count <> observed_success THEN
        RAISE EXCEPTION 'ROOM_K6_PROMOTION_SUCCESS_MISMATCH http_success=%, db_promoted=%',
            observed_success, promoted_count;
    END IF;

    SELECT count(*) INTO invalid_count
    FROM rooms
    WHERE title LIKE ${sqlString(prefix)}
      AND active_participant_count <> capacity;
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_CANCEL_ROOM_NOT_FULL count=%', invalid_count;
    END IF;

    SELECT count(*) INTO invalid_count
    FROM room_waitlists promoted
    JOIN rooms room ON room.id = promoted.room_id
    JOIN room_waitlists waiting ON waiting.room_id = promoted.room_id
    WHERE room.title LIKE ${sqlString(prefix)}
      AND promoted.status = 'PROMOTED'
      AND waiting.status = 'WAITING'
      AND waiting.queue_order < promoted.queue_order;
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_FIFO_VIOLATION count=%', invalid_count;
    END IF;

    SELECT count(*) INTO invalid_count
    FROM room_waitlists waitlist
    JOIN rooms room ON room.id = waitlist.room_id
    WHERE room.title LIKE ${sqlString(prefix)}
      AND waitlist.status NOT IN ('WAITING', 'PROMOTED');
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_UNEXPECTED_WAITLIST_STATUS count=%', invalid_count;
    END IF;
END
$$;

SELECT json_build_object(
    'fixtureId', ${sqlString(fixture.model.fixtureId)},
    'httpSuccess', current_setting('room_k6.observed_success_count')::BIGINT,
    'dbCanceled', (
        SELECT count(*) FROM participations participation
        JOIN rooms room ON room.id = participation.room_id
        WHERE room.title LIKE ${sqlString(measuredPrefix)} AND participation.status = 'CANCELED'
    ),
    'dbPromoted', (
        SELECT count(*) FROM room_waitlists waitlist
        JOIN rooms room ON room.id = waitlist.room_id
        WHERE room.title LIKE ${sqlString(measuredPrefix)} AND waitlist.status = 'PROMOTED'
    )
) AS room_k6_verification;
COMMIT;
`;
}

function renderWaitlistRegistrationVerify(fixture) {
  const prefix = `ROOM-K6:${fixture.model.fixtureId}:%`;
  const measuredPrefix = `ROOM-K6:${fixture.model.fixtureId}:measure:%`;
  return `${verifyPreamble(fixture.model.fixtureId)}
${verifyCommonInvariants(fixture.model.fixtureId)}

DO $$
DECLARE
    observed_success BIGINT := current_setting('room_k6.observed_success_count')::BIGINT;
    waiting_count BIGINT;
    invalid_count BIGINT;
BEGIN
    SELECT count(*) INTO waiting_count
    FROM room_waitlists waitlist
    JOIN rooms room ON room.id = waitlist.room_id
    WHERE room.title LIKE ${sqlString(measuredPrefix)}
      AND waitlist.status = 'WAITING';
    IF waiting_count <> observed_success THEN
        RAISE EXCEPTION 'ROOM_K6_WAITLIST_SUCCESS_MISMATCH http_success=%, db_waiting=%',
            observed_success, waiting_count;
    END IF;

    SELECT count(*) INTO invalid_count
    FROM room_waitlists waitlist
    JOIN rooms room ON room.id = waitlist.room_id
    WHERE room.title LIKE ${sqlString(prefix)}
      AND waitlist.status <> 'WAITING';
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_UNEXPECTED_WAITLIST_STATUS count=%', invalid_count;
    END IF;

    SELECT count(*) INTO invalid_count
    FROM (
        SELECT waitlist.room_id, waitlist.user_id
        FROM room_waitlists waitlist
        JOIN rooms room ON room.id = waitlist.room_id
        WHERE room.title LIKE ${sqlString(prefix)}
        GROUP BY waitlist.room_id, waitlist.user_id
        HAVING count(*) > 1
    ) duplicate;
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_DUPLICATE_WAITLIST count=%', invalid_count;
    END IF;

    SELECT count(*) INTO invalid_count
    FROM (
        SELECT waitlist.room_id, waitlist.queue_order
        FROM room_waitlists waitlist
        JOIN rooms room ON room.id = waitlist.room_id
        WHERE room.title LIKE ${sqlString(prefix)}
        GROUP BY waitlist.room_id, waitlist.queue_order
        HAVING count(*) > 1
    ) duplicate_order;
    IF invalid_count <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_DUPLICATE_QUEUE_ORDER count=%', invalid_count;
    END IF;
END
$$;

SELECT json_build_object(
    'fixtureId', ${sqlString(fixture.model.fixtureId)},
    'httpSuccess', current_setting('room_k6.observed_success_count')::BIGINT,
    'dbWaiting', (
        SELECT count(*) FROM room_waitlists waitlist
        JOIN rooms room ON room.id = waitlist.room_id
        WHERE room.title LIKE ${sqlString(measuredPrefix)} AND waitlist.status = 'WAITING'
    )
) AS room_k6_verification;
COMMIT;
`;
}

function renderDueBacklogVerify(fixture) {
  const prefix = `ROOM-K6:${fixture.model.fixtureId}:measure:%`;
  const configuration = fixture.manifest.configuration;
  return `${verifyPreamble(fixture.model.fixtureId)}
${verifyCommonInvariants(fixture.model.fixtureId)}

DO $$
DECLARE
    measured_room_count BIGINT;
    remaining_due BIGINT;
    closed_room_count BIGINT;
    finished_room_count BIGINT;
    unexpected_room_status_count BIGINT;
    total_waitlist_count BIGINT;
    expired_waitlist_count BIGINT;
    waiting_count BIGINT;
BEGIN
    SELECT count(*) INTO measured_room_count
    FROM rooms
    WHERE title LIKE ${sqlString(prefix)};
    IF measured_room_count <> ${configuration.dueRoomCount} THEN
        RAISE EXCEPTION 'ROOM_K6_DUE_ROOM_COUNT_CHANGED expected=%, actual=%',
            ${configuration.dueRoomCount}, measured_room_count;
    END IF;

    SELECT count(*) INTO remaining_due
    FROM rooms
    WHERE title LIKE ${sqlString(prefix)}
      AND (
          (status = 'RECRUITING' AND start_at <= CURRENT_TIMESTAMP)
          OR (status = 'CLOSED' AND start_at <= CURRENT_TIMESTAMP - INTERVAL '24 hours')
      );
    IF remaining_due <> 0 THEN
        RAISE EXCEPTION 'ROOM_K6_DUE_BACKLOG_REMAINS count=%', remaining_due;
    END IF;

    SELECT
        count(*) FILTER (WHERE status = 'CLOSED'),
        count(*) FILTER (WHERE status = 'FINISHED'),
        count(*) FILTER (WHERE status NOT IN ('CLOSED', 'FINISHED'))
    INTO closed_room_count, finished_room_count, unexpected_room_status_count
    FROM rooms
    WHERE title LIKE ${sqlString(prefix)};
    IF closed_room_count <> ${configuration.recruitingDueRoomCount}
        OR finished_room_count <> ${configuration.closedDueRoomCount}
        OR unexpected_room_status_count <> 0 THEN
        RAISE EXCEPTION
            'ROOM_K6_DUE_FINAL_STATUS_MISMATCH closed=%/% finished=%/% unexpected=%',
            closed_room_count, ${configuration.recruitingDueRoomCount},
            finished_room_count, ${configuration.closedDueRoomCount},
            unexpected_room_status_count;
    END IF;

    SELECT
        count(*),
        count(*) FILTER (WHERE waitlist.status = 'EXPIRED'),
        count(*) FILTER (WHERE waitlist.status = 'WAITING')
    INTO total_waitlist_count, expired_waitlist_count, waiting_count
    FROM room_waitlists waitlist
    JOIN rooms room ON room.id = waitlist.room_id
    WHERE room.title LIKE ${sqlString(prefix)};
    IF total_waitlist_count <> ${configuration.expectedExpiredWaitlistCount}
        OR expired_waitlist_count <> ${configuration.expectedExpiredWaitlistCount}
        OR waiting_count <> 0 THEN
        RAISE EXCEPTION
            'ROOM_K6_DUE_WAITLIST_STATUS_MISMATCH total=%/% expired=%/% waiting=%',
            total_waitlist_count, ${configuration.expectedExpiredWaitlistCount},
            expired_waitlist_count, ${configuration.expectedExpiredWaitlistCount}, waiting_count;
    END IF;
END
$$;

SELECT json_build_object(
    'fixtureId', ${sqlString(fixture.model.fixtureId)},
    'measuredRooms', (SELECT count(*) FROM rooms WHERE title LIKE ${sqlString(prefix)}),
    'remainingDue', (
        SELECT count(*) FROM rooms
        WHERE title LIKE ${sqlString(prefix)}
          AND ((status = 'RECRUITING' AND start_at <= CURRENT_TIMESTAMP)
            OR (status = 'CLOSED' AND start_at <= CURRENT_TIMESTAMP - INTERVAL '24 hours'))
    ),
    'closedRooms', (
        SELECT count(*) FROM rooms WHERE title LIKE ${sqlString(prefix)} AND status = 'CLOSED'
    ),
    'finishedRooms', (
        SELECT count(*) FROM rooms WHERE title LIKE ${sqlString(prefix)} AND status = 'FINISHED'
    ),
    'expiredWaitlists', (
        SELECT count(*) FROM room_waitlists waitlist
        JOIN rooms room ON room.id = waitlist.room_id
        WHERE room.title LIKE ${sqlString(prefix)} AND waitlist.status = 'EXPIRED'
    )
) AS room_k6_verification;
COMMIT;
`;
}

function renderReadOnlyVerify(fixture) {
  const roomId = fixture.manifest.configuration.roomId;
  const queueLength = fixture.manifest.configuration.queueLength;
  const expectedPosition = fixture.manifest.configuration.expectedPosition;
  const targetUser = fixture.model.user(fixture.manifest.configuration.userKey || 'host');
  const positionAssertions = fixture.manifest.scenario === 'waitlist-position'
    ? `
    SELECT count(*) INTO invalid_count
    FROM room_waitlists
    WHERE room_id = ${roomId} AND status = 'WAITING';
    IF invalid_count <> ${queueLength} THEN
        RAISE EXCEPTION 'ROOM_K6_QUEUE_LENGTH_CHANGED expected=%, actual=%', ${queueLength}, invalid_count;
    END IF;

    SELECT count(*) INTO invalid_count
    FROM room_waitlists
    WHERE room_id = ${roomId}
      AND user_id = ${targetUser.id}
      AND status = 'WAITING';
    IF invalid_count <> 1 THEN
        RAISE EXCEPTION 'ROOM_K6_TARGET_WAITLIST_MISSING room_id=%, user_id=%', ${roomId}, ${targetUser.id};
    END IF;

    SELECT count(*) + 1 INTO actual_position
    FROM room_waitlists preceding
    WHERE preceding.room_id = ${roomId}
      AND preceding.status = 'WAITING'
      AND preceding.queue_order < (
          SELECT target.queue_order
          FROM room_waitlists target
          WHERE target.room_id = ${roomId}
            AND target.user_id = ${targetUser.id}
            AND target.status = 'WAITING'
      );
    IF actual_position <> ${expectedPosition} THEN
        RAISE EXCEPTION 'ROOM_K6_QUEUE_POSITION_CHANGED expected=%, actual=%', ${expectedPosition}, actual_position;
    END IF;`
    : '';
  return `${verifyPreamble(fixture.model.fixtureId)}
${verifyCommonInvariants(fixture.model.fixtureId)}

DO $$
DECLARE
    invalid_count BIGINT;
    actual_position BIGINT;
BEGIN
    SELECT count(*) INTO invalid_count FROM rooms WHERE id = ${roomId};
    IF invalid_count <> 1 THEN
        RAISE EXCEPTION 'ROOM_K6_ROOM_MISSING room_id=%', ${roomId};
    END IF;${positionAssertions}
END
$$;

SELECT json_build_object(
    'fixtureId', ${sqlString(fixture.model.fixtureId)},
    'roomId', ${roomId},
    'activeParticipantCount', (SELECT active_participant_count FROM rooms WHERE id = ${roomId})
) AS room_k6_verification;
COMMIT;
`;
}

export function renderVerifySql(fixture) {
  if (fixture.manifest.scenario === 'cancel-promotion') {
    return renderCancelPromotionVerify(fixture);
  }
  if (fixture.manifest.scenario === 'waitlist-registration') {
    return renderWaitlistRegistrationVerify(fixture);
  }
  if (fixture.manifest.scenario === 'due-backlog-read') {
    return renderDueBacklogVerify(fixture);
  }
  return renderReadOnlyVerify(fixture);
}

function sourceGitSha() {
  try {
    return execFileSync('git', ['rev-parse', 'HEAD'], {
      cwd: REPOSITORY_ROOT,
      encoding: 'utf8',
    }).trim();
  } catch (_) {
    return 'unknown';
  }
}

function sha256File(path) {
  return createHash('sha256').update(readFileSync(path)).digest('hex');
}

function prepareOutputDirectory(output) {
  const resolvedOutput = resolve(output);
  const allowedRoot = resolve(REPOSITORY_ROOT, 'build/k6/room');
  assert(resolvedOutput.startsWith(`${allowedRoot}\\`) || resolvedOutput.startsWith(`${allowedRoot}/`),
    `output은 ${allowedRoot} 아래여야 합니다: ${resolvedOutput}`);

  if (existsSync(resolvedOutput)) {
    const marker = resolve(resolvedOutput, BUNDLE_MARKER);
    assert(existsSync(marker), `기존 디렉터리에 ${BUNDLE_MARKER}가 없어 삭제하지 않습니다: ${resolvedOutput}`);
    rmSync(resolvedOutput, { recursive: true, force: false });
  }
  mkdirSync(resolvedOutput, { recursive: true });
  writeFileSync(resolve(resolvedOutput, BUNDLE_MARKER), 'schemaVersion=1\n', { mode: 0o600 });
  return resolvedOutput;
}

export function writeFixtureBundle(options, fixture) {
  const output = prepareOutputDirectory(options.output);
  const scenarioSource = resolve(SCRIPT_DIRECTORY, SCENARIO_SCRIPTS[options.scenario]);
  const commonSource = resolve(SCRIPT_DIRECTORY, 'common.js');
  assert(existsSync(scenarioSource), `scenario script가 없습니다: ${scenarioSource}`);
  assert(existsSync(commonSource), `common script가 없습니다: ${commonSource}`);

  cpSync(scenarioSource, resolve(output, 'scenario.js'));
  cpSync(commonSource, resolve(output, 'common.js'));
  writeFileSync(resolve(output, 'manifest.json'), `${JSON.stringify(fixture.manifest, null, 2)}\n`);
  writeFileSync(resolve(output, 'users.json'), `${JSON.stringify(fixture.users, null, 2)}\n`, { mode: 0o600 });
  writeFileSync(resolve(output, 'prepare.sql'), renderPrepareSql(fixture), { mode: 0o600 });
  writeFileSync(resolve(output, 'verify.sql'), renderVerifySql(fixture), { mode: 0o600 });
  writeFileSync(resolve(output, 'k6-vars.json'), `${JSON.stringify({
    k6_environment: {
      K6_MANIFEST_FILE: './manifest.json',
      K6_USERS_FILE: './users.json',
    },
  }, null, 2)}\n`);

  const metadata = {
    schemaVersion: 1,
    fixtureId: fixture.model.fixtureId,
    scenario: options.scenario,
    seed: options.seed,
    generatedAt: new Date().toISOString(),
    sourceGitSha: sourceGitSha(),
    sources: {
      scenario: {
        path: `load-tests/k6/room/${SCENARIO_SCRIPTS[options.scenario]}`,
        sha256: sha256File(scenarioSource),
      },
      common: {
        path: 'load-tests/k6/room/common.js',
        sha256: sha256File(commonSource),
      },
    },
    fixtureCounts: {
      users: fixture.model.usersByKey.size,
      rooms: fixture.model.rooms.length,
      participations: fixture.model.participations.length,
      waitlists: fixture.model.waitlists.length,
    },
  };
  writeFileSync(resolve(output, 'source-metadata.json'), `${JSON.stringify(metadata, null, 2)}\n`);

  for (const protectedFile of ['.room-k6-fixture-bundle', 'users.json', 'prepare.sql']) {
    chmodSync(resolve(output, protectedFile), 0o600);
  }
  return { output, metadata };
}

export function repositoryRoot() {
  return REPOSITORY_ROOT;
}
