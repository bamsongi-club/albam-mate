import http from 'k6/http';
import { check } from 'k6';
import { BASE_URL, FIXED_KEYWORD, query, requestParams } from './common.js';

const expectedTotalElements = Number(__ENV.EXPECTED_TOTAL_ELEMENTS);

if (!FIXED_KEYWORD) {
  throw new Error('KEYWORD는 필수입니다.');
}
if (!Number.isSafeInteger(expectedTotalElements) || expectedTotalElements < 0) {
  throw new Error('EXPECTED_TOTAL_ELEMENTS는 0 이상의 정수여야 합니다.');
}

export const options = {
  vus: 1,
  iterations: 1,
  thresholds: {
    checks: ['rate==1'],
  },
};

export default function () {
  const response = http.get(
    `${BASE_URL}/api/games${query({
      keyword: FIXED_KEYWORD,
      page: 0,
      size: 20,
    })}`,
    requestParams('game-keyword-contract')
  );

  let body = null;
  try {
    body = response.json();
  } catch {
    // check에서 응답 구조 실패로 기록한다.
  }

  check(response, {
    'GAME 키워드 계약: status 200': () => response.status === 200,
    'GAME 키워드 계약: data.content array': () => Array.isArray(body?.data?.content),
    'GAME 키워드 계약: totalElements non-negative integer': () =>
      Number.isSafeInteger(body?.data?.totalElements) && body.data.totalElements >= 0,
    [`GAME 키워드 계약: totalElements ${expectedTotalElements}`]: () =>
      body?.data?.totalElements === expectedTotalElements,
  });
}
