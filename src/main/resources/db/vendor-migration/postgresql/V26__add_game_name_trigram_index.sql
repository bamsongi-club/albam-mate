CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX ix_games_name_lower_trgm
    ON games USING gin (lower(name) gin_trgm_ops);
