CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE OR REPLACE FUNCTION game_search_bigrams(value text)
RETURNS text[]
LANGUAGE plpgsql
IMMUTABLE
PARALLEL SAFE
AS $$
DECLARE
	normalized text := lower(coalesce(value, ''));
	grams text[] := ARRAY[]::text[];
	position integer;
BEGIN
	IF char_length(normalized) < 2 THEN
		RETURN grams;
	END IF;
	FOR position IN 1..char_length(normalized) - 1 LOOP
		grams := array_append(grams, substring(normalized FROM position FOR 2));
	END LOOP;
	RETURN grams;
END;
$$;

CREATE INDEX ix_games_english_name_lower_trgm
    ON games USING gin (lower(english_name) gin_trgm_ops);

CREATE INDEX ix_games_alias_lower_trgm
    ON games USING gin (lower(alias) gin_trgm_ops);

CREATE INDEX ix_games_description_lower_trgm
    ON games USING gin (lower(description) gin_trgm_ops);

CREATE INDEX ix_games_name_lower_bigram
	ON games USING gin (game_search_bigrams(name));

CREATE INDEX ix_games_english_name_lower_bigram
	ON games USING gin (game_search_bigrams(english_name));

CREATE INDEX ix_games_alias_lower_bigram
	ON games USING gin (game_search_bigrams(alias));

CREATE INDEX ix_games_description_lower_bigram
	ON games USING gin (game_search_bigrams(description));
