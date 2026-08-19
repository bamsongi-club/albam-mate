ALTER TABLE match_parties
    ADD CONSTRAINT ck_match_parties_lifecycle CHECK (
        (status = 'PREPARING')
        OR (status = 'ACTIVE' AND chat_opened_at IS NOT NULL AND closes_at IS NOT NULL)
        OR (status = 'CLOSED' AND closed_at IS NOT NULL AND purge_after IS NOT NULL AND purge_after = closed_at + INTERVAL '7 days')
    );

CREATE UNIQUE INDEX uq_match_requests_active_user
    ON match_requests (user_id)
    WHERE status IN ('WAITING', 'PROPOSED', 'PAUSED');
CREATE INDEX idx_match_requests_waiting_candidate
    ON match_requests (game_id, priority_since ASC, id ASC)
    WHERE status = 'WAITING';
CREATE INDEX idx_match_requests_purge_after
    ON match_requests (purge_after, id)
    WHERE purge_after IS NOT NULL;

CREATE INDEX idx_match_proposals_purge_after
    ON match_proposals (purge_after, id)
    WHERE purge_after IS NOT NULL;

CREATE UNIQUE INDEX uq_match_parties_proposal
    ON match_parties (proposal_id)
    WHERE proposal_id IS NOT NULL;
CREATE INDEX idx_match_parties_preparing_due
    ON match_parties (preparing_started_at, id)
    WHERE status = 'PREPARING';
CREATE INDEX idx_match_parties_active_due
    ON match_parties (closes_at, id)
    WHERE status = 'ACTIVE';
CREATE INDEX idx_match_parties_purge_after
    ON match_parties (purge_after, id)
    WHERE purge_after IS NOT NULL;

CREATE INDEX idx_match_party_participants_current
    ON match_party_participants (party_id, user_id)
    WHERE left_at IS NULL;

CREATE UNIQUE INDEX uq_match_chat_messages_user_client
    ON match_chat_messages (match_chat_room_id, sender_user_id, client_message_id)
    WHERE client_message_id IS NOT NULL;
CREATE UNIQUE INDEX uq_match_chat_messages_system_event
    ON match_chat_messages (match_chat_room_id, system_event_key)
    WHERE system_event_key IS NOT NULL;
