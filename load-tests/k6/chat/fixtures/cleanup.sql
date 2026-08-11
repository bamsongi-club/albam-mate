\set ON_ERROR_STOP on

-- 채팅 부하테스트 fixture 정리. rooms.sql 이 만든 방과 계정, 그리고 측정 중에 생긴
-- 파생 행을 지운다.
--
-- 필요한 psql 변수
--   run_id   지울 실행의 격리 키. rooms.sql 에 준 값과 같아야 한다
--
-- 측정이 만든 메시지와 알림도 함께 사라진다. run_id 로 범위를 좁히므로 같은 DB 의
-- 다른 실행이나 실제 데이터는 건드리지 않는다.

BEGIN;

CREATE TEMP TABLE chat_fixture_rooms ON COMMIT DROP AS
    SELECT id FROM rooms WHERE title LIKE format('k6-%s-room-%%', :'run_id');
CREATE TEMP TABLE chat_fixture_users ON COMMIT DROP AS
    SELECT id FROM users WHERE email LIKE format('k6.%s.chat.%%@example.com', :'run_id');
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

COMMIT;
