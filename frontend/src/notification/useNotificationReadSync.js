import { useCallback, useEffect, useRef, useState } from 'react';

export const NOTIFICATION_SYNC_ERROR_MESSAGE =
  '알림 상태를 갱신하지 못했습니다. 다시 시도해 주세요.';

function removeId(ids, notificationId) {
  const nextIds = new Set(ids);
  nextIds.delete(notificationId);
  return nextIds;
}

function removeIds(ids, notificationIds) {
  const nextIds = new Set(ids);
  notificationIds.forEach((notificationId) => nextIds.delete(notificationId));
  return nextIds;
}

function isCanceledRequest(error, isUnauthenticated) {
  return error?.name === 'AbortError' || isUnauthenticated(error);
}

export function useNotificationReadSync({
  enabled,
  unreadCount,
  unreadCountRevision = 0,
  readSynchronizationPaused = false,
  markNotificationRead,
  markAllNotificationsRead,
  replaceNotification,
  refreshUnreadAfterSingleRead,
  pauseForReadSynchronization,
  refreshAfterReadSynchronization,
  resumeAfterReadSynchronization,
  isUnauthenticated
}) {
  const [optimisticReadIds, setOptimisticReadIds] = useState(() => new Set());
  const [bulkReadPending, setBulkReadPending] = useState(false);
  const [synchronizationFailed, setSynchronizationFailed] = useState(false);
  const pendingSingleReadIdsRef = useRef(new Set());
  const confirmedReadIdsRef = useRef(new Set());
  const bulkReadPendingRef = useRef(false);
  // 일괄 읽음은 이전 단건 작업을, 세션 전환은 이전 사용자의 모든 작업을 무효화한다.
  const readOperationGenerationRef = useRef(0);
  const sessionGenerationRef = useRef(0);
  const observedUnreadCountRevisionRef = useRef(unreadCountRevision);

  const clearOptimisticRead = useCallback((notificationId) => {
    setOptimisticReadIds((currentIds) => removeId(currentIds, notificationId));
  }, []);

  const clearConfirmedRead = useCallback((notificationId) => {
    confirmedReadIdsRef.current.delete(notificationId);
    clearOptimisticRead(notificationId);
  }, [clearOptimisticRead]);

  const clearConfirmedOptimisticReads = useCallback(() => {
    const confirmedReadIds = new Set(confirmedReadIdsRef.current);
    confirmedReadIdsRef.current.clear();
    if (confirmedReadIds.size === 0) return;
    setOptimisticReadIds((currentIds) => removeIds(currentIds, confirmedReadIds));
  }, []);

  const operationIsCurrent = useCallback((sessionGeneration, operationGeneration) => (
    sessionGeneration === sessionGenerationRef.current
    && operationGeneration === readOperationGenerationRef.current
  ), []);

  const applySynchronizationResult = useCallback((synchronized) => {
    setSynchronizationFailed(!synchronized);
    if (synchronized) clearConfirmedOptimisticReads();
    return synchronized;
  }, [clearConfirmedOptimisticReads]);

  const applyBulkSynchronizationResult = useCallback((synchronized) => {
    confirmedReadIdsRef.current.clear();
    setOptimisticReadIds(new Set());
    setSynchronizationFailed(!synchronized);
    return synchronized;
  }, []);

  const synchronizeWithServer = useCallback(async () => {
    if (!enabled) return false;
    const sessionGeneration = sessionGenerationRef.current;
    const operationGeneration = readOperationGenerationRef.current;
    pauseForReadSynchronization();
    try {
      const synchronized = await refreshAfterReadSynchronization();
      if (!operationIsCurrent(sessionGeneration, operationGeneration)) return false;
      return applySynchronizationResult(synchronized);
    } finally {
      if (sessionGeneration === sessionGenerationRef.current) {
        resumeAfterReadSynchronization();
      }
    }
  }, [
    applySynchronizationResult,
    enabled,
    operationIsCurrent,
    pauseForReadSynchronization,
    refreshAfterReadSynchronization,
    resumeAfterReadSynchronization
  ]);

  const markAsRead = useCallback((notification) => {
    const notificationId = notification.id;
    if (
      !enabled
      || notification.readAt
      || bulkReadPendingRef.current
      || pendingSingleReadIdsRef.current.has(notificationId)
    ) {
      return Promise.resolve(false);
    }

    pendingSingleReadIdsRef.current.add(notificationId);
    const sessionGeneration = sessionGenerationRef.current;
    const operationGeneration = readOperationGenerationRef.current;
    setSynchronizationFailed(false);
    setOptimisticReadIds((currentIds) => new Set(currentIds).add(notificationId));

    return (async () => {
      try {
        const updatedNotification = await markNotificationRead(notificationId);
        if (!operationIsCurrent(sessionGeneration, operationGeneration)) return false;
        confirmedReadIdsRef.current.add(notificationId);
        replaceNotification(updatedNotification);

        const unreadCountRefreshed = await refreshUnreadAfterSingleRead();
        if (!operationIsCurrent(sessionGeneration, operationGeneration)) return false;
        setSynchronizationFailed(!unreadCountRefreshed);
        if (unreadCountRefreshed) clearConfirmedRead(notificationId);
        return true;
      } catch (error) {
        if (sessionGeneration === sessionGenerationRef.current) {
          clearConfirmedRead(notificationId);
        }
        if (!operationIsCurrent(sessionGeneration, operationGeneration)) return false;
        if (!isCanceledRequest(error, isUnauthenticated)) await synchronizeWithServer();
        return false;
      } finally {
        if (sessionGeneration === sessionGenerationRef.current) {
          pendingSingleReadIdsRef.current.delete(notificationId);
        }
      }
    })();
  }, [
    clearConfirmedRead,
    enabled,
    isUnauthenticated,
    markNotificationRead,
    operationIsCurrent,
    refreshUnreadAfterSingleRead,
    replaceNotification,
    synchronizeWithServer
  ]);

  const markAllAsRead = useCallback(() => {
    if (!enabled || bulkReadPendingRef.current) return Promise.resolve(false);

    bulkReadPendingRef.current = true;
    readOperationGenerationRef.current += 1;
    const sessionGeneration = sessionGenerationRef.current;
    const operationGeneration = readOperationGenerationRef.current;
    setBulkReadPending(true);
    setSynchronizationFailed(false);
    pauseForReadSynchronization();

    return (async () => {
      let mutationSucceeded = false;
      let shouldSynchronize = true;
      try {
        await markAllNotificationsRead();
        mutationSucceeded = true;
      } catch (error) {
        shouldSynchronize = operationIsCurrent(sessionGeneration, operationGeneration)
          && !isCanceledRequest(error, isUnauthenticated);
      }

      try {
        if (!operationIsCurrent(sessionGeneration, operationGeneration)) return false;
        if (!shouldSynchronize) return false;
        const synchronized = await refreshAfterReadSynchronization();
        if (!operationIsCurrent(sessionGeneration, operationGeneration)) return false;
        applyBulkSynchronizationResult(synchronized);
        return mutationSucceeded && synchronized;
      } finally {
        if (sessionGeneration === sessionGenerationRef.current) {
          resumeAfterReadSynchronization();
          bulkReadPendingRef.current = false;
          setBulkReadPending(false);
        }
      }
    })();
  }, [
    applyBulkSynchronizationResult,
    enabled,
    isUnauthenticated,
    markAllNotificationsRead,
    operationIsCurrent,
    pauseForReadSynchronization,
    refreshAfterReadSynchronization,
    resumeAfterReadSynchronization
  ]);

  useEffect(() => {
    sessionGenerationRef.current += 1;
    readOperationGenerationRef.current += 1;
    observedUnreadCountRevisionRef.current = unreadCountRevision;
    if (enabled) return;
    pendingSingleReadIdsRef.current.clear();
    confirmedReadIdsRef.current.clear();
    bulkReadPendingRef.current = false;
    setOptimisticReadIds(new Set());
    setBulkReadPending(false);
    setSynchronizationFailed(false);
  }, [enabled]);

  useEffect(() => {
    if (observedUnreadCountRevisionRef.current === unreadCountRevision) return;
    observedUnreadCountRevisionRef.current = unreadCountRevision;
    if (readSynchronizationPaused) return;
    if (confirmedReadIdsRef.current.size === 0) return;
    // 단건 직후 조회가 실패해도 다음 정상 polling count가 오면 낙관 차감을 끝낸다.
    clearConfirmedOptimisticReads();
    setSynchronizationFailed(false);
  }, [clearConfirmedOptimisticReads, readSynchronizationPaused, unreadCountRevision]);

  const visibleUnreadCount = unreadCount === null
    ? null
    : Math.max(0, unreadCount - optimisticReadIds.size);

  return {
    optimisticReadIds,
    visibleUnreadCount,
    bulkReadPending,
    synchronizationFailed,
    markAsRead,
    markAllAsRead,
    retrySynchronization: synchronizeWithServer
  };
}
