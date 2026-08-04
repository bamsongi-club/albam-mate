CREATE TABLE shedlock (
    name VARCHAR(64) NOT NULL PRIMARY KEY,
    lock_until TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_at TIMESTAMP WITH TIME ZONE NOT NULL,
    locked_by VARCHAR(255) NOT NULL
);

INSERT INTO chat_rooms (room_id, purge_after, messages_purged_at, created_at, updated_at)
SELECT
    rooms.id,
    CASE WHEN rooms.status IN ('CANCELED', 'FINISHED') THEN CURRENT_TIMESTAMP ELSE NULL END,
    CASE WHEN rooms.status IN ('CANCELED', 'FINISHED') THEN CURRENT_TIMESTAMP ELSE NULL END,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM rooms
WHERE NOT EXISTS (
    SELECT 1
    FROM chat_rooms existing_chat_room
    WHERE existing_chat_room.room_id = rooms.id
);
