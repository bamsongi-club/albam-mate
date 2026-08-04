import { useCallback, useEffect, useRef, useState } from 'react';

export const NOTIFICATION_SYNC_ERROR_MESSAGE =
  '알림 상태를 갱신하지 못했습니다. 다시 시도해 주세요.';

function removeId(ids, notificationId) {
  const nextIds = new Set(ids);
  nextIds.delete(notificationId);
  return nextIds;
}

function isCanceledRequest(error, isUnauthenticated) {
  return error?.name === 'AbortError' || isUnauthenticated(error);
}

export function useNotificationReadSync({
  enabled,
  unreadCount,
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
  const bulkReadPendingRef = useRef(false);
  const readOperationGenerationRef = useRef(0);

  const clearOptimisticRead = useCallback((notificationId) => {
    setOptimisticReadIds((currentIds) => removeId(currentIds, notificationId));
  }, []);

  const applySynchronizationResult = useCallback((synchronized) => {
    setSynchronizationFailed(!synchronized);
    if (synchronized) setOptimisticReadIds(new Set());
    return synchronized;
  }, []);

  const synchronizeWithServer = useCallback(async () => {
    pauseForReadSynchronization();
    try {
      const synchronized = await refreshAfterReadSynchronization();
      return applySynchronizationResult(synchronized);
    } finally {
      resumeAfterReadSynchronization();
    }
  }, [
    applySynchronizationResult,
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
    const operationGeneration = readOperationGenerationRef.current;
    setSynchronizationFailed(false);
    setOptimisticReadIds((currentIds) => new Set(currentIds).add(notificationId));

    return (async () => {
      try {
        const updatedNotification = await markNotificationRead(notificationId);
        if (operationGeneration !== readOperationGenerationRef.current) return false;
        replaceNotification(updatedNotification);

        const unreadCountRefreshed = await refreshUnreadAfterSingleRead();
        setSynchronizationFailed(!unreadCountRefreshed);
        if (unreadCountRefreshed) clearOptimisticRead(notificationId);
        return true;
      } catch (error) {
        clearOptimisticRead(notificationId);
        if (!isCanceledRequest(error, isUnauthenticated)) await synchronizeWithServer();
        return false;
      } finally {
        pendingSingleReadIdsRef.current.delete(notificationId);
      }
    })();
  }, [
    clearOptimisticRead,
    enabled,
    isUnauthenticated,
    markNotificationRead,
    refreshUnreadAfterSingleRead,
    replaceNotification,
    synchronizeWithServer
  ]);

  const markAllAsRead = useCallback(() => {
    if (!enabled || bulkReadPendingRef.current) return Promise.resolve(false);

    bulkReadPendingRef.current = true;
    readOperationGenerationRef.current += 1;
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
        shouldSynchronize = !isCanceledRequest(error, isUnauthenticated);
      }

      try {
        if (!shouldSynchronize) return false;
        const synchronized = await refreshAfterReadSynchronization();
        applySynchronizationResult(synchronized);
        return mutationSucceeded && synchronized;
      } finally {
        resumeAfterReadSynchronization();
        bulkReadPendingRef.current = false;
        setBulkReadPending(false);
      }
    })();
  }, [
    applySynchronizationResult,
    enabled,
    isUnauthenticated,
    markAllNotificationsRead,
    pauseForReadSynchronization,
    refreshAfterReadSynchronization,
    resumeAfterReadSynchronization
  ]);

  useEffect(() => {
    if (enabled) return;
    pendingSingleReadIdsRef.current.clear();
    bulkReadPendingRef.current = false;
    readOperationGenerationRef.current += 1;
    setOptimisticReadIds(new Set());
    setBulkReadPending(false);
    setSynchronizationFailed(false);
  }, [enabled]);

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
