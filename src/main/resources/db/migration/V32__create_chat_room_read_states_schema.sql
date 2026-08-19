CREATE TABLE chat_room_read_states (
    user_id BIGINT NOT NULL,
    chat_room_id BIGINT NOT NULL,
    last_read_message_id BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (user_id, chat_room_id),
    CONSTRAINT fk_chat_room_read_states_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE NO ACTION,
    CONSTRAINT fk_chat_room_read_states_chat_room
        FOREIGN KEY (chat_room_id) REFERENCES chat_rooms (id) ON DELETE NO ACTION
);
