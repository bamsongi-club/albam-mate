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
    onMarkAllAsRead: vi.fn(),
    onRetrySynchronization: vi.fn(),
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

describe('#272 T1 낙관 읽음 표시', () => {
  it('서버 readAt을 만들지 않고 지정된 행만 읽음 스타일로 표시한다', () => {
    const notification = {
      id: 4,
      type: 'ROOM_CANCELED',
      roomId: 9,
      roomTitle: '낙관 표시 모임',
      readAt: null,
      createdAt: '2026-08-04T09:00:00+09:00'
    };
    renderPanel({
      notifications: [notification],
      optimisticReadIds: new Set([4])
    });

    const row = screen.getByRole('button', { name: /낙관 표시 모임/ });
    expect(row.classList.contains('read')).toBe(true);
    expect(notification.readAt).toBeNull();
  });
});

describe('#272 T4 일괄 읽음 동작', () => {
  it('처리 중에는 모두 읽음 버튼을 비활성화한다', () => {
    const { properties } = renderPanel({ canMarkAllAsRead: true });
    fireEvent.click(screen.getByRole('button', { name: '모두 읽음' }));
    expect(properties.onMarkAllAsRead).toHaveBeenCalledOnce();

    cleanup();
    renderPanel({ canMarkAllAsRead: true, bulkReadPending: true });
    expect(screen.getByRole('button', { name: '처리 중…' }).disabled).toBe(true);
  });
});

describe('#272 T8 동기화 오류', () => {
  it('마지막 목록과 함께 계약된 오류와 명시적인 재시도를 제공한다', () => {
    const { properties } = renderPanel({ synchronizationFailed: true });

    expect(screen.getByRole('alert').textContent).toContain(
      '알림 상태를 갱신하지 못했습니다. 다시 시도해 주세요.'
    );
    fireEvent.click(screen.getByRole('button', { name: '다시 시도' }));
    expect(properties.onRetrySynchronization).toHaveBeenCalledOnce();
  });
});
