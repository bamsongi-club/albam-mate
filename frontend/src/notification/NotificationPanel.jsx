import React, { useEffect, useRef } from 'react';
import { notificationMessage } from './notificationMessages';
import { NOTIFICATION_SYNC_ERROR_MESSAGE } from './useNotificationReadSync';

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

function formatCreatedTime(createdAt) {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return '';
  return new Intl.DateTimeFormat('ko-KR', {
    timeZone: SEOUL_TIME_ZONE,
    hour: '2-digit',
    minute: '2-digit',
    hour12: false
  }).format(date);
}

function seoulCalendarDay(createdAt) {
  const date = new Date(createdAt);
  if (Number.isNaN(date.getTime())) return null;

  const values = new Intl.DateTimeFormat('en-US', {
    timeZone: SEOUL_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit'
  }).formatToParts(date).reduce((parts, part) => ({ ...parts, [part.type]: part.value }), {});

  return Date.UTC(Number(values.year), Number(values.month) - 1, Number(values.day));
}

function notificationSectionLabel(createdAt) {
  const createdDay = seoulCalendarDay(createdAt);
  const today = seoulCalendarDay(new Date());
  if (createdDay === null || today === null) return '이전 알림';

  const daysAgo = Math.round((today - createdDay) / 86_400_000);
  if (daysAgo <= 0) return '오늘';
  if (daysAgo <= 7) return '지난 7일';
  return '이전 알림';
}

function groupNotifications(notifications) {
  return notifications.reduce((sections, notification) => {
    const label = notificationSectionLabel(notification.createdAt);
    const lastSection = sections.at(-1);
    if (lastSection?.label === label) {
      lastSection.notifications.push(notification);
      return sections;
    }
    return [...sections, { label, notifications: [notification] }];
  }, []);
}

function notificationIconTone(type) {
  if (type === 'PARTICIPANT_JOINED') return 'green';
  if (type === 'WAITLIST_PROMOTED') return 'gold';
  if (type === 'PARTICIPANT_CANCELED') return 'clay';
  return 'muted';
}

function NotificationPersonIcon({ tone }) {
  return (
    <span className={'notification-item-icon ' + tone} aria-hidden="true">
      <svg viewBox="0 0 24 24" width="18" height="18" fill="currentColor">
        <circle cx="12" cy="8" r="4" />
        <path d="M4.5 21a7.5 7.5 0 0 1 15 0Z" />
      </svg>
    </span>
  );
}

const FOCUSABLE_SELECTOR = 'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

function focusableElements(container) {
  return [...container.querySelectorAll(FOCUSABLE_SELECTOR)];
}

function makeBackgroundInert() {
  const elements = [
    document.querySelector('main'),
    document.querySelector('.site-footer'),
    document.querySelector('.mobile-bottom-nav'),
    document.querySelector('#toast')
  ].filter(Boolean);
  const previous = elements.map((element) => ({ element, inert: element.hasAttribute('inert') }));
  elements.forEach((element) => element.setAttribute('inert', ''));
  return () => previous.forEach(({ element, inert }) => {
    if (!inert) element.removeAttribute('inert');
  });
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
  onRetrySynchronization,
  isModal = false
}) {
  const panelRef = useRef(null);
  const closeButtonRef = useRef(null);
  const onCloseRef = useRef(onClose);
  useEffect(() => {
    onCloseRef.current = onClose;
  }, [onClose]);

  useEffect(() => {
    if (!open || !isModal) return undefined;

    const focusedBeforeOpen = document.activeElement;
    const restoreBackground = makeBackgroundInert();
    closeButtonRef.current?.focus();
    const handleKeyDown = (event) => {
      if (event.key === 'Escape') {
        event.preventDefault();
        onCloseRef.current();
        return;
      }
      if (event.key !== 'Tab') return;

      const focusable = focusableElements(panelRef.current);
      const first = focusable[0];
      const last = focusable.at(-1);
      if (!first || !last) return;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', handleKeyDown);

    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      restoreBackground();
      if (focusedBeforeOpen instanceof HTMLElement && focusedBeforeOpen.isConnected) focusedBeforeOpen.focus();
    };
  }, [open, isModal]);

  if (!open) return null;
  const sections = groupNotifications(notifications);

  return (
    <section ref={panelRef} className="notification-panel" aria-label="알림함" role={isModal ? 'dialog' : undefined} aria-modal={isModal || undefined} tabIndex={isModal ? -1 : undefined}>
      <div className="notification-panel-header">
        <button ref={closeButtonRef} type="button" className="notification-close" aria-label="알림함 닫기" onClick={onClose}>
          <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true"><path d="m15 18-6-6 6-6" /></svg>
        </button>
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
          <div className="notification-sections">
            {sections.map((section, sectionIndex) => (
              <section className="notification-section" key={section.label} aria-labelledby={'notification-section-' + sectionIndex}>
                <h3 className="notification-section-title" id={'notification-section-' + sectionIndex}>{section.label}</h3>
                <ul className="notification-list">
                  {section.notifications.map((notification) => {
                    const read = Boolean(notification.readAt || optimisticReadIds.has(notification.id));
                    return (
                      <li key={notification.id}>
                        <button
                          type="button"
                          className={'notification-item ' + (read ? 'read' : 'unread')}
                          onClick={() => onSelectNotification(notification)}
                        >
                          <NotificationPersonIcon tone={notificationIconTone(notification.type)} />
                          <span className="notification-item-copy">
                            <span className="notification-item-message">{notificationMessage(notification)}</span>
                            <time dateTime={notification.createdAt} title={formatCreatedAt(notification.createdAt)}>{formatCreatedTime(notification.createdAt)}</time>
                          </span>
                          {!read && <span className="notification-unread-dot" aria-hidden="true" />}
                        </button>
                      </li>
                    );
                  })}
                </ul>
              </section>
            ))}
          </div>
        )}
      </div>
    </section>
  );
}
