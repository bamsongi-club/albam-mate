import http from 'k6/http';
import { check, sleep } from 'k6';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:5173';
const CONCURRENCY = Number(__ENV.CONCURRENCY || '2');
const DURATION = __ENV.DURATION || '30s';
const RUN_ID = __ENV.RUN_ID || 'game-list-867-local';
const PHASE = __ENV.PHASE || `vus-${CONCURRENCY}`;
const WORKLOAD = __ENV.WORKLOAD || 'mixed';
const WORKLOADS = ['mixed', 'base', 'relation', 'complex'];

if (!Number.isInteger(CONCURRENCY) || CONCURRENCY <= 0) {
  throw new Error(`CONCURRENCY는 양의 정수여야 합니다: ${CONCURRENCY}`);
}

if (!WORKLOADS.includes(WORKLOAD)) {
  throw new Error(`WORKLOAD는 mixed, base, relation, complex 중 하나여야 합니다: ${WORKLOAD}`);
}

export const options = {
  scenarios: {
    gameList: {
      executor: 'constant-vus',
      vus: CONCURRENCY,
      duration: DURATION,
      gracefulStop: '5s',
    },
  },
  thresholds: {
    checks: ['rate==1'],
    http_req_failed: ['rate<0.01'],
  },
};

function requestParams(name) {
  return {
    headers: {
      'X-Albam-Mate-Measurement-Id': `${RUN_ID}-${PHASE}`,
    },
    tags: { name },
  };
}

function query(params) {
  return Object.entries(params)
    .filter(([, value]) => value !== undefined && value !== null && value !== '')
    .flatMap(([key, value]) => (Array.isArray(value) ? value.map((item) => [key, item]) : [[key, value]]))
    .map(([key, value]) => `${encodeURIComponent(key)}=${encodeURIComponent(value)}`)
    .join('&');
}

function readJson(response) {
  try {
    return response.json();
  } catch (_error) {
    return null;
  }
}

function checkSlice(response, label) {
  const payload = readJson(response);
  const data = payload?.data;
  const keys = data && typeof data === 'object' ? Object.keys(data).sort().join(',') : '';
  return check(response, {
    [`${label}: HTTP 200`]: (value) => value.status === 200,
    [`${label}: Slice keys`]: () => keys === 'content,hasNext,page,size',
    [`${label}: content array`]: () => Array.isArray(data?.content),
    [`${label}: page metadata`]: () => Number.isInteger(data?.page)
      && Number.isInteger(data?.size)
      && data.size > 0
      && typeof data.hasNext === 'boolean'
      && Array.isArray(data?.content)
      && data.content.length <= data.size,
  });
}

function getGameList(params, name) {
  const suffix = query(params);
  const response = http.get(`${BASE_URL}/api/games${suffix ? `?${suffix}` : ''}`, requestParams(name));
  checkSlice(response, name);
}

function metadataCode(path, label) {
  const response = http.get(`${BASE_URL}${path}`, requestParams(`setup-${label}`));
  if (response.status !== 200) {
    throw new Error(`${label} metadata 요청이 실패했습니다: ${response.status}`);
  }
  const data = readJson(response)?.data;
  const code = Array.isArray(data) ? data.find((item) => typeof item?.code === 'string')?.code : null;
  if (!code) {
    throw new Error(`${label} metadata code가 없습니다.`);
  }
  return code;
}

export function setup() {
  const smoke = http.get(`${BASE_URL}/api/games?page=0&size=1`, requestParams('setup-game-list'));
  if (!checkSlice(smoke, 'setup-game-list')) {
    throw new Error('게임 목록 Slice preflight가 실패했습니다.');
  }
  return {
    theme: metadataCode('/api/game-themes', 'theme'),
    mechanism: metadataCode('/api/game-mechanisms', 'mechanism'),
  };
}

export default function (data) {
  const selection = WORKLOAD === 'mixed' ? Math.random() : null;
  if (WORKLOAD === 'base' || (selection !== null && selection < 0.34)) {
    getGameList({ page: 0, size: 24 }, 'game-list-base');
  } else if (WORKLOAD === 'relation' || (selection !== null && selection < 0.67)) {
    getGameList({
      page: 0,
      size: 24,
      theme: data.theme,
      themeMatch: 'ANY',
      mechanism: data.mechanism,
      mechanismMatch: 'ANY',
    }, 'game-list-relation');
  } else if (WORKLOAD === 'complex' || selection !== null) {
    getGameList({
      page: 0,
      size: 24,
      playerCountMin: 2,
      playerCountMax: 4,
      playTime: ['OVER_30_TO_60', 'OVER_60_UNDER_90'],
      complexityMin: '2.00',
      complexityMax: '4.00',
      theme: data.theme,
      mechanism: data.mechanism,
      themeMatch: 'ANY',
      mechanismMatch: 'ANY',
    }, 'game-list-complex');
  } else {
    throw new Error(`지원하지 않는 WORKLOAD입니다: ${WORKLOAD}`);
  }
  sleep(0.05);
}
