import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NotificationPanel } from './NotificationPanel';

afterEach(cleanup);

function renderPanel(overrides = {}) {
  const properties = {
    open: true,
    notifications: [],
    listStatus: 'ready',
    onClose: vi.fn(),
    onRetry: vi.fn(),
    onSelectNotification: vi.fn(),
    ...overrides
  };
  return { properties, ...render(<NotificationPanel {...properties} />) };
}

describe('T5 알림 패널 표시', () => {
  it('roomTitle을 HTML이 아닌 일반 텍스트로 렌더링한다', () => {
    const unsafeTitle = '<img src=x onerror=alert(1)>';
    const { container } = renderPanel({
      notifications: [{
        id: 1,
        type: 'PARTICIPANT_JOINED',
        roomId: 4,
        roomTitle: unsafeTitle,
        readAt: null,
        createdAt: '2026-08-03T09:00:00+09:00'
      }]
    });

    expect(screen.getByText(`'${unsafeTitle}' 모임에 새 참가자가 있어요.`)).toBeTruthy();
    expect(container.querySelector('img')).toBeNull();
  });
});

describe('T7 알림 패널 상태', () => {
  it('최초 로딩과 빈 목록을 구분한다', () => {
    const { rerender, properties } = renderPanel({ listStatus: 'loading' });
    expect(screen.getByText('알림을 불러오는 중입니다.')).toBeTruthy();

    rerender(<NotificationPanel {...properties} listStatus="ready" />);
    expect(screen.getByText('새로운 알림이 없습니다.')).toBeTruthy();
  });

  it('최초 실패에 명시적인 재시도 동작을 제공한다', () => {
    const { properties } = renderPanel({ listStatus: 'error' });

    expect(screen.getByText('알림을 불러오지 못했습니다.')).toBeTruthy();
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(properties.onRetry).toHaveBeenCalledOnce();
  });
});
