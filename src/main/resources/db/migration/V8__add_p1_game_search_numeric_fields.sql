UPDATE games
SET complexity = NULL
WHERE complexity < 1.00 OR complexity > 5.00;

ALTER TABLE games
    ADD COLUMN min_players INTEGER;

ALTER TABLE games
    ADD COLUMN max_players INTEGER;

ALTER TABLE games
    ADD COLUMN min_play_time_minutes INTEGER;

ALTER TABLE games
    ADD COLUMN max_play_time_minutes INTEGER;

ALTER TABLE games
    ADD CONSTRAINT ck_games_player_range
        CHECK (
            (min_players IS NULL AND max_players IS NULL)
            OR (
                min_players IS NOT NULL
                AND max_players IS NOT NULL
                AND min_players > 0
                AND max_players > 0
                AND min_players <= max_players
            )
        );

ALTER TABLE games
    ADD CONSTRAINT ck_games_play_time_range
        CHECK (
            (min_play_time_minutes IS NULL AND max_play_time_minutes IS NULL)
            OR (
                min_play_time_minutes IS NOT NULL
                AND max_play_time_minutes IS NOT NULL
                AND min_play_time_minutes > 0
                AND max_play_time_minutes > 0
                AND min_play_time_minutes <= max_play_time_minutes
            )
        );

ALTER TABLE games
    ADD CONSTRAINT ck_games_complexity_range
        CHECK (complexity IS NULL OR complexity BETWEEN 1.00 AND 5.00);
