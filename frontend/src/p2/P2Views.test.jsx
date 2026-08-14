import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import { App } from '../main';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  vi.useRealTimers();
  cleanup();
  window.location.hash = '';
});

function page(content) {
  return { content, page: 0, size: 100, totalElements: content.length, totalPages: 1 };
}

const GAMES = [
  { id: 1, name: '카탄', supportedPlayerCount: '3-4인', estimatedPlayTime: '75분' },
  { id: 2, name: '윙스팬', supportedPlayerCount: '1-5인', estimatedPlayTime: '70분' },
  { id: 3, name: '아줄', supportedPlayerCount: '2-4인', estimatedPlayTime: '35분' },
  { id: 4, name: '스플렌더', supportedPlayerCount: '2-4인', estimatedPlayTime: '30분' }
];

beforeEach(() => {
  vi.spyOn(api, 'getMyProfile').mockResolvedValue({ id: 1, nickname: '테스터', email: 'tester@example.com', profileImageUrl: null });
  vi.spyOn(api, 'getSocialProviders').mockResolvedValue([]);
  vi.spyOn(api, 'getNotifications').mockResolvedValue(page([]));
  vi.spyOn(api, 'getUnreadNotificationCount').mockResolvedValue({ unreadCount: 0 });
  vi.spyOn(api, 'getRooms').mockResolvedValue(page([]));
  vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
  vi.spyOn(api, 'getGameRankings').mockResolvedValue({ overall: [], pastWeek: [] });
  // 봇은 낱말을 keyword로 넘겨 게임을 찾는다. 실제 조회처럼 이름으로 걸러 준다.
  vi.spyOn(api, 'getGames').mockImplementation(({ keyword }) => (
    Promise.resolve(page(keyword ? GAMES.filter((game) => game.name.includes(keyword)) : GAMES))
  ));
});

async function renderApp(hash) {
  vi.stubGlobal('scrollTo', vi.fn());
  window.location.hash = hash;
  const view = render(<App />);
  await act(async () => {});
  return view;
}

async function go(hash) {
  await act(async () => { window.location.hash = hash; });
}

describe('P2 알밤봇 시안', () => {
  it('상단 화면에서만 FAB을 띄운다', async () => {
    await renderApp('#/home');
    await waitFor(() => expect(screen.getByRole('link', { name: '알밤봇 열기' })).toBeTruthy());

    await go('#/chats');

    await waitFor(() => expect(screen.queryByRole('link', { name: '알밤봇 열기' })).toBeNull());
  });

  it('확인 카드를 누르기 전에는 아무 화면으로도 넘어가지 않는다', async () => {
    await renderApp('#/bot');

    await act(async () => { screen.getByRole('button', { name: '윙스팬 모임 만들어줘' }).click(); });
    await waitFor(() => expect(screen.getByRole('heading', { name: '모임 만들기' })).toBeTruthy());

    // 카드만 떠 있고 라우트는 그대로다.
    expect(window.location.hash).toBe('#/bot');
    expect(screen.getByText('누를 때까지는 아무것도 실행되지 않아요.')).toBeTruthy();
    expect(screen.getByText('게임 · 윙스팬')).toBeTruthy();
  });

  it('확인하면 봇이 찾은 게임으로 모임 만들기 화면을 연다', async () => {
    await renderApp('#/bot');
    await act(async () => { screen.getByRole('button', { name: '윙스팬 모임 만들어줘' }).click(); });
    await waitFor(() => expect(screen.getByRole('button', { name: '이 조건으로 만들기' })).toBeTruthy());

    await act(async () => { screen.getByRole('button', { name: '이 조건으로 만들기' }).click(); });

    await waitFor(() => expect(window.location.hash).toBe('#/create'));
  });
});

describe('P2 실시간 온라인 매칭 시안', () => {
  it('홈 엔트리에서 매칭 화면으로 이어진다', async () => {
    await renderApp('#/home');

    await waitFor(() => expect(screen.getByRole('link', { name: /실시간 온라인 매칭/ }).getAttribute('href')).toBe('#/match'));
  });

  it('매칭을 시작하면 찾는 중을 거쳐 성사 화면으로 간다', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true });
    await renderApp('#/match');
    await waitFor(() => expect(screen.getByRole('button', { name: '매칭 시작하기' })).toBeTruthy());

    await act(async () => { screen.getByRole('button', { name: '매칭 시작하기' }).click(); });
    expect(screen.getByRole('button', { name: '매칭 취소' })).toBeTruthy();

    await act(async () => { await vi.advanceTimersByTimeAsync(4000); });

    expect(screen.getByRole('heading', { name: /모였어요/ })).toBeTruthy();
    expect(screen.getByRole('button', { name: /온라인 방 들어가기/ })).toBeTruthy();
  });

  it('실패 화면은 다시 시도와 오프라인 모임 보기를 함께 준다', async () => {
    await renderApp('#/match/failed');

    await waitFor(() => expect(screen.getByRole('heading', { name: '지금은 사람이 모이지 않았어요' })).toBeTruthy());
    expect(screen.getByRole('button', { name: '다시 시도' })).toBeTruthy();
    expect(screen.getByRole('button', { name: '오프라인 모임 보기' })).toBeTruthy();
  });

  it('온라인 방의 표 합계는 참가자 수와 같고, 정해야 아레나 CTA가 열린다', async () => {
    await renderApp('#/online-room');
    await waitFor(() => expect(screen.getByRole('button', { name: /카탄\s*\d+표/ })).toBeTruthy());

    const votes = screen.getAllByText(/^\d+표$/).map((node) => Number(node.textContent.replace('표', '')));
    expect(votes.reduce((sum, count) => sum + count, 0)).toBe(4);

    expect(screen.getByRole('button', { name: '게임을 먼저 정해주세요' }).disabled).toBe(true);

    await act(async () => { screen.getByRole('button', { name: /으로 정하기$/ }).click(); });

    expect(screen.getByRole('button', { name: /보드게임아레나에서 열기$/ }).disabled).toBe(false);
    expect(screen.getByText(/으로 정해졌어요/)).toBeTruthy();
  });
});
