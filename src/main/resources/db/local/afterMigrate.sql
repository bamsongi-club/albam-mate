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
game_title(seed_index, title) AS (
    VALUES
        (1, '오늘 저녁 같이 한 판 하실 분'),
        (2, '초보 환영, 룰 알려드려요'),
        (3, '주말 오후 보드게임 모임'),
        (4, '전략 게임 좋아하는 분 모여요'),
        (5, '가볍게 두세 판 돌려요'),
        (6, '퇴근하고 한 판 어때요'),
        (7, '처음 오셔도 편하게 오세요'),
        (8, '시간 여유 있게 잡았어요'),
        (9, '이 게임 배워보고 싶은 분'),
        (10, '같이 룰 익혀볼까요'),
        (11, '경험자끼리 제대로 한 판'),
        (12, '금요일 밤에 모여요'),
        (13, '토요일 낮 모임입니다'),
        (14, '일요일 오후에 느긋하게'),
        (15, '평일 저녁 정기 모임'),
        (16, '두 명이면 바로 시작해요'),
        (17, '네 명 모이면 시작합니다'),
        (18, '자리 넉넉해요 편하게'),
        (19, '설명 천천히 해드릴게요'),
        (20, '처음이라도 괜찮아요'),
        (21, '오래 해본 분 환영합니다'),
        (22, '한 판만 짧게 하실 분'),
        (23, '길게 붙잡고 해봐요'),
        (24, '조용한 자리 좋아하면'),
        (25, '이야기하면서 가볍게'),
        (26, '새로 나온 거 해보실 분'),
        (27, '다시 꺼내서 해봐요'),
        (28, '인원 적어도 진행해요'),
        (29, '늦게 오셔도 기다려요'),
        (30, '뭐 할지는 만나서 정해요')
),
person_title(seed_index, title) AS (
    VALUES
        (1, '퇴근 후 가볍게 한 판'),
        (2, '초보 환영 보드게임 모임'),
        (3, '주말 오후에 느긋하게'),
        (4, '처음 오셔도 편한 자리'),
        (5, '전략 게임 좋아하는 분들'),
        (6, '파티 게임 위주로 놀아요'),
        (7, '조용히 집중해서 하는 모임'),
        (8, '게임은 만나서 함께 골라요'),
        (9, '둘이 하는 게임 즐기는 분'),
        (10, '저녁 먹고 한 판 어때요'),
        (11, '금요일 밤 보드게임'),
        (12, '토요일 낮에 모여요'),
        (13, '일요일 오후 모임'),
        (14, '협력 게임 해보고 싶은 분'),
        (15, '정체 숨기는 게임 좋아하면'),
        (16, '짧은 게임 여러 판 돌리기'),
        (17, '긴 게임 하나 제대로'),
        (18, '카드 게임 위주 모임'),
        (19, '보드게임 입문 같이 해요'),
        (20, '룰 설명 천천히 해드려요'),
        (21, '직장인 저녁 모임'),
        (22, '학생 환영 오후 모임'),
        (23, '처음 만나도 어색하지 않게'),
        (24, '매주 같은 시간에 모여요'),
        (25, '새로 나온 게임 해보기'),
        (26, '오래 사랑받은 게임만 골라서'),
        (27, '경험자끼리 깊게 한 판'),
        (28, '인원 적어도 그냥 진행해요'),
        (29, '조금 늦게 오셔도 괜찮아요'),
        (30, '뭐 할지 정하기 어려우면')
),
seed_targets AS (
    SELECT
        raw.host_user_id,
        raw.game_id,
        raw.room_type,
        raw.title,
        raw.description,
        raw.start_at,
        CASE raw.seed_index % 6
            WHEN 0 THEN '홍대입구역 3번 출구 도보 5분'
            WHEN 1 THEN '합정역 근처 보드게임 카페'
            WHEN 2 THEN '홍대 걷고싶은거리 인근'
            WHEN 3 THEN '홍익대 정문 앞'
            WHEN 4 THEN '상수역 1번 출구 근처'
            ELSE '홍대입구역 9번 출구 앞'
        END AS place,
        CASE raw.seed_index % 3
            WHEN 0 THEN 'BEGINNER_WELCOME'
            WHEN 1 THEN 'ALL_LEVELS'
            ELSE 'EXPERIENCED_PREFERRED'
        END AS experience_level,
        raw.seed_index % 3 = 0 AS is_rulemaster_led
    FROM (
        SELECT
            seed_host.id AS host_user_id,
            selected_games.id AS game_id,
            'GAME_FOCUSED' AS room_type,
            selected_games.seed_index AS seed_index,
            game_title.title AS title,
            CASE selected_games.seed_index % 4
                WHEN 0 THEN '룰은 제가 설명해 드릴게요. 처음이어도 부담 없이 오세요.'
                WHEN 1 THEN '가볍게 한두 판 돌리고 이야기 나누는 자리예요.'
                WHEN 2 THEN '시간 여유 있게 잡았어요. 늦으시면 미리 알려주세요.'
                ELSE '해본 분도 처음인 분도 함께 즐길 수 있게 진행해요.'
            END AS description,
            CURRENT_TIMESTAMP + selected_games.seed_index * INTERVAL '1 day' + INTERVAL '4 hours' AS start_at
        FROM seed_host
        CROSS JOIN selected_games
        JOIN game_title ON game_title.seed_index = selected_games.seed_index
        UNION ALL
        SELECT
            seed_host.id,
            NULL,
            'PERSON_FOCUSED',
            person_title.seed_index,
            person_title.title,
            CASE person_title.seed_index % 4
                WHEN 0 THEN '무슨 게임을 할지는 모여서 함께 정해요.'
                WHEN 1 THEN '인원 보고 그 자리에서 맞는 게임을 골라요.'
                WHEN 2 THEN '가볍게 이야기하다가 자연스럽게 시작해요.'
                ELSE '하고 싶은 게임 있으면 가져오셔도 좋아요.'
            END,
            CURRENT_TIMESTAMP + person_title.seed_index * INTERVAL '1 day' + INTERVAL '18 hours'
        FROM seed_host
        CROSS JOIN person_title
    ) raw
),
existing_seed_rooms AS (
    SELECT
        room.id,
        seed_targets.game_id,
        seed_targets.room_type,
        seed_targets.description,
        seed_targets.start_at,
        seed_targets.place,
        seed_targets.experience_level,
        seed_targets.is_rulemaster_led,
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
        seed_targets.start_at,
        seed_targets.place,
        seed_targets.experience_level,
        seed_targets.is_rulemaster_led
)
UPDATE rooms room
SET
    game_id = existing_seed_rooms.game_id,
    room_type = existing_seed_rooms.room_type,
    description = existing_seed_rooms.description,
    experience_level = existing_seed_rooms.experience_level,
    is_rulemaster_led = existing_seed_rooms.is_rulemaster_led,
    region = '홍대',
    capacity = LEAST(10, GREATEST(6, existing_seed_rooms.active_participant_count)),
    active_participant_count = existing_seed_rooms.active_participant_count,
    start_at = existing_seed_rooms.start_at,
    place = existing_seed_rooms.place,
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
game_title(seed_index, title) AS (
    VALUES
        (1, '오늘 저녁 같이 한 판 하실 분'),
        (2, '초보 환영, 룰 알려드려요'),
        (3, '주말 오후 보드게임 모임'),
        (4, '전략 게임 좋아하는 분 모여요'),
        (5, '가볍게 두세 판 돌려요'),
        (6, '퇴근하고 한 판 어때요'),
        (7, '처음 오셔도 편하게 오세요'),
        (8, '시간 여유 있게 잡았어요'),
        (9, '이 게임 배워보고 싶은 분'),
        (10, '같이 룰 익혀볼까요'),
        (11, '경험자끼리 제대로 한 판'),
        (12, '금요일 밤에 모여요'),
        (13, '토요일 낮 모임입니다'),
        (14, '일요일 오후에 느긋하게'),
        (15, '평일 저녁 정기 모임'),
        (16, '두 명이면 바로 시작해요'),
        (17, '네 명 모이면 시작합니다'),
        (18, '자리 넉넉해요 편하게'),
        (19, '설명 천천히 해드릴게요'),
        (20, '처음이라도 괜찮아요'),
        (21, '오래 해본 분 환영합니다'),
        (22, '한 판만 짧게 하실 분'),
        (23, '길게 붙잡고 해봐요'),
        (24, '조용한 자리 좋아하면'),
        (25, '이야기하면서 가볍게'),
        (26, '새로 나온 거 해보실 분'),
        (27, '다시 꺼내서 해봐요'),
        (28, '인원 적어도 진행해요'),
        (29, '늦게 오셔도 기다려요'),
        (30, '뭐 할지는 만나서 정해요')
),
person_title(seed_index, title) AS (
    VALUES
        (1, '퇴근 후 가볍게 한 판'),
        (2, '초보 환영 보드게임 모임'),
        (3, '주말 오후에 느긋하게'),
        (4, '처음 오셔도 편한 자리'),
        (5, '전략 게임 좋아하는 분들'),
        (6, '파티 게임 위주로 놀아요'),
        (7, '조용히 집중해서 하는 모임'),
        (8, '게임은 만나서 함께 골라요'),
        (9, '둘이 하는 게임 즐기는 분'),
        (10, '저녁 먹고 한 판 어때요'),
        (11, '금요일 밤 보드게임'),
        (12, '토요일 낮에 모여요'),
        (13, '일요일 오후 모임'),
        (14, '협력 게임 해보고 싶은 분'),
        (15, '정체 숨기는 게임 좋아하면'),
        (16, '짧은 게임 여러 판 돌리기'),
        (17, '긴 게임 하나 제대로'),
        (18, '카드 게임 위주 모임'),
        (19, '보드게임 입문 같이 해요'),
        (20, '룰 설명 천천히 해드려요'),
        (21, '직장인 저녁 모임'),
        (22, '학생 환영 오후 모임'),
        (23, '처음 만나도 어색하지 않게'),
        (24, '매주 같은 시간에 모여요'),
        (25, '새로 나온 게임 해보기'),
        (26, '오래 사랑받은 게임만 골라서'),
        (27, '경험자끼리 깊게 한 판'),
        (28, '인원 적어도 그냥 진행해요'),
        (29, '조금 늦게 오셔도 괜찮아요'),
        (30, '뭐 할지 정하기 어려우면')
),
seed_targets AS (
    SELECT
        raw.host_user_id,
        raw.game_id,
        raw.room_type,
        raw.title,
        raw.description,
        raw.start_at,
        CASE raw.seed_index % 6
            WHEN 0 THEN '홍대입구역 3번 출구 도보 5분'
            WHEN 1 THEN '합정역 근처 보드게임 카페'
            WHEN 2 THEN '홍대 걷고싶은거리 인근'
            WHEN 3 THEN '홍익대 정문 앞'
            WHEN 4 THEN '상수역 1번 출구 근처'
            ELSE '홍대입구역 9번 출구 앞'
        END AS place,
        CASE raw.seed_index % 3
            WHEN 0 THEN 'BEGINNER_WELCOME'
            WHEN 1 THEN 'ALL_LEVELS'
            ELSE 'EXPERIENCED_PREFERRED'
        END AS experience_level,
        raw.seed_index % 3 = 0 AS is_rulemaster_led
    FROM (
        SELECT
            seed_host.id AS host_user_id,
            selected_games.id AS game_id,
            'GAME_FOCUSED' AS room_type,
            selected_games.seed_index AS seed_index,
            game_title.title AS title,
            CASE selected_games.seed_index % 4
                WHEN 0 THEN '룰은 제가 설명해 드릴게요. 처음이어도 부담 없이 오세요.'
                WHEN 1 THEN '가볍게 한두 판 돌리고 이야기 나누는 자리예요.'
                WHEN 2 THEN '시간 여유 있게 잡았어요. 늦으시면 미리 알려주세요.'
                ELSE '해본 분도 처음인 분도 함께 즐길 수 있게 진행해요.'
            END AS description,
            CURRENT_TIMESTAMP + selected_games.seed_index * INTERVAL '1 day' + INTERVAL '4 hours' AS start_at
        FROM seed_host
        CROSS JOIN selected_games
        JOIN game_title ON game_title.seed_index = selected_games.seed_index
        UNION ALL
        SELECT
            seed_host.id,
            NULL,
            'PERSON_FOCUSED',
            person_title.seed_index,
            person_title.title,
            CASE person_title.seed_index % 4
                WHEN 0 THEN '무슨 게임을 할지는 모여서 함께 정해요.'
                WHEN 1 THEN '인원 보고 그 자리에서 맞는 게임을 골라요.'
                WHEN 2 THEN '가볍게 이야기하다가 자연스럽게 시작해요.'
                ELSE '하고 싶은 게임 있으면 가져오셔도 좋아요.'
            END,
            CURRENT_TIMESTAMP + person_title.seed_index * INTERVAL '1 day' + INTERVAL '18 hours'
        FROM seed_host
        CROSS JOIN person_title
    ) raw
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
    seed_targets.experience_level,
    seed_targets.is_rulemaster_led,
    '홍대',
    6,
    0,
    seed_targets.start_at,
    seed_targets.place,
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

WITH callback_now AS (
    SELECT CURRENT_TIMESTAMP AS value
)
INSERT INTO chat_rooms (room_id, purge_after, messages_purged_at, created_at, updated_at)
SELECT
    rooms.id,
    CASE WHEN rooms.status IN ('CANCELED', 'FINISHED') THEN callback_now.value ELSE NULL END,
    CASE WHEN rooms.status IN ('CANCELED', 'FINISHED') THEN callback_now.value ELSE NULL END,
    callback_now.value,
    callback_now.value
FROM rooms
CROSS JOIN callback_now
WHERE NOT EXISTS (
    SELECT 1
    FROM chat_rooms existing_chat_room
    WHERE existing_chat_room.room_id = rooms.id
);
