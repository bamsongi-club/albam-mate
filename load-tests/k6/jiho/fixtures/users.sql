\set ON_ERROR_STOP on

BEGIN;

-- k6와 같은 소문자 Run ID 계약을 적용한다. 한쪽만 정규화하면 fixture 이메일과 로그인 이메일이 달라진다.
CREATE TEMP TABLE load_test_user_parameters (
    run_id text NOT NULL
        CONSTRAINT load_test_user_run_id_format
        CHECK (run_id ~ '^[a-z0-9][a-z0-9._-]{0,79}$')
) ON COMMIT DROP;

INSERT INTO load_test_user_parameters (run_id) VALUES (:'run_id');

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

COMMIT;
