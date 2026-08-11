\set ON_ERROR_STOP on

-- 읽기 경로 용량 측정용 알림 백로그를 만든다. 빈 테이블에서 측정하면 미확인 개수 조회가 항상 즉시 끝나
-- 실제 사용 조건을 재현하지 못한다. 이 fixture는 `users.sql`을 먼저 적용한 뒤에만 실행한다.
--
-- 필요한 psql 변수: run_id, user_count, room_count, notifications_per_user, unread_percent

-- 아래 중복 적용 검사와 INSERT는 한 트랜잭션 안에서 원자적으로 실행해야 한다. 자동 커밋으로 나뉘면 같은
-- Run ID의 두 실행이 동시에 검사를 통과한 뒤 서로 다른 source_event_id로 백로그를 두 배 적재할 수 있고,
-- 그 사실이 결과 어디에도 드러나지 않는다. 오류가 나면 ON_ERROR_STOP이 psql을 끝내고 전체가 되돌아간다.
BEGIN;

-- 실행기가 보장하는 입력 계약을 SQL에서도 다시 확인한다. 범위를 벗어난 값이 조용히 0건을 만들거나
-- 지나치게 큰 fixture를 적재하지 못하도록 제약 위반으로 즉시 중단한다.
CREATE TEMP TABLE notification_backlog_parameters (
    run_id text NOT NULL CONSTRAINT notification_backlog_run_id_not_empty CHECK (run_id <> ''),
    user_count integer NOT NULL CONSTRAINT notification_backlog_user_count_range CHECK (user_count BETWEEN 1 AND 20000),
    room_count integer NOT NULL CONSTRAINT notification_backlog_room_count_range CHECK (room_count BETWEEN 1 AND 1000),
    notifications_per_user integer NOT NULL
        CONSTRAINT notification_backlog_per_user_range CHECK (notifications_per_user BETWEEN 1 AND 10000),
    unread_percent integer NOT NULL
        CONSTRAINT notification_backlog_unread_percent_range CHECK (unread_percent BETWEEN 0 AND 100)
) ON COMMIT DROP;

INSERT INTO notification_backlog_parameters (
    run_id, user_count, room_count, notifications_per_user, unread_percent)
VALUES (
    :'run_id', :user_count::integer, :room_count::integer,
    :notifications_per_user::integer, :unread_percent::integer);

-- Run ID 단위 직렬화. 두 번째 실행은 여기서 기다렸다가 중복 적용 검사에서 멈춘다. 트랜잭션 종료와 함께
-- 자동으로 풀리므로 별도 해제가 필요 없다.
SELECT pg_advisory_xact_lock(hashtext('albam-mate-notification-backlog'), hashtext(:'run_id'));

-- 확인용 출력. 두 값이 다르면 바로 아래 검사가 실행을 멈춘다.
SELECT
    count(*) AS matched_fixture_users,
    :user_count::integer AS expected_fixture_users
FROM generate_series(1, :user_count::integer) AS fixture(fixture_index)
JOIN users ON users.email = format('k6.%s.auth.%s@example.com', :'run_id', fixture.fixture_index);

-- 사용자가 없으면 백로그가 조용히 0건이 되므로 여기서 즉시 중단한다. 나누는 값이 집계 결과에 의존해야
-- 플래너가 상수로 접어 항상 실패시키지 않는다. 사용자 수가 맞지 않으면 0으로 나누기 오류로 멈춘다.
SELECT 1 / (CASE WHEN count(*) = :user_count::integer THEN 1 ELSE 0 END) AS fixture_users_verified
FROM generate_series(1, :user_count::integer) AS fixture(fixture_index)
JOIN users ON users.email = format('k6.%s.auth.%s@example.com', :'run_id', fixture.fixture_index);

-- 이 fixture는 멱등하지 않다. 같은 Run ID로 다시 적용하면 방과 알림이 그대로 더 쌓여 측정 조건이 달라진다.
-- 이미 적용된 Run ID면 여기서 멈춘다. 다시 깔아야 하면 새 Run ID를 쓰거나 이 Run ID의 백로그 방과 알림을
-- 먼저 지운다.
SELECT 1 / (CASE WHEN count(*) = 0 THEN 1 ELSE 0 END) AS backlog_not_applied_yet
FROM rooms
WHERE title LIKE format('k6-%s-backlog-%%', left(:'run_id', 30));

WITH host AS (
    SELECT id
    FROM users
    WHERE email = format('k6.%s.auth.1@example.com', :'run_id')
),
-- notifications.room_id는 rooms 외래 키이므로 백로그가 참조할 방을 먼저 만든다. 이 방들은 방 목록 조회
-- 부하에도 실제 행으로 쓰인다.
created_rooms AS (
    INSERT INTO rooms (
        host_user_id, room_type, title, experience_level, is_rulemaster_led, capacity,
        active_participant_count, start_at, place, status, created_at, updated_at)
    SELECT
        host.id,
        'PERSON_FOCUSED',
        format('k6-%s-backlog-%s', left(:'run_id', 30), room_index),
        'ALL_LEVELS',
        false,
        10,
        0,
        clock_timestamp() + INTERVAL '30 day',
        'k6-perf-fixture',
        'RECRUITING',
        clock_timestamp(),
        clock_timestamp()
    FROM host, generate_series(1, :room_count::integer) AS room_index
    RETURNING id
),
backlog_room AS (
    SELECT
        id,
        row_number() OVER (ORDER BY id) - 1 AS room_offset,
        count(*) OVER () AS room_total
    FROM created_rooms
),
recipient AS (
    SELECT
        users.id,
        fixture.fixture_index - 1 AS user_offset
    FROM generate_series(1, :user_count::integer) AS fixture(fixture_index)
    JOIN users ON users.email = format('k6.%s.auth.%s@example.com', :'run_id', fixture.fixture_index)
),
-- source_event_id는 (source_event_id, recipient_user_id) 유일 제약을 받고, relay는 알림을
-- `on conflict do nothing`으로 삽입한다. 따라서 합성 id가 실제 outbox 이벤트 id와 겹치면 이후 진짜 알림이
-- 오류 없이 조용히 버려지고 outbox만 PROCESSED로 남는다. 과거 최대값이 아니라 앞으로 생길 id까지 피하도록
-- 실제 시퀀스가 닿지 않는 높은 값에서 시작하며, 재적용할 때를 위해 기존 최대값도 함께 고려한다.
event_id_base AS (
    SELECT greatest(coalesce(max(source_event_id), 0), 1000000000000::bigint) AS value
    FROM notifications
),
-- 보존 기간 안쪽 89일에 고르게 퍼뜨려 모든 행이 만료 전 상태가 되게 한다. 90일을 넘기면 조회에서 제외되어
-- 측정하려는 스캔 대상에서 빠진다.
backlog AS (
    SELECT
        event_id_base.value
            + recipient.user_offset * :notifications_per_user::bigint
            + item.item_index + 1 AS source_event_id,
        recipient.id AS recipient_user_id,
        backlog_room.id AS room_id,
        (ARRAY['PARTICIPANT_JOINED', 'PARTICIPANT_CANCELED', 'ROOM_CANCELED'])[item.item_index % 3 + 1] AS type,
        (item.item_index % 100) < :unread_percent::integer AS unread,
        clock_timestamp()
            - make_interval(days => 89)
            + make_interval(
                secs => (89 * 86400.0 * item.item_index / :notifications_per_user::integer)::double precision
              ) AS created_at
    FROM recipient
    CROSS JOIN event_id_base
    CROSS JOIN generate_series(0, :notifications_per_user::integer - 1) AS item(item_index)
    JOIN backlog_room ON backlog_room.room_offset = item.item_index % backlog_room.room_total
),
inserted_notifications AS (
    INSERT INTO notifications (
        source_event_id, recipient_user_id, room_id, type, read_at, created_at, recorded_at, expires_at)
    SELECT
        backlog.source_event_id,
        backlog.recipient_user_id,
        backlog.room_id,
        backlog.type,
        CASE
            WHEN backlog.unread THEN NULL
            ELSE LEAST(backlog.created_at + INTERVAL '1 hour', clock_timestamp())
        END,
        backlog.created_at,
        backlog.created_at,
        backlog.created_at + INTERVAL '90 day'
    FROM backlog
    RETURNING 1
)
-- 입력 범위가 유효해도 조인 조건이 달라져 예상보다 적게 적재되면 빈 데이터 측정을 막기 위해 실패한다.
-- 분모가 실제 INSERT 결과에 의존하므로 유효한 경우 플래너가 0 나눗셈을 미리 평가하지 않는다.
SELECT
    count(*) AS inserted_notifications,
    :user_count::bigint * :notifications_per_user::bigint AS expected_notifications,
    1 / (
        CASE
            WHEN count(*) = :user_count::bigint * :notifications_per_user::bigint
                AND count(*) > 0 THEN 1
            ELSE 0
        END
    ) AS notification_backlog_verified
FROM inserted_notifications;

COMMIT;
