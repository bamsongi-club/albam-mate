import React from 'react';
import { notificationMessage } from './notificationMessages';
import { NOTIFICATION_SYNC_ERROR_MESSAGE } from './useNotificationReadSync';
import { ScreenTitle, StateBlock, TopBar } from '../shared/ui';

const EMPTY_READ_IDS = new Set();
const SEOUL_TIME_ZONE = 'Asia/Seoul';

function formatCreatedAt(createdAt) {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: SEOUL_TIME_ZONE,
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit'
  }).format(date);
}

/**
 * 알림함 화면. 목록·읽음 처리 상태는 모두 위에서 내려받는다.
 *
 * 미읽음은 빨간 점과 굵은 글씨, 읽음은 회색 점과 본문 굵기로 구분한다.
 */
export function NotificationPanel({
  notifications,
  listStatus,
  optimisticReadIds = EMPTY_READ_IDS,
  canMarkAllAsRead = false,
  bulkReadPending = false,
  synchronizationFailed = false,
  onBack,
  onRetry,
  onSelectNotification,
  onMarkAllAsRead,
  onRetrySynchronization
}) {
  return (
    <div className="screen sub">
      <TopBar
        onBack={onBack}
        backLabel="알림함 닫기"
        action={(
          <button
            type="button"
            className="topbar-action"
            disabled={!canMarkAllAsRead || bulkReadPending}
            onClick={onMarkAllAsRead}
          >
            {bulkReadPending ? '처리 중…' : '모두 읽음'}
          </button>
        )}
      />
      <div className="screen-body pad-bottom">
        <ScreenTitle>알림</ScreenTitle>
        <div aria-live="polite" aria-busy={bulkReadPending}>
          {synchronizationFailed && (
            <div className="notification-sync-error" role="alert">
              <p>{NOTIFICATION_SYNC_ERROR_MESSAGE}</p>
              <button type="button" className="btn sm" onClick={onRetrySynchronization}>다시 시도</button>
            </div>
          )}
          {listStatus === 'loading' && <p className="notification-state" role="status">알림을 불러오는 중입니다.</p>}
          {listStatus === 'error' && (
            <StateBlock tone="error" title="알림을 불러오지 못했습니다." description="네트워크 상태를 확인하고 다시 시도해주세요.">
              <button type="button" className="btn" onClick={onRetry}>다시 시도</button>
            </StateBlock>
          )}
          {listStatus === 'ready' && notifications.length === 0 && (
            <p className="notification-state">새로운 알림이 없습니다.</p>
          )}
          {listStatus === 'ready' && notifications.length > 0 && (
            <ul className="notification-list">
              {notifications.map((notification) => {
                const read = Boolean(notification.readAt || optimisticReadIds.has(notification.id));
                return (
                  <li key={notification.id}>
                    <button
                      type="button"
                      className={'notification-item ' + (read ? 'read' : 'unread')}
                      onClick={() => onSelectNotification(notification)}
                    >
                      <span className="notification-dot" aria-hidden="true" />
                      <span>
                        <span className="notification-item-message">{notificationMessage(notification)}</span>
                        <time dateTime={notification.createdAt}>{formatCreatedAt(notification.createdAt)}</time>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}
