import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGames = vi.fn();
const getGameSearch = vi.fn();
const getGameMechanisms = vi.fn();
const getGameCategories = vi.fn();
const getGameThemes = vi.fn();

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  api: {
    getGames: (...parameters) => getGames(...parameters),
    getGameSearch: (...parameters) => getGameSearch(...parameters),
    getGameMechanisms: (...parameters) => getGameMechanisms(...parameters),
    getGameCategories: (...parameters) => getGameCategories(...parameters),
    getGameThemes: (...parameters) => getGameThemes(...parameters),
    getMyProfile: vi.fn(),
    getNotifications: vi.fn(),
    getUnreadNotificationCount: vi.fn()
  },
  clearCsrfToken: vi.fn(),
  messageForError: () => '요청을 처리하지 못했어요.',
  setUnauthenticatedHandler: vi.fn()
}));

const { GamesView } = await import('./game/index.js');

const EMPTY_PAGE = { content: [], page: 0, size: 24, hasNext: false };
const SEARCH_HIT = {
  content: [{ id: 1, name: '협동 게임', englishName: 'Co-op Game', supportedPlayerCount: '2~4명', estimatedPlayTime: '30분', complexity: 2, upcomingRoomCount: 0 }],
  page: 0,
  size: 24,
  hasNext: false
};

async function renderGamesView(gameQuery = '') {
  const onGameQueryChange = vi.fn();
  const view = render(
    <GamesView title="게임 찾기" gameQuery={gameQuery} onGameQueryChange={onGameQueryChange} dataVersion={0} />
  );
  await act(async () => {});
  return { ...view, onGameQueryChange };
}

function submitQuery(value) {
  const input = screen.getByPlaceholderText(/게임 이름 또는 예:/);
  fireEvent.change(input, { target: { value } });
  fireEvent.submit(input.closest('form'));
}

beforeEach(() => {
  getGames.mockReset();
  getGames.mockResolvedValue(EMPTY_PAGE);
  getGameSearch.mockReset();
  getGameSearch.mockResolvedValue(EMPTY_PAGE);
  getGameMechanisms.mockReset();
  getGameMechanisms.mockResolvedValue([]);
  getGameCategories.mockReset();
  getGameCategories.mockResolvedValue([]);
  getGameThemes.mockReset();
  getGameThemes.mockResolvedValue([]);
});

afterEach(() => {
  cleanup();
});

describe('T1 검색어 없이 열면 기존 인기순 목록을 그대로 쓴다', () => {
  it('탭 없이 검색창 하나만 있고 초기 조회는 GAME-01로 간다', async () => {
    await renderGamesView();

    expect(getGames).toHaveBeenCalled();
    expect(getGameSearch).not.toHaveBeenCalled();
    expect(screen.getByPlaceholderText(/게임 이름 또는 예:/)).toBeTruthy();
  });
});

describe('T2 검색어 제출', () => {
  it('검색창에 입력해 제출하면 부모 상태로 올린다', async () => {
    const { onGameQueryChange } = await renderGamesView();

    submitQuery('가족과 짧게 할 협력 게임');

    expect(onGameQueryChange).toHaveBeenCalledWith('가족과 짧게 할 협력 게임');
  });

  it('부모가 올려준 검색어가 있으면 검색 API를 호출한다', async () => {
    getGameSearch.mockResolvedValue(SEARCH_HIT);
    await renderGamesView('가족과 짧게 할 협력 게임');

    expect(getGameSearch).toHaveBeenCalledWith(
      expect.objectContaining({ query: '가족과 짧게 할 협력 게임', page: 0, size: 24 }),
      expect.anything()
    );
    expect(getGames).not.toHaveBeenCalled();
    expect(screen.getByText('협동 게임')).toBeTruthy();
  });
});

describe('T7 searchMode 미노출 — 구현 방식 배너를 보여주지 않는다', () => {
  it('응답 데이터에 searchMode가 섞여 들어와도 프런트는 무시하고 화면에 표시하지 않는다', async () => {
    getGameSearch.mockResolvedValue({ ...SEARCH_HIT, searchMode: 'LEXICAL_FALLBACK' });
    await renderGamesView('가벼운 파티 게임');

    expect(screen.queryByText(/LEXICAL/)).toBeNull();
    expect(screen.queryByText(/FALLBACK/)).toBeNull();
  });
});

describe('T5 재검색 중 로딩 표시', () => {
  it('이미 결과가 있는 상태에서 검색어를 바꾸면 검색 중임을 알린다', async () => {
    getGameSearch.mockResolvedValue(SEARCH_HIT);
    const { rerender, onGameQueryChange } = await renderGamesView('첫 검색어');
    expect(screen.getByText('협동 게임')).toBeTruthy();
    expect(screen.getByText('게임 목록')).toBeTruthy();

    let resolveSecond;
    getGameSearch.mockReturnValue(new Promise((resolve) => { resolveSecond = resolve; }));
    rerender(<GamesView title="게임 찾기" gameQuery="두번째 검색어" onGameQueryChange={onGameQueryChange} dataVersion={0} />);
    await act(async () => {});

    // 이전 결과를 지우지 않고 로딩 중임을 알린다.
    expect(screen.getByText('검색하는 중')).toBeTruthy();
    expect(screen.getByText('협동 게임')).toBeTruthy();

    await act(async () => { resolveSecond(SEARCH_HIT); });
    expect(screen.getByText('게임 목록')).toBeTruthy();
  });
});

describe('T4 검색 결과 없음 안내 문구', () => {
  it('검색어로 조회했지만 결과가 없으면 다른 표현을 안내한다', async () => {
    getGameSearch.mockResolvedValue(EMPTY_PAGE);
    await renderGamesView('아무도 없는 조건');

    expect(screen.getByText('검색 결과가 없어요')).toBeTruthy();
    expect(screen.getByText('다른 표현이나 조건으로 다시 시도해보세요.')).toBeTruthy();
  });

  it('검색어 없이 결과가 없으면 기존 이름 검색 안내 문구를 보여준다', async () => {
    getGames.mockResolvedValue(EMPTY_PAGE);
    await renderGamesView();

    expect(screen.getByText('검색 결과가 없어요')).toBeTruthy();
    expect(screen.getByText('게임 이름의 일부만 넣어보세요.')).toBeTruthy();
  });
});
