WITH callback_now AS (
    SELECT CURRENT_TIMESTAMP AS value
)
INSERT INTO users (email, password_hash, nickname, created_at, updated_at)
SELECT
    'local.seed.host@albammate.local',
    '{bcrypt}$2a$10$6fzHq4LYhgKdwPTfFGRY8eV4JC7GDmK3eE3eM9WlQqKP7sFiSWOmK',
    '로컬 모임지기',
    callback_now.value,
    callback_now.value
FROM callback_now
WHERE NOT EXISTS (
    SELECT 1
    FROM users
    WHERE email = 'local.seed.host@albammate.local'
);

WITH positive_game_count AS (
    SELECT LEAST(30, COUNT(*))::int AS value
    FROM games
    WHERE bgg_id > 0
),
callback_now AS (
    SELECT CURRENT_TIMESTAMP AS value
)
INSERT INTO games (
    bgg_id, name, english_name, supported_player_count, tag,
    estimated_play_time, description, detail_description, created_at, updated_at
)
SELECT
    -9000000000 - generated.seed_index,
    format('[LOCAL] 참조 게임 %s', lpad(generated.seed_index::text, 2, '0')),
    format('[LOCAL] Reference Game %s', lpad(generated.seed_index::text, 2, '0')),
    '2~4명',
    'LOCAL',
    '60분',
    '로컬 개발 환경의 모임 시드용 참조 게임입니다.',
    '운영 카탈로그가 30개 미만일 때만 사용하는 로컬 전용 참조 게임입니다.',
    callback_now.value,
    callback_now.value
FROM positive_game_count
CROSS JOIN callback_now
CROSS JOIN generate_series(1, GREATEST(0, 30 - positive_game_count.value)) AS generated(seed_index)
WHERE NOT EXISTS (
    SELECT 1
    FROM games existing_game
    WHERE existing_game.bgg_id = -9000000000 - generated.seed_index
);

WITH seed_host AS (
    SELECT id
    FROM users
    WHERE email = 'local.seed.host@albammate.local'
),
selected_games AS (
    SELECT
        candidate.id,
        ROW_NUMBER() OVER (ORDER BY candidate.source_order, candidate.id)::int AS seed_index
    FROM (
        SELECT id, 0 AS source_order
        FROM games
        WHERE bgg_id > 0
        UNION ALL
        SELECT id, 1 AS source_order
        FROM games
        WHERE bgg_id BETWEEN -9000000030 AND -9000000001
    ) candidate
    ORDER BY candidate.source_order, candidate.id
    LIMIT 30
),
seed_targets AS (
    SELECT
        seed_host.id AS host_user_id,
        selected_games.id AS game_id,
        'GAME_FOCUSED' AS room_type,
        format('[LOCAL] 게임 중심 모임 %s', lpad(selected_games.seed_index::text, 2, '0')) AS title,
        '로컬 개발용 게임 중심 모임입니다. 편하게 참여해 보세요.' AS description,
        CURRENT_TIMESTAMP + selected_games.seed_index * INTERVAL '1 day' + INTERVAL '4 hours' AS start_at
    FROM seed_host
    CROSS JOIN selected_games
    UNION ALL
    SELECT
        seed_host.id,
        NULL,
        'PERSON_FOCUSED',
        format('[LOCAL] 사람 중심 모임 %s', lpad(generated.seed_index::text, 2, '0')),
        '로컬 개발용 사람 중심 모임입니다. 게임은 현장에서 함께 정해요.',
        CURRENT_TIMESTAMP + generated.seed_index * INTERVAL '1 day' + INTERVAL '18 hours'
    FROM seed_host
    CROSS JOIN generate_series(1, 30) AS generated(seed_index)
),
existing_seed_rooms AS (
    SELECT
        room.id,
        seed_targets.game_id,
        seed_targets.room_type,
        seed_targets.description,
        seed_targets.start_at,
        COUNT(participation.id) FILTER (WHERE participation.status = 'ACTIVE')::int AS active_participant_count
    FROM seed_targets
    JOIN rooms room
        ON room.host_user_id = seed_targets.host_user_id
        AND room.title = seed_targets.title
    LEFT JOIN participations participation ON participation.room_id = room.id
    GROUP BY
        room.id,
        seed_targets.game_id,
        seed_targets.room_type,
        seed_targets.description,
        seed_targets.start_at
)
UPDATE rooms room
SET
    game_id = existing_seed_rooms.game_id,
    room_type = existing_seed_rooms.room_type,
    description = existing_seed_rooms.description,
    experience_level = 'BEGINNER_WELCOME',
    is_rulemaster_led = true,
    region = '홍대',
    capacity = LEAST(10, GREATEST(6, existing_seed_rooms.active_participant_count)),
    active_participant_count = existing_seed_rooms.active_participant_count,
    start_at = existing_seed_rooms.start_at,
    place = '홍대입구역 보드게임 카페',
    status = CASE WHEN existing_seed_rooms.active_participant_count >= 6 THEN 'CLOSED' ELSE 'RECRUITING' END,
    version = room.version + 1,
    updated_at = CURRENT_TIMESTAMP
FROM existing_seed_rooms
WHERE room.id = existing_seed_rooms.id;

WITH seed_host AS (
    SELECT id
    FROM users
    WHERE email = 'local.seed.host@albammate.local'
),
selected_games AS (
    SELECT
        candidate.id,
        ROW_NUMBER() OVER (ORDER BY candidate.source_order, candidate.id)::int AS seed_index
    FROM (
        SELECT id, 0 AS source_order
        FROM games
        WHERE bgg_id > 0
        UNION ALL
        SELECT id, 1 AS source_order
        FROM games
        WHERE bgg_id BETWEEN -9000000030 AND -9000000001
    ) candidate
    ORDER BY candidate.source_order, candidate.id
    LIMIT 30
),
seed_targets AS (
    SELECT
        seed_host.id AS host_user_id,
        selected_games.id AS game_id,
        'GAME_FOCUSED' AS room_type,
        format('[LOCAL] 게임 중심 모임 %s', lpad(selected_games.seed_index::text, 2, '0')) AS title,
        '로컬 개발용 게임 중심 모임입니다. 편하게 참여해 보세요.' AS description,
        CURRENT_TIMESTAMP + selected_games.seed_index * INTERVAL '1 day' + INTERVAL '4 hours' AS start_at
    FROM seed_host
    CROSS JOIN selected_games
    UNION ALL
    SELECT
        seed_host.id,
        NULL,
        'PERSON_FOCUSED',
        format('[LOCAL] 사람 중심 모임 %s', lpad(generated.seed_index::text, 2, '0')),
        '로컬 개발용 사람 중심 모임입니다. 게임은 현장에서 함께 정해요.',
        CURRENT_TIMESTAMP + generated.seed_index * INTERVAL '1 day' + INTERVAL '18 hours'
    FROM seed_host
    CROSS JOIN generate_series(1, 30) AS generated(seed_index)
)
INSERT INTO rooms (
    game_id, host_user_id, room_type, title, description, experience_level,
    is_rulemaster_led, region, capacity, active_participant_count, start_at,
    place, status, version, created_at, updated_at
)
SELECT
    seed_targets.game_id,
    seed_targets.host_user_id,
    seed_targets.room_type,
    seed_targets.title,
    seed_targets.description,
    'BEGINNER_WELCOME',
    true,
    '홍대',
    6,
    0,
    seed_targets.start_at,
    '홍대입구역 보드게임 카페',
    'RECRUITING',
    0,
    CURRENT_TIMESTAMP,
    CURRENT_TIMESTAMP
FROM seed_targets
WHERE NOT EXISTS (
    SELECT 1
    FROM rooms room
    WHERE room.host_user_id = seed_targets.host_user_id
        AND room.title = seed_targets.title
);
