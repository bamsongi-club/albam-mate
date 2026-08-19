-- v2/flags-upcoming-exact content read
-- source=docs/measurements/results/game-list-740/game-list-867-2026-08-19/sql-captures/v2/flags-upcoming-exact.postgres.log
-- captured_execute_duration_ms=3.041
EXPLAIN (ANALYZE, BUFFERS, VERBOSE, FORMAT TEXT)
select g1_0.id,g1_0.alias,g1_0.bgg_id,g1_0.complexity,g1_0.created_at,g1_0.description,g1_0.detail_description,g1_0.english_name,g1_0.estimated_play_time,g1_0.image_url,g1_0.max_play_time_minutes,g1_0.max_players,g1_0.min_age,g1_0.min_play_time_minutes,g1_0.min_players,g1_0.name,g1_0.popularity_score,g1_0.release_year,g1_0.supported_player_count,g1_0.tag,g1_0.updated_at from games g1_0 where g1_0.id in ('31','32','33','34','35','36','37','38','39','40','41','42','43','44','45','46','47','48','49','50','51','52','53','54','55','56','57','58','59','60') and g1_0.min_players='4' and g1_0.max_players='4' order by g1_0.popularity_score desc,g1_0.name,g1_0.id offset '0' rows fetch first '25' rows only;
