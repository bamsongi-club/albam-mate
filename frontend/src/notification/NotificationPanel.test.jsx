import React from 'react';
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { NotificationPanel } from './NotificationPanel';

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

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

function ModalHarness({ open, onClose }) {
  return (
    <>
      <button type="button">알림 열기</button>
      <main>알림함 뒤 본문</main>
      <NotificationPanel
        open={open}
        isModal
        notifications={[]}
        listStatus="ready"
        canMarkAllAsRead
        onClose={onClose}
        onRetry={vi.fn()}
        onSelectNotification={vi.fn()}
        onMarkAllAsRead={vi.fn()}
        onRetrySynchronization={vi.fn()}
      />
    </>
  );
}

describe('#499 T8 자동 승격 알림 표시와 선택', () => {
  it('확정 문구를 일반 텍스트로 렌더링하고 기존 선택 흐름에 전달한다', () => {
    const unsafeTitle = '<img src=x onerror=alert(1)>';
    const notification = {
        id: 1,
        type: 'WAITLIST_PROMOTED',
        roomId: 4,
        roomTitle: unsafeTitle,
        readAt: null,
        createdAt: '2026-08-03T09:00:00+09:00'
    };
    const { container, properties } = renderPanel({
      notifications: [notification]
    });

    const message = screen.getByText(`'${unsafeTitle}' 모임 대기에서 참가자로 확정됐어요.`);
    const notificationButton = message.closest('button');
    expect(notificationButton).toBeTruthy();
    expect(container.querySelector('img')).toBeNull();
    fireEvent.click(notificationButton);
    expect(properties.onSelectNotification).toHaveBeenCalledWith(notification);
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
    const selectedNotification = {
      id: 4,
      type: 'ROOM_CANCELED',
      roomId: 9,
      roomTitle: '낙관 표시 모임',
      readAt: null,
      createdAt: '2026-08-04T09:00:00+09:00'
    };
    const otherNotification = {
      ...selectedNotification,
      id: 5,
      roomTitle: '다른 미확인 모임'
    };
    renderPanel({
      notifications: [selectedNotification, otherNotification],
      optimisticReadIds: new Set([4])
    });

    const selectedRow = screen.getByRole('button', { name: /낙관 표시 모임/ });
    const otherRow = screen.getByRole('button', { name: /다른 미확인 모임/ });
    expect(selectedRow.classList.contains('read')).toBe(true);
    expect(otherRow.classList.contains('unread')).toBe(true);
    expect(selectedNotification.readAt).toBeNull();
    expect(otherNotification.readAt).toBeNull();
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

describe('모바일 알림 목록 구조', () => {
  it('상단 뒤로가기로 알림함을 닫는다', () => {
    const { properties } = renderPanel();

    fireEvent.click(screen.getByRole('button', { name: '알림함 닫기' }));

    expect(properties.onClose).toHaveBeenCalledOnce();
  });

  it('생성 시각과 읽음 상태를 날짜별 카드 흐름으로 구분한다', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-11T12:00:00+09:00'));

    const { container } = renderPanel({
      notifications: [
        {
          id: 11,
          type: 'PARTICIPANT_JOINED',
          roomId: 1,
          roomTitle: '오늘의 윙스팬',
          readAt: null,
          createdAt: '2026-08-11T18:05:00+09:00'
        },
        {
          id: 12,
          type: 'WAITLIST_PROMOTED',
          roomId: 2,
          roomTitle: '지난 주말 카탄',
          readAt: null,
          createdAt: '2026-08-08T18:31:00+09:00'
        },
        {
          id: 13,
          type: 'ROOM_CANCELED',
          roomId: 3,
          roomTitle: '이전 모임',
          readAt: '2026-08-03T19:00:00+09:00',
          createdAt: '2026-08-03T18:00:00+09:00'
        }
      ]
    });

    expect([...container.querySelectorAll('.notification-section-title')].map((heading) => heading.textContent))
      .toEqual(['오늘', '지난 7일', '이전 알림']);
    expect(container.querySelector('.notification-item-icon.green')).toBeTruthy();
    expect(container.querySelector('.notification-item.unread .notification-unread-dot')).toBeTruthy();
    expect(screen.getByRole('button', { name: /이전 모임/ }).classList.contains('read')).toBe(true);
  });

  it('전체 화면에서는 키보드 모달로 열고 닫으며 초점을 되돌린다', () => {
    const onClose = vi.fn();
    const { rerender } = render(<ModalHarness open={false} onClose={onClose} />);
    const trigger = screen.getByRole('button', { name: '알림 열기' });
    trigger.focus();

    rerender(<ModalHarness open onClose={onClose} />);

    const dialog = screen.getByRole('dialog', { name: '알림함' });
    const closeButton = screen.getByRole('button', { name: '알림함 닫기' });
    const readAllButton = screen.getByRole('button', { name: '모두 읽음' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(document.activeElement).toBe(closeButton);
    expect(document.querySelector('main')?.hasAttribute('inert')).toBe(true);

    readAllButton.focus();
    fireEvent.keyDown(readAllButton, { key: 'Tab' });
    expect(document.activeElement).toBe(closeButton);
    fireEvent.keyDown(closeButton, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(readAllButton);

    fireEvent.keyDown(readAllButton, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledOnce();

    rerender(<ModalHarness open={false} onClose={onClose} />);
    expect(document.activeElement).toBe(trigger);
    expect(document.querySelector('main')?.hasAttribute('inert')).toBe(false);
  });
});
