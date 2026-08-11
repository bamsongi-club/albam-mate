\set ON_ERROR_STOP on

INSERT INTO users (email, password_hash, nickname, created_at, updated_at)
SELECT
    format('k6.%s.auth.%s@example.com', :'run_id', fixture_index),
    '{bcrypt}$2y$10$PzJpRRDVEB/jtl2uSy8vZuLyskdxt1Jg6BZ23PQqlQLvm7kB0EAem',
    format('k6-%s-%s', left(:'run_id', 36), fixture_index),
    clock_timestamp(),
    clock_timestamp()
FROM generate_series(1, :user_count::integer) AS fixture_index
ON CONFLICT (email) DO UPDATE SET
    password_hash = EXCLUDED.password_hash,
    nickname = EXCLUDED.nickname,
    updated_at = clock_timestamp();
