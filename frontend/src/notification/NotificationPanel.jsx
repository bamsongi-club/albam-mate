import React from 'react';
import { notificationMessage } from './notificationMessages';
import { NOTIFICATION_SYNC_ERROR_MESSAGE } from './useNotificationReadSync';

const EMPTY_READ_IDS = new Set();

function formatCreatedAt(createdAt) {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('ko-KR', {
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date);
}

export function NotificationPanel({
  open,
  notifications,
  listStatus,
  optimisticReadIds = EMPTY_READ_IDS,
  canMarkAllAsRead = false,
  bulkReadPending = false,
  synchronizationFailed = false,
  onClose,
  onRetry,
  onSelectNotification,
  onMarkAllAsRead,
  onRetrySynchronization
}) {
  if (!open) return null;

  return (
    <section className="notification-panel" aria-label="알림함">
      <div className="notification-panel-header">
        <h2>알림</h2>
        <div className="notification-panel-actions">
          <button
            type="button"
            className="notification-read-all"
            disabled={!canMarkAllAsRead || bulkReadPending}
            onClick={onMarkAllAsRead}
          >
            {bulkReadPending ? '처리 중…' : '모두 읽음'}
          </button>
          <button type="button" className="notification-close" aria-label="알림함 닫기" onClick={onClose}>×</button>
        </div>
      </div>
      <div className="notification-panel-body" aria-live="polite" aria-busy={bulkReadPending}>
        {synchronizationFailed && (
          <div className="notification-sync-error" role="alert">
            <p>{NOTIFICATION_SYNC_ERROR_MESSAGE}</p>
            <button type="button" className="btn ghost" onClick={onRetrySynchronization}>다시 시도</button>
          </div>
        )}
        {listStatus === 'loading' && <p className="notification-state">알림을 불러오는 중입니다.</p>}
        {listStatus === 'error' && (
          <div className="notification-state">
            <p>알림을 불러오지 못했습니다.</p>
            <button type="button" className="btn ghost" onClick={onRetry}>다시 시도</button>
          </div>
        )}
        {listStatus === 'ready' && notifications.length === 0 && (
          <p className="notification-state">새로운 알림이 없습니다.</p>
        )}
        {listStatus === 'ready' && notifications.length > 0 && (
          <ul className="notification-list">
            {notifications.map((notification) => (
              <li key={notification.id}>
                <button
                  type="button"
                  className={'notification-item ' + (
                    notification.readAt || optimisticReadIds.has(notification.id) ? 'read' : 'unread'
                  )}
                  onClick={() => onSelectNotification(notification)}
                >
                  <span className="notification-item-message">{notificationMessage(notification)}</span>
                  <time dateTime={notification.createdAt}>{formatCreatedAt(notification.createdAt)}</time>
                </button>
              </li>
            ))}
          </ul>
        )}
      </div>
    </section>
  );
}
