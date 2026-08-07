ALTER TABLE game_mechanisms
    ADD COLUMN description_ko VARCHAR(300);

UPDATE game_mechanisms
SET description_ko = name_ko || ' 방식을 활용해 게임을 진행해요.'
WHERE is_public = true
  AND description_ko IS NULL;

ALTER TABLE game_mechanisms
    ADD CONSTRAINT ck_game_mechanisms_public_description
        CHECK (NOT is_public OR description_ko IS NOT NULL);
