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

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

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
  vi.unstubAllGlobals();
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

describe('#272 T4~T6 읽음 동기화 generation', () => {
  it('polling을 멈추고 이전 GET을 취소·폐기한 뒤 새 generation 응답만 적용한다', async () => {
    const staleList = deferred();
    const staleUnread = deferred();
    const freshList = deferred();
    const freshUnread = deferred();
    const loadNotifications = vi.fn()
      .mockReturnValueOnce(staleList.promise)
      .mockReturnValueOnce(freshList.promise)
      .mockResolvedValue(FIRST_PAGE);
    const loadUnreadCount = vi.fn()
      .mockReturnValueOnce(staleUnread.promise)
      .mockReturnValueOnce(freshUnread.promise)
      .mockResolvedValue({ unreadCount: 1 });
    const polling = renderPolling({ panelOpen: true, loadNotifications, loadUnreadCount });
    await flushRequests();
    const staleListSignal = loadNotifications.mock.calls[0][0];
    const staleUnreadSignal = loadUnreadCount.mock.calls[0][0];

    act(() => polling.result.current.pauseForReadSynchronization());
    expect(staleListSignal.aborted).toBe(true);
    expect(staleUnreadSignal.aborted).toBe(true);

    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    expect(loadNotifications).toHaveBeenCalledOnce();
    expect(loadUnreadCount).toHaveBeenCalledOnce();

    let refreshPromise;
    act(() => {
      refreshPromise = polling.result.current.refreshAfterReadSynchronization();
    });
    await flushRequests();
    staleList.resolve({ content: [{ ...FIRST_PAGE.content[0], roomTitle: '오래된 모임' }] });
    staleUnread.resolve({ unreadCount: 8 });
    freshList.resolve({ content: [{ ...FIRST_PAGE.content[0], roomTitle: '새 모임' }] });
    freshUnread.resolve({ unreadCount: 1 });

    let synchronized;
    await act(async () => {
      synchronized = await refreshPromise;
    });
    expect(synchronized).toBe(true);
    expect(polling.result.current.notifications[0].roomTitle).toBe('새 모임');
    expect(polling.result.current.unreadCount).toBe(1);

    act(() => polling.result.current.resumeAfterReadSynchronization());
    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    expect(loadNotifications).toHaveBeenCalledTimes(3);
    expect(loadUnreadCount).toHaveBeenCalledTimes(3);
  });

  it('abort signal이 바뀌지 않아도 이전 generation 응답을 폐기한다', async () => {
    const abort = vi.fn();
    class StableSignalAbortController {
      constructor() {
        this.signal = { aborted: false };
      }

      abort() {
        abort();
      }
    }
    vi.stubGlobal('AbortController', StableSignalAbortController);
    const staleList = deferred();
    const staleUnread = deferred();
    const polling = renderPolling({
      loadNotifications: vi.fn().mockReturnValue(staleList.promise),
      loadUnreadCount: vi.fn().mockReturnValue(staleUnread.promise)
    });
    await flushRequests();

    act(() => polling.result.current.pauseForReadSynchronization());
    expect(abort).toHaveBeenCalledTimes(2);

    staleList.resolve({ content: [{ ...FIRST_PAGE.content[0], roomTitle: '폐기할 모임' }] });
    staleUnread.resolve({ unreadCount: 9 });
    await flushRequests();

    expect(polling.result.current.notifications).toEqual([]);
    expect(polling.result.current.unreadCount).toBeNull();
  });

  it('겹친 동기화 작업이 각자 획득한 pause를 모두 해제한 뒤에만 polling한다', async () => {
    const polling = renderPolling({ panelOpen: true });
    await flushRequests();
    const initialListCalls = polling.loadNotifications.mock.calls.length;
    const initialUnreadCalls = polling.loadUnreadCount.mock.calls.length;

    act(() => {
      polling.result.current.pauseForReadSynchronization();
      polling.result.current.pauseForReadSynchronization();
      polling.result.current.resumeAfterReadSynchronization();
    });
    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    expect(polling.loadNotifications).toHaveBeenCalledTimes(initialListCalls);
    expect(polling.loadUnreadCount).toHaveBeenCalledTimes(initialUnreadCalls);

    act(() => polling.result.current.resumeAfterReadSynchronization());
    act(() => vi.advanceTimersByTime(NOTIFICATION_POLL_INTERVAL_MS));
    await flushRequests();
    expect(polling.loadNotifications).toHaveBeenCalledTimes(initialListCalls + 1);
    expect(polling.loadUnreadCount).toHaveBeenCalledTimes(initialUnreadCalls + 1);
  });
});

describe('#272 T8 읽음 재동기화 실패', () => {
  it('목록만 실패하고 count가 성공하면 false를 반환하고 마지막 성공 목록을 유지한다', async () => {
    const loadNotifications = vi.fn()
      .mockResolvedValueOnce(FIRST_PAGE)
      .mockRejectedValueOnce(new Error('list failed'));
    const loadUnreadCount = vi.fn()
      .mockResolvedValueOnce({ unreadCount: 2 })
      .mockResolvedValueOnce({ unreadCount: 1 });
    const polling = renderPolling({ loadNotifications, loadUnreadCount });
    await flushRequests();

    act(() => polling.result.current.pauseForReadSynchronization());
    let synchronized;
    await act(async () => {
      synchronized = await polling.result.current.refreshAfterReadSynchronization();
    });

    expect(synchronized).toBe(false);
    expect(polling.result.current.notifications).toEqual(FIRST_PAGE.content);
    expect(polling.result.current.unreadCount).toBe(1);
  });

  it('목록과 count가 모두 성공할 때 true를 반환하고 두 상태를 갱신한다', async () => {
    const refreshedPage = {
      content: [{ ...FIRST_PAGE.content[0], readAt: '2026-08-04T10:00:00+09:00' }]
    };
    const loadNotifications = vi.fn()
      .mockResolvedValueOnce(FIRST_PAGE)
      .mockResolvedValueOnce(refreshedPage);
    const loadUnreadCount = vi.fn()
      .mockResolvedValueOnce({ unreadCount: 2 })
      .mockResolvedValueOnce({ unreadCount: 0 });
    const polling = renderPolling({ loadNotifications, loadUnreadCount });
    await flushRequests();

    act(() => polling.result.current.pauseForReadSynchronization());
    let synchronized;
    await act(async () => {
      synchronized = await polling.result.current.refreshAfterReadSynchronization();
    });

    expect(synchronized).toBe(true);
    expect(polling.result.current.notifications).toEqual(refreshedPage.content);
    expect(polling.result.current.unreadCount).toBe(0);
  });
});
