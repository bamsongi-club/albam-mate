import http from 'k6/http';
import {
  BASE_URL,
  FIXED_KEYWORD,
  assertOk,
  fetchGameFixture,
  pick,
  query,
  requestParams,
  scenarioKeyword,
  think,
  thresholds,
} from './common.js';

export const options = {
  stages: [
    { duration: '30s', target: 10 },
    { duration: '1m', target: 30 },
    { duration: '3m', target: 30 },
    { duration: '30s', target: 0 },
  ],
  thresholds,
  tags: {
    keyword_mode: FIXED_KEYWORD ? 'fixed' : 'random-game-substring',
  },
};

export function setup() {
  if (FIXED_KEYWORD) {
    return { keyword: FIXED_KEYWORD };
  }
  return { games: fetchGameFixture() };
}

export default function (data) {
  const game = data.games ? pick(data.games) : null;
  const keyword = data.keyword || scenarioKeyword(game);

  const res = http.get(
    `${BASE_URL}/api/games${query({
      keyword,
      page: 0,
      size: 20,
    })}`,
    requestParams('game-keyword')
  );

  assertOk(res, 'GAME 키워드 검색');
  think();
}
