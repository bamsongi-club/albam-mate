import React from 'react';
import { cleanup, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';
import { mobileTabForRoute, MobileBottomNavigation } from './MobileNavigation';

afterEach(cleanup);

describe('MobileBottomNavigation', () => {
  it('로그인 사용자의 게임 탭을 활성화하고 네 개의 주요 탭을 제공한다', () => {
    render(<MobileBottomNavigation route="game-list" authenticated />);

    expect(screen.getByRole('navigation', { name: '모바일 주요 메뉴' })).toBeTruthy();
    expect(screen.getByRole('link', { name: '게임' }).getAttribute('aria-current')).toBe('page');
    expect(screen.getByRole('link', { name: '내 모임' }).getAttribute('href')).toBe('#/my');
    expect(screen.getByRole('link', { name: '내정보' }).getAttribute('href')).toBe('#/profile');
    expect(screen.getAllByRole('link')).toHaveLength(4);
  });

  it('비로그인 사용자의 내 모임과 내정보 탭을 로그인 화면으로 보낸다', () => {
    render(<MobileBottomNavigation route="home" authenticated={false} />);

    expect(screen.getByRole('link', { name: '내 모임' }).getAttribute('href')).toBe('#/auth');
    expect(screen.getByRole('link', { name: '내정보' }).getAttribute('href')).toBe('#/auth');
  });

  it('모임과 채팅의 세부 route를 내 모임 탭으로 묶는다', () => {
    expect(mobileTabForRoute('session')).toBe('my');
    expect(mobileTabForRoute('chat')).toBe('my');
    expect(mobileTabForRoute('chats')).toBe('my');
  });
});
