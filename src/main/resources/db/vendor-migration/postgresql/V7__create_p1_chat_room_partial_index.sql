CREATE INDEX idx_chat_rooms_pending_purge
    ON chat_rooms (purge_after)
    WHERE purge_after IS NOT NULL AND messages_purged_at IS NULL;
