import { check, sleep } from 'k6';
import exec from 'k6/execution';
import http from 'k6/http';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = (__ENV.ALBAM_MATE_TARGET_URL || __ENV.K6_BASE_URL || 'http://localhost:8080')
  .replace(/\/$/, '');
const EXPECTED_200 = http.expectedStatuses(200);
const EXPECTED_MUTATION_200 = http.expectedStatuses(200, 409);
const EXPECTED_MUTATION_201 = http.expectedStatuses(201, 409);
const WAVE_PROFILE_COMPLETION_GRACE_SECONDS = 30;
const DURATION_PATTERN = /^[1-9]\d*(ms|s|m|h)$/;

export const requestDuration = new Trend('room_request_duration_ms', true);
export const measuredRequests = new Counter('room_measured_requests');
export const successResponses = new Counter('room_success_responses');
export const conflictResponses = new Counter('room_conflict_responses');
export const unexpected4xxResponses = new Counter('room_unexpected_4xx_responses');
export const serverErrorResponses = new Counter('room_5xx_responses');
export const unexpectedResponseRate = new Rate('room_unexpected_response_rate');
export const measurementCheckRate = new Rate('room_measurement_check_rate');

const sessions = {};
const vuWarmups = {};

export const correctnessThresholds = {
  room_measurement_check_rate: ['rate==1'],
  room_unexpected_response_rate: ['rate==0'],
};

function abortTest(message) {
  exec.test.abort(message);
  throw new Error(message);
}

function readJson(path, label) {
  if (!path) {
    throw new Error(`${label} 경로가 필요합니다.`);
  }

  try {
    return JSON.parse(open(path));
  } catch (error) {
    throw new Error(`${label}을 읽을 수 없습니다: ${error.message}`);
  }
}

function requireManifest(condition, message) {
  if (!condition) {
    throw new Error(`manifest 검증 실패: ${message}`);
  }
}

function isIntegerBetween(value, minimum, maximum) {
  return Number.isInteger(value) && value >= minimum && value <= maximum;
}

function validateClassification(manifest, expectedScenario) {
  const classification = manifest.classification;
  requireManifest(classification && typeof classification === 'object',
    'classification 객체가 필요합니다.');
  requireManifest(classification.loadProfiles && typeof classification.loadProfiles === 'object',
    'classification.loadProfiles 객체가 필요합니다.');

  if (expectedScenario === 'waitlist-position') {
    requireManifest(classification.category === 'data-scale-low-contention-comparison',
      '대기 순번은 data-scale-low-contention-comparison 분류여야 합니다.');
    requireManifest(classification.appliedLoadType === 'constant-vus-1',
      '대기 순번은 constant-vus-1 부하만 허용합니다.');
    requireManifest(classification.loadProfiles.stress === 'not-applicable'
      && classification.loadProfiles.spike === 'not-applicable'
      && classification.loadProfiles.soak === 'not-applicable',
    '대기 순번에는 stress/spike/soak profile을 적용하지 않습니다.');
    return;
  }

  let expectedCategory = 'write-contention';
  if (expectedScenario === 'due-backlog-read') {
    expectedCategory = 'read-write-contention';
  } else if (expectedScenario === 'room-detail') {
    expectedCategory = 'read-load';
  }
  requireManifest(classification.category === expectedCategory,
    `시나리오 분류가 올바르지 않습니다: ${expectedScenario}`);
  requireManifest(classification.loadProfiles.stress === 'required',
    'stress는 필수 profile이어야 합니다.');
  requireManifest(classification.loadProfiles.spike === 'recommended',
    'spike는 권장 profile이어야 합니다.');
  const expectedSoak = expectedScenario === 'room-detail' ? 'future-recommended' : 'excluded';
  requireManifest(classification.loadProfiles.soak === expectedSoak,
    `soak 분류가 올바르지 않습니다: ${expectedScenario}`);
}

function validateSpikeRamp(configuration) {
  if (configuration.loadProfile !== 'spike') {
    requireManifest(configuration.spikeRampSeconds === null,
      'stress/soak profile에는 spikeRampSeconds가 없어야 합니다.');
    return;
  }
  requireManifest(isIntegerBetween(configuration.spikeRampSeconds, 1, 10),
    'spikeRampSeconds는 1~10 정수여야 합니다.');
}

function validateLoginConfiguration(manifest, users) {
  requireManifest(Array.isArray(manifest.loginUserKeys), 'loginUserKeys 배열이 필요합니다.');
  requireManifest(new Set(manifest.loginUserKeys).size === manifest.loginUserKeys.length,
    'loginUserKeys에 중복 값이 있습니다.');
  requireManifest(users.schemaVersion === 1, `users schemaVersion=${users.schemaVersion}`);
  requireManifest(users.fixtureId === manifest.fixtureId, 'manifest와 users의 fixtureId가 다릅니다.');
  requireManifest(users.credentials && typeof users.credentials === 'object',
    'users.json에 credentials 객체가 필요합니다.');

  for (const userKey of manifest.loginUserKeys) {
    const credential = users.credentials[userKey];
    requireManifest(typeof userKey === 'string' && userKey.length > 0,
      'loginUserKeys에는 비어 있지 않은 문자열만 허용합니다.');
    requireManifest(credential && typeof credential === 'object',
      `users.json에 ${userKey} credential이 없습니다.`);
    requireManifest(typeof credential.email === 'string' && credential.email.length > 0,
      `${userKey} email이 필요합니다.`);
    requireManifest(typeof credential.password === 'string' && credential.password.length > 0,
      `${userKey} password가 필요합니다.`);
  }
}

function validateWaveManifest(manifest, expectedScenario) {
  const maximumVus = expectedScenario === 'cancel-promotion' ? 10 : 32;
  requireManifest(isIntegerBetween(manifest.globalStartDelaySeconds, 0, 60),
    'globalStartDelaySeconds는 0~60 정수여야 합니다.');
  requireManifest(Array.isArray(manifest.configurations) && manifest.configurations.length > 0,
    'configurations가 비어 있습니다.');

  const configurationIds = new Set();
  let earliestNextProfileStartSeconds = 0;
  for (const configuration of manifest.configurations) {
    requireManifest(configuration && typeof configuration === 'object',
      'configuration은 객체여야 합니다.');
    requireManifest(typeof configuration.id === 'string' && configuration.id.length > 0,
      'configuration.id가 필요합니다.');
    requireManifest(!configurationIds.has(configuration.id),
      `configuration.id가 중복되었습니다: ${configuration.id}`);
    configurationIds.add(configuration.id);
    requireManifest(configuration.loadProfile === 'stress' || configuration.loadProfile === 'spike',
      `지원하지 않는 loadProfile입니다: ${configuration.loadProfile}`);
    requireManifest(configuration.mode === 'hot' || configuration.mode === 'spread',
      `지원하지 않는 mode입니다: ${configuration.mode}`);
    requireManifest(isIntegerBetween(configuration.vus, 1, maximumVus),
      `configuration.vus는 1~${maximumVus} 정수여야 합니다: ${configuration.vus}`);
    requireManifest(Number.isInteger(configuration.startOffsetSeconds)
      && configuration.startOffsetSeconds >= 0,
      'startOffsetSeconds는 0 이상의 정수여야 합니다.');
    requireManifest(configuration.startOffsetSeconds >= earliestNextProfileStartSeconds,
      `이전 profile의 maxDuration과 겹칩니다: ${configuration.id}`);
    requireManifest(isIntegerBetween(configuration.startDelaySeconds, 1, 60),
      'startDelaySeconds는 1~60 정수여야 합니다.');
    requireManifest(isIntegerBetween(configuration.waveIntervalSeconds, 1, 60),
      'waveIntervalSeconds는 1~60 정수여야 합니다.');
    requireManifest(isIntegerBetween(configuration.warmupWaves, 0, 10),
      'warmupWaves는 0~10 정수여야 합니다.');
    requireManifest(isIntegerBetween(configuration.measuredWaves, 1, 100),
      'measuredWaves는 1~100 정수여야 합니다.');
    requireManifest(configuration.waveCount
      === configuration.warmupWaves + configuration.measuredWaves,
      'waveCount는 warmupWaves와 measuredWaves의 합이어야 합니다.');
    if (configuration.loadProfile === 'spike') {
      requireManifest(configuration.warmupWaves === 0 && configuration.measuredWaves === 1,
        'spike는 warmup 0, measure 1회의 단일 동시 burst여야 합니다.');
    }
    const expectedMaxDurationSeconds = configuration.startDelaySeconds
      + configuration.waveCount * configuration.waveIntervalSeconds
      + WAVE_PROFILE_COMPLETION_GRACE_SECONDS;
    requireManifest(configuration.maxDurationSeconds === expectedMaxDurationSeconds,
      `maxDurationSeconds가 wave 실행 구간과 일치하지 않습니다: ${configuration.id}`);
    earliestNextProfileStartSeconds = configuration.startOffsetSeconds
      + configuration.maxDurationSeconds;
    requireManifest(Array.isArray(configuration.targets)
      && configuration.targets.length === configuration.waveCount,
    `targets 수가 waveCount와 다릅니다: ${configuration.id}`);

    for (const targets of configuration.targets) {
      requireManifest(Array.isArray(targets) && targets.length === configuration.vus,
        `wave target 수가 VU와 다릅니다: ${configuration.id}`);
      for (const target of targets) {
        requireManifest(Number.isSafeInteger(target?.roomId) && target.roomId > 0,
          `target.roomId가 올바르지 않습니다: ${configuration.id}`);
        requireManifest(typeof target?.userKey === 'string' && target.userKey.length > 0,
          `target.userKey가 올바르지 않습니다: ${configuration.id}`);
        requireManifest(manifest.loginUserKeys.includes(target.userKey),
          `target.userKey가 loginUserKeys에 없습니다: ${target.userKey}`);
      }
    }
  }
}

function validateDueBacklogManifest(manifest) {
  const configuration = manifest.configuration;
  requireManifest(configuration && typeof configuration === 'object',
    'configuration 객체가 필요합니다.');
  requireManifest(configuration.endpoint === 'room-list' || configuration.endpoint === 'my-rooms',
    `지원하지 않는 endpoint입니다: ${configuration.endpoint}`);
  requireManifest([0, 20, 2_000, 10_000].includes(configuration.dueRoomCount),
    'dueRoomCount는 0, 20, 2000, 10000 중 하나여야 합니다.');
  requireManifest(configuration.loadProfile === 'stress' || configuration.loadProfile === 'spike',
    `지원하지 않는 loadProfile입니다: ${configuration.loadProfile}`);
  validateSpikeRamp(configuration);
  requireManifest(isIntegerBetween(configuration.vus, 1, 32),
    'vus는 1~32 정수여야 합니다.');
  requireManifest(DURATION_PATTERN.test(configuration.duration),
    `duration 형식이 올바르지 않습니다: ${configuration.duration}`);
  requireManifest(isIntegerBetween(configuration.thinkTimeSeconds, 0, 60),
    'thinkTimeSeconds는 0~60 정수여야 합니다.');
  requireManifest(isIntegerBetween(manifest.globalStartDelaySeconds, 1, 60),
    'globalStartDelaySeconds는 1~60 정수여야 합니다.');
  requireManifest(isIntegerBetween(configuration.recruitingDueRoomCount, 0, 10_000)
      && isIntegerBetween(configuration.closedDueRoomCount, 0, 10_000)
      && configuration.recruitingDueRoomCount + configuration.closedDueRoomCount
        === configuration.dueRoomCount,
    'RECRUITING/CLOSED due ROOM 수가 dueRoomCount와 일치해야 합니다.');
  requireManifest(configuration.waitingPerClosedDueRoom === 10,
    'waitingPerClosedDueRoom은 ROOM-09d 기준값 10이어야 합니다.');
  requireManifest(configuration.controlRoomCount === 10,
    'controlRoomCount는 10이어야 합니다.');
  requireManifest(configuration.expectedWaitingWaitlistCount
      === configuration.closedDueRoomCount * configuration.waitingPerClosedDueRoom,
    'expectedWaitingWaitlistCount가 CLOSED due ROOM 수와 일치해야 합니다.');
  requireManifest(configuration.schedulerLockName === 'room-status-correction',
    'schedulerLockName은 room-status-correction이어야 합니다.');
  requireManifest(configuration.schedulerLockDurationSeconds === 300,
    'schedulerLockDurationSeconds는 300이어야 합니다.');
  requireManifest(configuration.schedulerLockOwner
      === `ROOM-K6:${manifest.fixtureId}:due-backlog-read`,
    'schedulerLockOwner가 fixtureId와 일치해야 합니다.');
  if (configuration.endpoint === 'my-rooms') {
    requireManifest(typeof configuration.userKey === 'string'
      && manifest.loginUserKeys.includes(configuration.userKey),
      '내 모임 조회용 userKey가 필요합니다.');
  } else {
    requireManifest(configuration.userKey === null, 'ROOM 목록 조회는 userKey를 사용하지 않습니다.');
  }
}

function validateRoomDetailManifest(manifest) {
  const configuration = manifest.configuration;
  requireManifest(configuration && typeof configuration === 'object',
    'configuration 객체가 필요합니다.');
  requireManifest(['public', 'host', 'participant'].includes(configuration.role),
    `지원하지 않는 role입니다: ${configuration.role}`);
  requireManifest(isIntegerBetween(configuration.activeParticipantCount, 1, 10),
    'activeParticipantCount는 1~10 정수여야 합니다.');
  requireManifest(['stress', 'spike', 'soak'].includes(configuration.loadProfile),
    `지원하지 않는 loadProfile입니다: ${configuration.loadProfile}`);
  validateSpikeRamp(configuration);
  requireManifest(configuration.loadProfile !== 'soak' || configuration.durationExplicit === true,
    'soak profile은 명시한 duration이 필요합니다.');
  requireManifest(configuration.expectedParticipantCount === configuration.activeParticipantCount + 1,
    'expectedParticipantCount는 주최자를 포함한 참가자 수여야 합니다.');
  requireManifest(configuration.expectedRemainingRecruitmentSeats
      === 10 - configuration.activeParticipantCount,
    'expectedRemainingRecruitmentSeats는 모집 정원과 일치해야 합니다.');
  requireManifest(isIntegerBetween(configuration.vus, 1, 100), 'vus는 1~100 정수여야 합니다.');
  requireManifest(DURATION_PATTERN.test(configuration.duration),
    `duration 형식이 올바르지 않습니다: ${configuration.duration}`);
  requireManifest(isIntegerBetween(configuration.thinkTimeSeconds, 0, 60),
    'thinkTimeSeconds는 0~60 정수여야 합니다.');
  requireManifest(Number.isSafeInteger(configuration.roomId) && configuration.roomId > 0,
    'roomId는 양의 safe integer여야 합니다.');
  if (configuration.role === 'public') {
    requireManifest(configuration.userKey === null, '공개 상세 조회는 userKey를 사용하지 않습니다.');
    requireManifest(configuration.expectedMyRole === null
      && configuration.expectedParticipantsLength === null,
    '공개 상세 조회에는 관계자 응답 필드가 없어야 합니다.');
  } else {
    requireManifest(typeof configuration.userKey === 'string'
      && manifest.loginUserKeys.includes(configuration.userKey),
      `${configuration.role} 상세 조회용 userKey가 필요합니다.`);
    const expectedMyRole = configuration.role === 'host' ? 'HOST' : 'JOINED';
    requireManifest(configuration.expectedMyRole === expectedMyRole,
      `${configuration.role} 상세 조회의 myRole이 올바르지 않습니다.`);
    requireManifest(configuration.expectedParticipantsLength
        === configuration.expectedParticipantCount,
      '관계자 상세 조회의 participants 길이가 participantCount와 일치해야 합니다.');
  }
}

function expectedQueuePosition(configuration) {
  if (configuration.position === 'head') {
    return 1;
  }
  if (configuration.position === 'middle') {
    return Math.ceil(configuration.queueLength / 2);
  }
  return configuration.queueLength;
}

function validateWaitlistPositionManifest(manifest) {
  const configuration = manifest.configuration;
  requireManifest(configuration && typeof configuration === 'object',
    'configuration 객체가 필요합니다.');
  requireManifest([10, 100, 1_000, 10_000].includes(configuration.queueLength),
    'queueLength는 10, 100, 1000, 10000 중 하나여야 합니다.');
  requireManifest(['head', 'middle', 'tail'].includes(configuration.position),
    `지원하지 않는 position입니다: ${configuration.position}`);
  const expectedPosition = expectedQueuePosition(configuration);
  requireManifest(configuration.expectedPosition === expectedPosition,
    'expectedPosition이 position과 queueLength에 맞지 않습니다.');
  requireManifest(configuration.loadProfile === 'data-scale',
    '대기 순번은 data-scale profile이어야 합니다.');
  requireManifest(configuration.appliedLoadType === 'constant-vus-1',
    '대기 순번은 constant-vus-1 부하만 허용합니다.');
  requireManifest(configuration.vus === 1, '대기 순번 VU는 1로 고정해야 합니다.');
  requireManifest(DURATION_PATTERN.test(configuration.duration),
    `duration 형식이 올바르지 않습니다: ${configuration.duration}`);
  requireManifest(isIntegerBetween(configuration.thinkTimeSeconds, 0, 60),
    'thinkTimeSeconds는 0~60 정수여야 합니다.');
  requireManifest(Number.isSafeInteger(configuration.roomId) && configuration.roomId > 0,
    'roomId는 양의 safe integer여야 합니다.');
  requireManifest(typeof configuration.userKey === 'string'
    && manifest.loginUserKeys.includes(configuration.userKey),
    '대기 순번 조회용 userKey가 필요합니다.');
}

function validateScenarioManifest(manifest, expectedScenario) {
  if (expectedScenario === 'cancel-promotion' || expectedScenario === 'waitlist-registration') {
    validateWaveManifest(manifest, expectedScenario);
  } else if (expectedScenario === 'due-backlog-read') {
    validateDueBacklogManifest(manifest);
  } else if (expectedScenario === 'room-detail') {
    validateRoomDetailManifest(manifest);
  } else {
    validateWaitlistPositionManifest(manifest);
  }
}

export function loadRuntime(expectedScenario) {
  const manifest = readJson(__ENV.K6_MANIFEST_FILE, 'K6_MANIFEST_FILE');
  const users = readJson(__ENV.K6_USERS_FILE, 'K6_USERS_FILE');

  requireManifest(manifest && typeof manifest === 'object', 'manifest는 객체여야 합니다.');
  if (manifest.schemaVersion !== 1) {
    throw new Error(`지원하지 않는 manifest schemaVersion입니다: ${manifest.schemaVersion}`);
  }
  if (manifest.scenario !== expectedScenario) {
    throw new Error(`scenario 불일치: expected=${expectedScenario}, actual=${manifest.scenario}`);
  }
  requireManifest(typeof manifest.fixtureId === 'string' && manifest.fixtureId.length > 0,
    'fixtureId가 필요합니다.');
  requireManifest(users && typeof users === 'object', 'users.json은 객체여야 합니다.');
  validateClassification(manifest, expectedScenario);
  validateLoginConfiguration(manifest, users);
  validateScenarioManifest(manifest, expectedScenario);

  return { manifest, users };
}

function responseJson(response) {
  try {
    return response.json();
  } catch (_) {
    return null;
  }
}

function csrfToken(tags, jar) {
  const response = http.get(`${BASE_URL}/api/auth/csrf`, {
    jar,
    tags: { ...tags, operation: 'csrf' },
    responseCallback: EXPECTED_200,
  });
  const body = responseJson(response);
  const valid = check(response, {
    'CSRF token 발급 성공': (res) => res.status === 200
      && typeof body?.data?.headerName === 'string'
      && typeof body?.data?.token === 'string',
  }, tags);

  if (!valid) {
    abortTest(`CSRF token 발급 실패: status=${response.status}`);
  }
  return body.data;
}

function login(credential) {
  const loginTags = { phase: 'setup', operation: 'login' };
  const jar = new http.CookieJar();
  const beforeLogin = csrfToken(loginTags, jar);
  const response = http.post(`${BASE_URL}/api/auth/login`, JSON.stringify({
    email: credential.email,
    password: credential.password,
  }), {
    jar,
    headers: {
      'Content-Type': 'application/json',
      [beforeLogin.headerName]: beforeLogin.token,
    },
    tags: loginTags,
    responseCallback: EXPECTED_200,
  });

  const valid = check(response, {
    '로그인 성공': (res) => res.status === 200,
  }, loginTags);
  if (!valid) {
    abortTest(`로그인 실패: email=${credential.email}, status=${response.status}`);
  }

  const afterLogin = csrfToken(loginTags, jar);
  return { ...afterLogin, jar };
}

export function sessionFor(runtime, userKey) {
  if (!userKey) {
    return null;
  }
  if (!sessions[userKey]) {
    const credential = runtime.users.credentials[userKey];
    if (!credential) {
      abortTest(`credential을 찾을 수 없습니다: ${userKey}`);
    }
    sessions[userKey] = login(credential);
  }
  return sessions[userKey];
}

export function runVuLocalWarmup(warmupKey, warmup) {
  const key = `${exec.scenario.name}:${exec.vu.idInTest}:${warmupKey}`;
  if (vuWarmups[key]) {
    return false;
  }

  warmup();
  vuWarmups[key] = true;
  return true;
}

export function readParams(phase, operation, session = null) {
  return {
    ...(session ? { jar: session.jar } : {}),
    tags: { phase, operation },
    responseCallback: EXPECTED_200,
  };
}

export function mutationParams(session, phase, operation, successStatus) {
  return {
    jar: session.jar,
    headers: {
      [session.headerName]: session.token,
    },
    tags: { phase, operation },
    responseCallback: successStatus === 200 ? EXPECTED_MUTATION_200 : EXPECTED_MUTATION_201,
  };
}

export function waveScenarioOptions(manifest, executionFunction) {
  const scenarios = {};
  for (const configuration of manifest.configurations) {
    scenarios[configuration.id] = {
      executor: 'per-vu-iterations',
      exec: executionFunction,
      vus: configuration.vus,
      iterations: 1,
      startTime: `${configuration.startOffsetSeconds}s`,
      maxDuration: `${configuration.maxDurationSeconds}s`,
      gracefulStop: '0s',
      tags: {
        load_shape: configuration.mode,
        load_profile: configuration.loadProfile,
        test_classification: manifest.classification.category,
        concurrency: String(configuration.vus),
      },
    };
  }
  return scenarios;
}

export function readScenarioOptions(manifest, executionFunction, scenarioTags) {
  const configuration = manifest.configuration;
  const tags = {
    ...scenarioTags,
    load_profile: configuration.loadProfile,
    test_classification: manifest.classification.category,
  };
  if (configuration.loadProfile === 'spike') {
    const rampDuration = `${configuration.spikeRampSeconds}s`;
    return {
      executor: 'ramping-vus',
      exec: executionFunction,
      startVUs: 0,
      stages: [
        { duration: rampDuration, target: configuration.vus },
        { duration: configuration.duration, target: configuration.vus },
        { duration: rampDuration, target: 0 },
      ],
      gracefulRampDown: '0s',
      gracefulStop: '5s',
      tags,
    };
  }

  return {
    executor: 'constant-vus',
    exec: executionFunction,
    vus: configuration.vus,
    duration: configuration.duration,
    gracefulStop: '5s',
    tags,
  };
}

export function currentConfiguration(manifest) {
  const configuration = manifest.configurations.find((candidate) => candidate.id === exec.scenario.name);
  if (!configuration) {
    abortTest(`현재 scenario configuration을 찾을 수 없습니다: ${exec.scenario.name}`);
  }
  return configuration;
}

export function actorIndex() {
  return exec.scenario.iterationInTest;
}

export function waveTarget(configuration, wave, currentActorIndex) {
  const target = configuration.targets?.[wave]?.[currentActorIndex];
  if (!target) {
    abortTest(`wave target을 찾을 수 없습니다: configuration=${configuration.id}, wave=${wave}, actor=${currentActorIndex}`);
  }
  return target;
}

export function waitUntil(epochMillis) {
  const remainingMillis = epochMillis - Date.now();
  if (remainingMillis > 0) {
    sleep(remainingMillis / 1000);
  }
}

export function recordResponse(
  response,
  phase,
  successStatus,
  tags,
  allowConcurrentConflict = false,
) {
  if (phase !== 'measure') {
    return;
  }

  requestDuration.add(response.timings.duration, tags);
  measuredRequests.add(1, tags);
  if (response.status === successStatus) {
    successResponses.add(1, tags);
    unexpectedResponseRate.add(false, tags);
    return;
  }
  const body = responseJson(response);
  const expectedConflict = allowConcurrentConflict
    && response.status === 409
    && body?.code === 'ROOM_CONCURRENT_MODIFICATION';
  if (expectedConflict) {
    conflictResponses.add(1, tags);
    unexpectedResponseRate.add(false, tags);
    return;
  }
  if (response.status >= 500) {
    serverErrorResponses.add(1, tags);
  } else if (response.status >= 400) {
    unexpected4xxResponses.add(1, tags);
  }
  unexpectedResponseRate.add(true, tags);
}

function recordMeasurementCheck(valid, phase, tags) {
  if (phase === 'measure') {
    measurementCheckRate.add(valid, tags);
  }
}

export function checkMutationResponse(response, phase, successStatus, successPredicate, tags = { phase }) {
  const body = responseJson(response);
  const checkTags = { ...tags, phase };
  const valid = check(response, {
    '성공 또는 동시 수정 409 응답': (res) => {
      if (res.status === successStatus) {
        return successPredicate(body);
      }
      return res.status === 409 && body?.code === 'ROOM_CONCURRENT_MODIFICATION';
    },
  }, checkTags);
  recordMeasurementCheck(valid, phase, checkTags);
  if (phase !== 'measure' && !valid) {
    abortTest(`warm-up 명령 응답 계약 위반: status=${response.status}`);
  }
  return valid;
}

export function checkPageResponse(response, phase, tags = { phase }) {
  const body = responseJson(response);
  const checkTags = { ...tags, phase };
  const valid = check(response, {
    'ROOM 목록 조회 계약 충족': (res) => res.status === 200
      && body?.status === 200
      && Array.isArray(body?.data?.content),
  }, checkTags);
  recordMeasurementCheck(valid, phase, checkTags);
  if (phase !== 'measure' && !valid) {
    abortTest(`ROOM 목록 warm-up 응답 계약 위반: status=${response.status}`);
  }
  return valid;
}

function hasNoRelationshipFields(detail) {
  return ['myRole', 'place', 'host', 'participants'].every(
    (field) => !Object.prototype.hasOwnProperty.call(detail, field),
  );
}

function matchesExpectedInteger(value, expected) {
  return Number.isSafeInteger(value) && value === expected;
}

function matchesRoomDetailContract(detail, configuration) {
  if (!matchesExpectedInteger(detail?.id, configuration.roomId)
    || !matchesExpectedInteger(detail?.participantCount, configuration.expectedParticipantCount)
    || !matchesExpectedInteger(
      detail?.remainingRecruitmentSeats,
      configuration.expectedRemainingRecruitmentSeats,
    )) {
    return false;
  }

  if (configuration.role === 'public') {
    return hasNoRelationshipFields(detail);
  }

  return detail?.myRole === configuration.expectedMyRole
    && Array.isArray(detail?.participants)
    && detail.participants.length === configuration.expectedParticipantsLength;
}

export function checkRoomDetailResponse(response, phase, configuration, tags = { phase }) {
  const body = responseJson(response);
  const detail = body?.data;
  const checkTags = { ...tags, phase };
  const valid = check(response, {
    'ROOM 상세 조회 계약 충족': (res) => res.status === 200
      && body?.status === 200
      && matchesRoomDetailContract(detail, configuration),
  }, checkTags);
  recordMeasurementCheck(valid, phase, checkTags);
  if (phase !== 'measure' && !valid) {
    abortTest(`ROOM 상세 warm-up 응답 계약 위반: status=${response.status}`);
  }
  return valid;
}

export function checkWaitlistPositionResponse(
  response,
  phase,
  roomId,
  expectedPosition,
  tags = { phase },
) {
  const body = responseJson(response);
  const checkTags = { ...tags, phase };
  const valid = check(response, {
    '대기 순번 조회 계약 충족': (res) => res.status === 200
      && body?.status === 200
      && Number(body?.data?.roomId) === Number(roomId)
      && body?.data?.waitlistStatus === 'WAITING'
      && Number(body?.data?.position) === Number(expectedPosition),
  }, checkTags);
  recordMeasurementCheck(valid, phase, checkTags);
  if (phase !== 'measure' && !valid) {
    abortTest(`대기 순번 warm-up 응답 계약 위반: status=${response.status}`);
  }
  return valid;
}

export function baseUrl() {
  return BASE_URL;
}
