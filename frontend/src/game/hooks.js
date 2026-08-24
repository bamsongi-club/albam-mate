import { useEffect, useState } from 'react';
import { api } from '../api';
import { GAME_NUMBER_FILTER_DEBOUNCE_MS } from './constants';
import { gameFiltersWithoutPlayerCountNumbers } from './filterLogic';

/**
 * 해 본 게임 표시·취소를 서버 응답 기준으로 화면에 반영한다.
 *
 * 요청이 끝나기 전에는 같은 게임의 조작을 잠그고, 성공한 `200` 응답의 `playedByMe`만 반영한다.
 * 실패하면 이전 상태를 그대로 두고 공통 오류 흐름에 넘긴다.
 */
export function usePlayedGames(onError) {
  const [played, setPlayed] = useState({});
  const [pending, setPending] = useState({});
  // 활성 해 본 게임 필터(PLAYED_ONLY·EXCLUDE_PLAYED)가 표시·취소 뒤에도 목록·전체 건수와
  // 일치하도록, 성공할 때마다 올려 목록 조회 쪽에서 재조회 신호로 쓸 수 있게 한다.
  const [version, setVersion] = useState(0);
  const toggle = async (gameId, current) => {
    if (pending[gameId]) return;
    setPending((currentPending) => ({ ...currentPending, [gameId]: true }));
    try {
      const result = current ? await api.unmarkGamePlayed(gameId) : await api.markGamePlayed(gameId);
      setPlayed((currentPlayed) => ({ ...currentPlayed, [gameId]: result.playedByMe }));
      setVersion((currentVersion) => currentVersion + 1);
    } catch (error) {
      onError?.(error, '해 본 게임 표시를 바꾸지 못했어요.');
    } finally {
      setPending((currentPending) => ({ ...currentPending, [gameId]: false }));
    }
  };
  const stateOf = (game) => played[game.id] ?? game.playedByMe;
  return {
    stateOf,
    isPending: (game) => Boolean(pending[game.id]),
    toggle: (game) => toggle(game.id, stateOf(game)),
    version
  };
}

// 조회 조건에 따라 바뀌지 않는 선택지는 화면에 들어올 때 한 번만 불러온다.
export function useGameOptions(load) {
  const [options, setOptions] = useState([]);
  useEffect(() => {
    const controller = new AbortController();
    load(controller.signal).then((loaded) => setOptions(loaded || [])).catch(() => setOptions([]));
    return () => controller.abort();
  }, []);
  return options;
}

// 메커니즘 선택지는 조회 조건에 따라 바뀌지 않으므로 화면에 들어올 때 한 번만 불러온다.
export function useGameMechanisms() {
  return useGameOptions(api.getGameMechanisms);
}

/**
 * 조회에 실제로 쓸 게임 조건을 고른다.
 *
 * 숫자 입력만 바뀌면 마지막 입력 뒤에 조회한다. 체크박스처럼 다른 조건이 함께 바뀌면 기다리지 않는다.
 * 전용 인원을 고르면 인원 범위가 함께 지워지므로, 이때 숫자를 늦게 반영하면 계약이 금지한
 * 범위·전용 인원 조합을 한 번 요청하게 된다. 그래서 함께 바뀐 변경은 즉시 반영해야 한다.
 */
export function useAppliedGameFilters(filters) {
  const [applied, setApplied] = useState(filters);
  useEffect(() => {
    if (filters === applied) return undefined;
    if (gameFiltersWithoutPlayerCountNumbers(filters) !== gameFiltersWithoutPlayerCountNumbers(applied)) {
      setApplied(filters);
      return undefined;
    }
    const timer = setTimeout(() => setApplied(filters), GAME_NUMBER_FILTER_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [filters, applied]);
  return applied;
}
