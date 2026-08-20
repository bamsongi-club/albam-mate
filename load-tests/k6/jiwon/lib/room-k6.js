import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { readExecutionOptions } from './read-execution-options.mjs';
import {
  hasNicknameOnlySet,
  hasNicknameSummary,
  hasParticipationPayload,
  hasT3CancelPayload,
  hasWaitlistPayload,
} from './write-response-contract.mjs';
import { outcomeDurationThresholds, writeOptions } from './write-options.mjs';
import { START_SKEW_THRESHOLD } from './start-skew.mjs';

export { writeOptions };

const RUN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,79}$/;
const FIXTURE_SCHEMA_VERSION = 2;
const PREPARE_OWNERSHIP_PATTERN = /^[0-9a-f]{32}$/;

export const roomRequestDuration = new Trend('room_request_duration', true);
export const roomStartSkewMilliseconds = new Trend('room_start_skew_ms', true);
export const roomRequests = new Counter('room_requests');
export const roomSuccess = new Counter('room_success');
export const roomCreated = new Counter('room_created');
export const roomBusinessFailures = new Counter('room_business_failures');
export const roomConcurrentFailures = new Counter('room_concurrent_failures');
export const roomUnexpected4xx = new Counter('room_unexpected_4xx');
export const roomServerFailures = new Counter('room_server_failures');
export const roomContractFailures = new Counter('room_contract_failures');
const roomWaitlistPositionCounters = Array.from(
  { length: 8 },
  (_, index) => new Counter(`room_waitlist_position_${index + 1}`),
);

function fail(message) {
  throw new Error(message);
}

function requiredEnvironment(name) {
  const value = (__ENV[name] || '').trim();
  if (!value) {
    fail(`${name} 환경 변수가 필요합니다.`);
  }
  return value;
}

function integerEnvironment(name, fallback, minimum, maximum) {
  const raw = (__ENV[name] || '').trim();
  const value = raw ? Number(raw) : fallback;
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    fail(`${name}은(는) ${minimum} 이상 ${maximum} 이하의 정수여야 합니다.`);
  }
  return value;
}

function normalizeTargetUrl(value) {
  return value.replace(/\/+$/, '');
}

function hasOwn(value, property) {
  return Object.prototype.hasOwnProperty.call(value, property);
}

function header(response, name) {
  const expected = name.toLowerCase();
  for (const key of Object.keys(response.headers || {})) {
    if (key.toLowerCase() === expected) {
      return String(response.headers[key]);
    }
  }
  return '';
}

function envelope(response) {
  try {
    const value = response.json();
    if (!value || typeof value !== 'object') {
      return null;
    }
    return value;
  } catch (_) {
    return null;
  }
}

function responseCode(value) {
  return value && typeof value.code === 'string' ? value.code : null;
}

function commonEnvelope(response, value) {
  return value !== null && value.status === response.status;
}

function requestParameters(client, tags, headers = {}) {
  return {
    jar: client.jar,
    headers,
    tags,
  };
}

function requestJson(client, runtime, method, path, body, tags) {
  const headers = { 'Content-Type': 'application/json' };
  if (client.csrf) {
    headers[client.csrf.headerName] = client.csrf.token;
  }
  return http.request(
    method,
    `${runtime.targetUrl}${path}`,
    JSON.stringify(body),
    requestParameters(client, tags, headers),
  );
}

export function requestEmpty(client, runtime, method, path, tags) {
  const headers = {};
  if (client.csrf) {
    headers[client.csrf.headerName] = client.csrf.token;
  }
  return http.request(
    method,
    `${runtime.targetUrl}${path}`,
    null,
    requestParameters(client, tags, headers),
  );
}

export function getRoomDetail(client, runtime, roomId, tags) {
  return http.get(
    `${runtime.targetUrl}/api/rooms/${roomId}`,
    requestParameters(client, tags),
  );
}

function fetchCsrf(client, runtime, tags) {
  const response = http.get(
    `${runtime.targetUrl}/api/auth/csrf`,
    requestParameters(client, tags),
  );
  const value = envelope(response);
  const data = value && value.data;
  if (response.status !== 200 || !commonEnvelope(response, value)
    || !data || typeof data.headerName !== 'string' || typeof data.token !== 'string') {
    return false;
  }
  client.csrf = { headerName: data.headerName, token: data.token };
  return true;
}

function login(client, runtime, account, tags) {
  if (!fetchCsrf(client, runtime, tags)) {
    return false;
  }
  const response = requestJson(client, runtime, 'POST', '/api/auth/login', {
    email: account.email,
    password: runtime.password,
  }, tags);
  const value = envelope(response);
  const sessionId = responseCookie(response, 'JSESSIONID');
  if (response.status !== 200 || !commonEnvelope(response, value) || value.data == null || sessionId === null) {
    return false;
  }
  client.csrf = null;
  if (!fetchCsrf(client, runtime, tags)) {
    return false;
  }
  client.sessionId = sessionId;
  return true;
}

function fixtureFromEnvironment() {
  const fixturePath = requiredEnvironment('ROOM_K6_FIXTURE');
  let fixture;
  try {
    fixture = JSON.parse(open(fixturePath));
  } catch (_) {
    fail(`ROOM_K6_FIXTURE를 읽을 수 없습니다: ${fixturePath}`);
  }
  if (!fixture || fixture.schemaVersion !== FIXTURE_SCHEMA_VERSION
    || typeof fixture.prepareOwnership !== 'string'
    || !PREPARE_OWNERSHIP_PATTERN.test(fixture.prepareOwnership)
    || !fixture.options || !fixture.users || !fixture.rooms) {
    fail('ROOM_K6_FIXTURE 형식이 #649 fixture schemaVersion=2와 다릅니다. 새로 prepare한 fixture를 사용하세요.');
  }
  return fixture;
}

export function loadRuntime(expectedScenario) {
  const fixture = fixtureFromEnvironment();
  if (fixture.options.scenario !== expectedScenario) {
    fail(`fixture scenario=${fixture.options.scenario}는 ${expectedScenario} 스크립트와 맞지 않습니다.`);
  }
  const runId = requiredEnvironment('ALBAM_MATE_RUN_ID');
  if (!RUN_ID_PATTERN.test(runId)) {
    fail('ALBAM_MATE_RUN_ID는 영문 소문자 또는 숫자로 시작하는 80자 이하의 안전한 값이어야 합니다.');
  }
  if (fixture.options.runId !== runId) {
    fail('ALBAM_MATE_RUN_ID와 fixture 생성 runId가 다릅니다. 다른 실행의 fixture를 섞지 마세요.');
  }
  if (!fixture.fixtureId || !fixture.fixtureId.startsWith('room-k6-')) {
    fail('fixtureId가 ROOM k6 fixture 형식이 아닙니다.');
  }
  const readExecution = readExecutionOptions(__ENV);
  return {
    fixture,
    runId,
    targetUrl: normalizeTargetUrl(requiredEnvironment('ALBAM_MATE_TARGET_URL')),
    password: requiredEnvironment('ROOM_K6_FIXTURE_PASSWORD'),
    sessionWarmupSeconds: integerEnvironment('ROOM_K6_SESSION_WARMUP_SECONDS', 15, 5, 120),
    roundIntervalSeconds: integerEnvironment('ROOM_K6_ROUND_INTERVAL_SECONDS', 20, 5, 300),
    readVus: readExecution.vus,
    readDurationSeconds: readExecution.durationSeconds,
    readThinkTimeMilliseconds: readExecution.thinkTimeMilliseconds,
  };
}

export function scenarioTags(runtime, target, extra = {}) {
  const tags = {
    scenario: runtime.fixture.options.scenario,
    profile: runtime.fixture.options.profile,
    fixture_id: runtime.fixture.fixtureId,
    run_id: runtime.runId,
    ...extra,
  };
  if (runtime.fixture.options.mode) {
    tags.mode = runtime.fixture.options.mode;
  }
  if (runtime.fixture.options.subcase) {
    tags.subcase = runtime.fixture.options.subcase;
  }
  if (runtime.fixture.options.t3Mode) {
    tags.t3_mode = runtime.fixture.options.t3Mode;
  }
  if (target && target.role) {
    tags.role = target.role;
  }
  if (target && target.scale) {
    tags.active_scale = String(target.scale);
  }
  return tags;
}

const clientsByUserKey = {};

function responseCookie(response, name) {
  const cookies = response.cookies && response.cookies[name];
  return cookies && cookies.length > 0 ? cookies[cookies.length - 1].value : null;
}

function preparedSessions(runtime) {
  const sessions = {};
  for (const userKey of runtime.fixture.sessionUserKeys) {
    const account = runtime.fixture.users[userKey];
    if (!account) {
      fail(`fixture session user를 찾지 못했습니다: ${userKey}`);
    }
    const client = { jar: new http.CookieJar(), csrf: null, sessionId: null };
    const setupTags = scenarioTags(runtime, null, { phase: 'session-setup', user_key: userKey });
    if (!login(client, runtime, account, setupTags)) {
      fail(`fixture session 준비에 실패했습니다(user=${userKey}). 인증 제한·비밀번호 hash·대상 환경을 확인하세요.`);
    }
    sessions[userKey] = {
      sessionId: client.sessionId,
      csrfHeaderName: client.csrf.headerName,
      csrfToken: client.csrf.token,
    };
  }
  return sessions;
}

export function sessionFor(runtime, sessions, userKey) {
  if (clientsByUserKey[userKey]) {
    return clientsByUserKey[userKey];
  }
  const prepared = sessions && sessions[userKey];
  if (!prepared || !prepared.sessionId || !prepared.csrfHeaderName || !prepared.csrfToken) {
    fail(`준비된 fixture session을 찾지 못했습니다: ${userKey}`);
  }
  const jar = new http.CookieJar();
  jar.set(runtime.targetUrl, 'JSESSIONID', prepared.sessionId);
  jar.set(runtime.targetUrl, 'XSRF-TOKEN', prepared.csrfToken);
  const client = {
    jar,
    csrf: { headerName: prepared.csrfHeaderName, token: prepared.csrfToken },
  };
  clientsByUserKey[userKey] = client;
  return client;
}

export function readOptions(runtime) {
  const maxDuration = runtime.sessionWarmupSeconds + runtime.readDurationSeconds + 30;
  return {
    summaryTrendStats: ['avg', 'min', 'med', 'max', 'p(90)', 'p(95)', 'p(99)', 'count'],
    scenarios: {
      room_read: {
        executor: 'per-vu-iterations',
        vus: runtime.readVus,
        iterations: 1,
        maxDuration: `${maxDuration}s`,
      },
    },
    thresholds: {
      ...outcomeDurationThresholds(),
      room_contract_failures: ['count==0'],
      room_unexpected_4xx: ['count==0'],
      room_server_failures: ['count==0'],
      room_start_skew_ms: [START_SKEW_THRESHOLD],
    },
  };
}

export function writeSetup(runtime) {
  return {
    sessions: preparedSessions(runtime),
    firstBarrierAt: Date.now() + (runtime.sessionWarmupSeconds * 1000),
    roundIntervalMilliseconds: runtime.roundIntervalSeconds * 1000,
  };
}

export function readSetup(runtime) {
  const sessions = preparedSessions(runtime);
  const firstBarrierAt = Date.now() + (runtime.sessionWarmupSeconds * 1000);
  return {
    sessions,
    firstBarrierAt,
    measurementEndsAt: firstBarrierAt + (runtime.readDurationSeconds * 1000),
  };
}

export function waitFor(time) {
  const remainingMilliseconds = time - Date.now();
  if (remainingMilliseconds > 0) {
    sleep(remainingMilliseconds / 1000);
  }
}

export function recordStartSkew(barrierAt, tags) {
  roomStartSkewMilliseconds.add(Math.max(0, Date.now() - barrierAt), tags);
}

export function targetForRoundAndSlot(fixture, round, slot) {
  const target = fixture.targets.find((candidate) => candidate.round === round && candidate.slot === slot);
  if (!target) {
    fail(`round=${round}, slot=${slot} fixture target을 찾지 못했습니다.`);
  }
  return target;
}

export function targetForRound(fixture, round) {
  const target = fixture.targets.find((candidate) => candidate.round === round);
  if (!target) {
    fail(`round=${round} fixture target을 찾지 못했습니다.`);
  }
  return target;
}

function recordResponse(response, outcome, tags, label) {
  const outcomeCategory = outcome.contract ? outcome.category : 'unexpected';
  const metricTags = { ...tags, outcome: outcomeCategory };
  roomRequestDuration.add(response.timings.duration, { outcome: outcomeCategory });
  roomRequests.add(1, metricTags);

  const isSuccessful = outcome.category === 'success' && outcome.contract;
  const isBusinessFailure = outcome.category === 'business' && outcome.contract;
  const isConcurrentFailure = outcome.category === 'concurrency' && outcome.contract;
  const isServerFailure = response.status >= 500;
  const isUnexpected4xx = !outcome.contract && response.status >= 400 && response.status < 500;

  roomSuccess.add(isSuccessful ? 1 : 0, metricTags);
  roomCreated.add(isSuccessful && response.status === 201 ? 1 : 0, metricTags);
  roomBusinessFailures.add(isBusinessFailure ? 1 : 0, metricTags);
  roomConcurrentFailures.add(isConcurrentFailure ? 1 : 0, metricTags);
  roomUnexpected4xx.add(isUnexpected4xx ? 1 : 0, metricTags);
  roomServerFailures.add(isServerFailure ? 1 : 0, metricTags);
  roomContractFailures.add(outcome.contract ? 0 : 1, metricTags);
  check(response, { [`${label} response contract`]: () => outcome.contract }, metricTags);
}

export function evaluateResponse(response, classifier, tags, label) {
  const value = envelope(response);
  const outcome = classifier(response, value);
  recordResponse(response, outcome, tags, label);
  return { ...outcome, value };
}

export function recordWaitlistPosition(position, tags) {
  for (let index = 0; index < roomWaitlistPositionCounters.length; index += 1) {
    roomWaitlistPositionCounters[index].add(position === index + 1 ? 1 : 0, tags);
  }
}

function success(response, value, valid) {
  return { category: 'success', contract: commonEnvelope(response, value) && valid };
}

function business(response, value, valid) {
  return { category: 'business', contract: commonEnvelope(response, value) && valid };
}

function concurrent(response, value, valid) {
  return { category: 'concurrency', contract: commonEnvelope(response, value) && valid };
}

function unexpected() {
  return { category: 'unexpected', contract: false };
}

export function classifyT1Cancel(response, value, room) {
  if (response.status === 200) {
    return success(
      response,
      value,
      hasParticipationPayload(value && value.data, room.id, 'CANCELED', 'CLOSED', room.capacity + 1, 0),
    );
  }
  if (response.status === 409 && responseCode(value) === 'ROOM_CONCURRENT_MODIFICATION') {
    return concurrent(response, value, value.data === null);
  }
  return unexpected();
}

export function classifyT2Waitlist(response, value, expectedRoomId, allowExisting, expectedPosition = null) {
  const data = value && value.data;
  const validData = hasWaitlistPayload(data, expectedRoomId, expectedPosition);
  if (response.status === 201) {
    return success(response, value, validData);
  }
  if (allowExisting && response.status === 200) {
    return success(response, value, validData);
  }
  if (response.status === 409 && responseCode(value) === 'ROOM_CONCURRENT_MODIFICATION') {
    return concurrent(response, value, value.data === null);
  }
  return unexpected();
}

export function classifyT3Waitlist(response, value, expectedRoomId) {
  const data = value && value.data;
  if (response.status === 201) {
    return success(
      response,
      value,
      hasWaitlistPayload(data, expectedRoomId, 1),
    );
  }
  if (response.status === 409 && responseCode(value) === 'WAITLIST_NOT_AVAILABLE') {
    return business(response, value, value.data === null);
  }
  if (response.status === 409 && responseCode(value) === 'ROOM_CONCURRENT_MODIFICATION') {
    return concurrent(response, value, value.data === null);
  }
  return unexpected();
}

export function classifyT3Cancel(response, value, room, t3Mode) {
  if (response.status === 200) {
    const data = value && value.data;
    return success(response, value, hasT3CancelPayload(data, room, t3Mode));
  }
  if (response.status === 409 && responseCode(value) === 'ROOM_CONCURRENT_MODIFICATION') {
    return concurrent(response, value, value.data === null);
  }
  return unexpected();
}

export function classifyT4Join(response, value, expectedRoomId) {
  if (response.status === 201) {
    return success(
      response,
      value,
      hasParticipationPayload(value && value.data, expectedRoomId, 'ACTIVE', 'CLOSED', 2, 0),
    );
  }
  if (response.status === 409 && responseCode(value) === 'CAPACITY_EXCEEDED') {
    return business(response, value, value.data === null);
  }
  if (response.status === 409 && responseCode(value) === 'ROOM_CONCURRENT_MODIFICATION') {
    return concurrent(response, value, value.data === null);
  }
  return unexpected();
}

function participantNicknames(fixture, room) {
  return [room.hostKey, ...room.activeKeys].map((userKey) => fixture.users[userKey].nickname);
}

export function classifyT5Detail(response, value, fixture, target) {
  const room = fixture.rooms[target.roomKey];
  const data = value && value.data;
  const commonValid = response.status === 200
    && commonEnvelope(response, value)
    && data && data.id === room.id
    && data.status === 'CLOSED'
    && data.recruitmentCapacity === room.capacity
    && data.participantCount === room.capacity + 1
    && data.remainingRecruitmentSeats === 0
    && data.joinable === false
    && header(response, 'Cache-Control').includes('private')
    && header(response, 'Cache-Control').includes('no-store')
    && header(response, 'Vary').toLowerCase().includes('cookie');
  if (!commonValid) {
    return unexpected();
  }

  if (target.role === 'public') {
    const valid = data.waitlistable === true
      && !hasOwn(data, 'myRole')
      && !hasOwn(data, 'place')
      && !hasOwn(data, 'host')
      && !hasOwn(data, 'participants');
    return success(response, value, valid);
  }

  const expectedRole = target.role === 'host' ? 'HOST' : 'JOINED';
  const expectedParticipants = participantNicknames(fixture, room);
  const validParticipants = hasNicknameOnlySet(data.participants, expectedParticipants);
  const valid = data.waitlistable === false
    && data.myRole === expectedRole
    && typeof data.place === 'string'
    && hasNicknameSummary(data.host)
    && data.host.nickname === fixture.users[room.hostKey].nickname
    && validParticipants;
  return success(response, value, valid);
}
