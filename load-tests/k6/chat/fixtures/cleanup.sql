\set ON_ERROR_STOP on

-- 채팅 부하테스트 fixture 정리. rooms.sql 이 만든 방과 계정, 그리고 측정 중에 생긴
-- 파생 행을 지운다.
--
-- 필요한 psql 변수
--   run_id   지울 실행의 격리 키. rooms.sql 에 준 값과 같아야 한다
--
-- 측정이 만든 메시지와 알림도 함께 사라진다. rooms.sql 이 기록한 ID로 범위를 좁히므로
-- 같은 DB의 다른 실행이나 제목이 같은 실제 데이터는 건드리지 않는다.

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

BEGIN;

CREATE TEMP TABLE chat_fixture_parameters (
    run_id text NOT NULL
        CONSTRAINT chat_fixture_cleanup_run_id_format
        CHECK (run_id ~ '^[a-z0-9][a-z0-9._-]{0,63}$')
) ON COMMIT DROP;

INSERT INTO chat_fixture_parameters (run_id) VALUES (:'run_id');

-- seed와 같은 run_id 잠금을 사용해 seed 트랜잭션의 중간 상태를 보지 않는다.
SELECT pg_advisory_xact_lock(hashtext(run_id)::bigint)
FROM chat_fixture_parameters;

CREATE TEMP TABLE chat_fixture_rooms ON COMMIT DROP AS
    SELECT registry.resource_id AS id
    FROM chat_k6_fixture_registry registry
    WHERE registry.run_id = (SELECT run_id FROM chat_fixture_parameters)
      AND registry.resource_type = 'ROOM';
CREATE TEMP TABLE chat_fixture_users ON COMMIT DROP AS
    SELECT registry.resource_id AS id
    FROM chat_k6_fixture_registry registry
    WHERE registry.run_id = (SELECT run_id FROM chat_fixture_parameters)
      AND registry.resource_type = 'USER';
CREATE TEMP TABLE chat_fixture_chat_rooms ON COMMIT DROP AS
    SELECT id FROM chat_rooms WHERE room_id IN (SELECT id FROM chat_fixture_rooms);

DELETE FROM chat_messages WHERE chat_room_id IN (SELECT id FROM chat_fixture_chat_rooms);
DELETE FROM chat_rooms WHERE id IN (SELECT id FROM chat_fixture_chat_rooms);
DELETE FROM notifications
    WHERE room_id IN (SELECT id FROM chat_fixture_rooms)
    OR recipient_user_id IN (SELECT id FROM chat_fixture_users);
DELETE FROM notification_outbox_recipients
    WHERE recipient_user_id IN (SELECT id FROM chat_fixture_users)
    OR outbox_event_id IN (SELECT id FROM notification_outbox_events
        WHERE room_id IN (SELECT id FROM chat_fixture_rooms));
DELETE FROM notification_outbox_events WHERE room_id IN (SELECT id FROM chat_fixture_rooms);
DELETE FROM room_waitlists
    WHERE room_id IN (SELECT id FROM chat_fixture_rooms)
    OR user_id IN (SELECT id FROM chat_fixture_users);
DELETE FROM participations
    WHERE room_id IN (SELECT id FROM chat_fixture_rooms)
    OR user_id IN (SELECT id FROM chat_fixture_users);
DELETE FROM user_played_games WHERE user_id IN (SELECT id FROM chat_fixture_users);
DELETE FROM social_accounts WHERE user_id IN (SELECT id FROM chat_fixture_users);
DELETE FROM rooms WHERE id IN (SELECT id FROM chat_fixture_rooms);
DELETE FROM users WHERE id IN (SELECT id FROM chat_fixture_users);
DELETE FROM chat_k6_fixture_registry
WHERE run_id = (SELECT run_id FROM chat_fixture_parameters);

COMMIT;
