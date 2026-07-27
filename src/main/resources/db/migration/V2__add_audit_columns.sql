ALTER TABLE games ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

UPDATE games
SET updated_at = created_at;

ALTER TABLE games ALTER COLUMN updated_at SET NOT NULL;

ALTER TABLE participations ADD COLUMN created_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE participations ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE;

UPDATE participations
SET created_at = joined_at,
    updated_at = COALESCE(canceled_at, joined_at);

ALTER TABLE participations ALTER COLUMN created_at SET NOT NULL;
ALTER TABLE participations ALTER COLUMN updated_at SET NOT NULL;
