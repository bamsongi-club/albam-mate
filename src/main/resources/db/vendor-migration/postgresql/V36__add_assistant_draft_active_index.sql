CREATE UNIQUE INDEX uq_assistant_drafts_active_user
    ON assistant_drafts (user_id) WHERE status = 'ACTIVE';
