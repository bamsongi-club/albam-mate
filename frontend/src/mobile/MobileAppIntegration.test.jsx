import React from 'react';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
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
  vi.spyOn(api, 'getMyProfile').mockResolvedValue({ id: 1, nickname: '테스터', profileImageUrl: null });
  vi.spyOn(api, 'getSocialProviders').mockResolvedValue([]);
  vi.spyOn(api, 'getNotifications').mockResolvedValue(page([]));
  vi.spyOn(api, 'getUnreadNotificationCount').mockResolvedValue({ unreadCount: 0 });
  vi.spyOn(api, 'getRooms').mockResolvedValue(page([]));
  vi.spyOn(api, 'getGames').mockResolvedValue(page([]));
  const getMyRooms = vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
  return { getMyRooms };
}

function stubViewport(width) {
  vi.stubGlobal('matchMedia', vi.fn().mockImplementation((query) => ({
    matches: query === '(max-width: 767px)' && width <= 767,
    media: query,
    addEventListener: vi.fn(),
    removeEventListener: vi.fn()
  })));
}

describe('모바일 앱 셸', () => {
  it('로그인한 홈에서 내 모임 요약, 전체 채팅 진입, 네 개 탭을 함께 배선한다', async () => {
    stubHomeDependencies();
    stubViewport(767);
    vi.stubGlobal('scrollTo', vi.fn());
    window.location.hash = '#/home';

    render(<App />);

    await waitFor(() => expect(screen.getByText('예정된 모임이 없어요.')).toBeTruthy());
    expect(screen.getByLabelText('모바일 주요 메뉴')).toBeTruthy();
    expect(screen.getByRole('link', { name: '전체 채팅' }).getAttribute('href')).toBe('#/chats');
    expect(document.querySelector('.mobile-page-title')?.textContent).toBe('홈');
  });

  it.each([768, 1280])('%ipx에서는 모바일 홈 패널과 내 모임 조회를 추가하지 않는다', async (width) => {
    const { getMyRooms } = stubHomeDependencies();
    stubViewport(width);
    vi.stubGlobal('scrollTo', vi.fn());
    window.location.hash = '#/home';

    render(<App />);

    await waitFor(() => expect(screen.getByLabelText('테스터 프로필')).toBeTruthy());
    expect(screen.queryByText('예정된 모임이 없어요.')).toBeNull();
    expect(getMyRooms).not.toHaveBeenCalled();
  });
});
