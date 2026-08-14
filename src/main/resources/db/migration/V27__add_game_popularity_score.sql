ALTER TABLE games
    ADD COLUMN popularity_score DECIMAL(8, 6) NOT NULL DEFAULT 0.000000;

ALTER TABLE games
    ADD CONSTRAINT ck_games_popularity_score
        CHECK (popularity_score BETWEEN 0 AND 1);

CREATE INDEX ix_games_popularity_score_name_id
    ON games (popularity_score DESC, name ASC, id ASC);
