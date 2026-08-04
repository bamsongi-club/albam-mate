import { act, cleanup, renderHook } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { useNotificationReadSync } from './useNotificationReadSync';

const UNREAD_NOTIFICATION = {
  id: 7,
  type: 'ROOM_CANCELED',
  roomId: 3,
  roomTitle: '테스트 모임',
  readAt: null
};
const READ_NOTIFICATION = {
  ...UNREAD_NOTIFICATION,
  readAt: '2026-08-04T09:00:00+09:00'
};

function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((promiseResolve, promiseReject) => {
    resolve = promiseResolve;
    reject = promiseReject;
  });
  return { promise, resolve, reject };
}

function renderReadSync(overrides = {}) {
  const controls = {
    markNotificationRead: overrides.markNotificationRead
      || vi.fn().mockResolvedValue(READ_NOTIFICATION),
    markAllNotificationsRead: overrides.markAllNotificationsRead
      || vi.fn().mockResolvedValue({ updatedCount: 1, boundaryNotificationId: 7 }),
    replaceNotification: vi.fn(),
    refreshUnreadAfterSingleRead: overrides.refreshUnreadAfterSingleRead
      || vi.fn().mockResolvedValue(true),
    pauseForReadSynchronization: vi.fn(),
    refreshAfterReadSynchronization: overrides.refreshAfterReadSynchronization
      || vi.fn().mockResolvedValue(true),
    resumeAfterReadSynchronization: vi.fn(),
    isUnauthenticated: vi.fn().mockReturnValue(false)
  };
  const hook = renderHook(
    ({ enabled, unreadCount }) => useNotificationReadSync({
      enabled,
      unreadCount,
      ...controls
    }),
    {
      initialProps: {
        enabled: overrides.enabled ?? true,
        unreadCount: overrides.unreadCount ?? 3
      }
    }
  );
  return { ...hook, ...controls };
}

afterEach(cleanup);

describe('#272 T1 단건 낙관 표시', () => {
  it('PATCH 완료를 기다리지 않고 해당 행과 배지를 먼저 읽음으로 표시한다', async () => {
    const readRequest = deferred();
    const sync = renderReadSync({
      markNotificationRead: vi.fn().mockReturnValue(readRequest.promise)
    });
    let requestPromise;

    act(() => {
      requestPromise = sync.result.current.markAsRead(UNREAD_NOTIFICATION);
    });

    expect(sync.result.current.optimisticReadIds.has(7)).toBe(true);
    expect(sync.result.current.visibleUnreadCount).toBe(2);
    expect(sync.markNotificationRead).toHaveBeenCalledWith(7);

    readRequest.resolve(READ_NOTIFICATION);
    await act(async () => requestPromise);
  });
});

describe('#272 T2 단건 성공', () => {
  it('서버 응답 항목을 적용하고 권위 있는 미확인 수를 다시 조회한다', async () => {
    const sync = renderReadSync();

    await act(async () => sync.result.current.markAsRead(UNREAD_NOTIFICATION));

    expect(sync.replaceNotification).toHaveBeenCalledWith(READ_NOTIFICATION);
    expect(sync.refreshUnreadAfterSingleRead).toHaveBeenCalledOnce();
    expect(sync.result.current.optimisticReadIds.has(7)).toBe(false);
    expect(sync.result.current.synchronizationFailed).toBe(false);
  });
});

describe('#272 T3 단건 실패 복구', () => {
  it('낙관 표시를 취소하고 목록과 미확인 수의 서버 재동기화를 요청한다', async () => {
    const sync = renderReadSync({
      markNotificationRead: vi.fn().mockRejectedValue(new Error('PATCH failed'))
    });

    await act(async () => sync.result.current.markAsRead(UNREAD_NOTIFICATION));

    expect(sync.result.current.optimisticReadIds.has(7)).toBe(false);
    expect(sync.pauseForReadSynchronization).toHaveBeenCalledOnce();
    expect(sync.refreshAfterReadSynchronization).toHaveBeenCalledOnce();
    expect(sync.resumeAfterReadSynchronization).toHaveBeenCalledOnce();
  });
});

describe('#272 T4~T7 일괄 읽음', () => {
  it('중복 제출을 막고 PATCH 뒤의 권위 재조회가 끝날 때까지 polling을 멈춘다', async () => {
    const bulkRequest = deferred();
    const refreshRequest = deferred();
    const sync = renderReadSync({
      markAllNotificationsRead: vi.fn().mockReturnValue(bulkRequest.promise),
      refreshAfterReadSynchronization: vi.fn().mockReturnValue(refreshRequest.promise)
    });
    let firstRequest;

    act(() => {
      firstRequest = sync.result.current.markAllAsRead();
      sync.result.current.markAllAsRead();
    });

    expect(sync.markAllNotificationsRead).toHaveBeenCalledOnce();
    expect(sync.pauseForReadSynchronization).toHaveBeenCalledOnce();
    expect(sync.result.current.bulkReadPending).toBe(true);

    bulkRequest.resolve({ updatedCount: 99, boundaryNotificationId: 9999 });
    await act(async () => Promise.resolve());
    expect(sync.refreshAfterReadSynchronization).toHaveBeenCalledOnce();
    expect(sync.replaceNotification).not.toHaveBeenCalled();
    expect(sync.resumeAfterReadSynchronization).not.toHaveBeenCalled();

    refreshRequest.resolve(true);
    await act(async () => firstRequest);
    expect(sync.resumeAfterReadSynchronization).toHaveBeenCalledOnce();
    expect(sync.result.current.bulkReadPending).toBe(false);
  });

  it('일괄 읽음이 시작되면 앞선 단건 PATCH의 늦은 응답을 화면에 적용하지 않는다', async () => {
    const singleRequest = deferred();
    const bulkRequest = deferred();
    const sync = renderReadSync({
      markNotificationRead: vi.fn().mockReturnValue(singleRequest.promise),
      markAllNotificationsRead: vi.fn().mockReturnValue(bulkRequest.promise)
    });
    let singlePromise;
    let bulkPromise;

    act(() => {
      singlePromise = sync.result.current.markAsRead(UNREAD_NOTIFICATION);
      bulkPromise = sync.result.current.markAllAsRead();
    });

    singleRequest.resolve(READ_NOTIFICATION);
    await act(async () => singlePromise);
    expect(sync.replaceNotification).not.toHaveBeenCalled();
    expect(sync.refreshUnreadAfterSingleRead).not.toHaveBeenCalled();

    bulkRequest.resolve({ updatedCount: 1, boundaryNotificationId: 7 });
    await act(async () => bulkPromise);
  });

  it('PATCH가 실패해도 목록과 미확인 수를 다시 조회한 뒤 polling을 재개한다', async () => {
    const sync = renderReadSync({
      markAllNotificationsRead: vi.fn().mockRejectedValue(new Error('PATCH failed'))
    });

    await act(async () => sync.result.current.markAllAsRead());

    expect(sync.refreshAfterReadSynchronization).toHaveBeenCalledOnce();
    expect(sync.resumeAfterReadSynchronization).toHaveBeenCalledOnce();
  });
});

describe('#272 T8 재동기화 실패', () => {
  it('실패 상태를 노출하고 명시적인 재시도로 복구한다', async () => {
    const refreshAfterReadSynchronization = vi.fn()
      .mockResolvedValueOnce(false)
      .mockResolvedValueOnce(true);
    const sync = renderReadSync({ refreshAfterReadSynchronization });

    await act(async () => sync.result.current.markAllAsRead());
    expect(sync.result.current.synchronizationFailed).toBe(true);

    await act(async () => sync.result.current.retrySynchronization());
    expect(refreshAfterReadSynchronization).toHaveBeenCalledTimes(2);
    expect(sync.result.current.synchronizationFailed).toBe(false);
  });
});
