ALTER TABLE game_mechanisms
    ADD COLUMN description_ko VARCHAR(300);

UPDATE game_mechanisms
SET is_public = false,
    featured_order = NULL
WHERE is_public = true
  AND NULLIF(BTRIM(description_ko), '') IS NULL;

ALTER TABLE game_mechanisms
    ADD CONSTRAINT ck_game_mechanisms_public_description
        CHECK (NOT is_public OR NULLIF(BTRIM(description_ko), '') IS NOT NULL);
