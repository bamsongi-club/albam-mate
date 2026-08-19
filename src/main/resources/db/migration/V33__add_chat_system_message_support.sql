ALTER TABLE chat_messages ADD COLUMN message_type VARCHAR(20) NOT NULL DEFAULT 'USER';
ALTER TABLE chat_messages ADD COLUMN system_event_key VARCHAR(40);
ALTER TABLE chat_messages ADD COLUMN subject_user_id BIGINT;

ALTER TABLE chat_messages ALTER COLUMN sender_user_id DROP NOT NULL;
ALTER TABLE chat_messages ALTER COLUMN client_message_id DROP NOT NULL;
ALTER TABLE chat_messages ALTER COLUMN content DROP NOT NULL;

ALTER TABLE chat_messages
    ADD CONSTRAINT fk_chat_messages_subject_user
    FOREIGN KEY (subject_user_id) REFERENCES users (id) ON DELETE NO ACTION;

ALTER TABLE chat_messages
    ADD CONSTRAINT ck_chat_messages_kind CHECK (
        (message_type = 'USER'
            AND sender_user_id IS NOT NULL AND client_message_id IS NOT NULL AND content IS NOT NULL
            AND system_event_key IS NULL AND subject_user_id IS NULL)
        OR (message_type = 'SYSTEM'
            AND sender_user_id IS NULL AND client_message_id IS NULL AND content IS NULL
            AND subject_user_id IS NOT NULL
            AND system_event_key IS NOT NULL
            AND system_event_key IN ('PARTICIPANT_ENTERED', 'PARTICIPANT_LEFT'))
    );

CREATE TABLE chat_system_message_activation (
    gate_name VARCHAR(64) PRIMARY KEY,
    enabled_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

INSERT INTO chat_system_message_activation (gate_name, enabled_at, updated_at)
VALUES ('chat-system-message', NULL, CURRENT_TIMESTAMP);
