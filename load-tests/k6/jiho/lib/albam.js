import http from 'k6/http';

const RUN_ID_PATTERN = /^[a-z0-9][a-z0-9._-]{0,79}$/;

function requiredEnv(name) {
  const value = (__ENV[name] || '').trim();
  if (!value) {
    throw new Error(`${name} 환경 변수가 필요합니다.`);
  }
  return value;
}

function normalizeTargetUrl(value) {
  return value.replace(/\/+$/, '');
}

export const TARGET_URL = normalizeTargetUrl(requiredEnv('ALBAM_MATE_TARGET_URL'));
export const RUN_ID = requiredEnv('ALBAM_MATE_RUN_ID');
export const FIXTURE_PASSWORD = 'LoadTest-Password-2026!';
export const CAPACITY_PROFILE_ACK = 'auth-notification-perf-v1';

if (!RUN_ID_PATTERN.test(RUN_ID)) {
  throw new Error('ALBAM_MATE_RUN_ID는 영문 소문자 또는 숫자로 시작하는 80자 이하의 안전한 값이어야 합니다.');
}

export function integerEnv(name, fallback, minimum, maximum) {
  const raw = (__ENV[name] || '').trim();
  const value = raw ? Number(raw) : fallback;
  if (!Number.isInteger(value) || value < minimum || value > maximum) {
    throw new Error(`${name}은(는) ${minimum} 이상 ${maximum} 이하의 정수여야 합니다.`);
  }
  return value;
}

export function requireCapacityProfile() {
  const actual = (__ENV.CAPACITY_PROFILE_ACK || '').trim();
  if (actual !== CAPACITY_PROFILE_ACK) {
    throw new Error(
      `용량 측정은 고정 성능 프로파일과 실제 컨테이너 설정 검증을 통과한 뒤 CAPACITY_PROFILE_ACK=${CAPACITY_PROFILE_ACK}로 실행해야 합니다.`,
    );
  }
}

export function fixtureAccount(index) {
  if (!Number.isInteger(index) || index < 1) {
    throw new Error('fixture 사용자 번호는 1 이상의 정수여야 합니다.');
  }
  return {
    email: `k6.${RUN_ID}.auth.${index}@example.com`,
    password: FIXTURE_PASSWORD,
  };
}

export function missingAccount(index) {
  return {
    email: `k6.${RUN_ID}.missing.${index}@example.com`,
    password: FIXTURE_PASSWORD,
  };
}

export function createClient() {
  return { jar: new http.CookieJar(), csrf: null };
}

export function responseCode(response) {
  try {
    return response.json('code') || null;
  } catch (_) {
    return null;
  }
}

export function responseData(response) {
  try {
    return response.json('data');
  } catch (_) {
    return null;
  }
}

export function responseHeader(response, name) {
  const expected = name.toLowerCase();
  for (const key of Object.keys(response.headers || {})) {
    if (key.toLowerCase() === expected) {
      return response.headers[key];
    }
  }
  return null;
}

export function upstreamName(response) {
  return responseHeader(response, 'X-Albam-Mate-Upstream') || 'missing';
}

export function retryAfterSeconds(response) {
  const value = responseHeader(response, 'Retry-After');
  return value === null ? null : Number(value);
}

export function fetchCsrf(client, tags = {}) {
  const response = http.get(`${TARGET_URL}/api/auth/csrf`, {
    jar: client.jar,
    tags: { operation: 'csrf', ...tags },
  });
  const data = responseData(response);
  client.csrf = response.status === 200 && data
    ? { headerName: data.headerName, token: data.token }
    : null;
  return response;
}

export function requestJson(client, method, path, body, tags = {}, headers = {}) {
  const requestHeaders = { 'Content-Type': 'application/json', ...headers };
  if (client.csrf) {
    requestHeaders[client.csrf.headerName] = client.csrf.token;
  }
  return http.request(method, `${TARGET_URL}${path}`, body === null ? null : JSON.stringify(body), {
    jar: client.jar,
    headers: requestHeaders,
    tags,
  });
}

export function signupRequest(client, account, nickname, tags = {}, headers = {}) {
  return requestJson(client, 'POST', '/api/auth/signup', {
    email: account.email,
    password: account.password,
    nickname,
  }, { operation: 'signup', ...tags }, headers);
}

export function publicProbe(client, tags = {}) {
  return http.get(`${TARGET_URL}/api/games?size=1`, {
    jar: client.jar,
    tags: { operation: 'upstream-probe', ...tags },
  });
}

export function loginRequest(client, account, tags = {}, headers = {}) {
  const response = requestJson(client, 'POST', '/api/auth/login', account, {
    operation: 'login',
    ...tags,
  }, headers);
  if (response.status === 200) {
    client.csrf = null;
  }
  return response;
}

export function logoutRequest(client, tags = {}) {
  if (!client.csrf) {
    fetchCsrf(client, tags);
  }
  return requestJson(client, 'POST', '/api/auth/logout', null, {
    operation: 'logout',
    ...tags,
  });
}

export function loginFixture(client, index, tags = {}) {
  const csrf = fetchCsrf(client, tags);
  if (csrf.status !== 200) {
    return { csrf, login: null };
  }
  return { csrf, login: loginRequest(client, fixtureAccount(index), tags) };
}

export function createRoom(client, title, tags = {}, recruitmentCapacity = 1) {
  if (!client.csrf) {
    fetchCsrf(client, tags);
  }
  return requestJson(client, 'POST', '/api/rooms', {
    roomType: 'PERSON_FOCUSED',
    title,
    description: 'k6 notification E2E fixture',
    gameId: null,
    experienceLevel: 'ALL_LEVELS',
    isRulemasterLed: false,
    startsAt: new Date(Date.now() + 24 * 60 * 60 * 1000).toISOString(),
    place: 'k6-perf-fixture',
    recruitmentCapacity,
  }, { operation: 'room-create', ...tags });
}

export function joinRoom(client, roomId, tags = {}) {
  if (!client.csrf) {
    fetchCsrf(client, tags);
  }
  return requestJson(client, 'POST', `/api/rooms/${roomId}/participants`, null, {
    operation: 'room-join',
    ...tags,
  });
}

export function cancelParticipation(client, roomId, tags = {}) {
  if (!client.csrf) {
    fetchCsrf(client, tags);
  }
  return requestJson(client, 'DELETE', `/api/rooms/${roomId}/participants/me`, null, {
    operation: 'room-cancel',
    ...tags,
  });
}

export function cancelRoom(client, roomId, tags = {}) {
  if (!client.csrf) {
    fetchCsrf(client, tags);
  }
  return requestJson(client, 'DELETE', `/api/rooms/${roomId}`, null, {
    operation: 'room-cancel-by-host',
    ...tags,
  });
}

export function listRooms(client, page = 0, size = 10, tags = {}) {
  return http.get(`${TARGET_URL}/api/rooms?page=${page}&size=${size}`, {
    jar: client.jar,
    tags: { operation: 'room-list', ...tags },
  });
}

export function listNotifications(client, page = 0, size = 100, tags = {}) {
  return http.get(`${TARGET_URL}/api/users/me/notifications?page=${page}&size=${size}`, {
    jar: client.jar,
    tags: { operation: 'notification-list', ...tags },
  });
}

export function unreadNotificationCount(client, tags = {}) {
  return http.get(`${TARGET_URL}/api/users/me/notifications/unread-count`, {
    jar: client.jar,
    tags: { operation: 'notification-unread-count', ...tags },
  });
}
