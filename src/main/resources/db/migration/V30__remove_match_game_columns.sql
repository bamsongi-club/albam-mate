ALTER TABLE match_requests DROP CONSTRAINT fk_match_requests_game;
ALTER TABLE match_requests DROP COLUMN game_id;

ALTER TABLE match_proposals DROP CONSTRAINT fk_match_proposals_game;
ALTER TABLE match_proposals DROP COLUMN game_id;

ALTER TABLE match_parties DROP CONSTRAINT fk_match_parties_game;
ALTER TABLE match_parties DROP COLUMN game_id;
