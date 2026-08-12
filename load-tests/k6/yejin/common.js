import http from 'k6/http';
import { check, sleep } from 'k6';

export const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
export const SESSION_COOKIE =
  __ENV.SESSION_COOKIE ||
  (__ENV.JSESSIONID ? `JSESSIONID=${__ENV.JSESSIONID}` : '');
export const FIXED_KEYWORD = (__ENV.KEYWORD || '').trim();

export function authHeaders() {
  if (!SESSION_COOKIE) {
    return {};
  }
  return {
    Cookie: SESSION_COOKIE,
  };
}

export function requestParams(name, authenticated = false) {
  return {
    headers: authenticated ? authHeaders() : {},
    tags: { name },
  };
}

export function assertOk(response, label) {
  check(response, {
    [`${label}: status 200`]: (r) => r.status === 200,
  });
}

export function think(minSeconds = 0.5, maxSeconds = 1.5) {
  sleep(minSeconds + Math.random() * (maxSeconds - minSeconds));
}

export function pick(items) {
  if (!items || items.length === 0) return null;
  return items[Math.floor(Math.random() * items.length)];
}

export function query(params) {
  const parts = [];
  for (const [key, value] of Object.entries(params)) {
    if (value === undefined || value === null || value === '') continue;

    if (Array.isArray(value)) {
      value.forEach((item) => {
        if (item !== undefined && item !== null && item !== '') {
          parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(item)}`);
        }
      });
    } else {
      parts.push(`${encodeURIComponent(key)}=${encodeURIComponent(value)}`);
    }
  }
  return parts.length ? `?${parts.join('&')}` : '';
}

export function fetchGameFixture() {
  const res = http.get(
    `${BASE_URL}/api/games?size=100`,
    requestParams('setup-game-list')
  );

  if (res.status !== 200) {
    throw new Error(`setup /api/games failed: status=${res.status}, body=${res.body}`);
  }

  const json = res.json();
  const content = json?.data?.content || [];

  const games = content
    .filter((game) => game && game.id && game.name)
    .map((game) => ({
      id: game.id,
      name: game.name,
    }));

  if (games.length === 0) {
    throw new Error('setup에서 게임 데이터를 찾지 못했습니다. DB seed/import 상태를 확인하세요.');
  }

  return games;
}

function fetchCodes(path) {
  const res = http.get(
    `${BASE_URL}${path}`,
    requestParams(`setup-${path}`)
  );

  if (res.status !== 200) {
    throw new Error(`setup ${path} failed: status=${res.status}, body=${res.body}`);
  }

  const data = res.json()?.data || [];
  if (!Array.isArray(data)) {
    throw new Error(`setup ${path} failed: data must be an array`);
  }

  const codes = data
    .map((item) => item?.code)
    .filter((code) => typeof code === 'string' && code.length > 0);

  if (codes.length === 0) {
    throw new Error(`setup ${path} failed: at least one code is required`);
  }

  return codes;
}

export function fetchMetadataCodes() {
  return {
    categories: fetchCodes('/api/game-categories'),
    themes: fetchCodes('/api/game-themes'),
    mechanisms: fetchCodes('/api/game-mechanisms'),
  };
}

export function keywordFromGame(game) {
  if (!game?.name) return 'a';

  const cleaned = game.name.trim();
  if (cleaned.length <= 2) return cleaned;

  const start = Math.floor(Math.random() * Math.max(1, cleaned.length - 2));
  return cleaned.substring(start, Math.min(cleaned.length, start + 2));
}

export function scenarioKeyword(game) {
  return FIXED_KEYWORD || keywordFromGame(game);
}

export const thresholds = {
  checks: ['rate==1'],
  http_req_failed: ['rate<0.01'],
  http_req_duration: ['p(95)<500', 'p(99)<1000'],
};
