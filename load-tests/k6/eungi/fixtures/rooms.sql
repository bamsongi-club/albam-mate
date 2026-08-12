\set ON_ERROR_STOP on

-- 채팅 부하테스트 fixture. 방과 계정, 참가 관계, 채팅 이력을 한 트랜잭션으로 만든다.
--
-- 필요한 psql 변수
--   run_id              실행 격리 키. 같은 DB에서 동시에 돌아도 섞이지 않게 한다
--   room_count          만들 방 수
--   accounts_per_room   방마다 만들 계정 수. 1명은 호스트, 나머지는 참가자
--   messages_per_room   방마다 넣을 채팅 메시지 수
--   password_hash       계정 비밀번호의 bcrypt 해시. '{bcrypt}' 접두사가 있어야 한다
--   password            위 해시의 평문. 마지막 SELECT가 내보내는 fixture에만 쓴다
--
-- 마지막 SELECT 가 k6 credential fixture(JSON)를 내보낸다. 실제 비밀번호가 담기므로
-- 출력을 저장소에 커밋하지 않는다.

-- cleanup.sql 이 제목·이메일 같은 사용자 입력 가능한 값으로 대상을 추측하지 않도록,
-- 이 SQL 이 실제로 만든 방·계정의 ID를 run_id별로 기록한다. fixture 데이터와 registry
-- 행은 아래 BEGIN 안에서 함께 커밋되므로 중간 실패 시 registry만 남지 않는다.
CREATE TABLE IF NOT EXISTS chat_k6_fixture_registry (
    run_id text NOT NULL
        CONSTRAINT chat_k6_fixture_registry_run_id_format
        CHECK (run_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    resource_type text NOT NULL
        CONSTRAINT chat_k6_fixture_registry_resource_type
        CHECK (resource_type IN ('ROOM', 'USER')),
    resource_key text NOT NULL,
    resource_id bigint NOT NULL,
    created_at timestamp with time zone NOT NULL DEFAULT current_timestamp,
    CONSTRAINT pk_chat_k6_fixture_registry
        PRIMARY KEY (run_id, resource_type, resource_key),
    CONSTRAINT uq_chat_k6_fixture_registry_resource
        UNIQUE (resource_type, resource_id)
);

CREATE TABLE IF NOT EXISTS chat_k6_fixture_run_parameters (
    run_id text NOT NULL
        CONSTRAINT chat_k6_fixture_run_parameters_run_id_format
        CHECK (run_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    room_count integer NOT NULL
        CONSTRAINT chat_k6_fixture_run_parameters_room_count_range CHECK (room_count BETWEEN 1 AND 100),
    accounts_per_room integer NOT NULL
        CONSTRAINT chat_k6_fixture_run_parameters_accounts_range CHECK (accounts_per_room BETWEEN 7 AND 11),
    messages_per_room integer NOT NULL
        CONSTRAINT chat_k6_fixture_run_parameters_messages_range CHECK (messages_per_room BETWEEN 2 AND 5000),
    created_at timestamp with time zone NOT NULL DEFAULT current_timestamp,
    CONSTRAINT pk_chat_k6_fixture_run_parameters PRIMARY KEY (run_id)
);

BEGIN;

CREATE TEMP TABLE chat_fixture_parameters (
    run_id text NOT NULL
        CONSTRAINT chat_fixture_run_id_format
        CHECK (run_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$'),
    room_count integer NOT NULL
        CONSTRAINT chat_fixture_room_count_range CHECK (room_count BETWEEN 1 AND 100),
    -- 방 정원이 10이고 호스트는 참가자로 세지 않으므로 계정은 최대 11명까지다.
    -- 최소 7명은 rate-limit-room 이 방 한도를 채우는 데 필요한 수다.
    accounts_per_room integer NOT NULL
        CONSTRAINT chat_fixture_accounts_range CHECK (accounts_per_room BETWEEN 7 AND 11),
    messages_per_room integer NOT NULL
        CONSTRAINT chat_fixture_messages_range CHECK (messages_per_room BETWEEN 2 AND 5000),
    password_hash text NOT NULL
        CONSTRAINT chat_fixture_password_hash_prefix CHECK (password_hash LIKE '{bcrypt}$%')
) ON COMMIT DROP;

INSERT INTO chat_fixture_parameters (
    run_id, room_count, accounts_per_room, messages_per_room, password_hash)
VALUES (
    :'run_id', :'room_count'::integer, :'accounts_per_room'::integer,
    :'messages_per_room'::integer, :'password_hash');

-- 같은 run_id의 seed와 cleanup이 서로의 중간 상태를 보지 않도록 트랜잭션 동안 직렬화한다.
SELECT pg_advisory_xact_lock(hashtext(run_id)::bigint)
FROM chat_fixture_parameters;

INSERT INTO chat_k6_fixture_run_parameters (run_id, room_count, accounts_per_room, messages_per_room)
SELECT run_id, room_count, accounts_per_room, messages_per_room
FROM chat_fixture_parameters
ON CONFLICT (run_id) DO NOTHING;

DO $validate_fixture_parameters$
DECLARE
    parameters chat_fixture_parameters%ROWTYPE;
BEGIN
    SELECT * INTO parameters FROM chat_fixture_parameters;
    IF EXISTS (
        SELECT 1
        FROM chat_k6_fixture_run_parameters existing
        WHERE existing.run_id = parameters.run_id
          AND (existing.room_count <> parameters.room_count
              OR existing.accounts_per_room <> parameters.accounts_per_room
              OR existing.messages_per_room <> parameters.messages_per_room)
    ) THEN
        RAISE EXCEPTION 'fixture run % already exists with different seed parameters', parameters.run_id;
    END IF;
END
$validate_fixture_parameters$;

DO $seed$
DECLARE
    parameters chat_fixture_parameters%ROWTYPE;
    room_index int;
    account_index int;
    message_index int;
    host_id bigint;
    member_id bigint;
    seeded_room_id bigint;
    seeded_chat_room_id bigint;
    registered_id bigint;
    room_title text;
    registry_key text;
    user_email text;
BEGIN
    SELECT * INTO parameters FROM chat_fixture_parameters;

    FOR room_index IN 1..parameters.room_count LOOP
        room_title := format('k6-%s-room-%s', parameters.run_id, room_index);

        FOR account_index IN 1..parameters.accounts_per_room LOOP
            user_email := format('k6.%s.chat.r%s.u%s@example.com',
                                 parameters.run_id, room_index, account_index);
            registry_key := format('room-%s-user-%s', room_index, account_index);
            SELECT registry.resource_id INTO registered_id
            FROM chat_k6_fixture_registry registry
            WHERE registry.run_id = parameters.run_id
              AND registry.resource_type = 'USER'
              AND registry.resource_key = registry_key;

            IF registered_id IS NULL THEN
                SELECT id INTO member_id FROM users WHERE email = user_email;
                IF member_id IS NOT NULL THEN
                    RAISE EXCEPTION 'fixture user email is already used by an unregistered user: %', user_email;
                END IF;

                INSERT INTO users (email, password_hash, nickname, created_at, updated_at)
                VALUES (
                    user_email,
                    parameters.password_hash,
                    format('k6-%s-r%s-u%s', left(parameters.run_id, 20), room_index, account_index),
                    now(), now())
                RETURNING id INTO member_id;

                INSERT INTO chat_k6_fixture_registry (run_id, resource_type, resource_key, resource_id)
                VALUES (parameters.run_id, 'USER', registry_key, member_id);
            ELSE
                member_id := registered_id;
                IF NOT EXISTS (SELECT 1 FROM users WHERE id = member_id AND email = user_email) THEN
                    RAISE EXCEPTION 'fixture user registry is stale for %', user_email;
                END IF;
                UPDATE users SET password_hash = parameters.password_hash, updated_at = now()
                WHERE id = member_id;
            END IF;

            IF account_index = 1 THEN
                host_id := member_id;
            END IF;
        END LOOP;

        registry_key := format('room-%s', room_index);
        SELECT registry.resource_id INTO registered_id
        FROM chat_k6_fixture_registry registry
        WHERE registry.run_id = parameters.run_id
          AND registry.resource_type = 'ROOM'
          AND registry.resource_key = registry_key;

        IF registered_id IS NULL THEN
            SELECT id INTO seeded_room_id FROM rooms WHERE title = room_title;
            IF seeded_room_id IS NOT NULL THEN
                RAISE EXCEPTION 'fixture room title is already used by an unregistered room: %', room_title;
            END IF;

            -- host 는 participations 행을 갖지 않고 active_participant_count 에도 들어가지
            -- 않는다. Room.create 가 0 에서 시작해 참가자마다 증가시키는 규칙과 같다.
            --
            -- start_at 이 지난 시각이면 상태 보정이 방을 FINISHED 로 바꾸고 채팅이 닫혀
            -- 전송이 403 이 된다. 측정 창보다 충분히 뒤로 잡는다.
            INSERT INTO rooms (
                game_id, host_user_id, room_type, title, description, experience_level,
                is_rulemaster_led, capacity, active_participant_count, start_at, place,
                status, version, created_at, updated_at)
            VALUES (
                NULL, host_id, 'PERSON_FOCUSED', room_title,
                'k6 부하테스트 전용', 'ALL_LEVELS', false, 10,
                parameters.accounts_per_room - 1,
                now() + interval '30 days', 'k6', 'RECRUITING', 0, now(), now())
            RETURNING id INTO seeded_room_id;
            INSERT INTO chat_k6_fixture_registry (run_id, resource_type, resource_key, resource_id)
            VALUES (parameters.run_id, 'ROOM', registry_key, seeded_room_id);
        ELSE
            seeded_room_id := registered_id;
            IF NOT EXISTS (SELECT 1 FROM rooms WHERE id = seeded_room_id AND title = room_title) THEN
                RAISE EXCEPTION 'fixture room registry is stale for %', room_title;
            END IF;
        END IF;

        FOR account_index IN 2..parameters.accounts_per_room LOOP
            registry_key := format('room-%s-user-%s', room_index, account_index);
            SELECT registry.resource_id INTO member_id
            FROM chat_k6_fixture_registry registry
            WHERE registry.run_id = parameters.run_id
              AND registry.resource_type = 'USER'
              AND registry.resource_key = registry_key;
            IF member_id IS NULL THEN
                RAISE EXCEPTION 'fixture user registry is missing for room % account %', room_index, account_index;
            END IF;
            INSERT INTO participations (room_id, user_id, status, joined_at, created_at, updated_at)
            VALUES (seeded_room_id, member_id, 'ACTIVE', now(), now(), now())
            ON CONFLICT ON CONSTRAINT uq_participations_room_user DO NOTHING;
        END LOOP;

        SELECT id INTO seeded_chat_room_id FROM chat_rooms WHERE room_id = seeded_room_id;
        IF seeded_chat_room_id IS NULL THEN
            INSERT INTO chat_rooms (room_id, created_at, updated_at)
            VALUES (seeded_room_id, now(), now())
            RETURNING id INTO seeded_chat_room_id;
        END IF;

        -- 이력 조회는 profile 의 모든 방에 VU 를 분산하므로 방마다 채워야 한다.
        -- 한 방만 채우면 나머지 방의 VU 가 최소 메시지 조건을 못 넘긴다.
        FOR message_index IN 1..parameters.messages_per_room LOOP
            INSERT INTO chat_messages (
                chat_room_id, sender_user_id, client_message_id, content, created_at)
            VALUES (
                seeded_chat_room_id, host_id,
                format('k6-%s-seed-%s-%s', parameters.run_id, room_index, message_index),
                format('k6 시드 메시지 %s', message_index),
                now() - make_interval(
                    secs => (parameters.messages_per_room - message_index)))
            ON CONFLICT ON CONSTRAINT uq_chat_messages_room_sender_client_message DO NOTHING;
        END LOOP;
    END LOOP;
END
$seed$;

COMMIT;

-- k6 credential fixture. 호스트는 participations 행이 없으므로 rooms.host_user_id 에서,
-- 참가자는 participations 에서 각각 모은다. 방 제목을 잘라 쓰지 않아 run_id 길이에
-- 영향받지 않는다.
WITH seeded_rooms AS (
    SELECT fixture.resource_id AS room_id, room.host_user_id,
           dense_rank() OVER (ORDER BY fixture.resource_id) AS room_number
    FROM chat_k6_fixture_registry fixture
    JOIN rooms room ON room.id = fixture.resource_id
    WHERE fixture.run_id = :'run_id'
      AND fixture.resource_type = 'ROOM'
), members AS (
    SELECT room.room_id, room.room_number, 0 AS ordinal, account.email
    FROM seeded_rooms room
    JOIN users account ON account.id = room.host_user_id
    UNION ALL
    SELECT
        room.room_id,
        room.room_number,
        row_number() OVER (PARTITION BY room.room_id ORDER BY account.id)::int,
        account.email
    FROM seeded_rooms room
    JOIN participations participation
        ON participation.room_id = room.room_id AND participation.status = 'ACTIVE'
    JOIN users account ON account.id = participation.user_id
), tail AS (
    SELECT message.id
    FROM chat_messages message
    JOIN chat_rooms chat_room ON chat_room.id = message.chat_room_id
    JOIN seeded_rooms room ON room.room_id = chat_room.room_id
    WHERE room.room_number = 1
    ORDER BY message.id DESC
    LIMIT 3
)
SELECT jsonb_pretty(jsonb_build_object(
    'users', (SELECT jsonb_agg(jsonb_build_object(
        'label', CASE WHEN ordinal = 0
            THEN format('room-%s-host', room_number)
            ELSE format('room-%s-participant-%s', room_number, ordinal) END,
        'email', email,
        'password', :'password',
        'roomId', room_id) ORDER BY room_id, ordinal) FROM members),
    'profiles', jsonb_build_object('hot-room', jsonb_build_object(
        'roomIds', (SELECT jsonb_agg(DISTINCT room_id) FROM seeded_rooms))),
    'reconnect', jsonb_build_object(
        'userLabel', 'room-1-host',
        'afterMessageId', (SELECT min(id) FROM tail),
        'expectedMessageIds', (SELECT jsonb_agg(id ORDER BY id)
                               FROM tail WHERE id > (SELECT min(id) FROM tail)))
));
