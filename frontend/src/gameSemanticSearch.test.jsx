import React from 'react';
import { act, cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGames = vi.fn();
const getGamesSemanticSearch = vi.fn();
const getGameMechanisms = vi.fn();
const getGameCategories = vi.fn();
const getGameThemes = vi.fn();

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  api: {
    getGames: (...parameters) => getGames(...parameters),
    getGamesSemanticSearch: (...parameters) => getGamesSemanticSearch(...parameters),
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
const SEMANTIC_HIT = {
  content: [{ id: 1, name: '협동 게임', englishName: 'Co-op Game', supportedPlayerCount: '2~4명', estimatedPlayTime: '30분', complexity: 2, upcomingRoomCount: 0 }],
  page: 0,
  size: 24,
  hasNext: false,
  searchMode: 'SEMANTIC'
};
const FALLBACK_HIT = { ...SEMANTIC_HIT, searchMode: 'LEXICAL_FALLBACK' };

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
  getGamesSemanticSearch.mockReset();
  getGamesSemanticSearch.mockResolvedValue(EMPTY_PAGE);
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
    expect(getGamesSemanticSearch).not.toHaveBeenCalled();
    expect(screen.getByPlaceholderText(/게임 이름 또는 예:/)).toBeTruthy();
  });
});

describe('T2 검색어 제출', () => {
  it('검색창에 입력해 제출하면 부모 상태로 올린다', async () => {
    const { onGameQueryChange } = await renderGamesView();

    submitQuery('가족과 짧게 할 협력 게임');

    expect(onGameQueryChange).toHaveBeenCalledWith('가족과 짧게 할 협력 게임');
  });

  it('부모가 올려준 검색어가 있으면 의미 검색 API를 호출한다', async () => {
    getGamesSemanticSearch.mockResolvedValue(SEMANTIC_HIT);
    await renderGamesView('가족과 짧게 할 협력 게임');

    expect(getGamesSemanticSearch).toHaveBeenCalledWith(
      expect.objectContaining({ query: '가족과 짧게 할 협력 게임', page: 0, size: 24 }),
      expect.anything()
    );
    expect(getGames).not.toHaveBeenCalled();
    expect(screen.getByText('협동 게임')).toBeTruthy();
  });
});

describe('T3 fallback 상태 표시', () => {
  it('searchMode가 LEXICAL_FALLBACK이면 대체 안내를 보여준다', async () => {
    getGamesSemanticSearch.mockResolvedValue(FALLBACK_HIT);
    await renderGamesView('가벼운 파티 게임');

    expect(screen.getByText('키워드 검색 결과로 대신 보여드려요')).toBeTruthy();
  });

  it('검색어가 없을 때는 fallback 배너를 보여주지 않는다', async () => {
    getGames.mockResolvedValue(EMPTY_PAGE);
    await renderGamesView();

    expect(screen.queryByText('키워드 검색 결과로 대신 보여드려요')).toBeNull();
  });
});

describe('T4 검색 결과 없음 안내 문구', () => {
  it('검색어로 조회했지만 결과가 없으면 다른 표현을 안내한다', async () => {
    getGamesSemanticSearch.mockResolvedValue({ ...EMPTY_PAGE, searchMode: 'SEMANTIC' });
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

  it('대체 검색인데 결과도 없으면 안내를 하나로 합쳐 보여준다', async () => {
    getGamesSemanticSearch.mockResolvedValue({ ...EMPTY_PAGE, searchMode: 'LEXICAL_FALLBACK' });
    await renderGamesView('아무도 없는 조건');

    expect(screen.queryByText('키워드 검색 결과로 대신 보여드려요')).toBeNull();
    expect(screen.getByText('검색 결과가 없어요')).toBeTruthy();
    expect(screen.getByText('의미 검색을 잠시 사용할 수 없어 이름·조건 기반으로 찾아봤지만 결과가 없어요. 다른 표현이나 조건으로 다시 시도해보세요.')).toBeTruthy();
  });
});
