CREATE INDEX idx_match_requests_waiting_candidate
    ON match_requests (priority_since ASC, id ASC)
    WHERE status = 'WAITING';
