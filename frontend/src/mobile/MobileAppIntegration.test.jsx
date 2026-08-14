import React from 'react';
import { act, cleanup, render, screen, waitFor } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { api } from '../api';
import { App } from '../main';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  cleanup();
  window.location.hash = '';
});

function page(content) {
  return {
    content,
    page: 0,
    size: 100,
    totalElements: content.length,
    totalPages: 1
  };
}

function stubHomeDependencies() {
  vi.spyOn(api, 'getMyProfile').mockResolvedValue({ id: 1, nickname: '테스터', email: 'tester@example.com', profileImageUrl: null });
  vi.spyOn(api, 'getSocialProviders').mockResolvedValue([]);
  vi.spyOn(api, 'getNotifications').mockResolvedValue(page([]));
  vi.spyOn(api, 'getUnreadNotificationCount').mockResolvedValue({ unreadCount: 0 });
  vi.spyOn(api, 'getRooms').mockResolvedValue(page([]));
  const getGames = vi.spyOn(api, 'getGames').mockResolvedValue(page([]));
  vi.spyOn(api, 'getGameRankings').mockResolvedValue({ overall: [], pastWeek: [] });
  const getMyRooms = vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
  return { getGames, getMyRooms };
}

function lastGameQuery(getGames) {
  return getGames.mock.calls[getGames.mock.calls.length - 1][0];
}

async function renderApp(hash) {
  vi.stubGlobal('scrollTo', vi.fn());
  window.location.hash = hash;
  const view = render(<App />);
  await act(async () => {});
  return view;
}

describe('모바일 앱 셸', () => {
  it('로그인한 홈에서 내 모임 요약, 알림·채팅 진입, 네 개 탭을 함께 배선한다', async () => {
    stubHomeDependencies();

    await renderApp('#/home');

    await waitFor(() => expect(screen.getByRole('heading', { name: '예정된 모임이 없어요' })).toBeTruthy());
    const tabs = screen.getByLabelText('모바일 주요 메뉴');
    expect([...tabs.querySelectorAll('a')].map((tab) => tab.textContent)).toEqual(['홈', '게임', '모임 찾기', '내정보']);
    expect(screen.getByRole('link', { name: '전체 채팅' }).getAttribute('href')).toBe('#/chats');
    expect(screen.getByRole('button', { name: '알림함' })).toBeTruthy();
  });

  it('세션 확인이 끝나기 전에는 스플래시를 덮어 둔다', async () => {
    stubHomeDependencies();
    vi.spyOn(api, 'getMyProfile').mockReturnValue(new Promise(() => {}));
    vi.stubGlobal('scrollTo', vi.fn());
    window.location.hash = '#/home';

    render(<App />);

    expect(screen.getByRole('status', { name: '알밤메이트를 여는 중' })).toBeTruthy();
  });

  it('해 본 게임과 게임 찾기를 오가면 route에 맞는 조건으로 다시 조회한다', async () => {
    const { getGames } = stubHomeDependencies();

    await renderApp('#/game-list/played');
    await waitFor(() => expect(lastGameQuery(getGames).playedFilter).toBe('PLAYED_ONLY'));

    await act(async () => { window.location.hash = '#/game-list'; });
    await waitFor(() => expect(lastGameQuery(getGames).playedFilter).toBeFalsy());

    await act(async () => { window.location.hash = '#/game-list/played'; });
    await waitFor(() => expect(lastGameQuery(getGames).playedFilter).toBe('PLAYED_ONLY'));
  });

  it('하위 화면에서는 하단 탭바를 감춘다', async () => {
    stubHomeDependencies();
    vi.spyOn(api, 'getGame').mockResolvedValue({ id: 7, name: '루미큐브', themes: [], mechanisms: [], categories: [] });

    await renderApp('#/game/7');

    await waitFor(() => expect(screen.getByRole('heading', { name: '루미큐브' })).toBeTruthy());
    expect(screen.queryByLabelText('모바일 주요 메뉴')).toBeNull();
  });
});
