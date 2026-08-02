CREATE INDEX idx_notification_outbox_events_relay
    ON notification_outbox_events (available_at, id)
    WHERE status IN ('PENDING', 'RETRY_WAIT');

CREATE INDEX idx_notification_outbox_events_failed
    ON notification_outbox_events (id)
    WHERE status = 'FAILED';

CREATE INDEX idx_notification_outbox_events_cleanup
    ON notification_outbox_events (cleanup_at, id)
    WHERE cleanup_at IS NOT NULL;

CREATE INDEX idx_notifications_recipient_unread
    ON notifications (recipient_user_id, id)
    WHERE read_at IS NULL;
