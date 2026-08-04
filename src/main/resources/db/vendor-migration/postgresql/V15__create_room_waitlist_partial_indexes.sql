CREATE UNIQUE INDEX uq_room_waitlists_waiting_room_queue_order
    ON room_waitlists (room_id, queue_order)
    WHERE status = 'WAITING';

CREATE INDEX idx_room_waitlists_waiting_user_room
    ON room_waitlists (user_id, room_id)
    WHERE status = 'WAITING';
