import React from 'react';
import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const getGames = vi.fn();
const getGame = vi.fn();
const getRooms = vi.fn();
const markGamePlayed = vi.fn();
const unmarkGamePlayed = vi.fn();
const getMyRooms = vi.fn();

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
    getGameCategories: vi.fn().mockResolvedValue([]),
    getGameThemes: vi.fn().mockResolvedValue([]),
    markGamePlayed: (...parameters) => markGamePlayed(...parameters),
    unmarkGamePlayed: (...parameters) => unmarkGamePlayed(...parameters),
    getMyRooms: (...parameters) => getMyRooms(...parameters),
    getMyProfile: vi.fn(),
    getNotifications: vi.fn(),
    getUnreadNotificationCount: vi.fn()
  },
  clearCsrfToken: vi.fn(),
  messageForError: (error, fallback = '요청을 처리하지 못했어요.') => error?.message || fallback,
  setUnauthenticatedHandler: vi.fn()
}));

const { GamesView, GameDetailView } = await import('./game/index.js');
const { ProfileView } = await import('./main.jsx');

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
  return screen.getByRole('button', { name: /해봤어요|저장 중…/ });
}

function openFilterPanel() {
  fireEvent.click(screen.getByRole('button', { name: /게임 필터/ }));
}

// 관계 필터는 검색창 아래 칩 줄에서 고른다.
function playedFilterOption(label) {
  return screen.getByRole('button', { name: label });
}

function isSelected(chip) {
  return chip.getAttribute('aria-pressed') === 'true';
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
    expect(screen.getByRole('button', { name: '해봤어요 ✓' })).toBeTruthy();
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
    expect(screen.getByRole('button', { name: '저장 중…' })).toBeTruthy();

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

  it('상세 화면의 모임 만들기 버튼은 선택한 게임을 생성 화면으로 넘긴다', async () => {
    const onCreateGame = vi.fn();
    await renderGameDetailView({ onCreateGame });

    fireEvent.click(screen.getByRole('button', { name: '이 게임으로 모임 만들기' }));

    expect(onCreateGame).toHaveBeenCalledWith(expect.objectContaining({ id: '7', title: '루미큐브' }));
  });

  it('상세 응답의 테마와 메커니즘을 빠짐없이 표시한다', async () => {
    getGame.mockResolvedValue({
      ...GAME,
      themes: [{ code: 'FANTASY', nameKo: '판타지' }, { code: 'WAR', nameKo: '전쟁' }],
      mechanisms: [{ code: 'DICE_ROLLING', nameKo: '주사위 굴리기' }, { code: 'HAND_MANAGEMENT', nameKo: '핸드 관리' }]
    });
    await renderGameDetailView();

    expect(screen.getByLabelText('게임 카테고리와 테마, 메커니즘')).toBeTruthy();
    expect(screen.queryByText('특징')).toBeNull();
    expect(screen.getByText('판타지')).toBeTruthy();
    expect(screen.getByText('전쟁')).toBeTruthy();
    expect(screen.getByText('주사위 굴리기')).toBeTruthy();
    expect(screen.getByText('핸드 관리')).toBeTruthy();
  });

  it('상세 상단에 영어명·출시 연도·권장 나이와 해 본 게임 조작을 함께 표시한다', async () => {
    getGame.mockResolvedValue({ ...GAME, releaseYear: 1990, minAge: 8 });
    await renderGameDetailView();

    expect(screen.getByText('Rummikub · 1990')).toBeTruthy();
    // 권장 연령은 인원·플레이 시간과 같은 줄의 항목으로 둔다.
    expect(screen.getByText('권장 연령')).toBeTruthy();
    expect(screen.getByText('8세+')).toBeTruthy();
    expect(screen.getByRole('button', { name: '해봤어요' })).toBeTruthy();
  });
});

describe('내정보의 해 본 게임 진입점', () => {
  it('해 본 게임 수를 통계로 세고 목록 진입점을 남긴다', async () => {
    getGames.mockResolvedValue({ content: [], page: 0, size: 1, totalElements: 19, totalPages: 19 });
    getMyRooms.mockResolvedValue({ content: [], page: 0, size: 1, totalElements: 4, totalPages: 4 });

    render(<ProfileView me={{ nickname: '테스터', email: 'tester@example.com' }} onSave={vi.fn()} onLogout={vi.fn()} />);
    await act(async () => {});

    expect(getGames).toHaveBeenCalledWith(
      expect.objectContaining({ playedFilter: 'PLAYED_ONLY', page: 0, size: 1 }),
      expect.any(AbortSignal)
    );
    expect(screen.getByText('19')).toBeTruthy();
    expect(screen.getByRole('link', { name: /해 본 게임/ }).getAttribute('href')).toBe('#/game-list/played');
  });
});

describe('T8 해 본 게임 검색 필터', () => {
  it('전체는 관계 필터를 보내지 않는다', async () => {
    await renderGamesView();
    openFilterPanel();

    expect(isSelected(playedFilterOption('전체'))).toBe(true);
    expect(lastQuery().playedFilter).toBe('');
  });

  it('해 본 게임만과 해 본 게임 제외는 각각 한 값만 보낸다', async () => {
    await renderGamesView();
    openFilterPanel();

    fireEvent.click(playedFilterOption('해 본 게임만'));
    expect(lastQuery().playedFilter).toBe('PLAYED_ONLY');

    fireEvent.click(playedFilterOption('해 본 게임 제외'));
    expect(lastQuery().playedFilter).toBe('EXCLUDE_PLAYED');
    expect(isSelected(playedFilterOption('해 본 게임만'))).toBe(false);
    expect(isSelected(playedFilterOption('전체'))).toBe(false);
  });

  it('해 본 게임만 필터 중 표시를 취소하면 목록을 다시 불러온다', async () => {
    getGames.mockResolvedValue(gamePage({ ...GAME, playedByMe: true }));
    unmarkGamePlayed.mockResolvedValue({ gameId: 7, playedByMe: false });
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(playedFilterOption('해 본 게임만'));
    const callsBeforeToggle = getGames.mock.calls.length;

    await act(async () => { fireEvent.click(playedToggle()); });

    expect(getGames.mock.calls.length).toBeGreaterThan(callsBeforeToggle);
  });

  it('해 본 게임 제외 필터 중 표시하면 목록을 다시 불러온다', async () => {
    getGames.mockResolvedValue(gamePage({ ...GAME, playedByMe: false }));
    markGamePlayed.mockResolvedValue({ gameId: 7, playedByMe: true });
    await renderGamesView();
    openFilterPanel();
    fireEvent.click(playedFilterOption('해 본 게임 제외'));
    const callsBeforeToggle = getGames.mock.calls.length;

    await act(async () => { fireEvent.click(playedToggle()); });

    expect(getGames.mock.calls.length).toBeGreaterThan(callsBeforeToggle);
  });

  it('전체 상태에서는 표시·취소해도 목록을 다시 불러오지 않는다', async () => {
    markGamePlayed.mockResolvedValue({ gameId: 7, playedByMe: true });
    await renderGamesView();
    const callsBeforeToggle = getGames.mock.calls.length;

    await act(async () => { fireEvent.click(playedToggle()); });

    expect(getGames.mock.calls.length).toBe(callsBeforeToggle);
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

  it('목록 토글의 401은 공통 인증 실패 흐름에 그대로 넘긴다', async () => {
    const unauthenticated = new TestApiError({ status: 401, code: 'UNAUTHENTICATED', message: '로그인이 필요합니다.' });
    markGamePlayed.mockRejectedValue(unauthenticated);
    const onPlayedError = vi.fn();
    await renderGamesView({ onPlayedError });

    await act(async () => { fireEvent.click(playedToggle()); });

    expect(playedToggle().disabled).toBe(false);
    expect(onPlayedError).toHaveBeenCalledWith(unauthenticated, expect.any(String));
  });

  it('상세 토글의 401도 같은 흐름으로 넘긴다', async () => {
    const unauthenticated = new TestApiError({ status: 401, code: 'UNAUTHENTICATED', message: '로그인이 필요합니다.' });
    markGamePlayed.mockRejectedValue(unauthenticated);
    const onPlayedError = vi.fn();
    await renderGameDetailView({ onPlayedError });

    await act(async () => { fireEvent.click(playedToggle()); });

    expect(onPlayedError).toHaveBeenCalledWith(unauthenticated, expect.any(String));
  });
});
