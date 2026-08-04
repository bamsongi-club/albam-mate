CREATE SEQUENCE room_waitlist_queue_order_seq
    AS BIGINT
    START WITH 1
    INCREMENT BY 1
    NO CYCLE
    CACHE 1;

CREATE TABLE room_waitlists (
    room_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    queue_order BIGINT NOT NULL,
    queued_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_room_waitlists PRIMARY KEY (room_id, user_id),
    CONSTRAINT fk_room_waitlists_room
        FOREIGN KEY (room_id) REFERENCES rooms (id) ON DELETE NO ACTION,
    CONSTRAINT fk_room_waitlists_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE NO ACTION,
    CONSTRAINT ck_room_waitlists_status
        CHECK (status IN ('WAITING', 'PROMOTED', 'CANCELED', 'EXPIRED', 'ROOM_CANCELED')),
    CONSTRAINT ck_room_waitlists_queue_order_positive
        CHECK (queue_order > 0)
);
