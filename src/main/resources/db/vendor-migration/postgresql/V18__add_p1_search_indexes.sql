CREATE INDEX idx_rooms_public_start_at_id
    ON rooms (start_at, id)
    WHERE status IN ('RECRUITING', 'CLOSED');
