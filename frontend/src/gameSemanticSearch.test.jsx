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

async function renderGamesView() {
  const view = render(
    <GamesView title="게임 찾기" gameQuery="" onGameQueryChange={vi.fn()} dataVersion={0} />
  );
  await act(async () => {});
  return view;
}

function switchToSemanticTab() {
  fireEvent.click(screen.getByRole('tab', { name: '의미로 검색' }));
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

describe('T1 검색 방식 기본값과 전환', () => {
  it('기본은 이름 검색이고 의미 검색 입력은 보이지 않는다', async () => {
    await renderGamesView();

    expect(screen.getByRole('tab', { name: '이름으로 검색' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.getByPlaceholderText('게임 이름으로 검색')).toBeTruthy();
    expect(screen.queryByPlaceholderText(/예: 가족과 짧게/)).toBeNull();
  });

  it('의미로 검색 탭을 누르면 이름 입력 대신 의미 검색 입력이 보인다', async () => {
    await renderGamesView();

    switchToSemanticTab();

    expect(screen.getByRole('tab', { name: '의미로 검색' }).getAttribute('aria-selected')).toBe('true');
    expect(screen.queryByPlaceholderText('게임 이름으로 검색')).toBeNull();
    expect(screen.getByPlaceholderText(/예: 가족과 짧게/)).toBeTruthy();
  });
});

describe('T2 의미 검색 제출 전 상태', () => {
  it('아직 제출하지 않았으면 core를 호출하지 않고 안내 문구만 보여준다', async () => {
    await renderGamesView();
    getGamesSemanticSearch.mockClear();

    switchToSemanticTab();
    await act(async () => {});

    expect(getGamesSemanticSearch).not.toHaveBeenCalled();
    expect(screen.getByText('찾고 싶은 게임을 문장으로 설명해보세요')).toBeTruthy();
  });
});

describe('T3 의미 검색 조회', () => {
  it('의미 검색어를 제출하면 query와 페이지 파라미터로 조회한다', async () => {
    getGamesSemanticSearch.mockResolvedValue(SEMANTIC_HIT);
    await renderGamesView();
    switchToSemanticTab();

    fireEvent.change(screen.getByPlaceholderText(/예: 가족과 짧게/), { target: { value: '가족과 짧게 할 협력 게임' } });
    fireEvent.submit(screen.getByPlaceholderText(/예: 가족과 짧게/).closest('form'));
    await act(async () => {});

    expect(getGamesSemanticSearch).toHaveBeenCalledWith(
      expect.objectContaining({ query: '가족과 짧게 할 협력 게임', page: 0, size: 24 }),
      expect.anything()
    );
    expect(screen.getByText('협동 게임')).toBeTruthy();
  });
});

describe('T4 fallback 상태 표시', () => {
  it('searchMode가 LEXICAL_FALLBACK이면 대체 안내를 보여준다', async () => {
    getGamesSemanticSearch.mockResolvedValue(FALLBACK_HIT);
    await renderGamesView();
    switchToSemanticTab();

    fireEvent.change(screen.getByPlaceholderText(/예: 가족과 짧게/), { target: { value: '가벼운 파티 게임' } });
    fireEvent.submit(screen.getByPlaceholderText(/예: 가족과 짧게/).closest('form'));
    await act(async () => {});

    expect(screen.getByText('키워드 검색 결과로 대신 보여드려요')).toBeTruthy();
  });
});

describe('T5 의미 검색 결과 없음', () => {
  it('제출했지만 결과가 없으면 이름 검색과 다른 안내 문구를 보여준다', async () => {
    getGamesSemanticSearch.mockResolvedValue({ ...EMPTY_PAGE, searchMode: 'SEMANTIC' });
    await renderGamesView();
    switchToSemanticTab();

    fireEvent.change(screen.getByPlaceholderText(/예: 가족과 짧게/), { target: { value: '아무도 없는 조건' } });
    fireEvent.submit(screen.getByPlaceholderText(/예: 가족과 짧게/).closest('form'));
    await act(async () => {});

    expect(screen.getByText('검색 결과가 없어요')).toBeTruthy();
    expect(screen.getByText('다른 표현이나 조건으로 다시 시도해보세요.')).toBeTruthy();
  });
});
