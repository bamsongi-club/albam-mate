import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGames = vi.fn();
const getGame = vi.fn();
const getRooms = vi.fn();
const markGamePlayed = vi.fn();
const unmarkGamePlayed = vi.fn();

class TestApiError extends Error {
  constructor({ status, code, message }) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
    this.code = code;
  }
}

vi.mock('./api', () => ({
  ApiError: TestApiError,
  api: {
    getGames: (...parameters) => getGames(...parameters),
    getGame: (...parameters) => getGame(...parameters),
    getRooms: (...parameters) => getRooms(...parameters),
    getGameMechanisms: vi.fn().mockResolvedValue([]),
    markGamePlayed: (...parameters) => markGamePlayed(...parameters),
    unmarkGamePlayed: (...parameters) => unmarkGamePlayed(...parameters),
    getMyProfile: vi.fn(),
    getNotifications: vi.fn(),
    getUnreadNotificationCount: vi.fn()
  },
  clearCsrfToken: vi.fn(),
  messageForError: (error, fallback = '요청을 처리하지 못했어요.') => error?.message || fallback,
  setUnauthenticatedHandler: vi.fn()
}));

const { GamesView, GameDetailView } = await import('./main.jsx');

const GAME = {
  id: 7,
  name: '루미큐브',
  englishName: 'Rummikub',
  supportedPlayerCount: '2~4명',
  estimatedPlayTime: '30분',
  complexity: 1.8,
  upcomingRoomCount: 0,
  playedByMe: false
};

function gamePage(game) {
  return { content: [game], page: 0, size: 24, totalElements: 1, totalPages: 1 };
}

function emptyRoomPage() {
  return { content: [], page: 0, size: 10, totalElements: 0, totalPages: 0 };
}

async function renderGamesView(props = {}) {
  const view = render(
    <GamesView title="게임 찾기" gameQuery="" onGameQueryChange={vi.fn()} dataVersion={0} {...props} />
  );
  await act(async () => {});
  return view;
}

async function renderGameDetailView(props = {}) {
  const view = render(<GameDetailView gameId="7" onCreateGame={vi.fn()} dataVersion={0} {...props} />);
  await act(async () => {});
  return view;
}

function playedToggle() {
  return screen.getByRole('button', { name: '해봤어요' });
}

function openFilterPanel() {
  fireEvent.click(screen.getByLabelText('조건 필터'));
}

// 게임 난이도 조건도 `전체`를 제공하므로 관계 필터는 그룹 안에서 찾는다.
function playedFilterOption(label) {
  return within(screen.getByRole('group', { name: '해 본 게임' })).getByLabelText(label);
}

function lastQuery() {
  return getGames.mock.calls.at(-1)[0];
}

beforeEach(() => {
  getGames.mockReset();
  getGames.mockResolvedValue(gamePage(GAME));
  getGame.mockReset();
  getGame.mockResolvedValue(GAME);
  getRooms.mockReset();
  getRooms.mockResolvedValue(emptyRoomPage());
  markGamePlayed.mockReset();
  unmarkGamePlayed.mockReset();
});

afterEach(cleanup);

describe('T7 해 본 게임 표시와 취소', () => {
  it('목록 카드에서 표시하면 서버 성공 응답의 본인 상태를 반영한다', async () => {
    markGamePlayed.mockResolvedValue({ gameId: 7, playedByMe: true });
    await renderGamesView();

    expect(playedToggle().getAttribute('aria-pressed')).toBe('false');
    await act(async () => { fireEvent.click(playedToggle()); });

    expect(markGamePlayed).toHaveBeenCalledWith('7');
    expect(playedToggle().getAttribute('aria-pressed')).toBe('true');
  });

  it('이미 표시한 게임을 다시 누르면 취소를 요청하고 결과를 반영한다', async () => {
    getGames.mockResolvedValue(gamePage({ ...GAME, playedByMe: true }));
    unmarkGamePlayed.mockResolvedValue({ gameId: 7, playedByMe: false });
    await renderGamesView();

    expect(playedToggle().getAttribute('aria-pressed')).toBe('true');
    await act(async () => { fireEvent.click(playedToggle()); });

    expect(unmarkGamePlayed).toHaveBeenCalledWith('7');
    expect(playedToggle().getAttribute('aria-pressed')).toBe('false');
  });

  it('요청이 끝나기 전에는 그 조작을 잠근다', async () => {
    let resolveMark;
    markGamePlayed.mockReturnValue(new Promise((resolve) => { resolveMark = resolve; }));
    await renderGamesView();

    await act(async () => { fireEvent.click(playedToggle()); });
    expect(playedToggle().disabled).toBe(true);

    await act(async () => { resolveMark({ gameId: 7, playedByMe: true }); });
    expect(playedToggle().disabled).toBe(false);
  });

  it('실패하면 이전 화면 상태를 유지하고 공통 오류 흐름에 넘긴다', async () => {
    const failure = new TestApiError({ status: 500, code: 'INTERNAL_ERROR', message: '표시하지 못했어요.' });
    markGamePlayed.mockRejectedValue(failure);
    const onPlayedError = vi.fn();
    await renderGamesView({ onPlayedError });

    await act(async () => { fireEvent.click(playedToggle()); });

    expect(playedToggle().getAttribute('aria-pressed')).toBe('false');
    expect(playedToggle().disabled).toBe(false);
    expect(onPlayedError).toHaveBeenCalledWith(failure, expect.any(String));
  });

  it('상세 화면에서도 같은 표시·취소 조작을 제공한다', async () => {
    markGamePlayed.mockResolvedValue({ gameId: 7, playedByMe: true });
    await renderGameDetailView();

    await act(async () => { fireEvent.click(playedToggle()); });

    expect(markGamePlayed).toHaveBeenCalledWith('7');
    expect(playedToggle().getAttribute('aria-pressed')).toBe('true');
  });
});

describe('T8 해 본 게임 검색 필터', () => {
  it('전체는 관계 필터를 보내지 않는다', async () => {
    await renderGamesView();
    openFilterPanel();

    expect(playedFilterOption('전체').checked).toBe(true);
    expect(lastQuery().playedFilter).toBe('');
  });

  it('해 본 게임만과 해 본 게임 제외는 각각 한 값만 보낸다', async () => {
    await renderGamesView();
    openFilterPanel();

    fireEvent.click(playedFilterOption('해 본 게임만'));
    expect(lastQuery().playedFilter).toBe('PLAYED_ONLY');

    fireEvent.click(playedFilterOption('해 본 게임 제외'));
    expect(lastQuery().playedFilter).toBe('EXCLUDE_PLAYED');
    expect(playedFilterOption('해 본 게임만').checked).toBe(false);
    expect(playedFilterOption('전체').checked).toBe(false);
  });
});

describe('T9 비로그인 사용자의 해 본 게임 처리', () => {
  it('관계를 판정하지 않은 상태를 해보지 않음으로 표시하지 않는다', async () => {
    getGames.mockResolvedValue(gamePage({ ...GAME, playedByMe: null }));
    await renderGamesView();

    expect(playedToggle().getAttribute('aria-pressed')).toBe('false');
    expect(screen.queryByText('해보지 않음')).toBeNull();
  });

  it('관계 필터의 401은 공통 로그인 필요 흐름으로 안내한다', async () => {
    await renderGamesView();
    openFilterPanel();
    getGames.mockRejectedValue(new TestApiError({ status: 401, code: 'UNAUTHENTICATED', message: '로그인이 필요합니다.' }));

    await act(async () => { fireEvent.click(playedFilterOption('해 본 게임만')); });

    expect(screen.getByText('로그인이 필요해요')).toBeTruthy();
    expect(screen.getByRole('link', { name: '로그인 또는 회원가입' })).toBeTruthy();
  });
});
