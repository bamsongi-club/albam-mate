import http from 'k6/http';
import {
  BASE_URL,
  SESSION_COOKIE,
  assertOk,
  fetchGameFixture,
  fetchMetadataCodes,
  pick,
  query,
  requestParams,
  scenarioKeyword,
  think,
  thresholds,
} from './common.js';

const PROFILE = __ENV.PROFILE || 'load';

const PROFILES = {
  load: {
    stages: [
      { duration: '1m', target: 10 },
      { duration: '1m', target: 30 },
      { duration: '1m', target: 50 },
      { duration: '3m', target: 50 },
      { duration: '1m', target: 100 },
      { duration: '5m', target: 100 },
      { duration: '1m', target: 0 },
    ],
  },
  spike: {
    stages: [
      { duration: '1m', target: 20 },
      { duration: '10s', target: 200 },
      { duration: '1m', target: 200 },
      { duration: '10s', target: 20 },
      { duration: '2m', target: 20 },
      { duration: '30s', target: 0 },
    ],
  },
  stress: {
    stages: [
      { duration: '1m', target: 50 },
      { duration: '2m', target: 50 },
      { duration: '1m', target: 100 },
      { duration: '2m', target: 100 },
      { duration: '1m', target: 200 },
      { duration: '2m', target: 200 },
      { duration: '1m', target: 300 },
      { duration: '2m', target: 300 },
      { duration: '1m', target: 500 },
      { duration: '2m', target: 500 },
      { duration: '1m', target: 0 },
    ],
  },
  soak: {
    stages: [
      { duration: '2m', target: 50 },
      { duration: __ENV.SOAK_DURATION || '1h', target: 50 },
      { duration: '2m', target: 0 },
    ],
  },
};

if (!PROFILES[PROFILE]) {
  throw new Error(`지원하지 않는 PROFILE=${PROFILE}. load|spike|stress|soak 중 하나를 사용하세요.`);
}

export const options = {
  ...PROFILES[PROFILE],
  thresholds: {
    ...thresholds,

    'http_req_duration{name:game-list}': ['p(95)<500'],
    'http_req_duration{name:game-keyword}': ['p(95)<500'],
    'http_req_duration{name:game-filter-complex}': ['p(95)<500'],
    'http_req_duration{name:game-relation}': ['p(95)<700'],
    'http_req_duration{name:game-upcoming}': ['p(95)<700'],
    'http_req_duration{name:game-detail}': ['p(95)<500'],
  },
};

export function setup() {
  return {
    games: fetchGameFixture(),
    metadata: fetchMetadataCodes(),
  };
}

function list() {
  const res = http.get(
    `${BASE_URL}/api/games?page=0&size=20`,
    requestParams('game-list')
  );
  assertOk(res, 'GAME 목록');
}

function keyword(data) {
  const game = pick(data.games);
  const res = http.get(
    `${BASE_URL}/api/games${query({
      keyword: scenarioKeyword(game),
      size: 20,
    })}`,
    requestParams('game-keyword')
  );
  assertOk(res, 'GAME 키워드');
}

function complexFilter() {
  const res = http.get(
    `${BASE_URL}/api/games${query({
      playerCountMin: 2,
      playerCountMax: 4,
      playTime: ['OVER_30_TO_60', 'OVER_60_UNDER_90'],
      complexityMin: '2.00',
      complexityMax: '4.00',
      size: 20,
    })}`,
    requestParams('game-filter-complex')
  );
  assertOk(res, 'GAME 복합 필터');
}

function relation(data) {
  const metadata = data.metadata;
  const candidates = [];

  if (metadata.mechanisms.length) {
    candidates.push({ mechanism: pick(metadata.mechanisms) });
  }
  if (metadata.categories.length) {
    candidates.push({ category: pick(metadata.categories) });
  }
  if (metadata.themes.length) {
    candidates.push({
      theme: pick(metadata.themes),
      themeMatch: 'ANY',
    });
  }

  if (!candidates.length) {
    throw new Error('관계형 필터 후보가 없어 시나리오를 실행할 수 없습니다.');
  }

  const res = http.get(
    `${BASE_URL}/api/games${query({
      ...pick(candidates),
      size: 20,
    })}`,
    requestParams('game-relation')
  );
  assertOk(res, 'GAME 관계형 필터');
}

function upcoming() {
  const res = http.get(
    `${BASE_URL}/api/games${query({
      upcomingOnly: true,
      size: 20,
    })}`,
    requestParams('game-upcoming')
  );
  assertOk(res, 'GAME 예정 모임');
}

function personalized() {
  if (!SESSION_COOKIE) {
    list();
    return;
  }

  const filter = Math.random() < 0.5 ? 'PLAYED_ONLY' : 'EXCLUDE_PLAYED';

  const res = http.get(
    `${BASE_URL}/api/games${query({
      playedFilter: filter,
      size: 20,
    })}`,
    requestParams('game-personalized', true)
  );
  assertOk(res, 'GAME 개인화');
}

function detail(data) {
  const game = pick(data.games);
  const res = http.get(
    `${BASE_URL}/api/games/${game.id}`,
    requestParams('game-detail')
  );
  assertOk(res, 'GAME 상세');
}

export default function (data) {
  const r = Math.random();

  // 실제 사용 패턴 가정
  // 목록 25%, 키워드 15%, 복합 필터 20%, 관계형 15%,
  // upcoming 10%, 개인화 5%, 상세 10%
  if (r < 0.25) list();
  else if (r < 0.40) keyword(data);
  else if (r < 0.60) complexFilter();
  else if (r < 0.75) relation(data);
  else if (r < 0.85) upcoming();
  else if (r < 0.90) personalized();
  else detail(data);

  think(0.5, 2.0);
}
