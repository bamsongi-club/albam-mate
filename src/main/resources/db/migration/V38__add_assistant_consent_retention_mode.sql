ALTER TABLE assistant_consents
    ADD COLUMN retention_mode VARCHAR(30);

UPDATE assistant_consents
SET retention_mode = 'unverified';

ALTER TABLE assistant_consents
    ALTER COLUMN retention_mode SET NOT NULL;

ALTER TABLE assistant_consents
    ADD CONSTRAINT ck_assistant_consents_retention_mode
        CHECK (retention_mode IN ('default-30d', 'zero-data-retention', 'unverified'));
