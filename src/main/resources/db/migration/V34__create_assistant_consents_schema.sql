CREATE TABLE assistant_consents (
    user_id BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    consent_version VARCHAR(50) NOT NULL,
    provider VARCHAR(20) NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    policy_url VARCHAR(500) NOT NULL,
    store BOOLEAN NOT NULL,
    granted_at TIMESTAMP WITH TIME ZONE,
    revoked_at TIMESTAMP WITH TIME ZONE,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT pk_assistant_consents PRIMARY KEY (user_id),
    CONSTRAINT fk_assistant_consents_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT ck_assistant_consents_status
        CHECK (status IN ('GRANTED', 'REVOKED')),
    CONSTRAINT ck_assistant_consents_provider
        CHECK (provider = 'OPENAI'),
    CONSTRAINT ck_assistant_consents_store
        CHECK (store = FALSE)
);
