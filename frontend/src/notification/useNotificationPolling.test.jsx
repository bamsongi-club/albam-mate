import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  NOTIFICATION_POLL_INTERVAL_MS,
  useNotificationPolling
} from './useNotificationPolling';

const FIRST_PAGE = {
  content: [{ id: 1, type: 'ROOM_CANCELED', roomId: 7, roomTitle: '테스트 모임', readAt: null }]
};

let hidden;

async function flushRequests() {
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
  });
}

function renderPolling(overrides = {}) {
  const loadNotifications = overrides.loadNotifications || vi.fn().mockResolvedValue(FIRST_PAGE);
  const loadUnreadCount = overrides.loadUnreadCount || vi.fn().mockResolvedValue({ unreadCount: 2 });
  const onBackgroundError = overrides.onBackgroundError || vi.fn();
  const initialProps = {
    enabled: overrides.enabled ?? true,
    panelOpen: overrides.panelOpen ?? false
  };
  const hook = renderHook(
    ({ enabled, panelOpen }) => useNotificationPolling({
      enabled,
      panelOpen,
      loadNotifications,
      loadUnreadCount,
      onBackgroundError
    }),
    { initialProps }
  );
  return { ...hook, loadNotifications, loadUnreadCount, onBackgroundError };
}

beforeEach(() => {
  vi.useFakeTimers();
  hidden = false;
  Object.defineProperty(document, 'visibilityState', {
    configurable: true,
    get: () => (hidden ? 'hidden' : 'visible')
  });
});

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('T1 세션 시작 조회', () => {
  it('로그인 또는 세션 복구로 enabled가 되면 목록 첫 페이지와 미확인 수를 즉시 조회한다', async () => {
    const polling = renderPolling({ enabled: false });
    expect(polling.loadNotifications).not.toHaveBeenCalled();
    expect(polling.loadUnreadCount).not.toHaveBeenCalled();

    polling.rerender({ enabled: true, panelOpen: false });
    await flushRequests();

    expect(polling.loadNotifications).toHaveBeenCalledOnce();
    expect(polling.loadUnreadCount).toHaveBeenCalledOnce();
    expect(polling.result.current.notifications).toEqual(FIRST_PAGE.content);
    expect(polling.result.current.unreadCount).toBe(2);
  });
});

describe('T2 polling과 중복 방지', () => {
  it('닫힌 알림함에서는 미확인 수만 polling하고 열린 뒤에는 두 조회를 polling한다', async () => {
    const polling = renderPolling();
    await flushRequests();

    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    expect(polling.loadNotifications).toHaveBeenCalledTimes(1);
    expect(polling.loadUnreadCount).toHaveBeenCalledTimes(2);

    polling.rerender({ enabled: true, panelOpen: true });
    await flushRequests();
    expect(polling.loadNotifications).toHaveBeenCalledTimes(2);
    expect(polling.loadUnreadCount).toHaveBeenCalledTimes(3);

    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    expect(polling.loadNotifications).toHaveBeenCalledTimes(3);
    expect(polling.loadUnreadCount).toHaveBeenCalledTimes(4);
  });

  it('같은 종류의 진행 중 요청을 다음 tick에서 겹쳐 보내지 않는다', async () => {
    let resolveUnread;
    const loadUnreadCount = vi.fn()
      .mockResolvedValueOnce({ unreadCount: 2 })
      .mockImplementationOnce(() => new Promise((resolve) => { resolveUnread = resolve; }));
    const polling = renderPolling({ loadUnreadCount });
    await flushRequests();

    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    expect(loadUnreadCount).toHaveBeenCalledTimes(2);

    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    expect(loadUnreadCount).toHaveBeenCalledTimes(2);

    resolveUnread({ unreadCount: 1 });
    await flushRequests();
    expect(polling.result.current.unreadCount).toBe(1);
  });
});

describe('T3 탭 가시성과 알림함 상태', () => {
  it('숨김 상태에는 polling을 멈추고 재가시화 때 열린 알림함과 미확인 수를 즉시 조회한다', async () => {
    const polling = renderPolling({ panelOpen: true });
    await flushRequests();
    const initialListCalls = polling.loadNotifications.mock.calls.length;
    const initialUnreadCalls = polling.loadUnreadCount.mock.calls.length;

    hidden = true;
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS * 2));
    await flushRequests();
    expect(polling.loadNotifications).toHaveBeenCalledTimes(initialListCalls);
    expect(polling.loadUnreadCount).toHaveBeenCalledTimes(initialUnreadCalls);

    hidden = false;
    act(() => document.dispatchEvent(new Event('visibilitychange')));
    await flushRequests();
    expect(polling.loadNotifications).toHaveBeenCalledTimes(initialListCalls + 1);
    expect(polling.loadUnreadCount).toHaveBeenCalledTimes(initialUnreadCalls + 1);

    polling.rerender({ enabled: false, panelOpen: true });
    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS * 2));
    await flushRequests();
    expect(polling.loadNotifications).toHaveBeenCalledTimes(initialListCalls + 1);
    expect(polling.loadUnreadCount).toHaveBeenCalledTimes(initialUnreadCalls + 1);
  });
});

describe('T4 배지의 마지막 성공값', () => {
  it('미확인 수 polling 실패에도 마지막 성공값을 유지하고 같은 오류는 반복 보고하지 않는다', async () => {
    const onBackgroundError = vi.fn();
    const loadUnreadCount = vi.fn()
      .mockResolvedValueOnce({ unreadCount: 4 })
      .mockRejectedValue(new Error('temporary'));
    const polling = renderPolling({ loadUnreadCount, onBackgroundError });
    await flushRequests();

    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();

    expect(polling.result.current.unreadCount).toBe(4);
    expect(onBackgroundError).toHaveBeenCalledOnce();
  });
});

describe('T7 로딩과 실패 복구', () => {
  it('최초 목록 실패 뒤 명시적인 retry로 빈 목록 성공 상태에 도달한다', async () => {
    const loadNotifications = vi.fn()
      .mockRejectedValueOnce(new Error('initial failure'))
      .mockResolvedValueOnce({ content: [] });
    const polling = renderPolling({ loadNotifications });
    await flushRequests();
    expect(polling.result.current.listStatus).toBe('error');

    await act(async () => polling.result.current.retry());
    expect(polling.result.current.listStatus).toBe('ready');
    expect(polling.result.current.notifications).toEqual([]);
  });

  it('기존 목록 뒤 polling 실패는 목록을 유지하고 반복 오류를 억제한다', async () => {
    const onBackgroundError = vi.fn();
    const loadNotifications = vi.fn()
      .mockResolvedValueOnce(FIRST_PAGE)
      .mockRejectedValue(new Error('poll failure'));
    const polling = renderPolling({ panelOpen: true, loadNotifications, onBackgroundError });
    await flushRequests();

    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();

    expect(polling.result.current.listStatus).toBe('ready');
    expect(polling.result.current.notifications).toEqual(FIRST_PAGE.content);
    expect(onBackgroundError).toHaveBeenCalledOnce();
  });
});
