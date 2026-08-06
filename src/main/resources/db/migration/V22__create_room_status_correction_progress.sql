CREATE TABLE room_status_correction_progress (
    job_name VARCHAR(64) NOT NULL,
    turn_cutoff TIMESTAMP WITH TIME ZONE,
    cursor_due_at TIMESTAMP WITH TIME ZONE,
    cursor_room_id BIGINT,
    progress_version BIGINT NOT NULL,
    execution_generation BIGINT NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_room_status_correction_progress PRIMARY KEY (job_name),
    CONSTRAINT ck_room_status_correction_progress_job_name
        CHECK (job_name = 'room-status-correction'),
    CONSTRAINT ck_room_status_correction_progress_cursor_pair
        CHECK ((cursor_due_at IS NULL) = (cursor_room_id IS NULL)),
    CONSTRAINT ck_room_status_correction_progress_cursor_within_turn
        CHECK (cursor_due_at IS NULL OR (turn_cutoff IS NOT NULL AND cursor_due_at <= turn_cutoff)),
    CONSTRAINT ck_room_status_correction_progress_non_negative_versions
        CHECK ((cursor_room_id IS NULL OR cursor_room_id > 0)
            AND progress_version >= 0
            AND execution_generation >= 0)
);

INSERT INTO room_status_correction_progress (
    job_name,
    turn_cutoff,
    cursor_due_at,
    cursor_room_id,
    progress_version,
    execution_generation
) VALUES (
    'room-status-correction',
    NULL,
    NULL,
    NULL,
    0,
    0
);
