import http from 'k6/http';
import {
  BASE_URL,
  assertOk,
  fetchGameFixture,
  keywordFromGame,
  pick,
  query,
  requestParams,
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
};

export function setup() {
  return { games: fetchGameFixture() };
}

export default function (data) {
  const game = pick(data.games);
  const keyword = keywordFromGame(game);

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
