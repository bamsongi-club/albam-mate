ALTER TABLE notification_outbox_events
    DROP CONSTRAINT ck_notification_outbox_events_event_type;

ALTER TABLE notification_outbox_events
    ADD CONSTRAINT ck_notification_outbox_events_event_type
        CHECK (event_type IN (
            'PARTICIPATION_JOINED',
            'PARTICIPATION_CANCELED',
            'WAITLIST_PROMOTED',
            'ROOM_CANCELED'
        ));

ALTER TABLE notifications
    DROP CONSTRAINT ck_notifications_type;

ALTER TABLE notifications
    ADD CONSTRAINT ck_notifications_type
        CHECK (type IN (
            'PARTICIPANT_JOINED',
            'PARTICIPANT_CANCELED',
            'WAITLIST_PROMOTED',
            'ROOM_CANCELED'
        ));
