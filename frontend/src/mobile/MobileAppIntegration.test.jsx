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
  vi.spyOn(api, 'getMyRooms').mockResolvedValue(page([]));
}

describe('모바일 앱 셸', () => {
  it('로그인한 홈에서 내 모임 요약, 전체 채팅 진입, 네 개 탭을 함께 배선한다', async () => {
    stubHomeDependencies();
    vi.stubGlobal('scrollTo', vi.fn());
    window.location.hash = '#/home';

    render(<App />);

    await waitFor(() => expect(screen.getByText('예정된 모임이 없어요.')).toBeTruthy());
    expect(screen.getByLabelText('모바일 주요 메뉴')).toBeTruthy();
    expect(screen.getByRole('link', { name: '전체 채팅' }).getAttribute('href')).toBe('#/chats');
    expect(document.querySelector('.mobile-page-title')?.textContent).toBe('홈');
  });
});
