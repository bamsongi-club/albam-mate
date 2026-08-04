import { useCallback, useEffect, useRef, useState } from 'react';

export const NOTIFICATION_POLL_INTERVAL_MS = 10_000;

function isDocumentVisible() {
  return typeof document === 'undefined' || document.visibilityState !== 'hidden';
}

function isAborted(error, controller) {
  return controller.signal.aborted || error?.name === 'AbortError';
}

export function useNotificationPolling({
  enabled,
  panelOpen,
  loadNotifications,
  loadUnreadCount,
  onBackgroundError
}) {
  const [documentVisible, setDocumentVisible] = useState(isDocumentVisible);
  const [notifications, setNotifications] = useState([]);
  const [unreadCount, setUnreadCount] = useState(null);
  const [unreadCountRevision, setUnreadCountRevision] = useState(0);
  const [listStatus, setListStatus] = useState('idle');
  const [readSynchronizationPaused, setReadSynchronizationPaused] = useState(false);
  const generationRef = useRef(0);
  const listRequestRef = useRef(null);
  const unreadRequestRef = useRef(null);
  const readSynchronizationPausedRef = useRef(false);
  // 재시도와 일괄 읽음이 겹치면 각 작업이 획득한 pause를 모두 반납한 뒤 polling한다.
  const readSynchronizationPauseCountRef = useRef(0);
  const listSucceededRef = useRef(false);
  const reportedBackgroundErrorRef = useRef({ list: false, unread: false });
  const onBackgroundErrorRef = useRef(onBackgroundError);
  const wasDocumentVisibleRef = useRef(documentVisible);

  useEffect(() => {
    onBackgroundErrorRef.current = onBackgroundError;
  }, [onBackgroundError]);

  const reportBackgroundError = useCallback((kind, error) => {
    const alreadyReported = Object.values(reportedBackgroundErrorRef.current).some(Boolean);
    reportedBackgroundErrorRef.current[kind] = true;
    if (!alreadyReported) onBackgroundErrorRef.current?.(error);
  }, []);

  const refreshList = useCallback(({ allowWhilePaused = false, rejectOnError = false } = {}) => {
    if (!enabled || (readSynchronizationPausedRef.current && !allowWhilePaused)) {
      return Promise.resolve(false);
    }
    if (listRequestRef.current) return listRequestRef.current.promise;

    const controller = new AbortController();
    const generation = generationRef.current;
    const requestState = { controller, promise: null };
    if (!listSucceededRef.current) setListStatus('loading');

    requestState.promise = Promise.resolve()
      .then(() => loadNotifications(controller.signal))
      .then((page) => {
        if (controller.signal.aborted || generation !== generationRef.current) return false;
        setNotifications(Array.isArray(page?.content) ? page.content : []);
        setListStatus('ready');
        listSucceededRef.current = true;
        reportedBackgroundErrorRef.current.list = false;
        return true;
      })
      .catch((error) => {
        if (isAborted(error, controller) || generation !== generationRef.current) return false;
        if (rejectOnError) throw error;
        if (listSucceededRef.current) reportBackgroundError('list', error);
        else setListStatus('error');
        return false;
      })
      .finally(() => {
        if (listRequestRef.current === requestState) listRequestRef.current = null;
      });

    listRequestRef.current = requestState;
    return requestState.promise;
  }, [enabled, loadNotifications, reportBackgroundError]);

  const refreshUnreadCount = useCallback(({ allowWhilePaused = false, rejectOnError = false } = {}) => {
    if (!enabled || (readSynchronizationPausedRef.current && !allowWhilePaused)) {
      return Promise.resolve(false);
    }
    if (unreadRequestRef.current) return unreadRequestRef.current.promise;

    const controller = new AbortController();
    const generation = generationRef.current;
    const requestState = { controller, promise: null };

    requestState.promise = Promise.resolve()
      .then(() => loadUnreadCount(controller.signal))
      .then((response) => {
        if (controller.signal.aborted || generation !== generationRef.current) return false;
        const count = Number(response?.unreadCount);
        setUnreadCount(Number.isFinite(count) ? Math.max(0, count) : 0);
        setUnreadCountRevision((revision) => revision + 1);
        reportedBackgroundErrorRef.current.unread = false;
        return true;
      })
      .catch((error) => {
        if (isAborted(error, controller) || generation !== generationRef.current) return false;
        if (rejectOnError) throw error;
        reportBackgroundError('unread', error);
        return false;
      })
      .finally(() => {
        if (unreadRequestRef.current === requestState) unreadRequestRef.current = null;
      });

    unreadRequestRef.current = requestState;
    return requestState.promise;
  }, [enabled, loadUnreadCount, reportBackgroundError]);

  const refreshAll = useCallback(
    () => Promise.all([refreshList(), refreshUnreadCount()]),
    [refreshList, refreshUnreadCount]
  );

  const stopRequests = useCallback(() => {
    generationRef.current += 1;
    listRequestRef.current?.controller.abort();
    unreadRequestRef.current?.controller.abort();
    listRequestRef.current = null;
    unreadRequestRef.current = null;
  }, []);

  const pauseForReadSynchronization = useCallback(() => {
    readSynchronizationPauseCountRef.current += 1;
    readSynchronizationPausedRef.current = true;
    setReadSynchronizationPaused(true);
    stopRequests();
  }, [stopRequests]);

  const resumeAfterReadSynchronization = useCallback(() => {
    if (readSynchronizationPauseCountRef.current === 0) return;
    readSynchronizationPauseCountRef.current -= 1;
    if (readSynchronizationPauseCountRef.current > 0) return;
    readSynchronizationPausedRef.current = false;
    setReadSynchronizationPaused(false);
  }, []);

  const replaceNotification = useCallback((updatedNotification) => {
    setNotifications((currentNotifications) => currentNotifications.map((notification) => (
      notification.id === updatedNotification.id ? updatedNotification : notification
    )));
  }, []);

  const refreshUnreadAfterSingleRead = useCallback(async () => {
    stopRequests();
    try {
      return await refreshUnreadCount({ allowWhilePaused: true, rejectOnError: true });
    } catch {
      return false;
    }
  }, [refreshUnreadCount, stopRequests]);

  const refreshAfterReadSynchronization = useCallback(async () => {
    stopRequests();
    const results = await Promise.allSettled([
      refreshList({ allowWhilePaused: true, rejectOnError: true }),
      refreshUnreadCount({ allowWhilePaused: true, rejectOnError: true })
    ]);
    return results.every((result) => result.status === 'fulfilled' && result.value === true);
  }, [refreshList, refreshUnreadCount, stopRequests]);

  useEffect(() => {
    const handleVisibilityChange = () => setDocumentVisible(isDocumentVisible());
    document.addEventListener('visibilitychange', handleVisibilityChange);
    return () => document.removeEventListener('visibilitychange', handleVisibilityChange);
  }, []);

  useEffect(() => {
    if (!enabled) {
      stopRequests();
      readSynchronizationPausedRef.current = false;
      readSynchronizationPauseCountRef.current = 0;
      setReadSynchronizationPaused(false);
      listSucceededRef.current = false;
      reportedBackgroundErrorRef.current = { list: false, unread: false };
      setNotifications([]);
      setUnreadCount(null);
      setUnreadCountRevision(0);
      setListStatus('idle');
      return undefined;
    }

    refreshAll();
    return stopRequests;
  }, [enabled, refreshAll, stopRequests]);

  useEffect(() => {
    if (enabled && panelOpen) refreshAll();
  }, [enabled, panelOpen, refreshAll]);

  useEffect(() => {
    const wasVisible = wasDocumentVisibleRef.current;
    wasDocumentVisibleRef.current = documentVisible;
    if (!enabled || !documentVisible || wasVisible) return;
    refreshUnreadCount();
    if (panelOpen) refreshList();
  }, [documentVisible, enabled, panelOpen, refreshList, refreshUnreadCount]);

  useEffect(() => {
    if (!enabled || !documentVisible || readSynchronizationPaused) return undefined;
    const timer = window.setInterval(() => {
      refreshUnreadCount();
      if (panelOpen) refreshList();
    }, NOTIFICATION_POLL_INTERVAL_MS);
    return () => window.clearInterval(timer);
  }, [documentVisible, enabled, panelOpen, readSynchronizationPaused, refreshList, refreshUnreadCount]);

  return {
    notifications,
    unreadCount,
    unreadCountRevision,
    readSynchronizationPaused,
    listStatus,
    retry: refreshAll,
    pauseForReadSynchronization,
    resumeAfterReadSynchronization,
    replaceNotification,
    refreshUnreadAfterSingleRead,
    refreshAfterReadSynchronization
  };
}
